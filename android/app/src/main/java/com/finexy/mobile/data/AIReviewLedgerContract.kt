package com.finexy.mobile.data

import kotlinx.serialization.json.*

/** The queue is scoped by the server, never inferred from an account name. */
data class LedgerAIRecognition(
    val ledgerId: Long, val type: Int, val time: Long?, val categoryId: Long?,
    val sourceAccountId: Long?, val destinationAccountId: Long?,
    val sourceAmountMinor: Long?, val destinationAmountMinor: Long?,
    val tagIds: List<Long>, val comment: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ledgerId", ledgerId.toString()); put("type", type)
        time?.let { put("time", it) }; categoryId?.let { put("categoryId", it.toString()) }
        sourceAccountId?.let { put("sourceAccountId", it.toString()) }
        destinationAccountId?.let { put("destinationAccountId", it.toString()) }
        sourceAmountMinor?.let { put("sourceAmount", it) }; destinationAmountMinor?.let { put("destinationAmount", it) }
        put("tagIds", buildJsonArray { tagIds.forEach { add(it.toString()) } }); put("comment", comment)
    }
    // Go omitempty removes zero IDs/amounts and empty arrays in the receipt.
    internal fun normalized() = copy(time = time ?: 0, categoryId = categoryId ?: 0,
        sourceAccountId = sourceAccountId ?: 0, destinationAccountId = destinationAccountId ?: 0,
        sourceAmountMinor = sourceAmountMinor ?: 0, destinationAmountMinor = destinationAmountMinor ?: 0)
}

data class LedgerAIReviewItem(
    val ledgerId: Long, val id: Long, val creatorUid: Long, val transactionId: Long, val confirmedUid: Long,
    val editable: Boolean, val deleted: Boolean, val sourceType: Int, val status: Int,
    val sourceText: String, val recognized: LedgerAIRecognition?, val failureReason: String, val createdUnixTime: Long
)

data class LedgerAIConfirmationReceipt(val ledgerId: Long, val id: Long, val transactionId: Long)

private const val AI_AMOUNT_LIMIT = 9_999_999_999_999L
private val AI_ID = Regex("0|[1-9][0-9]*")
private fun aiObject(raw: String): JsonObject = try {
    Json.parseToJsonElement(raw) as? JsonObject ?: error("invalid")
} catch (_: RuntimeException) { throw IllegalArgumentException("AI 回包或请求格式不完整，请保留原内容") }

private fun aiResult(raw: String): JsonElement {
    val envelope = aiObject(raw)
    require(envelope["success"] == JsonPrimitive(true)) { "AI 回包未确认成功" }
    return envelope["result"]?.takeUnless { it is JsonNull } ?: error("AI 回包缺少结果")
}
private fun JsonObject.aiString(key: String): String {
    val value = this[key] as? JsonPrimitive
    require(value != null && value.isString) { "AI 字符串字段不完整" }
    return value.content
}
private fun JsonObject.aiId(key: String, positive: Boolean = false): Long {
    val value = aiString(key)
    val id = value.takeIf { it.matches(AI_ID) }?.toLongOrNull()
    require(id != null && id >= if (positive) 1 else 0) { "AI 标识必须是完整的字符串 ID" }
    return id
}
private fun JsonObject.aiInteger(key: String, range: LongRange): Long {
    val value = this[key] as? JsonPrimitive
    val number = value?.takeUnless { it.isString }?.longOrNull
    require(number != null && number in range) { "AI 数值字段必须是范围内的整数" }
    return number
}
private fun JsonObject.aiBoolean(key: String): Boolean {
    val value = this[key] as? JsonPrimitive
    val boolean = value?.takeUnless { it.isString }?.booleanOrNull
    require(boolean != null) { "AI 状态字段不完整" }
    return boolean
}
private fun JsonObject.aiIds(key: String, optional: Boolean = false): List<Long> {
    if (optional && key !in this) return emptyList()
    val values = this[key] as? JsonArray ?: error("AI 关联字段不完整")
    require(values.size <= 10) { "AI 关联字段过多" }
    val ids = values.map { buildJsonObject { put("id", it) }.aiId("id", true) }
    require(ids.distinct().size == ids.size) { "AI 关联字段重复" }
    return ids
}

