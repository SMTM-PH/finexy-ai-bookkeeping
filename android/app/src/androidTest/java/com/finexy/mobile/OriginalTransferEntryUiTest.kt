package com.finexy.mobile

import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.finexy.mobile.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Production EntryScreen in the existing non-exported host with the production Theme/Scaffold. */
class OriginalTransferEntryUiTest {
    @get:Rule val rule = createAndroidComposeRule<EntryReliabilityQaActivity>()
    private data class Saved(val localId: String?, val source: Long, val amount: Long, val destination: Long?, val destinationAmount: Long)
    private val saved = AtomicReference<Saved?>()
    private val accounts = listOf(AccountEntity(10, "Synthetic source", "USD"), AccountEntity(11, "Synthetic destination", "CNY"))
    private val categories = listOf(CategoryEntity(3, "Synthetic transfer parent", 0, 3), CategoryEntity(23, "Synthetic transfer", 3, 3))
    private fun existing(source: Long, amount: Long, destination: Long?, destinationAmount: Long) = Activity(
        "Synthetic original", "", "转账", "Synthetic transfer", "original-local-id", serverId = 9007199254740993L,
        accountId = source, categoryId = 23, time = 1790000000000, utcOffset = -240,
        destinationAccountId = destination, destinationAmountMinor = destinationAmount, sourceAmountMinor = amount,
        currency = if (source == 0L) "CNY" else "USD", destinationCurrency = "CNY", pictureIdsJson = "[\"601\"]")
    private fun show(row: Activity?) {
        rule.runOnUiThread { rule.activity.setContent {
            FinexyTheme(isLightTheme = true) { Scaffold(containerColor = CanvasBlack) { padding ->
                EntryScreen(padding, false, existing = row, accounts = accounts, serverCategories = categories,
                    defaultAccountId = 0) { amount, _, _, _, _, id, source, _, destination, destinationAmount ->
                    saved.set(Saved(id, source, amount, destination, destinationAmount))
                }
            } }
        } }
        rule.waitForIdle()
    }

    @Test fun originalIncomingShowsTheOriginalDestinationAndSavesZeroSource() {
        show(existing(0, 0, 11, 456))
        rule.onNodeWithText("从系统外转入：", substring = true).assertExists()
        rule.onNodeWithText("Synthetic destination").assertExists()
        rule.onNodeWithText("Synthetic source").assertDoesNotExist()
        rule.onNodeWithContentDescription("记账金额").assertTextContains("4.56")
        rule.onNodeWithText("保存修改").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Saved("original-local-id", 0, 0, 11, 456), saved.get())
    }

    @Test fun originalOutgoingKeepsOnlyItsSourceAndAmount() {
        show(existing(10, 123, null, 0))
        rule.onNodeWithText("转出到系统外：", substring = true).assertExists()
        rule.onNodeWithText("转入账户").assertDoesNotExist()
        rule.onNodeWithText("Synthetic source").assertExists()
        rule.onNodeWithContentDescription("记账金额").assertTextContains("1.23")
        rule.onNodeWithText("保存修改").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Saved("original-local-id", 10, 123, null, 0), saved.get())
    }

    @Test fun newUnselectedSourceRemainsInternalEvenAfterChoosingADestination() {
        show(null)
        rule.onNodeWithText("转账").performClick()
        rule.onNodeWithText("在两个不同的本系统账户间转账", substring = true).assertExists()
        rule.onNodeWithContentDescription("记账金额").performTextInput("1.23")
        rule.onNodeWithText("选择转入账户").performScrollTo().performClick()
        rule.onNodeWithText("Synthetic destination · CNY").performClick()
        rule.onNodeWithText("账户不可用，请重新选择").assertExists()
        rule.onNodeWithText("保存转账").performScrollTo().assertIsNotEnabled()
        assertNull(saved.get())
    }

    @Test fun explicitOutgoingDirectionDoesNotSendHiddenInternalDestinationState() {
        show(existing(10, 123, 11, 456))
        rule.onNodeWithText("转出").performScrollTo().performClick()
        rule.onNodeWithText("转入账户").assertDoesNotExist()
        rule.onNodeWithText("保存修改").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Saved("original-local-id", 10, 123, null, 0), saved.get())
    }
}
