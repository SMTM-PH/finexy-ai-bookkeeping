package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.YearMonth
import java.time.ZoneId

internal const val MAXIMUM_MONTHLY_BUDGET_AMOUNT = 9_999_999_999_999L
internal const val MAXIMUM_MONTHLY_BUDGET_REVISION = 9_007_199_254_740_991L

// Same explicit currency allow-list as pkg/validators/currency.go; no platform ISO-version guesses.
private val supportedBudgetCurrencies = "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BHD BIF BMD BND BOB BRL BSD BTN BWP BYN BZD CAD CDF CHF CLP CNY COP CRC CUC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HTG HUF IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD RUB RWF SAR SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD TWD TZS UAH UGX USD UYU UZS VED VES VND VUV WST XAF XCD XOF XPF XSU YER ZAR ZMW ZWG ZWL".split(" ").toSet()

internal fun requireBudgetMonth(yearMonth: Int) {
    require(yearMonth in 200001..999912 && yearMonth % 100 in 1..12) { "预算月份无效" }
}

internal fun requireBudgetCurrency(currency: String) {
    require(currency in supportedBudgetCurrencies) { "请输入三位大写币种代码" }
}

internal data class MonthlyBudget(
    val id: Long, val ledgerId: Long, val yearMonth: Int, val amountMinor: Long,
    val currency: String, val categoryAmounts: Map<Long, Long>, val revision: Long
)

internal data class MonthlyBudgetDraft(
    val yearMonth: Int, val amountMinor: Long, val currency: String,
    val categoryAmounts: Map<Long, Long> = emptyMap(), val expectedRevision: Long = 0
) {
    fun payload(ledgerId: Long): String {
        require(ledgerId >= 0) { "账本无效" }
        requireBudgetMonth(yearMonth)
        requireBudgetCurrency(currency)
        require(expectedRevision in 0..MAXIMUM_MONTHLY_BUDGET_REVISION) { "预算版本无效" }
        require(amountMinor in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT) { "预算金额必须大于零且在支持范围内" }
        require(categoryAmounts.all { (id, amount) -> id > 0 && amount in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT }) { "分类预算无效" }
        return buildJsonObject {
            put("ledgerId", ledgerId.toString())
            put("yearMonth", yearMonth)
            put("amount", amountMinor)
            put("currency", currency)
            put("expectedRevision", expectedRevision)
            put("categoryAmounts", buildJsonObject { categoryAmounts.forEach { (id, amount) -> put(id.toString(), amount) } })
        }.toString()
    }
}

internal interface MonthlyBudgetGateway {
    suspend fun role(ledgerId: Long): Int
    suspend fun get(ledgerId: Long, yearMonth: Int): MonthlyBudget?
    suspend fun set(ledgerId: Long, draft: MonthlyBudgetDraft): MonthlyBudget
    suspend fun delete(ledgerId: Long, yearMonth: Int, expectedRevision: Long)
    suspend fun categories(ledgerId: Long): List<MonthlyBudgetCategory>
    suspend fun expenses(ledgerId: Long, yearMonth: Int, zone: ZoneId): MonthlyBudgetExpenseSnapshot
}

internal class MonthlyBudgetApiGateway(private val api: FinexyApi) : MonthlyBudgetGateway {
    override suspend fun role(ledgerId: Long): Int {
        if (ledgerId == LedgerEntity.DEFAULT_LEDGER_ID) return RemoteLedgerMember.ROLE_OWNER
        val members = api.listLedgerMembers(ledgerId)
        require(members.all { it.ledgerId == ledgerId }) { "成员响应与当前账本不一致" }
        return members.singleOrNull { it.isCurrentUser && it.status == 1 }?.role ?: error("当前用户没有有效账本成员关系")
    }
    override suspend fun get(ledgerId: Long, yearMonth: Int) = api.getMonthlyBudget(ledgerId, yearMonth)
    override suspend fun set(ledgerId: Long, draft: MonthlyBudgetDraft) = api.setMonthlyBudget(ledgerId, draft)
    override suspend fun delete(ledgerId: Long, yearMonth: Int, expectedRevision: Long) = api.deleteMonthlyBudget(ledgerId, yearMonth, expectedRevision)
    override suspend fun categories(ledgerId: Long) = api.listMonthlyBudgetCategories(ledgerId)
    override suspend fun expenses(ledgerId: Long, yearMonth: Int, zone: ZoneId) = api.getMonthlyBudgetExpenses(ledgerId, yearMonth, zone)
}

/** Exact two-decimal input; rounding a user's money is never implicit. */
internal fun parseMonthlyBudgetAmount(text: String): Long {
    require(text.trim().matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "请输入正数金额，最多两位小数" }
    val amount = runCatching { BigDecimal(text.trim()).movePointRight(2).longValueExact() }.getOrNull()
    require(amount != null && amount in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT) { "预算金额必须大于零且在支持范围内" }
    return amount
}

internal fun monthlyBudgetAmountInput(amountMinor: Long): String = BigDecimal.valueOf(amountMinor, 2).toPlainString()