/** Match Go strings.TrimSpace, including U+0085 and non-breaking spaces. */
internal fun trimAIReviewSource(value: String): String = value.trim {
    // Java also treats U+001C..U+001F as whitespace; Go preserves those source bytes.
    it in '\u0009'..'\u000d' || it == '\u0020' || it == '\u0085' || it == '\u00a0' ||
        it == '\u1680' || it in '\u2000'..'\u200a' || it == '\u2028' || it == '\u2029' ||
        it == '\u202f' || it == '\u205f' || it == '\u3000'
}

internal fun requireLedgerAIReviewCapabilities(raw: String, ledgerId: Long, recognition: Boolean = false) {
    require(ledgerId >= 0) { "AI 账本无效" }
    val value = aiResult(raw) as? JsonObject ?: error("AI 能力回包不完整")
    require(value.aiId("ledgerId") == ledgerId && value.aiInteger("contractVersion", 1L..1L) == 1L &&
        listOf("durableCreation", "atomicConfirmation", "payloadBinding", "deletedReceipts").all { value.aiBoolean(it) } &&
        value.aiInteger("maxClientSessionIdBytes", 128L..128L) == 128L && value.aiInteger("maxPageSize", 100L..100L) == 100L &&
        value.aiString("pagination") == "beforeId" && value.aiString("reviewerScope") == "manager-or-creator" &&
        value.aiString("confirmationPath") == "v1/ai/review/confirm.json" && (!recognition || value.aiBoolean("recognitionLedgerContext"))) {
        "当前服务不支持可靠的账本 AI 流程，请升级服务；原内容已保留"
    }
}

private fun aiRecognition(value: JsonObject, ledgerId: Long, requireLedger: Boolean = false): LedgerAIRecognition {
    require(ledgerId >= 0 && (!requireLedger || ledgerId == 0L || "ledgerId" in value) &&
        ("ledgerId" !in value || value.aiId("ledgerId") == ledgerId)) { "AI 识别结果账本不一致" }
    fun optionalId(key: String) = if (key in value) value.aiId(key) else null
    fun optionalInteger(key: String, range: LongRange) = if (key in value) value.aiInteger(key, range) else null
    return LedgerAIRecognition(ledgerId, value.aiInteger("type", 2L..4L).toInt(),
        optionalInteger("time", 0L..Long.MAX_VALUE), optionalId("categoryId"), optionalId("sourceAccountId"), optionalId("destinationAccountId"),
        optionalInteger("sourceAmount", -AI_AMOUNT_LIMIT..AI_AMOUNT_LIMIT), optionalInteger("destinationAmount", -AI_AMOUNT_LIMIT..AI_AMOUNT_LIMIT),
        value.aiIds("tagIds", true), if ("comment" in value) value.aiString("comment") else "")
}

internal fun parseLedgerAIRecognition(raw: String, ledgerId: Long): LedgerAIRecognition =
    aiRecognition(aiResult(raw) as? JsonObject ?: error("AI 识别结果不完整"), ledgerId, true)

private fun aiItem(value: JsonObject, ledgerId: Long): LedgerAIReviewItem {
    require(ledgerId >= 0 && value.aiId("ledgerId") == ledgerId) { "AI 条目不属于原账本" }
    val status = value.aiInteger("status", 1L..3L).toInt()
    val transactionId = value.aiId("transactionId")
    val confirmedUid = value.aiId("confirmedUid")
    val editable = value.aiBoolean("editable")
    val deleted = value.aiBoolean("deleted")
    require(!deleted || !editable) { "已删除 AI 条目不可编辑" }
    require(if (status == 2) transactionId > 0 && confirmedUid > 0 else transactionId == 0L && confirmedUid == 0L) { "AI 确认状态与流水回执不一致" }
    val recognized = value["recognizedData"]?.takeUnless { it is JsonNull }?.let {
        aiRecognition(it as? JsonObject ?: error("AI 识别数据格式无效"), ledgerId)
    }
    return LedgerAIReviewItem(ledgerId, value.aiId("id", true), value.aiId("creatorUid", true), transactionId, confirmedUid,
        editable, deleted, value.aiInteger("sourceType", 1L..3L).toInt(), status, value.aiString("sourceText"), recognized,
        value.aiString("failureReason"), value.aiInteger("createdUnixTime", 0L..Long.MAX_VALUE))
}

