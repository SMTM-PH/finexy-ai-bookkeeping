package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Read-only transport. No report content, proof or financial data is persisted here. */
internal class LedgerReportContractException : IllegalArgumentException("报告查询或响应不完整，请重新读取")

internal data class LedgerReportContext(val serverUrl: String, val identity: String, val ledgerId: Long)
internal data class LedgerReportQuery(val json: JsonObject) {
    val ledgerId: Long get() = json.getValue("ledgerId").jsonPrimitive.content.toLong()
    val startDate: String get() = json.getValue("startDate").jsonPrimitive.content
    val endDate: String get() = json.getValue("endDate").jsonPrimitive.content
    val timezone: String get() = json.getValue("timezone").jsonPrimitive.content
    val includesHiddenAmounts: Boolean get() = json.getValue("hidden").jsonPrimitive.content == "include"
}
internal data class LedgerReportRange(val start: Long, val endExclusive: Long)
internal data class LedgerReportMoney(val currency: String, val income: Long, val expense: Long, val net: Long,
                                     val adjustment: Long, val transferIn: Long, val transferOut: Long) {
    internal fun amounts(): List<BigInteger> = listOf(income, expense, net, adjustment, transferIn, transferOut).map(BigInteger::valueOf)
}
internal data class LedgerReportResource(val id: Long, val name: String, val hidden: Boolean,
                                        val parentId: Long? = null, val type: Int? = null, val currency: String? = null)
internal data class LedgerReportLeg(val accountId: Long, val accountName: String, val currency: String, val amount: Long)
internal data class LedgerReportDetail(val id: Long, val physicalIds: List<Long>, val type: Int, val time: Long,
    val sequence: Long, val date: String, val categoryId: Long, val categoryName: String,
    val source: LedgerReportLeg?, val destination: LedgerReportLeg?, val adjustment: Long, val balanceTarget: Long?,
    val tags: List<LedgerReportResource>, val hidden: Boolean, val comment: String, val recorderUid: Long, val payerUid: Long)
internal data class LedgerReportGroup(val key: String, val name: String?, val count: Int, val money: List<LedgerReportMoney>)
internal data class LedgerReportPage(
    val query: LedgerReportQuery, val ranges: List<LedgerReportRange>, val snapshotHash: String, val memberRole: Int,
    val matchedCount: Int, val details: List<LedgerReportDetail>, val totals: List<LedgerReportMoney>,
    val trend: List<LedgerReportGroup>, val categories: List<LedgerReportGroup>, val tagGroups: List<LedgerReportGroup>,
    val accounts: List<LedgerReportResource>, val categoryMetadata: List<LedgerReportResource>, val readableTags: List<LedgerReportResource>,
    val offset: Int, val nextOffset: Int?, val detailsComplete: Boolean, val exportAllowed: Boolean,
    internal val json: JsonObject
) {
    // The server's complete aggregate source is independent of transport pagination.
    val dataComplete: Boolean get() = true
    val tagGroupsAdditive: Boolean get() = false
}
internal data class LedgerReportPreview(val snapshotHash: String, val expiresAt: Long,
    val includesHiddenAmounts: Boolean, internal val previewToken: String, internal val json: JsonObject) {
    override fun toString(): String = "LedgerReportPreview(expiresAt=$expiresAt)"
}
internal class LedgerReportDownload internal constructor(val filename: String, bytes: ByteArray, val rowCount: Int) {
    private val content = bytes.copyOf()
    fun bytes(): ByteArray = content.copyOf()
}

