package com.finexy.mobile.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Single source of truth for local bookkeeping data.
 *
 * The legacy JSON preferences are imported once. The original values are not
 * removed until the Room copy has been written successfully.
 */
class TransactionRepository(context: Context, private val database: FinexyDatabase = FinexyDatabase.get(context.applicationContext), private val importLegacy: Boolean = true) : LedgerWriteStore, PeriodicActionStore, TemplateCreationStore {
    private val appContext = context.applicationContext
    private val store = SecureStore(appContext)
    private val dao = database.dao()
    val aiReview = AIReviewPersistence(database)

    fun observeTransactions(): Flow<List<TransactionEntity>> = dao.observeTransactions()
    /** Read-only pending inspection includes unsynchronized deletion intents hidden by the UI Flow. */
    suspend fun ledgerPendingTransactions(): List<TransactionEntity> = dao.allTransactions()
    fun observeAccounts(): Flow<List<AccountEntity>> = dao.observeAccounts()
    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeCategories()
    fun observeConflicts(): Flow<List<SyncConflictEntity>> = dao.observeConflicts()
    fun observeCategoryMappings(): Flow<List<CategoryMappingEntity>> = dao.observeCategoryMappings()
    fun observeAccountMappings(): Flow<List<AccountMappingEntity>> = dao.observeAccountMappings()
    fun observeTags(): Flow<List<TagEntity>> = dao.observeTags()
    fun observeTemplates(ledgerId: Long = 0): Flow<List<TemplateEntity>> = dao.observeTemplates(ledgerId)
    fun observeNormalTemplates(ledgerId: Long): Flow<List<TemplateEntity>> = dao.observeNormalTemplates(ledgerId)

    fun observeTemplateDraft(ledgerId: Long, templateType: Int): Flow<TemplateFormDraftEntity?> = dao.observeTemplateDraft(ledgerId, templateType)
    fun observeTemplateCreations(): Flow<List<TemplateCreationEntity>> = dao.observeTemplateCreations()
    override suspend fun templateCreations(): List<TemplateCreationEntity> = dao.allTemplateCreations()
    suspend fun templateDraft(ledgerId: Long, templateType: Int): TemplateFormDraftEntity? = dao.findTemplateDraft(ledgerId, templateType)

    /** Compare the editor revision so an older asynchronous save cannot replace newer input. */
    suspend fun saveTemplateDraft(ledgerId: Long, templateType: Int, editingId: Long, formJson: String,
        expected: TemplateFormDraftEntity? = null): TemplateFormDraftEntity = database.withTransaction {
        val current = dao.findTemplateDraft(ledgerId, templateType)
        require(current == expected) { "模板草稿已更新，请重新打开" }
        require(expected == null || expected.ledgerId == ledgerId && expected.templateType == templateType && expected.editingId == editingId)
        val draft = TemplateFormDraftEntity(ledgerId, templateType, current?.draftId ?: java.util.UUID.randomUUID().toString(),
            Math.addExact(current?.revision ?: 0, 1), editingId, formJson)
        validateTemplateDraft(draft)
        dao.upsertTemplateDraft(draft)
        draft
    }

    suspend fun discardTemplateDraft(draft: TemplateFormDraftEntity): Boolean = database.withTransaction {
        val write = dao.findTemplateCreation(draft.ledgerId, draft.templateType)
        require(write == null || write.draftId != draft.draftId) { "已确认请求不能丢弃，请先核对原操作结果" }
        dao.deleteTemplateDraft(draft.ledgerId, draft.templateType, draft.draftId, draft.revision) > 0
    }

    /** A confirmed request is immutable until its receipt and a complete scoped refresh are durable. */
    suspend fun enqueueTemplateCreation(draft: TemplateFormDraftEntity, payloadJson: String): TemplateCreationEntity = database.withTransaction {
        validateTemplateDraft(draft)
        require(draft.editingId == 0L) { "创建队列不接受修改操作" }
        require(dao.findTemplateDraft(draft.ledgerId, draft.templateType) == draft) { "请先保存最新模板草稿" }
        validateTemplateCreationPayload(payloadJson, draft.ledgerId, draft.templateType)
        val current = dao.findTemplateCreation(draft.ledgerId, draft.templateType)
        if (current != null) {
            require(current.draftId == draft.draftId && current.draftRevision == draft.revision && current.payloadJson == payloadJson) { "原模板创建结果尚待确认，请重试原请求" }
            return@withTransaction current
        }
        val write = TemplateCreationEntity(draft.ledgerId, draft.templateType, java.util.UUID.randomUUID().toString(), draft.draftId, draft.revision, payloadJson)
        validateTemplateCreation(write)
        dao.insertTemplateCreation(write)
        write
    }

    override suspend fun markTemplateCreationPosted(write: TemplateCreationEntity, receipt: TemplateCreationReceipt): Boolean = database.withTransaction {
        require(receipt.id > 0 && receipt.ledgerId == write.ledgerId && receipt.templateType == write.templateType) { "模板回执范围错误" }
        val current = dao.findTemplateCreation(write.ledgerId, write.templateType) ?: return@withTransaction false
        if (!current.sameRequest(write)) return@withTransaction false
        if (current.state == TemplateCreationEntity.POSTED) return@withTransaction current.serverId == receipt.id && current.serverDeleted == receipt.deleted
        dao.markTemplateCreationPosted(write.ledgerId, write.templateType, write.clientSessionId, receipt.id, receipt.deleted)
        true
    }

    override suspend fun markTemplateCreationFailed(write: TemplateCreationEntity, message: String) = database.withTransaction {
        val current = dao.findTemplateCreation(write.ledgerId, write.templateType)
        if (current != null && current.sameRequest(write)) dao.markTemplateCreationFailed(write.ledgerId, write.templateType, write.clientSessionId, message.take(2000))
    }

    private fun TemplateCreationEntity.sameRequest(other: TemplateCreationEntity): Boolean =
        copy(state = TemplateCreationEntity.PENDING, serverId = null, serverDeleted = false, lastError = "") ==
            other.copy(state = TemplateCreationEntity.PENDING, serverId = null, serverDeleted = false, lastError = "")

    fun observeScheduledTemplates(ledgerId: Long = 0): Flow<List<TemplateEntity>> = dao.observeScheduledTemplates(ledgerId)

    fun observeOccurrences(ledgerId: Long = 0): Flow<List<ScheduledOccurrenceEntity>> = dao.observeOccurrences(ledgerId)

    fun observeAIReviewItems(): Flow<List<AIReviewItemEntity>> = dao.observeAIReviewItems()
    fun observeProductAssets(): Flow<List<ProductAssetEntity>> = dao.observeProductAssets()
    fun observeExchangeRates(): Flow<List<ExchangeRateEntity>> = dao.observeExchangeRates()
    fun observeLedgerWrites(): Flow<List<LedgerWriteEntity>> = dao.observeLedgerWrites()
    override suspend fun ledgerWrites(): List<LedgerWriteEntity> = dao.allLedgerWrites()

