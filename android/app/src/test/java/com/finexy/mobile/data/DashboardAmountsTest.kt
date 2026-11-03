package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

class DashboardAmountsTest {
    private fun row(id: String, type: Int, amount: Long, currency: String = "CNY", source: Long = 10,
                    destination: Long? = null, destinationAmount: Long = 0) = TransactionEntity(
        id, type = type, categoryName = "测试", sourceAmountMinor = amount, currency = currency,
        sourceAccountId = source, destinationAccountId = destination, destinationAmountMinor = destinationAmount,
        comment = "", time = 1)

    @Test fun differentCurrenciesUseContractBalancesAndDoNotMixTransferSides() {
        val accounts = listOf(AccountEntity(10, "人民币", "CNY", 70000), AccountEntity(11, "美元", "USD", 9900))
        val amounts = dashboardAmounts(accounts, listOf(
            row("income", 2, 10000), row("usd-expense", 3, 100, "USD", 11),
            row("transfer", 4, 700, destination = 11, destinationAmount = 100)))
        assertEquals(listOf(CurrencyAmounts("CNY", 70000, 10000, 0), CurrencyAmounts("USD", 9900, 0, 100)), amounts)
    }

    @Test fun parentAndHiddenBalancesAreExcludedWithoutDroppingHistoricalFlows() {
        val accounts = listOf(AccountEntity(1, "分组", "CNY", 50000, type = 2, hidden = true),
            AccountEntity(10, "子账户", "CNY", 20000, parentId = 1), AccountEntity(11, "隐藏", "CNY", 40000, hidden = true),
            AccountEntity(12, "有效", "CNY", -1000))
        assertEquals(CurrencyAmounts("CNY", -1000, 0, 300), dashboardAmounts(accounts, listOf(row("old", 3, 300))).single())
    }

    @Test fun localWalletAdjustmentsRefundsAndTransfersUseTheLocalSideOnly() {
        val accounts = listOf(AccountEntity(-1, "本地", "CNY"), AccountEntity(10, "云端", "USD", 900))
        val amounts = dashboardAmounts(accounts, listOf(row("initial", 1, 10000, source = -1),
            row("expense", 3, 2000, source = -1), row("refund", 3, -500, source = -1),
            row("out", 4, 700, source = -1, destination = 10, destinationAmount = 100),
            row("in", 4, 200, "USD", 10, -1, 1400)))
        assertEquals(CurrencyAmounts("CNY", 9200, 0, 1500), amounts.first { it.currency == "CNY" })
        assertEquals(CurrencyAmounts("USD", 900, 0, 0), amounts.first { it.currency == "USD" })
    }

    @Test fun deletedRowsAndBalanceAdjustmentsDoNotBecomeIncomeOrExpense() {
        val rows = listOf(row("deleted", 2, 900).copy(deleted = true), row("adjustment", 1, 2000))
        assertEquals(CurrencyAmounts("CNY", 7000, 0, 0), dashboardAmounts(listOf(AccountEntity(10, "账户", "CNY", 7000)), rows).single())
    }

    @Test fun amountsRetainIntegerPrecisionAndCurrencyWhenFormatted() {
        val precise = 9_007_199_254_740_993L
        val result = dashboardAmounts(listOf(AccountEntity(10, "账户", "USD", precise)), emptyList()).single()
        assertEquals(precise, result.balanceMinor)
        assertEquals("USD 90,071,992,547,409.93", currencyMoney(result.balanceMinor!!, result.currency))
        assertEquals("USD -12.34", currencyMoney(-1234, "USD"))
    }

    @Test fun noDataDoesNotInventAZeroBalanceOrDefaultCurrency() {
        assertTrue(dashboardAmounts(emptyList(), emptyList()).isEmpty())
    }

    @Test fun outOfRangeTotalsDoNotHideValidCurrenciesOrIndependentFields() {
        val accounts = listOf(AccountEntity(10, "人民币", "CNY", 1200),
            AccountEntity(11, "美元一", "USD", Long.MAX_VALUE), AccountEntity(12, "美元二", "USD", 1))
        val amounts = dashboardAmounts(accounts, listOf(row("income1", 2, Long.MAX_VALUE, "USD", 11),
            row("income2", 2, 1, "USD", 12), row("expense", 3, 200, "USD", 11)))
        assertEquals(CurrencyAmounts("CNY", 1200, 0, 0), amounts.first { it.currency == "CNY" })
        assertEquals(CurrencyAmounts("USD", null, null, 200), amounts.first { it.currency == "USD" })
    }

    @Test fun intermediateOverflowDoesNotInvalidateARepresentableFinalTotal() {
        val accounts = listOf(AccountEntity(10, "一", "CNY", Long.MAX_VALUE),
            AccountEntity(11, "二", "CNY", Long.MAX_VALUE), AccountEntity(12, "三", "CNY", Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE - 1, dashboardAmounts(accounts, emptyList()).single().balanceMinor)
    }

    @Test fun localRefundAtIntegerMinimumDoesNotWrapAndCanOffsetServerBalance() {
        val accounts = listOf(AccountEntity(-1, "本地", "CNY"), AccountEntity(10, "云端", "CNY", -1))
        val amounts = dashboardAmounts(accounts, listOf(row("refund", 3, Long.MIN_VALUE, source = -1))).single()
        assertEquals(CurrencyAmounts("CNY", Long.MAX_VALUE, 0, Long.MIN_VALUE), amounts)
        assertNull(dashboardAmounts(listOf(AccountEntity(-1, "本地", "CNY")),
            listOf(row("refund", 3, Long.MIN_VALUE, source = -1))).single().balanceMinor)
    }
}
