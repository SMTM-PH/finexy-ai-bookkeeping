package com.finexy.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Suspendable typed transport doubles test lifecycle races, not Android HTTP or file saving. */
class LedgerReportControllerTest {
    private val hash = "ab".repeat(32)
    private val startTime = Instant.parse("2026-10-20T12:00:00Z").epochSecond
    private fun query(ledgerId: Long = 41, hidden: Boolean = false): LedgerReportQuery {
        val base = LedgerReportContract.query(ledgerId, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), ZoneId.of("UTC"))
        return if (hidden) LedgerReportContract.query(JsonObject(base.json + ("hidden" to JsonPrimitive("include")))) else base
    }
    private fun envelope(value: JsonObject) = buildJsonObject { put("success", true); put("result", value) }.toString()
    private fun money() = buildJsonArray { add(buildJsonObject {
        put("currency", "CNY"); put("income", "225"); put("expense", "0"); put("net", "225")
        put("adjustment", "0"); put("transferIn", "0"); put("transferOut", "0")
    }) }
    private fun pageJson(query: LedgerReportQuery, offset: Int = 0, count: Int = 50, snapshot: String = hash): JsonObject {
        val all = listOf(303L to 50L, 302L to 75L, 301L to 100L).map { (id, amount) -> buildJsonObject {
            put("id", id.toString()); put("physicalIds", buildJsonArray { add(id.toString()) }); put("type", 2)
            put("time", startTime.toString()); put("sequence", (startTime * 1000).toString()); put("date", "2026-10-20")
            put("categoryId", "21"); put("categoryName", "Synthetic income")
            put("source", buildJsonObject { put("accountId", "11"); put("accountName", "Synthetic account"); put("currency", "CNY"); put("amount", amount.toString()) })
            put("destination", JsonNull); put("adjustment", "0"); put("tags", buildJsonArray {})
            put("hidden", false); put("comment", "Synthetic controller race fixture"); put("recorderUid", "1"); put("payerUid", "1")
        } }
        fun groups(key: String, name: String? = null) = buildJsonArray { add(buildJsonObject {
            put("key", key); name?.let { put("name", it) }; put("count", 3); put("money", money())
        }) }
        val rows = all.drop(offset).take(count)
        val next = (offset + rows.size).takeIf { it < 3 }
        return buildJsonObject {
            put("query", JsonObject(query.json + mapOf("version" to JsonPrimitive(1), "utcRanges" to buildJsonArray {
                add(buildJsonObject { put("start", "1790812800"); put("endExclusive", "1793491200") })
            })))
            put("snapshotHash", snapshot); put("memberRole", 1); put("dataComplete", true); put("matchedCount", 3)
            put("details", JsonArray(rows)); put("totals", money()); put("trend", groups("2026-10-20"))
            put("categories", groups("21", "Synthetic income")); put("tagGroups", groups("none")); put("tagGroupsAdditive", false)
            put("accounts", buildJsonArray { add(buildJsonObject {
                put("id", "11"); put("name", "Synthetic account"); put("hidden", false); put("parentId", "0"); put("type", 1); put("currency", "CNY")
            }) })
            put("categoryMetadata", buildJsonArray {
                add(buildJsonObject { put("id", "20"); put("name", "Synthetic parent"); put("hidden", false); put("parentId", "0"); put("type", 1) })
                add(buildJsonObject { put("id", "21"); put("name", "Synthetic income"); put("hidden", false); put("parentId", "20"); put("type", 1) })
            })
            put("readableTags", buildJsonArray {}); put("offset", offset); put("returnedCount", rows.size)
            put("nextOffset", next?.let(::JsonPrimitive) ?: JsonNull); put("detailsComplete", offset == 0 && rows.size == 3); put("exportAllowed", true)
        }
    }
    private fun page(query: LedgerReportQuery, offset: Int = 0, count: Int = 50, snapshot: String = hash) =
        LedgerReportContract.page(envelope(pageJson(query, offset, count, snapshot)), query, offset, count, if (offset > 0) snapshot else null)
    private fun preview(page: LedgerReportPage, now: Long) = LedgerReportContract.preview(envelope(buildJsonObject {
        put("query", page.json.getValue("query")); put("snapshotHash", page.snapshotHash); put("matchedCount", page.matchedCount)
        put("totals", page.json.getValue("totals")); put("amountUnit", "minor"); put("includesHiddenAmounts", page.query.includesHiddenAmounts)
        put("expiresAt", (now + 600).toString()); put("previewToken", "synthetic.proof")
    }), page, now)

    private data class QueryCall(val context: LedgerReportContext, val query: LedgerReportQuery, val offset: Int,
        val count: Int, val expectedHash: String?, val result: CompletableDeferred<LedgerReportPage> = CompletableDeferred())
    private data class PreviewCall(val context: LedgerReportContext, val page: LedgerReportPage,
        val result: CompletableDeferred<LedgerReportPreview> = CompletableDeferred())
    private data class DownloadCall(val context: LedgerReportContext, val knownPage: LedgerReportPage, val proof: LedgerReportPreview,
        val confirmed: Boolean, val includeHiddenAmounts: Boolean, val result: CompletableDeferred<LedgerReportDownload> = CompletableDeferred())
    private class Transport : LedgerReportTransport {
        val queries = Channel<QueryCall>(Channel.UNLIMITED)
        val previews = Channel<PreviewCall>(Channel.UNLIMITED)
        val downloads = Channel<DownloadCall>(Channel.UNLIMITED)
        override suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int, expectedHash: String?): LedgerReportPage {
            val call = QueryCall(context, query, offset, count, expectedHash); queries.send(call); return call.result.await()
        }
        override suspend fun preview(context: LedgerReportContext, page: LedgerReportPage): LedgerReportPreview {
            val call = PreviewCall(context, page); previews.send(call); return call.result.await()
        }
        override suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
            confirmed: Boolean, includeHiddenAmounts: Boolean): LedgerReportDownload {
            val call = DownloadCall(context, knownPage, preview, confirmed, includeHiddenAmounts); downloads.send(call); return call.result.await()
        }
    }
    private inner class Harness(ledgerId: Long = 41) {
        var current: LedgerReportContext? = LedgerReportContext("https://synthetic.invalid", "synthetic-user", ledgerId)
        var now = 1_792_497_600L
        val transport = Transport()
        val controller = LedgerReportController(transport, { current }, { now })
        val state get() = controller.state.value
        init { controller.bind(current) }
        suspend fun load(query: LedgerReportQuery = query(requireNotNull(current).ledgerId), count: Int = 50) = coroutineScope {
            val request = async(start = CoroutineStart.UNDISPATCHED) { controller.load(query, count) }
            val call = transport.queries.receive()
            assertEquals(0, call.offset); assertNull(call.expectedHash)
            call.result.complete(page(call.query, call.offset, call.count))
            assertTrue(request.await())
        }
        suspend fun prepare(): LedgerReportConfirmation = coroutineScope {
            val request = async(start = CoroutineStart.UNDISPATCHED) { controller.prepareExport() }
            val call = transport.previews.receive(); call.result.complete(preview(call.page, now))
            requireNotNull(request.await())
        }
        fun download(call: DownloadCall, rows: Int = 3, filename: String? = null) = LedgerReportDownload(
            filename ?: "finexy-report-${call.context.ledgerId}-2026-10-01-2026-10-31.csv", "already-validated-transport-double".toByteArray(), rows)
    }
    private fun noMoney(state: LedgerReportState) {
        assertNull(state.report); assertTrue(state.details.isEmpty()); assertNull(state.confirmation)
        assertFalse(state.completeOnlineSource); assertFalse(state.detailsComplete)
    }

    @Test fun onlineAggregateAndPartialDetailsRemainDifferentUntilPaginationFinishes() = runBlocking {
        val h = Harness(); noMoney(h.state); h.load(count = 2)
        assertTrue(h.state.completeOnlineSource); assertFalse(h.state.detailsComplete)
        assertEquals(225L, h.state.report!!.totals.single().net); assertEquals(2, h.state.details.size)
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.loadNext() }
        noMoney(h.state)
        val next = h.transport.queries.receive(); assertEquals(2, next.offset); assertEquals(hash, next.expectedHash)
        next.result.complete(page(next.query, next.offset, next.count))
        assertTrue(request.await()); assertTrue(h.state.detailsComplete)
        assertEquals(listOf(303L, 302L, 301L), h.state.details.map { it.id })
    }

    @Test fun defaultLedgerZeroIsAnExplicitContextAndDoesNotFallBackToAnotherLedger() = runBlocking {
        val h = Harness(0); h.load(); assertEquals(0L, h.state.ledgerId); assertEquals(0L, h.state.query!!.ledgerId)
        assertFalse(h.controller.load(query(41))); noMoney(h.state)
        assertEquals(LedgerReportFailureKind.CONTRACT, h.state.failure!!.kind)
        assertTrue(h.transport.queries.tryReceive().isFailure)
    }

    @Test fun localOfflineAndSynchronizingModesEraseOnlineDataAndNeverIssueRequests() = runBlocking {
        for ((mode, source) in listOf(LedgerReportAvailability.LOCAL_ONLY to LedgerReportSource.LOCAL_ONLY,
            LedgerReportAvailability.OFFLINE to LedgerReportSource.OFFLINE, LedgerReportAvailability.SYNCHRONIZING to LedgerReportSource.SYNCHRONIZING)) {
            val h = Harness(); h.load(); h.prepare(); h.controller.bind(h.current, mode)
            noMoney(h.state); assertEquals(source, h.state.source)
            assertFalse(h.controller.load(query())); assertFalse(h.controller.loadNext()); assertNull(h.controller.prepareExport())
            assertTrue(h.transport.queries.tryReceive().isFailure); assertTrue(h.transport.previews.tryReceive().isFailure)
        }
    }

    @Test fun equalContextRebindInvalidatesProofAndInFlightQueryInsteadOfReusingThem() = runBlocking {
        val h = Harness(); h.load(); val proof = h.prepare(); h.controller.bind(h.current)
        assertNull(h.controller.download(proof, true)); noMoney(h.state)
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query()) }
        val call = h.transport.queries.receive(); h.controller.bind(h.current)
        call.result.complete(page(call.query)); assertFalse(request.await()); noMoney(h.state)
    }

    @Test fun accountServerLedgerAndLogoutChangesRejectLateResponsesWithoutExplicitBind() = runBlocking {
        for (change in listOf<(LedgerReportContext) -> LedgerReportContext?>(
            { it.copy(identity = "different-user") }, { it.copy(serverUrl = "https://another.invalid") }, { it.copy(ledgerId = 42) }, { null })) {
            val h = Harness(); val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query()) }
            val call = h.transport.queries.receive(); h.current = change(requireNotNull(h.current))
            call.result.complete(page(call.query)); assertFalse(request.await()); noMoney(h.state)
            assertEquals(LedgerReportSource.NO_SESSION, h.state.source)
        }
    }

    @Test fun lateQuerySuccessAndFailureCannotReplaceTheNewerReport() = runBlocking {
        for (oldFails in listOf(false, true)) {
            val h = Harness(); val first = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query(), 2) }
            val old = h.transport.queries.receive()
            val newer = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query(), 3) }
            val current = h.transport.queries.receive(); current.result.complete(page(current.query, count = 3))
            assertTrue(newer.await())
            if (oldFails) old.result.completeExceptionally(ApiException(403, "private response must not be shown"))
            else old.result.complete(page(old.query, count = 2))
            assertFalse(first.await()); assertTrue(h.state.detailsComplete); assertEquals(3, h.state.details.size); assertNull(h.state.failure)
        }
    }

    @Test fun refreshImmediatelyHidesOldAmountsAndRejectsOldPreview() = runBlocking {
        val h = Harness(); h.load(); val proof = h.prepare()
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query()) }
        noMoney(h.state); assertEquals(LedgerReportOperation.QUERY, h.state.operation)
        assertNull(h.controller.download(proof, true)); assertTrue(h.transport.downloads.tryReceive().isFailure)
        val call = h.transport.queries.receive(); call.result.complete(page(call.query)); assertTrue(request.await())
    }

    @Test fun paginationInvalidatesTheShownConfirmationAndCannotExportWhileLoading() = runBlocking {
        val h = Harness(); h.load(count = 2); val proof = h.prepare()
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.loadNext() }
        noMoney(h.state); assertNull(h.controller.download(proof, true)); assertNull(h.controller.prepareExport())
        val call = h.transport.queries.receive(); call.result.complete(page(call.query, call.offset, call.count)); assertTrue(request.await())
        assertNull(h.controller.download(proof, true)); assertTrue(h.transport.downloads.tryReceive().isFailure)
    }

    @Test fun mismatchingSnapshotOrMetadataOnContinuationClearsAllOldMoney() = runBlocking {
        for (differentHash in listOf(false, true)) {
            val h = Harness(); h.load(count = 2)
            val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.loadNext() }
            val call = h.transport.queries.receive()
            var json = pageJson(call.query, call.offset, call.count, if (differentHash) "cd".repeat(32) else hash)
            if (!differentHash) json = JsonObject(json + ("memberRole" to JsonPrimitive(4)))
            call.result.complete(LedgerReportContract.page(envelope(json), call.query, call.offset, call.count,
                json.getValue("snapshotHash").jsonPrimitive.content))
            assertFalse(request.await()); noMoney(h.state); assertEquals(LedgerReportFailureKind.CONTRACT, h.state.failure!!.kind)
        }
    }

    @Test fun crossPageDuplicateLogicalAndPhysicalIdsAreRejected() = runBlocking {
        val h = Harness(); h.load(count = 2)
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.loadNext() }
        val call = h.transport.queries.receive(); var json = pageJson(call.query, call.offset, call.count)
        val row = json.getValue("details").jsonArray.single().jsonObject
        json = JsonObject(json + ("details" to JsonArray(listOf(JsonObject(row + mapOf("id" to JsonPrimitive("302"), "physicalIds" to buildJsonArray { add("302") }))))))
        call.result.complete(LedgerReportContract.page(envelope(json), call.query, call.offset, call.count, hash))
        assertFalse(request.await()); noMoney(h.state)
    }

    @Test fun remoteFailureAndRevocationClearDataAndExposeOnlySanitizedStatus() = runBlocking {
        for ((status, kind) in listOf(401 to LedgerReportFailureKind.AUTHENTICATION, 403 to LedgerReportFailureKind.FORBIDDEN,
            404 to LedgerReportFailureKind.UNAVAILABLE, 405 to LedgerReportFailureKind.UNAVAILABLE, 409 to LedgerReportFailureKind.SNAPSHOT_CHANGED)) {
            val h = Harness(); h.load()
            val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.prepareExport() }
            h.transport.previews.receive().result.completeExceptionally(ApiException(status, "secret financial response"))
            assertNull(request.await()); noMoney(h.state)
            assertEquals(status, h.state.failure!!.httpStatus); assertEquals(kind, h.state.failure!!.kind)
            assertFalse(h.state.failure!!.message.contains("secret"))
        }
    }

    @Test fun networkFailureIsOfflineRatherThanAnEmptyOrCompleteLocalReport() = runBlocking {
        val h = Harness(); val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query()) }
        h.transport.queries.receive().result.completeExceptionally(IOException("private network diagnostics"))
        assertFalse(request.await()); noMoney(h.state); assertEquals(LedgerReportSource.OFFLINE, h.state.source)
        assertEquals(LedgerReportFailureKind.NETWORK, h.state.failure!!.kind)
    }

    @Test fun explicitConfirmationAndHiddenAcknowledgementAreBothRequired() = runBlocking {
        val h = Harness(); h.load(query(hidden = true)); val proof = h.prepare()
        assertTrue(proof.includesHiddenAmounts)
        assertNull(h.controller.download(proof, false, true)); assertSame(proof, h.state.confirmation)
        assertNull(h.controller.download(proof, true, false)); assertSame(proof, h.state.confirmation)
        assertTrue(h.transport.downloads.tryReceive().isFailure)
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(proof, true, true) }
        val call = h.transport.downloads.receive(); assertTrue(call.confirmed); assertTrue(call.includeHiddenAmounts)
        call.result.complete(h.download(call)); val delivery = requireNotNull(request.await())
        assertNotNull(delivery.takeIfCurrent()); assertNull(delivery.takeIfCurrent())
        assertFalse(h.state.notice.orEmpty().contains("保存成功"))
    }

    @Test fun equalProofFieldsFromOldDialogCannotConfirmAReplacementPreview() = runBlocking {
        val h = Harness(); h.load(); val first = h.prepare(); val second = h.prepare()
        assertEquals(first.proof, second.proof); assertNotSame(first, second)
        assertNull(h.controller.download(first, true)); assertSame(second, h.state.confirmation)
        assertTrue(h.transport.downloads.tryReceive().isFailure)
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(second, true) }
        val call = h.transport.downloads.receive(); call.result.complete(h.download(call))
        assertNotNull(request.await())
    }

    @Test fun downloadedCsvIsCheckedAgainstEveryLoadedDetailInsteadOfOnlyPageOne() = runBlocking {
        val h = Harness(); h.load(count = 2)
        val more = async(start = CoroutineStart.UNDISPATCHED) { h.controller.loadNext() }
        val next = h.transport.queries.receive(); next.result.complete(page(next.query, next.offset, next.count)); assertTrue(more.await())
        val proof = h.prepare(); val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(proof, true) }
        val call = h.transport.downloads.receive()
        assertEquals(listOf(303L, 302L, 301L), call.knownPage.details.map { it.id })
        assertEquals(0, call.knownPage.offset); call.result.complete(h.download(call)); assertNotNull(request.await())
    }

    @Test fun cancellingPreviewIgnoresLateProofAndCloseIgnoresLateQuery() = runBlocking {
        val h = Harness(); h.load()
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.prepareExport() }
        val call = h.transport.previews.receive(); h.controller.cancelExport()
        call.result.complete(preview(call.page, h.now)); assertNull(request.await()); assertNull(h.state.confirmation)
        val query = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query()) }
        val late = h.transport.queries.receive(); h.controller.close(); late.result.complete(page(late.query))
        assertFalse(query.await()); noMoney(h.state); assertEquals(LedgerReportSource.CLOSED, h.state.source)
    }

    @Test fun closeAndChangedSessionPreventLateCsvDelivery() = runBlocking {
        for (close in listOf(true, false)) {
            val h = Harness(); h.load(); val proof = h.prepare()
            val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(proof, true) }
            val call = h.transport.downloads.receive()
            if (close) h.controller.close() else h.current = requireNotNull(h.current).copy(identity = "another-user")
            call.result.complete(h.download(call)); assertNull(request.await()); noMoney(h.state)
        }
    }

    @Test fun delayedFilePickerCannotObtainBytesAfterRebindOrExpiry() = runBlocking {
        for (expires in listOf(true, false)) {
            val h = Harness(); h.load(); val proof = h.prepare()
            val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(proof, true) }
            val call = h.transport.downloads.receive(); call.result.complete(h.download(call))
            val delivery = requireNotNull(request.await())
            if (expires) h.now = proof.expiresAt else h.controller.bind(h.current)
            assertNull(delivery.takeIfCurrent())
        }
    }

    @Test fun expiredProofAndSlowDownloadCannotDeliverBytes() = runBlocking {
        val h = Harness(); h.load(); val expired = h.prepare(); h.now = expired.expiresAt
        assertNull(h.controller.download(expired, true)); assertTrue(h.transport.downloads.tryReceive().isFailure); assertNull(h.state.confirmation)
        val current = h.prepare(); val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(current, true) }
        val call = h.transport.downloads.receive(); h.now = current.expiresAt; call.result.complete(h.download(call))
        assertNull(request.await()); noMoney(h.state); assertEquals(LedgerReportFailureKind.CONTRACT, h.state.failure!!.kind)
    }

    @Test fun cancellationClearsBusyMoneyAndDoesNotSwallowStructuredCancellation() = runBlocking {
        val h = Harness(); h.load()
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.load(query()) }
        h.transport.queries.receive(); request.cancelAndJoin()
        assertTrue(request.isCancelled); noMoney(h.state); assertFalse(h.state.busy)
    }

    @Test fun invalidDownloadMetadataClearsStateAndRecreationNeverRestoresProofOrMoney() = runBlocking {
        for (wrongName in listOf(true, false)) {
            val h = Harness(); h.load(); val proof = h.prepare()
            val request = async(start = CoroutineStart.UNDISPATCHED) { h.controller.download(proof, true) }
            val call = h.transport.downloads.receive()
            call.result.complete(if (wrongName) h.download(call, filename = "wrong.csv") else h.download(call, rows = 2))
            assertNull(request.await()); noMoney(h.state); assertEquals(LedgerReportFailureKind.CONTRACT, h.state.failure!!.kind)
            val replacement = LedgerReportController(h.transport, { h.current }, { h.now }); replacement.bind(h.current)
            noMoney(replacement.state.value); assertNull(replacement.prepareExport()); assertNull(replacement.download(proof, true))
        }
    }
}