    /** Save the user-confirmed immutable snapshot before issuing a create request. */
    suspend fun enqueueLedgerWrite(ledgerId: Long, transaction: TransactionEntity): LedgerWriteEntity = database.withTransaction {
        require(ledgerId > 0 && transaction.serverId == null && transaction.localId.isNotBlank()) { "写入账本或草稿无效" }
        require(dao.allLedgers().any { it.id == ledgerId }) { "账本已不可访问，请重新同步" }
        require(dao.findLedgerWrite(ledgerId) == null) { "此账本还有写入结果待确认，请先重试原记录" }
        val payload = transaction.toApiPayload().put("ledgerId", ledgerId.toString()).toString()
        LedgerWriteEntity(ledgerId, transaction.localId, payload).also { dao.insertLedgerWrite(it) }
    }

    override suspend fun markLedgerWritePosted(write: LedgerWriteEntity, serverId: Long) = database.withTransaction {
        require(serverId > 0) { "保存响应缺少流水 ID" }
        val current = dao.findLedgerWrite(write.ledgerId) ?: error("原写入记录已不存在")
        require(current.localId == write.localId && current.payloadJson == write.payloadJson) { "写入快照已改变" }
        dao.markLedgerWritePosted(write.ledgerId, write.localId, serverId)
    }

    override suspend fun markLedgerWriteFailed(write: LedgerWriteEntity, message: String) {
        dao.markLedgerWriteFailed(write.ledgerId, write.localId, message)
    }

    /** Original-target recovery never reads or rewrites a different queue or financial row. */
    internal fun observeOriginalTransaction(localId: String): Flow<TransactionEntity?> = dao.observeOriginalTransaction(localId)
    internal suspend fun originalTransaction(localId: String): TransactionEntity? = dao.findTransaction(localId)
    internal suspend fun originalConflict(localId: String): SyncConflictEntity? = dao.findConflict(localId)
    internal suspend fun originalLedgerWrite(ledgerId: Long): LedgerWriteEntity? = dao.findLedgerWrite(ledgerId)

    internal suspend fun saveOriginalTransaction(expected: TransactionEntity, draft: TransactionDraft,
        current: () -> Boolean): TransactionEntity = database.withTransaction {
        requireOrdinaryOriginalEditor(expected)
        require(draft.localId == expected.localId && draft.reviewItemId == null)
        require(draft.type == expected.type) { "恢复编辑必须保留原流水种类" }
        check(current() && dao.findTransaction(expected.localId) == expected && dao.findConflict(expected.localId) == null) {
            "原流水已变化，请返回重新核验"
        }
        // The existing write path retains serverId, original time, pictures, location and baseline.
        save(draft)
        val saved = requireNotNull(dao.findTransaction(expected.localId))
        val sideIds = listOf(saved.sourceAccountId, saved.destinationAccountId ?: 0L).filter { it > 0L }
        sideIds.forEach { id ->
            val account = requireNotNull(dao.findAccount(id))
            require(!account.hidden && account.type == 1 && (account.parentId == 0L ||
                dao.findAccount(account.parentId)?.hidden == false)) { "原账户或父账户已不可用" }
        }
        require(draft.type == TYPE_MODIFY_BALANCE || draft.categoryId == null || saved.categoryId == draft.categoryId) {
            "原分类已不可用，请明确核验后保存；不自动改成其他分类"
        }
        saved.categoryId?.let { id ->
            val category = requireNotNull(dao.findCategory(id))
            val parent = requireNotNull(dao.findCategory(category.parentId))
            require(!category.hidden && !parent.hidden && parent.parentId == 0L && parent.type == category.type &&
                category.type == when (saved.type) { TYPE_INCOME -> 1; TYPE_EXPENSE -> 2; TYPE_TRANSFER -> 3; else -> 0 }) {
                "原分类的类型或父级已变化"
            }
        }
        check(saved.localId == expected.localId && saved.serverId == expected.serverId && saved.timeSequenceId == expected.timeSequenceId && saved.time == expected.time &&
            saved.utcOffset == expected.utcOffset && saved.pictureIdsJson == expected.pictureIdsJson && saved.geoLocationJson == expected.geoLocationJson)
        check(current()) { "原登录归属已变化，修改未提交" }
        saved
    }

    internal suspend fun markOriginalLedgerWritePosted(expected: LedgerWriteEntity, serverId: Long): LedgerWriteEntity? = database.withTransaction {
        require(serverId > 0)
        val row = dao.findLedgerWrite(expected.ledgerId) ?: return@withTransaction null
        val command = pendingLedgerWriteRecord(expected).target
        if (pendingLedgerWriteRecord(row).target != command) return@withTransaction null
        if (row.state == LedgerWriteEntity.POSTED) return@withTransaction row.takeIf { it.serverId == serverId }
        if (row != expected || row.state != LedgerWriteEntity.PENDING) return@withTransaction null
        dao.markLedgerWritePosted(expected.ledgerId, expected.localId, serverId)
        dao.findLedgerWrite(expected.ledgerId)
    }

    internal suspend fun failOriginalLedgerWrite(expected: LedgerWriteEntity) = database.withTransaction {
        val row = dao.findLedgerWrite(expected.ledgerId)
        if (row != null && row.state == LedgerWriteEntity.PENDING && pendingLedgerWriteRecord(row).target == pendingLedgerWriteRecord(expected).target)
            dao.markLedgerWriteFailed(row.ledgerId, row.localId, "原请求结果仍待核验，请重试原请求；不会替代新增。")
    }

    internal suspend fun acknowledgeOriginalLedgerWrite(expected: LedgerWriteEntity, remote: RemoteTransaction,
        current: () -> Boolean): Boolean = database.withTransaction {
        require(expected.state == LedgerWriteEntity.POSTED && (expected.serverId ?: 0) > 0)
        require(remote.ledgerId == expected.ledgerId && remote.id == expected.serverId && remote.type in 1..4)
        if (!current() || dao.findLedgerWrite(expected.ledgerId) != expected) return@withTransaction false
        // A GET of one original transaction is one cache upsert, never a complete ledger snapshot.
        dao.upsertLedgerTransactions(listOf(LedgerTransactionCacheEntity(remote.id, remote.ledgerId, remote.type,
            remote.sourceAccountId, remote.destinationAccountId, remote.categoryId, remote.categoryName,
            remote.sourceAmountMinor, remote.destinationAmountMinor, remote.currency, remote.comment,
            Math.multiplyExact(remote.time, 1000L), remote.utcOffset, remote.tagIdsJson, remote.hideAmount,
            remote.recorderUid, remote.payerUid, remote.editable)))
        check(dao.acknowledgeOriginalLedgerWrite(expected.ledgerId, expected.localId, expected.createdAt,
            expected.payloadJson, requireNotNull(expected.serverId)) == 1)
        val previousStatus = dao.findSyncStatus() ?: SyncStatusEntity()
        dao.upsertSyncStatus(previousStatus.copy(state = SyncRunState.IDLE,
            message = "原流水结果已核对，账户余额和其他账本数据仍待明确刷新", nextRetryAt = 0))
        check(current()) { "原登录归属已变化，读取结果未写入" }
        true
    }