internal fun parseLedgerAIReviewItem(raw: String, ledgerId: Long, id: Long? = null): LedgerAIReviewItem {
    val item = aiItem(aiResult(raw) as? JsonObject ?: error("AI 条目回包不完整"), ledgerId)
    require(id == null || id == item.id) { "AI 条目回包不是原对象" }
    return item
}

internal fun parseLedgerAIReviewPage(raw: String, ledgerId: Long, status: Int, beforeId: Long): List<LedgerAIReviewItem> {
    require(ledgerId >= 0 && status in 1..3 && beforeId >= 0) { "AI 队列查询无效" }
    val array = aiResult(raw) as? JsonArray ?: error("AI 队列回包不完整")
    require(array.size <= 100) { "AI 队列分页超过合同上限" }
    var previous: Long? = beforeId.takeIf { it > 0 }
    return array.map { value ->
        aiItem(value as? JsonObject ?: error("AI 队列条目不完整"), ledgerId).also { item ->
            require(!item.deleted && item.status == status && (previous == null || item.id < previous!!)) { "AI 队列分页重复、乱序或状态不一致" }
            previous = item.id
        }
    }
}

/** A broken later page throws; callers never receive a partial snapshot. */
internal suspend fun collectLedgerAIReviewPages(ledgerId: Long, status: Int, fetch: suspend (Long) -> String): List<LedgerAIReviewItem> {
    require(ledgerId >= 0 && status in 1..3)
    val items = mutableListOf<LedgerAIReviewItem>()
    var beforeId = 0L
    while (true) {
        val page = parseLedgerAIReviewPage(fetch(beforeId), ledgerId, status, beforeId)
        require(items.size + page.size <= 100_000) { "AI 队列过大，未完整读取；请保留原缓存" }
        items += page
        if (page.size < 100) return items
        beforeId = page.last().id
    }
}

private fun aiKey(value: String, bytes: Int) {
    require(trimAIReviewSource(value).isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= bytes) { "AI 原请求稳定键无效" }
}

internal fun validateLedgerAIReviewCreate(payloadJson: String, ledgerId: Long): JsonObject {
    require(ledgerId >= 0 && payloadJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "AI 来源请求过大或账本无效" }
    val payload = aiObject(payloadJson)
    val expected = setOf("ledgerId", "clientSessionId", "sourceType", "sourceText", "failureReason")
    require(payload.keys - "recognizedData" == expected && payload.aiId("ledgerId") == ledgerId) { "AI 来源请求字段或账本不一致" }
    aiKey(payload.aiString("clientSessionId"), 128); payload.aiInteger("sourceType", 1L..3L)
    val source = trimAIReviewSource(payload.aiString("sourceText"))
    val failure = trimAIReviewSource(payload.aiString("failureReason"))
    require(source.isNotEmpty() && source.toByteArray(Charsets.UTF_8).size <= 8000 && failure.codePointCount(0, failure.length) <= 500) { "AI 来源内容为空或超限" }
    payload["recognizedData"]?.takeUnless { it is JsonNull }?.let { aiRecognition(it as? JsonObject ?: error("AI 识别请求格式无效"), ledgerId) }
    return payload
}

