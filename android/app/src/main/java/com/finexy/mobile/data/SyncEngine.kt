package com.finexy.mobile.data

import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncResult(
    val pulled: Int,
    val accounts: Int,
    val categories: Int,
    val pushed: Int,
    val waitingForMapping: Int,
    val conflicts: Int,
    val removed: Int
)

/**
 * Pulls server changes and pushes locally-created rows that have enough
 * server identifiers to be represented by the API.
 *
 * Legacy/local-only rows intentionally remain pending until an account and
 * category mapping is selected; silently uploading them to an arbitrary
 * server account would corrupt bookkeeping data.
 */
class SyncEngine(
    private val api: FinexyApi,
    private val repository: TransactionRepository,
    private val autoUpdateExchangeRates: Boolean = true
) {
    suspend fun sync(): SyncResult = withoutSync {
        val aiDelivery = AIReviewDelivery.forApi(repository.aiReview, api).push()
        repository.migrateLegacyDataIfNeeded()
        val templateDelivery = TemplateCreationDelivery(repository) { api.createTemplate(it) }.push()
        val periodicDelivery = deliverPeriodicActions()
        // Legacy review links remain quarantined until explicit original-transaction
        // verification. A normal sync must not resolve the source or clear its link.
        // A create request may have reached the server even when its response was
        // lost. Retry brand-new rows with the stable localId before pulling so the
        // idempotent server response can be attached to the original Room row.
        // Existing edits and deletes still pull first to preserve conflict checks.
        val ledgerDelivery = LedgerWriteDelivery(repository) { write ->
            val payload = JSONObject(write.payloadJson)
            require(payload.getString("ledgerId") == write.ledgerId.toString()) { "待确认写入账本不一致" }
            api.parseWrittenTransaction(api.addTransaction(payload, write.localId)).id
        }.push()
        var pushed = ledgerDelivery.pushed + periodicDelivery.acknowledged + templateDelivery.posted + aiDelivery.posted
        repository.pendingTransactions()
            .filter { local ->
                !local.deleted &&
                    local.reviewItemId == null &&
                    local.serverId == null &&
                    local.sourceAccountId != TransactionEntity.LOCAL_ACCOUNT_ID &&
                    (local.type == TransactionRepository.TYPE_MODIFY_BALANCE || local.categoryId != null) &&
                    (local.type != TransactionRepository.TYPE_TRANSFER || local.destinationAccountId != TransactionEntity.LOCAL_ACCOUNT_ID)
            }
            .forEach { local ->
                val response = api.parseWrittenTransaction(api.addTransaction(local.toApiPayload(), local.localId))
                repository.markSynced(local, response)
                pushed++
            }

        val remote = api.parseTransactionResponse(api.listTransactions())
        val merge = repository.mergeRemote(remote)
        val absence = repository.reconcileRemoteAbsence(remote.map { it.id }.toSet())
        val accounts = api.parseAccountResponse(api.listAccounts())
        repository.mergeAccounts(accounts)
        val categories = api.parseCategoryResponse(api.listCategories())
        repository.mergeCategories(categories)
        repository.mergeTags(api.parseTagResponse(api.listTags()))
        repository.replaceProductAssets(api.listProductAssets())
        // Exchange-rate providers may depend on a third-party central bank.
        // Keep the last verified snapshot if that optional dependency is down.
        if (autoUpdateExchangeRates) {
            runCatching { api.latestExchangeRates() }.onSuccess { repository.replaceExchangeRates(it) }
        }
        repository.replaceAIReviewItems(api.listAIReviewItems())
        // Family ledgers and savings goals ride on optional server features. A
        // legacy server answers 404/400 for these endpoints; keeping the local
        // cache untouched beats presenting an empty ledger or an empty plan.
        val ledgers = api.listLedgersIfEnabled()
        if (ledgers != null) {
            // Fetch every explicit ledger completely before mutating Room. If a
            // single request or validation fails, the last coherent snapshots
            // remain available instead of showing a partly refreshed ledger.
            val ledgerSnapshots = ledgers.associate { ledger ->
                val ledgerAccounts = api.parseAccountResponse(api.listAccounts(ledger.id))
                val ledgerTransactions = api.parseTransactionResponse(api.listTransactions(ledger.id), ledger.id)
                ledger.id to Pair(ledgerAccounts, ledgerTransactions)
            }
            repository.replaceLedgers(ledgers)
            ledgerSnapshots.forEach { (ledgerId, snapshot) ->
                repository.replaceLedgerSnapshot(ledgerId, snapshot.first, snapshot.second)
            }
            // One complete snapshot across every visible ledger, including the
            // implicit default personal ledger, so a single ledger failure can
            // never look like an emptied plan.
            val mergedGoals = ledgers.map { ledger -> api.listSavingsGoals(ledger.id) }
                .flatten() + api.listSavingsGoals(RemoteLedger.DEFAULT_LEDGER_ID)
            repository.replaceSavingsGoals(mergedGoals)
        }
        // Each ledger is fetched completely before mutating its cache. Pending actions were delivered before pulls.
        val periodicErrors = mutableListOf<Exception>()
        for (ledgerId in listOf(0L) + ledgers.orEmpty().map { it.id }) {
            try { refreshPeriodicUnlocked(ledgerId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { periodicErrors += error }
        }
        val pending = repository.pendingTransactions()
        var waiting = 0
        pending.forEach { local ->
            // Legacy AI drafts lack an atomic confirmation snapshot. Keep them
            // for explicit review rather than creating a second ordinary entry.
            if (local.reviewItemId != null && local.serverId == null && !local.deleted) {
                waiting++
                return@forEach
            }
            if (!local.deleted && (local.sourceAccountId == TransactionEntity.LOCAL_ACCOUNT_ID ||
                    (local.type != TransactionRepository.TYPE_MODIFY_BALANCE && local.categoryId == null) ||
                    (local.type == TransactionRepository.TYPE_TRANSFER && local.destinationAccountId == TransactionEntity.LOCAL_ACCOUNT_ID))) {
                waiting++
                return@forEach
            }
            val payload = local.toApiPayload()
            val response = if (local.deleted) {
                local.serverId?.let { api.deleteTransaction(it) }
                null
            } else if (local.serverId == null) {
                api.parseWrittenTransaction(api.addTransaction(payload, local.localId))
            } else {
                api.parseWrittenTransaction(api.modifyTransaction(payload.put("id", local.serverId.toString())))
            }
            repository.markSynced(local, response)
            pushed++
        }
        // An inaccessible queued ledger must not prevent other confirmed work
        // or default-ledger pulls from completing. Preserve the request and
        // report its failure only after the independent sync work is done.
        (ledgerDelivery.errors + periodicDelivery.errors + templateDelivery.errors + aiDelivery.errors + periodicErrors).firstOrNull()?.let { throw it }
        SyncResult(merge.merged, accounts.size, categories.size, pushed, waiting, merge.conflicts + absence.conflicts, absence.removed)
    }

    private suspend fun deliverPeriodicActions() = PeriodicActionDelivery(repository) { action ->
        when (action.action) {
            ScheduledOccurrenceEntity.ACTION_CONFIRM -> api.confirmOccurrence(action.templateId, action.scheduledUnixTime, action.ledgerId)
            else -> {
                api.setOccurrenceDismissed(action.templateId, action.scheduledUnixTime, action.action == ScheduledOccurrenceEntity.ACTION_DISMISS, action.ledgerId)
                0L
            }
        }
    }.push()

    suspend fun refreshPeriodic(ledgerId: Long) = withoutSync { refreshPeriodicUnlocked(ledgerId) }

    suspend fun refreshAIReview(ledgerId: Long, status: Int = 1) = withoutSync {
        repository.aiReview.replaceSnapshot(ledgerId, status, api.listLedgerAIReviewItems(ledgerId, status))
    }

    /** Used by the dedicated durable Worker, without unrelated legacy pulls. */
    suspend fun deliverAIReview(): AIReviewDeliveryResult = withoutSync {
        AIReviewDelivery.forApi(repository.aiReview, api).push()
    }

    /** Persist and schedule first. This method sends only the original confirmed request. */
    suspend fun performTemplateCreation(write: TemplateCreationEntity) = withoutSync {
        val result = TemplateCreationDelivery(object : TemplateCreationStore {
            override suspend fun templateCreations() = repository.templateCreations().filter {
                it.clientSessionId == write.clientSessionId && it.payloadJson == write.payloadJson && it.ledgerId == write.ledgerId && it.templateType == write.templateType
            }
            override suspend fun markTemplateCreationPosted(write: TemplateCreationEntity, receipt: TemplateCreationReceipt) = repository.markTemplateCreationPosted(write, receipt)
            override suspend fun markTemplateCreationFailed(write: TemplateCreationEntity, message: String) = repository.markTemplateCreationFailed(write, message)
        }) { api.createTemplate(it) }.push()
        result.errors.firstOrNull()?.let { throw it }
        refreshPeriodicUnlocked(write.ledgerId)
    }

    private suspend fun refreshPeriodicUnlocked(ledgerId: Long) {
        val templates = api.parseTemplateResponse(api.listTemplates(1, ledgerId), ledgerId)
        val scheduled = api.listScheduledTemplatesIfEnabled(ledgerId)
        val occurrences = scheduled?.let {
            api.listOccurrences(ScheduledOccurrenceEntity.STATUS_PENDING, ledgerId) +
                api.listOccurrences(ScheduledOccurrenceEntity.STATUS_DISMISSED, ledgerId)
        }
        repository.replacePeriodicSnapshot(ledgerId, templates, scheduled, occurrences)
    }

    /** UI queues first and schedules WorkManager before invoking this; receipt persists before refresh. */
    suspend fun performPeriodicAction(action: PeriodicAction) = withoutSync {
        val result = PeriodicActionDelivery(object : PeriodicActionStore {
            override suspend fun pendingPeriodicActions() = repository.pendingPeriodicActions().filter { it == action }
            override suspend fun acknowledgePeriodicAction(action: PeriodicAction, transactionId: Long) = repository.acknowledgePeriodicAction(action, transactionId)
        }) { request ->
            when (request.action) {
                ScheduledOccurrenceEntity.ACTION_CONFIRM -> api.confirmOccurrence(request.templateId, request.scheduledUnixTime, request.ledgerId)
                else -> { api.setOccurrenceDismissed(request.templateId, request.scheduledUnixTime, request.action == ScheduledOccurrenceEntity.ACTION_DISMISS, request.ledgerId); 0L }
            }
        }.push()
        result.errors.firstOrNull()?.let { throw it }
        refreshPeriodicUnlocked(action.ledgerId)
    }

    companion object {
        private val syncMutex = Mutex()
        suspend fun <T> withoutSync(action: suspend () -> T): T = syncMutex.withLock { SyncActivity.during(action) }
    }
}