    /** A single original upload is not a full sync: preserve the other cached data's stale status. */
    internal suspend fun acknowledgeOriginalLocalSnapshot(uploaded: TransactionEntity, remote: RemoteTransaction?) = database.withTransaction {
        require(uploaded.reviewItemId == null)
        require(remote == null && uploaded.deleted || remote != null && remote.ledgerId == 0L && remote.id > 0 &&
            (uploaded.serverId == null || uploaded.serverId == remote.id))
        val current = dao.findTransaction(uploaded.localId) ?: return@withTransaction
        // An edit made while the HTTP request ran survives, but a new identity,
        // a legacy association or a different pinned receipt cannot inherit it.
        check(current.reviewItemId == null && current.serverId == uploaded.serverId &&
            current.time == uploaded.time && current.utcOffset == uploaded.utcOffset && current.timeSequenceId == uploaded.timeSequenceId) {
            "原流水身份已变化，原回执不得覆盖替代记录"
        }
        markSynced(uploaded, remote)
        val previous = dao.findSyncStatus() ?: SyncStatusEntity()
        dao.upsertSyncStatus(previous.copy(state = SyncRunState.IDLE,
            message = "原流水已核对，账户余额和其他数据仍待明确刷新", nextRetryAt = 0))
    }

    internal suspend fun mergeOriginalTransaction(expected: TransactionEntity, remote: RemoteTransaction,
        current: () -> Boolean): TransactionEntity = database.withTransaction {
        require(expected.reviewItemId == null && expected.serverId == remote.id && remote.ledgerId == 0L)
        check(current() && dao.findTransaction(expected.localId) == expected) { "原流水已变化" }
        val approved = dao.findConflict(expected.localId)?.takeIf { it.resolution == ConflictResolution.KEEP_LOCAL }
        if (approved != null) {
            val approvedSnapshot = runCatching { originalConflictRemote(approved, expected.localId).syncSnapshot() }.getOrNull()
            if (approvedSnapshot != remote.toEntity(expected.localId).syncSnapshot()) {
                // KEEP_LOCAL authorizes overwriting only the remote version the
                // user actually saw, never an unseen subsequent server edit.
                dao.upsertConflict(SyncConflictEntity(expected.localId, remote.id, expected.comment,
                    expected.sourceAmountMinor, remote.comment, remote.sourceAmountMinor, remote.toJson()))
                dao.upsertTransaction(expected.copy(syncState = SyncState.CONFLICT))
            }
        } else mergeRemote(listOf(remote))
        check(current())
        requireNotNull(dao.findTransaction(expected.localId))
    }

    /** A read-only submission receipt binds identity; it is never a replay of the mutable current payload. */
    internal suspend fun bindOriginalTransactionReceipt(expected: TransactionEntity, serverId: Long,
        current: () -> Boolean): TransactionEntity = database.withTransaction {
        require(expected.serverId == null && expected.reviewItemId == null && serverId > 0)
        check(current() && dao.findTransaction(expected.localId) == expected && dao.findConflict(expected.localId) == null)
        check(dao.allTransactions().none { it.localId != expected.localId && it.serverId == serverId }) {
            "原服务器流水已关联另一条本机记录，请先专用核验"
        }
        // There is no immutable pre-upload snapshot for old local rows. An
        // unequal GET must become a conflict, not a freshly invented baseline.
        val bound = expected.copy(serverId = serverId, syncedSnapshotJson = "")
        dao.upsertTransaction(bound)
        check(current())
        bound
    }

    /** A caller must first validate an unfiltered, complete default-ledger read. Only this row changes. */
    internal suspend fun recordOriginalAbsence(expected: TransactionEntity, current: () -> Boolean): TransactionEntity = database.withTransaction {
        require(expected.reviewItemId == null && expected.serverId != null)
        check(current() && dao.findTransaction(expected.localId) == expected)
        if (expected.deleted) {
            markSynced(expected)
        } else {
            dao.upsertConflict(SyncConflictEntity(expected.localId, requireNotNull(expected.serverId), expected.comment,
                expected.sourceAmountMinor, "原账本中已不存在（可能删除或迁移）", 0, REMOTE_DELETION_MARKER))
            dao.upsertTransaction(expected.copy(syncState = SyncState.CONFLICT))
        }
        check(current())
        requireNotNull(dao.findTransaction(expected.localId))
    }

    internal suspend fun undoOriginalDeletion(expected: TransactionEntity, remote: RemoteTransaction,
        current: () -> Boolean): TransactionEntity = database.withTransaction {
        require(expected.deleted && expected.reviewItemId == null && expected.serverId == remote.id && remote.ledgerId == 0L)
        require(remote.toEntity(expected.localId).syncSnapshot() == expected.syncSnapshot()) {
            "服务器原流水已变化，不能静默撤回删除，请先重新核验冲突"
        }
        check(current() && dao.findTransaction(expected.localId) == expected && dao.findConflict(expected.localId) == null)
        val restored = remote.toEntity(expected.localId)
        dao.upsertTransaction(restored)
        check(current())
        restored
    }

    internal suspend fun resolveOriginalConflict(expected: TransactionEntity, conflict: SyncConflictEntity,
        keepRemote: Boolean, current: () -> Boolean): TransactionEntity = database.withTransaction {
        require(expected.reviewItemId == null && conflict.localId == expected.localId && conflict.serverId == expected.serverId)
        check(current() && dao.findTransaction(expected.localId) == expected && dao.findConflict(expected.localId) == conflict)
        val deletedOnServer = conflict.remoteEntityJson == REMOTE_DELETION_MARKER
        val remote = if (deletedOnServer) null else originalConflictRemote(conflict, expected.localId)
        val resolved = when {
            keepRemote && deletedOnServer -> expected.copy(deleted = true, syncState = SyncState.SYNCED, updatedAt = System.currentTimeMillis())
            keepRemote -> requireNotNull(remote).copy(reviewItemId = null)
            deletedOnServer -> error("原流水可能已删除或迁移；先保留原冲突，不能通过恢复操作另存替代流水")
            else -> expected.copy(syncState = SyncState.PENDING)
        }
        dao.upsertTransaction(resolved)
        if (!keepRemote && !deletedOnServer) dao.upsertConflict(conflict.copy(resolution = ConflictResolution.KEEP_LOCAL))
        else dao.deleteConflict(expected.localId)
        check(current())
        resolved
    }

    // --- 账本与存钱目标 -------------------------------------------------
    fun observeLedgers(): Flow<List<LedgerEntity>> = dao.observeLedgers()
    fun observeSavingsGoals(ledgerId: Long): Flow<List<SavingsGoalEntity>> = dao.observeSavingsGoals(ledgerId)
    fun observeLedgerAccounts(ledgerId: Long): Flow<List<AccountEntity>> = dao.observeLedgerAccounts(ledgerId).map { rows -> rows.map { it.toAccountEntity() } }
    fun observeLedgerTransactions(ledgerId: Long): Flow<List<TransactionEntity>> = dao.observeLedgerTransactions(ledgerId).map { rows -> rows.map { it.toTransactionEntity() } }
    fun observeLedgerTransactionEditability(ledgerId: Long): Flow<Map<Long, Boolean>> =
        dao.observeLedgerTransactions(ledgerId).map { rows -> rows.associate { it.id to it.editable } }

