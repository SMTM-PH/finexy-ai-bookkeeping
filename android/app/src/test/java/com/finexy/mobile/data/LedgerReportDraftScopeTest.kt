package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

/** Real lifecycle ownership transitions, without Android connectivity, HTTP or persisted state. */
class LedgerReportDraftScopeTest {
    private val initialForm = LedgerReportForm("2026-10-01", "2026-10-31", "UTC")
    private val editedForm = LedgerReportForm(
        start = "2026-09-", end = "2026-10-17", timezone = "Asia/",
        types = setOf(2, 3), accountIds = setOf(9_007_199_254_740_993L), excludeAccounts = true, accountChildren = false,
        categoryIds = setOf(0L, 91L), excludeCategories = true, categoryChildren = false,
        tags = listOf(LedgerReportTagChoice("all", setOf(21L, 22L)), LedgerReportTagChoice("notAny", setOf(23L))),
        currencies = setOf("CNY", "USD"), hidden = true
    )
    private val stagedTag = LedgerReportTagChoice("notAll", setOf(31L, 32L))
    private fun environment() = LedgerReportEnvironmentState("qa-a", LedgerReportContext("https://server-a", "user-a", 41L),
        LedgerReportAvailability.ONLINE, 1L, 0L, 11L)
    private val noRequests = object : LedgerReportTransport {
        override suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int, expectedHash: String?): LedgerReportPage =
            error("Editing a draft must not request a report")
        override suspend fun preview(context: LedgerReportContext, page: LedgerReportPage): LedgerReportPreview =
            error("Editing a draft must not preview an export")
        override suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
            confirmed: Boolean, includeHiddenAmounts: Boolean): LedgerReportDownload = error("Editing a draft must not download")
    }
    private inner class Harness(initial: LedgerReportEnvironmentState = environment()) {
        var currentEnvironment = initial
        var failSnapshot = false
        val lifecycle = LedgerReportLifecycle({
            check(!failSnapshot)
            currentEnvironment
        }, { noRequests })
        init { lifecycle.enter() }
        fun change(update: (LedgerReportEnvironmentState) -> LedgerReportEnvironmentState) {
            currentEnvironment = update(currentEnvironment)
            lifecycle.refreshEnvironment()
        }
        fun bind(current: LedgerReportDraftChoices? = null, showAmountsInitially: Boolean = true) =
            rebindLedgerReportDraft(current, lifecycle.draftScope.value, showAmountsInitially) { initialForm }
        fun edited(visible: Boolean = false) = bind().copy(form = editedForm, amountsVisible = visible, pendingTag = stagedTag)
    }
    private fun assertPreserved(draft: LedgerReportDraftChoices, visible: Boolean) {
        // Includes incomplete raw date/timezone input, five filter dimensions and pending tag controls.
        assertEquals(editedForm, draft.form)
        assertEquals(stagedTag, draft.pendingTag)
        assertEquals(visible, draft.amountsVisible)
    }
    private fun assertReset(draft: LedgerReportDraftChoices) {
        assertEquals(initialForm, draft.form)
        assertEquals(LedgerReportTagChoice("any"), draft.pendingTag)
        assertFalse(draft.amountsVisible)
    }

    @Test fun offlineNetworkSyncAndSameUserSessionRefreshInvalidateFinanceButKeepAllRawChoices() {
        for (visible in listOf(false, true)) {
            val h = Harness()
            val originalScope = h.lifecycle.draftScope.value
            var draft = h.edited(visible)
            val changes: List<(LedgerReportEnvironmentState) -> LedgerReportEnvironmentState> = listOf(
                { it.copy(availability = LedgerReportAvailability.OFFLINE, networkId = 0L) },
                { it.copy(availability = LedgerReportAvailability.ONLINE, networkId = 12L) },
                { it.copy(availability = LedgerReportAvailability.SYNCHRONIZING, syncRevision = 1L) },
                { it.copy(availability = LedgerReportAvailability.ONLINE, syncRevision = 2L) },
                // Same user's token changes the credential revision, not the safe identity partition.
                { it.copy(sessionRevision = 2L) }
            )
            for (change in changes) {
                val financialEpoch = h.lifecycle.contextEpoch.value
                h.change(change)
                assertTrue(h.lifecycle.contextEpoch.value > financialEpoch)
                assertEquals(originalScope, h.lifecycle.draftScope.value)
                draft = h.bind(draft)
                assertPreserved(draft, visible)
            }
            val financialEpoch = h.lifecycle.contextEpoch.value
            h.change { it.copy(availability = LedgerReportAvailability.SYNCHRONIZING, syncRevision = 3L) }
            h.change { it.copy(availability = LedgerReportAvailability.ONLINE, syncRevision = 4L) }
            // A short actual sync may start and finish before the UI collects either event.
            assertTrue(h.lifecycle.contextEpoch.value >= financialEpoch + 2)
            assertEquals(originalScope, h.lifecycle.draftScope.value)
            assertPreserved(h.bind(draft), visible)
        }
    }

    @Test fun rotationReattachmentKeepsDraftAndUserAmountVisibilityChoice() {
        for (visible in listOf(false, true)) {
            val h = Harness()
            val original = h.edited(visible)
            val scope = h.lifecycle.draftScope.value
            h.lifecycle.enter()
            assertEquals(scope, h.lifecycle.draftScope.value)
            assertPreserved(h.bind(original, showAmountsInitially = !visible), visible)
        }
    }

    @Test fun everyRealOwnerDimensionClearsInputsAndHidesNewOwnerAmounts() {
        val changes: List<(LedgerReportEnvironmentState) -> LedgerReportEnvironmentState> = listOf(
            { it.copy(namespace = "qa-b") },
            { it.copy(context = requireNotNull(it.context).copy(serverUrl = "https://server-b")) },
            { it.copy(context = requireNotNull(it.context).copy(identity = "user-b")) },
            { it.copy(context = requireNotNull(it.context).copy(ledgerId = 0L)) },
            { it.copy(availability = LedgerReportAvailability.LOCAL_ONLY, configuredServerUrl = "https://server-a") }
        )
        for (change in changes) {
            val h = Harness(); val old = h.edited(visible = true)
            h.change(change)
            assertTrue(h.lifecycle.draftScope.value.revision > old.scope.revision)
            assertNotEquals(old.scope.owner, h.lifecycle.draftScope.value.owner)
            assertReset(h.bind(old, showAmountsInitially = true))
        }
    }

    @Test fun fastOwnerAbaCannotRestoreDraftWhenObserverMissesIntermediateOwner() {
        val changes: List<(LedgerReportEnvironmentState) -> LedgerReportEnvironmentState> = listOf(
            { it.copy(namespace = "qa-b") },
            { it.copy(context = requireNotNull(it.context).copy(serverUrl = "https://server-b")) },
            { it.copy(context = requireNotNull(it.context).copy(identity = "user-b")) },
            { it.copy(context = requireNotNull(it.context).copy(ledgerId = 42L)) },
            { it.copy(availability = LedgerReportAvailability.LOCAL_ONLY, configuredServerUrl = "https://server-a") }
        )
        for (change in changes) {
            val h = Harness(); val old = h.edited(visible = true)
            h.change(change)
            h.change { environment() }
            assertEquals(old.scope.owner, h.lifecycle.draftScope.value.owner)
            assertTrue(h.lifecycle.draftScope.value.revision >= old.scope.revision + 2)
            assertReset(h.bind(old))
        }
    }

    @Test fun lostSessionAndUnreadableEnvironmentCannotAssignOldInputsToReturningIdentity() {
        for (snapshotFails in listOf(false, true)) {
            val h = Harness(); val old = h.edited(visible = true)
            if (snapshotFails) { h.failSnapshot = true; h.lifecycle.refreshEnvironment() }
            else h.change { it.copy(context = null, sessionRevision = 2L) }
            assertNull(h.lifecycle.draftScope.value.owner)
            assertTrue(h.lifecycle.draftScope.value.revision > old.scope.revision)
            assertReset(h.bind(old))
            h.failSnapshot = false
            h.currentEnvironment = environment()
            h.lifecycle.refreshEnvironment()
            // Simulate a collector that saw neither the unknown owner nor its cleared form.
            assertEquals(old.scope.owner, h.lifecycle.draftScope.value.owner)
            assertReset(h.bind(old))
        }
    }

    @Test fun localModeUsesConfiguredServerEvenWhenFinancialContextStaysLocal() {
        val h = Harness(environment().copy(context = LedgerReportContext("local", "local", 41L),
            availability = LedgerReportAvailability.LOCAL_ONLY, configuredServerUrl = "https://server-a", configuredIdentity = "user-a"))
        val old = h.edited(visible = true)
        assertEquals("https://server-a", old.scope.owner?.serverUrl)
        assertTrue(requireNotNull(old.scope.owner).localMode)
        assertEquals("user-a", old.scope.owner?.identity)
        h.change { it.copy(networkId = 0L, syncRevision = 1L, sessionRevision = 2L) }
        assertPreserved(h.bind(old), visible = true)
        h.change { it.copy(configuredServerUrl = "https://server-b") }
        assertEquals("https://server-b", h.lifecycle.draftScope.value.owner?.serverUrl)
        assertReset(h.bind(old))
        h.change { it.copy(configuredServerUrl = "https://server-a") }
        assertEquals(old.scope.owner, h.lifecycle.draftScope.value.owner)
        assertReset(h.bind(old))
    }

    @Test fun localModeAccountAbaCannotReviveOldInputsEvenWithUnchangedLocalFinancialContext() {
        val h = Harness(environment().copy(context = LedgerReportContext("local", "local", 41L),
            availability = LedgerReportAvailability.LOCAL_ONLY, configuredServerUrl = "https://server-a", configuredIdentity = "user-a"))
        val old = h.edited(visible = true)
        h.change { it.copy(configuredIdentity = "user-b", sessionRevision = 2L) }
        assertReset(h.bind(old))
        h.change { it.copy(configuredIdentity = "user-a", sessionRevision = 3L) }
        assertEquals(old.scope.owner, h.lifecycle.draftScope.value.owner)
        assertReset(h.bind(old))
    }

    @Test fun internalLeaveAndReentryNeverRevivePreviousDraftOrVisibility() {
        val h = Harness(); val old = h.edited(visible = true)
        h.lifecycle.leave()
        assertNull(h.lifecycle.draftScope.value.owner)
        assertReset(h.bind(old))
        h.lifecycle.enter()
        assertEquals(old.scope.owner, h.lifecycle.draftScope.value.owner)
        assertReset(h.bind(old))
    }

    @Test fun unknownInitialSessionCannotInheritShowAmountsPreferenceWhenIdentityAppears() {
        val h = Harness(environment().copy(context = null))
        val unknownDraft = h.bind(showAmountsInitially = true)
        assertNull(unknownDraft.scope.owner)
        assertFalse(unknownDraft.amountsVisible)
        h.change { environment() }
        assertReset(h.bind(unknownDraft, showAmountsInitially = true))
        // The first directly verified owner may still honor its own initial display preference.
        assertTrue(Harness().bind(showAmountsInitially = true).amountsVisible)
    }
}
