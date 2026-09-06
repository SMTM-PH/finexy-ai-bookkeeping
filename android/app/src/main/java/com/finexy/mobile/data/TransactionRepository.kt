package com.finexy.mobile.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
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
class TransactionRepository(context: Context, private val database: FinexyDatabase = FinexyDatabase.get(context.applicationContext), private val importLegacy: Boolean = true) {
    private val appContext = context.applicationContext
    private val store = SecureStore(appContext)
    private val dao = database.dao()

    fun observeTransactions(): Flow<List<TransactionEntity>> = dao.observeTransactions()
    fun observeAccounts(): Flow<List<AccountEntity>> = dao.observeAccounts()
    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeCategories()
    fun observeConflicts(): Flow<List<SyncConflictEntity>> = dao.observeConflicts()
    fun observeCategoryMappings(): Flow<List<CategoryMappingEntity>> = dao.observeCategoryMappings()
    fun observeAccountMappings(): Flow<List<AccountMappingEntity>> = dao.observeAccountMappings()
    fun observeTags(): Flow<List<TagEntity>> = dao.observeTags()
    fun observeTemplates(): Flow<List<TemplateEntity>> = dao.observeTemplates()

    fun observeScheduledTemplates(): Flow<List<TemplateEntity>> = dao.observeScheduledTemplates()

    fun observeOccurrences(): Flow<List<ScheduledOccurrenceEntity>> = dao.observeOccurrences()

    suspend fun replaceScheduledTemplates(remote: List<RemoteTemplate>) = database.withTransaction {
        require(remote.all { it.templateType == 2 }) { "周期模板响应类型错误" }
        mergeTemplates(remote)
        dao.deleteMissingScheduledTemplates(remote.map { it.id })
    }

    /**
     * Reconciles the local review queue with a fully-paginated server pull.
     * The parser rejects malformed pages, so a partial response never clears
     * locally cached rows; rows confirmed on the server disappear from the
     * pending/dismissed lists and are dropped here as well.
     */
    suspend fun replaceOccurrences(remote: List<RemoteOccurrence>) = database.withTransaction {
        require(remote.all { it.status != ScheduledOccurrenceEntity.STATUS_CONFIRMED }) { "确认记录不进入待确认队列" }
        dao.upsertOccurrences(remote.map { it.toEntity() })
        val keys = remote.map { "${it.templateId}:${it.scheduledUnixTime}" }
        dao.deleteOccurrencesNotIn(keys)
    }

    suspend fun allOccurrences(): List<ScheduledOccurrenceEntity> = dao.allOccurrences()

    /** Applies the result of a successful confirm/dismiss/restore API call. */
    suspend fun applyOccurrenceAction(templateId: Long, scheduledUnixTime: Long, status: Int, transactionId: Long = 0) = database.withTransaction {
        require(status in listOf(ScheduledOccurrenceEntity.STATUS_PENDING, ScheduledOccurrenceEntity.STATUS_CONFIRMED, ScheduledOccurrenceEntity.STATUS_DISMISSED)) { "待确认状态无效" }
        val current = dao.allOccurrences().firstOrNull {
            it.templateId == templateId && it.scheduledUnixTime == scheduledUnixTime
        } ?: return@withTransaction
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
        if (draft.type == TYPE_MODIFY_BALANCE) require(draft.sourceAmountMinor != 0L && draft.sourceAmountMinor in -99_999_999_999L..99_999_999_999L) { "调整金额不能为 0 或超出范围" }
        else require(draft.sourceAmountMinor in 1..99_999_999_999L) { "金额超出范围" }
        require(draft.comment.length <= 255) { "描述最多 255 个字符" }
        val current = dao.findTransaction(draft.localId)
        require(current == null || current.type in listOf(TYPE_MODIFY_BALANCE, TYPE_INCOME, TYPE_EXPENSE, TYPE_TRANSFER)) { "不支持编辑该流水" }
        val resolvedAccountId = if (draft.sourceAccountId == TransactionEntity.LOCAL_ACCOUNT_ID)
            dao.findAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)?.serverId ?: TransactionEntity.LOCAL_ACCOUNT_ID
        else draft.sourceAccountId
        val account = dao.findAccount(resolvedAccountId)
        require(resolvedAccountId == TransactionEntity.LOCAL_ACCOUNT_ID || (account?.hidden == false && account.type == 1)) { "账户已不可用，请重新选择" }
        val resolvedDestinationId = if (draft.type == TYPE_TRANSFER) draft.destinationAccountId else null
        val destinationAccount = resolvedDestinationId?.let { dao.findAccount(it) }
        if (draft.type == TYPE_TRANSFER) {
            require(resolvedDestinationId != null && resolvedDestinationId != resolvedAccountId) { "转入账户必须与转出账户不同" }
            require(destinationAccount?.hidden == false && destinationAccount.type == 1) { "转入账户已不可用，请重新选择" }
            require(draft.destinationAmountMinor in 1..99_999_999_999L) { "转入金额超出范围" }
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
            currency = account?.currency ?: base.currency, tagIdsJson = tags.toString(),
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
            val remoteEntity = item.toEntity(local?.localId)
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
        }.associateBy({ it.id }, { it.pausedFromFrequencyType to it.pausedFromFrequency })
        dao.upsertTemplates(remote.map {
            TemplateEntity(it.id, it.name, it.type, it.categoryId, it.sourceAccountId, it.sourceAmountMinor, it.comment, it.tagIdsJson, it.hidden,
                templateType = it.templateType, destinationAccountId = it.destinationAccountId, destinationAmountMinor = it.destinationAmountMinor,
                hideAmount = it.hideAmount, scheduledFrequencyType = it.scheduledFrequencyType, scheduledFrequency = it.scheduledFrequency,
                scheduledStartDate = it.scheduledStartDate, scheduledEndDate = it.scheduledEndDate, utcOffset = it.utcOffset,
                scheduledAt = it.scheduledAt, nextScheduledTime = it.nextScheduledTime, displayOrder = it.displayOrder).let { entity ->
                // The pre-pause frequency only lives locally; keep it while the
                // server still shows the schedule as paused.
                val backup = pausedBackups[it.id]
                if (entity.templateType == 2 && entity.scheduledFrequencyType == 0 && backup != null)
                    entity.copy(pausedFromFrequencyType = backup.first, pausedFromFrequency = backup.second)
                else entity
            }
        })
    }

    suspend fun removeTemplate(id: Long) = dao.deleteTemplate(id)

    /** Persists a locally reordered schedule list after a successful server move. */
    suspend fun saveTemplates(items: List<TemplateEntity>) = dao.upsertTemplates(items)

    private fun RemoteOccurrence.toEntity() = ScheduledOccurrenceEntity(
        templateId = templateId, scheduledUnixTime = scheduledUnixTime, status = status, transactionId = transactionId,
        name = name, type = type, categoryId = categoryId, sourceAccountId = sourceAccountId,
        destinationAccountId = destinationAccountId, sourceAmountMinor = sourceAmountMinor,
        destinationAmountMinor = destinationAmountMinor, utcOffset = utcOffset, hideAmount = hideAmount,
        tagIdsJson = stringIds(tagIdsJson).toString(), comment = comment
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
