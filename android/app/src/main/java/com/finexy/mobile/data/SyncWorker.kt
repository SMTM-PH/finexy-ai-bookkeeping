package com.finexy.mobile.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val expectedIdentity = inputData.getString(KEY_IDENTITY) ?: return Result.failure()
        val namespace = inputData.getString(KEY_STORE_NAMESPACE) ?: DEFAULT_STORE_NAMESPACE
        val store = SecureStore(applicationContext, namespace)
        val databaseName = LedgerScope.databaseName(store, expectedIdentity)
        val repository = TransactionRepository(applicationContext, FinexyDatabase.get(applicationContext, databaseName), databaseName == "finexy.db")
        val now = System.currentTimeMillis()
        val previous = repository.syncStatus() ?: SyncStatusEntity()

        val serverUrl = store.get(FinexyApi.KEY_SERVER_URL).orEmpty()
        val token = store.get(FinexyApi.KEY_TOKEN)
        val localMode = store.get("local_mode") == "true"
        val currentIdentity = LedgerScope.identity(serverUrl, token, localMode)
        if (localMode || token.isNullOrBlank() || serverUrl.isBlank() || currentIdentity != expectedIdentity) {
            repository.updateSyncStatus(previous.copy(
                state = SyncRunState.IDLE,
                message = "当前未登录此账本，切换回来后将继续同步",
                nextRetryAt = 0
            ))
            return Result.success()
        }

        repository.updateSyncStatus(previous.copy(
            state = SyncRunState.RUNNING,
            message = "正在同步…",
            attemptCount = runAttemptCount + 1,
            nextRetryAt = 0,
            lastStartedAt = now
        ))

        return try {
            val result = SyncEngine(FinexyApi(store), repository).sync()
            repository.updateSyncStatus(SyncStatusEntity(
                state = SyncRunState.SUCCEEDED,
                message = result.summary(),
                lastStartedAt = now,
                lastFinishedAt = System.currentTimeMillis()
            ))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val retryable = error is IOException && (error !is ApiException || error.status == 408 || error.status == 429 || error.status >= 500)
            val message = error.message?.takeIf(String::isNotBlank) ?: "网络不可用"
            if (retryable && runAttemptCount < MAX_RETRY_ATTEMPTS - 1) {
                val delay = retryDelayMillis(runAttemptCount)
                repository.updateSyncStatus(previous.copy(
                    state = SyncRunState.RETRYING,
                    message = "同步失败：$message；将在网络恢复后重试",
                    attemptCount = runAttemptCount + 1,
                    nextRetryAt = System.currentTimeMillis() + delay,
                    lastStartedAt = now,
                    lastFinishedAt = System.currentTimeMillis()
                ))
                Result.retry()
            } else {
                repository.updateSyncStatus(previous.copy(
                    state = SyncRunState.FAILED,
                    message = "同步失败：$message；请检查登录或数据后重试",
                    attemptCount = runAttemptCount + 1,
                    nextRetryAt = 0,
                    lastStartedAt = now,
                    lastFinishedAt = System.currentTimeMillis()
                ))
                Result.failure(Data.Builder().putString(KEY_ERROR, message).build())
            }
        }
    }

    companion object {
        const val KEY_IDENTITY = "ledger_identity"
        const val KEY_STORE_NAMESPACE = "secure_store_namespace"
        const val KEY_ERROR = "sync_error"
        const val DEFAULT_STORE_NAMESPACE = "finexy_secure"
        const val MAX_RETRY_ATTEMPTS = 8

        internal fun retryDelayMillis(attempt: Int): Long = min(6 * 60 * 60_000L, 10_000L * (1L shl attempt.coerceIn(0, 10)))
    }
}

object SyncScheduler {
    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueCurrent(context: Context, store: SecureStore, replace: Boolean = false) {
        val serverUrl = store.get(FinexyApi.KEY_SERVER_URL).orEmpty()
        val token = store.get(FinexyApi.KEY_TOKEN)
        if (serverUrl.isBlank() || token.isNullOrBlank() || store.get("local_mode") == "true") return
        enqueue(context, LedgerScope.identity(serverUrl, token, false), store.namespace, replace)
    }

    fun enqueue(context: Context, identity: String, namespace: String = SyncWorker.DEFAULT_STORE_NAMESPACE, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workerData(identity, namespace))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(tag(identity, namespace))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            oneTimeName(identity, namespace),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun schedulePeriodicCurrent(context: Context, store: SecureStore) {
        val serverUrl = store.get(FinexyApi.KEY_SERVER_URL).orEmpty()
        val token = store.get(FinexyApi.KEY_TOKEN)
        if (serverUrl.isBlank() || token.isNullOrBlank() || store.get("local_mode") == "true") return
        val identity = LedgerScope.identity(serverUrl, token, false)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workerData(identity, store.namespace))
            .setConstraints(networkConstraint)
            .addTag(tag(identity, store.namespace))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicName(identity, store.namespace), ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    private fun workerData(identity: String, namespace: String) = Data.Builder()
        .putString(SyncWorker.KEY_IDENTITY, identity)
        .putString(SyncWorker.KEY_STORE_NAMESPACE, namespace)
        .build()

    private fun tag(identity: String, namespace: String) = "finexy-sync-${identity.take(24)}-${namespace.hashCode()}"
    private fun oneTimeName(identity: String, namespace: String) = "${tag(identity, namespace)}-once"
    private fun periodicName(identity: String, namespace: String) = "${tag(identity, namespace)}-periodic"
}

internal fun SyncResult.summary(): String =
    "已同步流水 $pulled 条，服务端删除 $removed 条，账户 $accounts 个，分类 $categories 个，上传 $pushed 条，待映射 $waitingForMapping 条，冲突 $conflicts 条"
