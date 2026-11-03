package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Durable identifiers only. A WorkManager input must never contain a financial DTO. */
internal data class OriginalTransactionCommand(
    val namespace: String,
    val serverUrl: String,
    val identity: String,
    val storagePartition: String,
    val target: LedgerPendingTarget.Transaction,
    val expectedReceiptId: Long? = null
) {
    init {
        LedgerPendingContext(namespace, serverUrl, identity, storagePartition, target.ledgerId, 0)
        require(identity != "local" && target.relatedAIItemId == null)
        require(expectedReceiptId == null || target.ledgerId > 0 && expectedReceiptId > 0)
    }

    fun matches(context: LedgerPendingContext): Boolean = !context.localMode &&
        namespace == context.namespace && serverUrl == context.serverUrl && identity == context.identity &&
        storagePartition == context.storagePartition && target.ledgerId == context.ledgerId

    companion object {
        fun from(location: LedgerPendingPageLocation, receiptId: Long? = null): OriginalTransactionCommand =
            OriginalTransactionCommand(location.context.namespace, location.context.serverUrl,
                location.context.identity, location.context.storagePartition,
                location.target as LedgerPendingTarget.Transaction, receiptId)
    }
}

/** UI operations retain their original revision; a durable Worker may bind a fresh session in the same partition. */
internal fun originalRecoveryContext(command: OriginalTransactionCommand, actual: LedgerPendingContext?,
    expected: LedgerPendingContext? = null): LedgerPendingContext? = actual?.takeIf {
    command.matches(it) && (expected == null || expected == it)
}

internal fun matchesOriginalTransaction(target: LedgerPendingTarget.Transaction, row: TransactionEntity?): Boolean =
    target.ledgerId == 0L && row != null && pendingTransactionRecord(row)?.target == target

internal fun matchesOriginalLedgerWrite(command: OriginalTransactionCommand, row: LedgerWriteEntity?): Boolean =
    row != null && command.target.ledgerId > 0 && pendingLedgerWriteRecord(row).target == command.target &&
        (command.expectedReceiptId == null || row.state == LedgerWriteEntity.POSTED && row.serverId == command.expectedReceiptId)

internal fun requireOrdinaryOriginalEditor(row: TransactionEntity): TransactionEntity {
    require(row.reviewItemId == null) { "旧 AI 请求必须先核验原回执，不能转为普通新增" }
    require(!row.deleted && row.syncState != SyncState.CONFLICT) { "原删除或冲突必须先按原意图处理" }
    // Type 1 stores the server's target balance, while the existing entry form
    // edits a signed delta. Do not silently reinterpret either representation.
    require(row.type in 1..4 && pendingTransactionRecord(row) != null) { "原流水种类不受支持或已经处理，原内容保留" }
    require(row.type != TransactionRepository.TYPE_MODIFY_BALANCE) { "原余额调整须在专用目标余额核验入口处理；原内容保留" }
    return row
}

/** Conflict JSON is our full internal RemoteTransaction projection, not an API response envelope. */
internal fun originalConflictRemote(conflict: SyncConflictEntity, localId: String): TransactionEntity {
    require(conflict.localId == localId && conflict.remoteEntityJson != TransactionRepository.REMOTE_DELETION_MARKER)
    val raw = JSONObject(conflict.remoteEntityJson)
    require(listOf("id", "type", "categoryId", "sourceAccountId", "destinationAccountId", "sourceAmount",
        "destinationAmount", "time", "utcOffset", "comment", "tagIds", "pictureIds", "geoLocation", "hideAmount",
        "editable", "ledgerId", "sourceAccount").all(raw::has)) { "原冲突字段不完整" }
    require(raw.get("tagIds") is JSONArray && raw.get("pictureIds") is JSONArray &&
        raw.get("hideAmount") is Boolean && raw.get("editable") is Boolean)
    require(raw.getJSONObject("sourceAccount").getString("currency").isNotBlank())
    val remote = RemoteTransaction.from(raw)
    require(remote.id == conflict.serverId && remote.id > 0 && remote.ledgerId == 0L && remote.type in 1..4 && remote.time > 0 && remote.utcOffset in -720..840)
    require(remote.type == 1 || (remote.categoryId ?: 0) > 0)
    require(remote.sourceAmountMinor in -9_999_999_999_999L..9_999_999_999_999L && remote.destinationAmountMinor in 0..9_999_999_999_999L)
    if (remote.type == 4) {
        val direction = transferDirectionFromSides(4, remote.sourceAccountId, remote.destinationAccountId)
        // Legacy GETs mirror an external incoming amount on the inactive source
        // side. Its write projection still has source 0 / amount 0.
        requireTransactionTransferDirection(TransactionDraft(localId, 4, remote.sourceAccountId, remote.categoryId,
            remote.categoryName, if (direction == TransactionTransferDirection.IN) 0 else remote.sourceAmountMinor, remote.comment, remote.tagIdsJson,
            remote.destinationAccountId, remote.destinationAmountMinor, transferDirection = direction))
    } else require(remote.sourceAccountId > 0 && (remote.destinationAccountId ?: 0) == 0L && remote.destinationAmountMinor == 0L)
    return remote.toEntity(localId)
}

