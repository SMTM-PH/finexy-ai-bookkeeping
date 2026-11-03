package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.MonthDay

private const val PERIODIC_AMOUNT_LIMIT = 9_999_999_999_999L

/** Keep identifiers and integer minor units out of coercing JSON accessors. */
private fun JsonObject.periodicLong(name: String, minimum: Long = 0, maximum: Long = Long.MAX_VALUE, default: Long? = null): Long {
    val raw = this[name] ?: return default ?: error("周期响应缺少 $name")
    val primitive = raw as? JsonPrimitive ?: error("周期响应 $name 无效")
    val parsed = primitive.content.toLongOrNull()
    require(parsed != null && parsed.toString() == primitive.content && parsed in minimum..maximum) { "周期响应 $name 必须是有效整数" }
    return parsed
}

private fun JsonObject.periodicAmount(name: String, default: Long? = null): Long {
    require(this[name] == null || (this[name] as? JsonPrimitive)?.isString == false) { "周期金额不是整数最小货币单位" }
    return periodicLong(name, -PERIODIC_AMOUNT_LIMIT, PERIODIC_AMOUNT_LIMIT, default)
}

private fun JsonObject.periodicString(name: String, default: String? = null): String {
    val raw = this[name] ?: return default ?: error("周期响应缺少 $name")
    require(raw is JsonPrimitive && raw.isString) { "周期响应 $name 必须是文字" }
    return raw.content
}

private fun JsonObject.periodicBoolean(name: String, default: Boolean = false): Boolean {
    val raw = this[name] ?: return default
    require(raw is JsonPrimitive && !raw.isString && raw.booleanOrNull != null) { "周期响应 $name 必须是布尔值" }
    return raw.boolean
}

private fun JsonObject.periodicLedger(expected: Long): Long {
    require(expected >= 0) { "账本无效" }
    if (!containsKey("ledgerId")) {
        require(expected == 0L) { "非默认账本响应缺少账本归属" }
        return 0
    }
    val actual = periodicLong("ledgerId")
    require(actual == expected) { "周期响应与请求账本不一致" }
    return actual
}

private fun JsonObject.periodicDate(name: String): String? {
    if (this[name] == null || this[name] == JsonNull) return null
    val text = periodicString(name)
    require(runCatching { LocalDate.parse(text).toString() == text }.getOrDefault(false)) { "周期日期无效" }
    return text
}

private fun JsonObject.periodicTags(): String {
    val tags = this["tagIds"] ?: return "[]"
    require(tags is JsonArray) { "周期标签响应无效" }
    val values = tags.map { value ->
        require(value is JsonPrimitive) { "周期标签 ID 无效" }
        val id = value.content.toLongOrNull()
        require(id != null && id > 0 && id.toString() == value.content) { "周期标签 ID 无效" }
        id
    }
    require(values.distinct().size == values.size) { "周期标签包含重复 ID" }
    return JsonArray(values.map { JsonPrimitive(it.toString()) }).toString()
}

private fun periodicResult(raw: String): JsonElement {
    val envelope = Json.parseToJsonElement(raw) as? JsonObject ?: error("周期响应无效")
    require(envelope.periodicBoolean("success")) { "周期响应失败" }
    return envelope["result"] ?: error("周期响应缺少结果")
}

internal fun requirePeriodicAccountsLedger(raw: String, ledgerId: Long) {
    val rows = periodicResult(raw) as? JsonArray ?: error("账户列表响应不完整")
    val ids = mutableSetOf<Long>()
    fun check(row: JsonElement) {
        val account = row as? JsonObject ?: error("账户响应无效")
        account.periodicLedger(ledgerId)
        require(ids.add(account.periodicLong("id", minimum = 1))) { "账户响应包含重复 ID" }
        val type = account.periodicLong("type", 1, 2)
        val currency = account.periodicString("currency")
        require(type == 2L || currency.matches(Regex("[A-Z]{3}"))) { "账户币种无效" }
        require(account.periodicString("name").isNotBlank()) { "账户名称缺失" }
        account.periodicLong("parentId", default = 0)
        account.periodicBoolean("hidden")
        val children = account["subAccounts"]
        if (children != null && children != JsonNull) {
            require(children is JsonArray) { "子账户响应不完整" }
            children.forEach(::check)
        }
    }
    rows.forEach(::check)
}

