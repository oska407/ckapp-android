package com.ckapp.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 方案B 播放服务：AlarmManager 到点后由 RingReceiver 拉起，前台服务内 MediaPlayer 播提示音
 * （缓存音频优先，缺失/下载失败用系统默认提示音），同时挂一条可点的常驻通知。
 * 播完回调网页 onNativeRing（推进轮次/排下一轮），随后 stopSelf。
 *
 * 为什么敢从后台起 FGS：SyncService 是常驻前台服务，本进程已在前台，不受 Android 12+
 * 后台起 FGS 限制约束；兜底另有 startForegroundService 失败时静默降级（网页通道仍会补响）。
 */
class RingPlayService : Service() {

    companion object {
        private const val CHANNEL = "ckapp_ring"
        private const val NOTIF_ID = 2

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RingPlayService::class.java)) }
        }
    }

    private var player: MediaPlayer? = null
    private var done = false
    private var ringBk = ""
    private var ringAt = 0L
    private var ringId = 0L

    override fun onCreate() {
        super.onCreate()
        RingGuard.init(this)
        createChannel()
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

    /** 缓存音频 → 系统默认提示音 → 静默；任何异常都走完成回调，不挂住服务。 */
    private fun play(sound: String, done: () -> Unit) {
        try {
            val cached = RingGuard.soundFileFor(sound)?.takeIf { it.exists() }
            val uri: Uri = when {
                cached != null -> Uri.fromFile(cached)
                else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            player = MediaPlayer().apply {
                setDataSource(this@RingPlayService, uri)
                prepare()
                setOnCompletionListener { done() }
                setOnErrorListener { _, _, _ -> done(); true }
                start()
            }
        } catch (e: Exception) {
            done()
        }
    }

    private fun finishOnce() {
        if (done) return
        done = true
        RingGuard.notifyWebRang(ringBk, ringAt, ringId)
        stopSelf()
    }

    override fun onDestroy() {
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
        mgr.createNotificationChannel(ch)
    }
}
