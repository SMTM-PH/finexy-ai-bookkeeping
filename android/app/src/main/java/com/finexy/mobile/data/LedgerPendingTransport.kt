package com.finexy.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Production read adapter, permanently bound to the repository's original server/user partition. */
internal class FinexyLedgerPendingTransport(
    context: Context, private val store: SecureStore, private val repository: TransactionRepository
) : LedgerPendingTransport {
    private val appContext = context.applicationContext
    private val partition = repository.aiReview.storagePartition
    private val originalServer = store.get(FinexyApi.KEY_SERVER_URL).orEmpty().trimEnd('/')
    private val originalMode = store.get("local_mode") == "true"
    private val originalIdentity = LedgerScope.identity(originalServer, store.get(FinexyApi.KEY_TOKEN), originalMode)

    /** Null means this repository cannot serve the selected session; construct a fresh adapter. */
    fun context(ledgerId: Long): LedgerPendingContext? = runCatching {
        val revision = store.sessionRevision
        val server = store.get(FinexyApi.KEY_SERVER_URL).orEmpty().trimEnd('/')
        val local = store.get("local_mode") == "true"
        val token = store.get(FinexyApi.KEY_TOKEN)
        val identity = LedgerScope.identity(server, token, local)
        check(local || !token.isNullOrBlank())
        check(server == originalServer && identity == originalIdentity && local == originalMode)
        check(partition == repository.aiReview.storagePartition && partition == LedgerScope.databaseName(store, identity))
        LedgerPendingContext(store.namespace, if (local) "local://device" else server, identity, partition,
            ledgerId, revision, local).also { check(store.sessionRevision == revision) }
    }.getOrNull()

    private fun checkContext(context: LedgerPendingContext) {
        check(this.context(context.ledgerId) == context) { "待处理所属会话已变化" }
    }
    private fun api(context: LedgerPendingContext): FinexyApi {
        checkContext(context)
        if (context.localMode) throw LedgerPendingUnavailableException()
        return FinexyApi(store).also {
            val captured = it.captureLedgerReportContext(context.ledgerId)
            check(captured.serverUrl == context.serverUrl && captured.identity == context.identity)
            checkContext(context)
        }
    }

    override suspend fun role(context: LedgerPendingContext): Int = withContext(Dispatchers.IO) {
        checkContext(context)
        if (context.localMode) {
            require(context.ledgerId == 0L)
            // The bound device partition owns its local rows; online sources stay unavailable.
            return@withContext RemoteLedgerMember.ROLE_OWNER
        }
        val api = api(context)
        val role = if (context.ledgerId == 0L) {
            // periodicRole(0) is a constant owner shortcut; a live authenticated read is required.
            val profile = AccountSecurity(api).profile()
            require(profile.getString("username").isNotBlank())
            RemoteLedgerMember.ROLE_OWNER
        } else {
            val members = api.listLedgerMembers(context.ledgerId)
            require(members.all { it.ledgerId == context.ledgerId })
            val own = members.filter { it.isCurrentUser && it.status == 1 }.singleOrNull()
                ?: throw LedgerPendingDeniedException()
            own.role.also { require(it in RemoteLedgerMember.ROLE_OWNER..RemoteLedgerMember.ROLE_VIEWER) }
        }
        checkContext(context)
        role
    }

    override suspend fun read(context: LedgerPendingContext, source: LedgerPendingSource): List<LedgerPendingRecord> = withContext(Dispatchers.IO) {
        checkContext(context)
        val ledger = context.ledgerId
        val rows = when (source) {
            LedgerPendingSource.AI_QUEUE -> api(context).listLedgerAIReviewItems(ledger, 1).also { items ->
                require(items.size <= 10_000 && items.all { it.ledgerId == ledger && it.status == 1 && !it.deleted })
            }.map { LedgerPendingRecord(LedgerPendingTarget.AIItem(ledger, it.id), LedgerPendingKind.REMOTE_PENDING) }
            LedgerPendingSource.OCCURRENCES -> api(context).listOccurrences(ScheduledOccurrenceEntity.STATUS_PENDING, ledger).also { items ->
                require(items.size <= 10_000 && items.all { it.ledgerId == ledger && it.status == ScheduledOccurrenceEntity.STATUS_PENDING })
            }.map { LedgerPendingRecord(LedgerPendingTarget.Occurrence(ledger, it.templateId, it.scheduledUnixTime), LedgerPendingKind.REMOTE_PENDING) }
            LedgerPendingSource.AI_REQUESTS -> repository.aiReview.requests().filter { it.ledgerId == ledger }.map(::pendingAIRequestRecord)
            LedgerPendingSource.AI_DRAFTS -> repository.aiReview.observeDrafts(ledger).first().also { items ->
                require(items.all { it.ledgerId == ledger })
            }.map(::pendingAIDraftRecord)
            LedgerPendingSource.LEDGER_WRITES -> if (ledger == 0L) emptyList() else
                repository.ledgerWrites().filter { it.ledgerId == ledger }.map(::pendingLedgerWriteRecord)
            LedgerPendingSource.LOCAL_TRANSACTIONS -> if (ledger != 0L) emptyList() else
                repository.ledgerPendingTransactions().mapNotNull(::pendingTransactionRecord)
            LedgerPendingSource.TEMPLATE_REQUESTS -> repository.templateCreations().filter { it.ledgerId == ledger }.map(::pendingTemplateRequestRecord)
            LedgerPendingSource.TEMPLATE_DRAFTS -> (1..2).mapNotNull { repository.templateDraft(ledger, it) }.also { items ->
                require(items.all { it.ledgerId == ledger })
            }.map(::pendingTemplateDraftRecord)
            LedgerPendingSource.PERIODIC_ACTIONS -> repository.pendingPeriodicActions().filter { it.ledgerId == ledger }.map(::pendingPeriodicActionRecord)
            LedgerPendingSource.CAPTURE -> listOfNotNull(AIPendingCaptureStore(appContext, partition, ledger).inspect()).map(::pendingCaptureRecord)
            LedgerPendingSource.LEGACY_AI_CACHE -> if (ledger != 0L) emptyList() else
                repository.observeAIReviewItems().first().map(::pendingLegacyAIRecord)
        }
        checkContext(context)
        val snapshot = LedgerPendingSourceState(LedgerPendingAvailability.COMPLETE, rows)
        aggregateLedgerPending(context, mapOf(source to snapshot)) // rejects duplicate, unbounded or cross-ledger records
        rows
    }

    override suspend fun contains(context: LedgerPendingContext, target: LedgerPendingTarget): Boolean {
        checkContext(context)
        require(target.ledgerId == context.ledgerId)
        if (target is LedgerPendingTarget.AIItem) {
            val item = api(context).getLedgerAIReviewItem(context.ledgerId, target.itemId)
            checkContext(context)
            return item.ledgerId == target.ledgerId && item.id == target.itemId && item.status == 1 && !item.deleted
        }
        // Local locators include the exact immutable request or raw editor revision/hash.
        return read(context, target.source()).any { it.target == target }
    }
}
