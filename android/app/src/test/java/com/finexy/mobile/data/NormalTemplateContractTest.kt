package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

class NormalTemplateContractTest {
    private val accounts = listOf(AccountEntity(10, "同名", "USD"), AccountEntity(11, "同名", "CNY"))
    private val categories = listOf(CategoryEntity(20, "支出", type = 2), CategoryEntity(21, "同名", 20, 2),
        CategoryEntity(22, "其他支出", type = 2), CategoryEntity(23, "同名", 22, 2),
        CategoryEntity(30, "收入", type = 1), CategoryEntity(31, "同名", 30, 1),
        CategoryEntity(40, "转账", type = 3), CategoryEntity(41, "同名", 40, 3))
    private fun template() = TemplateEntity(9007199254740993, "模板", 3, 23, 10, -12345, "退款", "[\"9007199254740995\"]", hideAmount = true, ledgerId = 9)
    private fun apply(item: TemplateEntity = template(), role: Int = 1, strip: Boolean = false) = applyNormalTemplate(item, 9, role, accounts, categories, strip)
    private fun rejected(block: () -> Unit) { assertThrows(RuntimeException::class.java, block) }

    @Test fun refundApplicationRetainsExactIdsIntegerMoneyTagsAndHiddenAmount() {
        val seed = apply()
        assertEquals(9L, seed.ledgerId); assertEquals(23L, seed.categoryId); assertEquals(10L, seed.sourceAccountId)
        assertEquals(-12345L, seed.sourceAmountMinor); assertEquals("USD", seed.sourceCurrency)
        assertEquals(template().tagIdsJson, seed.tagIdsJson); assertTrue(seed.hideAmount)
        assertNotEquals(seed.localId, apply().localId)
    }
    @Test fun transfersKeepBothAmountsAndCurrenciesAndRejectUnequalSameCurrencyAmounts() {
        val transfer = template().copy(type = 4, categoryId = 41, sourceAmountMinor = 9999999999999,
            destinationAccountId = 11, destinationAmountMinor = 1234567890123)
        val seed = apply(transfer)
        assertEquals(9999999999999L, seed.sourceAmountMinor); assertEquals(1234567890123L, seed.destinationAmountMinor)
        assertEquals("CNY", seed.destinationCurrency)
        rejected { applyNormalTemplate(transfer, 9, 1, accounts.map { it.copy(currency = "USD") }, categories) }
    }
    @Test fun nonOwnersMustExplicitlyConfirmRemovingPrivateOwnerTags() {
        for (role in 2..3) {
            rejected { apply(role = role) }
            val seed = apply(role = role, strip = true)
            assertEquals("[]", seed.tagIdsJson); assertEquals(template().sourceAmountMinor, seed.sourceAmountMinor)
            assertEquals("[\"9007199254740995\"]", template().tagIdsJson)
        }
        rejected { apply(role = 4, strip = true) }
    }
    @Test fun wrongLedgerHiddenTemplateAndWrongCategoryTypeAreRejected() {
        rejected { apply(template().copy(ledgerId = 10)) }; rejected { apply(template().copy(hidden = true)) }
        rejected { apply(template().copy(type = 2, sourceAmountMinor = 123, categoryId = 23)) }
        rejected { apply(template().copy(templateType = 2)) }
    }
    @Test fun hiddenParentDuplicateIdAndMalformedCurrencyCannotBeUsed() {
        for (invalidAccounts in listOf(accounts + accounts.first(), accounts.map { it.copy(currency = "US") },
            listOf(accounts.first().copy(parentId = 99), accounts.last(), AccountEntity(99, "父级", "USD", hidden = true, type = 2)))) {
            rejected { applyNormalTemplate(template(), 9, 1, invalidAccounts, categories) }
        }
        rejected { applyNormalTemplate(template(), 9, 1, accounts, categories + categories.first { it.id == 23L }) }
        rejected { applyNormalTemplate(template(), 9, 1, accounts, categories.map { if (it.id == 22L) it.copy(hidden = true) else it }) }
    }
    @Test fun malformedTagsNegativeIncomeAndMoneyOutsideWireRangeAreRejected() {
        for (raw in listOf("null", "[true]", "[1.0]", "[\"01\"]", "[1,1]", "[-1]")) rejected { apply(template().copy(tagIdsJson = raw)) }
        rejected { apply(template().copy(sourceAmountMinor = -10000000000000)) }
        rejected { apply(template().copy(type = 2, categoryId = 31)) }
        rejected { apply(template().copy(destinationAccountId = 11)) }
    }
    @Test fun zeroAmountTemplateIsValidButStillRequiresAnAmountInTheEntryForm() {
        assertEquals(0L, apply(template().copy(sourceAmountMinor = 0)).sourceAmountMinor)
    }
    @Test fun accountEnvelopeRejectsWrongNestedLedgerMissingResultAndCoercingSuccess() {
        requirePeriodicAccountsLedger("""{"success":true,"result":[{"id":"1","ledgerId":"9","name":"A","type":1,"currency":"USD","subAccounts":[{"id":"2","ledgerId":"9","name":"B","type":1,"currency":"USD"}]}]}""", 9)
        for (raw in listOf("""{"success":true}""", """{"success":"true","result":[]}""",
            """{"success":true,"result":[{"id":"1"}]}""", """{"success":true,"result":[{"id":"1","ledgerId":"9","name":"A","type":1,"currency":"USD","subAccounts":[{"id":"2","ledgerId":"10"}]}]}""")) rejected { requirePeriodicAccountsLedger(raw, 9) }
    }
    @Test fun missingCurrencyAndMalformedCategoryResponsesCannotInventResources() {
        rejected { requirePeriodicAccountsLedger("""{"success":true,"result":[{"id":"1","ledgerId":"9","name":"A","type":1}]}""", 9) }
        requirePeriodicCategories("""{"success":true,"result":{"2":[{"id":"1","name":"Root","type":2,"subCategories":[{"id":"2","parentId":"1","name":"Leaf","type":2}]}]}}""")
        for (raw in listOf("""{"success":true}""", """{"success":true,"result":{"2":[{"id":"1","name":"A","type":2,"hidden":"false"}]}}""",
            """{"success":true,"result":{"2":[{"id":"1","name":"A","type":2},{"id":"1","name":"B","type":2}]}}""")) rejected { requirePeriodicCategories(raw) }
    }
    @Test fun transactionDetailMustMatchScopeAndPreserveCompleteIntegerAndAssociationFields() {
        val item = """{"id":"9007199254740993","ledgerId":"9","type":3,"sourceAccountId":"10","destinationAccountId":"0","categoryId":"21","sourceAmount":-1234,"destinationAmount":0,"time":1791167400,"utcOffset":-240,"comment":"fixture","hideAmount":true,"editable":true,"tagIds":["9007199254740995"]}"""
        val envelope = """{"success":true,"result":$item}"""
        requirePeriodicTransactionDetail(envelope, 9007199254740993, 9)
        rejected { requirePeriodicTransactionDetail(envelope, 9007199254740993, 10) }
        rejected { requirePeriodicTransactionDetail(envelope, 9007199254740994, 9) }
        for (broken in listOf(envelope.replace("\"sourceAmount\":-1234", "\"sourceAmount\":\"-1234\""),
            envelope.replace("\"editable\":true", "\"editable\":\"true\""), envelope.replace("\"tagIds\":[\"9007199254740995\"]", "\"tagIds\":null"))) {
            rejected { requirePeriodicTransactionDetail(broken, 9007199254740993, 9) }
        }
    }

}
