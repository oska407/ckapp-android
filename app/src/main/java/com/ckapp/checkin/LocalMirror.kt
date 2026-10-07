package com.ckapp.checkin

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * LocalMirror —— 原生离线镜像（Phase3 §3.1）。
 *
 * 解决「PC 服务不在 = App 直接白屏」：App 是 WebView 直连 `http://<PC-IP>:8787/`，
 * PC 关机/服务没起时 loadUrl 失败、连页面都打不开。这里用 shouldInterceptRequest
 * 拦截同源请求：
 *   - 在线态：照常走真实网络，同时把同源 GET 静态资源预取到 filesDir/ckapp/mirror，
 *     并记录 manifest.json（URL→文件/类型/大小/最后访问）。
 *   - 离线态（探活 GET /api/lan 失败，3s 超时 ×2）：同源 GET 静态资源命中缓存即本地应答；
 *     未命中主帧返回友好兜底页「暂时连不上家长电脑」；`/api/` 开头的路径（非 SSE）返回
 *     200 + {"ok":false,"offline":true} + 响应头 X-CK-Offline:1（api.js 的 if(!j.ok) 分支
 *     据此走 markOffline + 写队列，原生侧拦截即够，网页网络层几乎不动）。
 *
 * 为什么用 shouldInterceptRequest 而不是起 127.0.0.1 本地服务器：页面 URL 与 origin 不变，
 * localStorage / fetch / SSE 全部保持同源行为，无需 usesCleartextTraffic 权限、不占端口、
 * 不用改 App 入口 URL（manifest 已开 cleartext，但这里本就不依赖它）。
 *
 * 约束：镜像只存界面资源，不存账本（账本只在服务端，离线数据以 op 形式由 CKAppBridge 加密存本地）。
 * 缓存上限 300 个文件 / 64MB，LRU 淘汰。
 */
