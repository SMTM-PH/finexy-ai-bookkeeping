package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All financial expectations come from the repository's shared synthetic fixture. */
class LedgerReportContractTest {
    private val fixtureFile = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { File(it, "testdata/ledger_report/v1.json") }.first { it.isFile }
    private val fixture = Json.parseToJsonElement(fixtureFile.readText(Charsets.UTF_8)).jsonObject
    private val expected = fixture.getValue("expected").jsonObject
    private val query = LedgerReportContract.query(fixture.getValue("query").jsonObject)
    private val hash = "ab".repeat(32)
    private val now = 1_791_633_700L
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun envelope(result: JsonElement) = buildJsonObject { put("success", true); put("result", result) }.toString()
    private fun patch(value: JsonObject, vararg changes: Pair<String, JsonElement>) = JsonObject(value + changes.toMap())
    private fun rejected(block: () -> Unit) { assertThrows(LedgerReportContractException::class.java, block) }
    private fun metadata(row: JsonObject, tag: Boolean = false) = buildJsonObject {
        put("id", row.getValue("id")); put("name", row.getValue("name")); put("hidden", row["hidden"] ?: JsonPrimitive(false))
        if (!tag) { put("parentId", row["parentId"] ?: JsonPrimitive("0")); put("type", row.getValue("type")); row["currency"]?.let { put("currency", it) } }
    }
    private fun logicalDetail(identifier: String): JsonObject {
        val row = fixture.rows("transactions").single { it.s("id") == identifier }
        val partner = row["relatedId"]?.let { id -> fixture.rows("transactions").single { it.getValue("id") == id } }
        val kind = row.s("type").toInt().let { if (it == 5) 4 else it }
        fun leg(entry: JsonObject) = buildJsonObject {
            val account = fixture.rows("accounts").single { it.getValue("id") == entry.getValue("accountId") }
            put("accountId", account.getValue("id")); put("accountName", account.getValue("name")); put("currency", account.getValue("currency")); put("amount", entry.getValue("amount"))
        }
        val tagIds = (row["tagIds"]?.jsonArray.orEmpty() + partner?.get("tagIds")?.jsonArray.orEmpty()).distinct()
        return buildJsonObject {
            put("id", row.getValue("id")); put("physicalIds", JsonArray(listOfNotNull(row["id"], partner?.get("id"))))
            put("type", kind); put("time", row.getValue("transactionTime")); put("sequence", (row.s("transactionTime").toLong() * 1000).toString())
            put("date", Instant.ofEpochSecond(row.s("transactionTime").toLong()).atZone(ZoneId.of(fixture.s("timezone"))).toLocalDate().toString())
            put("categoryId", row["categoryId"] ?: JsonPrimitive("0")); put("categoryName", row["categoryId"]?.let { id -> fixture.rows("categories").single { it["id"] == id }.getValue("name") } ?: JsonPrimitive(""))
            put("source", if (row.s("type") == "5") JsonNull else leg(row)); put("destination", if (row.s("type") == "5") leg(row) else partner?.let(::leg) ?: JsonNull)
            put("adjustment", if (kind == 1) row.getValue("relatedAccountAmount") else JsonPrimitive("0")); if (kind == 1) put("balanceTarget", row.getValue("amount"))
            put("tags", JsonArray(fixture.rows("tags").filter { it["id"] in tagIds }.map { metadata(it, true) }))
            put("hidden", row["hideAmount"] ?: JsonPrimitive(false)); put("comment", row["comment"] ?: JsonPrimitive("")); put("recorderUid", "100"); put("payerUid", "100")
        }
    }
    private val moneyFields = listOf("income", "expense", "net", "adjustment", "transferIn", "transferOut")
    /** Transport-only grouping adapter. Top-level and named-case amounts are asserted against v1.json. */
    private fun aggregate(rows: List<JsonObject>): JsonArray {
        val buckets = sortedMapOf<String, MutableList<BigInteger>>()
        for (row in rows) for (side in listOf("source", "destination")) {
            val leg = row[side] as? JsonObject ?: continue
            val bucket = buckets.getOrPut(leg.s("currency")) { MutableList(6) { BigInteger.ZERO } }
            val amount = leg.s("amount").toBigInteger()
            when (row.s("type")) {
                "1" -> bucket[3] += row.s("adjustment").toBigInteger()
                "2" -> { bucket[0] += amount; bucket[2] += amount }
                "3" -> { bucket[1] += amount; bucket[2] -= amount }
                "4" -> bucket[if (side == "source") 5 else 4] += amount
            }
        }
        return JsonArray(buckets.map { (code, amounts) -> buildJsonObject { put("currency", code); moneyFields.zip(amounts).forEach { (key, amount) -> put(key, amount.toString()) } } })
    }
    private fun pageJson(query: LedgerReportQuery = this.query, ids: List<String> = expected.getValue("detailIds").jsonArray.map { it.jsonPrimitive.content }, totals: JsonArray? = null, overrides: Map<String, JsonObject> = emptyMap()): JsonObject {
        val rows = ids.map { overrides[it] ?: logicalDetail(it) }
        val accounts = fixture.rows("accounts").filter { it.s("ledgerId") == query.ledgerId.toString() }.map { metadata(it) }
        val categories = fixture.rows("categories").filter { it.s("uid") == "100" && it["databaseOnly"] != JsonPrimitive(true) }.map { metadata(it) }
        val tags = fixture.rows("tags").filter { it["id"] in expected.getValue("readableTagIds").jsonArray }.map { metadata(it, true) }
        fun groups(kind: String): JsonArray {
            val selected = mutableMapOf<String, MutableList<JsonObject>>()
            for (row in rows) for (key in when (kind) { "trend" -> listOf(row.s("date")); "category" -> listOf(row.s("categoryId")); else -> row.rows("tags").map { it.s("id") }.ifEmpty { listOf("none") } }) selected.getOrPut(key) { mutableListOf() }.add(row)
            return JsonArray(selected.map { (key, details) -> buildJsonObject {
                put("key", key); put("count", details.size); put("money", aggregate(details))
                if (kind == "category" && key != "0") put("name", categories.single { it.s("id") == key }.getValue("name"))
                if (kind == "tag" && key != "none") put("name", tags.single { it.s("id") == key }.getValue("name"))
            } })
        }
        val zone = ZoneId.of(query.timezone)
        return buildJsonObject {
            put("query", JsonObject(query.json + mapOf("version" to JsonPrimitive(1), "utcRanges" to buildJsonArray {
                add(buildJsonObject { put("start", LocalDate.parse(query.startDate).atStartOfDay(zone).toEpochSecond().toString()); put("endExclusive", LocalDate.parse(query.endDate).plusDays(1).atStartOfDay(zone).toEpochSecond().toString()) })
            })))
            put("snapshotHash", hash); put("memberRole", 1); put("dataComplete", true); put("matchedCount", rows.size); put("details", JsonArray(rows))
            put("totals", totals ?: if (overrides.isEmpty() && query == this@LedgerReportContractTest.query && ids == expected.getValue("detailIds").jsonArray.map { it.jsonPrimitive.content }) expected.getValue("totals") else aggregate(rows))
            put("trend", groups("trend")); put("categories", groups("category")); put("tagGroups", groups("tag")); put("tagGroupsAdditive", false)
            put("accounts", JsonArray(accounts)); put("categoryMetadata", JsonArray(categories)); put("readableTags", JsonArray(tags))
            put("offset", 0); put("returnedCount", rows.size); put("nextOffset", JsonNull); put("detailsComplete", true); put("exportAllowed", true)
        }
    }
    private fun page(value: JsonObject = pageJson(), query: LedgerReportQuery = this.query) = LedgerReportContract.page(envelope(value), query)
    private fun previewJson(page: JsonObject = pageJson(), expires: Long = now + 600): JsonObject = buildJsonObject {
        put("query", page.getValue("query")); put("snapshotHash", page.getValue("snapshotHash")); put("matchedCount", page.getValue("matchedCount")); put("totals", page.getValue("totals"))
        put("amountUnit", "minor"); put("includesHiddenAmounts", page.getValue("query").jsonObject.s("hidden") == "include"); put("expiresAt", expires.toString()); put("previewToken", "synthetic.proof")
    }

