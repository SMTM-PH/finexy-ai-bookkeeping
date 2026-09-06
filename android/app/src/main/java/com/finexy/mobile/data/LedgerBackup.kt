package com.finexy.mobile.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Versioned full local-ledger backup. No credentials, PINs or keystore material. */
class LedgerBackup(private val db: FinexyDatabase, private val scope: String) {
    private val legacyTables = listOf("accounts", "categories", "category_mappings", "tags", "transaction_templates", "transactions", "sync_conflicts")
    private val tables = listOf("accounts", "categories", "category_mappings", "account_mappings", "tags", "transaction_templates", "transactions", "sync_conflicts", "scheduled_occurrences")
    private fun tablesFor(schema: Int): List<String> = when (schema) { 8 -> legacyTables; 9 -> tables.dropLast(1); else -> tables }
    suspend fun export(password: String): String = withContext(Dispatchers.IO) {
        val document = db.withTransaction {
            val root = JSONObject().put("schema", 10).put("scope", scope).put("createdAt", System.currentTimeMillis())
            val data = JSONObject()
            for (table in tables) {
                val rows = JSONArray()
                db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                    while (cursor.moveToNext()) {
                        check(rows.length() < 50_000) { "账本过大，请分批使用 Web 导出" }
                        val row = JSONObject()
                        cursor.columnNames.forEachIndexed { i, name -> row.put(name, when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                            else -> error("备份包含不受支持的数据类型")
                        }) }
                        rows.put(row)
                    }
                }
                data.put(table, rows)
            }
            root.put("tables", data).toString()
        }
        require(document.toByteArray().size <= 16 * 1024 * 1024) { "备份超过 16 MiB，请使用 Web 导出" }
        PrivacyCrypto.encrypt(document, password)
    }

    suspend fun preview(encrypted: String, password: String): JSONObject = withContext(Dispatchers.IO) {
        val document = JSONObject(PrivacyCrypto.decrypt(encrypted, password))
        validate(document)
        document
    }

    private fun validate(document: JSONObject) {
        val schema = document.getInt("schema")
        require(schema in 8..10 && document.getString("scope") == scope) { "此备份属于其他账号/服务器或不支持的数据库版本" }
        val expectedTables = tablesFor(schema)
        val data = document.getJSONObject("tables")
        require(data.keys().asSequence().toSet() == expectedTables.toSet()) { "备份缺少数据表" }
        for (table in expectedTables) {
            val columns = mutableMapOf<String, Pair<String, Boolean>>()
            db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
                while (c.moveToNext()) columns[c.getString(c.getColumnIndexOrThrow("name"))] = c.getString(c.getColumnIndexOrThrow("type")) to (c.getInt(c.getColumnIndexOrThrow("notnull")) == 1)
            }
            val rows = data.getJSONArray(table)
            require(rows.length() <= 50_000) { "备份行数超限" }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                require(row.keys().asSequence().toSet() == columns.keys) { "备份字段不完整" }
                columns.forEach { (name, kind) ->
                    val value = row.get(name)
                    require(if (value == JSONObject.NULL) !kind.second else if (kind.first == "INTEGER") value is Int || value is Long else value is String) { "备份字段类型不正确" }
                }
            }
        }
    }

    /** Add missing rows only, transactionally; never overwrite newer on-device edits. */
    suspend fun restore(document: JSONObject): Int = withContext(Dispatchers.IO) {
        db.withTransaction {
            validate(document)
            val data = document.getJSONObject("tables")
            val restoredTables = tablesFor(document.getInt("schema"))
            var restored = 0
            val restoredIds = mutableSetOf<String>()
            for (table in restoredTables) {
                val rows = data.getJSONArray(table)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (table == "transactions" && !row.isNull("serverId")) {
                        val id = row.getLong("serverId")
                        val exists = db.openHelper.readableDatabase.query("SELECT 1 FROM transactions WHERE serverId = ? LIMIT 1", arrayOf<Any>(id)).use { it.moveToFirst() }
                        if (exists) continue
                    }
                    // A stale backup conflict must not attach to an existing, newer local row.
                    if (table == "sync_conflicts" && row.getString("localId") !in restoredIds) continue
                    val keys = row.keys().asSequence().toList()
                    val args = keys.map { row.get(it).let { v -> if (v == JSONObject.NULL) null else v } }.toTypedArray()
                    db.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO $table (${keys.joinToString()}) VALUES (${keys.joinToString { "?" }})", args)
                    val changed = db.openHelper.readableDatabase.query("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }
                    if (table == "transactions" && changed > 0) { restored++; restoredIds += row.getString("localId") }
                }
            }
            restored
        }
    }

    suspend fun statistics(): String = withContext(Dispatchers.IO) {
        val rows = db.dao().allTransactions()
        "流水 ${rows.count { !it.deleted }} 条 · 删除标记 ${rows.count { it.deleted }} 条\n待同步 ${rows.count { it.syncState == SyncState.PENDING }} 条 · 冲突 ${rows.count { it.syncState == SyncState.CONFLICT }} 条 · 失败 ${rows.count { it.syncState == SyncState.FAILED }} 条"
    }

    suspend fun clearLocal(confirmation: String) = withContext(Dispatchers.IO) {
        require(confirmation == "清空当前账本") { "请输入完整确认文字" }
        db.withTransaction { tables.reversed().forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM $it") } }
    }

    companion object {
        /** Only disposable regular files under our dedicated cache directory are touched. */
        fun clearCache(context: Context): Int {
            val root = context.cacheDir.canonicalFile
            val directory = java.io.File(root, "finexy-previews")
            require(directory.canonicalFile.parentFile == root) { "缓存路径异常" }
            var count = 0
            directory.listFiles()?.forEach { file ->
                if (file.canonicalFile.parentFile == directory.canonicalFile && file.isFile && file.delete()) count++
            }
            return count
        }
    }
}
