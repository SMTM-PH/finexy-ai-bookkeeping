package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun CategoryEntity.isAISelectableLeaf(all: List<CategoryEntity>, expectedType: Int): Boolean =
    !hidden && type == expectedType && parentId != 0L && all.any { parent ->
        parent.id == parentId && !parent.hidden && parent.parentId == 0L && parent.type == expectedType
    }

/** Raw strings remain local until an explicitly confirmed immutable atomic request is enqueued. */
data class AIReviewEditorForm(val fields: Map<String, String>) {
    fun json() = JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
    fun changed(key: String, value: String) = copy(fields = fields + (key to value))
    fun payload(ledgerId: Long, itemId: Long, accounts: List<AccountEntity>, categories: List<CategoryEntity>): String {
        val type = fields.getValue("type").toInt()
        require(type in 2..4)
        val direction = fields.getValue("direction"); require(direction in setOf("internal", "out", "in"))
        val source = if (type == 4 && direction == "in") 0 else fields.getValue("sourceAccountId").toLongOrNull() ?: 0
        val destination = if (type == 4 && direction != "out") fields.getValue("destinationAccountId").toLongOrNull() ?: 0 else 0
        fun account(id: Long): AccountEntity = accounts.singleOrNull { it.id == id && it.id > 0 && it.type == 1 && !it.hidden &&
            (it.parentId == 0L || accounts.any { parent -> parent.id == it.parentId && !parent.hidden }) } ?: error("请选择当前账本可用的账户")
        val sourceAccount = if (type != 4 || direction != "in") account(source) else null
        val destinationAccount = if (type == 4 && direction != "out") account(destination) else null
        if (sourceAccount != null && destinationAccount != null) require(source != destination) { "转出和转入账户不能相同" }
        fun amount(field: String): Long {
            val raw = fields.getValue(field)
            require(raw.matches(Regex("-?[0-9]+(\\.[0-9]{1,2})?"))) { "金额表达式尚未完成，请输入至多两位小数" }
            return BigDecimal(raw).movePointRight(2).longValueExact().also {
                require(it in -9_999_999_999_999L..9_999_999_999_999L) { "金额超限" }
            }
        }
        val sourceAmount = if (sourceAccount == null) 0L else amount("sourceAmount")
        val destinationAmount = if (destinationAccount == null) 0L else if (sourceAccount != null && sourceAccount.currency == destinationAccount.currency) sourceAmount else amount("destinationAmount")
        if (type != 3) require(sourceAmount >= 0 && destinationAmount >= 0) { "此类型不接受负金额" }
        val category = fields.getValue("categoryId").toLongOrNull()
        val categoryType = when (type) { 2 -> 1; 3 -> 2; else -> 3 }
        require(categories.any { it.id == category && it.isAISelectableLeaf(categories, categoryType) }) { "请选择当前类型可用的末级分类" }
        val zone = ZoneId.of(fields.getValue("timeZone"))
        val local = LocalDateTime.parse(fields.getValue("dateTime"), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val offsetMinutes = fields.getValue("utcOffset").toInt()
        require(offsetMinutes in -720..840)
        val offset = ZoneOffset.ofTotalSeconds(Math.multiplyExact(offsetMinutes, 60))
        require(offset in zone.rules.getValidOffsets(local)) { "日期不在此时区的有效时刻，或 UTC 偏移不匹配；请核对夏令时" }
        val seconds = local.toEpochSecond(offset)
        val payload = buildJsonObject {
            put("ledgerId", ledgerId.toString()); put("id", itemId.toString())
            put("transaction", buildJsonObject {
                put("ledgerId", ledgerId.toString()); put("clientSessionId", UUID.randomUUID().toString()); put("type", type)
                put("time", seconds); put("utcOffset", offsetMinutes); put("categoryId", requireNotNull(category).toString())
                put("sourceAccountId", source.toString()); put("destinationAccountId", destination.toString())
                put("sourceAmount", sourceAmount); put("destinationAmount", destinationAmount)
                put("hideAmount", fields.getValue("hideAmount").toBooleanStrict())
                put("tagIds", Json.parseToJsonElement(fields.getValue("tagIdsJson")))
                put("pictureIds", Json.parseToJsonElement(fields.getValue("pictureIdsJson")))
                put("comment", fields.getValue("comment"))
                fields["geoLocationJson"]?.takeIf(String::isNotEmpty)?.let { put("geoLocation", Json.parseToJsonElement(it)) }
            })
        }.toString()
        validateLedgerAIConfirmation(payload, ledgerId, itemId)
        return payload
    }
    companion object {
        fun initial(item: LedgerAIReviewItem, zone: ZoneId = ZoneId.systemDefault()): AIReviewEditorForm {
            val recognized = item.recognized
            val instant = (recognized?.time?.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: Instant.now()).atZone(zone)
            fun id(value: Long?) = value?.takeIf { it > 0 }?.toString().orEmpty()
            fun amount(value: Long?) = value?.let { BigDecimal.valueOf(it).movePointLeft(2).toPlainString() }.orEmpty()
            return AIReviewEditorForm(mapOf("type" to (recognized?.type?.takeIf { it in 2..4 } ?: 3).toString(),
                "direction" to when { recognized?.sourceAccountId == 0L && (recognized.destinationAccountId ?: 0) > 0 -> "in"
                    (recognized?.sourceAccountId ?: 0) > 0 && recognized?.destinationAccountId == 0L -> "out"; else -> "internal" },
                "sourceAccountId" to id(recognized?.sourceAccountId), "destinationAccountId" to id(recognized?.destinationAccountId),
                "sourceAmount" to amount(recognized?.sourceAmountMinor), "destinationAmount" to amount(recognized?.destinationAmountMinor),
                "categoryId" to id(recognized?.categoryId), "dateTime" to instant.toLocalDateTime().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "timeZone" to zone.id, "utcOffset" to (instant.offset.totalSeconds / 60).toString(), "hideAmount" to "false",
                "comment" to (recognized?.comment ?: ""), "tagIdsJson" to buildJsonArray { recognized?.tagIds?.forEach { add(it.toString()) } }.toString(),
                "pictureIdsJson" to "[]", "geoLocationJson" to ""))
        }
        fun read(draft: AIReviewDraftEntity): AIReviewEditorForm {
            validateAIReviewDraft(draft)
            return AIReviewEditorForm((Json.parseToJsonElement(draft.formJson) as JsonObject).mapValues { it.value.jsonPrimitive.content })
        }
    }
}