    @Test fun fixtureIsSyntheticAndOriginalCurrencyAmountsAreExact() {
        assertEquals(JsonPrimitive(true), fixture["synthetic"])
        val value = page()
        assertEquals(expected.getValue("detailIds").jsonArray.map { it.jsonPrimitive.content.toLong() }, value.details.map { it.id })
        assertEquals(expected.getValue("totals"), aggregate(value.json.rows("details")))
        assertEquals(expected.getValue("categoryCounts").jsonObject.mapValues { it.value.jsonPrimitive.int }, value.categories.associate { it.key to it.count })
        assertEquals(expected.getValue("tagCounts").jsonObject.mapValues { it.value.jsonPrimitive.int }, value.tagGroups.associate { it.key to it.count })
        assertFalse(value.tagGroupsAdditive)
        val transfer = expected.getValue("transfer").jsonObject
        val row = value.details.single { it.id.toString() == transfer.s("id") }
        assertEquals(transfer.s("sourceAmount").toLong(), row.source!!.amount)
        assertEquals(transfer.s("destinationAmount").toLong(), row.destination!!.amount)
        assertEquals(transfer.getValue("physicalIds").jsonArray.map { it.jsonPrimitive.content.toLong() }, row.physicalIds)
        assertEquals(2, value.categoryMetadata.count { it.name == fixture.rows("categories").single { it.s("id") == "1002" }.s("name") })
    }
    @Test fun everySharedFilterCaseRetainsItsExplicitLogicalIdOracle() {
        for (scenario in fixture.rows("cases")) {
            val q = LedgerReportContract.query(JsonObject(fixture.getValue("query").jsonObject + scenario.getValue("query").jsonObject))
            val ids = scenario.getValue("detailIds").jsonArray.map { it.jsonPrimitive.content }
            val json = pageJson(q, ids, scenario["totals"] as? JsonArray)
            val report = page(json, q)
            assertEquals(scenario.s("name"), ids, report.details.map { it.id.toString() })
            scenario["totals"]?.let { assertEquals(it, aggregate(json.rows("details"))) }
            // The same canonical query must reject any fixture row outside the chosen result set.
            val excluded = (expected.getValue("detailIds").jsonArray.map { it.jsonPrimitive.content } - ids.toSet()).firstOrNull()
            if (excluded != null) rejected { page(pageJson(q, (ids + excluded).sortedByDescending { logicalDetail(it).s("sequence").toLong() }), q) }
        }
    }
    @Test fun canonicalQueryHasNoFloatingPointOrImplicitLedgerFallback() {
        val default = LedgerReportContract.query(patch(query.json, "ledgerId" to JsonPrimitive("0"), "categories" to buildJsonObject { put("mode", "include"); put("ids", buildJsonArray { add("0") }) }))
        assertEquals(0L, default.ledgerId)
        assertEquals("0", default.json.getValue("categories").jsonObject.getValue("ids").jsonArray.single().jsonPrimitive.content)
        val large = "9007199254740993"
        assertEquals(large, LedgerReportContract.query(patch(query.json, "ledgerId" to JsonPrimitive(large))).json.s("ledgerId"))
        val cases = listOf("ledgerId" to JsonPrimitive(11), "ledgerId" to JsonPrimitive("01"), "ledgerId" to JsonPrimitive("9223372036854775808"),
            "startDate" to JsonPrimitive("2026-02-30"), "endDate" to JsonPrimitive("2037-01-01"), "timezone" to JsonPrimitive("Local"), "timezone" to JsonPrimitive("+08:00"),
            "types" to JsonArray(emptyList()), "accounts" to buildJsonObject { put("mode", "include"); put("ids", buildJsonArray { add("0") }) },
            "tags" to buildJsonArray { add(buildJsonObject { put("mode", "any"); put("ids", buildJsonArray { add("0") }) }) })
        for (invalid in cases) rejected { LedgerReportContract.query(patch(query.json, invalid)) }
        val ordered = LedgerReportContract.query(patch(query.json, "types" to buildJsonArray { add(3); add(2); add(3) }, "accounts" to buildJsonObject { put("mode", "exclude"); put("ids", buildJsonArray { add(large); add("2"); add("2") }) }))
        assertEquals(listOf("2", large), ordered.json.getValue("accounts").jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(2, 3), ordered.json.getValue("types").jsonArray.map { it.jsonPrimitive.int })
    }
    @Test fun allSharedNaturalDayRangesPreserveIanaDstSkipsAndDisjointDays() {
        val repository = requireNotNull(requireNotNull(requireNotNull(fixtureFile.parentFile).parentFile).parentFile)
        val dates = Json.parseToJsonElement(File(repository, "pkg/models/testdata/ledger_report_dates.json").readText(Charsets.UTF_8)).jsonArray
        assertEquals(12, dates.size)
        for (element in dates) {
            val date = element.jsonObject
            val q = LedgerReportContract.query(patch(query.json, "startDate" to date.getValue("startDate"), "endDate" to date.getValue("endDate"), "timezone" to date.getValue("timezone")))
            val ranges = JsonArray(date.getValue("utcRanges").jsonArray.map { pair -> buildJsonObject {
                put("start", Instant.parse(pair.jsonArray[0].jsonPrimitive.content).epochSecond.toString())
                put("endExclusive", Instant.parse(pair.jsonArray[1].jsonPrimitive.content).epochSecond.toString())
            } })
            val base = pageJson(q, emptyList())
            val json = patch(base, "query" to patch(base.getValue("query").jsonObject, "utcRanges" to ranges))
            val report = page(json, q)
            assertEquals(date.s("name"), ranges.size, report.ranges.size)
            assertTrue(report.detailsComplete)
            assertEquals(0, report.matchedCount)
        }
    }
    @Test fun partialPagesKeepWholeReportTotalsAndRequireContinuationHash() {
        val full = pageJson(); val details = full.getValue("details").jsonArray
        fun slice(offset: Int, count: Int) = patch(full, "details" to JsonArray(details.drop(offset).take(count)), "offset" to JsonPrimitive(offset), "returnedCount" to JsonPrimitive(minOf(count, details.size - offset)), "nextOffset" to if (offset + count < details.size) JsonPrimitive(offset + count) else JsonNull, "detailsComplete" to JsonPrimitive(false))
        val first = LedgerReportContract.page(envelope(slice(0, 3)), query, 0, 3)
        assertTrue(first.dataComplete); assertFalse(first.detailsComplete); assertEquals(page().totals, first.totals)
        rejected { LedgerReportContract.request(query, 3, 3) }
        rejected { LedgerReportContract.page(envelope(slice(3, 3)), query, 3, 3, "cd".repeat(32)) }
        val second = LedgerReportContract.page(envelope(slice(3, 3)), query, 3, 3, hash)
        val third = LedgerReportContract.page(envelope(slice(6, 3)), query, 6, 3, hash)
        val accumulated = LedgerReportContract.append(first, LedgerReportContract.append(first, first.details, second), third)
        assertEquals(page().details, accumulated)
        rejected { LedgerReportContract.append(first, first.details, third) }
        val movedTotals = patch(slice(3, 3), "accounts" to JsonArray(full.rows("accounts").mapIndexed { index, row -> if (index == 0) patch(row, "hidden" to JsonPrimitive(true)) else row }))
        val moved = LedgerReportContract.page(envelope(movedTotals), query, 3, 3, hash)
        rejected { LedgerReportContract.append(first, first.details, moved) }
    }
    @Test fun corruptOrMissingFinancialResponsesAreNeverEmptyOrRounded() {
        val baseline = pageJson(); val rows = baseline.getValue("details").jsonArray
        val invalid = listOf(patch(baseline, "dataComplete" to JsonPrimitive(false)), patch(baseline, "detailsComplete" to JsonPrimitive(false)), patch(baseline, "tagGroupsAdditive" to JsonPrimitive(true)), patch(baseline, "returnedCount" to JsonPrimitive(0)),
            patch(baseline, "details" to JsonArray(rows + rows.first())),
            patch(baseline, "details" to JsonArray(rows.mapIndexed { index, row -> if (index == 0) patch(row.jsonObject, "adjustment" to JsonPrimitive(1000)) else row })),
            patch(baseline, "details" to JsonArray(rows.mapIndexed { index, row -> if (index == 0) patch(row.jsonObject, "sequence" to JsonPrimitive("9223372036854775808")) else row })),
            patch(baseline, "totals" to JsonArray(baseline.rows("totals").mapIndexed { index, row -> if (index == 0) patch(row, "income" to JsonPrimitive("10000.0")) else row })),
            patch(baseline, "categoryMetadata" to JsonArray(baseline.rows("categoryMetadata").map { if (it.s("id") == "1002") patch(it, "parentId" to JsonPrimitive("987")) else it })))
        for (value in invalid) rejected { page(value) }
        for (raw in listOf("{}", "{\"success\":true}", "{\"success\":true,\"result\":null}", "{\"success\":false,\"result\":{}}", "[]")) rejected { LedgerReportContract.page(raw, query) }
        val valid = envelope(baseline)
        for (raw in listOf(valid.replaceFirst("\"success\":true", "\"success\":false,\"success\":true"), valid.replaceFirst("\"success\":true", "\"Success\":false,\"success\":true"), valid.replaceFirst("\"success\":true", "\"suc\\u0063ess\":false,\"success\":true"), valid + "{}")) rejected { LedgerReportContract.page(raw, query) }
        assertEquals("-92233720368547758.08", LedgerReportContract.formatAmount(Long.MIN_VALUE))
        assertEquals("92233720368547758.07", LedgerReportContract.formatAmount(Long.MAX_VALUE))
    }
    @Test fun stringMoneyAboveDoublePrecisionAndIntermediateOverflowRemainExact() {
        for (amount in listOf(9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val row = logicalDetail("1")
            val changed = patch(row, "source" to patch(row.getValue("source").jsonObject, "amount" to JsonPrimitive(amount.toString())))
            val report = page(pageJson(overrides = mapOf("1" to changed)))
            assertEquals(amount, report.totals.single { it.currency == "CNY" }.income)
        }
        // Fixture identity/metadata remain the common source; these are numeric boundary mutations.
        val income = logicalDetail("1")
        val replacements = mapOf("1" to Long.MIN_VALUE, "2" to Long.MAX_VALUE, "3" to Long.MAX_VALUE).mapValues { (identifier, amount) ->
            val row = logicalDetail(identifier)
            patch(row, "type" to JsonPrimitive(2), "categoryId" to income.getValue("categoryId"), "categoryName" to income.getValue("categoryName"),
                "tags" to JsonArray(emptyList()), "source" to patch(row.getValue("source").jsonObject, "amount" to JsonPrimitive(amount.toString())))
        }
        val report = page(pageJson(overrides = replacements))
        val exact = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO + BigInteger.valueOf(Long.MIN_VALUE)
        assertEquals(exact.longValueExact(), report.totals.single { it.currency == "CNY" }.income)
        val tooLarge = patch(income, "source" to patch(income.getValue("source").jsonObject, "amount" to JsonPrimitive("9223372036854775808")))
        rejected { page(pageJson(overrides = mapOf("1" to tooLarge))) }
    }
    @Test fun historicalAdjustmentDeltaIsIndependentFromItsBalanceTarget() {
        val historical = fixture.getValue("variants").jsonObject.getValue("historicalAdjustment").jsonObject
        val base = pageJson(); val rows = base.rows("details").map { if (it.s("id") == historical.s("id")) patch(it, "adjustment" to historical.getValue("delta")) else it }
        // Rebuild the transport group values for this explicitly named historical variant.
        fun remoney(groups: List<JsonObject>, kind: String) = JsonArray(groups.map { group ->
            val selected = rows.filter { row ->
                when (kind) {
                    "trend" -> row.s("date") == group.s("key")
                    "category" -> row.s("categoryId") == group.s("key")
                    else -> if (group.s("key") == "none") row.rows("tags").isEmpty() else row.rows("tags").any { it.s("id") == group.s("key") }
                }
            }
            patch(group, "money" to aggregate(selected))
        })
        val report = page(patch(base, "details" to JsonArray(rows), "totals" to historical.getValue("totals"), "trend" to remoney(base.rows("trend"), "trend"), "categories" to remoney(base.rows("categories"), "category"), "tagGroups" to remoney(base.rows("tagGroups"), "tag")))
        val adjusted = report.details.single { it.id.toString() == historical.s("id") }
        assertEquals(historical.s("delta").toLong(), adjusted.adjustment)
        assertEquals(historical.s("balanceTarget").toLong(), adjusted.balanceTarget)
    }
    @Test fun previewsBindObservedSnapshotAndExplicitHiddenConfirmationBeforeDownload() {
        val page = page(); val json = previewJson(); val preview = LedgerReportContract.preview(envelope(json), page, now)
        rejected { LedgerReportContract.exportRequest(page, preview, false, nowSeconds = now) }
        rejected { LedgerReportContract.exportRequest(page, preview, true, nowSeconds = now + 600) }
        for (p in listOf(patch(json, "snapshotHash" to JsonPrimitive("cd".repeat(32))), patch(json, "matchedCount" to JsonPrimitive(0)), patch(json, "expiresAt" to JsonPrimitive(now + 600)))) rejected { LedgerReportContract.preview(envelope(p), page, now) }
        val hiddenQuery = LedgerReportContract.query(patch(query.json, "hidden" to JsonPrimitive("include")))
        val hiddenCase = fixture.rows("cases").single { it.s("name") == "hidden-explicit" }
        val hiddenJson = pageJson(hiddenQuery, hiddenCase.getValue("detailIds").jsonArray.map { it.jsonPrimitive.content })
        val hiddenPage = page(hiddenJson, hiddenQuery); val hiddenPreview = LedgerReportContract.preview(envelope(previewJson(hiddenJson)), hiddenPage, now)
        rejected { LedgerReportContract.exportRequest(hiddenPage, hiddenPreview, true, nowSeconds = now) }
        assertTrue(Json.parseToJsonElement(LedgerReportContract.exportRequest(hiddenPage, hiddenPreview, true, true, now)).jsonObject.getValue("includeHiddenAmounts").jsonPrimitive.boolean)
        rejected { LedgerReportContract.exportRequest(page, hiddenPreview, true, true, now) }
    }
    @Test fun validServerExpiryAllowsDeviceClockLagWithoutRelaxingPreviewBindingOrExpiry() {
        val page = page(); val json = previewJson(); val expires = now + 600
        for (lag in listOf(1L, 30L)) {
            val deviceNow = now - lag
            val preview = LedgerReportContract.preview(envelope(json), page, deviceNow)
            assertEquals(600 + lag, preview.expiresAt - deviceNow)
            assertEquals(expires, preview.expiresAt)
            val request = Json.parseToJsonElement(LedgerReportContract.exportRequest(page, preview, true, nowSeconds = deviceNow)).jsonObject
            assertEquals(hash, request.s("expectedSnapshotHash"))
            assertEquals("synthetic.proof", request.s("previewToken"))
            rejected { LedgerReportContract.exportRequest(page, preview, true, nowSeconds = expires) }
            val wrongQuery = patch(json.getValue("query").jsonObject, "ledgerId" to JsonPrimitive(if (query.ledgerId == 0L) "1" else "0"))
            for (invalid in listOf(
                patch(json, "expiresAt" to JsonPrimitive(deviceNow.toString())),
                patch(json, "expiresAt" to JsonPrimitive("0")),
                patch(json, "expiresAt" to JsonPrimitive(expires)),
                patch(json, "expiresAt" to JsonPrimitive("9223372036854775808")),
                patch(json, "query" to wrongQuery),
                patch(json, "snapshotHash" to JsonPrimitive("cd".repeat(32)))
            )) rejected { LedgerReportContract.preview(envelope(invalid), page, deviceNow) }
        }
    }
    private val header = "report_version,snapshot_hash,ledger_id,timezone,start_date,end_date,amount_unit,hidden_policy,id,physical_ids_json,type,unix_seconds,sequence,local_date,category_id,category_name,source_account_id,source_account_name,source_currency,source_amount_minor,destination_account_id,destination_account_name,destination_currency,destination_amount_minor,adjustment_minor,balance_target_minor,tag_ids_json,tag_names_json,hidden,comment,recorder_uid,payer_uid,major_unit_scale,source_amount_major,destination_amount_major,adjustment_major,balance_target_major"
    private fun headers(page: LedgerReportPage = page()) = mapOf("Content-Type" to "text/csv; charset=utf-8", "Content-Disposition" to "attachment; filename=finexy-report-${page.query.ledgerId}-${page.query.startDate}-${page.query.endDate}.csv", "X-Finexy-Report-Snapshot" to page.snapshotHash, "X-Finexy-Report-Count" to page.matchedCount.toString(), "X-Finexy-Report-Amount-Unit" to "minor")
    private fun csvBytes(page: JsonObject = pageJson()): ByteArray {
        fun text(value: String): String = if (value.startsWith("'") || value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        fun cell(value: String): String = if (value.any { it in listOf(',', '"', '\n', '\r') }) "\"${value.replace("\"", "\"\"")}\"" else value
        val rows = page.rows("details").map { row ->
            val source = row["source"] as? JsonObject; val destination = row["destination"] as? JsonObject
            fun leg(row: JsonObject?) = if (row == null) List(4) { "" } else listOf(row.s("accountId"), text(row.s("accountName")), row.s("currency"), row.s("amount"))
            val tags = row.rows("tags")
            val values = listOf("1", hash, query.ledgerId.toString(), query.timezone, query.startDate, query.endDate, "minor", "exclude", row.s("id"), row.getValue("physicalIds").toString(), row.s("type"), row.s("time"), row.s("sequence"), row.s("date"), row.s("categoryId"), text(row.s("categoryName"))) +
                leg(source) + leg(destination) + listOf(row.s("adjustment"), row["balanceTarget"]?.jsonPrimitive?.content.orEmpty(), JsonArray(tags.map { it.getValue("id") }).toString(), JsonArray(tags.map { it.getValue("name") }).toString(), row.s("hidden"), text(row.s("comment")), row.s("recorderUid"), row.s("payerUid"), "2", source?.s("amount")?.toLong()?.let(LedgerReportContract::formatAmount).orEmpty(), destination?.s("amount")?.toLong()?.let(LedgerReportContract::formatAmount).orEmpty(), LedgerReportContract.formatAmount(row.s("adjustment").toLong()), row["balanceTarget"]?.jsonPrimitive?.content?.toLong()?.let(LedgerReportContract::formatAmount).orEmpty())
            assertEquals(37, values.size)
            values.joinToString(",", transform = ::cell)
        }
        return (header + "\n" + rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n")).toByteArray(Charsets.UTF_8)
    }
    @Test fun onlyRealValidatedCsvBytesAreReturnedWithoutClaimingSystemSave() {
        val page = page(); val bytes = csvBytes(); val download = LedgerReportContract.download(bytes, headers(), page)
        assertArrayEquals(bytes, download.bytes()); assertEquals(expected.getValue("detailIds").jsonArray.size, download.rowCount)
        val changed = download.bytes(); changed[0] = 0
        assertArrayEquals(bytes, download.bytes())
        for (h in listOf(headers() + ("Content-Type" to "application/json"), headers() + ("content-type" to "text/csv"), headers() + ("X-Finexy-Report-Count" to "0"), headers() + ("Content-Disposition" to "attachment; filename=other.csv"), headers() + ("Content-Length" to (bytes.size + 1).toString()))) rejected { LedgerReportContract.download(bytes, h, page) }
        for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), "{\"success\":false}".toByteArray(), bytes.copyOf(bytes.size - 2), (bytes.toString(Charsets.UTF_8) + "\"unterminated").toByteArray(), bytes.toString(Charsets.UTF_8).replace(",10.00", ",10.01").toByteArray())) rejected { LedgerReportContract.download(bad, headers(), page) }
        val firstJson = patch(pageJson(), "details" to JsonArray(pageJson().getValue("details").jsonArray.take(2)), "returnedCount" to JsonPrimitive(2), "nextOffset" to JsonPrimitive(2), "detailsComplete" to JsonPrimitive(false))
        val first = LedgerReportContract.page(envelope(firstJson), query, 0, 2)
        assertArrayEquals(bytes, LedgerReportContract.download(bytes, headers(first), first).bytes())
    }
    @Test fun csvFormulaProtectionHandlesAstralUnicodeFormatPrefixes() {
        val prefix = String(Character.toChars(0xE0001))
        val row = logicalDetail("2")
        val json = pageJson(overrides = mapOf("2" to patch(row, "comment" to JsonPrimitive(prefix + row.s("comment")))))
        val report = page(json)
        val bytes = csvBytes().toString(Charsets.UTF_8).replace("'=SUM", "'$prefix=SUM").toByteArray(Charsets.UTF_8)
        assertEquals(report.matchedCount, LedgerReportContract.download(bytes, headers(report), report).rowCount)
        rejected { LedgerReportContract.download(bytes.toString(Charsets.UTF_8).replace("'$prefix=SUM", "$prefix=SUM").toByteArray(Charsets.UTF_8), headers(report), report) }
    }
}
