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
class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("finexy_secure", Context.MODE_PRIVATE)
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

    fun put(name: String, value: String) {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        preferences.edit().putString(name, Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = preferences.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String) { preferences.edit().remove(name).apply() }
}
