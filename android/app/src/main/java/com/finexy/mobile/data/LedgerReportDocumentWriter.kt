package com.finexy.mobile.data

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A provider accepting write() is insufficient: close and independently re-read its actual bytes. */
internal object LedgerReportDocumentWriter {
    fun writeAndVerify(bytes: ByteArray, isCurrent: () -> Boolean,
        openOutput: () -> OutputStream?, openInput: () -> InputStream?) {
        require(bytes.size <= LedgerReportContract.MAX_DOWNLOAD_BYTES)
        check(isCurrent())
        openOutput()?.use { output ->
            for (offset in bytes.indices step 8192) {
                check(isCurrent()); output.write(bytes, offset, minOf(8192, bytes.size - offset))
            }
            output.flush()
        } ?: error("所选文件不可写")
        check(isCurrent())
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        openInput()?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                check(isCurrent())
                val count = input.read(buffer); if (count < 0) break
                size += count; check(size <= bytes.size.toLong())
                digest.update(buffer, 0, count)
            }
        } ?: error("所选文件不可重新读取")
        check(size == bytes.size.toLong() && digest.digest().contentEquals(expected))
        check(isCurrent())
    }
}