internal fun requirePeriodicCategories(raw: String) {
    val groups = periodicResult(raw) as? JsonObject ?: error("分类列表响应不完整")
    val ids = mutableSetOf<Long>()
    fun check(row: JsonElement, expectedType: Long) {
        val category = row as? JsonObject ?: error("分类响应无效")
        require(ids.add(category.periodicLong("id", minimum = 1))) { "分类响应包含重复 ID" }
        require(category.periodicString("name").isNotBlank()) { "分类名称缺失" }
        require(category.periodicLong("type", 1, 3) == expectedType) { "分类类型与分组不一致" }
        category.periodicLong("parentId", default = 0)
        category.periodicBoolean("hidden")
        val children = category["subCategories"]
        if (children != null && children != JsonNull) {
            require(children is JsonArray) { "子分类响应不完整" }
            children.forEach { check(it, expectedType) }
        }
    }
    groups.forEach { (key, value) ->
        val type = key.toLongOrNull()?.takeIf { it in 1..3 && it.toString() == key } ?: error("分类分组无效")
        require(value is JsonArray) { "分类分组响应不完整" }
        value.forEach { check(it, type) }
    }
}

internal fun requirePeriodicTransactionDetail(raw: String, id: Long, ledgerId: Long) {
    val item = periodicResult(raw) as? JsonObject ?: error("流水详情不完整")
    item.periodicLedger(ledgerId)
    require(item.periodicLong("id", 1) == id) { "流水详情对象不一致" }
    val type = item.periodicLong("type", 1, 4)
    val source = item.periodicLong("sourceAccountId")
    val destination = item.periodicLong("destinationAccountId")
    require(source > 0 || (type == 4L && destination > 0)) { "流水账户缺失" }
    val category = item.periodicLong("categoryId")
    require(type == 1L || category > 0) { "流水分类缺失" }
    item.periodicAmount("sourceAmount"); item.periodicAmount("destinationAmount")
    item.periodicLong("time", 1); item.periodicLong("utcOffset", -720, 840)
    item.periodicString("comment")
    require(item.containsKey("hideAmount") && item.containsKey("editable") && item["tagIds"] is JsonArray) { "流水详情关联不完整" }
    item.periodicBoolean("hideAmount"); item.periodicBoolean("editable"); item.periodicTags()
}

private fun validatePeriodicFrequency(kind: Int, frequency: String, start: String?) {
    if (kind == 0) { require(frequency.isEmpty()) { "暂停规则无效" }; return }
    val values = frequency.split(',').map { part ->
        part.toLongOrNull()?.takeIf { it.toString() == part } ?: error("周期规则包含无效整数")
    }
    val valid = when (kind) {
        1 -> values.all { it in 0..6 }
        2 -> values.all { it in -31..-1 || it in 1..31 }
        3 -> values.size == 1 && values.single() in 0..1
        4 -> values.all { it in 101..1231 && runCatching { MonthDay.of((it / 100).toInt(), (it % 100).toInt()) }.isSuccess }
        5 -> values.size == 1 && values.single() > 0 && start != null
        else -> false
    }
    require(valid) { "周期规则无效" }
}