internal enum class OriginalTransactionRecoveryResult { ACKNOWLEDGED, CHANGED, WAITING_FOR_MAPPING, CONFLICT, PAUSED, REQUIRES_ORIGINAL_VERIFICATION }

internal interface OriginalLedgerWriteStore {
    suspend fun read(ledgerId: Long): LedgerWriteEntity?
    /** Persist a receipt in the original partition even when the UI has just left it. */
    suspend fun posted(expected: LedgerWriteEntity, serverId: Long): LedgerWriteEntity?
    suspend fun failed(expected: LedgerWriteEntity)
    /** One cache row and exact queue deletion in one transaction; no full-snapshot replacement. */
    suspend fun acknowledge(expected: LedgerWriteEntity, remote: RemoteTransaction, current: () -> Boolean): Boolean
}

/** Never scans another ledger queue. Already-posted requests can only call GET. */
internal class OriginalLedgerWriteRecovery(
    private val store: OriginalLedgerWriteStore,
    private val current: () -> Boolean,
    private val role: suspend () -> Int,
    private val upload: suspend (LedgerWriteEntity) -> RemoteTransaction,
    private val read: suspend (Long, Long) -> RemoteTransaction
) {
    private fun checkCurrent() { check(current()) { "原登录会话已变化，原请求保留" } }

    suspend fun recover(command: OriginalTransactionCommand): OriginalTransactionRecoveryResult {
        require(command.target.ledgerId > 0)
        currentCoroutineContext().ensureActive()
        if (!current()) return OriginalTransactionRecoveryResult.PAUSED
        val loaded = store.read(command.target.ledgerId)
        if (!matchesOriginalLedgerWrite(command, loaded)) return OriginalTransactionRecoveryResult.CHANGED
        var original: LedgerWriteEntity = requireNotNull(loaded)
        try {
            val access = role(); checkCurrent()
            require(access in 1..4 && (original.state == LedgerWriteEntity.POSTED || access in 1..3)) {
                "当前成员只能查看原请求，不能重新提交"
            }
            val fresh = store.read(original.ledgerId); checkCurrent()
            if (!matchesOriginalLedgerWrite(command, fresh)) return OriginalTransactionRecoveryResult.CHANGED
            original = requireNotNull(fresh)
            if (original.state == LedgerWriteEntity.PENDING) {
                require(JSONObject(original.payloadJson).getInt("type") in 1..4) { "原流水种类无法核验，原请求保留" }
                require(JSONObject(original.payloadJson).getInt("type") != TransactionRepository.TYPE_MODIFY_BALANCE) {
                    "原余额调整的目标余额与差额须先专用核验，原请求保留"
                }
                // The role and exact immutable key/payload are checked again after the await.
                require(role() in 1..3); checkCurrent()
                val beforePost = store.read(original.ledgerId); checkCurrent()
                if (beforePost != original) return OriginalTransactionRecoveryResult.CHANGED
                currentCoroutineContext().ensureActive()
                val receipt = upload(original)
                require(receipt.id > 0 && receipt.ledgerId == original.ledgerId) { "原写入回执账本不一致" }
                // Do this before any GET or account/session check: the known receipt is durable.
                val posted = withContext(NonCancellable) { store.posted(original, receipt.id) }
                    ?: return OriginalTransactionRecoveryResult.CHANGED
                original = posted
                if (!current()) return OriginalTransactionRecoveryResult.PAUSED
            }
            require(original.state == LedgerWriteEntity.POSTED && (original.serverId ?: 0) > 0)
            val receiptId = requireNotNull(original.serverId)
            if (command.expectedReceiptId != null) require(receiptId == command.expectedReceiptId)
            checkCurrent(); require(role() in 1..4); checkCurrent()
            val beforeGet = store.read(original.ledgerId); checkCurrent()
            if (beforeGet != original) return OriginalTransactionRecoveryResult.CHANGED
            val remote = read(receiptId, original.ledgerId)
            checkCurrent()
            require(remote.id == receiptId && remote.ledgerId == original.ledgerId) { "读取结果不是原流水" }
            require(role() in 1..4); checkCurrent()
            currentCoroutineContext().ensureActive()
            return if (store.acknowledge(original, remote, current)) OriginalTransactionRecoveryResult.ACKNOWLEDGED
                else OriginalTransactionRecoveryResult.CHANGED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A fixed metadata-only message; never publish an API body, amount, token or comment.
            withContext(NonCancellable) { store.failed(original) }
            throw error
        }
    }
}