internal fun requireLedgerAIReviewCreateReceipt(item: LedgerAIReviewItem, payloadJson: String, ledgerId: Long) {
    val payload = validateLedgerAIReviewCreate(payloadJson, ledgerId)
    val recognized = payload["recognizedData"]?.takeUnless { it is JsonNull }?.let { aiRecognition(it as JsonObject, ledgerId) }
    require(item.ledgerId == ledgerId && item.sourceType == payload.aiInteger("sourceType", 1L..3L).toInt() &&
        item.sourceText == trimAIReviewSource(payload.aiString("sourceText")) && item.failureReason == trimAIReviewSource(payload.aiString("failureReason")) &&
        item.recognized?.normalized() == recognized?.normalized()) { "AI 来源创建回执与原快照不一致，请保留原请求" }
}

internal fun validateLedgerAIConfirmation(payloadJson: String, ledgerId: Long, id: Long): JsonObject {
    require(ledgerId >= 0 && id > 0 && payloadJson.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "AI 确认请求过大或标识无效" }
    val payload = aiObject(payloadJson)
    require(payload.keys == setOf("ledgerId", "id", "transaction") && payload.aiId("ledgerId") == ledgerId && payload.aiId("id", true) == id) { "AI 确认请求账本或对象不一致" }
    val tx = payload["transaction"] as? JsonObject ?: error("AI 确认流水不完整")
    val fields = setOf("ledgerId", "clientSessionId", "type", "time", "utcOffset", "categoryId", "sourceAccountId", "destinationAccountId", "sourceAmount", "destinationAmount", "hideAmount", "tagIds", "pictureIds", "comment")
    require(tx.keys - "geoLocation" == fields && tx.aiId("ledgerId") == ledgerId) { "AI 确认流水字段或账本不一致" }
    aiKey(tx.aiString("clientSessionId"), 128)
    val type = tx.aiInteger("type", 2L..4L)
    tx.aiInteger("time", 1L..((Long.MAX_VALUE - 999) / 1000)); tx.aiInteger("utcOffset", -720L..840L); tx.aiId("categoryId", true)
    val source = tx.aiId("sourceAccountId")
    val destination = tx.aiId("destinationAccountId")
    val amount = tx.aiInteger("sourceAmount", -AI_AMOUNT_LIMIT..AI_AMOUNT_LIMIT)
    val destinationAmount = tx.aiInteger("destinationAmount", -AI_AMOUNT_LIMIT..AI_AMOUNT_LIMIT)
    tx.aiBoolean("hideAmount"); tx.aiIds("tagIds"); tx.aiIds("pictureIds")
    val comment = tx.aiString("comment")
    require(comment.codePointCount(0, comment.length) <= 255) { "AI 确认备注过长" }
    if (type == 4L) require(source != destination && amount >= 0 && destinationAmount >= 0 &&
        (source != 0L || amount == 0L) && (destination != 0L || destinationAmount == 0L)) { "AI 转账须保留资金方向与双金额" }
    else require(source > 0 && destination == 0L && destinationAmount == 0L && (type == 3L || amount >= 0)) { "AI 收支账户或金额不完整" }
    tx["geoLocation"]?.takeUnless { it is JsonNull }?.let { value ->
        val geo = value as? JsonObject ?: error("AI 位置字段不完整")
        require(geo.keys == setOf("latitude", "longitude")) { "AI 位置字段无效" }
        for ((key, limit) in listOf("latitude" to 90.0, "longitude" to 180.0)) {
            val number = (geo[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            require(number != null && number.isFinite() && number in -limit..limit) { "AI 位置超出范围" }
        }
    }
    return payload
}

internal fun parseLedgerAIConfirmationReceipt(raw: String, ledgerId: Long, id: Long): LedgerAIConfirmationReceipt {
    require(ledgerId >= 0 && id > 0)
    val item = aiResult(raw) as? JsonObject ?: error("AI 入账回执不完整")
    require(item.aiId("ledgerId") == ledgerId && item.aiId("id", true) == id) { "AI 入账回执不是原账本和原条目" }
    return LedgerAIConfirmationReceipt(ledgerId, id, item.aiId("transactionId", true))
}

internal fun requireLedgerAIStateResult(raw: String) { require(aiResult(raw) == JsonPrimitive(true)) { "AI 状态操作未确认成功，请重新读取" } }
