package com.finexy.mobile.data

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object PrivacyCrypto {
    private const val ROUNDS = 210_000
    fun random(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun encrypt(plain: String, password: String): String {
        require(password.length in 12..128) { "备份密码需 12–128 个字符，请妥善保管" }
        val salt = random(16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(password, salt), "AES"))
        cipher.updateAAD("FinexyBackup:1".toByteArray())
        return JSONObject().put("format", "FinexyBackup").put("version", 1).put("salt", encode(salt))
            .put("iv", encode(cipher.iv)).put("data", encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))).toString()
    }
    fun decrypt(encrypted: String, password: String): String {
        require(encrypted.length <= 24 * 1024 * 1024 && password.length in 12..128) { "文件过大或密码长度不正确" }
        try {
            val root = JSONObject(encrypted)
            require(root.getString("format") == "FinexyBackup" && root.getInt("version") == 1)
            val salt = decode(root.getString("salt")); val iv = decode(root.getString("iv"))
            require(salt.size == 16 && iv.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(password, salt), "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD("FinexyBackup:1".toByteArray())
            return String(cipher.doFinal(decode(root.getString("data"))), Charsets.UTF_8)
        } catch (error: Exception) { throw IllegalArgumentException("密码错误、文件损坏或备份版本不受支持", error) }
    }
    fun matches(left: ByteArray, right: ByteArray) = MessageDigest.isEqual(left, right)
}

/** UI access lock, not database encryption. PIN hash and cooldown are encrypted by SecureStore. */
class AppLock(private val store: SecureStore) {
    val biometricKeyAlias: String get() = "finexy_biometric_gate_" + MessageDigest.getInstance("SHA-256").digest(store.namespace.toByteArray()).joinToString("") { "%02x".format(it) }
    val enabled: Boolean get() = store.contains(KEY)
    val biometricEnabled: Boolean get() = enabled && store.get(BIOMETRIC) == "true"
    fun enable(pin: String) {
        require(pin.matches(Regex("[0-9]{6,12}"))) { "PIN 需为 6–12 位数字" }
        val salt = PrivacyCrypto.random(16)
        store.put(KEY, JSONObject().put("salt", PrivacyCrypto.encode(salt)).put("hash", PrivacyCrypto.encode(PrivacyCrypto.derive(pin, salt))).toString(), durable = true)
        store.remove(ATTEMPTS); store.remove(UNTIL)
    }
    fun verify(pin: String, now: Long = System.currentTimeMillis()): Boolean {
        check(enabled) { "应用锁未启用" }
        check(now >= (store.get(UNTIL)?.toLongOrNull() ?: 0)) { "尝试过多，请稍后再试" }
        val record = JSONObject(store.get(KEY) ?: error("应用锁凭据损坏，无法解锁；请使用已保存的备份恢复到其他设备"))
        val ok = pin.length in 6..12 && PrivacyCrypto.matches(PrivacyCrypto.derive(pin, PrivacyCrypto.decode(record.getString("salt"))), PrivacyCrypto.decode(record.getString("hash")))
        if (ok) { store.remove(ATTEMPTS); store.remove(UNTIL) }
        else {
            val attempts = (store.get(ATTEMPTS)?.toIntOrNull() ?: 0) + 1
            store.put(ATTEMPTS, attempts.coerceAtMost(10).toString(), durable = true)
            if (attempts >= 5) store.put(UNTIL, (now + 30_000L * (1L shl (attempts - 5).coerceAtMost(5))).toString(), durable = true)
        }
        return ok
    }
    fun disable(pin: String) { require(verify(pin)) { "PIN 不正确" }; store.remove(KEY); store.remove(BIOMETRIC) }
    fun setBiometric(pin: String, enabled: Boolean) {
        require(verify(pin)) { "PIN 不正确" }
        // Re-enabling after biometric enrollment changes regenerates only this gate's key.
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(biometricKeyAlias) }
        store.put(BIOMETRIC, enabled.toString(), durable = true)
    }
    companion object {
        private const val KEY = "app_lock_pin_v1"
        private const val BIOMETRIC = "app_lock_biometric_v1"
        private const val ATTEMPTS = "app_lock_attempts_v1"
        private const val UNTIL = "app_lock_until_v1"
    }
}
