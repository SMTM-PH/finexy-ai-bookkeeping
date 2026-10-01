package com.finexy.mobile.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.inputstream.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

private const val MAX_STATEMENT_BYTES = 20 * 1024 * 1024

private fun InputStream.readBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_STATEMENT_BYTES) { "账单文件超过 20 MB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

data class StatementFile(val name: String, val bytes: ByteArray, val fileType: String)

/** Reads a user-selected file only; ZIP passwords and decrypted CSV bytes stay in memory. */
suspend fun readStatementFile(resolver: ContentResolver, uri: Uri, password: String, provider: String): StatementFile = withContext(Dispatchers.IO) {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: error("无法读取文件名称")
    val raw = resolver.openInputStream(uri)?.use { it.readBounded() } ?: error("无法读取账单文件")
    decodeStatementFile(name, raw, password, provider)
}

internal fun decodeStatementFile(name: String, raw: ByteArray, password: String, provider: String): StatementFile {
    require(provider == "alipay" || provider == "wechat") { "账单来源无效" }
    require(raw.isNotEmpty() && raw.size <= MAX_STATEMENT_BYTES) { "账单文件为空或超过 20 MB" }

    return when {
        name.endsWith(".xlsx", true) && provider == "wechat" -> StatementFile(name, raw, "wechat_pay_app_xlsx")
        name.endsWith(".csv", true) -> StatementFile(name, raw, if (provider == "wechat") "wechat_pay_app_csv" else "alipay_app_csv")
        name.endsWith(".zip", true) && provider == "alipay" -> {
            require(password.isNotBlank()) { "请输入支付宝压缩包密码" }
            ZipInputStream(raw.inputStream(), password.toCharArray()).use { zip ->
                val entry = zip.nextEntry ?: error("压缩包为空")
                require(!entry.isDirectory && entry.fileName.substringAfterLast('/').matches(Regex("支付宝交易明细.*\\.csv", RegexOption.IGNORE_CASE))) {
                    "压缩包应只包含支付宝交易明细 CSV 文件"
                }
                val csv = zip.readBounded()
                require(csv.isNotEmpty() && csv.size <= MAX_STATEMENT_BYTES) { "支付宝 CSV 文件为空或超过 20 MB" }
                require(zip.nextEntry == null) { "压缩包应只包含一份 CSV 文件" }
                StatementFile(entry.fileName.substringAfterLast('/'), csv, "alipay_app_csv")
            }
        }
        else -> error("请选择支付宝 ZIP/CSV 或微信 XLSX/CSV 文件")
    }
}

data class StatementRow(
    val type: Int,
    val time: Long,
    val utcOffset: Int,
    val sourceAmount: Long,
    val destinationAmount: Long,
    val categoryId: Long,
    val sourceAccountId: Long,
    val destinationAccountId: Long,
    val originalCategoryName: String,
    val originalSourceAccountName: String,
    val originalDestinationAccountName: String,
    val comment: String,
    val tagIds: List<String>,
    val selected: Boolean = true
) {
    fun ready(accounts: List<AccountEntity>, categories: List<CategoryEntity>): Boolean {
        val accountIds = accounts.filter { !it.hidden && it.type == 1 && it.id > 0 }.map { it.id }.toSet()
        val categoryIds = categories.filter { !it.hidden && it.parentId > 0 && it.id > 0 && it.type == when (type) { 2 -> 1; 3 -> 2; else -> 3 } }.map { it.id }.toSet()
        val accountsReady = if (type == 4) {
            (sourceAccountId in accountIds || destinationAccountId in accountIds) && sourceAccountId != destinationAccountId &&
                (sourceAccountId == 0L || sourceAccountId in accountIds) && (destinationAccountId == 0L || destinationAccountId in accountIds)
        } else sourceAccountId in accountIds && destinationAccountId == 0L
        return type in 2..4 && time > 0 && utcOffset in -720..840 && sourceAmount in -9_999_999_999_999L..9_999_999_999_999L &&
            accountsReady && categoryId in categoryIds && comment.length <= 255
    }

    fun toPayload(ledgerId: Long): JSONObject = JSONObject()
        .put("ledgerId", ledgerId.toString()).put("type", type).put("time", time).put("utcOffset", utcOffset)
        .put("categoryId", categoryId.toString()).put("sourceAccountId", sourceAccountId.toString())
        .put("destinationAccountId", destinationAccountId.toString()).put("sourceAmount", sourceAmount)
        .put("destinationAmount", if (type == 4) destinationAmount else 0)
        .put("tagIds", JSONArray(tagIds)).put("comment", comment)

    companion object {
        fun from(item: JSONObject): StatementRow {
            val type = item.getInt("type")
            require(type in 2..4) { "账单包含暂不支持的交易类型" }
            val time = item.getLong("time")
            require(time > 0) { "账单包含无效日期" }
            return StatementRow(type, time, item.getInt("utcOffset"), item.getLong("sourceAmount"),
                item.optLong("destinationAmount"), item.optString("categoryId").toLongOrNull() ?: 0L,
                item.optString("sourceAccountId").toLongOrNull() ?: 0L,
                item.optString("destinationAccountId").toLongOrNull() ?: 0L,
                item.optString("originalCategoryName"), item.optString("originalSourceAccountName"),
                item.optString("originalDestinationAccountName"), item.optString("comment"),
                item.optJSONArray("tagIds")?.let { tags -> (0 until tags.length()).map { tags.getString(it) } } ?: emptyList())
        }
    }
}
