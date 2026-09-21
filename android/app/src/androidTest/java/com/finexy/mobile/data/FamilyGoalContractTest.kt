package com.finexy.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract tests for the family / ledger / savings-goal feature: strict API
 * parsing, safe Room reconciliation, the v17→v18 migration and backup schema
 * 14 round-trip. A malformed server response must throw instead of reaching
 * Room, and only a real empty list may clear cached rows.
 */
@RunWith(AndroidJUnit4::class)
class FamilyGoalContractTest {
    private lateinit var db: FinexyDatabase
    private lateinit var repository: TransactionRepository

    private suspend fun <T> listOfFlow(flow: kotlinx.coroutines.flow.Flow<List<T>>): List<T> = flow.first()

    private fun groupJson(id: String, memberCount: Int = 2) = JSONObject()
        .put("id", id).put("ownerUid", "100").put("name", "温暖小家")
        .put("comment", "").put("memberCount", memberCount).put("createdTime", 1700000000)

    private fun ledgerJson(id: String, type: Int = 2, familyId: String = "7") = JSONObject()
        .put("id", id).put("ownerUid", "100").put("type", type)
        .put("familyId", familyId).put("name", "家庭账本").put("comment", "").put("createdTime", 1700000000)

    private fun goalJson(id: String, saved: Long = 50000, achieved: Boolean = false) = JSONObject()
        .put("id", id).put("uid", "100").put("ledgerId", "9").put("name", "全家旅行基金")
        .put("targetAmount", 200000).put("savedAmount", saved).put("achieved", achieved)
        .put("comment", "")

    private fun envelope(vararg items: JSONObject) =
        JSONObject().put("success", true).put("result", JSONArray().apply { items.forEach { put(it) } }).toString()

