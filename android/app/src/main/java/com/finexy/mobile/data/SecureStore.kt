package com.finexy.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted key/value store used for the server URL, token and sync queue. */
class SecureStore(context: Context, preferencesName: String = "finexy_secure") {
    internal val namespace = preferencesName
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyAlias = "finexy_mobile_store"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false)
                .build())
        }.generateKey().also { generated ->
            // The generated key is already persisted by AndroidKeyStore.
            check(generated.algorithm == "AES")
        }
    }

    fun put(name: String, value: String, durable: Boolean = false) {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val editor = preferences.edit().putString(name, Base64.encodeToString(iv + encrypted, Base64.NO_WRAP))
        if (durable) check(editor.commit()) { "无法保存账本归属信息" } else editor.apply()
    }

    fun get(name: String): String? {
        val stored = preferences.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    internal fun contains(name: String): Boolean = preferences.contains(name)

    fun remove(name: String) { check(preferences.edit().remove(name).commit()) { "无法清除本地凭据" } }

    /** Compare-and-set prevents an old request from restoring a logged-out session. */
    fun replaceSession(expected: String?, token: String?): Boolean = synchronized(sessionLock) {
        if (get(FinexyApi.KEY_TOKEN) != expected) return@synchronized false
        if (token == null) remove(FinexyApi.KEY_TOKEN) else put(FinexyApi.KEY_TOKEN, token, durable = true)
        true
    }

    companion object { private val sessionLock = Any() }
}
