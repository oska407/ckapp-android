package com.ckapp.checkin

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * JS Bridge（契约 §3 / §6 第一步）。
 * 通过 addJavascriptInterface(..., "CKAppBridge") 暴露给网页，仅含最小方法集：
 *   getDraft / setDraft / clearDraft —— 进行中计时与离线队列存 EncryptedSharedPreferences。
 * 网页侧 window.CKApp.onPush 由页面自己注册；原生 SSE 收到推送时经 MainActivity 调 evaluateJavascript 触发。
 * 注意：绑定名是 CKAppBridge（非 window.CKApp），因为 addJavascriptInterface 在 WebView 上下文创建时即同步可用，
 * 网页据此同步建 window.CKApp 数据对象（含 onPush），规避 onPageFinished 注入竞态。
 */
class CKAppBridge(
    private val context: Context,
    private val webView: android.webkit.WebView,
    // 用取值函数而非快照字符串：家长在设置页切换形态后无需重建桥，platform() 即时返回新值
    private val platformProvider: () -> String = { "phone" },
    private val version: String = ""
) {
    companion object {
        private const val PREFS = "ckapp_drafts"
        private const val MAX_DRAFT_LEN = 64 * 1024
        // 与网页真实 localStorage key 对齐（契约 §3 修正，2026-09-30）：
        // ck.draft=计时+路由快照 / ck.queue=离线队列 / ck.state=状态缓存
        // ck.flushLock=离线队列跨页重放互斥锁（2026-10-10 补：flush 防乱序修复曾把它塞进桥但白名单漏了，
        // 导致手机 save 被静默丢弃、flush 永远抢不到锁、离线数据传不出去；网页侧已改为走 localStorage，
        // 这里放行仅作卫生项，防止以后又把它路由回桥）。
        // ck.ringLog=原生响铃回执（2026-10-14 息屏铃声兜底：RingGuard 写、网页读，凭 id 去重对账）。
        private val ALLOWED_KEYS = setOf("ck.draft", "ck.queue", "ck.state", "ck.flushLock", "ck.ringLog")

        private fun prefsOf(context: Context): android.content.SharedPreferences {
            val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, PREFS, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /** 原生侧直读（RingGuard 读 ringLog 用；白名单同 ALLOWED_KEYS）。 */
        fun getDraftRaw(context: Context, key: String): String {
            if (key !in ALLOWED_KEYS) return ""
            return runCatching { prefsOf(context).getString(key, "") ?: "" }.getOrDefault("")
        }

        /** 原生侧直写（RingGuard 写 ringLog 回执用；白名单同 ALLOWED_KEYS）。 */
        fun writeDraftRaw(context: Context, key: String, json: String): Boolean {
            if (key !in ALLOWED_KEYS) return false
            if (json.length > MAX_DRAFT_LEN) return false
            return runCatching { prefsOf(context).edit().putString(key, json).apply() }.isSuccess
        }
    }

    private val prefs: android.content.SharedPreferences by lazy { prefsOf(context) }

    @JavascriptInterface
    fun getDraft(key: String?): String {
        if (key == null || key !in ALLOWED_KEYS) return ""
        return runCatching { prefs.getString(key, "") ?: "" }.getOrDefault("")
    }

    @JavascriptInterface
    fun setDraft(key: String?, json: String?) {
        if (key == null || key !in ALLOWED_KEYS) return
        if (json == null || json.length > MAX_DRAFT_LEN) return
        if (!looksLikeJson(json)) return
        runCatching { prefs.edit().putString(key, json).apply() }
    }

    @JavascriptInterface
    fun clearDraft(key: String?) {
        if (key == null || key !in ALLOWED_KEYS) return
        runCatching { prefs.edit().remove(key).apply() }
    }

    // 供网页同步建立 window.CKApp（addJavascriptInterface 在 WebView 上下文创建时即同步可用，规避注入竞态）
    @JavascriptInterface
    fun isApp(): Boolean = true

    @JavascriptInterface
    fun platform(): String = platformProvider()

    @JavascriptInterface
    fun version(): String = version

    // scene#15 任务9（2026-10-07）：网页按「当前计时分类 + PC 端分类开关」判定是否需要强制点亮，
    // 经此方法交给 Activity；真正的屏幕常亮 FLAG 由 Activity 按「原生总开关 && 网页判定」叠加。
    @JavascriptInterface
    fun setKeepAwake(on: Boolean) {
        val act = context as? MainActivity ?: return
        webView.post { act.onWebKeepAwake(on) }
    }

    // ---- 息屏铃声兜底（2026-10-14，设计见根目录《息屏铃声原生兜底设计方案.md》）----

    /** 方案A：网页上报「当前有无计时中的块且页面可见」→ RingGuard 持/放 PARTIAL_WAKE_LOCK。 */
    @JavascriptInterface
    fun setTimingActive(on: Boolean) {
        RingGuard.init(context)
        RingGuard.setTimingActive(on)
    }

    /** 方案B：网页排轮次的同刻排原生闹钟（到点唤醒播放，网页被冻也不丢响）。 */
    @JavascriptInterface
    fun armRing(atMs: Double, sound: String?, bk: String?) {
        RingGuard.init(context)
        RingGuard.armRing(atMs, sound ?: "", bk ?: "")
    }

    /** 网页五个取消点（两键/换块/块结束/轮数用尽）调：disarm 闹钟 + 停播。 */
    @JavascriptInterface
    fun cancelRing() {
        RingGuard.init(context)
        RingGuard.cancelRing()
    }

    /** 原生 → 网页：一条 SSE 推送交给页面已注册的 onPush（页面未注册则静默忽略）。 */
    fun push(payload: Map<String, Any?>) {
        val json = runCatching { JSONObject(payload).toString() }.getOrNull() ?: return
        val js = "try{if(window.CKApp&&typeof window.CKApp.onPush==='function'){window.CKApp.onPush($json);}}catch(e){}"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** 原生 → 网页：代响完成回调（RingGuard.webRingSink 注入），推进网页轮次/排下一轮。 */
    fun ring(payload: Map<String, Any?>) {
        val json = runCatching { JSONObject(payload).toString() }.getOrNull() ?: return
        val js = "try{if(window.CKApp&&typeof window.CKApp.onNativeRing==='function'){window.CKApp.onNativeRing($json);}}catch(e){}"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trim()
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }
}