    suspend fun allSavingsGoals(): List<SavingsGoalEntity> = dao.allSavingsGoals()

    /** Caches one goal after a deposit, withdrawal, create or modify succeeded. */
    suspend fun cacheSavingsGoal(remote: RemoteSavingsGoal) {
        require(remote.id > 0 && remote.targetAmountMinor > 0 && remote.savedAmountMinor >= 0) { "存钱目标无效" }
        dao.upsertSavingsGoals(listOf(remote.toEntity()))
    }

    suspend fun removeSavingsGoal(id: Long) {
        require(id > 0) { "目标 ID 无效" }
        dao.deleteSavingsGoal(id)
    }

    suspend fun replaceLedgers(remote: List<RemoteLedger>) = database.withTransaction {
        require(remote.map { it.id }.distinct().size == remote.size && remote.all { it.id > 0 }) { "账本列表包含无效或重复记录" }
        require(remote.none { it.id == LedgerEntity.DEFAULT_LEDGER_ID }) { "账本列表不应包含默认账本" }
        if (remote.isEmpty()) {
            dao.deleteAllLedgers()
            dao.deleteLedgerAccountsOutside(listOf(Long.MIN_VALUE))
            dao.deleteLedgerTransactionsOutside(listOf(Long.MIN_VALUE))
        } else {
            dao.upsertLedgers(remote.map { ledger ->
                LedgerEntity(ledger.id, ledger.ownerUid, ledger.type, ledger.familyId, ledger.name, ledger.comment, ledger.createdTime)
            })
            val ids = remote.map { it.id }
            dao.deleteLedgersNotIn(ids)
            dao.deleteLedgerAccountsOutside(ids)
            dao.deleteLedgerTransactionsOutside(ids)
        }
    }

    suspend fun replaceLedgerSnapshot(ledgerId: Long, accounts: List<RemoteAccount>, transactions: List<RemoteTransaction>) = database.withTransaction {
        require(ledgerId > 0) { "账本 ID 无效" }
        require(accounts.all { it.id > 0 } && accounts.map { it.id }.distinct().size == accounts.size) { "账本账户包含无效或重复记录" }
        require(transactions.all { it.id > 0 && it.ledgerId == ledgerId } && transactions.map { it.id }.distinct().size == transactions.size) { "账本流水包含无效、重复或跨账本记录" }
        dao.deleteLedgerAccounts(ledgerId)
        dao.deleteLedgerTransactions(ledgerId)
        if (accounts.isNotEmpty()) dao.upsertLedgerAccounts(accounts.map { item ->
            LedgerAccountCacheEntity(item.id, ledgerId, item.name, item.currency, item.balanceMinor, item.hidden,
                item.parentId, item.category, item.type, item.icon, item.color, item.comment, item.displayOrder, item.creditCardStatementDate)
        })
        if (transactions.isNotEmpty()) dao.upsertLedgerTransactions(transactions.map { item ->
            LedgerTransactionCacheEntity(item.id, ledgerId, item.type, item.sourceAccountId, item.destinationAccountId,
                item.categoryId, item.categoryName, item.sourceAmountMinor, item.destinationAmountMinor, item.currency,
                item.comment, Math.multiplyExact(item.time, 1000L), item.utcOffset, item.tagIdsJson, item.hideAmount,
                item.recorderUid, item.payerUid, item.editable)
        })
        // This is a validated complete read after a confirmed write. The row
        // may already have been deleted by another member; never resend it.
        dao.acknowledgeLedgerWrites(ledgerId)
    }

    /**
     * Replaces every cached goal with the merged per-ledger lists. The caller
     * must supply a complete snapshot across all visible ledgers so a partial
     * network failure can never look like an empty plan.
     */
    suspend fun replaceSavingsGoals(remote: List<RemoteSavingsGoal>) = database.withTransaction {
        require(remote.map { it.id }.distinct().size == remote.size && remote.all { it.id > 0 }) { "存钱计划包含无效或重复记录" }
        require(remote.all { it.targetAmountMinor > 0 && it.savedAmountMinor >= 0 }) { "存钱计划包含无效金额" }
        if (remote.isEmpty()) dao.deleteAllSavingsGoals() else {
            dao.upsertSavingsGoals(remote.map { goal ->
                SavingsGoalEntity(goal.id, goal.uid, goal.ledgerId, goal.name, goal.targetAmountMinor, goal.savedAmountMinor, goal.achieved, goal.deadlineTime, goal.comment)
            })
            dao.deleteSavingsGoalsNotIn(remote.map { it.id })
        }
    }

    suspend fun replaceExchangeRates(remote: RemoteExchangeRateSnapshot) = database.withTransaction {
        val items = remote.toEntities()
        require(items.isNotEmpty() && items.map { it.currency }.distinct().size == items.size) { "汇率快照为空或包含重复币种" }
        dao.upsertExchangeRates(items)
        dao.deleteExchangeRatesNotIn(items.map { it.currency })
    }

    suspend fun cacheCustomExchangeRate(remote: RemoteCustomExchangeRate) = database.withTransaction {
        val current = dao.allExchangeRates()
        require(current.isNotEmpty() && current.all { it.dataSource == ExchangeRateEntityData.USER_CUSTOM_SOURCE }) { "当前服务器未启用自定义汇率" }
        val metadata = current.first()
        dao.upsertExchangeRate(remote.toEntity(metadata))
        val updated = dao.allExchangeRates().map { it.copy(serverUpdateTime = remote.updateTime, fetchedAt = System.currentTimeMillis()) }
        dao.upsertExchangeRates(updated)
    }

    suspend fun removeCustomExchangeRate(currency: String) = dao.deleteExchangeRate(currency)

    suspend fun replaceProductAssets(remote: List<RemoteProductAsset>) = database.withTransaction {
        require(remote.map { it.id }.distinct().size == remote.size && remote.all { it.id > 0 }) { "资产列表包含无效或重复记录" }
        if (remote.isEmpty()) dao.deleteAllProductAssets() else {
            dao.upsertProductAssets(remote.map { it.toEntity() })
            dao.deleteProductAssetsNotIn(remote.map { it.id })
        }
    }

    suspend fun cacheProductAsset(remote: RemoteProductAsset) = dao.upsertProductAsset(remote.toEntity())

    suspend fun removeProductAsset(id: Long) = dao.deleteProductAsset(id)

    suspend fun replaceAIReviewItems(remote: List<RemoteAIReviewItem>) = database.withTransaction {
        require(remote.all { it.id > 0 && it.status == AIReviewItemEntity.STATUS_PENDING }) { "AI 待复核响应包含无效条目" }
        require(remote.map { it.id }.distinct().size == remote.size) { "AI 待复核响应包含重复条目" }
        dao.upsertAIReviewItems(remote.map { it.toEntity() })
        dao.deleteAIReviewItemsNotIn(remote.map { it.id })
        // This legacy pending list is not a posting receipt (and may be paged).
        // Keep every transaction association until an explicit original-transaction
        // recovery flow verifies it; absence cannot authorize a second confirmation.
    }