internal fun monthlyBudgetCanManage(role: Int?): Boolean = role == RemoteLedgerMember.ROLE_OWNER || role == RemoteLedgerMember.ROLE_ADMIN

internal data class MonthlyBudgetContext(val identity: String, val ledgerId: Long, val yearMonth: Int)
internal data class MonthlyBudgetTicket(val context: MonthlyBudgetContext, val generation: Long)

/** A late response from a previous account, ledger, month or retry cannot win. */
internal class MonthlyBudgetRequestGate {
    private var generation = 0L
    private var context: MonthlyBudgetContext? = null
    fun begin(context: MonthlyBudgetContext): MonthlyBudgetTicket {
        this.context = context
        return MonthlyBudgetTicket(context, ++generation)
    }
    fun accepts(ticket: MonthlyBudgetTicket, current: MonthlyBudgetContext, currentIdentity: String): Boolean =
        ticket.generation == generation && ticket.context == context && ticket.context == current && current.identity == currentIdentity
}

internal data class MonthlyBudgetCategory(val id: Long, val name: String, val parentId: Long, val hidden: Boolean)
internal data class MonthlyBudgetUsage(val expenseMinor: Long?, val categoryAmounts: Map<Long, Long?>)
internal data class MonthlyBudgetExpenseSnapshot(
    val currencies: Set<String>,
    private val expenses: Map<String, BigInteger>,
    private val categoryExpenses: Map<String, Map<Long, BigInteger>>
) {
    fun usage(currency: String): MonthlyBudgetUsage {
        requireBudgetCurrency(currency)
        return MonthlyBudgetUsage((expenses[currency] ?: BigInteger.ZERO).supportedBudgetTotal(),
            categoryExpenses[currency].orEmpty().mapValues { it.value.supportedBudgetTotal() })
    }
}

private fun BigInteger.supportedBudgetTotal(): Long? =
    if (this >= BigInteger.valueOf(Long.MIN_VALUE) && this <= BigInteger.valueOf(Long.MAX_VALUE)) toLong() else null

internal data class MonthlyBudgetProgress(val remainingMinor: BigInteger, val percentage: BigDecimal, val fill: Float, val warning: Int)

internal fun monthlyBudgetProgress(amountMinor: Long, expenseMinor: Long): MonthlyBudgetProgress {
    require(amountMinor > 0)
    val percentage = BigDecimal.valueOf(expenseMinor).multiply(BigDecimal(100))
        .divide(BigDecimal.valueOf(amountMinor), 1, RoundingMode.HALF_UP)
    val expense = BigInteger.valueOf(expenseMinor)
    val amount = BigInteger.valueOf(amountMinor)
    val warning = when {
        expense >= amount -> 2
        expense * BigInteger.TEN >= amount * BigInteger.valueOf(8) -> 1
        else -> 0
    }
    return MonthlyBudgetProgress(amount - expense, percentage,
        (expenseMinor.toDouble() / amountMinor).coerceIn(0.0, 1.0).toFloat(), warning)
}