class LocalMirror(
    private val context: Context,
    private val baseUrlProvider: () -> String?
) {
    companion object {
        private const val TAG = "LocalMirror"
        private const val MIRROR_DIR = "ckapp/mirror"
        private const val MAX_FILES = 300
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val PROBE_PERIOD_FG_MS = 15_000L   // 前台 15s
        private const val PROBE_PERIOD_BG_MS = 300_000L  // 后台 5min
        private const val PROBE_TIMEOUT_MS = 3_000       // 3s 超时
        private const val PROBE_FAIL_THRESHOLD = 2       // ×2 才判离线
    }

    private val mirrorDir = File(context.filesDir, MIRROR_DIR)
    private val manifestFile = File(mirrorDir, "manifest.json")
    private val lock = Any()

    @Volatile
    var serverOnline: Boolean = true
        private set

    private var failures = 0

    private val fetchClient = OkHttpClient.Builder()
        .connectTimeout(5_000, TimeUnit.MILLISECONDS)
        .readTimeout(10_000, TimeUnit.MILLISECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private var scheduler: ScheduledExecutorService? = null
    private var running = false

    // ---- 对外：WebViewClient.shouldInterceptRequest 委托 ----
    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url ?: return null
        val scheme = url.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!isSameOrigin(url)) return null            // 跨站一律走真实网络，不拦截

        val path = url.encodedPath ?: return null
        val method = req.method ?: "GET"

        // 数据 API：离线（非 SSE）本地应答离线桩；在线或 SSE 透传真实网络。
        if (path.startsWith("/api/")) {
            val accept = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
            val isSse = accept.contains("text/event-stream", ignoreCase = true)
            if (!serverOnline && !isSse) return offlineApiStub()
            return null
        }

        if (!method.equals("GET", ignoreCase = true)) return null  // 只镜像 GET 静态资源

        val urlStr = url.toString()
        if (!serverOnline) return serveFromCache(urlStr, req.isForMainFrame)

        // 在线：实时抓取并落盘（同时保证「改网页即时生效」），失败即判离线并回退缓存。
        return try {
            fetchAndCache(url, req)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed, switch to offline: ${e.message}")
            serverOnline = false
            serveFromCache(urlStr, req.isForMainFrame)
        }
    }

    // ---- 在线抓取 + 缓存 ----
    private fun fetchAndCache(url: Uri, req: WebResourceRequest): WebResourceResponse? {
        val builder = Request.Builder().url(url.toString())
        req.requestHeaders?.forEach { (k, v) ->
            if (!k.equals("Host", ignoreCase = true)) builder.header(k, v)
        }
        builder.header("Accept-Encoding", "identity") // 我们存解码后的原文
        builder.header("Cache-Control", "no-cache")

        val resp = fetchClient.newCall(builder.build()).execute()
        try {
            if (!resp.isSuccessful) return null // 非 2xx 让 WebView 自己处理（含 404）
            val mime = (resp.header("Content-Type") ?: "")
                .substringBefore(';').trim()
                .ifEmpty { guessMime(url.toString()) }
            val bytes = resp.body?.bytes() ?: return null
            cachePut(url.toString(), mime, bytes)
            serverOnline = true
            return WebResourceResponse(mime, encodingFor(mime), ByteArrayInputStream(bytes))
        } finally {
            resp.close()
        }
    }

    // ---- 离线读缓存 ----
    private fun serveFromCache(urlStr: String, isMain: Boolean): WebResourceResponse? {
        val hit = cacheLookup(urlStr) ?: return if (isMain) offlineFallbackPage() else null
        if (!hit.first.exists()) return if (isMain) offlineFallbackPage() else null
        return try {
            WebResourceResponse(hit.second, encodingFor(hit.second), FileInputStream(hit.first))
        } catch (e: Exception) {
            Log.w(TAG, "serveFromCache failed: ${e.message}")
            null
        }
    }

    // ---- 缓存写入 / LRU ----
    private fun cachePut(urlStr: String, mime: String, bytes: ByteArray) {
        synchronized(lock) {
            ensureDir()
            val hash = sha256(urlStr)
            val file = File(mirrorDir, hash)
            val tmp = File(mirrorDir, "$hash.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) file.writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "cachePut write failed: ${e.message}")
                return
            }
            val manifest = loadManifest()
            val entries = manifest.getJSONObject("entries")
            entries.put(urlStr, JSONObject().apply {
                put("f", hash); put("m", mime); put("s", bytes.size); put("t", System.currentTimeMillis())
            })
            enforceLru(entries)
            saveManifest(manifest)
        }
    }

    private fun cacheLookup(urlStr: String): Pair<File, String>? {
        synchronized(lock) {
            val entries = loadManifest().optJSONObject("entries") ?: return null
            val e = entries.optJSONObject(urlStr) ?: return null
            val f = e.optString("f", "")
            if (f.isEmpty()) return null
            val mime = e.optString("m", "application/octet-stream")
            return File(mirrorDir, f) to mime
        }
    }

    private fun enforceLru(entries: JSONObject) {
        var total = 0L
        val list = mutableListOf<Triple<String, File, Long>>()
        val iter = entries.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            val e = entries.getJSONObject(k)
            val f = File(mirrorDir, e.optString("f", ""))
            val s = e.optLong("s", 0L)
            total += s
            list.add(Triple(k, f, e.optLong("t", 0L)))
        }
        if (entries.length() <= MAX_FILES && total <= MAX_BYTES) return
        list.sortBy { it.third } // 最旧在前
        var i = 0
        while ((entries.length() > MAX_FILES || total > MAX_BYTES) && i < list.size) {
            val (k, f, s) = list[i]
            f.delete()
            entries.remove(k)
            total -= s
            i++
        }
    }

    // ---- 探活状态机 ----
    fun start() {
        if (running) return
        running = true
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({ runCatching { probe() } }, 0, PROBE_PERIOD_FG_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        scheduler?.shutdownNow()
        scheduler = null
    }

    /** 前台/后台切换探活周期（15s / 5min）。 */
    fun setBackground(bg: Boolean) {
        if (!running) return
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay(
            { runCatching { probe() } }, 0,
            if (bg) PROBE_PERIOD_BG_MS else PROBE_PERIOD_FG_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun probe() {
        val base = baseUrlProvider() ?: return
        val url = "$base/api/lan"
        try {
            val resp = probeClient.newCall(
                Request.Builder().url(url).header("Cache-Control", "no-cache").build()
            ).execute()
            try {
                if (resp.isSuccessful) {
                    serverOnline = true
                    failures = 0
                } else {
                    failures++
                    if (failures >= PROBE_FAIL_THRESHOLD) serverOnline = false
                }
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            failures++
            if (failures >= PROBE_FAIL_THRESHOLD) serverOnline = false
        }
    }

    // ---- 离线应答体 ----
    private fun offlineApiStub(): WebResourceResponse {
        val json = """{"ok":false,"offline":true,"at":${System.currentTimeMillis()}}"""
        val headers = mapOf("X-CK-Offline" to "1", "Content-Type" to "application/json")
        return WebResourceResponse("application/json", "utf-8", 200, "OK", headers, ByteArrayInputStream(json.toByteArray()))
    }

    private fun offlineFallbackPage(): WebResourceResponse {
        val html = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>离线</title></head>
<body style="margin:0;background:#FFF6EC;color:#5A4A3A;font-family:system-ui,-apple-system,'PingFang SC',sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;">
<div style="max-width:320px;padding:24px;text-align:center;">
<div style="font-size:40px;margin-bottom:12px;">&#128225;</div>
<h2 style="margin:0 0 8px;font-size:18px;">暂时连不上家长电脑</h2>
<p style="margin:0 0 18px;font-size:14px;line-height:1.6;color:#8A7F73;">请确认手机与运行「三端打卡」的电脑在同一 Wi-Fi，且电脑上的服务已启动，然后点击下方按钮重试。</p>
<button onclick="location.reload()" style="border:0;background:#E8833A;color:#fff;font-size:15px;padding:10px 22px;border-radius:999px;">重试</button>
</div></body></html>"""
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
    }

    // ---- 工具 ----
    private fun isSameOrigin(url: Uri): Boolean {
        val base = baseUrlProvider() ?: return false
        val b = Uri.parse(base)
        return url.host == b.host && effectivePort(url) == effectivePort(b)
    }

    private fun effectivePort(u: Uri): Int =
        if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80

    private fun encodingFor(mime: String): String? =
        if (mime.startsWith("text/") || mime.contains("javascript") || mime == "application/json") "utf-8" else null

    private fun guessMime(url: String): String {
        val l = url.lowercase()
        return when {
            l.endsWith(".js") || l.endsWith(".mjs") -> "application/javascript"
            l.endsWith(".css") -> "text/css"
            l.endsWith(".html") || l.endsWith(".htm") -> "text/html"
            l.endsWith(".json") -> "application/json"
            l.endsWith(".png") -> "image/png"
            l.endsWith(".jpg") || l.endsWith(".jpeg") -> "image/jpeg"
            l.endsWith(".svg") -> "image/svg+xml"
            l.endsWith(".woff2") -> "font/woff2"
            l.endsWith(".woff") -> "font/woff"
            l.endsWith(".gif") -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun ensureDir() {
        if (!mirrorDir.exists()) mirrorDir.mkdirs()
    }

    private fun loadManifest(): JSONObject {
        if (!manifestFile.exists()) return JSONObject().apply { put("entries", JSONObject()) }
        return try {
            JSONObject(manifestFile.readText())
        } catch (e: Exception) {
            JSONObject().apply { put("entries", JSONObject()) }
        }
    }

    private fun saveManifest(j: JSONObject) {
        ensureDir()
        runCatching { manifestFile.writeText(j.toString()) }
    }
}
