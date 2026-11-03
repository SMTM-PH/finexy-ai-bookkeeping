package com.finexy.mobile.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** This worker opens only the persisted server/user partition, never the selected UI ledger. */
class AIReviewWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val identity = inputData.getString(SyncWorker.KEY_IDENTITY) ?: return Result.failure()
        val namespace = inputData.getString(SyncWorker.KEY_STORE_NAMESPACE) ?: SyncWorker.DEFAULT_STORE_NAMESPACE
        val store = SecureStore(applicationContext, namespace)
        val actual = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), store.get("local_mode") == "true")
        if (actual != identity || actual == "local") return Result.success()
        val name = LedgerScope.databaseName(store, identity)
        val repository = TransactionRepository(applicationContext, FinexyDatabase.get(applicationContext, name), false)
        return try {
            val errors = SyncEngine(FinexyApi(store), repository, false).deliverAIReview().errors
            if (errors.isEmpty()) Result.success()
            else if (runAttemptCount < SyncWorker.MAX_RETRY_ATTEMPTS - 1 && errors.any {
                    it is IOException && (it !is ApiException || it.status == 408 || it.status == 429 || it.status >= 500)
                }) Result.retry()
            else Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) {
            if (runAttemptCount < SyncWorker.MAX_RETRY_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }
}

object AIReviewScheduler {
    /** Call after Room commits; login/resume can call again to recover a scheduling interruption. */
    fun enqueueCurrent(context: Context, store: SecureStore) {
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), store.get("local_mode") == "true")
        if (identity == "local") return
        val request = OneTimeWorkRequestBuilder<AIReviewWorker>()
            .setInputData(Data.Builder().putString(SyncWorker.KEY_IDENTITY, identity)
                .putString(SyncWorker.KEY_STORE_NAMESPACE, store.namespace).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("finexy-ai-$identity-${store.namespace.hashCode()}", ExistingWorkPolicy.KEEP, request)
    }
}
