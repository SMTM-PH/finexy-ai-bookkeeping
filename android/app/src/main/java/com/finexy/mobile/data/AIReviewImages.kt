package com.finexy.mobile.data

import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Entity
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Immutable original. Crop/rotation and recognition suggestions belong to the versioned draft. */
@Entity(tableName = "ai_review_images", primaryKeys = ["ledgerId", "imageId"])
data class AIReviewImageEntity(val ledgerId: Long, val imageId: String, val draftId: String,
    val mimeType: String, val width: Int, val height: Int, val byteCount: Int, val sha256: String, val chunkCount: Int)

/** Small rows avoid SQLite CursorWindow limits. Every chunk is independently authenticated. */
@Entity(tableName = "ai_review_image_chunks", primaryKeys = ["ledgerId", "imageId", "chunkIndex"])
data class AIReviewImageChunkEntity(val ledgerId: Long, val imageId: String, val chunkIndex: Int, val content: String)

internal const val AI_IMAGE_CHUNK_BYTES = 64 * 1024
internal const val AI_IMAGE_MAX_BYTES = 8 * 1024 * 1024
internal const val AI_IMAGE_FIELD = "sourceImageId"
internal fun aiImageDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun validateAIImage(row: AIReviewImageEntity) {
    require(row.ledgerId >= 0 && row.imageId.matches(Regex("[a-f0-9-]{36}")) && row.draftId.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
        row.mimeType in setOf("image/jpeg", "image/png", "image/webp") && row.width in 1..16000 && row.height in 1..16000 &&
        row.width.toLong() * row.height <= 40_000_000 && row.byteCount in 1..AI_IMAGE_MAX_BYTES &&
        row.sha256.matches(Regex("[a-f0-9]{64}")) && row.chunkCount == (row.byteCount + AI_IMAGE_CHUNK_BYTES - 1) / AI_IMAGE_CHUNK_BYTES) { "AI 原图元数据无效或超限" }
}

internal fun aiImageInfo(ledger: Long, id: String, draft: String, bytes: ByteArray): AIReviewImageEntity {
    require(bytes.size in 1..AI_IMAGE_MAX_BYTES) { "原图需在 8 MiB 以内" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val row = AIReviewImageEntity(ledger, id, draft, bounds.outMimeType.orEmpty(), bounds.outWidth, bounds.outHeight,
        bytes.size, aiImageDigest(bytes), (bytes.size + AI_IMAGE_CHUNK_BYTES - 1) / AI_IMAGE_CHUNK_BYTES)
    validateAIImage(row)
    var sample = 1
    while (maxOf(row.width, row.height) / sample > 512) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    require(decoded != null) { "无法解析原图" }; decoded.recycle()
    return row
}

internal fun aiDraftImageId(draft: AIReviewDraftEntity): String =
    (Json.parseToJsonElement(draft.formJson) as JsonObject)[AI_IMAGE_FIELD]?.jsonPrimitive?.content.orEmpty().also {
        require(it.isEmpty() || draft.itemId == 0L && it.matches(Regex("[a-f0-9-]{36}"))) { "AI 原图引用无效" }
    }

/** The app Keystore key never leaves Android. The AAD also binds the identity database and all metadata. */
class AIReviewImageCrypto(private val scope: String, private val keyAlias: String = "finexy_ai_source_images_v1") {
    private fun key(create: Boolean): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey) ?: run {
            check(create) { "原图密钥不可用，请从加密备份恢复" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }
    private fun aad(row: AIReviewImageEntity, index: Int): ByteArray = buildJsonArray {
        add("FinexyAISource:1"); add(scope); add(row.ledgerId.toString()); add(row.imageId); add(row.draftId)
        add(row.mimeType); add(row.width); add(row.height); add(row.byteCount); add(row.sha256); add(row.chunkCount); add(index)
    }.toString().toByteArray(Charsets.UTF_8)

    internal fun encrypt(row: AIReviewImageEntity, index: Int, bytes: ByteArray): AIReviewImageChunkEntity {
        validateChunkSize(row, index, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(aad(row, index)) }
        return AIReviewImageChunkEntity(row.ledgerId, row.imageId, index, "local1:" + PrivacyCrypto.encode(cipher.iv + cipher.doFinal(bytes)))
    }
    internal fun decrypt(row: AIReviewImageEntity, chunk: AIReviewImageChunkEntity): ByteArray {
        require(chunk.ledgerId == row.ledgerId && chunk.imageId == row.imageId && chunk.content.startsWith("local1:") && chunk.content.length <= 90_000) { "原图分块归属无效" }
        try {
            val bytes = PrivacyCrypto.decode(chunk.content.removePrefix("local1:"))
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(aad(row, chunk.chunkIndex))
            }
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).also { validateChunkSize(row, chunk.chunkIndex, it.size) }
        } catch (_: Exception) { error("原图损坏或密钥不可用，请从加密备份恢复") }
    }
    internal fun chunks(row: AIReviewImageEntity, bytes: ByteArray): List<AIReviewImageChunkEntity> {
        require(bytes.size == row.byteCount && aiImageDigest(bytes) == row.sha256)
        return (0 until row.chunkCount).map { index -> encrypt(row, index, bytes.copyOfRange(index * AI_IMAGE_CHUNK_BYTES, minOf(bytes.size, (index + 1) * AI_IMAGE_CHUNK_BYTES))) }
    }
    internal fun original(row: AIReviewImageEntity, chunks: List<AIReviewImageChunkEntity>): ByteArray {
        validateAIImage(row)
        require(chunks.map { it.chunkIndex } == (0 until row.chunkCount).toList()) { "原图分块不完整" }
        return ByteArrayOutputStream(row.byteCount).use { out ->
            chunks.forEach { out.write(decrypt(row, it)) }; out.toByteArray().also {
                require(aiImageInfo(row.ledgerId, row.imageId, row.draftId, it) == row) { "原图内容校验失败" }
            }
        }
    }
    companion object { private val keyLock = Any() }
}

internal fun validateChunkSize(row: AIReviewImageEntity, index: Int, size: Int) {
    validateAIImage(row)
    require(index in 0 until row.chunkCount && size == minOf(AI_IMAGE_CHUNK_BYTES, row.byteCount - index * AI_IMAGE_CHUNK_BYTES)) { "原图分块长度无效" }
}
