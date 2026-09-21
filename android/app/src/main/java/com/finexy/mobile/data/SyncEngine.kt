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
    suspend fun sync(): SyncResult = syncMutex.withLock {
        repository.migrateLegacyDataIfNeeded()
        resolvePostedAIReviews()
        // A create request may have reached the server even when its response was
        // lost. Retry brand-new rows with the stable localId before pulling so the
        // idempotent server response can be attached to the original Room row.
        // Existing edits and deletes still pull first to preserve conflict checks.
        var pushed = 0
        repository.pendingTransactions()
            .filter { local ->
                !local.deleted &&
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
        repository.mergeTemplates(api.parseTemplateResponse(api.listTemplates()))
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
            val familyGroups = api.listFamilyGroupsIfEnabled()
            if (familyGroups != null) {
                repository.replaceFamilyGroups(familyGroups)
                familyGroups.forEach { group ->
                    repository.replaceFamilyMembers(group.id, api.listFamilyMembers(group.id))
                }
            }
            // One complete snapshot across every visible ledger, including the
            // implicit default personal ledger, so a single ledger failure can
            // never look like an emptied plan.
            val mergedGoals = ledgers.map { ledger -> api.listSavingsGoals(ledger.id) }
                .flatten() + api.listSavingsGoals(RemoteLedger.DEFAULT_LEDGER_ID)
            repository.replaceSavingsGoals(mergedGoals)
        }
        // The review queue rides on the same feature flag; a disabled schedule
        // feature (HTTP 400 / 210003) leaves templates and the local queue
        // untouched instead of looking like an empty server state.
        api.listScheduledTemplatesIfEnabled()?.let { templates ->
            repository.replaceScheduledTemplates(templates)
            val pending = api.listOccurrences(ScheduledOccurrenceEntity.STATUS_PENDING)
            val dismissed = api.listOccurrences(ScheduledOccurrenceEntity.STATUS_DISMISSED)
            repository.replaceOccurrences(pending + dismissed)
        }
        val pending = repository.pendingTransactions()
        var waiting = 0
        pending.forEach { local ->
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
        resolvePostedAIReviews()
        SyncResult(merge.merged, accounts.size, categories.size, pushed, waiting, merge.conflicts + absence.conflicts, absence.removed)
    }

    private suspend fun resolvePostedAIReviews() {
        repository.pendingAIReviewResolutions().forEach { transaction ->
            api.resolveAIReviewItem(requireNotNull(transaction.reviewItemId))
            repository.markAIReviewResolved(transaction)
        }
    }

    companion object {
        private val syncMutex = Mutex()
        suspend fun <T> withoutSync(action: suspend () -> T): T = syncMutex.withLock { action() }
    }
}