    suspend fun cacheAIReviewItem(remote: RemoteAIReviewItem) {
        require(remote.id > 0 && remote.status == AIReviewItemEntity.STATUS_PENDING) { "AI 待复核条目无效" }
        dao.upsertAIReviewItems(listOf(remote.toEntity()))
    }

    suspend fun removeAIReviewItem(id: Long) = dao.deleteAIReviewItem(id)

    suspend fun pendingAIReviewResolutions(): List<TransactionEntity> = dao.allTransactions().filter {
        it.reviewItemId != null && it.serverId != null && it.syncState == SyncState.SYNCED && !it.deleted
    }

    suspend fun markAIReviewResolved(transaction: TransactionEntity) = database.withTransaction {
        transaction.reviewItemId?.let { dao.deleteAIReviewItem(it) }
        dao.clearTransactionReviewItem(transaction.localId)
    }

    suspend fun replaceTemplates(remote: List<RemoteTemplate>, ledgerId: Long = 0, templateType: Int = 1) = database.withTransaction {
        require(ledgerId >= 0 && templateType in 1..2 && remote.all { it.ledgerId == ledgerId && it.templateType == templateType } && remote.map { it.id }.distinct().size == remote.size) { "模板响应范围错误" }
        mergeTemplates(remote)
        dao.deleteMissingTemplates(remote.map { it.id }, templateType, ledgerId)
    }

    suspend fun replaceScheduledTemplates(remote: List<RemoteTemplate>, ledgerId: Long = 0) = replaceTemplates(remote, ledgerId, 2)

    /** Commit a fully validated ledger snapshot together; retain uncertain action snapshots. */
    suspend fun replacePeriodicSnapshot(ledgerId: Long, templates: List<RemoteTemplate>, scheduled: List<RemoteTemplate>?, occurrences: List<RemoteOccurrence>?) = database.withTransaction {
        require((scheduled == null) == (occurrences == null)) { "周期快照不完整" }
        replaceTemplates(templates, ledgerId)
        if (scheduled != null && occurrences != null) {
            replaceScheduledTemplates(scheduled, ledgerId)
            replaceOccurrences(occurrences, ledgerId)
        }
        // The receipt is already stored. Clear only after a complete, validated
        // snapshot; a failed read keeps the receipt and never re-posts creation.
        for (write in dao.allTemplateCreations().filter { it.ledgerId == ledgerId && it.state == TemplateCreationEntity.POSTED && (it.templateType == 1 || scheduled != null) }) {
            dao.deleteTemplateDraft(ledgerId, write.templateType, write.draftId, write.draftRevision)
            dao.acknowledgeTemplateCreation(ledgerId, write.templateType, write.clientSessionId)
        }
    }

    suspend fun replaceOccurrences(remote: List<RemoteOccurrence>, ledgerId: Long = 0) = database.withTransaction {
        require(ledgerId >= 0 && remote.all { it.ledgerId == ledgerId && it.status in listOf(ScheduledOccurrenceEntity.STATUS_PENDING, ScheduledOccurrenceEntity.STATUS_DISMISSED) }) { "待确认响应范围错误" }
        val keys = remote.map { "${it.templateId}:${it.scheduledUnixTime}" }
        require(keys.distinct().size == keys.size) { "待确认响应包含重复项" }
        val protected = dao.occurrencesInLedger(ledgerId).filter { it.pendingAction != ScheduledOccurrenceEntity.ACTION_NONE }
            .map { it.templateId to it.scheduledUnixTime }.toSet()
        dao.upsertOccurrences(remote.filter { (it.templateId to it.scheduledUnixTime) !in protected }.map { it.toEntity() })
        dao.deleteOccurrencesNotIn(keys, ledgerId)
    }

    suspend fun allOccurrences(): List<ScheduledOccurrenceEntity> = dao.allOccurrences()

    /** Persist the immutable intent before networking; another action cannot replace an unknown result. */
    suspend fun enqueuePeriodicAction(ledgerId: Long, templateId: Long, scheduledUnixTime: Long, action: Int): PeriodicAction = database.withTransaction {
        val current = dao.findOccurrence(ledgerId, templateId, scheduledUnixTime) ?: error("此待确认记录已不在本机，请刷新")
        if (current.pendingAction != ScheduledOccurrenceEntity.ACTION_NONE) {
            require(current.pendingAction == action) { "原操作结果尚待确认，请先重试原操作" }
            return@withTransaction current.toAction()
        }
        require(if (action == ScheduledOccurrenceEntity.ACTION_RESTORE) current.status == ScheduledOccurrenceEntity.STATUS_DISMISSED
            else action in ScheduledOccurrenceEntity.ACTION_CONFIRM..ScheduledOccurrenceEntity.ACTION_DISMISS && current.status == ScheduledOccurrenceEntity.STATUS_PENDING) { "待确认记录状态不允许此操作" }
        val queued = current.copy(pendingAction = action, pendingActionQueuedAt = System.currentTimeMillis().coerceAtLeast(current.updatedAt + 1))
        val intent = queued.toAction()
        dao.upsertOccurrences(listOf(queued))
        intent
    }

    override suspend fun pendingPeriodicActions(): List<PeriodicAction> = dao.allOccurrences().filter { it.pendingAction != ScheduledOccurrenceEntity.ACTION_NONE }.map { it.toAction() }

    override suspend fun acknowledgePeriodicAction(action: PeriodicAction, transactionId: Long): Boolean = database.withTransaction {
        val current = dao.findOccurrence(action.ledgerId, action.templateId, action.scheduledUnixTime) ?: return@withTransaction false
        if (current.pendingAction != action.action || current.pendingActionQueuedAt != action.queuedAt) return@withTransaction false
        require(if (action.action == ScheduledOccurrenceEntity.ACTION_CONFIRM) transactionId > 0 else transactionId == 0L) { "周期回执无效" }
        val status = when (action.action) {
            ScheduledOccurrenceEntity.ACTION_CONFIRM -> ScheduledOccurrenceEntity.STATUS_CONFIRMED
            ScheduledOccurrenceEntity.ACTION_DISMISS -> ScheduledOccurrenceEntity.STATUS_DISMISSED
            else -> ScheduledOccurrenceEntity.STATUS_PENDING
        }
        dao.upsertOccurrences(listOf(current.copy(status = status, transactionId = transactionId, pendingAction = 0, pendingActionQueuedAt = 0, updatedAt = System.currentTimeMillis())))
        true
    }

    private fun ScheduledOccurrenceEntity.toAction() = PeriodicAction(ledgerId, templateId, scheduledUnixTime, pendingAction, pendingActionQueuedAt)

    /** Compatibility for callers applying an already explicit receipt; cannot replace a pending intent. */
    suspend fun applyOccurrenceAction(templateId: Long, scheduledUnixTime: Long, status: Int, transactionId: Long = 0, ledgerId: Long = 0) = database.withTransaction {
        require(status in 1..3 && (status != ScheduledOccurrenceEntity.STATUS_CONFIRMED || transactionId > 0)) { "待确认回执无效" }
        val current = dao.findOccurrence(ledgerId, templateId, scheduledUnixTime) ?: return@withTransaction
        require(current.pendingAction == ScheduledOccurrenceEntity.ACTION_NONE) { "必须先核对原操作回执" }
        dao.upsertOccurrences(listOf(current.copy(status = status, transactionId = transactionId, updatedAt = System.currentTimeMillis())))
    }
    fun observeSyncStatus(): Flow<SyncStatusEntity?> = dao.observeSyncStatus()

