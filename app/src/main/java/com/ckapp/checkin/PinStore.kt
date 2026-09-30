package com.ckapp.checkin

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * 父母门禁 PIN（M5 / C4）：PIN 以 SHA-256(盐+pin) 哈希存 EncryptedSharedPreferences，绝不存明文。
 * 仅本地校验，无网络、无账号。
 */
class PinStore(context: Context) {
    companion object {
        private const val PREFS = "ckapp_pin"
        private const val K_HASH = "pin_hash"
        private const val SALT = "ckapp_pin_salt_v1"
    }

    private val prefs = run {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasPin(): Boolean = prefs.contains(K_HASH)
    fun setPin(pin: String) = prefs.edit().putString(K_HASH, hash(pin)).apply()
    fun clearPin() = prefs.edit().remove(K_HASH).apply()
    fun verify(pin: String): Boolean {
        val h = prefs.getString(K_HASH, null) ?: return false
        return h == hash(pin)
    }

    private fun hash(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
