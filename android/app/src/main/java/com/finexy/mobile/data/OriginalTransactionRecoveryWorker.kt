package com.finexy.mobile.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A pinned original operation; it never invokes a general sync or discovers another queue. */
class OriginalTransactionRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val command = runCatching { originalTransactionCommand(inputData) }.getOrNull() ?: return Result.failure()
        val store = SecureStore(applicationContext, command.namespace)
        val actual = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN),
            store.get("local_mode") == "true")
        if (actual != command.identity || actual == "local") return Result.success()
        val partition = LedgerScope.databaseName(store, actual)
        if (partition != command.storagePartition) return Result.failure()
        val repository = TransactionRepository(applicationContext, FinexyDatabase.get(applicationContext, partition), false)
        return try {
            recoverOriginalTransaction(applicationContext, store, repository, command)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val retryable = error is IOException && (error !is ApiException || error.status == 408 || error.status == 429 || error.status >= 500)
            if (retryable && runAttemptCount < SyncWorker.MAX_RETRY_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }
}

/** Enqueue only after an explicit operation; a POSTED UI path calls GET directly, never this. */
internal object OriginalTransactionRecoveryScheduler {
    fun enqueue(context: Context, command: OriginalTransactionCommand) {
        require(command.expectedReceiptId == null)
        val work = OneTimeWorkRequestBuilder<OriginalTransactionRecoveryWorker>()
            .setInputData(originalTransactionData(command))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("finexy-original-${ledgerPendingDigest(command.namespace, command.identity)}")
            .build()
        val name = ledgerPendingDigest(command.namespace, command.serverUrl, command.identity, command.storagePartition,
            command.target.ledgerId, command.target.localId, command.target.revision, command.target.snapshotHash)
        WorkManager.getInstance(context).enqueueUniqueWork("finexy-original-$name", ExistingWorkPolicy.KEEP, work)
    }
}

private fun originalTransactionData(command: OriginalTransactionCommand): Data = Data.Builder()
    .putString("namespace", command.namespace).putString("server", command.serverUrl)
    .putString("identity", command.identity).putString("partition", command.storagePartition)
    .putLong("ledger", command.target.ledgerId).putString("local", command.target.localId)
    .putLong("revision", command.target.revision).putString("hash", command.target.snapshotHash).build()

private fun originalTransactionCommand(data: Data): OriginalTransactionCommand = OriginalTransactionCommand(
    requireNotNull(data.getString("namespace")), requireNotNull(data.getString("server")),
    requireNotNull(data.getString("identity")), requireNotNull(data.getString("partition")),
    LedgerPendingTarget.Transaction(data.getLong("ledger", -1), requireNotNull(data.getString("local")),
        data.getLong("revision", -1), requireNotNull(data.getString("hash"))))

/** The API keeps its original token; the observer also rejects live A -> B -> A changes. */
internal suspend fun recoverOriginalTransaction(context: Context, store: SecureStore, repository: TransactionRepository,
    command: OriginalTransactionCommand, expectedContext: LedgerPendingContext? = null): OriginalTransactionRecoveryResult {
    val transport = FinexyLedgerPendingTransport(context.applicationContext, store, repository)
    val bound = originalRecoveryContext(command, transport.context(command.target.ledgerId), expectedContext)
        ?: return OriginalTransactionRecoveryResult.PAUSED
    val invalidated = AtomicBoolean(false)
    val observation = store.observeSessionChanges { invalidated.set(true) }
    fun current() = !invalidated.get() && originalRecoveryContext(command, transport.context(command.target.ledgerId), expectedContext) == bound
    val api = FinexyApi(store)
    try {
        if (!current()) return OriginalTransactionRecoveryResult.PAUSED
        return SyncEngine.withoutSync {
            if (command.target.ledgerId > 0) {
                val persistence = object : OriginalLedgerWriteStore {
                    override suspend fun read(ledgerId: Long) = repository.originalLedgerWrite(ledgerId)
                    override suspend fun posted(expected: LedgerWriteEntity, serverId: Long) = repository.markOriginalLedgerWritePosted(expected, serverId)
                    override suspend fun failed(expected: LedgerWriteEntity) = repository.failOriginalLedgerWrite(expected)
                    override suspend fun acknowledge(expected: LedgerWriteEntity, remote: RemoteTransaction, current: () -> Boolean) =
                        repository.acknowledgeOriginalLedgerWrite(expected, remote, current)
                }
                OriginalLedgerWriteRecovery(persistence, ::current, { transport.role(bound) }, { row ->
                    val payload = JSONObject(row.payloadJson)
                    require(payload.getString("ledgerId") == row.ledgerId.toString())
                    api.parseWrittenTransaction(api.addTransaction(payload, row.localId))
                }, api::getTransaction).recover(command)
            } else recoverOriginalLocalTransaction(repository, api, transport, bound, command, ::current)
        }
    } finally { observation.close() }
}