    suspend fun syncStatus(): SyncStatusEntity? = dao.findSyncStatus()
    suspend fun updateSyncStatus(status: SyncStatusEntity) = dao.upsertSyncStatus(status)

    suspend fun migrateLegacyDataIfNeeded() {
        if (!importLegacy) {
            database.withTransaction {
                if (dao.findAccount(TransactionEntity.LOCAL_ACCOUNT_ID) == null)
                    dao.upsertAccounts(listOf(AccountEntity(TransactionEntity.LOCAL_ACCOUNT_ID, "本地钱包", "CNY")))
            }
            return
        }
        if (store.get(KEY_ROOM_MIGRATED) == "true") return

        val legacy = run {
            val array = JSONArray(store.get("local_activities") ?: "[]")
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val kind = item.optString("kind", "支出")
                val amountMinor = parseAmountMinor(item.optString("amount"))
                TransactionEntity(
                    localId = item.optString("id").ifBlank { "legacy-$index" },
                    type = if (kind == "收入") TYPE_INCOME else TYPE_EXPENSE,
                    categoryName = item.optString("category", "其他"),
                    sourceAmountMinor = amountMinor,
                    comment = item.optString("title", kind),
                    time = System.currentTimeMillis() - index,
                    syncState = SyncState.PENDING
                )
            }
        }

