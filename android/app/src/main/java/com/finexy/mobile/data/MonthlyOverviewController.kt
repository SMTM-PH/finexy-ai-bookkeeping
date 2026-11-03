package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId

internal data class MonthlyOverviewContext(val environment: LedgerReportEnvironmentState,
    val month: YearMonth, val zone: ZoneId, val includeHidden: Boolean = true) {
    val ledgerId: Long? get() = environment.context?.ledgerId
    fun query(): LedgerReportQuery = LedgerReportForm(month.atDay(1).toString(), month.atEndOfMonth().toString(),
        zone.id, hidden = includeHidden).query(requireNotNull(ledgerId))
}
internal enum class MonthlyBudgetReadKind { NOT_LOADED, LOADING, UNAVAILABLE, EMPTY, READY, FAILURE }
internal data class MonthlyOverviewBudget(val kind: MonthlyBudgetReadKind = MonthlyBudgetReadKind.NOT_LOADED,
    val value: MonthlyBudget? = null, val message: String = "尚未读取预算。")
internal data class MonthlyOverviewState(val context: MonthlyOverviewContext? = null,
    val report: LedgerReportState = LedgerReportState(), val budget: MonthlyOverviewBudget = MonthlyOverviewBudget())

/** A limit has no timezone: usage is the selected IANA natural month, including hidden rows. */
internal fun MonthlyOverviewState.budgetUsage(): MonthlyBudgetProgress? {
    val selected = context ?: return null
    val limit = budget.value ?: return null
    val page = report.report ?: return null
    if (budget.kind != MonthlyBudgetReadKind.READY || !report.completeOnlineSource || !selected.includeHidden ||
        page.query != selected.query() || limit.ledgerId != selected.ledgerId ||
        limit.yearMonth != selected.month.year * 100 + selected.month.monthValue) return null
    val netExpense = page.totals.singleOrNull { it.currency == limit.currency }?.expense ?: 0L
    return monthlyBudgetProgress(limit.amountMinor, netExpense)
}
internal fun MonthlyOverviewState.budgetExpenseMinor(): Long? {
    budgetUsage() ?: return null
    return report.report!!.totals.singleOrNull { it.currency == budget.value!!.currency }?.expense ?: 0L
}
internal fun monthlyOverviewDrill(context: MonthlyOverviewContext, kind: String, currency: String? = null): LedgerReportQuery {
    val original = context.query()
    val types = when (kind) { "income" -> setOf(2); "expense" -> setOf(3); "net" -> setOf(2, 3)
        "transfer" -> setOf(4); "adjustment" -> setOf(1); "all" -> setOf(1, 2, 3, 4); else -> error("不支持此类下钻") }
    return LedgerReportForm.from(original).copy(types = types, currencies = currency?.let(::setOf) ?: emptySet()).query(original.ledgerId)
}

/** Read-only home owner: no Room, writes or Workers.
 * One atomic StateFlow prevents combine(selected, report) from briefly pairing a
 * new identity with a previous identity's equal-query financial result. */
