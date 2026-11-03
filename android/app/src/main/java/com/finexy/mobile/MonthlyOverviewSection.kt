package com.finexy.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import java.time.YearMonth
import java.time.ZoneId

/** Insert into Dashboard's existing scroll/safe-area container. No new tabs/FAB.
 * showAmounts comes from Dashboard's actual privacy toggle, never query.hidden. */
@Composable
internal fun MonthlyOverviewSection(state: MonthlyOverviewState, month: YearMonth, zone: ZoneId, showAmounts: Boolean,
    canManageBudget: Boolean, onScope: (YearMonth, ZoneId) -> Unit, onReload: () -> Unit,
    onReport: (LedgerReportQuery) -> Unit, onBudget: (Long, YearMonth) -> Unit) {
    var monthText by rememberSaveable(month.toString()) { mutableStateOf(month.toString()) }
    var zoneText by rememberSaveable(zone.id) { mutableStateOf(zone.id) }
    var formError by remember { mutableStateOf<String?>(null) }
    var editingScope by rememberSaveable { mutableStateOf(false) }
    val selected = state.context
    val page = state.report.report?.takeIf { selected != null && it.query == selected.query() && state.report.completeOnlineSource }
    fun amount(value: Long): String = if (showAmounts) LedgerReportContract.formatAmount(value) else "••••••"
    PanelCard {
        Text("月度总览", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { runCatching { month.minusMonths(1).also { requireBudgetMonth(it.year * 100 + it.monthValue) } }.onSuccess { onScope(it, zone) }.onFailure { formError = "该月份超出支持范围。" } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上月") }
            TextButton(onClick = { onScope(YearMonth.now(zone), zone) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("本月") }
            TextButton(onClick = { runCatching { month.plusMonths(1).also { requireBudgetMonth(it.year * 100 + it.monthValue); require(it.year <= 9998) } }.onSuccess { onScope(it, zone) }.onFailure { formError = "该月份超出支持范围。" } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("下月") }
        }
        TextButton(onClick = { editingScope = !editingScope }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (editingScope) "收起月份与时区" else "调整月份与时区") }
        if (editingScope) {
        OutlinedTextField(monthText, { monthText = it }, label = { Text("月份（YYYY-MM）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(zoneText, { zoneText = it }, label = { Text("报告时区（IANA）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextButton(onClick = {
            runCatching {
                val next = YearMonth.parse(monthText.trim())
                requireBudgetMonth(next.year * 100 + next.monthValue)
                next to ZoneId.of(zoneText.trim())
            }.onSuccess { (next, timezone) -> formError = null; onScope(next, timezone); editingScope = false }
                .onFailure { formError = "请选择有效月份和 IANA 时区，例如 Asia/Shanghai。" }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用月份与时区") }
        }
        formError?.let { message -> Text(message, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { error(message); liveRegion = LiveRegionMode.Polite }) }
        Text("$month · ${zone.id} 自然月 · ${if (selected?.includeHidden != false) "含隐藏记录" else "排除隐藏记录"} · 原币种，不折算", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        when {
            selected?.environment?.availability == LedgerReportAvailability.LOCAL_ONLY -> Text("仅本机记录，历史范围未核验；不作为完整月报。", color = Muted)
            selected?.environment?.availability == LedgerReportAvailability.SYNCHRONIZING -> Text("正在同步，完成后重新读取完整月报。", color = Muted)
            selected?.environment?.availability == LedgerReportAvailability.OFFLINE -> Text("离线，完整月报暂不可用。", color = Muted)
            state.report.busy -> { CircularProgressIndicator(Modifier.size(24.dp)); Text("正在读取完整月报…") }
            state.report.failure != null -> {
                val message = state.report.failure.message
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { error(message); liveRegion = LiveRegionMode.Polite })
            }
            page != null -> {
                Text("完整在线汇总 · ${page.matchedCount} 笔逻辑流水", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (page.matchedCount == 0) Text("该月暂无流水。")
                page.totals.forEach { money ->
                    Spacer(Modifier.height(12.dp)); Text(money.currency, style = MaterialTheme.typography.titleMedium)
                    val metrics = listOf(Triple("收入", money.income, "income"), Triple("净支出（含退款）", money.expense, "expense"),
                        Triple("收支结余", money.net, "net"), Triple("转入", money.transferIn, "transfer"),
                        Triple("转出", money.transferOut, "transfer"), Triple("余额调整", money.adjustment, "adjustment"))
                    metrics.forEach { (label, value, kind) ->
                        TextButton(onClick = { onReport(monthlyOverviewDrill(selected!!, kind, money.currency)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) { Text(label, color = Muted); Text(amount(value), color = Ink, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                    Text("退款已冲减净支出，不计作收入。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onReport(monthlyOverviewDrill(selected!!, "expense", money.currency)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看同范围支出与退款") }
                }
                TextButton(onClick = { onReport(selected!!.query()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看该月完整报告") }
            }
            else -> Text("尚未读取完整月报。", color = Muted)
        }
        TextButton(onClick = onReload, enabled = selected?.environment?.availability == LedgerReportAvailability.ONLINE,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新读取月度总览") }
    }
    PanelCard {
        Text("该月预算", style = MaterialTheme.typography.titleMedium)
        Text(state.budget.message, color = if (state.budget.kind == MonthlyBudgetReadKind.FAILURE) MaterialTheme.colorScheme.error else Muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val limit = state.budget.value
        if (limit != null) {
            Text("额度 · ${limit.currency} ${amount(limit.amountMinor)}", fontFamily = FontFamily.Monospace)
            val usage = state.budgetUsage()
            val expense = state.budgetExpenseMinor()
            if (!showAmounts) Text("使用额 •••••• · 剩余 ••••••")
            else if (usage != null && expense != null) {
                Text("净支出 ${amount(expense)} · 剩余 ${limit.currency} ${usage.remainingMinor.let { java.math.BigDecimal(it, 2).toPlainString() }}")
                Text("已用 ${usage.percentage.toPlainString()}%", color = if (usage.warning == 2) MaterialTheme.colorScheme.error else if (usage.warning == 1) MaterialTheme.colorScheme.tertiary else Muted)
                LinearProgressIndicator(progress = { usage.fill }, modifier = Modifier.fillMaxWidth(), color = if (usage.warning == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onReport(monthlyOverviewDrill(selected!!, "expense", limit.currency)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看同范围预算支出") }
            } else Text("完整月报暂不可用，或本页排除了隐藏记录；未计算使用额或使用率。", color = Muted)
            Text("按本页 ${zone.id} 自然月完整报告，仅计 ${limit.currency} 净支出，含隐藏记录；其他币种不折算。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (limit != null || canManageBudget && state.budget.kind == MonthlyBudgetReadKind.EMPTY) {
            TextButton(onClick = { selected?.ledgerId?.let { onBudget(it, month) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (limit == null) "设置该月预算" else "查看该月预算") }
        }
    }
    Text("待处理包含本账本全部月份，不随月度筛选变化。", color = Muted, style = MaterialTheme.typography.bodySmall)
}

/** Use in Dashboard's existing scroll container; rotation retains the owner and pure selection. */
@Composable
internal fun MonthlyOverviewHome(store: SecureStore, ledgerId: Long, showAmounts: Boolean, canManageBudget: Boolean,
    onReport: (LedgerReportQuery) -> Unit, onBudget: (Long, YearMonth) -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val model: MonthlyOverviewViewModel = viewModel(key = "monthly-overview-${store.namespace}")
    var monthText by rememberSaveable(store.namespace) { mutableStateOf(YearMonth.now().toString()) }
    var zoneText by rememberSaveable(store.namespace) { mutableStateOf(ZoneId.systemDefault().id) }
    val month = YearMonth.parse(monthText)
    val zone = ZoneId.of(zoneText)
    val observed by model.state.collectAsState()
    val state = observed.takeIf { it.context?.ledgerId == ledgerId && it.context.environment.sessionRevision == store.sessionRevision }
        ?: MonthlyOverviewState()
    DisposableEffect(model, store.namespace, ledgerId, month, zone) {
        model.enter(context.applicationContext, store, ledgerId, month, zone)
        onDispose { if (activity?.isChangingConfigurations != true) model.leave() }
    }
    MonthlyOverviewSection(state, month, zone, showAmounts, canManageBudget,
        onScope = { next, timezone -> monthText = next.toString(); zoneText = timezone.id }, onReload = model::reload,
        onReport = onReport, onBudget = onBudget)
}