internal fun parsePeriodicTemplates(raw: String, ledgerId: Long = 0, expectedType: Int? = null): List<RemoteTemplate> {
    val rows = periodicResult(raw) as? JsonArray ?: error("模板列表响应不完整")
    val ids = mutableSetOf<Long>()
    return rows.map { value ->
        val item = value as? JsonObject ?: error("模板响应无效")
        item.periodicLedger(ledgerId)
        require(!item.periodicBoolean("deleted")) { "模板列表包含已删除对象" }
        val id = item.periodicLong("id", 1)
        val templateType = item.periodicLong("templateType", 1, 2).toInt()
        require(ids.add(id) && (expectedType == null || templateType == expectedType)) { "模板包含重复 ID 或错误类型" }
        val type = item.periodicLong("type", 2, 4).toInt()
        val source = item.periodicLong("sourceAccountId")
        val destination = item.periodicLong("destinationAccountId", default = if (type != 4) 0 else null)
        require(if (type == 4) source > 0 || destination > 0 else source > 0 && destination == 0L) { "模板账户无效" }
        val amount = item.periodicAmount("sourceAmount")
        val destinationAmount = item.periodicAmount("destinationAmount", default = if (type != 4) 0 else null)
        require(type == 3 || amount >= 0) { "非支出模板金额不能为负数" }
        require(destinationAmount >= 0 && (type == 4 || destinationAmount == 0L)) { "模板转入金额无效" }
        val start = item.periodicDate("scheduledStartDate")
        val end = item.periodicDate("scheduledEndDate")
        require(start == null || end == null || start <= end) { "周期日期范围无效" }
        val frequencyType = if (templateType == 2) item.periodicLong("scheduledFrequencyType", 0, 5).toInt() else null
        val frequency = if (templateType == 2) item.periodicString("scheduledFrequency") else null
        if (frequencyType != null) validatePeriodicFrequency(frequencyType, requireNotNull(frequency), start)
        val next = if (item["nextScheduledTime"] == null || item["nextScheduledTime"] == JsonNull) null else item.periodicLong("nextScheduledTime", 1)
        RemoteTemplate(
            id = id, name = item.periodicString("name").also { require(it.isNotBlank()) { "模板缺少名称" } }, type = type,
            categoryId = item.periodicLong("categoryId", 1), sourceAccountId = source,
            sourceAmountMinor = amount, comment = item.periodicString("comment", ""), tagIdsJson = item.periodicTags(), hidden = item.periodicBoolean("hidden"),
            templateType = templateType, destinationAccountId = destination, destinationAmountMinor = destinationAmount,
            hideAmount = item.periodicBoolean("hideAmount"), scheduledFrequencyType = frequencyType, scheduledFrequency = frequency,
            scheduledStartDate = start, scheduledEndDate = end, utcOffset = item.periodicLong("utcOffset", -720, 840, 0).toInt(),
            scheduledAt = if (templateType == 2) item.periodicLong("scheduledAt", 0, 1439, 0).toInt() else null,
            nextScheduledTime = next, displayOrder = item.periodicLong("displayOrder", 0, Int.MAX_VALUE.toLong(), 0).toInt(), ledgerId = ledgerId
        )
    }
}

/** Require an explicit live contract; never infer it from the product version. */
internal fun requireTemplateCreationCapabilities(raw: String, templateType: Int) {
    val item = periodicResult(raw) as? JsonObject ?: error("服务器模板能力响应不完整")
    require(item.periodicLong("creationContractVersion", 1, 1) == 1L &&
        item.periodicBoolean("durableCreation") && item.periodicBoolean("payloadBinding") && item.periodicBoolean("deletedReceipts") &&
        item.periodicLong("maxClientSessionIdBytes", 64, 128) >= 64 && item.periodicString("submissionScope") == "owner-creator-ledger" &&
        item.periodicString("durableCreatePath") == "v1/transaction/templates/add_durable.json") {
        "服务器不支持持久模板创建，请先升级服务端"
    }
    val kinds = item["templateTypes"] as? JsonArray ?: error("服务器模板类型能力缺失")
    val values = kinds.map {
        require(it is JsonPrimitive && !it.isString && it.intOrNull?.let { n -> n in 1..2 } == true) { "服务器模板类型能力无效" }
        it.int
    }
    require(values.distinct().size == values.size && templateType in values) { "服务器未启用此模板类型" }
}

