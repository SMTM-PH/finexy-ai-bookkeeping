package com.finexy.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Write model. UI formatting is deliberately not part of persistence. */
data class TransactionDraft(
    val localId: String,
    val type: Int,
    val sourceAccountId: Long,
    val categoryId: Long?,
    val categoryName: String,
    val sourceAmountMinor: Long,
    val comment: String,
    val tagIdsJson: String,
    val destinationAccountId: Long? = null,
    val destinationAmountMinor: Long = 0
)

internal fun stringIds(raw: String): JSONArray {
    val values = JSONArray(raw)
    val ids = (0 until values.length()).map { values.getString(it) }.distinct().sorted()
    require(ids.all { (it.toLongOrNull() ?: 0) > 0 }) { "无效的标签或附件 ID" }
    return JSONArray().apply { ids.forEach { put(it) } }
}

/** All persisted transaction dates are milliseconds; the server contract uses seconds. */
internal fun TransactionEntity.toApiPayload(): JSONObject = JSONObject()
    .put("type", type)
    .put("categoryId", (categoryId ?: 0).toString())
    .put("time", time / 1000)
    .put("utcOffset", utcOffset)
    .put("sourceAccountId", sourceAccountId.toString())
    .put("destinationAccountId", (destinationAccountId ?: 0).toString())
    .put("sourceAmount", sourceAmountMinor)
    .put("destinationAmount", destinationAmountMinor)
    .put("tagIds", stringIds(tagIdsJson))
    .put("pictureIds", stringIds(pictureIdsJson))
    .put("hideAmount", hideAmount)
    .put("geoLocation", geoLocationJson.takeIf { it.isNotBlank() }?.let { raw ->
        val location = JSONObject(raw)
        JSONObject().put("longitude", location.optDouble("longitude"))
            .put("latitude", location.optDouble("latitude"))
    } ?: JSONObject.NULL)
    .put("comment", comment)

/** Stable field order and sorted ID sets; display names and sync metadata are not server edits. */
internal fun TransactionEntity.syncSnapshot(): String = toApiPayload().toString()
