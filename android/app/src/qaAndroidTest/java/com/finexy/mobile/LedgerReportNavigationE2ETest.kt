package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Production FinexyApp entry points only; not a report-only replacement host. */
class LedgerReportNavigationE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private lateinit var fixture: LedgerReportQaFixture

    @Before fun prepareDisposableRealLogin() {
        LedgerReportQaFixture.requireReportQaUrl()
        fixture = LedgerReportQaFixture(rule)
        fixture.prepare()
    }

    @After fun cleanOnlyOwnedFixture() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test fun overviewEntryKeepsLedgerAAndAppliedDatesAcrossActivityRecreationAndReturn() {
        fixture.openReport(fixture.ledgerA.id, LedgerReportQaEntry.OVERVIEW)
        val expected = fixture.applyReport()
        assertTrue("A query must contain its own explicit fixture rows", expected.details.any { it.comment == "Synthetic A visible expense" })
        assertTrue("A query must not import B/default rows", expected.details.none { it.comment.startsWith("Synthetic B") || it.comment.startsWith("Synthetic default") })
        captureQaUiEvidence(rule, "report-navigation-a-before-recreation")
        rule.activityRule.scenario.recreate()
        fixture.waitFor("当前账本报告")
        fixture.assertAgainstOracle(expected)
        fixture.returnToEntry(fixture.ledgerA.id, LedgerReportQaEntry.OVERVIEW)
        captureQaUiEvidence(rule, "report-navigation-a-returned")
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun accountEntryReadsOnlyLedgerBAndOriginalDetailThenReturnsToAccounts() {
        fixture.openReport(fixture.ledgerB.id, LedgerReportQaEntry.ACCOUNTS)
        val expected = fixture.applyReport()
        val original = expected.details.single { it.comment == "Synthetic B expense" }
        assertTrue("B report must not include A/default rows", expected.details.none { it.comment.startsWith("Synthetic A") || it.comment.startsWith("Synthetic default") })
        fixture.clickText("查看流水详情 #${original.id}")
        fixture.waitFor("流水详情 #${original.id}")
        rule.onNodeWithText("备注：${original.comment}").assertIsDisplayed()
        rule.onNodeWithText("${original.date} · 支出（退款冲减） · UTC").assertIsDisplayed()
        fixture.clickText("关闭")
        fixture.assertAgainstOracle(expected)
        captureQaUiEvidence(rule, "report-navigation-b-account-detail-returned")
        fixture.returnToEntry(fixture.ledgerB.id, LedgerReportQaEntry.ACCOUNTS)
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun explicitDefaultLedgerEntryDoesNotReuseAReportOrItsConfirmation() {
        fixture.openReport(fixture.ledgerA.id, LedgerReportQaEntry.OVERVIEW)
        fixture.applyReport()
        fixture.clickText("预览 CSV 导出")
        fixture.waitFor("确认导出 CSV")
        fixture.clickText("取消")
        fixture.returnToEntry(fixture.ledgerA.id, LedgerReportQaEntry.OVERVIEW)

        fixture.openReport(0, LedgerReportQaEntry.OVERVIEW)
        rule.onNodeWithText("确认导出 CSV").assertDoesNotExist()
        rule.onNodeWithText("完整在线报告").assertDoesNotExist()
        val expected = fixture.applyReport()
        assertTrue("Explicit default ledger must retain its zero identifier", expected.query.ledgerId == 0L)
        assertTrue("Default report must contain its own rows", expected.details.any { it.comment == "Synthetic default expense" })
        assertTrue("Default report must not revive A/B rows", expected.details.none { it.comment.startsWith("Synthetic A") || it.comment.startsWith("Synthetic B") })
        captureQaUiEvidence(rule, "report-navigation-default-zero")
        fixture.returnToEntry(0, LedgerReportQaEntry.OVERVIEW)
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }
}
