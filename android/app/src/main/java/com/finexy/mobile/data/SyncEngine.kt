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
    private val repository: TransactionRepository
) {
    suspend fun sync(): SyncResult = syncMutex.withLock {
        repository.migrateLegacyDataIfNeeded()
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
        SyncResult(merge.merged, accounts.size, categories.size, pushed, waiting, merge.conflicts + absence.conflicts, absence.removed)
    }

    companion object {
        private val syncMutex = Mutex()
        suspend fun <T> withoutSync(action: suspend () -> T): T = syncMutex.withLock { action() }
    }
}