internal object LedgerReportContract {
    const val MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024
    private val parser = Json { isLenient = false; ignoreUnknownKeys = false }
    private val decimals = Regex("(?:0|[1-9][0-9]*|-[1-9][0-9]*)")
    private val hashes = Regex("[0-9a-f]{64}")
    private val queryFields = setOf("ledgerId", "startDate", "endDate", "timezone", "types", "accounts", "categories", "tags", "currencies", "hidden")
    private fun requireReport(value: Boolean) { if (!value) throw LedgerReportContractException() }
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (_: Exception) { throw LedgerReportContractException() }
    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: throw LedgerReportContractException()
    private fun string(value: JsonElement?): String {
        val p = value as? JsonPrimitive ?: throw LedgerReportContractException()
        requireReport(p.isString)
        return p.content
    }
    private fun bool(value: JsonElement?): Boolean {
        val p = value as? JsonPrimitive ?: throw LedgerReportContractException()
        requireReport(!p.isString && p.booleanOrNull != null)
        return p.boolean
    }
    private fun number(value: JsonElement?, min: Int = 0, max: Int = 50000): Int {
        val p = value as? JsonPrimitive ?: throw LedgerReportContractException()
        requireReport(!p.isString && p.content.matches(Regex("0|[1-9][0-9]*")))
        val result = p.content.toIntOrNull() ?: throw LedgerReportContractException()
        requireReport(result in min..max)
        return result
    }
    private fun decimal(value: JsonElement?, min: Long = Long.MIN_VALUE): Long {
        val text = string(value)
        requireReport(text.length <= 20 && text.matches(decimals))
        val result = text.toLongOrNull() ?: throw LedgerReportContractException()
        requireReport(result >= min)
        return result
    }
    private fun id(value: JsonElement?, zero: Boolean = false) = decimal(value, if (zero) 0 else 1)
    private fun list(value: JsonElement?, max: Int = 50000): JsonArray {
        val values = value as? JsonArray ?: throw LedgerReportContractException()
        requireReport(values.size <= max)
        return values
    }
    private fun optional(value: JsonElement?) = value == null || value === JsonNull
    private fun keys(value: JsonObject, allowed: Set<String>) = requireReport(value.keys.all { it in allowed })
    private fun currency(value: JsonElement?): String = string(value).also { requireReport(it.matches(Regex("[A-Z]{3}"))) }
    private fun day(value: JsonElement?): LocalDate {
        val text = string(value)
        requireReport(text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        val day = LocalDate.parse(text)
        requireReport(day.year in 1970..9998 && day.toString() == text)
        return day
    }
    private fun ids(value: JsonElement?, zero: Boolean = false): JsonArray {
        val values = list(value, 1000).map { id(it, zero) }.distinct().sorted()
        requireReport(values.isNotEmpty())
        return JsonArray(values.map { JsonPrimitive(it.toString()) })
    }
    private fun filter(value: JsonElement?, zero: Boolean = false): JsonElement {
        if (optional(value)) return JsonNull
        val row = obj(value)
        keys(row, setOf("ids", "mode", "includeDescendants"))
        val mode = string(row["mode"])
        requireReport(mode in setOf("include", "exclude"))
        return buildJsonObject {
            put("ids", ids(row["ids"], zero)); put("mode", mode)
            put("includeDescendants", row["includeDescendants"]?.let(::bool) ?: false)
        }
    }
    /** Detach and canonicalize all five filter dimensions before starting asynchronous work. */
    fun query(value: JsonObject): LedgerReportQuery = guarded {
        keys(value, queryFields)
        val ledger = id(value["ledgerId"], true)
        val start = day(value["startDate"]); val end = day(value["endDate"])
        requireReport(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 3660)
        val zone = string(value["timezone"])
        requireReport(zone == "UTC" || zone in ZoneId.getAvailableZoneIds())
        val types = if (optional(value["types"])) listOf(1, 2, 3, 4) else list(value["types"], 4).map { number(it, 1, 4) }.distinct().sorted()
        requireReport(types.isNotEmpty())
        val tags = if (optional(value["tags"])) JsonNull else {
            val clauses = list(value["tags"], 20)
            requireReport(clauses.isNotEmpty())
            val values = clauses.map { element ->
                val clause = obj(element); keys(clause, setOf("mode", "ids"))
                val mode = string(clause["mode"])
                requireReport(mode in setOf("any", "all", "notAny", "notAll", "none"))
                val selected = if (mode == "none") {
                    requireReport(clauses.size == 1 && (optional(clause["ids"]) || list(clause["ids"]).isEmpty()))
                    JsonNull
                } else ids(clause["ids"])
                (mode + ":" + if (selected is JsonArray) selected.joinToString(",") { string(it) } else "") to buildJsonObject {
                    put("mode", mode); put("ids", selected)
                }
            }.distinctBy { it.first }.sortedBy { it.first }.map { it.second }
            JsonArray(values)
        }
        val currencies = if (optional(value["currencies"])) JsonNull else JsonArray(list(value["currencies"], 256).map(::currency).distinct().sorted().also { requireReport(it.isNotEmpty()) }.map(::JsonPrimitive))
        val hidden = value["hidden"]?.let(::string)?.takeIf { it.isNotEmpty() } ?: "exclude"
        requireReport(hidden in setOf("include", "exclude"))
        LedgerReportQuery(buildJsonObject {
            put("ledgerId", ledger.toString()); put("startDate", start.toString()); put("endDate", end.toString()); put("timezone", zone)
            put("types", JsonArray(types.map(::JsonPrimitive))); put("accounts", filter(value["accounts"])); put("categories", filter(value["categories"], true))
            put("tags", tags); put("currencies", currencies); put("hidden", hidden)
        })
    }
    fun query(ledgerId: Long, startDate: LocalDate, endDate: LocalDate, timezone: ZoneId): LedgerReportQuery = query(buildJsonObject {
        put("ledgerId", ledgerId.toString()); put("startDate", startDate.toString()); put("endDate", endDate.toString()); put("timezone", timezone.id)
    })
    private fun normalized(value: JsonElement?, expected: LedgerReportQuery): Pair<LedgerReportQuery, List<LedgerReportRange>> {
        val row = obj(value)
        keys(row, queryFields + setOf("version", "utcRanges"))
        requireReport(row.keys.containsAll(queryFields + setOf("version", "utcRanges")) && number(row["version"], 1, 1) == 1)
        requireReport(row["types"] is JsonArray && string(row["hidden"]) in setOf("include", "exclude"))
        val base = query(JsonObject(row.filterKeys { it in queryFields }))
        requireReport(base == expected)
        val lower = LocalDate.parse(base.startDate).toEpochDay() * 86400 - 172800
        val upper = LocalDate.parse(base.endDate).toEpochDay() * 86400 + 259200
        val ranges = list(row["utcRanges"], 1000).map {
            val r = obj(it); keys(r, setOf("start", "endExclusive"))
            val range = LedgerReportRange(decimal(r["start"]), decimal(r["endExclusive"]))
            requireReport(range.start in lower..upper && range.endExclusive in lower..upper && range.start < range.endExclusive)
            range
        }
        ranges.zipWithNext().forEach { (a, b) -> requireReport(a.endExclusive < b.start) }
        return base to ranges
    }
    private fun resource(value: JsonElement, kind: String): LedgerReportResource {
        val r = obj(value)
        val identifier = id(r["id"]); val name = string(r["name"]); val hidden = bool(r["hidden"])
        if (kind == "tag") return LedgerReportResource(identifier, name, hidden)
        val parent = id(r["parentId"], true); val type = number(r["type"], 1, if (kind == "account") 2 else 3)
        val currency = if (kind == "account" && type == 1) currency(r["currency"]) else null
        return LedgerReportResource(identifier, name, hidden, parent, type, currency)
    }
    private fun resources(value: JsonElement?, kind: String): List<LedgerReportResource> {
        val rows = list(value, 20000).map { resource(it, kind) }
        requireReport(rows.map { it.id }.distinct().size == rows.size)
        if (kind != "tag") for (row in rows) if (row.parentId != 0L) {
            val parent = rows.singleOrNull { it.id == row.parentId }
            requireReport(parent != null && parent.parentId == 0L && if (kind == "account") row.type == 1 && parent.type == 2 else parent.type == row.type)
        }
        return rows
    }
    private fun money(value: JsonElement?): List<LedgerReportMoney> {
        val result = list(value, 20000).map {
            val row = obj(it)
            LedgerReportMoney(currency(row["currency"]), decimal(row["income"]), decimal(row["expense"]), decimal(row["net"]), decimal(row["adjustment"]), decimal(row["transferIn"]), decimal(row["transferOut"])).also {
                requireReport(BigInteger.valueOf(it.net) == BigInteger.valueOf(it.income) - BigInteger.valueOf(it.expense))
            }
        }
        requireReport(result.map { it.currency }.distinct().size == result.size)
        return result
    }
    private fun sumMoney(values: List<List<LedgerReportMoney>>): Map<String, List<BigInteger>> {
        val buckets = mutableMapOf<String, List<BigInteger>>()
        for (row in values.flatten()) buckets[row.currency] = (buckets[row.currency] ?: List(6) { BigInteger.ZERO }).zip(row.amounts()) { a, b -> a + b }
        return buckets
    }
    private fun sameMoney(actual: Map<String, List<BigInteger>>, expected: List<LedgerReportMoney>) = requireReport(actual == expected.associate { it.currency to it.amounts() })
    private fun leg(value: JsonElement?, accounts: List<LedgerReportResource>): LedgerReportLeg? {
        if (value === JsonNull) return null
        val row = obj(value)
        val result = LedgerReportLeg(id(row["accountId"]), string(row["accountName"]), currency(row["currency"]), decimal(row["amount"]))
        requireReport(accounts.any { it.id == result.accountId && it.type == 1 && it.name == result.accountName && it.currency == result.currency })
        return result
    }
    private fun filterMatches(value: JsonElement?, ids: List<Long>, metadata: List<LedgerReportResource>): Boolean {
        if (optional(value)) return true
        val filter = obj(value); val selected = list(filter["ids"]).map { id(it, true) }
        val matches = ids.any { it in selected || bool(filter["includeDescendants"]) && metadata.any { row -> row.id == it && row.parentId in selected } }
        return if (string(filter["mode"]) == "exclude") !matches else matches
    }
    private fun tagsMatch(value: JsonElement?, tags: List<LedgerReportResource>): Boolean = optional(value) || list(value).all {
        val filter = obj(it); val mode = string(filter["mode"])
        val selected = if (optional(filter["ids"])) emptyList() else list(filter["ids"]).map { id(it) }
        val found = selected.count { selectedId -> tags.any { tag -> tag.id == selectedId } }
        when (mode) { "none" -> tags.isEmpty(); "any" -> found > 0; "all" -> found == selected.size; "notAny" -> found == 0; "notAll" -> found < selected.size; else -> false }
    }
    private fun details(value: JsonElement?, query: LedgerReportQuery, ranges: List<LedgerReportRange>, accounts: List<LedgerReportResource>, categories: List<LedgerReportResource>, readableTags: List<LedgerReportResource>, maximum: Int): List<LedgerReportDetail> {
        val zone = ZoneId.of(query.timezone)
        val rows = list(value, maximum).map { element ->
            val r = obj(element); val identifier = id(r["id"])
            val physical = list(r["physicalIds"], 2).map { id(it) }
            requireReport(physical.isNotEmpty() && physical.first() == identifier)
            val type = number(r["type"], 1, 4); val time = decimal(r["time"], 0); val sequence = decimal(r["sequence"], 0)
            requireReport(sequence / 1000 == time && ranges.any { time >= it.start && time < it.endExclusive })
            val date = day(r["date"]).toString()
            requireReport(date >= query.startDate && date <= query.endDate && Instant.ofEpochSecond(time).atZone(zone).toLocalDate().toString() == date)
            val categoryId = id(r["categoryId"], true); val categoryName = string(r["categoryName"])
            if (categoryId == 0L) requireReport(categoryName.isEmpty() && type in setOf(1, 4)) else requireReport(categories.any { it.id == categoryId && it.parentId != 0L && it.name == categoryName && it.type == when (type) { 2 -> 1; 3 -> 2; else -> 3 } })
            val source = leg(r["source"], accounts); val destination = leg(r["destination"], accounts)
            val adjustment = decimal(r["adjustment"]); val target = r["balanceTarget"]?.let { decimal(it) }
            if (type == 1) requireReport(source != null && destination == null && physical.size == 1 && categoryId == 0L && target == source.amount)
            else {
                requireReport(adjustment == 0L && target == null)
                if (type == 4) requireReport((source != null || destination != null) && physical.size == if (source != null && destination != null) 2 else 1)
                else requireReport(source != null && destination == null && physical.size == 1 && categoryId != 0L)
                if (source != null && destination != null) requireReport(source.accountId != destination.accountId)
            }
            val tags = list(r["tags"], 20000).map { resource(it, "tag") }
            requireReport(tags.distinctBy { it.id }.size == tags.size && tags.all { it in readableTags })
            val hidden = bool(r["hidden"])
            requireReport(type in list(query.json["types"]).map { number(it) } && (query.includesHiddenAmounts || !hidden))
            requireReport(filterMatches(query.json["accounts"], listOfNotNull(source?.accountId, destination?.accountId), accounts) && filterMatches(query.json["categories"], listOf(categoryId), categories) && tagsMatch(query.json["tags"], tags))
            requireReport(optional(query.json["currencies"]) || list(query.json["currencies"]).map(::string).any { it == source?.currency || it == destination?.currency })
            LedgerReportDetail(identifier, physical, type, time, sequence, date, categoryId, categoryName, source, destination, adjustment, target, tags, hidden, string(r["comment"]), id(r["recorderUid"]), id(r["payerUid"]))
        }
        requireReport(rows.distinctBy { it.id }.size == rows.size && rows.flatMap { it.physicalIds }.distinct().size == rows.sumOf { it.physicalIds.size })
        rows.zipWithNext().forEach { (a, b) -> requireReport(a.sequence > b.sequence || a.sequence == b.sequence && a.id > b.id) }
        return rows
    }
    private fun groups(value: JsonElement?, kind: String, matched: Int, query: LedgerReportQuery, metadata: List<LedgerReportResource>): List<LedgerReportGroup> {
        val rows = list(value).map {
            val row = obj(it); val key = string(row["key"]); val name = row["name"]?.let(::string)
            if (kind == "trend") requireReport(day(JsonPrimitive(key)).toString() >= query.startDate && key <= query.endDate && name.isNullOrEmpty())
            else if (key == if (kind == "tag") "none" else "0") requireReport(name.isNullOrEmpty())
            else requireReport(metadata.any { it.id == id(JsonPrimitive(key)) && it.name == name.orEmpty() })
            LedgerReportGroup(key, name, number(row["count"], 1, matched), money(row["money"]))
        }
        requireReport(rows.distinctBy { it.key }.size == rows.size)
        return rows
    }
    private fun detailMoney(row: LedgerReportDetail): Map<String, List<BigInteger>> {
        val values = listOfNotNull(row.source?.let { "source" to it }, row.destination?.let { "destination" to it }).map { (side, leg) ->
            val zero = BigInteger.ZERO; val amount = BigInteger.valueOf(leg.amount)
            leg.currency to listOf(if (row.type == 2) amount else zero, if (row.type == 3) amount else zero, if (row.type == 2) amount else if (row.type == 3) -amount else zero,
                if (row.type == 1) BigInteger.valueOf(row.adjustment) else zero, if (row.type == 4 && side == "destination") amount else zero, if (row.type == 4 && side == "source") amount else zero)
        }
        return values.groupBy({ it.first }, { it.second }).mapValues { (_, rows) -> rows.reduce { a, b -> a.zip(b) { x, y -> x + y } } }
    }
    private fun detailTotals(rows: List<LedgerReportDetail>): Map<String, List<BigInteger>> {
        val result = mutableMapOf<String, List<BigInteger>>()
        for (row in rows) for ((code, amounts) in detailMoney(row)) result[code] = (result[code] ?: List(6) { BigInteger.ZERO }).zip(amounts) { a, b -> a + b }
        return result
    }
    private fun verifyDetails(rows: List<LedgerReportDetail>, matched: Int, complete: Boolean, totals: List<LedgerReportMoney>, groups: List<Pair<String, List<LedgerReportGroup>>>) {
        requireReport(if (complete) rows.size == matched else rows.size <= matched)
        if (complete) sameMoney(detailTotals(rows), totals)
        for ((kind, values) in groups) {
            val byKey = mutableMapOf<String, MutableList<LedgerReportDetail>>()
            for (row in rows) for (key in when (kind) { "trend" -> listOf(row.date); "category" -> listOf(row.categoryId.toString()); else -> row.tags.map { it.id.toString() }.ifEmpty { listOf("none") } }) byKey.getOrPut(key) { mutableListOf() }.add(row)
            if (complete) requireReport(byKey.keys == values.map { it.key }.toSet())
            for ((key, selected) in byKey) {
                val group = values.singleOrNull { it.key == key } ?: throw LedgerReportContractException()
                requireReport(if (complete) group.count == selected.size else group.count >= selected.size)
                if (complete) sameMoney(detailTotals(selected), group.money)
            }
        }
    }
    /** Reject duplicate (including escaped/case-folded) names before a map parser can erase them. */
    private fun validateJsonStructure(raw: String) {
        var index = 0
        fun whitespace() { while (index < raw.length && raw[index] in " \t\r\n") index++ }
        fun quoted(): String {
            requireReport(index < raw.length && raw[index] == '"')
            val start = index++
            while (index < raw.length) {
                when (raw[index++]) {
                    '\\' -> { requireReport(index < raw.length); index++ }
                    '"' -> return raw.substring(start, index)
                }
            }
            throw LedgerReportContractException()
        }
        fun value(depth: Int) {
            requireReport(depth <= 32)
            whitespace(); requireReport(index < raw.length)
            when (raw[index]) {
                '{' -> {
                    index++; whitespace()
                    if (index < raw.length && raw[index] == '}') { index++; return }
                    val names = mutableSetOf<String>()
                    while (true) {
                        whitespace()
                        val key = parser.parseToJsonElement(quoted()).jsonPrimitive.content.lowercase(java.util.Locale.ROOT)
                        requireReport(names.add(key)); whitespace()
                        requireReport(index < raw.length && raw[index++] == ':')
                        value(depth + 1); whitespace(); requireReport(index < raw.length)
                        val separator = raw[index++]
                        if (separator == '}') return
                        requireReport(separator == ',')
                    }
                }
                '[' -> {
                    index++; whitespace()
                    if (index < raw.length && raw[index] == ']') { index++; return }
                    while (true) {
                        value(depth + 1); whitespace(); requireReport(index < raw.length)
                        val separator = raw[index++]
                        if (separator == ']') return
                        requireReport(separator == ',')
                    }
                }
                '"' -> { quoted() }
                else -> {
                    val start = index
                    while (index < raw.length && raw[index] !in ",]} \t\r\n") index++
                    requireReport(index > start)
                }
            }
        }
        value(0); whitespace(); requireReport(index == raw.length)
    }
    private fun parse(raw: String): JsonObject {
        requireReport(raw.length <= 32 * 1024 * 1024)
        validateJsonStructure(raw)
        return obj(parser.parseToJsonElement(raw))
    }
    private fun result(raw: String): JsonObject {
        val root = parse(raw); requireReport(bool(root["success"]))
        return obj(root["result"])
    }
    fun request(query: LedgerReportQuery, offset: Int = 0, count: Int = 50, expectedHash: String? = null): String = guarded {
        requireReport(query(query.json) == query && offset in 0..50000 && count in 1..200 && (expectedHash == null || expectedHash.matches(hashes)) && (offset == 0 || expectedHash != null))
        buildJsonObject { put("query", query.json); put("offset", offset); put("count", count); expectedHash?.let { put("expectedSnapshotHash", it) } }.toString()
    }
    fun page(raw: String, query: LedgerReportQuery, offset: Int = 0, count: Int = 50, expectedHash: String? = null): LedgerReportPage = guarded {
        request(query, offset, count, expectedHash)
        val page = result(raw); val (normalized, ranges) = normalized(page["query"], query)
        val hash = string(page["snapshotHash"])
        requireReport(hash.matches(hashes) && (expectedHash == null || hash == expectedHash) && bool(page["dataComplete"]) && !bool(page["tagGroupsAdditive"]))
        val matched = number(page["matchedCount"]); requireReport(offset <= matched && number(page["offset"]) == offset)
        val accounts = resources(page["accounts"], "account"); val categories = resources(page["categoryMetadata"], "category"); val tags = resources(page["readableTags"], "tag")
        for ((key, meta) in listOf("accounts" to accounts, "categories" to categories)) if (!optional(query.json[key])) for (selected in list(obj(query.json[key])["ids"])) requireReport(id(selected, key == "categories") == 0L || meta.any { it.id == id(selected) })
        if (!optional(query.json["tags"])) for (clause in list(query.json["tags"])) if (!optional(obj(clause)["ids"])) for (selected in list(obj(clause)["ids"])) requireReport(tags.any { it.id == id(selected) })
        val details = details(page["details"], normalized, ranges, accounts, categories, tags, 200)
        requireReport(number(page["returnedCount"], 0, 200) == details.size && details.size == minOf(count, matched - offset))
        val next = if (offset + details.size < matched) offset + details.size else null
        requireReport(if (next == null) page["nextOffset"] === JsonNull else number(page["nextOffset"]) == next)
        val complete = offset == 0 && details.size == matched
        requireReport(bool(page["detailsComplete"]) == complete)
        val totals = money(page["totals"]); requireReport((matched == 0) == totals.isEmpty())
        val trend = groups(page["trend"], "trend", matched, normalized, emptyList()); val categoryGroups = groups(page["categories"], "category", matched, normalized, categories); val tagGroups = groups(page["tagGroups"], "tag", matched, normalized, tags)
        for (partition in listOf(trend, categoryGroups)) { requireReport(partition.sumOf { it.count } == matched); sameMoney(sumMoney(partition.map { it.money }), totals) }
        requireReport((matched == 0) == tagGroups.isEmpty() && tagGroups.sumOf { it.count } >= matched && tagGroups.all { group -> group.money.all { row -> totals.any { it.currency == row.currency } } })
        verifyDetails(details, matched, complete, totals, listOf("trend" to trend, "category" to categoryGroups, "tag" to tagGroups))
        LedgerReportPage(normalized, ranges, hash, number(page["memberRole"], 1, 4), matched, details, totals, trend, categoryGroups, tagGroups, accounts, categories, tags, offset, next, complete, bool(page["exportAllowed"]), page)
    }
    /** Continuation pages must share the same aggregate source and immutable metadata. */
    fun append(first: LedgerReportPage, accumulated: List<LedgerReportDetail>, next: LedgerReportPage): List<LedgerReportDetail> = guarded {
        requireReport(first.offset == 0 && next.offset == accumulated.size && first.snapshotHash == next.snapshotHash && first.query == next.query && first.ranges == next.ranges)
        val ignored = setOf("offset", "returnedCount", "nextOffset", "detailsComplete", "details", "exportAllowed")
        requireReport(first.json.filterKeys { it !in ignored } == next.json.filterKeys { it !in ignored })
        requireReport(accumulated.take(first.details.size) == first.details)
        val rows = accumulated + next.details
        requireReport(rows.distinctBy { it.id }.size == rows.size && rows.flatMap { it.physicalIds }.distinct().size == rows.sumOf { it.physicalIds.size })
        rows.zipWithNext().forEach { (a, b) -> requireReport(a.sequence > b.sequence || a.sequence == b.sequence && a.id > b.id) }
        verifyDetails(rows, first.matchedCount, next.nextOffset == null, first.totals, listOf("trend" to first.trend, "category" to first.categories, "tag" to first.tagGroups))
        rows.toList()
    }
    fun previewRequest(page: LedgerReportPage): String = guarded {
        requireReport(page.exportAllowed)
        buildJsonObject { put("query", page.query.json); put("expectedSnapshotHash", page.snapshotHash) }.toString()
    }
    fun preview(raw: String, page: LedgerReportPage, nowSeconds: Long): LedgerReportPreview = guarded {
        requireReport(page.exportAllowed)
        val p = result(raw); val (query, ranges) = normalized(p["query"], page.query)
        requireReport(query == page.query && ranges == page.ranges && string(p["snapshotHash"]) == page.snapshotHash && number(p["matchedCount"]) == page.matchedCount && string(p["amountUnit"]) == "minor" && bool(p["includesHiddenAmounts"]) == query.includesHiddenAmounts)
        sameMoney(sumMoney(listOf(money(p["totals"]))), page.totals)
        // The server validates the signed token's issuance lifetime. Its clock can
        // lead this device, so client expiry must not infer that lifetime from now.
        val expires = decimal(p["expiresAt"], 1); requireReport(nowSeconds >= 0 && expires > nowSeconds)
        val token = string(p["previewToken"]); requireReport(token.length in 3..4096 && token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
        LedgerReportPreview(page.snapshotHash, expires, query.includesHiddenAmounts, token, p)
    }
    fun exportRequest(page: LedgerReportPage, preview: LedgerReportPreview, confirmed: Boolean, includeHiddenAmounts: Boolean = false, nowSeconds: Long): String = guarded {
        val checked = preview(buildJsonObject { put("success", true); put("result", preview.json) }.toString(), page, nowSeconds)
        requireReport(checked == preview && confirmed && (!preview.includesHiddenAmounts || includeHiddenAmounts))
        buildJsonObject { put("query", page.query.json); put("expectedSnapshotHash", page.snapshotHash); put("previewToken", preview.previewToken); put("confirmed", true); put("includeHiddenAmounts", includeHiddenAmounts) }.toString()
    }
    fun formatAmount(minor: Long): String {
        val amount = BigInteger.valueOf(minor); val absolute = amount.abs()
        return (if (minor < 0) "-" else "") + absolute.divide(BigInteger.valueOf(100)).toString() + "." + absolute.remainder(BigInteger.valueOf(100)).toString().padStart(2, '0')
    }
    private val csvHeader = ("report_version,snapshot_hash,ledger_id,timezone,start_date,end_date,amount_unit,hidden_policy,id,physical_ids_json,type,unix_seconds,sequence,local_date,category_id,category_name,source_account_id,source_account_name,source_currency,source_amount_minor,destination_account_id,destination_account_name,destination_currency,destination_amount_minor,adjustment_minor,balance_target_minor,tag_ids_json,tag_names_json,hidden,comment,recorder_uid,payer_uid,major_unit_scale,source_amount_major,destination_amount_major,adjustment_major,balance_target_major").split(',')
    private fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var index = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0); closed = false; requireReport(row.size <= csvHeader.size) }
        fun endRow() { endCell(); requireReport(row.size == csvHeader.size && rows.size <= 50000); rows.add(row); row = mutableListOf() }
        while (index < text.length) {
            val c = text[index++]
            if (quoted) {
                if (c == '"') { if (index < text.length && text[index] == '"') { cell.append('"'); index++ } else { quoted = false; closed = true } }
                else cell.append(c)
            } else when (c) {
                '"' -> { requireReport(cell.isEmpty() && !closed); quoted = true }
                ',' -> endCell()
                '\n' -> endRow()
                '\r' -> { requireReport(index < text.length && text[index++] == '\n'); endRow() }
                else -> { requireReport(!closed); cell.append(c) }
            }
        }
        requireReport(!quoted)
        if (row.isNotEmpty() || cell.isNotEmpty() || closed) endRow()
        requireReport(rows.isNotEmpty() && rows.first() == csvHeader)
        return rows.drop(1)
    }
    private fun csvProtected(value: String): String {
        if (value.startsWith("'")) return "'$value"
        var start = 0
        while (start < value.length) {
            val codePoint = value.codePointAt(start)
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) && !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) break
            start += Character.charCount(codePoint)
        }
        val trimmed = value.substring(start)
        return if (trimmed.firstOrNull() in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
    }
    private fun csvText(value: String): String {
        val decoded = if (value.startsWith("'")) value.drop(1) else value
        requireReport(csvProtected(decoded) == value)
        return decoded
    }
    /** Validates real downloaded bytes; callers still need a successful system document save. */
    fun download(bytes: ByteArray, headers: Map<String, String>, page: LedgerReportPage): LedgerReportDownload = guarded {
        requireReport(bytes.isNotEmpty() && bytes.size <= MAX_DOWNLOAD_BYTES)
        val h = headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
        requireReport(h.size == headers.size)
        val filename = "finexy-report-${page.query.ledgerId}-${page.query.startDate}-${page.query.endDate}.csv"
        requireReport(h["content-type"]?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) == "text/csv")
        val disposition = h["content-disposition"].orEmpty()
        val safeFilename = Regex.escape(filename)
        requireReport(disposition.matches(Regex("attachment;\\s*filename=(?:\\\"$safeFilename\\\"|$safeFilename)", RegexOption.IGNORE_CASE)))
        requireReport(h["x-finexy-report-snapshot"] == page.snapshotHash && h["x-finexy-report-count"] == page.matchedCount.toString() && h["x-finexy-report-amount-unit"] == "minor")
        h["content-length"]?.let { requireReport(it.toLongOrNull() == bytes.size.toLong()) }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val records = csv(text); requireReport(records.size == page.matchedCount)
        val detailsJson = JsonArray(records.map { fields ->
            val c = csvHeader.zip(fields).toMap()
            fun value(key: String) = c.getValue(key)
            for ((key, expected) in listOf("report_version" to "1", "snapshot_hash" to page.snapshotHash, "ledger_id" to page.query.ledgerId.toString(), "timezone" to page.query.timezone, "start_date" to page.query.startDate, "end_date" to page.query.endDate, "amount_unit" to "minor", "hidden_policy" to if (page.query.includesHiddenAmounts) "include" else "exclude", "major_unit_scale" to "2")) requireReport(value(key) == expected)
            for ((minor, major) in listOf("source_amount_minor" to "source_amount_major", "destination_amount_minor" to "destination_amount_major", "adjustment_minor" to "adjustment_major", "balance_target_minor" to "balance_target_major")) requireReport(if (value(minor).isEmpty()) value(major).isEmpty() else formatAmount(decimal(JsonPrimitive(value(minor)))) == value(major))
            fun leg(prefix: String): JsonElement {
                val fields = listOf("account_id", "account_name", "currency", "amount_minor").map { value(prefix + it) }
                if (fields.all { it.isEmpty() }) return JsonNull
                return buildJsonObject { put("accountId", fields[0]); put("accountName", csvText(fields[1])); put("currency", fields[2]); put("amount", fields[3]) }
            }
            val tagIds = list(parser.parseToJsonElement(value("tag_ids_json")))
            val tagNames = list(parser.parseToJsonElement(value("tag_names_json")))
            requireReport(tagIds.size == tagNames.size)
            val tags = JsonArray(tagIds.zip(tagNames).map { (tagId, tagName) ->
                val known = page.readableTags.singleOrNull { it.id == id(tagId) && it.name == string(tagName) } ?: throw LedgerReportContractException()
                buildJsonObject { put("id", known.id.toString()); put("name", known.name); put("hidden", known.hidden) }
            })
            requireReport(value("hidden") in setOf("true", "false"))
            buildJsonObject {
                requireReport(value("type").matches(Regex("[1-4]")))
                put("id", value("id")); put("physicalIds", parser.parseToJsonElement(value("physical_ids_json"))); put("type", value("type").toInt())
                put("time", value("unix_seconds")); put("sequence", value("sequence")); put("date", value("local_date")); put("categoryId", value("category_id")); put("categoryName", csvText(value("category_name")))
                put("source", leg("source_")); put("destination", leg("destination_")); put("adjustment", value("adjustment_minor")); if (value("balance_target_minor").isNotEmpty()) put("balanceTarget", value("balance_target_minor"))
                put("tags", tags); put("hidden", value("hidden") == "true"); put("comment", csvText(value("comment"))); put("recorderUid", value("recorder_uid")); put("payerUid", value("payer_uid"))
            }
        })
        val rows = details(detailsJson, page.query, page.ranges, page.accounts, page.categoryMetadata, page.readableTags, 50000)
        verifyDetails(rows, page.matchedCount, true, page.totals, listOf("trend" to page.trend, "category" to page.categories, "tag" to page.tagGroups))
        requireReport(rows.drop(page.offset).take(page.details.size) == page.details)
        LedgerReportDownload(filename, bytes, rows.size)
    }
}
