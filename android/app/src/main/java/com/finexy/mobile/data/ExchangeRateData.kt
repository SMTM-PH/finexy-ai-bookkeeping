package com.finexy.mobile.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

object ExchangeRateEntityData {
    const val USER_CUSTOM_SOURCE = "user_custom"
    const val EXTERNAL_MAX_AGE_SECONDS = 96L * 60 * 60
    private val currencyPattern = Regex("[A-Z]{3}")

    fun normalizeCurrency(value: String): String = value.trim().uppercase().also {
        require(currencyPattern.matches(it)) { "币种代码无效" }
    }

    fun normalizeRate(value: String): String = runCatching {
        BigDecimal(value.trim()).also { require(it > BigDecimal.ZERO) }.stripTrailingZeros().toPlainString()
    }.getOrElse { throw IllegalArgumentException("汇率必须是大于 0 的十进制数") }
}

data class RemoteExchangeRate(
    val currency: String,
    val rate: String
)

data class RemoteExchangeRateSnapshot(
    val dataSource: String,
    val referenceUrl: String,
    val updateTime: Long,
    val baseCurrency: String,
    val exchangeRates: List<RemoteExchangeRate>,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun validate(): RemoteExchangeRateSnapshot {
        require(dataSource.isNotBlank() && updateTime > 0 && fetchedAt > 0) { "汇率快照元数据不完整" }
        val base = ExchangeRateEntityData.normalizeCurrency(baseCurrency)
        require(exchangeRates.isNotEmpty() && exchangeRates.map { it.currency }.distinct().size == exchangeRates.size) { "汇率列表为空或包含重复币种" }
        require(exchangeRates.any { it.currency == base && BigDecimal(it.rate).compareTo(BigDecimal.ONE) == 0 }) { "汇率列表缺少基准币 1:1 报价" }
        return this
    }

    fun toEntities(): List<ExchangeRateEntity> {
        validate()
        return exchangeRates.map {
            ExchangeRateEntity(it.currency, it.rate, baseCurrency, dataSource, referenceUrl, updateTime, fetchedAt)
        }
    }
}

data class RemoteCustomExchangeRate(val currency: String, val rate: String, val updateTime: Long) {
    fun toEntity(metadata: ExchangeRateEntity): ExchangeRateEntity {
        require(updateTime > 0 && metadata.dataSource == ExchangeRateEntityData.USER_CUSTOM_SOURCE) { "自定义汇率响应无效" }
        return metadata.copy(currency = currency, rate = rate, serverUpdateTime = updateTime, fetchedAt = System.currentTimeMillis())
    }
}

fun ExchangeRateEntity.isUsable(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (runCatching { BigDecimal(rate) > BigDecimal.ZERO }.getOrDefault(false).not()) return false
    if (dataSource == ExchangeRateEntityData.USER_CUSTOM_SOURCE) return true
    val age = nowMillis / 1000 - serverUpdateTime
    return age in 0..ExchangeRateEntityData.EXTERNAL_MAX_AGE_SECONDS
}

fun convertMinorAmount(
    amountMinor: Long,
    fromCurrency: String,
    toCurrency: String,
    rates: List<ExchangeRateEntity>,
    nowMillis: Long = System.currentTimeMillis()
): Long? {
    if (amountMinor < 0) return null
    if (fromCurrency == toCurrency) return amountMinor
    val coherent = rates.takeIf { it.isNotEmpty() && it.map { row -> listOf(row.baseCurrency, row.dataSource, row.serverUpdateTime, row.fetchedAt) }.distinct().size == 1 } ?: return null
    val from = coherent.firstOrNull { it.currency == fromCurrency && it.isUsable(nowMillis) } ?: return null
    val to = coherent.firstOrNull { it.currency == toCurrency && it.isUsable(nowMillis) } ?: return null
    return runCatching {
        BigDecimal.valueOf(amountMinor).multiply(BigDecimal(to.rate)).divide(BigDecimal(from.rate), 0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()
}

internal fun parseExchangeRateSnapshot(raw: String, fetchedAt: Long = System.currentTimeMillis()): RemoteExchangeRateSnapshot {
    val envelope = JSONObject(raw)
    require(envelope.optBoolean("success", false)) { "汇率响应失败" }
    val result = envelope.optJSONObject("result") ?: error("汇率响应不完整")
    val array = result.optJSONArray("exchangeRates") ?: error("汇率列表缺失")
    val seen = mutableSetOf<String>()
    val rates = (0 until array.length()).map { index ->
        val item = array.optJSONObject(index) ?: error("汇率项目无效")
        val currency = ExchangeRateEntityData.normalizeCurrency(item.optString("currency"))
        require(seen.add(currency)) { "汇率列表包含重复币种" }
        RemoteExchangeRate(currency, ExchangeRateEntityData.normalizeRate(item.optString("rate")))
    }
    return RemoteExchangeRateSnapshot(
        dataSource = result.optString("dataSource").trim(),
        referenceUrl = result.optString("referenceUrl").trim(),
        updateTime = result.optString("updateTime").toLongOrNull() ?: result.optLong("updateTime"),
        baseCurrency = ExchangeRateEntityData.normalizeCurrency(result.optString("baseCurrency")),
        exchangeRates = rates,
        fetchedAt = fetchedAt
    ).validate()
}

internal fun parseCustomExchangeRate(raw: String): RemoteCustomExchangeRate {
    val envelope = JSONObject(raw)
    require(envelope.optBoolean("success", false)) { "自定义汇率响应失败" }
    val result = envelope.optJSONObject("result") ?: error("自定义汇率响应不完整")
    return RemoteCustomExchangeRate(
        ExchangeRateEntityData.normalizeCurrency(result.optString("currency")),
        ExchangeRateEntityData.normalizeRate(result.optString("rate")),
        result.optString("updateTime").toLongOrNull() ?: result.optLong("updateTime")
    ).also { require(it.updateTime > 0) { "自定义汇率更新时间无效" } }
}