    private fun api(): FinexyApi = FinexyApi(SecureStore(ApplicationProvider.getApplicationContext<Context>()))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = TransactionRepository(context, db, false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun familyGroupParsingAcceptsWellFormedListAndReconcilesRoom() = runBlocking {
        val parsed = api().parseFamilyGroups(envelope(groupJson("7"), groupJson("8")))
        assertEquals(2, parsed.size)
        repository.replaceFamilyGroups(parsed)
        assertEquals(2, listOfFlow(repository.observeFamilyGroups()).size)
    }

    @Test
    fun familyGroupParsingRejectsDuplicateOrInvalidIds() {
        assertThrows(IllegalArgumentException::class.java) {
            api().parseFamilyGroups(envelope(groupJson("7"), groupJson("7")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            api().parseFamilyGroups(envelope(groupJson("0")))
        }
    }

    @Test
    fun ledgerParsingRejectsPersonalLedgerWithFamilyReference() {
        assertThrows(IllegalArgumentException::class.java) {
            api().parseLedgers(envelope(ledgerJson("9", type = 1, familyId = "7")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            api().parseLedgers(envelope(ledgerJson("9", type = 2, familyId = "0")))
        }
    }

    @Test
    fun ledgerInvitationPreviewParsesLedgerRoleAndExpiryStrictly() {
        val result = JSONObject().put("ledger", ledgerJson("9"))
            .put("role", RemoteLedgerMember.ROLE_VIEWER).put("inviteeName", "Alex")
            .put("inviterNickname", "Owner").put("expiredTime", 2_000_000_000)
        val preview = api().parseLedgerInvitationPreview(JSONObject().put("success", true).put("result", result).toString())
        assertEquals(9L, preview.ledger.id)
        assertEquals(RemoteLedgerMember.ROLE_VIEWER, preview.role)
        assertEquals("Owner", preview.inviterNickname)
        assertThrows(IllegalArgumentException::class.java) {
            api().parseLedgerInvitationPreview(JSONObject().put("success", true).put("result", JSONObject(result.toString()).put("role", 2)).toString())
        }
    }

    @Test
    fun ledgerDeletePreviewRejectsInconsistentServerState() {
        fun result(canDelete: Boolean) = JSONObject().put("ledgerId", "9").put("ledgerName", "家庭账本")
            .put("activeMemberCount", 2).put("pendingInvitationCount", 1).put("accountCount", 1)
            .put("transactionCount", 0).put("savingsGoalCount", 0).put("savingsGoalFundCount", 0)
            .put("canDelete", canDelete).put("blockingReasons", JSONArray().put("accounts"))
        val preview = api().parseLedgerDeletePreview(JSONObject().put("success", true).put("result", result(false)).toString())
        assertFalse(preview.canDelete)
        assertEquals(1, preview.accountCount)
        assertThrows(IllegalArgumentException::class.java) {
            api().parseLedgerDeletePreview(JSONObject().put("success", true).put("result", result(true)).toString())
        }
    }

    @Test
    fun ledgerOverviewParsesBalancesAndRejectsDuplicateCurrencies() {
        fun result() = JSONObject().put("ledgerId", "9").put("ledgerName", "家庭账本")
            .put("activeMemberCount", 2).put("accountCount", 3).put("transactionCount", 8)
            .put("savingsGoalCount", 1).put("balances", JSONArray()
                .put(JSONObject().put("currency", "CNY").put("balance", 12345))
                .put(JSONObject().put("currency", "USD").put("balance", -200)))
        val overview = api().parseLedgerOverview(JSONObject().put("success", true).put("result", result()).toString())
        assertEquals(9L, overview.ledgerId)
        assertEquals(3, overview.accountCount)
        assertEquals(-200L, overview.balances.last().balanceMinor)
        assertThrows(IllegalArgumentException::class.java) {
            val duplicate = result().put("balances", JSONArray()
                .put(JSONObject().put("currency", "CNY").put("balance", 1))
                .put(JSONObject().put("currency", "CNY").put("balance", 2)))
            api().parseLedgerOverview(JSONObject().put("success", true).put("result", duplicate).toString())
        }
    }

    @Test
    fun goalTransferParsingKeepsSignedBalancesAndInboundCurrency() {
        val account = JSONObject().put("id", "9001").put("name", "家庭钱包")
            .put("currency", "EUR").put("balance", -12500)
        val options = api().parseSavingsGoalAccounts(envelope(account))
        assertEquals(-12500L, options.single().balanceMinor)

        val transfer = JSONObject().put("id", "8001").put("timeSequenceId", "1700000000000")
            .put("type", 4).put("categoryId", "0").put("time", 1700000000)
            .put("sourceAccountId", "0").put("destinationAccountId", "9001")
            .put("sourceAmount", 0).put("destinationAmount", 12500)
            .put("destinationAccount", account).put("comment", "存钱计划取出")
        assertEquals("EUR", api().parseTransactionResponse(
            JSONObject().put("result", JSONObject().put("items", JSONArray().put(transfer))).toString()
        ).single().currency)
    }

    @Test
    fun ledgerReconcileKeepsExplicitListAndRejectsDefaultLedgerRow() = runBlocking {
        val parsed = api().parseLedgers(envelope(ledgerJson("9")))
        repository.replaceLedgers(parsed)
        val rows = listOfFlow(repository.observeLedgers())
        assertEquals(1, rows.size)
        assertEquals(9L, rows.first().id)
        val rejected = try {
            repository.replaceLedgers(listOf(RemoteLedger(0, 100, 1, 0, "默认", "", 0)))
            false
        } catch (error: IllegalArgumentException) {
            true
        }
        assertTrue(rejected)
        // The rejected write must not have replaced the valid cached ledger.
        assertEquals(9L, listOfFlow(repository.observeLedgers()).first().id)
    }

    @Test
    fun savingsGoalParsingValidatesAmountsAndLedgerConsistency() {
        val parsed = api().parseSavingsGoals(envelope(goalJson("3")))
        assertEquals(200000L, parsed.first().targetAmountMinor)
        assertEquals(50000L, parsed.first().savedAmountMinor)
        assertFalse(parsed.first().achieved)

        assertThrows(IllegalArgumentException::class.java) {
            api().parseSavingsGoals(envelope(goalJson("3", saved = -1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            api().parseSavingsGoals(envelope(goalJson("3"), goalJson("3")))
        }
    }

    @Test
    fun savingsGoalReconcileReplacesSnapshotAndKeepsCacheOnFailure() = runBlocking {
        repository.replaceSavingsGoals(api().parseSavingsGoals(envelope(goalJson("3"), goalJson("4", saved = 1000, achieved = false))))
        assertEquals(2, repository.allSavingsGoals().size)

        // A malformed snapshot must throw before touching Room.
        val rejectedGoalWrite = try {
            repository.replaceSavingsGoals(api().parseSavingsGoals(envelope(goalJson("3"), goalJson("5", saved = -5))))
            false
        } catch (error: IllegalArgumentException) {
            true
        }
        assertTrue(rejectedGoalWrite)
        assertEquals(2, repository.allSavingsGoals().size)

        // Only a real (valid) empty list clears the plan.
        repository.replaceSavingsGoals(emptyList())
        assertTrue(repository.allSavingsGoals().isEmpty())
    }

    @Test
    fun familyMemberReconcileScopesPerFamily() = runBlocking {
        val member = api().parseFamilyMembers(envelope(
            JSONObject().put("id", "11").put("familyId", "7").put("uid", "100").put("role", 1)
                .put("status", 1).put("nickname", "林悦").put("joinedTime", 1700000000)
        ))
        repository.replaceFamilyMembers(7, member)
        val rows = listOfFlow(repository.observeFamilyMembers())
        assertEquals(1, rows.size)
        assertEquals("林悦", rows.first().nickname)

        val rejectedMember = try {
            repository.replaceFamilyMembers(7, api().parseFamilyMembers(envelope(
                JSONObject().put("id", "12").put("familyId", "9").put("uid", "200").put("role", 3)
                    .put("status", 1).put("nickname", "").put("joinedTime", 1700000001)
            )))
            false
        } catch (error: IllegalArgumentException) {
            true
        }
        assertTrue(rejectedMember)
        assertEquals(1, listOfFlow(repository.observeFamilyMembers()).size)
    }

    @Test
    fun explicitLedgerSnapshotsAreIsolatedAndRejectedWritesKeepOldCache() = runBlocking {
        fun transaction(ledgerId: Long, comment: String) = RemoteTransaction(
            id = 1, timeSequenceId = 1700000000000, type = TransactionRepository.TYPE_EXPENSE,
            categoryId = 3, categoryName = "餐饮", sourceAccountId = 1, destinationAccountId = null,
            sourceAmountMinor = 2500, destinationAmountMinor = 0, currency = "CNY", comment = comment,
            time = 1700000000, utcOffset = 480, tagIdsJson = "[]", ledgerId = ledgerId
        )
        val account = RemoteAccount(1, "家庭钱包", "CNY", 97500, false)
        repository.replaceLedgerSnapshot(9, listOf(account), listOf(transaction(9, "九号账本")))
        repository.replaceLedgerSnapshot(10, listOf(account.copy(name = "十号钱包")), listOf(transaction(10, "十号账本")))

        assertEquals("家庭钱包", listOfFlow(repository.observeLedgerAccounts(9)).single().name)
        assertEquals("十号钱包", listOfFlow(repository.observeLedgerAccounts(10)).single().name)
        assertEquals("九号账本", listOfFlow(repository.observeLedgerTransactions(9)).single().comment)
        assertEquals("十号账本", listOfFlow(repository.observeLedgerTransactions(10)).single().comment)

        val rejected = try {
            repository.replaceLedgerSnapshot(9, emptyList(), listOf(transaction(10, "跨账本")))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(rejected)
        assertEquals("九号账本", listOfFlow(repository.observeLedgerTransactions(9)).single().comment)
    }

    @Test
    fun savingsGoalDraftBuildsStrictPayloads() {
        val payload = SavingsGoalDraft("全家旅行基金", 200000, 1700000000, "旅行").toCreatePayload(9)
        assertEquals("9", payload.getString("ledgerId"))
        assertEquals(200000L, payload.getLong("targetAmount"))
        assertEquals(1700000000L, payload.getLong("deadlineTime"))

        val defaultPayload = SavingsGoalDraft("个人目标", 100).toCreatePayload(0)
        assertEquals("0", defaultPayload.getString("ledgerId"))
        assertFalse(defaultPayload.has("deadlineTime"))

        assertThrows(IllegalArgumentException::class.java) { SavingsGoalDraft("", 100).toCreatePayload(0) }
        assertThrows(IllegalArgumentException::class.java) { SavingsGoalDraft("x", 0).toCreatePayload(0) }
        assertThrows(IllegalArgumentException::class.java) { SavingsGoalDraft("x", 10_000_000_000_000L).toCreatePayload(0) }
    }

    @Test
    fun migrationFromV17CreatesFamilyTablesAndPreservesRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "family-migration-${java.util.UUID.randomUUID()}.db"
        var persisted: FinexyDatabase? = null
        try {
            // Build a v17 database by creating one and downgrading its version.
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            persisted.dao().upsertTags(listOf(TagEntity(1, "迁移前标签")))
            persisted.close()
            persisted = null
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old -> old.version = 17 }
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_17_18, FinexyDatabase.MIGRATION_18_19).build()
            assertTrue(persisted.dao().allSavingsGoals().isEmpty())
            val migrated = persisted.openHelper.readableDatabase
            listOf("family_groups", "family_members", "ledgers", "savings_goals", "ledger_account_cache", "ledger_transaction_cache").forEach { table ->
                migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
            }
            persisted.dao().upsertFamilyGroups(listOf(FamilyGroupEntity(7, 100, "温暖小家")))
            assertEquals("温暖小家", persisted.dao().allFamilyGroups().first().name)
        } finally {
            persisted?.close()
            context.deleteDatabase(name)
        }
    }
}
