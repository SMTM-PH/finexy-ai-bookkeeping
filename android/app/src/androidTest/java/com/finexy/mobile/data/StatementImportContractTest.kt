package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class StatementImportContractTest {
    @Test fun encryptedAlipayArchiveRequiresPasswordAndContainsOneCsv() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, "test-password".toCharArray()).use { zip ->
            val parameters = ZipParameters().apply {
                fileNameInZip = "支付宝交易明细(2026).csv"
                compressionMethod = CompressionMethod.DEFLATE
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.ZIP_STANDARD
            }
            zip.putNextEntry(parameters)
            zip.write("交易时间,金额\n".toByteArray())
            zip.closeEntry()
        }
        val archive = output.toByteArray()
        assertThrows(Exception::class.java) { decodeStatementFile("账单.zip", archive, "wrong", "alipay") }
        val csv = decodeStatementFile("账单.zip", archive, "test-password", "alipay")
        assertEquals("alipay_app_csv", csv.fileType)
        assertTrue(csv.bytes.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { decodeStatementFile("账单.zip", archive, "test-password", "wechat") }
    }

    @Test fun previewRequiresExplicitValidAccountAndLeafCategory() {
        val raw = JSONObject().put("type", 3).put("time", 1_780_000_000).put("utcOffset", 480)
            .put("sourceAmount", 12345).put("categoryId", "0").put("sourceAccountId", "0")
            .put("originalCategoryName", "餐饮").put("originalSourceAccountName", "余额")
        val item = StatementRow.from(raw)
        val accounts = listOf(AccountEntity(12, "支付宝", "CNY"))
        val categories = listOf(CategoryEntity(20, "生活", type = 2), CategoryEntity(21, "餐饮", parentId = 20, type = 2))
        assertFalse(item.ready(accounts, categories))
        val mapped = item.copy(sourceAccountId = 12, categoryId = 21)
        assertTrue(mapped.ready(accounts, categories))
        assertEquals("12", mapped.toPayload(0).getString("sourceAccountId"))
        assertEquals("21", mapped.toPayload(0).getString("categoryId"))
        assertFalse(mapped.ready(accounts, categories.map { if (it.id == 21L) it.copy(hidden = true) else it }))
    }
}
