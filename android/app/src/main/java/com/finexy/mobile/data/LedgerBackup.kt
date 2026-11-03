package com.finexy.mobile.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Versioned full local-ledger backup. No credentials, PINs or keystore material. */
class LedgerBackup(private val db: FinexyDatabase, private val scope: String,
    private val imageCrypto: AIReviewImageCrypto = AIReviewImageCrypto(db.openHelper.databaseName ?: "isolated-memory")) {
    private val legacyTables = listOf("accounts", "categories", "category_mappings", "tags", "transaction_templates", "transactions", "sync_conflicts")
    private val schemaTenTables = listOf("accounts", "categories", "category_mappings", "account_mappings", "tags", "transaction_templates", "transactions", "sync_conflicts", "scheduled_occurrences")
    private val schemaElevenTables = schemaTenTables + "ai_review_items"
    private val schemaTwelveTables = schemaElevenTables + "product_assets"
    private val schemaThirteenTables = schemaTwelveTables + "exchange_rates"
    private val schemaFourteenTables = schemaThirteenTables + listOf("family_groups", "family_members", "ledgers", "savings_goals")
    private val schemaFifteenTables = schemaThirteenTables + listOf("ledgers", "savings_goals")
    private val schemaSeventeenTables = schemaFifteenTables + "ledger_write_queue"
    private val schemaEighteenTables = schemaSeventeenTables + listOf("template_form_drafts", "template_creation_queue")
    private val schemaNineteenTables = schemaEighteenTables + listOf("ledger_ai_review_cache", "ai_review_drafts", "ai_review_requests")
    private val tables = schemaNineteenTables + listOf("ai_review_images", "ai_review_image_chunks")
    private fun tablesFor(schema: Int): List<String> = when (schema) { 8 -> legacyTables; 9 -> schemaTenTables.dropLast(1); 10 -> schemaTenTables; 11 -> schemaElevenTables; 12 -> schemaTwelveTables; 13 -> schemaThirteenTables; 14 -> schemaFourteenTables; 15 -> schemaFifteenTables; 16, 17 -> schemaSeventeenTables; 18 -> schemaEighteenTables; 19 -> schemaNineteenTables; else -> tables }
    suspend fun export(password: String): String = withContext(Dispatchers.IO) {
        val document = db.withTransaction {
            val root = JSONObject().put("schema", 20).put("scope", scope).put("createdAt", System.currentTimeMillis())
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
                        if (table == "ai_review_image_chunks") {
                            val image = db.dao().findAIReviewImage(row.getLong("ledgerId"), row.getString("imageId")) ?: error("原图元数据缺失")
                            // Portable bytes exist only in memory inside the password-encrypted backup; no Keystore material is exported.
                            row.put("content", "backup1:" + PrivacyCrypto.encode(imageCrypto.decrypt(image, imageChunk(row))))
                        }
                        rows.put(row)
                    }
                }
                data.put(table, rows)
            }
            root.put("tables", data)
            validate(root)
            root.toString()
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
        require(schema in 8..20 && document.getString("scope") == scope) { "此备份属于其他账号/服务器或不支持的数据库版本" }
        val expectedTables = tablesFor(schema)
        val data = document.getJSONObject("tables")
        require(data.keys().asSequence().toSet() == expectedTables.toSet()) { "备份缺少数据表" }
        for (table in expectedTables) {
            val columns = if (schema == 14 && table in setOf("family_groups", "family_members")) legacyFamilyColumns(table) else mutableMapOf<String, Pair<String, Boolean>>().also { result ->
                db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
                    while (c.moveToNext()) result[c.getString(c.getColumnIndexOrThrow("name"))] = c.getString(c.getColumnIndexOrThrow("type")) to (c.getInt(c.getColumnIndexOrThrow("notnull")) == 1)
                }
            }
            if (schema < 11 && table == "transactions") columns.remove("reviewItemId")
            if (schema < 20 && table == "ai_review_requests") columns.remove("sourceImageId")
            if (schema < 17 && table in setOf("transaction_templates", "scheduled_occurrences")) columns.remove("ledgerId")
            if (schema < 17 && table == "scheduled_occurrences") { columns.remove("pendingAction"); columns.remove("pendingActionQueuedAt") }
            val rows = data.getJSONArray(table)
            require(rows.length() <= 50_000) { "备份行数超限" }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                require(row.keys().asSequence().toSet() == columns.keys) { "备份字段不完整" }
                columns.forEach { (name, kind) ->
                    val value = row.get(name)
                    require(if (value == JSONObject.NULL) !kind.second else if (kind.first == "INTEGER") value is Int || value is Long else value is String) { "备份字段类型不正确" }
                }
                if (schema >= 17 && table in setOf("transaction_templates", "scheduled_occurrences")) {
                    require(row.getLong("ledgerId") >= 0) { "周期账本标识无效" }
                }
                if (schema >= 17 && table == "scheduled_occurrences") {
                    val action = row.getInt("pendingAction")
                    val queuedAt = row.getLong("pendingActionQueuedAt")
                    require(row.getLong("templateId") > 0 && row.getLong("scheduledUnixTime") > 0 && row.getInt("status") in 1..3) { "周期待确认标识或状态无效" }
                    require(action == 0 || (if (action == ScheduledOccurrenceEntity.ACTION_RESTORE) row.getInt("status") == ScheduledOccurrenceEntity.STATUS_DISMISSED else row.getInt("status") == ScheduledOccurrenceEntity.STATUS_PENDING)) { "待确认操作与记录状态不一致" }
                    require(action in ScheduledOccurrenceEntity.ACTION_NONE..ScheduledOccurrenceEntity.ACTION_RESTORE && queuedAt >= 0 && ((action == ScheduledOccurrenceEntity.ACTION_NONE) == (queuedAt == 0L))) { "周期待确认操作状态无效" }
                }
                if (table == "ledger_write_queue") {
                    require(row.getLong("ledgerId") > 0 && row.getString("localId").length in 1..64) { "待确认写入标识无效" }
                    require(row.getString("state") in setOf(LedgerWriteEntity.PENDING, LedgerWriteEntity.POSTED)) { "待确认写入状态无效" }
                    require(JSONObject(row.getString("payloadJson")).getString("ledgerId") == row.getLong("ledgerId").toString()) { "待确认写入账本不一致" }
                    if (row.getString("state") == LedgerWriteEntity.POSTED) require(!row.isNull("serverId") && row.getLong("serverId") > 0) { "已写入记录缺少服务器 ID" }
                }
                if (table == "template_form_drafts") validateTemplateDraft(TemplateFormDraftEntity(
                    row.getLong("ledgerId"), row.getInt("templateType"), row.getString("draftId"), row.getLong("revision"),
                    row.getLong("editingId"), row.getString("formJson"), row.getLong("updatedAt")))
                if (table == "template_creation_queue") {
                    require(row.getInt("serverDeleted") in 0..1) { "模板回执删除标识无效" }
                    validateTemplateCreation(TemplateCreationEntity(row.getLong("ledgerId"), row.getInt("templateType"),
                        row.getString("clientSessionId"), row.getString("draftId"), row.getLong("draftRevision"), row.getString("payloadJson"),
                        row.getString("state"), if (row.isNull("serverId")) null else row.getLong("serverId"), row.getInt("serverDeleted") == 1,
                        row.getString("lastError"), row.getLong("createdAt")))
                }
                if (table == "ledger_ai_review_cache") {
                    require(row.getInt("deleted") in 0..1) { "AI 缓存删除标识无效" }
                    validateLedgerAICache(LedgerAIReviewCacheEntity(row.getLong("ledgerId"), row.getLong("id"),
                        row.getInt("status"), row.getString("itemJson"), row.getInt("deleted") == 1, row.getLong("updatedAt")))
                }
                if (table == "ai_review_drafts") validateAIReviewDraft(AIReviewDraftEntity(row.getLong("ledgerId"),
                    row.getLong("itemId"), row.getString("draftId"), row.getLong("revision"), row.getString("formJson"), row.getLong("updatedAt")))
                if (table == "ai_review_requests") validateAIReviewRequest(AIReviewRequestEntity(row.getLong("ledgerId"),
                    row.getLong("itemId"), row.getString("requestId"), row.getString("draftId"), row.getLong("draftRevision"),
                    row.getString("action"), row.getString("payloadJson"), row.getString("state"), row.getString("receiptJson"),
                    row.getString("lastErrorCode"), row.getLong("createdAt"), row.optString("sourceImageId", "")))
            }
        }
        if (schema >= 20) validateImages(data)
    }

    private fun imageInt(row: JSONObject, field: String): Int = row.getLong(field).also {
        require(it in 0..Int.MAX_VALUE.toLong()) { "备份原图数值超限" }
    }.toInt()
    private fun imageRow(row: JSONObject) = AIReviewImageEntity(row.getLong("ledgerId"), row.getString("imageId"),
        row.getString("draftId"), row.getString("mimeType"), imageInt(row, "width"), imageInt(row, "height"),
        imageInt(row, "byteCount"), row.getString("sha256"), imageInt(row, "chunkCount"))
    private fun imageChunk(row: JSONObject) = AIReviewImageChunkEntity(row.getLong("ledgerId"), row.getString("imageId"), imageInt(row, "chunkIndex"), row.getString("content"))
    private fun portableChunk(image: AIReviewImageEntity, chunk: AIReviewImageChunkEntity): ByteArray {
        require(chunk.ledgerId == image.ledgerId && chunk.imageId == image.imageId && chunk.content.startsWith("backup1:") && chunk.content.length <= 90_000) { "备份原图分块格式无效" }
        return PrivacyCrypto.decode(chunk.content.removePrefix("backup1:")).also { validateChunkSize(image, chunk.chunkIndex, it.size) }
    }
    private fun validateImages(data: JSONObject) {
        val images = data.getJSONArray("ai_review_images").let { rows -> (0 until rows.length()).map { imageRow(rows.getJSONObject(it)) } }
        val chunks = data.getJSONArray("ai_review_image_chunks").let { rows -> (0 until rows.length()).map { imageChunk(rows.getJSONObject(it)) } }
        require(images.map { it.ledgerId to it.imageId }.distinct().size == images.size &&
            chunks.map { Triple(it.ledgerId, it.imageId, it.chunkIndex) }.distinct().size == chunks.size) { "备份原图包含重复标识" }
        require(chunks.all { c -> images.any { it.ledgerId == c.ledgerId && it.imageId == c.imageId } }) { "备份原图分块缺少归属" }
        for (image in images) {
            validateAIImage(image)
            val selected = chunks.filter { it.ledgerId == image.ledgerId && it.imageId == image.imageId }.sortedBy { it.chunkIndex }
            require(selected.map { it.chunkIndex } == (0 until image.chunkCount).toList()) { "备份原图不完整" }
            val bytes = java.io.ByteArrayOutputStream(image.byteCount).use { out -> selected.forEach { out.write(portableChunk(image, it)) }; out.toByteArray() }
            require(aiImageInfo(image.ledgerId, image.imageId, image.draftId, bytes) == image) { "备份原图内容校验失败" }
        }
        for (table in listOf("ai_review_drafts", "ai_review_requests")) {
            val rows = data.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = if (table == "ai_review_drafts") JSONObject(row.getString("formJson")).optString(AI_IMAGE_FIELD, "") else row.getString("sourceImageId")
                if (id.isNotEmpty()) require(images.any { it.imageId == id && it.ledgerId == row.getLong("ledgerId") && it.draftId == row.getString("draftId") }) { "备份来源缺少原图或归属不一致" }
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
                // Schema 14 family rows were a cache of the retired API. The
                // canonical ledgers in the same backup are restored below.
                if (table == "family_groups" || table == "family_members") continue
                val rows = data.getJSONArray(table)
                for (i in 0 until rows.length()) {
                    val row = JSONObject(rows.getJSONObject(i).toString())
                    if (table == "ai_review_images") {
                        val incoming = imageRow(row)
                        val existing = db.dao().findAIReviewImage(incoming.ledgerId, incoming.imageId)
                        require(existing == null || existing == incoming) { "设备原图与备份标识冲突" }
                    }
                    if (table == "ai_review_image_chunks") {
                        val image = db.dao().findAIReviewImage(row.getLong("ledgerId"), row.getString("imageId")) ?: error("恢复原图元数据缺失")
                        val portable = portableChunk(image, imageChunk(row))
                        row.put("content", imageCrypto.encrypt(image, row.getInt("chunkIndex"), portable).content)
                        val existing = db.dao().aiReviewImageChunks(image.ledgerId, image.imageId).find { it.chunkIndex == row.getInt("chunkIndex") }
                        if (existing != null) {
                            val readable = runCatching { imageCrypto.decrypt(image, existing) }.getOrNull()
                            if (readable != null) require(PrivacyCrypto.matches(readable, portable)) { "设备原图分块与备份冲突" }
                            else {
                                // Only immutable image bytes may be repaired from the fully validated backup.
                                // Financial rows, newer drafts and receipts still use insert-missing-only below.
                                db.openHelper.writableDatabase.execSQL("UPDATE ai_review_image_chunks SET content = ? WHERE ledgerId = ? AND imageId = ? AND chunkIndex = ?",
                                    arrayOf(row.getString("content"), image.ledgerId, image.imageId, row.getInt("chunkIndex")))
                            }
                        }
                    }
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
            if (document.getInt("schema") >= 20) {
                val images = data.getJSONArray("ai_review_images")
                val persistence = AIReviewPersistence(db, imageCrypto)
                (0 until images.length()).map { images.getJSONObject(it).getLong("ledgerId") }.distinct().forEach { persistence.pruneImages(it) }
            }
            restored
        }
    }

    suspend fun statistics(): String = withContext(Dispatchers.IO) {
        val rows = db.dao().allTransactions()
        "流水 ${rows.count { !it.deleted }} 条 · 删除标记 ${rows.count { it.deleted }} 条\n待同步 ${rows.count { it.syncState == SyncState.PENDING }} 条 · 冲突 ${rows.count { it.syncState == SyncState.CONFLICT }} 条 · 失败 ${rows.count { it.syncState == SyncState.FAILED }} 条\n其他账本待确认写入 ${db.dao().allLedgerWrites().size} 条 · 模板创建待核对 ${db.dao().allTemplateCreations().size} 条"
    }

    suspend fun clearLocal(confirmation: String) = withContext(Dispatchers.IO) {
        require(confirmation == "清空当前账本") { "请输入完整确认文字" }
        db.withTransaction {
            (listOf("ledger_transaction_cache", "ledger_account_cache") + tables.reversed() + "sync_status")
                .distinct().forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM $it") }
        }
    }

    private fun legacyFamilyColumns(table: String): MutableMap<String, Pair<String, Boolean>> = when (table) {
        "family_groups" -> mutableMapOf(
            "id" to ("INTEGER" to true), "ownerUid" to ("INTEGER" to true), "name" to ("TEXT" to true),
            "comment" to ("TEXT" to true), "memberCount" to ("INTEGER" to true),
            "createdTime" to ("INTEGER" to true), "updatedAt" to ("INTEGER" to true)
        )
        "family_members" -> mutableMapOf(
            "id" to ("INTEGER" to true), "familyId" to ("INTEGER" to true), "uid" to ("INTEGER" to true),
            "role" to ("INTEGER" to true), "status" to ("INTEGER" to true), "nickname" to ("TEXT" to true),
            "joinedTime" to ("INTEGER" to true), "updatedAt" to ("INTEGER" to true)
        )
        else -> error("未知旧家庭表")
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
