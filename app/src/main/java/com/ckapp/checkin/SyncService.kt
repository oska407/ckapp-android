package com.ckapp.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * 前台同步服务（契约 §2 / M4）：原生持 SSE 长连，收到推送后本地广播给 MainActivity，
 * 由网页 window.CKApp.onPush 触发刷新。WebView 自身不持有网络长连，切后台/锁屏仍保活。
 *
 * 注意：本期骨架已实现连接与转发；指数退避重连为简化版（固定 5s）。
 */
class SyncService : Service() {

    companion object {
        const val ACTION_SYNC_PUSH = "com.ckapp.checkin.action.SYNC_PUSH"
        private const val CHANNEL = "ckapp_sync"
        private const val NOTIF_ID = 1
        private const val RETRY_MS = 5000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var eventSource: EventSource? = null
    private lateinit var client: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.sync_title))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE 长读
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSse()
        return START_STICKY
    }

    private fun startSse() {
        val store = PairingStore(this)
        val base = store.baseUrl ?: return
        val token = store.token ?: return
        val serverId = store.serverId ?: return
        val url = "$base/api/stream?token=$token&serverId=$serverId"
        val req = Request.Builder().url(url).build()

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                // data 形如 {"type":"change","at":...} 或 {"type":"state","state":...}
                forward(data)
            }
            override fun onFailure(source: EventSource, t: Throwable?, response: okhttp3.Response?) {
                source.cancel()
                eventSource = null
                // 简化重连：5s 后重试（契约要求指数退避，骨架留 TODO）
                scope.launch {
                    delay(RETRY_MS)
                    startSse()
                }
            }
        }
        eventSource?.cancel()
        eventSource = EventSources.createFactory(client).newEventSource(req, listener)
    }

    /** 把原始 SSE data（JSON）通过本地广播发给 MainActivity。 */
    private fun forward(json: String) {
        val intent = Intent(ACTION_SYNC_PUSH).setPackage(packageName).putExtra("json", json)
        sendBroadcast(intent)
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        eventSource?.cancel()
        eventSource = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
