package com.ckapp.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 方案B 播放服务：AlarmManager 到点后由 RingReceiver 拉起，前台服务内 MediaPlayer 播提示音
 * （缓存音频优先，缺失/下载失败用系统默认闹钟音），同时挂一条可点的常驻通知。
 * 播完回调网页 onNativeRing（推进轮次/排下一轮），随后 stopSelf。
 *
 * 儿童模式/息屏加固（2026-10-15）：
 *  - 音频属性设为 USAGE_ALARM：锁屏/儿童模式/DND 下仍允许出声（普通媒体流在息屏会被静音）。
 *  - 持 ACQUIRE_CAUSES_WAKEUP 的 wake lock：闹钟一响即点亮屏幕，确保用户看到 + 听到。
 *  - 提示音循环播放 5 秒后强停，避免漏听或一直响。
 */
class RingPlayService : Service() {

    companion object {
        private const val CHANNEL = "ckapp_ring"
        private const val NOTIF_ID = 2
        private const val RING_MS = 5000L

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RingPlayService::class.java)) }
        }
    }

    private var player: MediaPlayer? = null
    private var done = false
    private var ringBk = ""
    private var ringAt = 0L
    private var ringId = 0L
    private var wl: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        RingGuard.init(this)
        createChannel()
        // 息屏响铃：持「点亮屏幕」的 wake lock，闹钟一响就把屏幕点亮（儿童模式息屏也能看到/听到）
        val pm = getSystemService(PowerManager::class.java)
        wl = runCatching {
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ckapp:ring"
            ).also { it.acquire(60_000L) }
        }.getOrNull()
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.ring_title))
            .setContentText(getString(R.string.ring_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ringAt = intent?.getLongExtra(RingGuard.EXTRA_AT, 0L) ?: 0L
        ringBk = intent?.getStringExtra(RingGuard.EXTRA_BK) ?: ""
        val sound = intent?.getStringExtra(RingGuard.EXTRA_SOUND) ?: ""
        ringId = intent?.getLongExtra(RingGuard.EXTRA_RING_ID, 0L) ?: 0L
        play(sound) { finishOnce() }
        return START_NOT_STICKY
    }

    /** 缓存音频 → 系统默认闹钟音 → 静默；任何异常都走完成回调，不挂住服务。 */
    private fun play(sound: String, done: () -> Unit) {
        try {
            val cached = RingGuard.soundFileFor(sound)?.takeIf { it.exists() }
            val uri: Uri = when {
                cached != null -> Uri.fromFile(cached)
                else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            player = MediaPlayer().apply {
                // 闹钟优先级音频流：息屏/锁屏/儿童模式下仍允许出声
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RingPlayService, uri)
                isLooping = true   // 循环播 5s 确保被听到
                setVolume(1.0f, 1.0f)
                setOnErrorListener { _, _, _ -> done(); true }
                prepare()
                start()
            }
            // 5s 后强停，避免一直响
            handler.postDelayed({ finishOnce() }, RING_MS)
        } catch (e: Exception) {
            done()
        }
    }

    private fun finishOnce() {
        if (done) return
        done = true
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        handler.removeCallbacksAndMessages(null)
        RingGuard.notifyWebRang(ringBk, ringAt, ringId)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wl?.takeIf { it.isHeld }?.release() }
        wl = null
        runCatching { player?.release() }
        player = null
        finishOnce()   // 被 cancelRing 主动停掉也要回调（网页若已自己响过会按 id/时间窗去重忽略）
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, getString(R.string.ring_channel), NotificationManager.IMPORTANCE_HIGH)
        ch.setShowBadge(false)
        ch.setSound(null, null)   // 声音走 MediaPlayer，通知通道静音防双响
        ch.enableVibration(false)
        mgr.createNotification(ch)
    }
}
