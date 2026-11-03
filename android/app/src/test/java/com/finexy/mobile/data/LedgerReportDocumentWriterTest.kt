package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LedgerReportDocumentWriterTest {
    private val bytes = ByteArray(25000) { (it % 127).toByte() }

    @Test fun verifiesOnlyAfterProviderHasClosedItsOutput() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { super.close(); closed = true } }
        LedgerReportDocumentWriter.writeAndVerify(bytes, { true }, { output }, {
            assertTrue(closed); ByteArrayInputStream(output.toByteArray())
        })
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun staleContextBeforeWriteNeverOpensOrTruncatesDocument() {
        var opened = false
        assertThrows(IllegalStateException::class.java) {
            LedgerReportDocumentWriter.writeAndVerify(bytes, { false }, { opened = true; ByteArrayOutputStream() }, { ByteArrayInputStream(bytes) })
        }
        assertFalse(opened)
    }

    @Test fun contextChangingDuringWriteStopsBeforeNextChunkAndClosesOutput() {
        var current = true; var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, count: Int) { super.write(buffer, offset, count); current = false }
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IllegalStateException::class.java) {
            LedgerReportDocumentWriter.writeAndVerify(bytes, { current }, { output }, { error("must not read stale file") })
        }
        assertTrue(closed); assertEquals(8192, output.size())
    }

    @Test fun sameLengthCorruptionTruncationAndUnexpectedSuffixCannotReportSuccess() {
        for (actual in listOf(bytes.copyOf().also { it[12000] = 0 }, bytes.copyOf(1000), bytes + byteArrayOf(7))) {
            assertThrows(IllegalStateException::class.java) {
                LedgerReportDocumentWriter.writeAndVerify(bytes, { true }, { ByteArrayOutputStream() }, { ByteArrayInputStream(actual) })
            }
        }
    }

    @Test fun providerFailureAndMissingReadbackPropagateAsFailure() {
        assertThrows(IOException::class.java) {
            LedgerReportDocumentWriter.writeAndVerify(bytes, { true }, { throw IOException("provider failure") }, { ByteArrayInputStream(bytes) })
        }
        assertThrows(IllegalStateException::class.java) {
            LedgerReportDocumentWriter.writeAndVerify(bytes, { true }, { ByteArrayOutputStream() }, { null })
        }
    }
}