internal fun parseTemplateCreationReceipt(raw: String, ledgerId: Long, templateType: Int): TemplateCreationReceipt {
    val item = periodicResult(raw) as? JsonObject ?: error("模板创建回执不完整")
    require(item.containsKey("ledgerId")) { "模板创建回执缺少账本归属" }
    item.periodicLedger(ledgerId)
    require(item.periodicLong("templateType", 1, 2) == templateType.toLong()) { "模板创建回执类型错误" }
    val id = item.periodicLong("id", 1)
    val deleted = item.periodicBoolean("deleted")
    if (deleted) {
        require(item.containsKey("editable") && !item.periodicBoolean("editable")) { "已删除模板回执不可编辑" }
        require(item["nextScheduledTime"] == null || item["nextScheduledTime"] == JsonNull) { "已删除模板不能包含下次到期时间" }
    } else {
        parsePeriodicTemplates(buildJsonObject {
            put("success", true); put("result", buildJsonArray { add(item) })
        }.toString(), ledgerId, templateType).single()
    }
    return TemplateCreationReceipt(ledgerId, templateType, id, deleted)
}

internal fun parsePeriodicOccurrences(raw: String, ledgerId: Long = 0, expectedStatus: Int? = null): List<RemoteOccurrence> {
    val rows = periodicResult(raw) as? JsonArray ?: error("待确认列表响应不完整")
    val keys = mutableSetOf<Pair<Long, Long>>()
    return rows.map { value ->
        val item = value as? JsonObject ?: error("待确认响应无效")
        item.periodicLedger(ledgerId)
        val templateId = item.periodicLong("templateId", 1)
        val due = item.periodicLong("scheduledUnixTime", 1)
        val status = item.periodicLong("status", 1, 3).toInt()
        require(keys.add(templateId to due) && (expectedStatus == null || status == expectedStatus)) { "待确认包含重复记录或错误状态" }
        val transactionId = item.periodicLong("transactionId", default = 0)
        require(status != 2 || transactionId > 0) { "已确认记录缺少流水 ID" }
        val snapshot = item["snapshot"] as? JsonObject ?: error("待确认记录缺少快照")
        snapshot.periodicLedger(ledgerId)
        val type = snapshot.periodicLong("type", 2, 4).toInt()
        val source = snapshot.periodicLong("sourceAccountId")
        val destination = snapshot.periodicLong("destinationAccountId", default = if (type != 4) 0 else null)
        require(if (type == 4) source > 0 || destination > 0 else source > 0 && destination == 0L) { "待确认账户无效" }
        val amount = snapshot.periodicAmount("sourceAmount")
        val destinationAmount = snapshot.periodicAmount("destinationAmount", default = if (type != 4) 0 else null)
        require(type == 3 || amount >= 0) { "非支出快照金额不能为负数" }
        require(destinationAmount >= 0 && (type == 4 || destinationAmount == 0L)) { "待确认转入金额无效" }
        RemoteOccurrence(templateId, due, status, transactionId, snapshot.periodicString("name", ""), type,
            snapshot.periodicLong("categoryId", 1), source, destination, amount, destinationAmount,
            snapshot.periodicLong("utcOffset", -720, 840).toInt(), snapshot.periodicBoolean("hideAmount"),
            snapshot.periodicTags(), snapshot.periodicString("comment", ""), ledgerId = ledgerId)
    }
}

internal fun parsePeriodicConfirmation(raw: String, ledgerId: Long = 0): Long {
    val result = periodicResult(raw) as? JsonObject ?: error("确认响应缺少结果")
    result.periodicLedger(ledgerId)
    return result.periodicLong("transactionId", 1)
}

internal fun requirePeriodicBooleanResult(raw: String) {
    val result = periodicResult(raw)
    require(result is JsonPrimitive && !result.isString && result.booleanOrNull == true) { "周期操作响应未确认成功" }
}
