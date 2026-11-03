package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** Online settings of the ledger captured at entry; no local budget or transaction replica. */
@Composable
internal fun MonthlyBudgetPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, ledgerId: Long,
    showAmountsByDefault: Boolean = true, gateway: MonthlyBudgetGateway? = null, onBack: () -> Unit) {
    val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), localMode)
    val zone = remember { ZoneId.systemDefault() }
    var month by remember { mutableStateOf(YearMonth.now(zone)) }
    val context = MonthlyBudgetContext(identity, ledgerId, month.year * 100 + month.monthValue)
    val currentContext = rememberUpdatedState(context)
    val api = remember(identity, gateway) { gateway ?: MonthlyBudgetApiGateway(FinexyApi(store)) }
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    val ledgerName = if (ledgerId == LedgerEntity.DEFAULT_LEDGER_ID) "默认个人账本" else ledgers.firstOrNull { it.id == ledgerId }?.name ?: "当前账本"
    val scope = rememberCoroutineScope()
    val budgetGate = remember { MonthlyBudgetRequestGate() }
    val categoryGate = remember { MonthlyBudgetRequestGate() }
    val expenseGate = remember { MonthlyBudgetRequestGate() }
    var refresh by remember { mutableIntStateOf(0) }
    var categoryRefresh by remember { mutableIntStateOf(0) }
    var expenseRefresh by remember { mutableIntStateOf(0) }
    var budget by remember(context) { mutableStateOf<MonthlyBudget?>(null) }
    var role by remember(context) { mutableStateOf<Int?>(null) }
    var loading by remember(context) { mutableStateOf(!localMode) }
    var loaded by remember(context) { mutableStateOf(false) }
    var loadError by remember(context) { mutableStateOf<String?>(null) }
    var categories by remember(context) { mutableStateOf<List<MonthlyBudgetCategory>?>(null) }
    var categoryError by remember(context) { mutableStateOf<String?>(null) }
    var expenses by remember(context) { mutableStateOf<MonthlyBudgetExpenseSnapshot?>(null) }
    var expenseError by remember(context) { mutableStateOf<String?>(null) }
    var editing by remember(context) { mutableStateOf(false) }
    var amountText by remember(context) { mutableStateOf("") }
    var currencyText by remember(context) { mutableStateOf("") }
    var categoryInputs by remember(context) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var showCategories by remember(context) { mutableStateOf(false) }
    var busy by remember(context) { mutableStateOf(false) }
    var actionError by remember(context) { mutableStateOf<String?>(null) }
    var message by remember(context) { mutableStateOf<String?>(null) }
    var deleting by remember(context) { mutableStateOf(false) }
    var needsRead by remember(context) { mutableStateOf(false) }
    var pendingDraft by remember(context) { mutableStateOf<MonthlyBudgetDraft?>(null) }
    var pendingDelete by remember(context) { mutableStateOf(false) }
    var baselineRevision by remember(context) { mutableLongStateOf(0) }
    var deleteRevision by remember(context) { mutableLongStateOf(0) }
    var conflict by remember(context) { mutableStateOf(false) }
    var conflictReadReady by remember(context) { mutableStateOf(false) }
    var showAmounts by remember(context) { mutableStateOf(showAmountsByDefault) }
    var leaveConfirm by remember(context) { mutableStateOf(false) }

    fun currentIdentity() = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), localMode)
    fun accepts(gate: MonthlyBudgetRequestGate, ticket: MonthlyBudgetTicket) = gate.accepts(ticket, currentContext.value, currentIdentity())
    suspend fun readRole(): Int = api.role(ledgerId)
    fun reload() { refresh++; categoryRefresh++; expenseRefresh++ }
    fun openEditor() {
        amountText = budget?.let { monthlyBudgetAmountInput(it.amountMinor) }.orEmpty()
        currencyText = budget?.currency.orEmpty()
        categoryInputs = budget?.categoryAmounts.orEmpty().mapValues { monthlyBudgetAmountInput(it.value) }
        baselineRevision = budget?.revision ?: 0
        showCategories = categoryInputs.isNotEmpty()
        actionError = null; message = null; editing = true
    }

    fun back() { if (!busy) { if (editing || needsRead) leaveConfirm = true else onBack() } }
    BackHandler(onBack = ::back)
    LaunchedEffect(context, refresh) {
        if (localMode) return@LaunchedEffect
        val ticket = budgetGate.begin(context)
        loading = true; loadError = null; role = null; budget = null; loaded = false
        try {
            val updatedRole = readRole()
            val remote = api.get(ledgerId, context.yearMonth)
            if (accepts(budgetGate, ticket)) {
                role = updatedRole; budget = remote; loaded = true
                if (conflict) {
                    conflictReadReady = true
                    message = "已读取服务器预算；请明确采用当前版本后再保存。当前输入保留。"
                } else if (needsRead) {
                    val confirmed = if (pendingDelete) remote == null else pendingDraft?.let { draft ->
                        remote != null && remote.amountMinor == draft.amountMinor && remote.currency == draft.currency && remote.categoryAmounts == draft.categoryAmounts
                    } == true
                    if (confirmed) { editing = false; message = if (pendingDelete) "已核对：预算已删除" else "已核对：预算已保存" }
                    else message = "已重新读取服务器预算，请核对后再操作。"
                    needsRead = false; pendingDraft = null; pendingDelete = false; actionError = null
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (accepts(budgetGate, ticket)) loadError = "预算加载失败，请检查网络、登录状态或账本权限后重试。"
        } finally { if (accepts(budgetGate, ticket)) loading = false }
    }
    LaunchedEffect(context, categoryRefresh) {
        if (localMode) return@LaunchedEffect
        val ticket = categoryGate.begin(context)
        categories = null; categoryError = null
        try {
            val remote = api.categories(ledgerId)
            if (accepts(categoryGate, ticket)) categories = remote
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (accepts(categoryGate, ticket)) categoryError = "分类读取失败，请重试；未加载的分类不能用于新增预算。"
        }
    }
    LaunchedEffect(context, expenseRefresh) {
        if (localMode) return@LaunchedEffect
        val ticket = expenseGate.begin(context)
        expenses = null; expenseError = null
        try {
            val remote = api.expenses(ledgerId, context.yearMonth, zone)
            if (accepts(expenseGate, ticket)) expenses = remote
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (accepts(expenseGate, ticket)) expenseError = "预算用量读取失败或数据不完整，请重试。"
        }
    }

    fun submit(draft: MonthlyBudgetDraft?, delete: Boolean) {
        if (busy || needsRead || !showAmounts || !monthlyBudgetCanManage(role)) return
        val ticket = budgetGate.begin(context)
        busy = true; actionError = null; message = null
        scope.launch {
            var requestStarted = false
            try {
                val currentRole = readRole()
                if (!accepts(budgetGate, ticket)) return@launch
                role = currentRole
                if (!monthlyBudgetCanManage(currentRole)) {
                    actionError = "角色已变化，当前只能查看预算。"
                    return@launch
                }
                requestStarted = true
                val saved = if (delete) { api.delete(ledgerId, context.yearMonth, deleteRevision); null }
                    else api.set(ledgerId, requireNotNull(draft))
                if (accepts(budgetGate, ticket)) {
                    budget = saved; loaded = true; loadError = null; editing = false
                    message = if (delete) "预算已删除；流水和账户余额保留。" else "预算已保存"
                    expenseRefresh++
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (accepts(budgetGate, ticket)) {
                    if (failure is ApiException && failure.status == 409) {
                        conflict = true; conflictReadReady = false; needsRead = true
                        pendingDraft = null; pendingDelete = false
                        actionError = "服务器预算已变化，请刷新核对并明确采用当前版本后再保存；当前输入已保留。"
                    } else {
                        if (failure is ApiException && failure.status in listOf(401, 403, 404)) {
                            role = null; budget = null; loaded = false
                            loadError = "账本访问或管理权限已变化，请重新读取预算。"
                        }
                        needsRead = requestStarted; pendingDraft = if (requestStarted) draft else null; pendingDelete = delete && requestStarted
                        actionError = if (requestStarted) "操作结果尚未确认，请刷新预算核对；当前输入已保留。" else "权限检查失败，请刷新预算后重试；当前输入已保留。"
                    }
                }
            } finally { if (accepts(budgetGate, ticket)) busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(enabled = !busy, onClick = ::back, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回设置") }
                Text("月度预算", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("账本：$ledgerName", style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !busy, onClick = { showAmounts = !showAmounts }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showAmounts) "隐藏预算金额" else "显示预算金额") }
                Text("按设备时区 ${zone.id} 的自然月统计；仅计预算币种支出，退款冲减支出，转账和余额调整不计入。", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !busy && !editing && !needsRead && month > YearMonth.of(2000, 1), onClick = { month = month.minusMonths(1) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "查看上月预算" }) { Text("上月") }
                    TextButton(enabled = !busy && !editing && !needsRead && month < YearMonth.of(9999, 12), onClick = { month = month.plusMonths(1) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "查看下月预算" }) { Text("下月") }
                    TextButton(enabled = !localMode && !busy && !loading, onClick = ::reload, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新预算") }
                }
                message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                actionError?.let { BudgetFailure(it, if (busy) null else ::reload) }
                val baselineChanged = editing && loaded && (budget?.revision ?: 0) != baselineRevision
                if ((conflict && conflictReadReady) || baselineChanged) {
                    TextButton(enabled = !busy && !loading && loaded && showAmounts && monthlyBudgetCanManage(role), onClick = {
                        baselineRevision = budget?.revision ?: 0
                        conflict = false; conflictReadReady = false; needsRead = false
                        actionError = null; message = "已采用服务器当前版本。我的输入保留，点击保存后才覆盖此版本。"
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("采用服务器当前版本，保留我的输入") }
                }
                when {
                    localMode -> Text("预算保存在服务器。本地模式暂不提供预算管理。", color = Muted)
                    loading -> Text("正在加载当前账本预算…", color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    loadError != null -> BudgetFailure(loadError!!, ::reload)
                    loaded -> {
                        Text(when (role) { 1 -> "我的角色：所有者"; 2 -> "我的角色：管理员"; 3 -> "我的角色：普通成员，仅查看预算"; else -> "我的角色：只读成员，仅查看预算" }, color = Muted)
                        val currentBudget = budget
                        if (currentBudget == null) Text("本月尚未设置预算。", color = Muted)
                        else PanelCard {
                            Text("本月总预算", style = MaterialTheme.typography.titleMedium)
                            Text(if (showAmounts) currencyMoney(currentBudget.amountMinor, currentBudget.currency) else "预算金额已隐藏", style = MaterialTheme.typography.titleLarge)
                            when {
                                !showAmounts -> Text("已用、剩余与使用率已隐藏", color = Muted)
                                expenseError != null -> BudgetFailure(expenseError!!, { expenseRefresh++ })
                                expenses == null -> Text("正在读取完整账本用量…", color = Muted)
                                else -> BudgetUsageSummary(currentBudget.currency, currentBudget.amountMinor, expenses!!.usage(currentBudget.currency).expenseMinor)
                            }
                            if (currentBudget.categoryAmounts.isNotEmpty()) {
                                Text("分类预算", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                                currentBudget.categoryAmounts.forEach { (id, amount) ->
                                    Text(categories?.firstOrNull { it.id == id }?.name ?: "暂不可用的分类", modifier = Modifier.padding(top = 12.dp))
                                    Text(if (showAmounts) "限额 ${currencyMoney(amount, currentBudget.currency)}" else "分类预算金额已隐藏", color = Muted)
                                    if (showAmounts && expenses != null && expenseError == null) BudgetUsageSummary(currentBudget.currency, amount, expenses!!.usage(currentBudget.currency).categoryAmounts[id] ?: if (expenses!!.usage(currentBudget.currency).categoryAmounts.containsKey(id)) null else 0L)
                                }
                            }
                        }
                        if (monthlyBudgetCanManage(role) && !editing) {
                            Button(enabled = !busy && !needsRead && showAmounts, onClick = ::openEditor, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (budget == null) "设置本月预算" else "编辑本月预算") }
                            if (budget != null) TextButton(enabled = !busy && !needsRead && showAmounts, onClick = { deleteRevision = budget!!.revision; deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除本月预算", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (editing && !showAmounts) Text("显示金额后才能核对或编辑预算；当前输入保留。", color = Muted)
                if (editing && showAmounts && role != null && !localMode) {
                    val editable = !busy && !needsRead && !baselineChanged && monthlyBudgetCanManage(role)
                    PanelCard {
                        Text("预算设置", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(currencyText, { currencyText = it.uppercase(Locale.ROOT) }, enabled = editable,
                            label = { Text("币种代码，如 CNY / USD") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        Text("同一账本每月仅一份预算；其他币种不折算、不累加。币种需明确选择。", color = Muted, style = MaterialTheme.typography.bodySmall)
                        expenses?.currencies?.sorted()?.forEach { currency ->
                            TextButton(enabled = editable, onClick = { currencyText = currency }, modifier = Modifier.heightIn(min = 48.dp)) { Text("使用 $currency") }
                        }
                        OutlinedTextField(amountText, { amountText = it }, enabled = editable, label = { Text("月度总预算金额") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !busy, onClick = { showCategories = !showCategories }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showCategories) "收起分类预算（可选）" else "展开分类预算（可选）") }
                        if (showCategories) {
                            when {
                                categoryError != null -> BudgetFailure(categoryError!!, { categoryRefresh++ })
                                categories == null -> Text("正在加载支出分类…", color = Muted)
                                categories!!.isEmpty() -> Text("本账本没有可见末级支出分类，可仅设置总预算。", color = Muted)
                                else -> categories!!.forEach { category ->
                                    OutlinedTextField(categoryInputs[category.id].orEmpty(), { value -> categoryInputs = categoryInputs + (category.id to value) },
                                        enabled = editable, label = { Text(category.name) }, supportingText = { Text("留空表示不设置该分类预算") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                                }
                            }
                            if (categories != null) categoryInputs.keys.filter { id -> categories!!.none { it.id == id } }.forEach { id ->
                                Text("已有分类已不可用，保留金额 ${categoryInputs[id]}；保存前请明确移除。", color = MaterialTheme.colorScheme.error)
                                TextButton(enabled = editable, onClick = { categoryInputs = categoryInputs - id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除失效分类预算") }
                            }
                        }
                        Button(enabled = editable, onClick = {
                            try {
                                val amounts = categoryInputs.filterValues { it.isNotBlank() }.mapValues { parseMonthlyBudgetAmount(it.value) }
                                require(amounts.isEmpty() || (categories != null && amounts.keys.all { id -> categories!!.any { it.id == id } })) { "请先加载分类并移除失效分类预算" }
                                val draft = MonthlyBudgetDraft(context.yearMonth, parseMonthlyBudgetAmount(amountText), currencyText.trim(), amounts, baselineRevision)
                                draft.payload(ledgerId)
                                submit(draft, false)
                            } catch (invalid: IllegalArgumentException) { actionError = invalid.message ?: "预算字段无效，请检查后重试。" }
                        }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Text(if (busy) "正在保存…" else "保存本月预算") }
                        TextButton(enabled = !busy && !needsRead, onClick = { editing = false; actionError = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消编辑") }
                    }
                }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除本月预算") },
        text = { Text("删除「$ledgerName」${month.year} 年 ${month.monthValue} 月的总预算和分类预算，保留全部流水与账户余额。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { deleting = false; submit(null, true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除预算", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    if (leaveConfirm) AlertDialog(onDismissRequest = { leaveConfirm = false }, title = { Text("离开预算设置") },
        text = { Text(if (needsRead) "本次操作仍需读取服务器核对。离开会丢弃本页输入，重新进入后请先核对服务器预算。" else "离开会丢弃未保存的预算输入，服务器预算保持当前状态。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { leaveConfirm = false; onBack() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("放弃输入并离开") } },
        dismissButton = { TextButton(onClick = { leaveConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续编辑") } })
}

@Composable
private fun BudgetFailure(message: String, onRetry: (() -> Unit)?) {
    Column {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { error(message); liveRegion = LiveRegionMode.Polite })
        if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试读取") }
    }
}

@Composable
private fun BudgetUsageSummary(currency: String, amountMinor: Long, expenseMinor: Long?) {
    if (expenseMinor == null) {
        Text("用量超出可汇总范围，无法显示进度。", color = MaterialTheme.colorScheme.error)
        return
    }
    val progress = monthlyBudgetProgress(amountMinor, expenseMinor)
    val color = when (progress.warning) { 2 -> MaterialTheme.colorScheme.error; 1 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.secondary }
    Text("已用 ${currencyMoney(expenseMinor, currency)}", modifier = Modifier.padding(top = 8.dp))
    Text("剩余 $currency ${String.format(Locale.CHINA, "%,.2f", BigDecimal(progress.remainingMinor, 2))}")
    Text("${progress.percentage.toPlainString()}% · ${when (progress.warning) { 2 -> "已达到或超过预算"; 1 -> "接近预算上限"; else -> "预算内" }}", color = color)
    LinearProgressIndicator(progress = { progress.fill }, color = color, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 6.dp))
}
