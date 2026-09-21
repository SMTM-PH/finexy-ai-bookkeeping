package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivacyDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "Test-backup-passphrase-123"

    @Test fun corruptedPinRecordCannotDisableTheLock() {
        val name = "privacy-corrupt-${UUID.randomUUID()}"
        try {
            context.getSharedPreferences(name, 0).edit().putString("app_lock_pin_v1", "corrupted").commit()
            val lock = AppLock(SecureStore(context, name))
            assertTrue(lock.enabled)
            assertThrows(IllegalStateException::class.java) { lock.verify("638195") }
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun encryptedBackupIsRandomizedAndRejectsWrongPasswordOrTampering() {
        val first = PrivacyCrypto.encrypt("private ledger", password)
        val second = PrivacyCrypto.encrypt("private ledger", password)
        assertNotEquals(first, second); assertFalse(first.contains("private ledger"))
        assertEquals("private ledger", PrivacyCrypto.decrypt(first, password))
        assertThrows(IllegalArgumentException::class.java) { PrivacyCrypto.decrypt(first, "Another-wrong-password") }
        val modified = JSONObject(first)
        val bytes = PrivacyCrypto.decode(modified.getString("data")); bytes[0] = (bytes[0].toInt() xor 1).toByte()
        modified.put("data", PrivacyCrypto.encode(bytes))
        assertThrows(IllegalArgumentException::class.java) { PrivacyCrypto.decrypt(modified.toString(), password) }
    }

    @Test fun pinPersistsAndCooldownSurvivesRecreation() {
        val name = "privacy-pin-${UUID.randomUUID()}"
        try {
            val lock = AppLock(SecureStore(context, name))
            assertFalse(lock.enabled)
            assertThrows(IllegalArgumentException::class.java) { lock.enable("123") }
            lock.enable("638195")
            assertTrue(AppLock(SecureStore(context, name)).verify("638195"))
            repeat(5) { assertFalse(lock.verify("111111", 100_000L)) }
            val reopened = AppLock(SecureStore(context, name))
            assertThrows(IllegalStateException::class.java) { reopened.verify("638195", 100_001L) }
            assertTrue(reopened.verify("638195", 131_000L))
            assertThrows(IllegalArgumentException::class.java) { reopened.disable("111111") }
            reopened.disable("638195"); assertFalse(reopened.enabled)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun loggedOutSessionCannotBeRestoredByAnOldRefresh() {
        val name = "privacy-session-${UUID.randomUUID()}"
        try {
            val store = SecureStore(context, name)
            store.put(FinexyApi.KEY_TOKEN, "old", durable = true)
            assertTrue(store.replaceSession("old", null))
            assertFalse(store.replaceSession("old", "new"))
            assertNull(store.get(FinexyApi.KEY_TOKEN))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun backupRoundTripPreservesFieldsAndDoesNotOverwriteOrDuplicate() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val row = TransactionEntity("backup-local", serverId = 123, type = 3, sourceAccountId = 10, categoryId = 20,
                categoryName = "旅行", sourceAmountMinor = 12345, comment = "原始", time = 1_788_000_000_000,
                tagIdsJson = "[\"9223372036854775806\"]", pictureIdsJson = "[\"2\"]", geoLocationJson = "{\"latitude\":12,\"longitude\":34}", hideAmount = true, syncedSnapshotJson = "baseline", reviewItemId = 77)
            source.dao().upsertTransaction(row)
            source.dao().upsertAIReviewItems(listOf(AIReviewItemEntity(77, 1, 1, "旅行 123.45 元", "", "", 100)))
            source.dao().upsertProductAsset(ProductAssetEntity(88, category = 3, status = 1, name = "旅行电脑",
                purchaseAmountMinor = 800000, purchaseTime = 1_788_000_000_000, utcOffset = 480,
                usefulLifeDays = 1825, residualAmountMinor = 0, bookValueMinor = 760000))
            source.dao().upsertExchangeRates(listOf(
                ExchangeRateEntity("CNY", "1", "CNY", "user_custom", "", 1_788_000_000, 1_788_000_000_000),
                ExchangeRateEntity("USD", "0.13876543", "CNY", "user_custom", "", 1_788_000_000, 1_788_000_000_000)
            ))
            source.dao().upsertFamilyGroups(listOf(FamilyGroupEntity(7, 100, "温暖小家", "共享账本", 2, 1700000000)))
            source.dao().upsertFamilyMembers(listOf(FamilyMemberEntity(11, 7, 100, 1, 1, "林悦", 1700000000)))
            source.dao().upsertLedgers(listOf(LedgerEntity(9, 100, 2, 7, "家庭账本", "", 1700000000)))
            source.dao().upsertSavingsGoals(listOf(SavingsGoalEntity(5, 100, 9, "全家旅行基金", 200000, 105000, false, 0, "")))
            source.dao().upsertAccounts(listOf(AccountEntity(10, "美元账户", "USD")))
            source.dao().upsertAccountMapping(AccountMappingEntity(TransactionEntity.LOCAL_ACCOUNT_ID, 10))
            source.dao().upsertConflict(SyncConflictEntity(row.localId, 123, "原始", 12345, "remote", 23456))
            val from = LedgerBackup(source, "scope-a"); val to = LedgerBackup(target, "scope-a")
            val encrypted = from.export(password)
            val preview = to.preview(encrypted, password)
            assertEquals(14, preview.getInt("schema"))
            assertEquals(1, to.restore(preview)); assertEquals(row, target.dao().findTransaction(row.localId))
            assertEquals(77L, target.dao().findAIReviewItem(77)!!.id)
            assertEquals("旅行电脑", target.dao().allProductAssets().single().name)
            assertEquals("0.13876543", target.dao().allExchangeRates().first { it.currency == "USD" }.rate)
            assertEquals("温暖小家", target.dao().observeFamilyGroups().first().first().name)
            assertEquals("林悦", target.dao().allFamilyMembers().first().nickname)
            assertEquals("家庭账本", target.dao().allLedgers().first().name)
            assertEquals("全家旅行基金", target.dao().allSavingsGoals().first().name)
            assertEquals(10L, target.dao().findAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)!!.serverId)
            target.dao().upsertTransaction(row.copy(comment = "更新"))
            assertEquals(0, to.restore(preview)); assertEquals("更新", target.dao().findTransaction(row.localId)!!.comment)
            val other = LedgerBackup(target, "scope-b")
            var rejected = false
            try { other.preview(encrypted, password) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { source.close(); target.close() }
    }

    @Test fun schemaEightBackupWithoutAccountMappingsStillRestores() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertTransaction(TransactionEntity("old-backup", type = 3, categoryName = "其他",
                sourceAmountMinor = 100, comment = "旧备份", time = 1L))
            val current = JSONObject(PrivacyCrypto.decrypt(LedgerBackup(source, "local").export(password), password))
            current.put("schema", 8)
            listOf("family_groups", "family_members", "ledgers", "savings_goals").forEach { current.getJSONObject("tables").remove(it) }
            current.getJSONObject("tables").remove("account_mappings")
            current.getJSONObject("tables").remove("scheduled_occurrences")
            current.getJSONObject("tables").remove("ai_review_items")
            current.getJSONObject("tables").remove("product_assets")
            current.getJSONObject("tables").remove("exchange_rates")
            current.getJSONObject("tables").getJSONArray("transactions").getJSONObject(0).remove("reviewItemId")
            val preview = LedgerBackup(target, "local").preview(PrivacyCrypto.encrypt(current.toString(), password), password)
            assertEquals(1, LedgerBackup(target, "local").restore(preview))
            assertNotNull(target.dao().findTransaction("old-backup"))
            assertNull(target.dao().findAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID))
        } finally { source.close(); target.close() }
    }

    @Test fun schemaNineBackupWithoutOccurrencesStillRestores() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertTransaction(TransactionEntity("schema9-backup", type = 3, categoryName = "其他",
                sourceAmountMinor = 100, comment = "九版备份", time = 1L))
            val current = JSONObject(PrivacyCrypto.decrypt(LedgerBackup(source, "local").export(password), password))
            current.put("schema", 9)
            listOf("family_groups", "family_members", "ledgers", "savings_goals").forEach { current.getJSONObject("tables").remove(it) }
            current.getJSONObject("tables").remove("scheduled_occurrences")
            current.getJSONObject("tables").remove("ai_review_items")
            current.getJSONObject("tables").remove("product_assets")
            current.getJSONObject("tables").remove("exchange_rates")
            current.getJSONObject("tables").getJSONArray("transactions").getJSONObject(0).remove("reviewItemId")
            val preview = LedgerBackup(target, "local").preview(PrivacyCrypto.encrypt(current.toString(), password), password)
            assertEquals(1, LedgerBackup(target, "local").restore(preview))
            assertNotNull(target.dao().findTransaction("schema9-backup"))
        } finally { source.close(); target.close() }
    }

    @Test fun schemaTenBackupWithoutAIReviewTableStillRestores() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertTransaction(TransactionEntity("schema10-backup", type = 3, categoryName = "其他",
                sourceAmountMinor = 100, comment = "十版备份", time = 1L))
            val current = JSONObject(PrivacyCrypto.decrypt(LedgerBackup(source, "local").export(password), password))
            current.put("schema", 10)
            listOf("family_groups", "family_members", "ledgers", "savings_goals").forEach { current.getJSONObject("tables").remove(it) }
            current.getJSONObject("tables").remove("ai_review_items")
            current.getJSONObject("tables").remove("product_assets")
            current.getJSONObject("tables").remove("exchange_rates")
            current.getJSONObject("tables").getJSONArray("transactions").getJSONObject(0).remove("reviewItemId")
            val preview = LedgerBackup(target, "local").preview(PrivacyCrypto.encrypt(current.toString(), password), password)
            assertEquals(1, LedgerBackup(target, "local").restore(preview))
            assertNotNull(target.dao().findTransaction("schema10-backup"))
        } finally { source.close(); target.close() }
    }

    @Test fun schemaElevenBackupWithoutProductAssetsStillRestores() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertTransaction(TransactionEntity("schema11-backup", type = 3, categoryName = "其他",
                sourceAmountMinor = 100, comment = "十一版备份", time = 1L))
            val current = JSONObject(PrivacyCrypto.decrypt(LedgerBackup(source, "local").export(password), password))
            current.put("schema", 11)
            listOf("family_groups", "family_members", "ledgers", "savings_goals").forEach { current.getJSONObject("tables").remove(it) }
            current.getJSONObject("tables").remove("product_assets")
            current.getJSONObject("tables").remove("exchange_rates")
            val preview = LedgerBackup(target, "local").preview(PrivacyCrypto.encrypt(current.toString(), password), password)
            assertEquals(1, LedgerBackup(target, "local").restore(preview))
            assertNotNull(target.dao().findTransaction("schema11-backup"))
            assertTrue(target.dao().allProductAssets().isEmpty())
        } finally { source.close(); target.close() }
    }

    @Test fun schemaTwelveBackupWithoutExchangeRatesStillRestores() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertProductAsset(ProductAssetEntity(89, category = 2, status = 1, name = "旧版手机",
                purchaseAmountMinor = 300000, purchaseTime = 1_700_000_000_000, utcOffset = 480,
                usefulLifeDays = 1460, residualAmountMinor = 0, bookValueMinor = 200000))
            val current = JSONObject(PrivacyCrypto.decrypt(LedgerBackup(source, "local").export(password), password))
            current.put("schema", 12)
            current.getJSONObject("tables").remove("exchange_rates")
            listOf("family_groups", "family_members", "ledgers", "savings_goals").forEach { current.getJSONObject("tables").remove(it) }
            val preview = LedgerBackup(target, "local").preview(PrivacyCrypto.encrypt(current.toString(), password), password)
            assertEquals(0, LedgerBackup(target, "local").restore(preview))
            assertEquals("旧版手机", target.dao().allProductAssets().single().name)
            assertTrue(target.dao().allExchangeRates().isEmpty())
        } finally { source.close(); target.close() }
    }

    @Test fun malformedRestoreIsAtomicAndClearAffectsOnlyExplicitDatabase() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val row = TransactionEntity("one", type = 3, categoryName = "其他", sourceAmountMinor = 100, comment = "保留", time = 1L)
            source.dao().upsertTransaction(row); target.dao().upsertTransaction(row)
            val service = LedgerBackup(source, "local")
            val doc = service.preview(service.export(password), password)
            doc.getJSONObject("tables").getJSONArray("transactions").getJSONObject(0).put("type", "bad")
            var rejected = false
            try { LedgerBackup(target, "local").restore(doc) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected); assertEquals(row, target.dao().findTransaction("one"))
            try { service.clearLocal("wrong") } catch (_: IllegalArgumentException) { }
            assertEquals(1, source.dao().allTransactions().size)
            service.clearLocal("清空当前账本")
            assertTrue(source.dao().allTransactions().isEmpty()); assertEquals(1, target.dao().allTransactions().size)
        } finally { source.close(); target.close() }
    }
}
