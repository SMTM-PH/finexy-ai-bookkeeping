package com.finexy.mobile.data

import org.json.JSONObject

data class ProductAssetDraft(
    val category: Int,
    val name: String,
    val brand: String = "",
    val model: String = "",
    val purchaseAmountMinor: Long,
    val purchaseTime: Long,
    val utcOffset: Int,
    val usefulLifeDays: Int = defaultUsefulLifeDays(category),
    val residualAmountMinor: Long = 0,
    val manualMarketValueMinor: Long? = null,
    val comment: String = "",
    val sourceTransactionId: Long = 0
) {
    fun validate() {
        require(category in 1..7) { "资产类别无效" }
        require(name.trim().isNotEmpty() && name.trim().length <= 128) { "资产名称不能为空且最多 128 个字符" }
        require(brand.trim().length <= 64 && model.trim().length <= 64) { "品牌和型号最多 64 个字符" }
        require(purchaseAmountMinor > 0) { "购买价格必须大于 0" }
        require(purchaseTime > 0) { "购买日期无效" }
        require(utcOffset in -720..840) { "购买时区无效" }
        require(usefulLifeDays in 1..36_500) { "预计使用寿命必须为 1–36500 天" }
        require(residualAmountMinor in 0..purchaseAmountMinor) { "期末残值不能超过购买价格" }
        require(manualMarketValueMinor == null || manualMarketValueMinor >= 0) { "市场估值不能小于 0" }
        require(comment.trim().length <= 255) { "备注最多 255 个字符" }
        require(sourceTransactionId >= 0) { "来源流水无效" }
    }

    fun toCreatePayload(): JSONObject {
        validate()
        return commonPayload()
    }

    fun toModifyPayload(id: Long, clearManualMarketValue: Boolean): JSONObject {
        require(id > 0) { "资产 ID 无效" }
        return commonPayload().put("id", id.toString()).put("clearManualMarketValue", clearManualMarketValue)
    }

    private fun commonPayload(): JSONObject {
        validate()
        return JSONObject()
            .put("sourceTransactionId", sourceTransactionId.toString())
            .put("category", category)
            .put("name", name.trim())
            .put("brand", brand.trim())
            .put("model", model.trim())
            .put("purchaseAmount", purchaseAmountMinor)
            .put("purchaseTime", purchaseTime / 1000)
            .put("utcOffset", utcOffset)
            .put("usefulLifeDays", usefulLifeDays)
            .put("residualAmount", residualAmountMinor)
            .put("comment", comment.trim())
            .also { payload -> manualMarketValueMinor?.let { payload.put("manualMarketValue", it) } }
    }
}
data class RemoteProductAsset(
    val id: Long,
    val sourceTransactionId: Long,
    val saleTransactionId: Long,
    val category: Int,
    val status: Int,
    val name: String,
    val brand: String,
    val model: String,
    val purchaseAmountMinor: Long,
    val purchaseTime: Long,
    val utcOffset: Int,
    val usefulLifeDays: Int,
    val residualAmountMinor: Long,
    val manualMarketValueMinor: Long?,
    val manualMarketValueTime: Long?,
    val soldAmountMinor: Long,
    val soldTime: Long?,
    val comment: String,
    val heldDays: Int,
    val accumulatedDepreciationMinor: Long,
    val bookValueMinor: Long
) {
    fun toEntity() = ProductAssetEntity(
        id, sourceTransactionId, saleTransactionId, category, status, name, brand, model,
        purchaseAmountMinor, purchaseTime, utcOffset, usefulLifeDays, residualAmountMinor,
        manualMarketValueMinor, manualMarketValueTime, soldAmountMinor, soldTime, comment,
        heldDays, accumulatedDepreciationMinor, bookValueMinor
    )

    companion object {
        fun from(data: JSONObject): RemoteProductAsset {
            fun id(name: String, optional: Boolean = false): Long {
                if (optional && (!data.has(name) || data.isNull(name))) return 0
                return data.getString(name).toLongOrNull()?.also { require(it >= if (optional) 0 else 1) }
                    ?: error("资产响应中的 $name 无效")
            }
            fun seconds(name: String): Long? = if (!data.has(name) || data.isNull(name)) null else
                data.getLong(name).also { require(it > 0) { "资产时间无效" } } * 1000

            val valuation = data.optJSONObject("valuation") ?: error("资产响应缺少估值")
            val category = data.getInt("category")
            val status = data.getInt("status")
            val purchaseAmount = data.getLong("purchaseAmount")
            val residualAmount = data.getLong("residualAmount")
            val manualValue = if (data.has("manualMarketValue") && !data.isNull("manualMarketValue")) data.getLong("manualMarketValue") else null
            require(category in 1..7 && status in 1..3) { "资产类别或状态无效" }
            require(purchaseAmount > 0 && residualAmount in 0..purchaseAmount) { "资产金额无效" }
            require(manualValue == null || manualValue >= 0) { "资产市场估值无效" }
            require(data.getInt("usefulLifeDays") in 1..36_500) { "资产使用寿命无效" }
            return RemoteProductAsset(
                id("id"), id("sourceTransactionId", true), id("saleTransactionId", true),
                category, status, data.getString("name").trim().also { require(it.isNotEmpty() && it.length <= 128) },
                data.optString("brand"), data.optString("model"), purchaseAmount,
                requireNotNull(seconds("purchaseTime")), data.getInt("utcOffset").also { require(it in -720..840) },
                data.getInt("usefulLifeDays"), residualAmount, manualValue, seconds("manualMarketValueTime"),
                data.optLong("soldAmount", 0).also { require(it >= 0) }, seconds("soldTime"), data.optString("comment"),
                valuation.getInt("heldDays").also { require(it >= 0) },
                valuation.getLong("accumulatedDepreciation").also { require(it >= 0) },
                valuation.getLong("bookValue").also { require(it >= 0) }
            )
        }
    }
}

fun defaultUsefulLifeDays(category: Int): Int = when (category) {
    2, 4 -> 4 * 365
    3, 6 -> 5 * 365
    5 -> 6 * 365
    7 -> 8 * 365
    else -> 5 * 365
}
