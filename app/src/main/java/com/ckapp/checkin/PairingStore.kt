package com.ckapp.checkin

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 配对信息存储（EncryptedSharedPreferences）：baseUrl / token / serverId。
 * 解析 ckapp://pair?... 深链，或 http(s)://...?pair=1 二维码链接，生成入口 URL。
 */
class PairingStore(context: Context) {
    companion object {
        private const val PREFS = "ckapp_pairing"
        private const val K_BASE = "base_url"
        private const val K_TOKEN = "token"
        private const val K_SERVER = "server_id"
        /** 家长手动锁定的界面形态："phone" / "pad"；为空=按屏幕自动判定。 */
        private const val K_FORM = "form_override"
    }

    private val prefs: android.content.SharedPreferences = run {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val baseUrl: String? get() = prefs.getString(K_BASE, "")?.takeIf { it.isNotEmpty() }
    val token: String? get() = prefs.getString(K_TOKEN, "")?.takeIf { it.isNotEmpty() }
    val serverId: String? get() = prefs.getString(K_SERVER, "")?.takeIf { it.isNotEmpty() }

    val isPaired: Boolean get() = baseUrl != null && token != null && serverId != null

    /**
     * 家长在设置页手动锁定的界面形态（防屏幕尺寸误判，如某些大屏手机/小尺寸平板）。
     * 只接受 "phone" / "pad"，其它值一律视为未设置（自动判定）。
     */
    var formOverride: String?
        get() = prefs.getString(K_FORM, "")?.takeIf { it == "phone" || it == "pad" }
        set(v) {
            if (v == null) prefs.edit().remove(K_FORM).apply()
            else prefs.edit().putString(K_FORM, v).apply()
        }

    fun save(baseUrl: String, token: String, serverId: String) {
        prefs.edit().apply {
            putString(K_BASE, baseUrl.trim().trimEnd('/'))
            putString(K_TOKEN, token.trim())
            putString(K_SERVER, serverId.trim())
            apply()
        }
    }

    fun clear() = prefs.edit().clear().apply()

    /**
     * 解析配对意图：
     *   ckapp://pair?base=http://host:port&token=...&serverId=...
     *   http(s)://host:port/...?pair=1&token=...&serverId=...
     * 返回 true 表示解析成功并已写入。
     */
    fun parseIntent(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val token = uri.getQueryParameter("token") ?: return false
        val serverId = uri.getQueryParameter("serverId") ?: return false
        val base = if (scheme == "ckapp") {
            uri.getQueryParameter("base") ?: return false
        } else {
            val port = uri.port
            val portSuffix = if (port != -1 && port != 80 && port != 443) ":$port" else ""
            "${uri.scheme}://${uri.host}$portSuffix"
        }
        save(base, token, serverId)
        return true
    }

    /** 入口 URL：?device=phone|pad&token=&serverId=&pair=1（契约 / Phase1 配对） */
    fun entryUrl(device: String): String {
        val b = baseUrl ?: return ""
        val t = token ?: return ""
        val s = serverId ?: return ""
        return "$b/?device=$device&token=$t&serverId=$s&pair=1"
    }
}
