package com.ckapp.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 息屏/后台响铃兜底（设计见根目录《息屏铃声原生兜底设计方案.md》2026-10-14 实施）。
 *
 * 方案A：按「当前有无计时中的学习块」持 PARTIAL_WAKE_LOCK，给网页定时器续命——CPU 不休眠，
 *   phone.js 的 setTimeout 铃声链原样到点。网页不可见（切后台）时网页自己会上报 false。
 * 方案B：网页排轮次的同刻 armRing(atMs, sound, bk) → AlarmManager.setAndAllowWhileIdle 到点唤醒
 *   → RingPlayService 播音频（缓存缺失/下载失败用系统提示音兜底）+ 通知 → 播完回调网页 onNativeRing。
 *
 * 分工铁律：业务判定（静音时段/轮数/总开关/当前块）全在网页侧，这里只做「定时+播放+回调」，
 * 不重复实现任何响铃口径。取消必须走 cancelRing()（网页五个取消点都会调），否则网页停了原生还在播。
 *
 * 回执：每响一次写 ck.ringLog（桥键，白名单见 CKAppBridge.ALLOWED_KEYS），网页 init/thrCheck
 * 重建时据此把 played 推进到实际已响轮数，防亮屏后 memo 续排路径补响。
 */
object RingGuard {
    private const val TAG = "RingGuard"
    const val ACTION_FIRE = "com.ckapp.checkin.action.RING_FIRE"
    const val RINGLOG_KEY = "ck.ringLog"
    const val EXTRA_AT = "at"
    const val EXTRA_BK = "bk"
    const val EXTRA_SOUND = "sound"
    const val EXTRA_RING_ID = "ringId"
    private const val PREFS = "ring_guard"
    private const val K_LAST_SOUND = "last_sound"
    private const val REQ_CODE = 1001
    private const val WAKELOCK_TAG = "ckapp:ringguard"
    private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L   // 兜底 2h：忘放也不烧穿电池
    private const val MAX_RINGLOG = 20
    private const val DL_TIMEOUT_S = 10L

    private var ctx: Context? = null
    private var prefs: SharedPreferences? = null
    private val dlPool = Executors.newSingleThreadExecutor()

    /** 原生 → 网页回调（MainActivity 注入 bridge.ring；页面销毁时置 null）。 */
    @Volatile var webRingSink: ((Map<String, Any?>) -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null

    /** 幂等：Activity 创建 / 进程被闹钟重启时都可调用。 */
    fun init(context: Context) {
        if (ctx != null) return
        val app = context.applicationContext
        ctx = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun c(): Context = ctx ?: error("RingGuard.init not called")

    // ---------- 方案A：wake lock 给网页定时器续命 ----------
    fun setTimingActive(on: Boolean) {
        if (on) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = c().getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .also { it.acquire(WAKELOCK_TIMEOUT_MS) }
        }.getOrNull().also { Log.i(TAG, "wakeLock acquired held=${it?.isHeld}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        Log.i(TAG, "wakeLock released")
    }

    // ---------- 方案B：排程 / 取消 ----------

    /**
     * 网页排轮次时同刻调用（thrSoundTick 排 setTimeout 的那一行旁）。
     * atMs=绝对 epoch 毫秒；bk=「分类@开始时刻」块键（写回执+网页对账用）。
     */
    fun armRing(atMs: Double, sound: String, bk: String) {
        val at = atMs.toLong()
        if (at <= 0L) return
        initIfPossible()
        ensureSoundCached(sound)
        val am = c().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, firePendingIntent(at, sound, bk)) }
            .onFailure { Log.w(TAG, "armRing failed: ${it.message}") }
        Log.i(TAG, "armRing at=$at sound=$sound bk=$bk")
    }

    /** 网页五个取消点（两键/换块/块结束/轮数用尽）都必须调：disarm 闹钟 + 停正在播的声音。 */
    fun cancelRing() {
        initIfPossible()
        val am = c().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(firePendingIntent(0L, "", "")) }
        RingPlayService.stop(c())
        Log.i(TAG, "cancelRing")
    }