internal class MonthlyOverviewController(
    private val environment: () -> LedgerReportEnvironmentState,
    transport: LedgerReportTransport,
    private val readBudget: suspend (Long, Int) -> MonthlyBudget?
) {
    private val monitor = Any()
    private var selected: MonthlyOverviewContext? = null
    private var generation = 0L
    private var closed = false
    private val mutable = MutableStateFlow(MonthlyOverviewState())
    val state: StateFlow<MonthlyOverviewState> = mutable.asStateFlow()
    private val reports = LedgerReportController(transport, {
        val captured = selected
        captured?.environment?.context?.takeIf { captured.environment == environment() && !closed }
    })

    fun bind(month: YearMonth, zone: ZoneId, includeHidden: Boolean = true) = synchronized(monitor) {
        check(!closed)
        generation++; selected = null; reports.invalidate(); mutable.value = MonthlyOverviewState()
        requireBudgetMonth(month.year * 100 + month.monthValue)
        require(month.year <= 9998)
        val next = MonthlyOverviewContext(environment(), month, zone, includeHidden)
        selected = next
        reports.bind(next.environment.context, next.environment.availability)
        val budget = if (next.environment.context == null || next.environment.availability != LedgerReportAvailability.ONLINE)
            MonthlyOverviewBudget(MonthlyBudgetReadKind.UNAVAILABLE, message = "完整在线预算暂不可用；未将缺失当作零。")
        else MonthlyOverviewBudget()
        mutable.value = MonthlyOverviewState(next, reports.state.value, budget)
    }
    /** Called synchronously by the report session/sync/network observers. */
    fun refreshEnvironment(): Boolean = synchronized(monitor) {
        val current = selected ?: return false
        if (current.environment == environment()) return false
        bind(current.month, current.zone, current.includeHidden); true
    }
    fun leave() = synchronized(monitor) {
        generation++; selected = null; reports.invalidate(); mutable.value = MonthlyOverviewState()
    }
    fun close() = synchronized(monitor) { leave(); closed = true; reports.close() }
    private fun accepts(serial: Long, captured: MonthlyOverviewContext): Boolean =
        !closed && serial == generation && selected === captured && captured.environment == environment()

    suspend fun load() = coroutineScope {
        val (serial, captured) = synchronized(monitor) {
            val context = selected ?: return@coroutineScope
            if (context.environment != environment()) { refreshEnvironment(); return@coroutineScope }
            if (context.environment.context == null || context.environment.availability != LedgerReportAvailability.ONLINE) return@coroutineScope
            val next = ++generation
            mutable.value = MonthlyOverviewState(context,
                LedgerReportState(ledgerId = context.ledgerId, query = context.query(), source = LedgerReportSource.NOT_LOADED,
                    operation = LedgerReportOperation.QUERY),
                MonthlyOverviewBudget(MonthlyBudgetReadKind.LOADING, message = "正在读取该月预算…"))
            next to context
        }
        launch {
            if (!synchronized(monitor) { accepts(serial, captured) }) return@launch
            try { reports.load(captured.query(), count = 1) }
            finally {
                synchronized(monitor) { if (accepts(serial, captured)) mutable.value = mutable.value.copy(report = reports.state.value) }
            }
        }
        launch {
            if (!synchronized(monitor) { accepts(serial, captured) }) return@launch
            try {
                val month = captured.month.year * 100 + captured.month.monthValue
                val value = readBudget(requireNotNull(captured.ledgerId), month)
                require(value == null || value.ledgerId == captured.ledgerId && value.yearMonth == month && value.id > 0 &&
                    value.amountMinor in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT && value.revision in 1..MAXIMUM_MONTHLY_BUDGET_REVISION &&
                    value.categoryAmounts.all { (id, amount) -> id > 0 && amount in 1..MAXIMUM_MONTHLY_BUDGET_AMOUNT })
                value?.currency?.let(::requireBudgetCurrency)
                synchronized(monitor) { if (accepts(serial, captured)) mutable.value = mutable.value.copy(budget = MonthlyOverviewBudget(
                    if (value == null) MonthlyBudgetReadKind.EMPTY else MonthlyBudgetReadKind.READY, value,
                    if (value == null) "该月尚未设置预算。" else "本账本该月固定币种额度。")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = (error as? ApiException)?.status
                val message = when {
                    status == 401 -> "登录已失效，请重新登录后读取预算。"
                    status == 403 -> "当前账本权限已变化，请重新核验访问权限。"
                    status == 404 || status == 405 -> "该月预算暂不可用，请核对账本及服务器支持情况。"
                    error is IOException -> "无法取得该月预算，请恢复网络后重试。"
                    error is IllegalArgumentException -> "预算响应不完整，请重新读取。"
                    else -> "未能读取该月预算，请重试。"
                }
                synchronized(monitor) { if (accepts(serial, captured)) mutable.value = mutable.value.copy(
                    budget = MonthlyOverviewBudget(MonthlyBudgetReadKind.FAILURE, message = message)) }
            }
        }
    }
}
