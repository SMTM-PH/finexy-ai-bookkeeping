package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/**
 * Reads committed journal metadata without AtomicFile.openRead (which can rename a backup),
 * mkdirs, discovering images, or opening the camera file. A .new-only journal is not empty.
 * AIPendingCaptureStore.inspect can call this under its writer lock when that entry point lands.
 */
internal fun inspectLedgerPendingCapture(filesDirectory: File, partition: String, ledgerId: Long): AIPendingCapture? {
    require(partition.isNotBlank() && ledgerId >= 0)
    val base = filesDirectory.canonicalFile
    require(base.isDirectory && base.canRead())
    val root = File(base, "receipt-captures")
    val directory = File(root, pendingCapturePartition(partition, ledgerId))
    require(root.canonicalFile == root && root.parentFile == base && directory.canonicalFile == directory)
    if (root.exists()) require(root.isDirectory && root.canRead())
    if (directory.exists()) require(directory.isDirectory && directory.canRead())
    val journal = File(directory, "pending.json")
    val backup = File(directory, "pending.json.bak")
    // Legacy AtomicFile restores the backup first; inspecting those bytes preserves its semantics.
    val committed = when {
        backup.exists() -> backup
        journal.exists() -> journal
        File(directory, "pending.json.new").exists() -> throw LedgerPendingUnavailableException()
        else -> return null
    }
    require(committed.canonicalFile == committed && committed.parentFile == directory && committed.isFile && committed.canRead())
    val bytes = committed.inputStream().use { input ->
        val buffer = ByteArray(4097)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        require(size in 1..4096)
        buffer.copyOf(size)
    }
    val row = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("拍照恢复记录不完整")
    require(row.keys == setOf("schema", "partition", "operationId", "ledgerId", "draftId", "revision", "formHash"))
    fun text(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("拍照恢复记录不完整")
    fun number(key: String): Long = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: error("拍照恢复记录不完整")
    require(number("schema") == 1L && text("partition") == directory.name && number("ledgerId") == ledgerId)
    val capture = AIPendingCapture(text("partition"), text("operationId"), ledgerId, text("draftId"), number("revision"), text("formHash"))
    require(runCatching { UUID.fromString(capture.operationId).toString() == capture.operationId }.getOrDefault(false))
    require(capture.draftId.matches(Regex("[A-Za-z0-9-]{1,64}")) && capture.revision > 0 && capture.formHash.matches(Regex("[a-f0-9]{64}")))
    return capture
}