    /** RingReceiver 到点：写回执 → 起播放服务（SyncService 常驻前台，进程已在前台，允许再起 FGS）。 */
    fun onAlarmFired(atMs: Long, bk: String, sound: String) {
        initIfPossible()
        val id = appendRingLog(bk, atMs)
        val svc = Intent(c(), RingPlayService::class.java).apply {
            putExtra(EXTRA_AT, atMs)
            putExtra(EXTRA_BK, bk)
            putExtra(EXTRA_SOUND, sound)
            putExtra(EXTRA_RING_ID, id)
        }
        runCatching { androidx.core.content.ContextCompat.startForegroundService(c(), svc) }
            .onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
        Log.i(TAG, "onAlarmFired at=$atMs bk=$bk sound=$sound id=$id")
    }

    /** 播完回调网页（bridge.ring → window.CKApp.onNativeRing）。 */
    fun notifyWebRang(bk: String, atMs: Long, ringId: Long) {
        webRingSink?.invoke(mapOf("type" to "nativeRing", "bk" to bk, "at" to atMs, "id" to ringId))
    }

    private fun initIfPossible() {
        if (ctx == null) Log.w(TAG, "init not called before use")
    }

    // PendingIntent 只用 action+requestCode 匹配（extras 不参与相等性），cancel 才能对上。
    private fun firePendingIntent(atMs: Long, sound: String, bk: String): PendingIntent {
        val i = Intent(ACTION_FIRE).apply {
            setPackage(c().packageName)
            putExtra(EXTRA_AT, atMs)
            putExtra(EXTRA_SOUND, sound)
            putExtra(EXTRA_BK, bk)
        }
        return PendingIntent.getBroadcast(
            c(), REQ_CODE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------- 音频缓存（静态路由 /sounds/ 无鉴权，直接拉） ----------
    internal fun soundFileFor(name: String): File? {
        if (name.isBlank()) return null
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(c().filesDir, "ring"), safe)
    }

    /** 缺失才下载；家长换了提示音（名字变了）先清旧缓存。失败静默：响的时候用系统提示音兜底。 */
    fun ensureSoundCached(name: String) {
        if (name.isBlank()) return
        val f = soundFileFor(name) ?: return
        if (f.exists()) return
        if (prefs?.getString(K_LAST_SOUND, "") != name) {
            runCatching { File(c().filesDir, "ring").listFiles()?.forEach { it.delete() } }
            prefs?.edit()?.putString(K_LAST_SOUND, name)?.apply()
        }
        val base = PairingStore(c()).baseUrl ?: return
        dlPool.execute {
            runCatching {
                val req = okhttp3.Request.Builder().url("$base/sounds/$name").build()
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(DL_TIMEOUT_S, TimeUnit.SECONDS)
                    .readTimeout(DL_TIMEOUT_S, TimeUnit.SECONDS)
                    .build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val bytes = resp.body?.byteStream() ?: return@use
                        f.parentFile?.mkdirs()
                        f.outputStream().use { out -> bytes.copyTo(out) }
                        Log.i(TAG, "sound cached: $name (${f.length()}B)")
                    }
            }.onFailure { Log.w(TAG, "sound download failed: ${it.message}") }
        }
    }

    // ---------- ringLog 回执（网页对账：亮屏不补响） ----------
    private fun readRingLog(): String = CKAppBridge.getDraftRaw(c(), RINGLOG_KEY)

    /** 追加一条回执，返回其 id（网页 onNativeRing 与 reconcileRingLog 都凭 id 去重）。 */
    private fun appendRingLog(bk: String, atMs: Long): Long {
        val id = System.currentTimeMillis()
        val arr = try { JSONArray(readRingLog()) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("bk", bk).put("at", atMs).put("id", id))
        while (arr.length() > MAX_RINGLOG) arr.remove(0)
        CKAppBridge.writeDraftRaw(c(), RINGLOG_KEY, arr.toString())
        return id
    }
}
