package com.finexy.mobile.data

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

/** QA-only source and witness; sync behavior remains in the production SyncWorker. */
class SyncRecoveryProbeWorker(context: Context, private val parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val namespace = requireNotNull(inputData.getString(SyncWorker.KEY_STORE_NAMESPACE))
        val file = evidenceFile(applicationContext, namespace)
        val evidence = JSONObject(file.readText(Charsets.UTF_8))
        evidence.put("workerPid", Process.myPid()).put("start", SystemClock.elapsedRealtime())
            .put("finish", 0).put("state", "RUNNING")
        writeEvidence(file, evidence)
        if (evidence.getInt("preparePid") == Process.myPid()) {
            evidence.put("finish", SystemClock.elapsedRealtime()).put("state", "INVALID_PROCESS")
            writeEvidence(file, evidence)
            return Result.failure()
        }
        return try {
            val result = SyncWorker(applicationContext, parameters).doWork()
            val state = when (result) {
                Result.success(result.outputData) -> "SUCCEEDED"
                Result.failure(result.outputData) -> "FAILED"
                else -> "RETRY"
            }
            evidence.put("finish", SystemClock.elapsedRealtime()).put("state", state)
            writeEvidence(file, evidence)
            result
        } catch (cancelled: CancellationException) {
            evidence.put("finish", SystemClock.elapsedRealtime()).put("state", "CANCELLED")
            writeEvidence(file, evidence)
            throw cancelled
        } catch (error: Throwable) {
            // Never persist messages, request bodies, credentials or finance data in the witness.
            evidence.put("finish", SystemClock.elapsedRealtime()).put("state", "FAILED")
            writeEvidence(file, evidence)
            throw error
        }
    }

    companion object {
        const val QA_PACKAGE = "com.finexy.mobile.qa"
        const val NAMESPACE_PREFIX = "ledger-write-recovery-"
        private val namespacePattern = Regex("${NAMESPACE_PREFIX}[a-f0-9]{16}")

        fun evidenceFile(context: Context, namespace: String): File {
            require(context.packageName == QA_PACKAGE) { "Recovery witness requires the isolated QA package" }
            require(namespacePattern.matches(namespace)) { "Recovery namespace must have a random 16-character hex suffix" }
            return File(File(context.filesDir, "sync-recovery"), "$namespace.json")
        }

        fun prepareEvidence(context: Context, namespace: String) {
            val file = evidenceFile(context, namespace)
            require(!file.exists()) { "Use a fresh recovery namespace" }
            writeEvidence(file, JSONObject().put("preparePid", Process.myPid()).put("workerPid", 0)
                .put("start", 0).put("finish", 0).put("state", "PREPARED"))
        }

        private fun writeEvidence(file: File, evidence: JSONObject) {
            // The file is intentionally readable by QA run-as; its fixed schema has no secrets.
            require(evidence.keys().asSequence().toSet() == setOf("preparePid", "workerPid", "start", "finish", "state"))
            require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create recovery evidence directory" }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(evidence.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }
}