        database.withTransaction {
            legacy.forEach { if (dao.findTransaction(it.localId) == null) dao.upsertTransaction(it) }
            if (dao.findAccount(TransactionEntity.LOCAL_ACCOUNT_ID) == null)
                dao.upsertAccounts(listOf(AccountEntity(TransactionEntity.LOCAL_ACCOUNT_ID, "本地钱包", "CNY")))
        }
        store.put(KEY_ROOM_MIGRATED, "true")
    }

    suspend fun save(draft: TransactionDraft) = database.withTransaction {
        require(draft.type in listOf(TYPE_MODIFY_BALANCE, TYPE_INCOME, TYPE_EXPENSE, TYPE_TRANSFER)) { "不支持的流水类型" }
        if (draft.type == TYPE_MODIFY_BALANCE || draft.type == TYPE_EXPENSE) require(draft.sourceAmountMinor != 0L && draft.sourceAmountMinor in -9_999_999_999_999L..9_999_999_999_999L) { "金额不能为 0 或超出范围" }
        else if (draft.type == TYPE_TRANSFER && draft.transferDirection == TransactionTransferDirection.IN)
            require(draft.sourceAmountMinor == 0L && draft.destinationAmountMinor in 1..9_999_999_999_999L) { "转入金额超出范围" }
        else require(draft.sourceAmountMinor in 1..9_999_999_999_999L) { "金额超出范围" }
        requireTransactionTransferDirection(draft)
        require(draft.comment.length <= 255) { "描述最多 255 个字符" }
        val current = dao.findTransaction(draft.localId)
        require(current == null || current.type in listOf(TYPE_MODIFY_BALANCE, TYPE_INCOME, TYPE_EXPENSE, TYPE_TRANSFER)) { "不支持编辑该流水" }
        val resolvedAccountId = if (draft.sourceAccountId == TransactionEntity.LOCAL_ACCOUNT_ID)
            dao.findAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)?.serverId ?: TransactionEntity.LOCAL_ACCOUNT_ID
        else draft.sourceAccountId
        val account = dao.findAccount(resolvedAccountId)
        val incoming = draft.type == TYPE_TRANSFER && draft.transferDirection == TransactionTransferDirection.IN
        require(incoming && resolvedAccountId == 0L || resolvedAccountId == TransactionEntity.LOCAL_ACCOUNT_ID ||
            account?.hidden == false && account.type == 1) { "账户已不可用，请重新选择" }
        val resolvedDestinationId = if (draft.type == TYPE_TRANSFER) draft.destinationAccountId?.takeIf { it != 0L } else null
        val destinationAccount = resolvedDestinationId?.let { dao.findAccount(it) }
        if (draft.type == TYPE_TRANSFER) {
            if (draft.transferDirection == TransactionTransferDirection.OUT) {
                require(resolvedDestinationId == null && draft.destinationAmountMinor == 0L)
            } else {
                require(resolvedDestinationId != null && resolvedDestinationId != resolvedAccountId) { "转入账户必须与转出账户不同" }
                require(destinationAccount?.hidden == false && destinationAccount.type == 1) { "转入账户已不可用，请重新选择" }
                require(draft.destinationAmountMinor in 1..9_999_999_999_999L) { "转入金额超出范围" }
                if (draft.transferDirection == TransactionTransferDirection.INTERNAL && account != null && account.currency == destinationAccount?.currency)
                    require(draft.sourceAmountMinor == draft.destinationAmountMinor) { "同币种转账的双侧金额必须一致，请明确核对" }
            }
        } else require(draft.destinationAccountId == null && draft.destinationAmountMinor == 0L) { "非转账流水不能设置转入账户或金额" }
        val categoryType = when (draft.type) { TYPE_INCOME -> 1; TYPE_EXPENSE -> 2; TYPE_TRANSFER -> 3; else -> 0 }
        val unchangedCategory = current?.categoryName == draft.categoryName && current.type == draft.type
        val candidateId = if (draft.type == TYPE_MODIFY_BALANCE) null else draft.categoryId
            ?: current?.categoryId?.takeIf { unchangedCategory }
            ?: dao.findCategoryMapping(draft.categoryName, draft.type)?.serverId
        val candidate = candidateId?.let { dao.findCategory(it) }
        val candidateParent = candidate?.takeIf { it.parentId != 0L }?.let { dao.findCategory(it.parentId) }
        val mappedCategoryId = if (draft.type == TYPE_MODIFY_BALANCE) null else candidate?.takeIf {
            !it.hidden && it.type == categoryType && it.parentId != 0L && candidateParent?.hidden == false
        }?.id
        val base = current ?: TransactionEntity(
            localId = draft.localId, type = draft.type, categoryName = draft.categoryName,
            sourceAmountMinor = draft.sourceAmountMinor, comment = draft.comment,
            time = System.currentTimeMillis(), utcOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        )
        val tags = stringIds(draft.tagIdsJson)
        require(tags.length() <= 10) { "每笔流水最多 10 个标签" }
        dao.upsertTransaction(base.copy(
            type = draft.type, sourceAccountId = resolvedAccountId,
            destinationAccountId = resolvedDestinationId,
            categoryId = mappedCategoryId, categoryName = draft.categoryName,
            sourceAmountMinor = draft.sourceAmountMinor, destinationAmountMinor = if (draft.type == TYPE_TRANSFER) draft.destinationAmountMinor else 0,
            comment = draft.comment,
            currency = account?.currency ?: destinationAccount?.currency ?: base.currency, tagIdsJson = tags.toString(),
            reviewItemId = draft.reviewItemId ?: base.reviewItemId,
            hideAmount = draft.hideAmount ?: base.hideAmount,
            syncState = SyncState.PENDING, deleted = false, updatedAt = System.currentTimeMillis(),
            syncedSnapshotJson = base.syncedSnapshotJson.ifBlank {
                if (base.serverId != null && base.syncState == SyncState.SYNCED) base.syncSnapshot() else ""
            }
        ))
    }

    suspend fun markDeleted(localId: String) = database.withTransaction {
        val current = dao.findTransaction(localId) ?: return@withTransaction
        dao.upsertTransaction(current.copy(deleted = true, syncState = if (current.serverId == null) SyncState.SYNCED else SyncState.PENDING,
            updatedAt = System.currentTimeMillis(), syncedSnapshotJson = current.syncedSnapshotJson.ifBlank {
                if (current.syncState == SyncState.SYNCED) current.syncSnapshot() else ""
            }))
    }

    suspend fun pendingTransactions(): List<TransactionEntity> =
        dao.allTransactions().filter { transaction ->
            if (transaction.syncState == SyncState.SYNCED) return@filter false
            // An unresolved conflict must stay visible until the user chooses
            // a side. KEEP_LOCAL is the explicit signal that it may retry push.
            val conflict = dao.findConflict(transaction.localId)
            conflict == null || conflict.resolution == ConflictResolution.KEEP_LOCAL
        }

    suspend fun mergeRemote(remote: List<RemoteTransaction>): MergeResult = database.withTransaction {
        val existing = dao.allTransactions()
        val byServerId = existing.mapNotNull { item -> item.serverId?.let { it to item } }.toMap()
        var merged = 0
        var conflicts = 0
        remote.forEach { item ->
            val local = byServerId[item.id]
            // reviewItemId is client-only linkage used to resolve the server
            // review after a successful transaction upload. A normal pull has
            // no such field, so preserve it for explicit original-transaction recovery.
            val remoteEntity = item.toEntity(local?.localId).copy(reviewItemId = local?.reviewItemId)
            val remoteSnapshot = remoteEntity.syncSnapshot()
            val existingConflict = local?.let { dao.findConflict(it.localId) }
            if (existingConflict?.resolution == ConflictResolution.KEEP_LOCAL) return@forEach
            if (local == null || local.syncState == SyncState.SYNCED ||
                (!local.deleted && local.syncSnapshot() == remoteSnapshot)) {
                dao.upsertTransaction(remoteEntity)
                local?.let { dao.deleteConflict(it.localId) }
                merged++
            } else if (local.syncedSnapshotJson.isNotBlank() && local.syncedSnapshotJson == remoteSnapshot) {
                // The server still has the baseline: preserve this local edit/deletion for push.
                dao.deleteConflict(local.localId)
            } else {
                dao.upsertConflict(SyncConflictEntity(local.localId, item.id, local.comment, local.sourceAmountMinor,
                    remoteEntity.comment, remoteEntity.sourceAmountMinor, item.toJson()))
                dao.upsertTransaction(local.copy(syncState = SyncState.CONFLICT))
                conflicts++
            }
        }
        MergeResult(merged, conflicts)
    }

    /** Reconcile only after a successful, unfiltered, fully paginated pull. */
    suspend fun reconcileRemoteAbsence(remoteServerIds: Set<Long>): AbsenceResult = database.withTransaction {
        var removed = 0
        var conflicts = 0
        dao.allTransactions().filter { it.serverId != null && it.serverId !in remoteServerIds }.forEach { local ->
            when {
                local.deleted -> {
                    dao.upsertTransaction(local.copy(syncState = SyncState.SYNCED, updatedAt = System.currentTimeMillis()))
                    dao.deleteConflict(local.localId)
                }
                local.syncState == SyncState.SYNCED -> {
                    dao.upsertTransaction(local.copy(deleted = true, syncState = SyncState.SYNCED, updatedAt = System.currentTimeMillis()))
                    dao.deleteConflict(local.localId)
                    removed++
                }
                local.syncState != SyncState.CONFLICT -> {
                    dao.upsertConflict(SyncConflictEntity(
                        localId = local.localId,
                        serverId = local.serverId!!,
                        localComment = local.comment,
                        localAmountMinor = local.sourceAmountMinor,
                        remoteComment = "已在服务器删除",
                        remoteAmountMinor = 0,
                        remoteEntityJson = REMOTE_DELETION_MARKER
                    ))
                    dao.upsertTransaction(local.copy(syncState = SyncState.CONFLICT))
                    conflicts++
                }
            }
        }
        AbsenceResult(removed, conflicts)
    }

    suspend fun resolveConflict(localId: String, keepRemote: Boolean) = database.withTransaction {
        val conflict = dao.findConflict(localId)
        val current = dao.findTransaction(localId) ?: return@withTransaction
        if (keepRemote) {
            conflict?.let {
                if (it.remoteEntityJson == REMOTE_DELETION_MARKER) {
                    dao.upsertTransaction(current.copy(deleted = true, syncState = SyncState.SYNCED, updatedAt = System.currentTimeMillis()))
                    dao.deleteConflict(localId)
                    return@withTransaction
                }
                val remote = it.remoteEntityJson.takeIf(String::isNotBlank)?.let { json -> runCatching { RemoteTransaction.from(org.json.JSONObject(json)).toEntity(localId) }.getOrNull() }
                dao.upsertTransaction((remote ?: current.copy(comment = it.remoteComment, sourceAmountMinor = it.remoteAmountMinor)).copy(syncState = SyncState.SYNCED, updatedAt = System.currentTimeMillis()))
            }
        } else {
            if (conflict?.remoteEntityJson == REMOTE_DELETION_MARKER) {
                val now = System.currentTimeMillis()
                // clientSessionId is the local ID. Reusing it would make the
                // server replay the original (now deleted) create request.
                dao.upsertTransaction(current.copy(deleted = true, syncState = SyncState.SYNCED, updatedAt = now))
                dao.upsertTransaction(current.copy(
                    localId = java.util.UUID.randomUUID().toString(), serverId = null, timeSequenceId = null,
                    deleted = false, syncState = SyncState.PENDING, syncedSnapshotJson = "", updatedAt = now
                ))
                dao.deleteConflict(localId)
                return@withTransaction
            }
            dao.upsertTransaction(current.copy(syncState = SyncState.PENDING))
            conflict?.let { dao.upsertConflict(it.copy(resolution = ConflictResolution.KEEP_LOCAL)) }
            return@withTransaction
        }
        dao.deleteConflict(localId)
    }

    suspend fun mergeAccounts(remote: List<RemoteAccount>) {
        dao.upsertAccounts(remote.map { AccountEntity(id = it.id, name = it.name, currency = it.currency, balanceMinor = it.balanceMinor,
            hidden = it.hidden, parentId = it.parentId, category = it.category, type = it.type, icon = it.icon, color = it.color,
            comment = it.comment, displayOrder = it.displayOrder, creditCardStatementDate = it.creditCardStatementDate) })
    }

    suspend fun removeAccount(id: Long) = dao.deleteAccount(id)

    suspend fun saveAccounts(items: List<AccountEntity>) = dao.upsertAccounts(items)

    suspend fun replaceAccountGroup(rootId: Long, remote: List<RemoteAccount>) = database.withTransaction {
        mergeAccounts(remote)
        val childIds = remote.filter { it.parentId == rootId }.map { it.id }
        if (childIds.isNotEmpty()) dao.deleteMissingChildAccounts(rootId, childIds)
    }

    suspend fun mergeCategories(remote: List<RemoteCategory>) {
        dao.upsertCategories(remote.map { CategoryEntity(it.id, it.name, it.parentId, it.type, it.icon, it.color, it.hidden, comment = it.comment, displayOrder = it.displayOrder) })
    }

    suspend fun saveCategories(items: List<CategoryEntity>) = dao.upsertCategories(items)

    suspend fun removeCategoryTree(id: Long) = dao.deleteCategoryTree(id)

    suspend fun mergeTags(remote: List<RemoteTag>) {
        dao.upsertTags(remote.map { TagEntity(it.id, it.name, it.groupId, it.hidden) })
    }

    suspend fun removeTag(id: Long) = dao.deleteTag(id)

    suspend fun mergeTemplates(remote: List<RemoteTemplate>) {
        val pausedBackups = dao.allTemplates().filter {
            it.templateType == 2 && it.scheduledFrequencyType == 0
        }.associateBy({ it.ledgerId to it.id }, { it.pausedFromFrequencyType to it.pausedFromFrequency })
        dao.upsertTemplates(remote.map {
            TemplateEntity(it.id, it.name, it.type, it.categoryId, it.sourceAccountId, it.sourceAmountMinor, it.comment, it.tagIdsJson, it.hidden,
                templateType = it.templateType, destinationAccountId = it.destinationAccountId, destinationAmountMinor = it.destinationAmountMinor,
                hideAmount = it.hideAmount, scheduledFrequencyType = it.scheduledFrequencyType, scheduledFrequency = it.scheduledFrequency,
                scheduledStartDate = it.scheduledStartDate, scheduledEndDate = it.scheduledEndDate, utcOffset = it.utcOffset,
                scheduledAt = it.scheduledAt, nextScheduledTime = it.nextScheduledTime, displayOrder = it.displayOrder, ledgerId = it.ledgerId).let { entity ->
                // The pre-pause frequency only lives locally; keep it while the
                // server still shows the schedule as paused.
                val backup = pausedBackups[it.ledgerId to it.id]
                if (entity.templateType == 2 && entity.scheduledFrequencyType == 0 && backup != null)
                    entity.copy(pausedFromFrequencyType = backup.first, pausedFromFrequency = backup.second)
                else entity
            }
        })
    }

    suspend fun removeTemplate(id: Long, ledgerId: Long = 0) = dao.deleteTemplate(id, ledgerId)

    /** Persists a locally reordered schedule list after a successful server move. */
    suspend fun saveTemplates(items: List<TemplateEntity>) = dao.upsertTemplates(items)

    private fun RemoteOccurrence.toEntity() = ScheduledOccurrenceEntity(
        templateId = templateId, scheduledUnixTime = scheduledUnixTime, status = status, transactionId = transactionId,
        name = name, type = type, categoryId = categoryId, sourceAccountId = sourceAccountId,
        destinationAccountId = destinationAccountId, sourceAmountMinor = sourceAmountMinor,
        destinationAmountMinor = destinationAmountMinor, utcOffset = utcOffset, hideAmount = hideAmount,
        tagIdsJson = stringIds(tagIdsJson).toString(), comment = comment, ledgerId = ledgerId
    )

    suspend fun mapCategory(localName: String, transactionType: Int, serverId: Long) = database.withTransaction {
        require(transactionType in listOf(TYPE_INCOME, TYPE_EXPENSE)) { "流水类型无效" }
        val category = dao.findCategory(serverId)
        val parent = category?.takeIf { it.parentId != 0L }?.let { dao.findCategory(it.parentId) }
        val expectedCategoryType = if (transactionType == TYPE_INCOME) 1 else 2
        require(category != null && !category.hidden && category.parentId != 0L && category.type == expectedCategoryType && parent?.hidden == false) {
            "只能绑定同类型的可用末级分类"
        }
        dao.upsertCategoryMapping(CategoryMappingEntity(localName, transactionType, serverId))
        dao.backfillPendingCategory(localName, transactionType, serverId)
    }

    suspend fun clearCategoryMapping(localName: String, transactionType: Int) = database.withTransaction {
        val mapping = dao.findCategoryMapping(localName, transactionType) ?: return@withTransaction
        dao.deleteCategoryMapping(localName, transactionType)
        dao.clearPendingCategory(localName, transactionType, mapping.serverId)
    }

    suspend fun mapAccount(localId: Long, serverId: Long): Int = database.withTransaction {
        require(localId == TransactionEntity.LOCAL_ACCOUNT_ID) { "本地账户无效" }
        val account = dao.findAccount(serverId)
        require(serverId != TransactionEntity.LOCAL_ACCOUNT_ID && account != null && !account.hidden) { "只能绑定可用的服务端账户" }
        dao.upsertAccountMapping(AccountMappingEntity(localId, serverId))
        dao.backfillPendingAccount(localId, serverId, account.currency)
    }

    suspend fun clearAccountMapping(localId: Long) = database.withTransaction {
        dao.deleteAccountMapping(localId)
    }

    suspend fun markSynced(uploaded: TransactionEntity, remote: RemoteTransaction? = null) = database.withTransaction {
        val current = dao.findTransaction(uploaded.localId) ?: return@withTransaction
        val unchanged = current.syncSnapshot() == uploaded.syncSnapshot() && current.deleted == uploaded.deleted
        val serverId = remote?.id ?: uploaded.serverId
        require(uploaded.deleted || serverId != null) { "同步响应缺少流水 ID" }
        dao.upsertTransaction(current.copy(serverId = serverId, timeSequenceId = remote?.timeSequenceId ?: current.timeSequenceId,
            syncState = if (unchanged) SyncState.SYNCED else SyncState.PENDING,
            syncedSnapshotJson = uploaded.syncSnapshot(), updatedAt = System.currentTimeMillis()))
        if (unchanged) dao.deleteConflict(uploaded.localId)
    }

    companion object {
        const val KEY_ROOM_MIGRATED = "room_migrated_v1"
        const val TYPE_INCOME = 2
        const val TYPE_EXPENSE = 3
        const val TYPE_MODIFY_BALANCE = 1
        const val TYPE_TRANSFER = 4
        const val REMOTE_DELETION_MARKER = "{\"deleted\":true}"

        fun parseAmountMinor(raw: String): Long = raw
            .replace("¥", "").replace(",", "").replace("+", "").replace("-", "").trim()
            .toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L
    }
}

data class MergeResult(val merged: Int, val conflicts: Int)
data class AbsenceResult(val removed: Int, val conflicts: Int)

object ConflictResolution {
    const val KEEP_LOCAL = "keep_local"
}
