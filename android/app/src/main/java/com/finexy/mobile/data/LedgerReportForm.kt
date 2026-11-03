package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId

/** Only editable query choices, never financial results or export proofs. */
internal data class LedgerReportForm(
    val start: String, val end: String, val timezone: String,
    val types: Set<Int> = setOf(1, 2, 3, 4),
    val accountIds: Set<Long> = emptySet(), val excludeAccounts: Boolean = false, val accountChildren: Boolean = true,
    val categoryIds: Set<Long> = emptySet(), val excludeCategories: Boolean = false, val categoryChildren: Boolean = true,
    val tags: List<LedgerReportTagChoice> = emptyList(), val currencies: Set<String> = emptySet(), val hidden: Boolean = false
) {
    fun query(ledgerId: Long): LedgerReportQuery = LedgerReportContract.query(buildJsonObject {
        put("ledgerId", ledgerId.toString()); put("startDate", start); put("endDate", end); put("timezone", timezone)
        put("types", JsonArray(types.sorted().map(::JsonPrimitive)))
        fun selection(ids: Set<Long>, exclude: Boolean, descendants: Boolean): JsonElement =
            if (ids.isEmpty()) JsonNull else buildJsonObject {
                put("mode", if (exclude) "exclude" else "include")
                put("ids", JsonArray(ids.sorted().map { JsonPrimitive(it.toString()) }))
                put("includeDescendants", descendants)
            }
        put("accounts", selection(accountIds, excludeAccounts, accountChildren))
        put("categories", selection(categoryIds, excludeCategories, categoryChildren))
        put("tags", if (tags.isEmpty()) JsonNull else JsonArray(tags.map { choice -> buildJsonObject {
            put("mode", choice.mode)
            put("ids", if (choice.mode == "none") JsonNull else JsonArray(choice.ids.sorted().map { JsonPrimitive(it.toString()) }))
        } }))
        put("currencies", if (currencies.isEmpty()) JsonNull else JsonArray(currencies.sorted().map(::JsonPrimitive)))
        put("hidden", if (hidden) "include" else "exclude")
    })

    companion object {
        fun currentMonth(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) = LedgerReportForm(
            today.withDayOfMonth(1).toString(), today.withDayOfMonth(today.lengthOfMonth()).toString(), zone.id)

        fun from(query: LedgerReportQuery): LedgerReportForm {
            val json = query.json
            fun selection(key: String) = json[key] as? JsonObject
            fun selected(key: String) = selection(key)?.get("ids")?.jsonArray.orEmpty().map { it.jsonPrimitive.content.toLong() }.toSet()
            return LedgerReportForm(query.startDate, query.endDate, query.timezone,
                json.getValue("types").jsonArray.map { it.jsonPrimitive.int }.toSet(),
                selected("accounts"), selection("accounts")?.get("mode")?.jsonPrimitive?.content == "exclude",
                selection("accounts")?.get("includeDescendants")?.jsonPrimitive?.boolean ?: true,
                selected("categories"), selection("categories")?.get("mode")?.jsonPrimitive?.content == "exclude",
                selection("categories")?.get("includeDescendants")?.jsonPrimitive?.boolean ?: true,
                (json["tags"] as? JsonArray).orEmpty().map { clause ->
                    val row = clause.jsonObject
                    LedgerReportTagChoice(row.getValue("mode").jsonPrimitive.content,
                        (row["ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toLong() }.toSet())
                }, (json["currencies"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet(), query.includesHiddenAmounts)
        }
    }
}

internal data class LedgerReportTagChoice(val mode: String, val ids: Set<Long> = emptySet())

/** A group was already selected by the original query: narrowing keeps every other dimension. */
internal fun narrowLedgerReport(query: LedgerReportQuery, kind: String, key: String, currency: String): LedgerReportQuery {
    val changes = query.json.toMutableMap()
    require(currency.matches(Regex("[A-Z]{3}")))
    val selectedCurrencies = query.json["currencies"] as? JsonArray
    require(selectedCurrencies == null || selectedCurrencies.any { it.jsonPrimitive.content == currency })
    changes["currencies"] = JsonArray(listOf(JsonPrimitive(currency)))
    when (kind) {
        "day" -> {
            val day = LocalDate.parse(key)
            require(!day.isBefore(LocalDate.parse(query.startDate)) && !day.isAfter(LocalDate.parse(query.endDate)))
            changes["startDate"] = JsonPrimitive(key); changes["endDate"] = JsonPrimitive(key)
        }
        "category" -> {
            require(key.toLong() >= 0)
            changes["categories"] = buildJsonObject {
                put("mode", "include"); put("ids", JsonArray(listOf(JsonPrimitive(key)))); put("includeDescendants", false)
            }
        }
        "tag" -> changes["tags"] = if (key == "none") JsonArray(listOf(buildJsonObject { put("mode", "none"); put("ids", JsonNull) }))
            else {
                require(key.toLong() > 0)
                JsonArray((query.json["tags"] as? JsonArray).orEmpty() + buildJsonObject {
                    put("mode", "any"); put("ids", JsonArray(listOf(JsonPrimitive(key))))
                })
            }
        else -> error("报告分组不支持下钻")
    }
    return LedgerReportContract.query(JsonObject(changes))
}

internal fun previousFullReportMonth(query: LedgerReportQuery): LedgerReportQuery {
    val start = LocalDate.parse(query.startDate); val end = LocalDate.parse(query.endDate)
    require(start.dayOfMonth == 1 && end == start.withDayOfMonth(start.lengthOfMonth()))
    val previous = start.minusMonths(1)
    return LedgerReportContract.query(JsonObject(query.json + mapOf(
        "startDate" to JsonPrimitive(previous.toString()),
        "endDate" to JsonPrimitive(previous.withDayOfMonth(previous.lengthOfMonth()).toString()))))
}
