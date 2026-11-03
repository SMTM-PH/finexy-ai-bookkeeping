package com.finexy.mobile.data

import androidx.room.Entity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Raw, possibly incomplete form input. This table is never an upload source. */
@Entity(tableName = "template_form_drafts", primaryKeys = ["ledgerId", "templateType"])
data class TemplateFormDraftEntity(
    val ledgerId: Long,
    val templateType: Int,
    val draftId: String,
    val revision: Long,
    val editingId: Long,
    val formJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Only explicit confirmation creates this immutable request. */
@Entity(tableName = "template_creation_queue", primaryKeys = ["ledgerId", "templateType"])
data class TemplateCreationEntity(
    val ledgerId: Long,
    val templateType: Int,
    val clientSessionId: String,
    val draftId: String,
    val draftRevision: Long,
    val payloadJson: String,
    val state: String = PENDING,
    val serverId: Long? = null,
    val serverDeleted: Boolean = false,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PENDING = "pending"
        const val POSTED = "posted"
    }
}

data class TemplateCreationReceipt(val ledgerId: Long, val templateType: Int, val id: Long, val deleted: Boolean = false)

internal fun validateTemplateDraft(draft: TemplateFormDraftEntity) {
    require(draft.ledgerId >= 0 && draft.templateType in 1..2 && draft.editingId >= 0 && draft.revision > 0 && draft.updatedAt >= 0)
    require(draft.draftId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "模板草稿标识无效" }
    require(draft.formJson.toByteArray().size <= 64 * 1024) { "模板草稿过大" }
    val fields = Json.parseToJsonElement(draft.formJson) as? JsonObject ?: error("模板草稿格式无效")
    require(fields.size <= 64 && fields.all { (key, value) ->
        key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && value is JsonPrimitive && value.isString && value.content.length <= 8192
    }) { "模板草稿字段无效" }
}

/** Validate the stored bytes without filling defaults or rewriting the upload snapshot. */
internal fun validateTemplateCreationPayload(payloadJson: String, ledgerId: Long, templateType: Int) {
    require(ledgerId >= 0 && templateType in 1..2 && payloadJson.toByteArray().size <= 64 * 1024)
    val payload = Json.parseToJsonElement(payloadJson) as? JsonObject ?: error("模板请求格式无效")
    val common = setOf("ledgerId", "templateType", "name", "type", "categoryId", "sourceAccountId", "destinationAccountId",
        "sourceAmount", "destinationAmount", "hideAmount", "tagIds", "comment")
    val expected = if (templateType == 2) common + setOf("scheduledFrequencyType", "scheduledFrequency", "scheduledStartDate", "scheduledEndDate", "utcOffset") else common
    require(payload.keys == expected) { "模板请求字段不完整或包含其他操作" }
    require(payload["ledgerId"] == JsonPrimitive(ledgerId.toString()) && payload["templateType"] == JsonPrimitive(templateType)) { "模板请求账本或类型不一致" }
    parsePeriodicTemplates(buildJsonObject {
        put("success", true)
        put("result", buildJsonArray { add(JsonObject(payload + ("id" to JsonPrimitive("1")))) })
    }.toString(), ledgerId, templateType).single()
}

internal fun validateTemplateCreation(write: TemplateCreationEntity) {
    require(write.clientSessionId.matches(Regex("[A-Za-z0-9-]{1,64}")) && write.draftId.matches(Regex("[A-Za-z0-9-]{1,64}")))
    require(write.draftRevision > 0 && write.createdAt >= 0 && write.state in setOf(TemplateCreationEntity.PENDING, TemplateCreationEntity.POSTED))
    require(if (write.state == TemplateCreationEntity.POSTED) write.serverId != null && write.serverId > 0 else write.serverId == null && !write.serverDeleted) { "模板创建回执状态无效" }
    validateTemplateCreationPayload(write.payloadJson, write.ledgerId, write.templateType)
}

interface TemplateCreationStore {
    suspend fun templateCreations(): List<TemplateCreationEntity>
    suspend fun markTemplateCreationPosted(write: TemplateCreationEntity, receipt: TemplateCreationReceipt): Boolean
    suspend fun markTemplateCreationFailed(write: TemplateCreationEntity, message: String)
}

/** Network adapter must require the server's durable creation contract before uploading. */
class TemplateCreationDelivery(
    private val store: TemplateCreationStore,
    private val upload: suspend (TemplateCreationEntity) -> TemplateCreationReceipt
) {
    suspend fun push(): TemplateCreationDeliveryResult {
        var posted = 0
        val errors = mutableListOf<Exception>()
        for (write in store.templateCreations().filter { it.state == TemplateCreationEntity.PENDING }) {
            try {
                validateTemplateCreation(write)
                val receipt = upload(write)
                require(receipt.id > 0 && receipt.ledgerId == write.ledgerId && receipt.templateType == write.templateType) { "模板创建回执范围错误" }
                check(store.markTemplateCreationPosted(write, receipt)) { "模板请求已变更，保留原回执待核对" }
                posted++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                store.markTemplateCreationFailed(write, error.message ?: "模板创建结果待确认")
                errors += error
            }
        }
        return TemplateCreationDeliveryResult(posted, errors)
    }
}

data class TemplateCreationDeliveryResult(val posted: Int, val errors: List<Exception>)
