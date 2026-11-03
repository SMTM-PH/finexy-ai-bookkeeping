package com.finexy.mobile.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Device-only staging, deliberately excluded from portable ledger backups. No credentials or raw form. */
internal fun pendingCapturePartition(partition: String, ledgerId: Long) = aiImageDigest(partition.toByteArray(Charsets.UTF_8)) + "-$ledgerId"

internal data class AIPendingCapture(val partitionKey: String, val operationId: String, val ledgerId: Long, val draftId: String,
    val revision: Long, val formHash: String) {
    fun matches(draft: AIReviewDraftEntity?) = draft != null && draft.ledgerId == ledgerId && draft.itemId == 0L &&
        draft.draftId == draftId && draft.revision == revision && aiImageDigest(draft.formJson.toByteArray(Charsets.UTF_8)) == formHash
}

/** Atomic metadata precedes creation of the writable camera file. Files are never discovered by scanning. */
internal class AIPendingCaptureStore(context: Context, private val partition: String, private val ledgerId: Long) {
    private val appContext = context.applicationContext
    private val base = context.filesDir.canonicalFile
    private val root = File(base, "receipt-captures")
    private val directory = File(root, pendingCapturePartition(partition, ledgerId))
    private val journal get() = AtomicFile(File(directory, "pending.json"))
    init { require(partition.isNotBlank() && ledgerId >= 0) }
    private fun ensureDirectory() {
        require(root.canonicalFile == root && root.parentFile == base && directory.canonicalFile.parentFile == root)
        check(directory.isDirectory || directory.mkdirs()) { "无法准备拍照暂存区" }
    }
    private fun validate(capture: AIPendingCapture) {
        require(capture.partitionKey == directory.name && capture.ledgerId == ledgerId && capture.operationId.matches(Regex("[a-f0-9-]{36}")) &&
            runCatching { UUID.fromString(capture.operationId).toString() == capture.operationId }.getOrDefault(false) &&
            capture.draftId.matches(Regex("[A-Za-z0-9-]{1,64}")) && capture.revision > 0 && capture.formHash.matches(Regex("[a-f0-9]{64}")))
    }
    fun file(capture: AIPendingCapture): File {
        validate(capture); ensureDirectory()
        return File(directory, "receipt-${capture.operationId}.jpg").also { require(it.canonicalFile.parentFile == directory.canonicalFile) }
    }
    /** Pure metadata inspection shares the writer lock without creating or repairing staging files. */
    fun inspect(): AIPendingCapture? = synchronized(lock) {
        inspectLedgerPendingCapture(base, partition, ledgerId)
    }
    fun read(): AIPendingCapture? = synchronized(lock) {
        ensureDirectory()
        val bytes = try { journal.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 4096) { "拍照恢复记录不完整" }; output.write(buffer, 0, count) }
            require(output.size() > 0); output.toByteArray()
        } } catch (error: java.io.FileNotFoundException) {
            // An unreadable journal is an error, not an empty staging area that may be overwritten.
            if (journal.baseFile.exists() || File(directory, "pending.json.bak").exists()) throw error
            return@synchronized null
        }
        val row = JSONObject(String(bytes, Charsets.UTF_8))
        require(row.getInt("schema") == 1 && row.getString("partition") == directory.name)
        AIPendingCapture(row.getString("partition"), row.getString("operationId"), row.getLong("ledgerId"), row.getString("draftId"),
            row.getLong("revision"), row.getString("formHash")).also(::validate)
    }
    fun begin(draft: AIReviewDraftEntity): AIPendingCapture = synchronized(lock) {
        require(draft.ledgerId == ledgerId && draft.itemId == 0L); validateAIReviewDraft(draft)
        check(read() == null) { "请先处理未保存的拍照内容" }
        val pending = AIPendingCapture(directory.name, UUID.randomUUID().toString(), ledgerId, draft.draftId, draft.revision,
            aiImageDigest(draft.formJson.toByteArray(Charsets.UTF_8)))
        val row = JSONObject().put("schema", 1).put("partition", directory.name).put("operationId", pending.operationId)
            .put("ledgerId", ledgerId).put("draftId", pending.draftId).put("revision", pending.revision).put("formHash", pending.formHash)
        val stream = journal.startWrite()
        try { stream.write(row.toString().toByteArray(Charsets.UTF_8)); journal.finishWrite(stream) }
        catch (error: Exception) { journal.failWrite(stream); throw error }
        // If creation fails or the process exits here, metadata still exposes a recoverable/explicitly discardable state.
        check(file(pending).createNewFile()) { "拍照暂存文件已存在" }
        pending
    }
    fun discard(capture: AIPendingCapture) = synchronized(lock) {
        require(read() == capture) { "拍照内容已变化，请重新读取" }
        val owned = file(capture)
        // A still-running Camera must not recreate this file after an explicit discard or commit.
        appContext.revokeUriPermission(androidx.core.content.FileProvider.getUriForFile(appContext,
            "${appContext.packageName}.fileprovider", owned),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(!owned.exists() || owned.isFile && owned.delete()) { "拍照内容未能清理，请重试" }
        journal.delete()
        check(read() == null) { "拍照恢复记录未能清理，请重试" }
    }
    companion object {
        private val lock = Any()
        /** Called only by the explicitly confirmed local-data clear. Never crosses an identity partition. */
        fun clearPartition(context: Context, partition: String): Int = synchronized(lock) {
            require(partition.isNotBlank())
            val base = context.filesDir.canonicalFile; val root = File(base, "receipt-captures")
            require(root.canonicalFile == root && root.parentFile == base)
            if (!root.exists()) return@synchronized 0
            val prefix = aiImageDigest(partition.toByteArray(Charsets.UTF_8)) + "-"
            val directories = requireNotNull(root.listFiles()) { "拍照暂存区未能读取，未清空账本" }
                .filter { it.name.startsWith(prefix) && it.name.removePrefix(prefix).matches(Regex("0|[1-9][0-9]*")) }
            // Validate every journal first. Missing/corrupt ownership never becomes permission to erase files.
            val records = directories.mapNotNull { directory ->
                require(directory.isDirectory && directory.canonicalFile.parentFile == root)
                val store = AIPendingCaptureStore(context, partition, directory.name.removePrefix(prefix).toLong())
                val capture = store.read()
                check(capture != null || requireNotNull(directory.listFiles()).none { it.name.endsWith(".jpg") }) { "拍照归属记录缺失，请先核对暂存内容" }
                capture?.let { store to it }
            }
            records.forEach { (store, capture) -> store.discard(capture) }
            records.size
        }
    }
}
