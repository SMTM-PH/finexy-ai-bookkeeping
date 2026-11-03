package com.finexy.mobile.data

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/** A null total is outside the supported integer range, never a zero balance. */
data class CurrencyAmounts(val currency: String, val balanceMinor: Long?, val incomeMinor: Long?, val expenseMinor: Long?)

private val minimumTotal = BigInteger.valueOf(Long.MIN_VALUE)
private val maximumTotal = BigInteger.valueOf(Long.MAX_VALUE)
private fun BigInteger.supportedTotal(): Long? = if (this >= minimumTotal && this <= maximumTotal) toLong() else null

/** No implicit FX: server account balances and cumulative flows have separate contracts. */
fun dashboardAmounts(accounts: List<AccountEntity>, transactions: List<TransactionEntity>): List<CurrencyAmounts> {
    val visibleAccounts = accounts.filter { account ->
        !account.hidden && account.type == 1 && (account.parentId == 0L ||
            accounts.any { it.id == account.parentId && !it.hidden })
    }
    val activeRows = transactions.filter { !it.deleted }
    val currencies = (visibleAccounts.map { it.currency } + activeRows.map { it.currency }).distinct().sorted()
    return currencies.map { currency ->
        val rows = activeRows.filter { it.currency == currency }
        val serverBalance = visibleAccounts.filter { it.currency == currency && it.id != TransactionEntity.LOCAL_ACCOUNT_ID }
            .fold(BigInteger.ZERO) { sum, account -> sum + BigInteger.valueOf(account.balanceMinor) }
        val localAccountVisible = visibleAccounts.any { it.currency == currency && it.id == TransactionEntity.LOCAL_ACCOUNT_ID }
        val localBalance = if (localAccountVisible) activeRows.fold(BigInteger.ZERO) { sum, row ->
            val source = if (row.sourceAccountId == TransactionEntity.LOCAL_ACCOUNT_ID && row.currency == currency) when (row.type) {
                TransactionRepository.TYPE_INCOME, TransactionRepository.TYPE_MODIFY_BALANCE -> BigInteger.valueOf(row.sourceAmountMinor)
                TransactionRepository.TYPE_EXPENSE, TransactionRepository.TYPE_TRANSFER -> BigInteger.valueOf(row.sourceAmountMinor).negate()
                else -> BigInteger.ZERO
            } else BigInteger.ZERO
            val destination = if (row.type == TransactionRepository.TYPE_TRANSFER && row.destinationAccountId == TransactionEntity.LOCAL_ACCOUNT_ID)
                BigInteger.valueOf(row.destinationAmountMinor) else BigInteger.ZERO
            sum + source + destination
        } else BigInteger.ZERO
        CurrencyAmounts(currency, (serverBalance + localBalance).supportedTotal(),
            rows.filter { it.type == TransactionRepository.TYPE_INCOME }
                .fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row.sourceAmountMinor) }.supportedTotal(),
            rows.filter { it.type == TransactionRepository.TYPE_EXPENSE }
                .fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row.sourceAmountMinor) }.supportedTotal())
    }
}

fun currencyMoney(amountMinor: Long, currency: String): String =
    "$currency ${String.format(Locale.CHINA, "%,.2f", BigDecimal.valueOf(amountMinor, 2))}"
