package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite and Android JSON, isolated from the user's on-device database. */
@RunWith(AndroidJUnit4::class)
class TransactionConsistencyTest {
    private lateinit var database: FinexyDatabase
    private lateinit var repository: TransactionRepository
    private val timeSeconds = 1_788_422_000L

    @Before fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        repository = TransactionRepository(context, database)
        database.dao().upsertAccounts(listOf(AccountEntity(10, "测试账户", "USD"), AccountEntity(11, "转入账户", "CNY")))
        database.dao().upsertCategories(listOf(
            CategoryEntity(1, "支出根", 0, 2), CategoryEntity(2, "收入根", 0, 1), CategoryEntity(3, "转账根", 0, 3),
            CategoryEntity(20, "餐饮", 1, 2), CategoryEntity(21, "交通", 1, 2), CategoryEntity(22, "工资", 2, 1), CategoryEntity(23, "账户互转", 3, 3)
        ))
    }

    @After fun tearDown() { database.close() }

    @Test fun scheduledRefreshRemovesStaleRowsWithoutRemovingNormalTemplates() = runBlocking {
        database.dao().upsertTemplates(listOf(
            TemplateEntity(1, "普通", TYPE_EXPENSE, 20, 10, 100, ""),
            TemplateEntity(2, "旧计划", TYPE_EXPENSE, 20, 10, 100, "", templateType = 2)
        ))
        repository.replaceScheduledTemplates(listOf(RemoteTemplate(3, "新计划", TYPE_EXPENSE, 20, 10, 200, "", "[]", true,
            templateType = 2, scheduledFrequencyType = 2, scheduledFrequency = "-1", utcOffset = 480)))
        assertEquals(listOf(1L), repository.observeTemplates().first().map { it.id })
        assertEquals(listOf(3L), repository.observeScheduledTemplates().first().map { it.id })
        assertEquals("-1", repository.observeScheduledTemplates().first().single().scheduledFrequency)
        repository.replaceScheduledTemplates(emptyList())
        assertTrue(repository.observeScheduledTemplates().first().isEmpty())
        assertEquals(1L, repository.observeTemplates().first().single().id)
    }

    private fun remote(comment: String = "原始描述", amount: Long = 1234) = RemoteTransaction(
        id = 100, timeSequenceId = timeSeconds * 1000 + 7, type = 3, categoryId = 20,
        categoryName = "餐饮", sourceAccountId = 10, destinationAccountId = null,
        sourceAmountMinor = amount, destinationAmountMinor = 0, currency = "USD", comment = comment,
        time = timeSeconds, utcOffset = -240, tagIdsJson = "[\"3840572843385421824\"]",
        pictureIdsJson = "[\"91\"]", geoLocationJson = "{\"longitude\":116.3,\"latitude\":39.9}", hideAmount = true
    )

    private fun TransactionEntity.draft(comment: String = this.comment, categoryId: Long? = this.categoryId,
        category: String = categoryName, tags: String = tagIdsJson) = TransactionDraft(
        localId, type, sourceAccountId, categoryId, category, sourceAmountMinor, comment, tags
    )

    @Test fun transferAndBalanceAdjustmentPersistStructuredFieldsAndPayload() = runBlocking {
        repository.save(TransactionDraft("transfer", TransactionRepository.TYPE_TRANSFER, 10, 23, "账户互转", 1234, "换汇", "[]", 11, 6789))
        val transfer = database.dao().findTransaction("transfer")!!
        assertEquals(11L, transfer.destinationAccountId)
        assertEquals(6789L, transfer.destinationAmountMinor)
        assertEquals("11", transfer.toApiPayload().getString("destinationAccountId"))
        assertEquals(6789L, transfer.toApiPayload().getLong("destinationAmount"))

        repository.save(TransactionDraft("balance", TransactionRepository.TYPE_MODIFY_BALANCE, 10, null, "余额调整", -5000, "盘点", "[]"))
        val balance = database.dao().findTransaction("balance")!!
        assertNull(balance.categoryId)
        assertNull(balance.destinationAccountId)
        assertEquals(-5000L, balance.sourceAmountMinor)
        assertEquals("0", balance.toApiPayload().getString("categoryId"))
        assertEquals(0L, balance.toApiPayload().getLong("destinationAmount"))
    }

    @Test fun transferRejectsSameOrHiddenDestinationAccount() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.save(TransactionDraft("same", 4, 10, 23, "账户互转", 100, "", "[]", 10, 100)) } }
        database.dao().upsertAccounts(listOf(AccountEntity(12, "停用", "CNY", hidden = true)))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.save(TransactionDraft("hidden", 4, 10, 23, "账户互转", 100, "", "[]", 12, 100)) } }
        Unit
    }

    @Test fun replacingMultiAccountGroupRemovesDeletedChildLocally() = runBlocking {
        repository.saveAccounts(listOf(
            AccountEntity(30, "父账户", "---", type = 2),
            AccountEntity(31, "保留", "USD", parentId = 30),
            AccountEntity(32, "删除", "EUR", parentId = 30)
        ))
        repository.replaceAccountGroup(30, listOf(
            RemoteAccount(30, "父账户", "---", 0, false, type = 2),
            RemoteAccount(31, "保留", "USD", 0, false, parentId = 30)
        ))
        assertNotNull(database.dao().findAccount(31))
        assertNull(database.dao().findAccount(32))
    }

    @Test fun editPreservesDateAndHiddenFieldsAndUpdatesCategoryAndTags() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        val before = database.dao().findTransaction("server-100")!!
        repository.save(before.draft("修改描述", 21, "交通", "[\"92\"]"))
        val after = database.dao().findTransaction(before.localId)!!
        assertEquals(21L, after.categoryId)
        assertEquals("交通", after.categoryName)
        assertEquals("[\"92\"]", after.tagIdsJson)
        assertEquals(before.time, after.time)
        assertEquals(before.serverId, after.serverId)
        assertEquals(before.timeSequenceId, after.timeSequenceId)
        assertEquals(before.utcOffset, after.utcOffset)
        assertEquals(before.currency, after.currency)
        assertEquals(before.pictureIdsJson, after.pictureIdsJson)
        assertEquals(before.geoLocationJson, after.geoLocationJson)
        assertTrue(after.hideAmount)
        assertEquals(before.syncedSnapshotJson, after.syncedSnapshotJson)
    }

    @Test fun apiRoundTripUsesSecondsAndStringIdsWithoutPrecisionLoss() {
        val entity = remote().toEntity()
        assertEquals(timeSeconds * 1000, entity.time)
        val payload = entity.toApiPayload()
        assertEquals(timeSeconds, payload.getLong("time"))
        assertTrue(payload.getJSONArray("tagIds").get(0) is String)
        assertEquals("3840572843385421824", payload.getJSONArray("tagIds").getString(0))
        assertEquals(entity, RemoteTransaction.from(JSONObject(remote().toJson())).toEntity().copy(updatedAt = entity.updatedAt))
    }

    @Test fun localOnlyChangeAndDeletionSurvivePullWithoutConflict() = runBlocking {
        val server = remote()
        repository.mergeRemote(listOf(server))
        val original = database.dao().findTransaction("server-100")!!
        repository.save(original.draft("仅本地修改"))
        assertEquals(0, repository.mergeRemote(listOf(server)).conflicts)
        assertEquals("仅本地修改", repository.pendingTransactions().single().comment)
        repository.markDeleted(original.localId)
        repository.mergeRemote(listOf(server))
        assertTrue(repository.pendingTransactions().single().deleted)
    }

    @Test fun completePullMarksMissingSyncedTransactionAsRemotelyDeleted() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        val result = repository.reconcileRemoteAbsence(emptySet())
        val local = database.dao().findTransaction("server-100")!!
        assertEquals(1, result.removed)
        assertTrue(local.deleted)
        assertEquals(SyncState.SYNCED, local.syncState)
    }

    @Test fun remoteDeleteConflictsWithLocalEditAndBothResolutionsAreLossless() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        val original = database.dao().findTransaction("server-100")!!
        repository.save(original.draft("本机保留"))
        val result = repository.reconcileRemoteAbsence(emptySet())
        assertEquals(1, result.conflicts)
        assertEquals(TransactionRepository.REMOTE_DELETION_MARKER, database.dao().findConflict(original.localId)!!.remoteEntityJson)

        repository.resolveConflict(original.localId, keepRemote = false)
        val recreate = repository.pendingTransactions().single()
        assertEquals("本机保留", recreate.comment)
        assertNotEquals(original.localId, recreate.localId)
        assertNull(recreate.serverId)
        assertEquals(SyncState.PENDING, recreate.syncState)

        repository.markSynced(recreate, remote("本机保留").copy(id = 101))
        repository.save(database.dao().findTransaction(recreate.localId)!!.draft("再次编辑"))
        repository.reconcileRemoteAbsence(emptySet())
        repository.resolveConflict(recreate.localId, keepRemote = true)
        val deleted = database.dao().findTransaction(recreate.localId)!!
        assertTrue(deleted.deleted)
        assertEquals(SyncState.SYNCED, deleted.syncState)
        assertNull(database.dao().findConflict(recreate.localId))
    }

    @Test fun matchingRemoteDeleteAcknowledgesLocalDeleteWithoutConflict() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        repository.markDeleted("server-100")
        val result = repository.reconcileRemoteAbsence(emptySet())
        assertEquals(0, result.conflicts)
        assertTrue(repository.pendingTransactions().isEmpty())
        assertEquals(SyncState.SYNCED, database.dao().findTransaction("server-100")!!.syncState)
    }

    @Test fun keepLocalIsNotOverwrittenByTheNextPull() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        val original = database.dao().findTransaction("server-100")!!
        repository.save(original.draft("我的修改"))
        val changedServer = remote("远端修改", 9999)
        assertEquals(1, repository.mergeRemote(listOf(changedServer)).conflicts)
        assertTrue(repository.pendingTransactions().isEmpty())
        repository.resolveConflict(original.localId, false)
        repository.mergeRemote(listOf(changedServer))
        val pending = repository.pendingTransactions().single()
        assertEquals("我的修改", pending.comment)
        assertEquals(1234L, pending.sourceAmountMinor)
    }

    @Test fun useServerRestoresFieldsBeyondDescriptionAndAmount() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        val original = database.dao().findTransaction("server-100")!!
        repository.save(original.draft("本地"))
        val changed = remote("远端").copy(sourceAccountId = 33, categoryId = 21, time = timeSeconds + 3600,
            utcOffset = 60, tagIdsJson = "[\"95\"]", pictureIdsJson = "[\"96\"]", hideAmount = false)
        repository.mergeRemote(listOf(changed))
        repository.resolveConflict(original.localId, true)
        val actual = database.dao().findTransaction(original.localId)!!
        val expected = changed.toEntity(original.localId)
        assertEquals(expected.copy(updatedAt = actual.updatedAt), actual)
        assertNull(database.dao().findConflict(original.localId))
    }

    @Test fun createAcknowledgementLinksServerIdAndPullDoesNotDuplicate() = runBlocking {
        val draft = TransactionDraft("created-local", 3, 10, 20, "餐饮", 1234, "新增", "[]")
        repository.save(draft)
        val uploaded = repository.pendingTransactions().single()
        val response = remote("新增").copy(time = uploaded.time / 1000, tagIdsJson = "[]")
        repository.markSynced(uploaded, response)
        repository.mergeRemote(listOf(response))
        val all = database.dao().allTransactions()
        assertEquals(1, all.size)
        assertEquals("created-local", all.single().localId)
        assertEquals(100L, all.single().serverId)
    }

    @Test fun acknowledgementDoesNotLoseAnEditMadeDuringTheRequest() = runBlocking {
        repository.mergeRemote(listOf(remote()))
        val before = database.dao().findTransaction("server-100")!!
        repository.save(before.draft("上传中的版本"))
        val uploaded = repository.pendingTransactions().single()
        repository.save(uploaded.draft("发送后又修改"))
        repository.markSynced(uploaded, remote("上传中的版本"))
        val latest = repository.pendingTransactions().single()
        assertEquals("发送后又修改", latest.comment)
        assertEquals(uploaded.syncSnapshot(), latest.syncedSnapshotJson)
    }

    @Test fun localWalletIsNeverSilentlyAssignedToAVisibleRemoteAccount() = runBlocking {
        repository.save(TransactionDraft("local", 3, -1, null, "餐饮", 100, "本地", "[]"))
        assertEquals(-1L, repository.pendingTransactions().single().sourceAccountId)
    }

    @Test fun wrongCategoryTypeDoesNotGenerateAnInvalidServerPayload() = runBlocking {
        repository.save(TransactionDraft("local", 3, 10, 22, "工资", 100, "支出", "[]"))
        assertNull(repository.pendingTransactions().single().categoryId)
    }

    @Test fun explicitCategoryMappingsAreTypeScopedAndBackfillWaitingRows() = runBlocking {
        repository.save(TransactionDraft("income-other", TYPE_INCOME, 10, null, "其他", 100, "收入", "[]"))
        repository.save(TransactionDraft("expense-other", TYPE_EXPENSE, 10, null, "其他", 200, "支出", "[]"))

        repository.mapCategory("其他", TYPE_INCOME, 22)
        assertEquals(22L, database.dao().findTransaction("income-other")!!.categoryId)
        assertNull(database.dao().findTransaction("expense-other")!!.categoryId)

        repository.mapCategory("其他", TYPE_EXPENSE, 20)
        assertEquals(20L, database.dao().findTransaction("expense-other")!!.categoryId)
        assertEquals(2, repository.observeCategoryMappings().first().size)

        repository.clearCategoryMapping("其他", TYPE_INCOME)
        assertNull(database.dao().findTransaction("income-other")!!.categoryId)
        assertEquals(20L, database.dao().findTransaction("expense-other")!!.categoryId)
    }

    @Test fun mappingRejectsWrongTypeRootAndHiddenCategories() = runBlocking {
        database.dao().upsertCategories(listOf(
            CategoryEntity(30, "收入根", 0, 1),
            CategoryEntity(31, "隐藏支出", 1, 2, hidden = true)
        ))
        listOf(20L, 30L, 31L).forEach { serverId ->
            try {
                repository.mapCategory("工资", TYPE_INCOME, serverId)
                fail("Expected invalid mapping $serverId to fail")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("同类型"))
            }
        }
    }

    @Test fun hiddenParentMakesItsChildrenUnavailable() = runBlocking {
        database.dao().upsertCategories(listOf(CategoryEntity(1, "支出根", 0, 2, hidden = true)))

        repository.save(TransactionDraft("hidden-parent", TYPE_EXPENSE, 10, 20, "餐饮", 100, "支出", "[]"))
        assertNull(database.dao().findTransaction("hidden-parent")!!.categoryId)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.mapCategory("餐饮", TYPE_EXPENSE, 20) }
        }
        Unit
    }

    @Test fun deletingUnsyncedLocalTransactionDoesNotEnqueueAnImpossibleServerDelete() = runBlocking {
        repository.save(TransactionDraft("local", 3, -1, null, "其他", 100, "", "[]"))
        repository.markDeleted("local")
        assertTrue(repository.pendingTransactions().isEmpty())
        assertTrue(database.dao().findTransaction("local")!!.deleted)
    }

    @Test fun paginationCollectsMoreThanFiftyAndStopsAtTheServerCursor() = runBlocking {
        val requested = mutableListOf<Long>()
        val raw = collectTransactionPages { cursor ->
            requested.add(cursor)
            val page = JSONArray()
            val range = if (cursor == 0L) 1..50 else 51..75
            range.forEach { page.put(JSONObject().put("id", it.toString())) }
            JSONObject().put("result", JSONObject().put("items", page)
                .put("nextTimeSequenceId", if (cursor == 0L) "500" else JSONObject.NULL)).toString()
        }
        assertEquals(listOf(0L, 500L), requested)
        assertEquals(75, JSONObject(raw).getJSONObject("result").getJSONArray("items").length())
    }

    @Test fun repeatedPaginationCursorFailsInsteadOfClaimingACompleteSync() = runBlocking {
        try {
            collectTransactionPages { JSONObject().put("result", JSONObject().put("items", JSONArray()).put("nextTimeSequenceId", "500")).toString() }
            fail("Expected cursor validation failure")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("游标")) }
    }

    @Test fun versionOneMigrationChainPreservesLedgerAndCreatesEveryLaterFeature() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v1-${java.util.UUID.randomUUID()}.db"
        var persisted: FinexyDatabase? = null
        try {
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            persisted.dao().upsertTransaction(remote().toEntity("v1-row").copy(serverId = null, time = timeSeconds * 1000))
            persisted.close()
            persisted = null
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE account_mappings")
                old.execSQL("DROP TABLE sync_status")
                old.execSQL("DROP TABLE transaction_templates")
                old.execSQL("DROP TABLE tags")
                old.execSQL("DROP TABLE category_mappings")
                old.execSQL("DROP TABLE sync_conflicts")
                listOf("syncedSnapshotJson", "pictureIdsJson", "geoLocationJson", "hideAmount").forEach {
                    old.execSQL("ALTER TABLE transactions DROP COLUMN $it")
                }
                old.version = 1
            }
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(
                    FinexyDatabase.MIGRATION_1_2, FinexyDatabase.MIGRATION_2_3,
                    FinexyDatabase.MIGRATION_3_4, FinexyDatabase.MIGRATION_4_5,
                    FinexyDatabase.MIGRATION_5_6, FinexyDatabase.MIGRATION_6_7,
                    FinexyDatabase.MIGRATION_7_8, FinexyDatabase.MIGRATION_8_9,
                    FinexyDatabase.MIGRATION_9_10,
                    FinexyDatabase.MIGRATION_10_11,
                    FinexyDatabase.MIGRATION_11_12, FinexyDatabase.MIGRATION_12_13,
                    FinexyDatabase.MIGRATION_13_14
                ).build()
            val row = persisted.dao().findTransaction("v1-row")!!
            assertEquals(1234L, row.sourceAmountMinor)
            assertEquals(timeSeconds * 1000, row.time)
            assertEquals("", row.syncedSnapshotJson)
            assertEquals("[]", row.pictureIdsJson)
            persisted.dao().upsertTags(listOf(TagEntity(1, "迁移后标签")))
            persisted.dao().upsertTemplates(listOf(TemplateEntity(2, "迁移后模板", TYPE_EXPENSE, 3, 4, 500, "模板")))
            persisted.dao().upsertSyncStatus(SyncStatusEntity(message = "迁移完成"))
            persisted.dao().upsertAccountMapping(AccountMappingEntity(TransactionEntity.LOCAL_ACCOUNT_ID, 4))
            assertEquals("迁移完成", persisted.dao().findSyncStatus()!!.message)
            assertEquals(4L, persisted.dao().findAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)!!.serverId)
        } finally {
            persisted?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSixMigrationPreservesRowsAndNormalizesOnlyServerSeconds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        var persisted: FinexyDatabase? = null
        try {
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            persisted.dao().upsertTransactions(listOf(
                remote().toEntity().copy(time = timeSeconds),
                remote().toEntity("local").copy(serverId = null, time = timeSeconds * 1000)
            ))
            persisted.close()
            persisted = null
            // Reconstruct the v6 shape in a test-owned database, then require Room's
            // real migration and schema validation to succeed on reopening it.
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                listOf("syncedSnapshotJson", "pictureIdsJson", "geoLocationJson", "hideAmount").forEach {
                    old.execSQL("ALTER TABLE transactions DROP COLUMN $it")
                }
                old.version = 6
            }
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_6_7, FinexyDatabase.MIGRATION_7_8, FinexyDatabase.MIGRATION_8_9, FinexyDatabase.MIGRATION_9_10, FinexyDatabase.MIGRATION_10_11, FinexyDatabase.MIGRATION_11_12, FinexyDatabase.MIGRATION_12_13,
                    FinexyDatabase.MIGRATION_13_14).build()
            val rows = persisted.dao().allTransactions()
            assertEquals(2, rows.size)
            rows.forEach { assertEquals(timeSeconds * 1000, it.time) }
            assertEquals(1234L, rows.first().sourceAmountMinor)
            assertEquals("[]", rows.first().pictureIdsJson)
        } finally {
            persisted?.close()
            context.deleteDatabase(name)
        }
    }


    @Test fun versionSevenMappingMigrationAddsTransactionTypeWithoutGuessing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mapping-migration-${java.util.UUID.randomUUID()}.db"
        var persisted: FinexyDatabase? = null
        try {
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            persisted.dao().upsertCategories(listOf(
                CategoryEntity(20, "餐饮", 1, 2),
                CategoryEntity(22, "工资", 2, 1),
                CategoryEntity(30, "支出根", 0, 2)
            ))
            persisted.close()
            persisted = null
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE category_mappings")
                old.execSQL("CREATE TABLE category_mappings (localName TEXT NOT NULL PRIMARY KEY, serverId INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                old.execSQL("INSERT INTO category_mappings VALUES ('其他', 20, 1), ('工资', 22, 2), ('无效根', 30, 3), ('缺失', 999, 4)")
                old.version = 7
            }
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_7_8, FinexyDatabase.MIGRATION_8_9, FinexyDatabase.MIGRATION_9_10, FinexyDatabase.MIGRATION_10_11, FinexyDatabase.MIGRATION_11_12, FinexyDatabase.MIGRATION_12_13,
                    FinexyDatabase.MIGRATION_13_14).build()
            val mappings = persisted.dao().observeCategoryMappings().first()
            assertEquals(2, mappings.size)
            assertEquals(TYPE_INCOME, mappings.single { it.localName == "工资" }.transactionType)
            assertEquals(TYPE_EXPENSE, mappings.single { it.localName == "其他" }.transactionType)
        } finally {
            persisted?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionEightMigrationCreatesPersistentSyncStatus() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sync-status-migration-${java.util.UUID.randomUUID()}.db"
        var persisted: FinexyDatabase? = null
        try {
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            persisted.dao().allTransactions()
            persisted.close()
            persisted = null
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE sync_status")
                old.version = 8
            }
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_8_9, FinexyDatabase.MIGRATION_9_10, FinexyDatabase.MIGRATION_10_11, FinexyDatabase.MIGRATION_11_12, FinexyDatabase.MIGRATION_12_13,
                    FinexyDatabase.MIGRATION_13_14).build()
            assertNull(persisted.dao().findSyncStatus())
            persisted.dao().upsertSyncStatus(SyncStatusEntity(state = SyncRunState.RETRYING, message = "等待重试", attemptCount = 2, nextRetryAt = 1234))
            assertEquals(1234L, persisted.dao().findSyncStatus()!!.nextRetryAt)
        } finally {
            persisted?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun explicitAccountMappingBackfillsPendingRowsAndControlsFutureSaves() = runBlocking {
        database.dao().upsertAccounts(listOf(
            AccountEntity(10, "工资卡", "CNY"),
            AccountEntity(11, "隐藏账户", "USD", hidden = true)
        ))
        repository.save(TransactionDraft("legacy-wallet", TYPE_EXPENSE, TransactionEntity.LOCAL_ACCOUNT_ID,
            null, "其他", 100, "旧流水", "[]"))
        database.dao().upsertTransaction(TransactionEntity("already-synced", serverId = 99, type = TYPE_EXPENSE,
            sourceAccountId = TransactionEntity.LOCAL_ACCOUNT_ID, categoryName = "其他", sourceAmountMinor = 200,
            comment = "服务端历史", time = 1, syncState = SyncState.SYNCED))

        assertEquals(1, repository.mapAccount(TransactionEntity.LOCAL_ACCOUNT_ID, 10))
        assertEquals(10L, database.dao().findTransaction("legacy-wallet")!!.sourceAccountId)
        assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, database.dao().findTransaction("already-synced")!!.sourceAccountId)
        repository.save(TransactionDraft("future", TYPE_EXPENSE, TransactionEntity.LOCAL_ACCOUNT_ID,
            null, "其他", 300, "后续流水", "[]"))
        assertEquals(10L, database.dao().findTransaction("future")!!.sourceAccountId)

        repository.clearAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)
        repository.save(TransactionDraft("after-clear", TYPE_EXPENSE, TransactionEntity.LOCAL_ACCOUNT_ID,
            null, "其他", 400, "清除后", "[]"))
        assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, database.dao().findTransaction("after-clear")!!.sourceAccountId)
        try {
            repository.mapAccount(TransactionEntity.LOCAL_ACCOUNT_ID, 11)
            fail("Expected hidden account mapping to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("可用"))
        }
    }

    @Test fun versionNineMigrationCreatesExplicitAccountMappings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "account-mapping-migration-${java.util.UUID.randomUUID()}.db"
        var persisted: FinexyDatabase? = null
        try {
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            persisted.dao().allTransactions()
            persisted.close()
            persisted = null
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE account_mappings")
                old.version = 9
            }
            persisted = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_9_10, FinexyDatabase.MIGRATION_10_11, FinexyDatabase.MIGRATION_11_12, FinexyDatabase.MIGRATION_12_13,
                    FinexyDatabase.MIGRATION_13_14).build()
            persisted.dao().upsertAccountMapping(AccountMappingEntity(TransactionEntity.LOCAL_ACCOUNT_ID, 10))
            assertEquals(10L, persisted.dao().findAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)!!.serverId)
        } finally {
            persisted?.close()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val TYPE_INCOME = TransactionRepository.TYPE_INCOME
        const val TYPE_EXPENSE = TransactionRepository.TYPE_EXPENSE
    }
}