/** One default-ledger row, with baseline conflict checks and durable snapshot acknowledgement. */
private suspend fun recoverOriginalLocalTransaction(repository: TransactionRepository, api: FinexyApi,
    transport: FinexyLedgerPendingTransport, bound: LedgerPendingContext, command: OriginalTransactionCommand,
    current: () -> Boolean): OriginalTransactionRecoveryResult {
    fun checkScope() { check(current()) { "原登录会话已变化，原流水保留" } }
    suspend fun access() { checkScope(); require(transport.role(bound) in 1..3); checkScope(); currentCoroutineContext().ensureActive() }
    checkScope()
    val original = repository.originalTransaction(command.target.localId)
    if (!matchesOriginalTransaction(command.target, original)) return OriginalTransactionRecoveryResult.CHANGED
    var row = requireNotNull(original)
    require(row.reviewItemId == null) { "旧 AI 上传结果必须单独核验" }
    if (row.type !in 1..4) return OriginalTransactionRecoveryResult.REQUIRES_ORIGINAL_VERIFICATION
    val conflict = repository.originalConflict(row.localId)
    if (conflict != null && conflict.resolution != ConflictResolution.KEEP_LOCAL || row.syncState == SyncState.CONFLICT)
        return OriginalTransactionRecoveryResult.CONFLICT
    if (!row.deleted && (row.sourceAccountId == TransactionEntity.LOCAL_ACCOUNT_ID ||
            row.type != TransactionRepository.TYPE_MODIFY_BALANCE && row.categoryId == null ||
            row.type == TransactionRepository.TYPE_TRANSFER && row.destinationAccountId == TransactionEntity.LOCAL_ACCOUNT_ID))
        return OriginalTransactionRecoveryResult.WAITING_FOR_MAPPING
    access()
    // This mutable row is not evidence of the original create payload. Until a
    // read-only submission lookup binds its stable key to an original receipt,
    // replaying it could resend an edit under a previously committed key.
    if (row.serverId == null) return OriginalTransactionRecoveryResult.REQUIRES_ORIGINAL_VERIFICATION
    if (row.serverId != null) {
        val originalServerId = requireNotNull(row.serverId)
        val remote = try { api.getTransaction(originalServerId, 0) }
        catch (error: ApiException) {
            if (error.status != 404 && error.serverCode != 205001) throw error
            // A GET 404 alone might be an unsupported endpoint or lost access. Establish absence
            // using the existing unfiltered, fully paginated reader before changing this row.
            access()
            val complete = api.parseTransactionResponse(api.listTransactions(), 0)
            require(complete.all { it.id > 0 && it.ledgerId == 0L } && complete.map { it.id }.distinct().size == complete.size)
            checkScope()
            if (complete.any { it.id == originalServerId }) throw error
            val reconciled = repository.recordOriginalAbsence(row, current)
            return if (reconciled.syncState == SyncState.CONFLICT) OriginalTransactionRecoveryResult.CONFLICT
                else OriginalTransactionRecoveryResult.ACKNOWLEDGED
        }
        access()
        require(remote.id == originalServerId && remote.ledgerId == 0L)
        row = repository.mergeOriginalTransaction(row, remote, current)
        if (row.syncState == SyncState.SYNCED) return OriginalTransactionRecoveryResult.ACKNOWLEDGED
        if (row.syncState == SyncState.CONFLICT) return OriginalTransactionRecoveryResult.CONFLICT
        require(remote.editable) { "当前权限不允许修改原流水" }
        // A preserved KEEP_LOCAL resolution is the user's explicit choice to overwrite its baseline.
    }
    access()
    val fresh = repository.originalTransaction(row.localId); checkScope()
    if (fresh != row) return OriginalTransactionRecoveryResult.CHANGED
    if (!row.deleted && (row.type == TransactionRepository.TYPE_MODIFY_BALANCE ||
            row.serverId != null && row.type == TransactionRepository.TYPE_TRANSFER && row.sourceAccountId == 0L))
        return OriginalTransactionRecoveryResult.REQUIRES_ORIGINAL_VERIFICATION
    val receipt = when {
        row.deleted -> {
            requireNotNull(row.serverId)
            val deleted = JSONObject(api.deleteTransaction(requireNotNull(row.serverId), 0))
            require(deleted.get("success") == true && deleted.get("result") == true) { "原删除回执无法核验" }
            null
        }
        row.serverId == null -> error("原新增回执尚未通过只读查询绑定，原记录保留")
        else -> api.parseWrittenTransaction(api.modifyTransaction(row.toApiPayload().put("id", requireNotNull(row.serverId).toString()).put("ledgerId", "0")))
    }
    if (receipt != null) require(receipt.id > 0 && receipt.ledgerId == 0L && (row.serverId == null || receipt.id == row.serverId))
    // This acknowledgement binds the upload snapshot and preserves an edit made during the request.
    withContext(NonCancellable) { repository.acknowledgeOriginalLocalSnapshot(row, receipt) }
    return if (current()) OriginalTransactionRecoveryResult.ACKNOWLEDGED else OriginalTransactionRecoveryResult.PAUSED
}
