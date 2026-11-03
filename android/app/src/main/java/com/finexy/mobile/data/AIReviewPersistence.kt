package com.finexy.mobile.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.util.UUID

/** The database is already partitioned by LedgerScope (server/user). */
@Entity(tableName = "ledger_ai_review_cache", primaryKeys = ["ledgerId", "id"])
data class LedgerAIReviewCacheEntity(val ledgerId: Long, val id: Long, val status: Int,
    val itemJson: String, val deleted: Boolean = false, val updatedAt: Long = System.currentTimeMillis())

/** itemId=0 is a source draft; positive IDs are raw confirmation editors. Never uploaded. */
@Entity(tableName = "ai_review_drafts", primaryKeys = ["ledgerId", "itemId"])
data class AIReviewDraftEntity(val ledgerId: Long, val itemId: Long, val draftId: String,
    val revision: Long, val formJson: String, val updatedAt: Long = System.currentTimeMillis())

/** Exact, explicitly confirmed bytes. A receipt remains until the original item is read. */
@Entity(tableName = "ai_review_requests", primaryKeys = ["ledgerId", "itemId"])
data class AIReviewRequestEntity(val ledgerId: Long, val itemId: Long, val requestId: String,
    val draftId: String, val draftRevision: Long, val action: String, val payloadJson: String,
    val state: String = PENDING, val receiptJson: String = "", val lastErrorCode: String = "",
    val createdAt: Long = System.currentTimeMillis(), @ColumnInfo(defaultValue = "''") val sourceImageId: String = "") {
    companion object { const val PENDING = "pending"; const val POSTED = "posted" }
}

internal fun LedgerAIReviewItem.toStoredJson(): String = buildJsonObject {
    put("ledgerId", ledgerId.toString()); put("id", id.toString()); put("creatorUid", creatorUid.toString())
    put("transactionId", transactionId.toString()); put("confirmedUid", confirmedUid.toString())
    put("editable", editable); put("deleted", deleted); put("sourceType", sourceType); put("status", status)
    put("sourceText", sourceText); put("failureReason", failureReason); put("createdUnixTime", createdUnixTime)
    recognized?.let { put("recognizedData", it.toJson()) }
}.toString()

