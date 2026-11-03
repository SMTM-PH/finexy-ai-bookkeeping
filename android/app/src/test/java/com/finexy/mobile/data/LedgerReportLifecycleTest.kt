package com.finexy.mobile.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Typed doubles cover the production lifecycle core, not Android HTTP, connectivity or SAF. */
class LedgerReportLifecycleTest {
    private val hash = "ab".repeat(32)
    private val now = 1_792_497_600L
    private fun query(ledgerId: Long = 41) = LedgerReportContract.query(ledgerId,
        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), ZoneId.of("UTC"))
    private fun envelope(value: JsonObject) = buildJsonObject { put("success", true); put("result", value) }.toString()
    private fun page(query: LedgerReportQuery): LedgerReportPage {
        val zone = ZoneId.of(query.timezone)
        val json = buildJsonObject {
            put("query", JsonObject(query.json + mapOf("version" to JsonPrimitive(1), "utcRanges" to buildJsonArray {
                add(buildJsonObject {
                    put("start", LocalDate.parse(query.startDate).atStartOfDay(zone).toEpochSecond().toString())
                    put("endExclusive", LocalDate.parse(query.endDate).plusDays(1).atStartOfDay(zone).toEpochSecond().toString())
                })
            })))
            put("snapshotHash", hash); put("memberRole", 1); put("dataComplete", true); put("matchedCount", 0)
            for (key in listOf("details", "totals", "trend", "categories", "tagGroups", "accounts", "categoryMetadata", "readableTags")) put(key, buildJsonArray {})
            put("tagGroupsAdditive", false); put("offset", 0); put("returnedCount", 0); put("nextOffset", JsonNull)
            put("detailsComplete", true); put("exportAllowed", true)
        }
        return LedgerReportContract.page(envelope(json), query)
    }
    private fun preview(page: LedgerReportPage) = LedgerReportContract.preview(envelope(buildJsonObject {
        put("query", page.json.getValue("query")); put("snapshotHash", hash); put("matchedCount", 0)
        put("totals", buildJsonArray {}); put("amountUnit", "minor"); put("includesHiddenAmounts", false)
        put("expiresAt", (now + 600).toString()); put("previewToken", "synthetic.proof")
    }), page, now)
    private inner class Transport : LedgerReportTransport {
        var queries = 0; var previews = 0; var downloads = 0
        var held: CompletableDeferred<LedgerReportPage>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int, expectedHash: String?): LedgerReportPage {
            queries++; started.complete(Unit)
            return held?.await() ?: page(query)
        }
        override suspend fun preview(context: LedgerReportContext, page: LedgerReportPage): LedgerReportPreview {
            previews++; return preview(page)
        }
        override suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
            confirmed: Boolean, includeHiddenAmounts: Boolean): LedgerReportDownload {
            downloads++; return LedgerReportDownload("finexy-report-${context.ledgerId}-${knownPage.query.startDate}-${knownPage.query.endDate}.csv", byteArrayOf(1), 0)
        }
    }
    private inner class Harness {
        var environment = LedgerReportEnvironmentState("synthetic", LedgerReportContext("https://synthetic.invalid", "synthetic-user", 41),
            LedgerReportAvailability.ONLINE, 0, 0, 1)
        var factories = 0
        val transport = Transport()
        val lifecycle = LedgerReportLifecycle({ environment }, { factories++; transport }, { now })
        val state get() = lifecycle.state.value
        init { lifecycle.enter() }
        suspend fun load() { assertTrue(lifecycle.load(query(requireNotNull(environment.context).ledgerId))) }
        fun change(update: (LedgerReportEnvironmentState) -> LedgerReportEnvironmentState) {
            environment = update(environment); lifecycle.refreshEnvironment()
        }
    }
    private fun assertCleared(h: Harness) {
        assertNull(h.state.report); assertTrue(h.state.details.isEmpty()); assertNull(h.state.confirmation)
        assertFalse(h.state.completeOnlineSource)
    }

    @Test fun rotationReattachesWithoutDiscardingReadyReportOrShownConfirmation() = runBlocking {
        val h = Harness(); h.load(); val shown = h.lifecycle.prepareExport()
        val before = h.lifecycle.captureTicket(); val epoch = h.lifecycle.contextEpoch.value
        h.lifecycle.enter()
        assertSame(shown, h.state.confirmation); assertTrue(h.state.completeOnlineSource)
        assertEquals(before, h.lifecycle.captureTicket()); assertEquals(epoch, h.lifecycle.contextEpoch.value)
        assertEquals(1, h.factories); assertEquals(1, h.transport.queries)
    }
    @Test fun explicitLeaveRejectsFileTicketAndReentryRequiresNewReadEvenInSameLedger() = runBlocking {
        val h = Harness(); h.load(); val ticket = requireNotNull(h.lifecycle.captureTicket())
        h.lifecycle.leave(); assertCleared(h); assertFalse(h.lifecycle.isCurrent(ticket)); assertNull(h.lifecycle.captureTicket())
        h.lifecycle.enter(); assertCleared(h); assertEquals(1, h.transport.queries)
        h.load(); assertTrue(h.state.completeOnlineSource); assertEquals(2, h.factories)
    }
    @Test fun sessionRevisionInvalidatesSameIdentityIncludingChangeBackAndCreatesFreshTransport() = runBlocking {
        val h = Harness(); h.load(); h.lifecycle.prepareExport(); val ticket = requireNotNull(h.lifecycle.captureTicket())
        val epoch = h.lifecycle.contextEpoch.value
        h.change { it.copy(sessionRevision = 2) }
        assertCleared(h); assertFalse(h.lifecycle.isCurrent(ticket)); assertEquals(epoch + 1, h.lifecycle.contextEpoch.value)
        assertEquals(2, h.factories); assertEquals(1, h.transport.queries)
    }
    @Test fun directLiveCheckRejectsMissedCallbackBeforeFileWrite() = runBlocking {
        val h = Harness(); h.load(); val ticket = requireNotNull(h.lifecycle.captureTicket())
        h.environment = h.environment.copy(sessionRevision = 1)
        assertFalse(h.lifecycle.isCurrent(ticket)); assertCleared(h)
    }
    @Test fun accountServerLedgerNamespaceAndNetworkChangesClearTheWholeView() = runBlocking {
        val updates = listOf<(LedgerReportEnvironmentState) -> LedgerReportEnvironmentState>(
            { it.copy(context = it.context!!.copy(identity = "another-user")) },
            { it.copy(context = it.context!!.copy(serverUrl = "https://another.invalid")) },
            { it.copy(context = it.context!!.copy(ledgerId = 0)) },
            { it.copy(namespace = "another-synthetic-scope") }, { it.copy(networkId = 2) })
        for (update in updates) {
            val h = Harness(); h.load(); h.lifecycle.prepareExport(); val ticket = requireNotNull(h.lifecycle.captureTicket())
            h.change(update); assertCleared(h); assertFalse(h.lifecycle.isCurrent(ticket)); assertEquals(1, h.transport.queries)
        }
    }
    @Test fun offlineLocalOnlyAndActualSyncNeverShowCompleteOnlineOrAutoLoadWhenAvailableAgain() = runBlocking {
        for (mode in listOf(LedgerReportAvailability.OFFLINE, LedgerReportAvailability.LOCAL_ONLY, LedgerReportAvailability.SYNCHRONIZING)) {
            val h = Harness(); h.load(); h.change { it.copy(availability = mode, syncRevision = 1) }
            assertCleared(h); assertNull(h.lifecycle.captureTicket()); assertFalse(h.lifecycle.load(query()))
            assertNull(h.lifecycle.prepareExport()); assertEquals(1, h.transport.queries)
            h.change { it.copy(availability = LedgerReportAvailability.ONLINE, syncRevision = 2) }
            assertCleared(h); assertEquals(1, h.transport.queries)
        }
    }
    @Test fun shortSyncStartAndFinishStillInvalidateProofThroughRevision() = runBlocking {
        val h = Harness(); h.load(); val shown = requireNotNull(h.lifecycle.prepareExport())
        h.change { it.copy(syncRevision = 2) }
        assertCleared(h); assertNull(h.lifecycle.download(shown)); assertEquals(0, h.transport.downloads)
    }
    @Test fun pendingQueryCannotPublishAfterAContextChangeEvenWhenTransportReturns() = runBlocking {
        val h = Harness(); val result = CompletableDeferred<LedgerReportPage>(); h.transport.held = result
        val job = async(start = CoroutineStart.UNDISPATCHED) { h.lifecycle.load(query()) }
        h.transport.started.await(); h.change { it.copy(sessionRevision = 1) }
        result.complete(page(query())); assertFalse(job.await()); assertCleared(h)
    }
    @Test fun draftChangeClearsResultsProofAndFileTicketWithoutResettingTheFormEpoch() = runBlocking {
        val h = Harness(); h.load(); val shown = requireNotNull(h.lifecycle.prepareExport())
        val ticket = requireNotNull(h.lifecycle.captureTicket()); val epoch = h.lifecycle.contextEpoch.value
        h.lifecycle.draftChanged(); assertCleared(h); assertFalse(h.lifecycle.isCurrent(ticket))
        assertEquals(epoch, h.lifecycle.contextEpoch.value); assertNull(h.lifecycle.download(shown))
        assertEquals(0, h.transport.downloads); assertEquals(1, h.transport.queries)
    }
    @Test fun delayedDocumentDeliveryCannotBeTakenAfterLeaveOrDraftChange() = runBlocking {
        for (leave in listOf(false, true)) {
            val h = Harness(); h.load(); val shown = requireNotNull(h.lifecycle.prepareExport())
            val delivery = requireNotNull(h.lifecycle.download(shown)); val ticket = requireNotNull(h.lifecycle.captureTicket(delivery))
            if (leave) h.lifecycle.leave() else h.lifecycle.draftChanged()
            assertFalse(h.lifecycle.isCurrent(ticket)); assertNull(h.lifecycle.captureTicket(delivery)); assertNull(delivery.takeIfCurrent())
        }
    }
    @Test fun comparisonAcceptsOnlyTheExactPreviousNaturalMonthAndLeavesPrimaryReportUnchanged() = runBlocking {
        val h = Harness(); h.load(); val primary = h.state.report
        assertNull(h.lifecycle.compare(query())); assertEquals(1, h.transport.queries)
        val compared = h.lifecycle.compare(previousFullReportMonth(requireNotNull(primary).query))
        assertEquals("2026-09-01", compared!!.query.startDate); assertSame(primary, h.state.report)
        assertEquals(2, h.transport.queries)
    }
    @Test fun lateComparisonAfterNewDraftIsDiscarded() = runBlocking {
        val h = Harness(); h.load(); val comparison = previousFullReportMonth(h.state.report!!.query)
        h.transport.held = CompletableDeferred()
        val request = async(start = CoroutineStart.UNDISPATCHED) { h.lifecycle.compare(comparison) }
        h.lifecycle.draftChanged(); h.transport.held!!.complete(page(comparison))
        assertNull(request.await()); assertCleared(h)
    }
    @Test fun unreadableEnvironmentErasesOldViewInsteadOfPreservingACompleteReport() = runBlocking {
        val h = Harness()
        var readable = true
        val lifecycle = LedgerReportLifecycle({ if (readable) h.environment else error("synthetic inaccessible storage") }, { h.transport }, { now })
        lifecycle.enter(); assertTrue(lifecycle.load(query())); val ticket = requireNotNull(lifecycle.captureTicket())
        readable = false; lifecycle.refreshEnvironment()
        assertNull(lifecycle.state.value.report); assertNull(lifecycle.state.value.confirmation)
        assertFalse(lifecycle.isCurrent(ticket)); assertNull(lifecycle.captureTicket())
    }
    @Test fun oldDeliveryCannotBeRelabeledWithANewerSessionTicket() = runBlocking {
        val h = Harness(); h.load(); val shown = requireNotNull(h.lifecycle.prepareExport())
        val original = requireNotNull(h.lifecycle.download(shown)); assertNotNull(h.lifecycle.captureTicket(original))
        assertNull(h.lifecycle.captureTicket(original)) // A delivery can mint its file ticket only once.
        h.change { it.copy(sessionRevision = 1) }; h.load()
        assertNotNull(h.lifecycle.captureTicket()); assertNull(h.lifecycle.captureTicket(original)); assertNull(original.takeIfCurrent())
    }
}
