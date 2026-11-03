package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LedgerPendingCaptureInspectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val partition = "synthetic-qa.db"
    private val operation = "b8a11e38-6b52-4c77-93c2-4e256a05d901"
    private fun directory() = File(temporary.root, "receipt-captures/${pendingCapturePartition(partition, 9)}")
    private fun journal(ledger: Long = 9, revision: JsonPrimitive = JsonPrimitive(1)) = buildJsonObject {
        put("schema", 1); put("partition", pendingCapturePartition(partition, 9)); put("operationId", operation)
        put("ledgerId", ledger); put("draftId", "original-draft"); put("revision", revision); put("formHash", "ab".repeat(32))
    }.toString()
    private fun write(name: String, content: String): File = File(directory().apply { mkdirs() }, name).apply { writeText(content) }

    @Test fun emptyInspectionCreatesNoDirectoriesOrFiles() {
        assertNull(inspectLedgerPendingCapture(temporary.root, partition, 9))
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun readingBackupDoesNotRenameDeleteOrRewriteAnyFile() {
        val base = write("pending.json", journal(revision = JsonPrimitive(2)))
        val backup = write("pending.json.bak", journal())
        val image = write("receipt-$operation.jpg", "image-body-is-not-journal-data")
        val before = listOf(base, backup, image).associateWith { it.readBytes().toList() to it.lastModified() }
        val capture = inspectLedgerPendingCapture(temporary.root, partition, 9)!!
        assertEquals(1L, capture.revision); assertEquals(operation, capture.operationId)
        before.forEach { (file, content) -> assertTrue(file.exists()); assertEquals(content, file.readBytes().toList() to file.lastModified()) }
        assertEquals(3, directory().listFiles()!!.size)
    }

    @Test fun inProgressOnlyJournalAndUnreadableMetadataNeverBecomeEmpty() {
        write("pending.json.new", journal())
        assertThrows(LedgerPendingUnavailableException::class.java) { inspectLedgerPendingCapture(temporary.root, partition, 9) }
        write("pending.json", "{corrupt")
        assertThrows(RuntimeException::class.java) { inspectLedgerPendingCapture(temporary.root, partition, 9) }
    }

    @Test fun wrongScopeFractionalRevisionAndOversizedMetadataAreRejected() {
        val file = write("pending.json", journal(ledger = 10))
        assertThrows(IllegalArgumentException::class.java) { inspectLedgerPendingCapture(temporary.root, partition, 9) }
        file.writeText(journal(revision = JsonPrimitive(1.5)))
        assertThrows(IllegalStateException::class.java) { inspectLedgerPendingCapture(temporary.root, partition, 9) }
        file.writeText("x".repeat(4097))
        assertThrows(IllegalArgumentException::class.java) { inspectLedgerPendingCapture(temporary.root, partition, 9) }
        assertEquals(4097L, file.length())
    }
}