private fun aiEnvelope(json: String): String {
    val result = try { Json.parseToJsonElement(json) } catch (_: RuntimeException) { error("AI 持久数据格式不完整") }
    return buildJsonObject { put("success", true); put("result", result) }.toString()
}
internal fun validateLedgerAICache(row: LedgerAIReviewCacheEntity): LedgerAIReviewItem {
    require(row.updatedAt >= 0 && row.itemJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
    return parseLedgerAIReviewItem(aiEnvelope(row.itemJson), row.ledgerId, row.id).also {
        require(it.status == row.status && it.deleted == row.deleted) { "AI 缓存状态不一致" }
    }
}

internal fun validateAIReviewDraft(draft: AIReviewDraftEntity) {
    require(draft.ledgerId >= 0 && draft.itemId >= 0 && draft.revision > 0 && draft.updatedAt >= 0 &&
        draft.draftId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "AI 原始草稿标识无效" }
    require(draft.formJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "AI 原始草稿过大" }
    val fields = try { Json.parseToJsonElement(draft.formJson) as? JsonObject } catch (_: RuntimeException) { null }
    require(fields != null && fields.size <= 64 && fields.all { (key, value) ->
        key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && value is JsonPrimitive && value.isString && value.content.length <= 8192
    }) { "AI 草稿须保留原始字符串输入" }
    aiDraftImageId(draft)
}

internal val aiReviewErrorCodes = setOf("", "delivery-failed", "refresh-failed", "permission-denied",
    "unsupported-service", "session-changed", "invalid-snapshot", "receipt-mismatch")

internal fun validateAIReviewRequest(write: AIReviewRequestEntity) {
    require(write.sourceImageId.isEmpty() || write.action == "create" && write.itemId == 0L && write.sourceImageId.matches(Regex("[a-f0-9-]{36}")))
    require(write.requestId.matches(Regex("[A-Za-z0-9-]{1,64}")) && write.draftId.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
        write.draftRevision > 0 && write.createdAt >= 0 && write.lastErrorCode in aiReviewErrorCodes &&
        write.state in setOf(AIReviewRequestEntity.PENDING, AIReviewRequestEntity.POSTED)) { "AI 持久请求状态无效" }
    when (write.action) {
        "create" -> { require(write.itemId == 0L); validateLedgerAIReviewCreate(write.payloadJson, write.ledgerId) }
        "confirm" -> validateLedgerAIConfirmation(write.payloadJson, write.ledgerId, write.itemId)
        else -> error("AI 持久请求不接受其他操作")
    }
    if (write.sourceImageId.isNotEmpty()) require((Json.parseToJsonElement(write.payloadJson) as JsonObject)["sourceType"]?.jsonPrimitive?.content == "2") { "原图引用只适用于图片来源" }
    if (write.state == AIReviewRequestEntity.PENDING) require(write.receiptJson.isEmpty()) { "未确认请求不能带回执" }
    else validateAIReviewReceipt(write, write.receiptJson)
}

internal fun validateAIReviewReceipt(write: AIReviewRequestEntity, receiptJson: String): Long {
    require(receiptJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
    return if (write.action == "create") {
        val item = parseLedgerAIReviewItem(aiEnvelope(receiptJson), write.ledgerId)
        requireLedgerAIReviewCreateReceipt(item, write.payloadJson, write.ledgerId)
        item.id
    } else parseLedgerAIConfirmationReceipt(aiEnvelope(receiptJson), write.ledgerId, write.itemId).id
}

private fun AIReviewRequestEntity.sameSnapshot(other: AIReviewRequestEntity) =
    ledgerId == other.ledgerId && itemId == other.itemId && requestId == other.requestId && action == other.action &&
        draftId == other.draftId && draftRevision == other.draftRevision && payloadJson == other.payloadJson && createdAt == other.createdAt && sourceImageId == other.sourceImageId

interface AIReviewRequestStore {
    suspend fun prepareDelivery(write: AIReviewRequestEntity) { }
    suspend fun requests(): List<AIReviewRequestEntity>
    suspend fun markPosted(write: AIReviewRequestEntity, receiptJson: String): Boolean
    suspend fun markFailed(write: AIReviewRequestEntity, errorCode: String)
    suspend fun acknowledge(write: AIReviewRequestEntity, remote: LedgerAIReviewItem): Boolean
}

class AIReviewPersistence(private val database: FinexyDatabase,
    private val imageCrypto: AIReviewImageCrypto = AIReviewImageCrypto(database.openHelper.databaseName ?: "isolated-memory")) : AIReviewRequestStore {
    internal val storagePartition: String get() = database.openHelper.databaseName ?: "isolated-memory"
    private val dao get() = database.dao()
    fun observeItems(ledgerId: Long, status: Int = 1): Flow<List<LedgerAIReviewCacheEntity>> {
        require(ledgerId >= 0 && status in 1..3)
        return dao.observeLedgerAIReview(ledgerId, status)
    }
    fun observeDraft(ledgerId: Long, itemId: Long): Flow<AIReviewDraftEntity?> {
        require(ledgerId >= 0 && itemId >= 0)
        return dao.observeAIReviewDraft(ledgerId, itemId)
    }
    fun observeRequests(): Flow<List<AIReviewRequestEntity>> = dao.observeAIReviewRequests()
    fun observeDrafts(ledgerId: Long): Flow<List<AIReviewDraftEntity>> {
        require(ledgerId >= 0)
        return dao.observeAIReviewDrafts(ledgerId)
    }
    suspend fun draft(ledgerId: Long, itemId: Long): AIReviewDraftEntity? = dao.findAIReviewDraft(ledgerId, itemId)
    override suspend fun requests(): List<AIReviewRequestEntity> = dao.allAIReviewRequests()
    override suspend fun prepareDelivery(write: AIReviewRequestEntity) = database.withTransaction {
        val current = dao.findAIReviewRequest(write.ledgerId, write.itemId)
        require(current != null && current.sameSnapshot(write)) { "原 AI 请求已变化" }
        require(write.action != "confirm" || write.ledgerId != 0L || !dao.hasLegacyAIReviewTransaction(write.itemId)) { "旧版关联流水需要先核对，不能替代入账" }
    }

    suspend fun saveDraft(ledgerId: Long, itemId: Long, formJson: String, expected: AIReviewDraftEntity? = null): AIReviewDraftEntity = database.withTransaction {
        val current = dao.findAIReviewDraft(ledgerId, itemId)
        require(current == expected && (expected == null || expected.ledgerId == ledgerId && expected.itemId == itemId)) { "AI 草稿已更新，请重新读取" }
        val draft = AIReviewDraftEntity(ledgerId, itemId, current?.draftId ?: UUID.randomUUID().toString(),
            Math.addExact(current?.revision ?: 0, 1), formJson)
        validateAIReviewDraft(draft); requireImage(draft); dao.upsertAIReviewDraft(draft); pruneImages(ledgerId); draft
    }

    /** Original bytes and draft reference commit together. Old images remain pinned by frozen requests. */
    suspend fun saveSourceImage(ledgerId: Long, formJson: String, bytes: ByteArray,
        expected: AIReviewDraftEntity? = null): AIReviewDraftEntity = database.withTransaction {
        require(ledgerId >= 0 && (expected == null || expected.itemId == 0L && expected.ledgerId == ledgerId))
        require(dao.findAIReviewDraft(ledgerId, 0) == expected) { "AI 草稿已更新，请重新读取" }
        val draftId = expected?.draftId ?: UUID.randomUUID().toString()
        val image = aiImageInfo(ledgerId, UUID.randomUUID().toString(), draftId, bytes)
        val fields = Json.parseToJsonElement(formJson) as? JsonObject ?: error("AI 草稿格式无效")
        val draft = AIReviewDraftEntity(ledgerId, 0, draftId, Math.addExact(expected?.revision ?: 0, 1),
            JsonObject(fields + (AI_IMAGE_FIELD to JsonPrimitive(image.imageId))).toString())
        validateAIReviewDraft(draft)
        dao.insertAIReviewImage(image); dao.insertAIReviewImageChunks(imageCrypto.chunks(image, bytes))
        dao.upsertAIReviewDraft(draft); pruneImages(ledgerId); draft
    }

    /** Call with a captured current draft; stale UI cannot read a replaced or different draft image. */
    suspend fun sourceImage(draft: AIReviewDraftEntity): ByteArray = database.withTransaction {
        validateAIReviewDraft(draft)
        require(dao.findAIReviewDraft(draft.ledgerId, draft.itemId) == draft) { "AI 草稿已更新，请重新读取" }
        val image = requireImage(draft) ?: error("来源草稿没有原图")
        imageCrypto.original(image, dao.aiReviewImageChunks(image.ledgerId, image.imageId))
    }

    suspend fun requestSourceImage(write: AIReviewRequestEntity): ByteArray = database.withTransaction {
        validateAIReviewRequest(write)
        val current = dao.findAIReviewRequest(write.ledgerId, write.itemId)
        require(current != null && current.sameSnapshot(write) && write.sourceImageId.isNotEmpty()) { "原 AI 请求或图片引用已变更" }
        val image = dao.findAIReviewImage(write.ledgerId, write.sourceImageId) ?: error("原请求图片缺失")
        require(image.draftId == write.draftId)
        imageCrypto.original(image, dao.aiReviewImageChunks(image.ledgerId, image.imageId))
    }

    private suspend fun requireImage(draft: AIReviewDraftEntity): AIReviewImageEntity? {
        val id = aiDraftImageId(draft)
        if (id.isEmpty()) return null
        return (dao.findAIReviewImage(draft.ledgerId, id) ?: error("来源原图缺失，请重新选择或从备份恢复")).also {
            validateAIImage(it); require(it.draftId == draft.draftId) { "来源原图不属于此草稿" }
        }
    }

    internal suspend fun pruneImages(ledgerId: Long) {
        val keep = buildSet {
            dao.findAIReviewDraft(ledgerId, 0)?.let { aiDraftImageId(it).takeIf(String::isNotEmpty)?.let(::add) }
            dao.findAIReviewRequest(ledgerId, 0)?.sourceImageId?.takeIf(String::isNotEmpty)?.let(::add)
        }
        for (image in dao.aiReviewImages(ledgerId)) if (image.imageId !in keep) {
            dao.deleteAIReviewImageChunks(ledgerId, image.imageId); dao.deleteAIReviewImage(ledgerId, image.imageId)
        }
    }

    suspend fun discardDraft(draft: AIReviewDraftEntity): Boolean = database.withTransaction {
        validateAIReviewDraft(draft)
        val write = dao.findAIReviewRequest(draft.ledgerId, draft.itemId)
        require(write == null || write.draftId != draft.draftId) { "已确认请求须先核对原结果" }
        val deleted = dao.deleteAIReviewDraft(draft.ledgerId, draft.itemId, draft.draftId, draft.revision) > 0
        if (deleted) pruneImages(draft.ledgerId)
        deleted
    }

    suspend fun enqueue(draft: AIReviewDraftEntity, action: String, payloadJson: String): AIReviewRequestEntity = database.withTransaction {
        validateAIReviewDraft(draft)
        require(dao.findAIReviewDraft(draft.ledgerId, draft.itemId) == draft) { "请先保存最新 AI 草稿" }
        val write = AIReviewRequestEntity(draft.ledgerId, draft.itemId, UUID.randomUUID().toString(), draft.draftId,
            draft.revision, action, payloadJson, sourceImageId = aiDraftImageId(draft))
        requireImage(draft)?.let { imageCrypto.original(it, dao.aiReviewImageChunks(it.ledgerId, it.imageId)) }
        validateAIReviewRequest(write)
        require(write.action != "confirm" || write.ledgerId != 0L || !dao.hasLegacyAIReviewTransaction(write.itemId)) { "旧版关联流水需要先核对，不能替代入账" }
        val current = dao.findAIReviewRequest(draft.ledgerId, draft.itemId)
        if (current != null) {
            require(current.draftId == draft.draftId && current.draftRevision == draft.revision &&
                current.action == action && current.payloadJson == payloadJson && current.sourceImageId == write.sourceImageId) { "原 AI 请求结果尚待核对，请重试原快照" }
            return@withTransaction current
        }
        dao.insertAIReviewRequest(write); write
    }

    override suspend fun markPosted(write: AIReviewRequestEntity, receiptJson: String): Boolean = database.withTransaction {
        validateAIReviewRequest(write); validateAIReviewReceipt(write, receiptJson)
        val current = dao.findAIReviewRequest(write.ledgerId, write.itemId) ?: return@withTransaction false
        if (!current.sameSnapshot(write)) return@withTransaction false
        if (current.state == AIReviewRequestEntity.POSTED) return@withTransaction current.receiptJson == receiptJson
        dao.markAIReviewRequestPosted(write.ledgerId, write.itemId, write.requestId, receiptJson) > 0
    }

    override suspend fun markFailed(write: AIReviewRequestEntity, errorCode: String) = database.withTransaction {
        require(errorCode in aiReviewErrorCodes && errorCode.isNotEmpty())
        val current = dao.findAIReviewRequest(write.ledgerId, write.itemId)
        if (current != null && current.sameSnapshot(write) && current.state == write.state)
            dao.markAIReviewRequestFailed(write.ledgerId, write.itemId, write.requestId, write.state, errorCode)
        Unit
    }

    override suspend fun acknowledge(write: AIReviewRequestEntity, remote: LedgerAIReviewItem): Boolean = database.withTransaction {
        validateAIReviewRequest(write)
        require(write.state == AIReviewRequestEntity.POSTED)
        val id = validateAIReviewReceipt(write, write.receiptJson)
        require(remote.ledgerId == write.ledgerId && remote.id == id) { "AI 读取不是原回执对象" }
        val cached = LedgerAIReviewCacheEntity(remote.ledgerId, remote.id, remote.status, remote.toStoredJson(), remote.deleted)
        validateLedgerAICache(cached)
        if (write.action == "create") requireLedgerAIReviewCreateReceipt(remote, write.payloadJson, write.ledgerId)
        else {
            val receipt = parseLedgerAIConfirmationReceipt(aiEnvelope(write.receiptJson), write.ledgerId, write.itemId)
            require(remote.status == 2 && remote.transactionId == receipt.transactionId) { "AI 读取未核对原确认流水" }
        }
        val current = dao.findAIReviewRequest(write.ledgerId, write.itemId) ?: return@withTransaction false
        if (!current.sameSnapshot(write) || current.state != write.state || current.receiptJson != write.receiptJson) return@withTransaction false
        cacheRows(listOf(cached))
        dao.deleteAIReviewDraft(write.ledgerId, write.itemId, write.draftId, write.draftRevision)
        dao.acknowledgeAIReviewRequest(write.ledgerId, write.itemId, write.requestId)
        pruneImages(write.ledgerId)
        true
    }

    /** Only a fully collected, valid response may replace one status in one ledger. */
    suspend fun replaceSnapshot(ledgerId: Long, status: Int, remote: List<LedgerAIReviewItem>) = database.withTransaction {
        require(ledgerId >= 0 && status in 1..3 && remote.all { it.ledgerId == ledgerId && it.status == status && !it.deleted } &&
            remote.map { it.id }.distinct().size == remote.size) { "AI 完整快照范围错误" }
        val rows = remote.map { LedgerAIReviewCacheEntity(it.ledgerId, it.id, it.status, it.toStoredJson()) }
        rows.forEach { validateLedgerAICache(it) }
        cacheRows(rows)
        dao.deleteLedgerAIReviewNotIn(ledgerId, status, rows.map { it.id })
    }

    private suspend fun cacheRows(rows: List<LedgerAIReviewCacheEntity>) {
        for (row in rows) {
            val previous = dao.findLedgerAIReview(row.ledgerId, row.id)
            // A list/get response started before confirmation or deletion may arrive late.
            if (previous?.deleted == true && !row.deleted) continue
            if (previous?.status == 2) {
                if (row.status != 2) continue
                val old = validateLedgerAICache(previous)
                val next = validateLedgerAICache(row)
                require(old.transactionId == next.transactionId && old.confirmedUid == next.confirmedUid) { "AI 缓存确认回执不可替换" }
            }
            dao.upsertLedgerAIReview(listOf(row))
        }
    }
}

/** Upload only confirmed requests; persist receipts before attempting the read. */
class AIReviewDelivery(private val store: AIReviewRequestStore,
    private val upload: suspend (AIReviewRequestEntity) -> String,
    private val get: suspend (Long, Long) -> LedgerAIReviewItem) {
    suspend fun push(): AIReviewDeliveryResult {
        var posted = 0; var acknowledged = 0
        val errors = mutableListOf<Exception>()
        for (snapshot in store.requests()) {
            var write = snapshot
            try {
                validateAIReviewRequest(write)
                if (write.state == AIReviewRequestEntity.PENDING) {
                    store.prepareDelivery(write)
                    val receipt = upload(write)
                    validateAIReviewReceipt(write, receipt)
                    check(store.markPosted(write, receipt)) { "AI 请求已变更，原回执待核对" }
                    write = write.copy(state = AIReviewRequestEntity.POSTED, receiptJson = receipt, lastErrorCode = "")
                    posted++
                }
                val itemId = validateAIReviewReceipt(write, write.receiptJson)
                check(store.acknowledge(write, get(write.ledgerId, itemId))) { "AI 回执或草稿已变更，请重新读取" }
                acknowledged++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val code = when {
                    error is ApiException && error.status in listOf(401, 403) -> "permission-denied"
                    error is ApiException && error.status == 404 -> "unsupported-service"
                    write.state == AIReviewRequestEntity.POSTED -> "refresh-failed"
                    else -> "delivery-failed"
                }
                store.markFailed(write, code)
                errors += error
            }
        }
        return AIReviewDeliveryResult(posted, acknowledged, errors)
    }

    companion object {
        fun forApi(store: AIReviewRequestStore, api: FinexyApi) = AIReviewDelivery(store, { write ->
            if (write.action == "create") api.createLedgerAIReviewItem(write.ledgerId, write.payloadJson).toStoredJson()
            else api.confirmLedgerAIReviewItem(write.ledgerId, write.itemId, write.payloadJson).let { receipt ->
                buildJsonObject { put("ledgerId", receipt.ledgerId.toString()); put("id", receipt.id.toString()); put("transactionId", receipt.transactionId.toString()) }.toString()
            }
        }, { ledgerId, itemId -> api.getLedgerAIReviewItem(ledgerId, itemId) })
    }
}

data class AIReviewDeliveryResult(val posted: Int, val acknowledged: Int, val errors: List<Exception>)
