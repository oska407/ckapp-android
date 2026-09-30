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
    private val platform: String = "phone",
    private val version: String = ""
) {
    companion object {
        private const val PREFS = "ckapp_drafts"
        private const val MAX_DRAFT_LEN = 64 * 1024
        // 与网页真实 localStorage key 对齐（契约 §3 修正，2026-09-30）：
        // ck.draft=计时+路由快照 / ck.queue=离线队列 / ck.state=状态缓存
        private val ALLOWED_KEYS = setOf("ck.draft", "ck.queue", "ck.state")
    }

    private val prefs: android.content.SharedPreferences by lazy {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

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
    fun platform(): String = platform

    @JavascriptInterface
    fun version(): String = version

    /** 原生 → 网页：把一条 SSE 推送交给页面已注册的 onPush（页面未注册则静默忽略）。 */
    fun push(payload: Map<String, Any?>) {
        val json = runCatching { JSONObject(payload).toString() }.getOrNull() ?: return
        val js = "try{if(window.CKApp&&typeof window.CKApp.onPush==='function'){window.CKApp.onPush($json);}}catch(e){}"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trim()
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }
}
