package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    @Test fun offlineFailurePersistsRetryAndFinalAttemptStops() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "sync-worker-${UUID.randomUUID()}"
        val store = SecureStore(context, namespace)
        store.put(FinexyApi.KEY_SERVER_URL, "http://10.0.2.2:59999", durable = true)
        store.put(FinexyApi.KEY_TOKEN, "test-token-${UUID.randomUUID()}", durable = true)
        store.put("local_mode", "false", durable = true)
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        val databaseName = LedgerScope.databaseName(store, identity)
        val input = Data.Builder().putString(SyncWorker.KEY_IDENTITY, identity)
            .putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build()
        try {
            val retryWorker = TestListenableWorkerBuilder<SyncWorker>(context)
                .setInputData(input).setRunAttemptCount(0).build()
            assertTrue(retryWorker.doWork() is ListenableWorker.Result.Retry)
            val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            val retry = repository.syncStatus()!!
            assertEquals(SyncRunState.RETRYING, retry.state)
            assertEquals(1, retry.attemptCount)
            assertTrue(retry.nextRetryAt > System.currentTimeMillis())

            val finalWorker = TestListenableWorkerBuilder<SyncWorker>(context)
                .setInputData(input).setRunAttemptCount(SyncWorker.MAX_RETRY_ATTEMPTS - 1).build()
            assertTrue(finalWorker.doWork() is ListenableWorker.Result.Failure)
            val failed = repository.syncStatus()!!
            assertEquals(SyncRunState.FAILED, failed.state)
            assertEquals(SyncWorker.MAX_RETRY_ATTEMPTS, failed.attemptCount)
            assertEquals(0L, failed.nextRetryAt)
        } finally {
            FinexyDatabase.closeInstance(databaseName)
            context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(namespace)
        }
    }

    @Test fun workerNeverSyncsADifferentCurrentIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "sync-worker-identity-${UUID.randomUUID()}"
        val store = SecureStore(context, namespace)
        store.put(FinexyApi.KEY_SERVER_URL, "http://10.0.2.2:59999", durable = true)
        store.put(FinexyApi.KEY_TOKEN, "current-token", durable = true)
        store.put("local_mode", "false", durable = true)
        val expectedIdentity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), "old-token", false)
        val databaseName = LedgerScope.databaseName(store, expectedIdentity)
        val input = Data.Builder().putString(SyncWorker.KEY_IDENTITY, expectedIdentity)
            .putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build()
        try {
            val worker = TestListenableWorkerBuilder<SyncWorker>(context).setInputData(input).build()
            assertTrue(worker.doWork() is ListenableWorker.Result.Success)
            val status = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false).syncStatus()!!
            assertEquals(SyncRunState.IDLE, status.state)
            assertTrue(status.message.contains("未登录此账本"))
        } finally {
            FinexyDatabase.closeInstance(databaseName)
            context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(namespace)
        }
    }
}