/** Strict parsers are pure JVM code so malformed snapshots can be tested without a device. */
internal object MonthlyBudgetContract {
    private fun envelope(raw: String): JsonObject {
        val data = Json.parseToJsonElement(raw) as? JsonObject ?: error("服务器响应不完整")
        require(data["success"] == JsonPrimitive(true) && data.containsKey("result")) { "服务器响应不完整" }
        return data
    }
    private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: error("服务器响应字段无效")
    private fun JsonObject.number(name: String): Long = (this[name] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.longOrNull ?: error("服务器响应整数无效")
    private fun JsonObject.id(name: String, allowZero: Boolean = false): Long = string(name).let { text ->
        require(text.matches(Regex(if (allowZero) "0|[1-9][0-9]*" else "[1-9][0-9]*"))) { "服务器响应标识无效" }
        text.toLongOrNull() ?: error("服务器响应标识越界")
    }
    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.booleanOrNull ?: error("服务器响应状态无效")

    fun parseBudget(raw: String, ledgerId: Long, yearMonth: Int, allowUnset: Boolean = true): MonthlyBudget? {
        require(ledgerId >= 0)
        requireBudgetMonth(yearMonth)
        val result = envelope(raw).getValue("result")
        if (result == JsonNull) {
            require(allowUnset) { "预算保存响应不完整" }
            return null
        }
        val data = result as? JsonObject ?: error("预算响应不完整")
        val actualLedger = data.id("ledgerId", allowZero = true)
        val actualMonth = data.number("yearMonth")
        require(actualLedger == ledgerId && actualMonth == yearMonth.toLong()) { "预算响应与当前账本或月份不一致" }
        val amount = data.number("amount").also { require(it in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT) { "预算金额无效" } }
        val currency = data.string("currency").also(::requireBudgetCurrency)
        val categories = data["categoryAmounts"] as? JsonObject ?: error("分类预算响应不完整")
        val categoryAmounts = categories.map { (key, value) ->
            require(key.matches(Regex("[1-9][0-9]*"))) { "分类预算标识无效" }
            val id = key.toLongOrNull() ?: error("分类预算标识越界")
            val limit = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            require(limit != null && limit in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT) { "分类预算金额无效" }
            id to limit
        }.toMap()
        val revision = data.number("revision").also { require(it in 1..MAXIMUM_MONTHLY_BUDGET_REVISION) { "预算版本无效" } }
        return MonthlyBudget(data.id("id"), actualLedger, yearMonth, amount, currency, categoryAmounts, revision)
    }

    fun deletePayload(ledgerId: Long, yearMonth: Int, expectedRevision: Long): String {
        require(ledgerId >= 0)
        requireBudgetMonth(yearMonth)
        require(expectedRevision in 1..MAXIMUM_MONTHLY_BUDGET_REVISION) { "预算版本无效，请重新读取" }
        return buildJsonObject { put("ledgerId", ledgerId.toString()); put("yearMonth", yearMonth); put("expectedRevision", expectedRevision) }.toString()
    }

    fun requireDeleted(raw: String) {
        require(envelope(raw)["result"] == JsonPrimitive(true)) { "预算删除响应不完整" }
    }

    fun parseCategoryLevel(raw: String, parentId: Long): List<MonthlyBudgetCategory> {
        val result = envelope(raw)["result"] as? JsonObject ?: error("分类响应不完整")
        require(result.keys.all { it == "2" }) { "分类类型不一致" }
        val items = if (result.isEmpty()) JsonArray(emptyList()) else result["2"] as? JsonArray ?: error("分类响应不完整")
        val categories = items.map { item ->
            val data = item as? JsonObject ?: error("分类响应不完整")
            require(data.number("type") == 2L && data.id("parentId", allowZero = true) == parentId) { "分类归属不一致" }
            MonthlyBudgetCategory(data.id("id"), data.string("name").also { require(it.isNotBlank()) }, parentId, data.flag("hidden"))
        }
        require(categories.map { it.id }.distinct().size == categories.size) { "分类响应包含重复记录" }
        return categories
    }

    /** Account and transaction scope/currency must be explicit; no legacy default guesses. */
    fun parseExpenses(accountRaw: String, transactionRaw: String, ledgerId: Long, yearMonth: Int, zone: ZoneId): MonthlyBudgetExpenseSnapshot {
        require(ledgerId >= 0)
        requireBudgetMonth(yearMonth)
        val accounts = linkedMapOf<Long, String>()
        val accountIds = mutableSetOf<Long>()
        fun walkAccount(item: JsonElement) {
            val data = item as? JsonObject ?: error("账户响应不完整")
            require(data.id("ledgerId", allowZero = true) == ledgerId) { "账户不属于当前账本" }
            val id = data.id("id")
            require(accountIds.add(id)) { "账户响应包含重复记录" }
            when (data.number("type")) {
                1L -> accounts[id] = data.string("currency").also(::requireBudgetCurrency)
                2L -> {
                    val children = data["subAccounts"]
                    if (children != null && children != JsonNull) (children as? JsonArray ?: error("账户响应不完整")).forEach(::walkAccount)
                }
                else -> error("账户类型无效")
            }
        }
        when (val result = envelope(accountRaw)["result"]) {
            is JsonArray -> result.forEach(::walkAccount)
            is JsonObject -> result.values.forEach { (it as? JsonArray ?: error("账户响应不完整")).forEach(::walkAccount) }
            else -> error("账户响应不完整")
        }
        val result = envelope(transactionRaw)["result"] as? JsonObject ?: error("流水响应不完整")
        val rows = result["items"] as? JsonArray ?: error("流水响应不完整")
        val month = YearMonth.of(yearMonth / 100, yearMonth % 100)
        val start = month.atDay(1).atStartOfDay(zone).toEpochSecond()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
        val expenses = linkedMapOf<String, BigInteger>()
        val categoryExpenses = linkedMapOf<String, MutableMap<Long, BigInteger>>()
        val ids = mutableSetOf<Long>()
        rows.forEach { item ->
            val data = item as? JsonObject ?: error("流水响应不完整")
            require(ids.add(data.id("id"))) { "流水响应包含重复记录" }
            require(data.id("ledgerId", allowZero = true) == ledgerId) { "流水不属于当前账本" }
            val type = data.number("type")
            require(type in 1..5) { "流水类型无效" }
            val time = data.number("time")
            if (type == 3L) {
                val currency = accounts[data.id("sourceAccountId")] ?: error("流水缺少来源账户或币种")
                val category = data.id("categoryId")
                val amount = BigInteger.valueOf(data.number("sourceAmount"))
                if (time >= start && time < end) {
                    expenses[currency] = (expenses[currency] ?: BigInteger.ZERO) + amount
                    val categories = categoryExpenses.getOrPut(currency) { linkedMapOf() }
                    categories[category] = (categories[category] ?: BigInteger.ZERO) + amount
                }
            }
        }
        return MonthlyBudgetExpenseSnapshot(accounts.values.toSet(), expenses, categoryExpenses)
    }
}
