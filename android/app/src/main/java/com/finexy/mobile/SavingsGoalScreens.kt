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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finexy.mobile.data.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val goalDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

/**
 * Savings goals of one ledger. Deposits and withdrawals only move funds
 * inside the ledger as transfer transactions. They never count as income or
 * expense. The saved amount always comes from the server snapshot.
 */
@Composable
internal fun SavingsGoalsPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, initialLedgerId: Long = LedgerEntity.DEFAULT_LEDGER_ID, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    var selectedLedgerId by remember(initialLedgerId) { mutableStateOf(initialLedgerId) }
    val goals by repository.observeSavingsGoals(selectedLedgerId).collectAsState(initial = null)
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var fundTarget by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var fundMode by remember { mutableStateOf("deposit") }
    var fundAccounts by remember { mutableStateOf<List<RemoteAccountOption>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }

    fun refreshGoals() {
        scope.launch {
            running = true; message = null
            runCatching {
                val visible = api.listLedgersIfEnabled() ?: throw IllegalStateException("存钱计划在本服务上不可用")
                repository.replaceLedgers(visible)
                val merged = visible.map { api.listSavingsGoals(it.id) }.flatten() + api.listSavingsGoals(LedgerEntity.DEFAULT_LEDGER_ID)
                repository.replaceSavingsGoals(merged)
            }.onFailure { message = "刷新失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }

    fun openFundEditor(goal: SavingsGoalEntity, mode: String) {
        fundTarget = goal; fundMode = mode
        scope.launch {
            runCatching { fundAccounts = api.listSavingsGoalAccounts(selectedLedgerId) }
                .onFailure { message = "读取可用账户失败：${it.message ?: "请稍后重试"}" }
        }
    }

    fun submitFund(goal: SavingsGoalEntity, amountText: String, accountId: Long, comment: String) {
        val amountMinor = Math.round((amountText.toDoubleOrNull() ?: -1.0) * 100)
        scope.launch {
            running = true; message = null
            runCatching {
                if (fundMode == "deposit") api.depositToSavingsGoal(goal.toRemote(), amountMinor, accountId, comment)
                else api.withdrawFromSavingsGoal(goal.toRemote(), amountMinor, accountId, comment)
                // The saved amount is server-derived: re-pull the full snapshot.
                val visible = api.listLedgersIfEnabled() ?: emptyList()
                repository.replaceSavingsGoals(visible.map { api.listSavingsGoals(it.id) }.flatten() + api.listSavingsGoals(LedgerEntity.DEFAULT_LEDGER_ID))
				fundAccounts = api.listSavingsGoalAccounts(selectedLedgerId)
				if (selectedLedgerId == LedgerEntity.DEFAULT_LEDGER_ID) {
					repository.mergeAccounts(api.parseAccountResponse(api.listAccounts()))
				}
            }.onSuccess { message = if (fundMode == "deposit") "已存入目标，不计入收支" else "已取出到账户，不计入收支" }
                .onFailure { message = "操作失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回设置" }) { Text("返回设置") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("存钱计划", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("存入会扣减账户余额，取出会增加账户余额；两者记为转账，不计入收支。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(enabled = !localMode && !running, onClick = { creating = true },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "存钱计划：新建目标" }) { Text("新建") }
                    TextButton(enabled = !localMode && !running, onClick = ::refreshGoals,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "刷新存钱计划" }) { Text("刷新") }
                }
                message?.let { Text(it, color = if (it.contains("失败") || it.contains("无法")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
                if (localMode) Text("本地模式没有服务端存钱计划。", color = Muted, fontSize = 13.sp)
                LedgerPicker(ledgers = ledgers, selectedLedgerId = selectedLedgerId, enabled = !running) { selectedLedgerId = it }
                when {
                    localMode -> {}
                    goals == null -> Text("加载中…", color = Muted, fontSize = 13.sp)
                    goals.orEmpty().isEmpty() -> Text("当前账本还没有存钱目标；点击“新建”创建第一个。", color = Muted, fontSize = 13.sp)
                    else -> goals.orEmpty().forEach { goal ->
                        SavingsGoalRow(goal, running || localMode,
                            onDeposit = { openFundEditor(goal, "deposit") },
                            onWithdraw = { openFundEditor(goal, "withdraw") },
                            onEdit = { editing = goal },
                            onDelete = { deleteTarget = goal })
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        SavingsGoalEditorDialog(existing = editing, onDismiss = { creating = false; editing = null },
            onSubmit = { draft ->
                val target = editing
                creating = false; editing = null
                scope.launch {
                    running = true; message = null
                    runCatching {
                        if (target == null) api.createSavingsGoal(selectedLedgerId, draft)
                        else api.modifySavingsGoal(target.toRemote(), draft)
                        val visible = api.listLedgersIfEnabled() ?: emptyList()
                        repository.replaceSavingsGoals(visible.map { api.listSavingsGoals(it.id) }.flatten() + api.listSavingsGoals(LedgerEntity.DEFAULT_LEDGER_ID))
                    }.onSuccess { message = if (target == null) "存钱目标已创建" else "存钱目标已更新" }
                        .onFailure { message = "保存失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            })
    }
    fundTarget?.let { goal ->
        SavingsGoalFundDialog(goal = goal, mode = fundMode, accounts = fundAccounts, running = running,
            onDismiss = { fundTarget = null },
            onSubmit = { amountText, accountId, comment ->
                fundTarget = null
                submitFund(goal, amountText, accountId, comment)
            })
    }
    deleteTarget?.let { goal ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text("删除存钱目标") },
            text = { Text("「${goal.name}」没有资金记录才能删除。删除后不能恢复。") },
            confirmButton = { TextButton(enabled = !running, onClick = {
                deleteTarget = null
                scope.launch {
                    running = true; message = null
                    runCatching { api.deleteSavingsGoal(goal.id); repository.removeSavingsGoal(goal.id) }
                        .onSuccess { message = "目标已删除" }
                        .onFailure { message = "删除失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
}

private fun SavingsGoalEntity.toRemote(): RemoteSavingsGoal = RemoteSavingsGoal(
    id = id, uid = uid, ledgerId = ledgerId, name = name, targetAmountMinor = targetAmountMinor,
    savedAmountMinor = savedAmountMinor, achieved = achieved, deadlineTime = deadlineTime, comment = comment
)

@Composable
private fun LedgerPicker(ledgers: List<LedgerEntity>, selectedLedgerId: Long, enabled: Boolean, onSelect: (Long) -> Unit) {
    Column {
        Text("账本", fontSize = 13.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selectedLedgerId == LedgerEntity.DEFAULT_LEDGER_ID, enabled = enabled,
                onClick = { onSelect(LedgerEntity.DEFAULT_LEDGER_ID) },
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "切换到默认个人账本" },
                label = { Text("默认个人账本") })
            ledgers.filter { it.type == LedgerEntity.TYPE_FAMILY }.forEach { ledger ->
                FilterChip(selected = selectedLedgerId == ledger.id, enabled = enabled,
                    onClick = { onSelect(ledger.id) },
                    modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "切换到${ledger.name}" },
                    label = { Text("${ledger.name}（家庭）") })
            }
            ledgers.filter { it.type != LedgerEntity.TYPE_FAMILY }.forEach { ledger ->
                FilterChip(selected = selectedLedgerId == ledger.id, enabled = enabled,
                    onClick = { onSelect(ledger.id) },
                    modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "切换到${ledger.name}" },
                    label = { Text(ledger.name) })
            }
        }
    }
}

@Composable
private fun SavingsGoalRow(goal: SavingsGoalEntity, disabled: Boolean, onDeposit: () -> Unit, onWithdraw: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(goal.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (goal.achieved) "已达成" else "进行中", color = if (goal.achieved) IncomeGreen else Muted, fontSize = 12.sp)
            }
            Text("¥ %.2f".format(goal.savedAmountMinor / 100.0), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            LinearProgressIndicator(
                progress = { (goal.savedAmountMinor * 100.0 / goal.targetAmountMinor).toFloat() / 100f },
                modifier = Modifier.fillMaxWidth().heightIn(min = 6.dp).semantics { contentDescription = "已存入 ${"%.1f".format(goal.savedAmountMinor * 100.0 / goal.targetAmountMinor)}%" },
            )
            Text("已存 ¥ %.2f / 目标 ¥ %.2f · 目标日期 %s · 存入不计收入，取出不计支出".format(
                goal.savedAmountMinor / 100.0, goal.targetAmountMinor / 100.0,
                if (goal.deadlineTime > 0) goalDateFormat.format(Date(goal.deadlineTime * 1000)) else "不限"),
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            TextButton(enabled = !disabled, onClick = onDeposit, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "存入：${goal.name}" }) { Text("存入") }
            TextButton(enabled = !disabled && goal.savedAmountMinor > 0, onClick = onWithdraw, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "取出：${goal.name}" }) { Text("取出") }
            TextButton(enabled = !disabled, onClick = onEdit, modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "编辑目标：${goal.name}" }) { Text("编辑") }
            TextButton(enabled = !disabled && goal.savedAmountMinor == 0L, onClick = onDelete,
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "删除目标：${goal.name}" }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SavingsGoalEditorDialog(existing: SavingsGoalEntity?, onDismiss: () -> Unit, onSubmit: (SavingsGoalDraft) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var target by remember { mutableStateOf(existing?.let { "%.2f".format(it.targetAmountMinor / 100.0) } ?: "") }
    var deadline by remember { mutableStateOf(existing?.deadlineTime?.takeIf { it > 0 }?.let { goalDateFormat.format(Date(it * 1000)) } ?: "") }
    var comment by remember { mutableStateOf(existing?.comment ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建存钱目标" else "编辑存钱目标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("创建或编辑目标本身不会移动资金。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("目标名称 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "目标名称" })
                OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("目标金额（CNY）*") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "目标金额" })
                OutlinedTextField(value = deadline, onValueChange = { deadline = it }, label = { Text("目标日期（yyyy-MM-dd，可选）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "目标日期" })
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "备注" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val amountMinor = Math.round((target.toDoubleOrNull() ?: -1.0) * 100)
            if (name.trim().isEmpty()) { error = "请输入目标名称。"; return@TextButton }
            if (amountMinor <= 0) { error = "目标金额必须大于 0。"; return@TextButton }
            val deadline = if (deadline.isBlank()) 0L else runCatching {
                goalDateFormat.parse(deadline.trim())?.time?.div(1000) ?: 0L
            }.getOrDefault(0L)
            onSubmit(SavingsGoalDraft(name, amountMinor, deadline, comment))
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun SavingsGoalFundDialog(goal: SavingsGoalEntity, mode: String, accounts: List<RemoteAccountOption>, running: Boolean, onDismiss: () -> Unit, onSubmit: (amountText: String, accountId: Long, comment: String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(0L) }
    var comment by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (mode == "deposit") "向「${goal.name}」存入" else "从「${goal.name}」取出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已存 ¥ %.2f / 目标 ¥ %.2f。".format(goal.savedAmountMinor / 100.0, goal.targetAmountMinor / 100.0) +
                    if (mode == "deposit") "存入不计入收入。" else "取出回到所选账户，不计入支出，最多不超过已存金额。",
                    color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金额（CNY）*") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "金额" })
                if (accounts.isEmpty()) {
                    Text("当前账本还没有可用账户；先在账户页添加。", color = Muted, fontSize = 12.sp)
                } else {
                    Column {
                        accounts.forEach { account ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = accountId == account.id, onClick = { accountId = account.id },
                                    modifier = Modifier.semantics { contentDescription = "选择账户：${account.name}" })
                                Text("${account.name}（¥ %.2f）".format(account.balanceMinor / 100.0), fontSize = 14.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "备注" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(enabled = !running, onClick = {
            if (accountId == 0L) { error = "请选择资金账户。"; return@TextButton }
            onSubmit(amount, accountId, comment)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (mode == "deposit") "确认存入" else "确认取出") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
internal fun LedgerManagementPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, onBack: () -> Unit) {
    BackHandler { onBack() }
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf(0L) }
    var members by remember { mutableStateOf<List<RemoteLedgerMember>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<RemoteLedgerInvitation>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var inviteOpen by remember { mutableStateOf(false) }
    var acceptOpen by remember { mutableStateOf(false) }
    var acceptPreview by remember { mutableStateOf<RemoteLedgerInvitationPreview?>(null) }
    var createdToken by remember { mutableStateOf<String?>(null) }
    var roleTarget by remember { mutableStateOf<RemoteLedgerMember?>(null) }
    var removeTarget by remember { mutableStateOf<RemoteLedgerMember?>(null) }
    var revokeTarget by remember { mutableStateOf<RemoteLedgerInvitation?>(null) }
    var deleteTarget by remember { mutableStateOf<LedgerEntity?>(null) }
    var deletePreview by remember { mutableStateOf<RemoteLedgerDeletePreview?>(null) }
    var overview by remember { mutableStateOf<RemoteLedgerOverview?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val api = remember(store) { FinexyApi(store) }
    val scope = rememberCoroutineScope()
    val canManage = members.firstOrNull { it.isCurrentUser && it.status == 1 }?.role in listOf(RemoteLedgerMember.ROLE_OWNER, RemoteLedgerMember.ROLE_ADMIN)
    val isOwner = members.firstOrNull { it.isCurrentUser && it.status == 1 }?.role == RemoteLedgerMember.ROLE_OWNER

    fun refresh(selected: Long = selectedId) {
        val generation = ++refreshGeneration
        scope.launch {
            running = true; message = null
            runCatching {
                val remote = api.listLedgersIfEnabled() ?: throw IllegalStateException("账本功能在本服务上不可用")
                repository.replaceLedgers(remote)
                val loadedOverview = api.getLedgerOverview(selected)
                if (selected > 0) {
                    val loadedMembers = api.listLedgerMembers(selected)
                    val me = loadedMembers.firstOrNull { it.isCurrentUser && it.status == 1 }
                    val loadedInvitations = if (me?.role in listOf(RemoteLedgerMember.ROLE_OWNER, RemoteLedgerMember.ROLE_ADMIN)) api.listLedgerInvitations(selected) else emptyList()
                    if (generation == refreshGeneration && selected == selectedId) { members = loadedMembers; invitations = loadedInvitations; overview = loadedOverview }
                } else if (generation == refreshGeneration && selected == selectedId) { members = emptyList(); invitations = emptyList(); overview = loadedOverview }
            }.onFailure { message = "刷新失败：${it.message ?: "请稍后重试"}" }
            if (generation == refreshGeneration) running = false
        }
    }
    LaunchedEffect(Unit) { if (!localMode) refresh() }
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Column(Modifier.safeDrawingPadding().imePadding().widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextButton(onClick = onBack) { Text("返回设置") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("账本管理", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }); Text("个人也可创建多个账本，每个账本独立管理成员。", color = Muted, fontSize = 13.sp) }
                TextButton(enabled = !localMode && !running, onClick = { refresh() }) { Text("刷新") }
            }
            message?.let { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
            Button(enabled = !localMode && !running, onClick = { createOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("创建账本") }
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("默认个人账本", fontWeight = FontWeight.SemiBold); Text("兼容账本 · 不支持添加成员", color = Muted, fontSize = 12.sp) }
                    TextButton(onClick = { selectedId = 0; refresh(0) }) { Text(if (selectedId == 0L) "当前" else "管理") }
                }
            }
            ledgers.forEach { ledger ->
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(ledger.name, fontWeight = FontWeight.SemiBold); Text(ledger.comment.ifBlank { "独立账本" }, color = Muted, fontSize = 12.sp) }
                        TextButton(onClick = { selectedId = ledger.id; refresh(ledger.id) }) { Text(if (selectedId == ledger.id) "当前" else "管理") }
                    }
                }
            }
            overview?.takeIf { it.ledgerId == selectedId }?.let { snapshot ->
                PanelCard {
                    Text("账本数据", fontWeight = FontWeight.SemiBold)
                    Text("账户 ${snapshot.accountCount} · 流水 ${snapshot.transactionCount} · 存钱目标 ${snapshot.savingsGoalCount} · 有效成员 ${snapshot.activeMemberCount}", color = Muted, fontSize = 13.sp)
                    Text(
                        snapshot.balances.takeIf { it.isNotEmpty() }
                            ?.joinToString("  ·  ") { "${it.currency} ${BigDecimal.valueOf(it.balanceMinor, 2).toPlainString()}" }
                            ?: "暂无可用账户余额",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (selectedId > 0) {
                Text("成员", fontWeight = FontWeight.SemiBold)
                members.filter { it.status == 1 }.forEach { member -> PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(member.nickname.ifBlank { "成员 ${member.uid}" }); Text(ledgerMemberRoleLabel(member.role), color = Muted, fontSize = 12.sp) }
                        if (canManage && member.role != RemoteLedgerMember.ROLE_OWNER && (member.role != RemoteLedgerMember.ROLE_ADMIN || isOwner)) {
                            TextButton(onClick = { roleTarget = member }) { Text("权限") }
                            TextButton(onClick = { removeTarget = member }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canManage) Button(onClick = { createdToken = null; inviteOpen = true }) { Text("邀请成员") }
                    OutlinedButton(onClick = { acceptPreview = null; acceptOpen = true }) { Text("用邀请码加入") }
                }
                if (isOwner) TextButton(enabled = !running, onClick = {
                    val ledger = ledgers.firstOrNull { it.id == selectedId } ?: return@TextButton
                    scope.launch {
                        running = true
                        runCatching { api.previewLedgerDelete(ledger.id) }
                            .onSuccess { deletePreview = it; deleteTarget = ledger }
                            .onFailure { message = "检查删除影响失败：${it.message}" }
                        running = false
                    }
                }) { Text("删除账本", color = MaterialTheme.colorScheme.error) }
                invitations.filter { it.status == 1 }.forEach { invitation -> PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("${invitation.inviteeName} · 待接受"); Text(invitation.token, color = Muted, fontSize = 12.sp) }
                        TextButton(onClick = { revokeTarget = invitation }) { Text("撤销") }
                    }
                } }
            }
        }
    }
    if (createOpen) FamilyLedgerEditorDialog(running, { createOpen = false }) { name, comment -> scope.launch {
        running = true; runCatching { api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, name, comment); refresh() }.onSuccess { createOpen = false; message = "账本已创建" }.onFailure { message = "创建失败：${it.message}" }; running = false
    } }
    if (inviteOpen) FamilyInviteDialog(createdToken, running, { inviteOpen = false }) { name, role -> scope.launch {
        running = true; runCatching { createdToken = api.createLedgerInvitation(selectedId, name, role).token; refresh() }.onFailure { message = "邀请失败：${it.message}" }; running = false
    } }
    if (acceptOpen) LedgerAcceptPreviewDialog(acceptPreview, running, { acceptOpen = false; acceptPreview = null }, { acceptPreview = null },
        onPreview = { token -> scope.launch {
            running = true; runCatching { api.previewLedgerInvitation(token) }.onSuccess { acceptPreview = it }
                .onFailure { message = "邀请码无效或已过期：${it.message}" }; running = false
        } },
        onAccept = { token -> scope.launch {
            running = true; runCatching { val ledger = api.acceptLedgerInvitation(token); selectedId = ledger.id; refresh(ledger.id) }
                .onSuccess { acceptOpen = false; acceptPreview = null; message = "已加入账本" }
                .onFailure { message = "加入失败：${it.message}" }; running = false
        } })
    roleTarget?.let { member ->
        var role by remember(member.id) { mutableIntStateOf(member.role) }
        AlertDialog(onDismissRequest = { roleTarget = null }, title = { Text("设置成员权限") }, text = {
            Column {
                if (isOwner) LedgerRoleOption(role, RemoteLedgerMember.ROLE_ADMIN, "管理员（管理账目与普通成员）") { role = it }
                LedgerRoleOption(role, RemoteLedgerMember.ROLE_MEMBER, "普通成员（可以记账）") { role = it }
                LedgerRoleOption(role, RemoteLedgerMember.ROLE_VIEWER, "只读成员（仅查看）") { role = it }
            }
        }, confirmButton = { TextButton(enabled = !running, onClick = {
            roleTarget = null; scope.launch { running = true; runCatching { api.changeLedgerMemberRole(selectedId, member.id, role); refresh() }.onFailure { message = "调整失败：${it.message}" }; running = false }
        }) { Text("保存权限") } }, dismissButton = { TextButton(onClick = { roleTarget = null }) { Text("取消") } })
    }
    removeTarget?.let { member -> AlertDialog(onDismissRequest = { removeTarget = null }, title = { Text("移除成员") },
        text = { Text("移除「${member.nickname.ifBlank { member.uid.toString() }}」后，对方将无法访问此账本，历史流水保留。") },
        confirmButton = { TextButton(enabled = !running, onClick = { removeTarget = null; scope.launch { running = true; runCatching { api.removeLedgerMember(selectedId, member.id); refresh() }.onFailure { message = "移除失败：${it.message}" }; running = false } }) { Text("移除成员", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } }) }
    revokeTarget?.let { invitation -> AlertDialog(onDismissRequest = { revokeTarget = null }, title = { Text("撤销邀请") }, text = { Text("撤销后，这个邀请码将不能再使用。") },
        confirmButton = { TextButton(enabled = !running, onClick = { revokeTarget = null; scope.launch { running = true; runCatching { api.revokeLedgerInvitation(selectedId, invitation.id); refresh() }.onFailure { message = "撤销失败：${it.message}" }; running = false } }) { Text("撤销邀请") } },
        dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("取消") } }) }
    deleteTarget?.let { ledger ->
        var confirmation by remember(ledger.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!running) deleteTarget = null }, title = { Text("删除账本") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val impact = deletePreview
                if (impact == null) Text("正在检查关联数据…", color = Muted)
                else {
                    Text(if (impact.canDelete) "可以删除此账本" else "暂时无法删除", fontWeight = FontWeight.SemiBold,
                        color = if (impact.canDelete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    Text("${impact.activeMemberCount} 位有效成员 · ${impact.pendingInvitationCount} 个待接受邀请", color = Muted, fontSize = 13.sp)
                    Text("账户 ${impact.accountCount} · 流水 ${impact.transactionCount} · 存钱目标 ${impact.savingsGoalCount} · 资金记录 ${impact.savingsGoalFundCount}", fontSize = 13.sp)
                    Text(if (impact.canDelete) "删除后所有成员将无法访问，待接受邀请会同时失效。" else "请先迁移或删除关联财务数据，再重新检查。", color = Muted, fontSize = 13.sp)
                    if (impact.canDelete) OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, label = { Text("输入「${ledger.name}」确认") }, singleLine = true)
                }
            }
        }, confirmButton = { if (deletePreview?.canDelete == true) TextButton(enabled = !running && confirmation == ledger.name, onClick = {
            scope.launch { running = true; runCatching { api.deleteLedger(ledger.id); selectedId = 0; members = emptyList(); invitations = emptyList(); refresh(0) }
                .onSuccess { deleteTarget = null; deletePreview = null; message = "账本已删除" }.onFailure { message = "删除失败：账本关联数据可能已变化，请重新检查" }; running = false }
        }) { Text("删除账本", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(enabled = !running, onClick = { deleteTarget = null; deletePreview = null }) { Text("取消") } })
    }
}

@Composable
private fun LedgerRoleOption(selected: Int, value: Int, label: String, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label, fontSize = 14.sp)
    }
}

private fun ledgerMemberRoleLabel(role: Int) = when (role) { 1 -> "所有者"; 2 -> "管理员"; 4 -> "只读成员"; else -> "普通成员" }

/** Legacy family page retained for server compatibility during migration. */
@Composable
internal fun FamilyPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, onBack: () -> Unit) {
    BackHandler { onBack() }
    val groups by repository.observeFamilyGroups().collectAsState(initial = emptyList())
    val members by repository.observeFamilyMembers().collectAsState(initial = emptyList())
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var groupEditorOpen by remember { mutableStateOf(false) }
    var inviteEditorOpen by remember { mutableStateOf(false) }
    var createdInvitation by remember { mutableStateOf<RemoteFamilyInvitation?>(null) }
    var acceptEditorOpen by remember { mutableStateOf(false) }
    var roleTarget by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var leaveConfirm by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var deleteGroupConfirm by remember { mutableStateOf(false) }
    var ledgerEditorOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }

    val activeGroup = groups.firstOrNull()
    val activeMember = members.firstOrNull { it.status == FamilyMemberEntity.STATUS_ACTIVE }
    val canManage = activeMember?.let { it.role == FamilyMemberEntity.ROLE_OWNER || it.role == FamilyMemberEntity.ROLE_ADMIN } ?: false
    val isOwner = activeMember?.role == FamilyMemberEntity.ROLE_OWNER

    fun refresh() {
        scope.launch {
            running = true; message = null
            runCatching {
                val loaded = api.listFamilyGroupsIfEnabled() ?: throw IllegalStateException("家庭共享在本服务上不可用")
                repository.replaceFamilyGroups(loaded)
                loaded.forEach { group -> repository.replaceFamilyMembers(group.id, api.listFamilyMembers(group.id)) }
            }.onFailure { message = "刷新失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回设置" }) { Text("返回设置") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("家庭共享", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("共享家庭账本；成员的个人账本永远私密。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(enabled = !localMode && !running, onClick = ::refresh,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "刷新家庭信息" }) { Text("刷新") }
                }
                message?.let { Text(it, color = if (it.contains("失败") || it.contains("无法")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
                if (localMode) Text("本地模式没有服务端家庭功能。", color = Muted, fontSize = 13.sp)
                when {
                    localMode -> {}
                    activeGroup == null -> {
                        Text("还没有加入任何家庭。创建一个家庭并邀请家人，或粘贴邀请码加入；个人账本永远私密。", color = Muted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !running, onClick = { groupEditorOpen = true },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "创建家庭" }) { Text("创建家庭") }
                            OutlinedButton(enabled = !running, onClick = { acceptEditorOpen = true },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "用邀请码加入家庭" }) { Text("用邀请码加入") }
                        }
                    }
                    else -> {
                        PanelCard {
                            Text(activeGroup.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            if (activeGroup.comment.isNotBlank()) Text(activeGroup.comment, color = Muted, fontSize = 13.sp)
                            Text("成员 ${activeGroup.memberCount} 位", color = Muted, fontSize = 12.sp)
                        }
                        if (canManage) {
                            Button(enabled = !running, onClick = { ledgerEditorOpen = true },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "创建家庭账本" }) {
                                Text("创建家庭账本")
                            }
                        }
                        Text("成员", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        members.filter { it.familyId == activeGroup.id && it.status == FamilyMemberEntity.STATUS_ACTIVE }.forEach { member ->
                            PanelCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(member.nickname.ifBlank { "成员 ${member.uid}" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text(if (member.status == FamilyMemberEntity.STATUS_ACTIVE) familyRoleLabel(member.role) else if (member.status == FamilyMemberEntity.STATUS_LEFT) "已退出" else "已被移除",
                                            color = Muted, fontSize = 12.sp)
                                    }
                                    if (canManage && member.status == FamilyMemberEntity.STATUS_ACTIVE && member.role != FamilyMemberEntity.ROLE_OWNER) {
                                        TextButton(enabled = !running, onClick = { roleTarget = member },
                                            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "调整 ${member.nickname.ifBlank { member.uid.toString() }} 的权限" }) { Text("权限") }
                                        TextButton(enabled = !running, onClick = { removeConfirm = member },
                                            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "移除 ${member.nickname.ifBlank { member.uid.toString() }}" }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                        if (canManage) {
                            Text("邀请记录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Button(enabled = !running, onClick = { createdInvitation = null; inviteEditorOpen = true },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "邀请成员" }) { Text("邀请成员") }
                        }
                        OutlinedButton(enabled = !running && !isOwner, onClick = { leaveConfirm = true },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "退出家庭" }) {
                            Text(if (isOwner) "所有者不能退出（可解散家庭）" else "退出家庭", color = if (isOwner) Muted else MaterialTheme.colorScheme.error)
                        }
                        if (isOwner) {
                            OutlinedButton(enabled = !running, onClick = { deleteGroupConfirm = true },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "解散家庭" }) { Text("解散家庭", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    if (groupEditorOpen) {
        FamilyGroupEditorDialog(onDismiss = { groupEditorOpen = false }, onSubmit = { name, comment ->
            groupEditorOpen = false
            scope.launch {
                running = true; message = null
                runCatching {
                    api.createFamilyGroup(name, comment)
                    val loaded = api.listFamilyGroupsIfEnabled() ?: emptyList()
                    repository.replaceFamilyGroups(loaded)
                    loaded.forEach { group -> repository.replaceFamilyMembers(group.id, api.listFamilyMembers(group.id)) }
                }.onSuccess { message = "家庭已创建，去邀请成员吧" }
                    .onFailure { message = "创建失败：${it.message ?: "请稍后重试"}" }
                running = false
            }
        })
    }
    if (ledgerEditorOpen) {
        FamilyLedgerEditorDialog(running = running, onDismiss = { ledgerEditorOpen = false }, onSubmit = { name, comment ->
            val groupId = activeGroup?.id
            if (groupId == null || !canManage) return@FamilyLedgerEditorDialog
            scope.launch {
                running = true; message = null
                runCatching {
                    api.createLedger(RemoteLedger.TYPE_FAMILY, groupId, name, comment)
                    repository.replaceLedgers(api.listLedgers())
                }.onSuccess {
                    ledgerEditorOpen = false
                    message = "家庭账本已创建，可在顶部切换"
                }.onFailure { message = "创建失败：${it.message ?: "请稍后重试"}" }
                running = false
            }
        })
    }
    if (inviteEditorOpen) {
        FamilyInviteDialog(createdToken = createdInvitation?.token, running = running, onDismiss = { inviteEditorOpen = false; createdInvitation = null },
            onSubmit = { inviteeName, role ->
                val groupId = activeGroup?.id
                if (groupId == null) { createdInvitation = null; return@FamilyInviteDialog }
                scope.launch {
                    running = true; message = null
                    runCatching { createdInvitation = api.createFamilyInvitation(groupId, inviteeName, role) }
                        .onSuccess { message = "邀请已生成，把邀请码发给对方" }
                        .onFailure { message = "生成失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            })
    }
    if (acceptEditorOpen) {
        FamilyAcceptDialog(running = running, onDismiss = { acceptEditorOpen = false }, onSubmit = { token ->
            scope.launch {
                running = true; message = null
                runCatching { api.acceptFamilyInvitation(token) }
                    .onSuccess { acceptEditorOpen = false; message = "已加入家庭"; refresh() }
                    .onFailure { message = "加入失败：${it.message ?: "请检查邀请码"}" }
                running = false
            }
        })
    }
    roleTarget?.let { member ->
        var draft by remember(member.id) { mutableStateOf(if (member.role == FamilyMemberEntity.ROLE_VIEWER) FamilyMemberEntity.ROLE_MEMBER else member.role) }
        AlertDialog(onDismissRequest = { roleTarget = null },
            title = { Text("调整「${member.nickname.ifBlank { member.uid.toString() }}」的权限") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draft == FamilyMemberEntity.ROLE_ADMIN, onClick = { draft = FamilyMemberEntity.ROLE_ADMIN },
                            modifier = Modifier.semantics { contentDescription = "设为管理员" })
                        Text("管理员（管理账目、目标与邀请）", fontSize = 14.sp)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draft == FamilyMemberEntity.ROLE_MEMBER, onClick = { draft = FamilyMemberEntity.ROLE_MEMBER },
                            modifier = Modifier.semantics { contentDescription = "设为普通成员" })
                        Text("普通成员（可记账）", fontSize = 14.sp)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draft == FamilyMemberEntity.ROLE_VIEWER, onClick = { draft = FamilyMemberEntity.ROLE_VIEWER },
                            modifier = Modifier.semantics { contentDescription = "设为只读成员" })
                        Text("只读成员（仅查看）", fontSize = 14.sp)
                    }
                    Text("改为只读后不能再新增或修改流水，历史记录保留。", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(enabled = !running, onClick = {
                val target = roleTarget; roleTarget = null
                val groupId = activeGroup?.id
                if (target == null || groupId == null) return@TextButton
                scope.launch {
                    running = true; message = null
                    runCatching { api.changeFamilyMemberRole(groupId, target.id, draft); refresh() }
                        .onSuccess { message = "成员权限已更新" }
                        .onFailure { message = "调整失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用") } },
            dismissButton = { TextButton(onClick = { roleTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    removeConfirm?.let { member ->
        AlertDialog(onDismissRequest = { removeConfirm = null },
            title = { Text("移除成员") },
            text = { Text("移除「${member.nickname.ifBlank { member.uid.toString() }}」后，该成员将无法访问家庭账本。") },
            confirmButton = { TextButton(enabled = !running, onClick = {
                val target = removeConfirm; removeConfirm = null
                val groupId = activeGroup?.id
                if (target == null || groupId == null) return@TextButton
                scope.launch {
                    running = true; message = null
                    runCatching { api.removeFamilyMember(groupId, target.id); refresh() }
                        .onSuccess { message = "成员已移除" }
                        .onFailure { message = "移除失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removeConfirm = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    if (leaveConfirm) {
        AlertDialog(onDismissRequest = { leaveConfirm = false },
            title = { Text("退出家庭") },
            text = { Text("退出后将无法查看家庭账本，历史记录保留。") },
            confirmButton = { TextButton(enabled = !running, onClick = {
                leaveConfirm = false
                val groupId = activeGroup?.id
                if (groupId == null) return@TextButton
                scope.launch {
                    running = true; message = null
                    runCatching { api.leaveFamily(groupId); refresh() }
                        .onSuccess { message = "已退出家庭" }
                        .onFailure { message = "退出失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出") } },
            dismissButton = { TextButton(onClick = { leaveConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    if (deleteGroupConfirm) {
        AlertDialog(onDismissRequest = { deleteGroupConfirm = false },
            title = { Text("解散家庭") },
            text = { Text("解散后所有成员失去访问。仅所有者可以解散，操作不能撤销。") },
            confirmButton = { TextButton(enabled = !running, onClick = {
                deleteGroupConfirm = false
                val groupId = activeGroup?.id
                if (groupId == null) return@TextButton
                scope.launch {
                    running = true; message = null
                    runCatching { api.deleteFamilyGroup(groupId); refresh() }
                        .onSuccess { message = "家庭已解散" }
                        .onFailure { message = "解散失败：${it.message ?: "请稍后重试"}" }
                    running = false
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("解散", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteGroupConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
}

private fun familyRoleLabel(role: Int): String = when (role) {
    FamilyMemberEntity.ROLE_OWNER -> "所有者"
    FamilyMemberEntity.ROLE_ADMIN -> "管理员"
    FamilyMemberEntity.ROLE_MEMBER -> "普通成员"
    else -> "只读成员"
}

@Composable
private fun FamilyGroupEditorDialog(onDismiss: () -> Unit, onSubmit: (name: String, comment: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("创建家庭") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("创建后你就是家庭的所有者。成员加入后可以看到家庭账本，但永远看不到彼此的个人账本。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("家庭名称 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "家庭名称" })
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "家庭描述" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (name.trim().isEmpty()) { error = "请输入家庭名称。"; return@TextButton }
            onSubmit(name.trim(), comment.trim())
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("创建家庭") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun FamilyLedgerEditorDialog(running: Boolean, onDismiss: () -> Unit, onSubmit: (name: String, comment: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!running) onDismiss() },
        title = { Text("创建账本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("创建后你是所有者，可随时邀请成员。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = name, onValueChange = { name = it.take(64); error = null }, label = { Text("账本名称 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "账本名称" })
                OutlinedTextField(value = comment, onValueChange = { comment = it.take(200) }, label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "账本描述" })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(enabled = !running, onClick = {
            if (name.trim().isEmpty()) { error = "请输入账本名称。"; return@TextButton }
            onSubmit(name.trim(), comment.trim())
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (running) "创建中" else "创建账本") } },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun FamilyInviteDialog(createdToken: String?, running: Boolean, onDismiss: () -> Unit, onSubmit: (inviteeName: String, role: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(FamilyMemberEntity.ROLE_MEMBER) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (createdToken == null) "邀请成员" else "邀请已生成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (createdToken == null) {
                    Text("邀请一次有效，24 小时后过期，可随时撤销。加入后普通成员可记账，只读成员仅查看。", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("邀请备注 *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "邀请备注" })
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == FamilyMemberEntity.ROLE_MEMBER, onClick = { role = FamilyMemberEntity.ROLE_MEMBER },
                            modifier = Modifier.semantics { contentDescription = "邀请为普通成员" })
                        Text("普通成员（可记账）", fontSize = 14.sp)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == FamilyMemberEntity.ROLE_VIEWER, onClick = { role = FamilyMemberEntity.ROLE_VIEWER },
                            modifier = Modifier.semantics { contentDescription = "邀请为只读成员" })
                        Text("只读成员（仅查看）", fontSize = 14.sp)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                } else {
                    Text("把邀请码发给对方，对方可在「账本管理」选择“用邀请码加入”。", color = Muted, fontSize = 12.sp)
                    Text(createdToken, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { contentDescription = "邀请码：$createdToken" })
                    Text("一次有效，24 小时后过期，可随时撤销。", color = Muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (createdToken == null) {
                TextButton(enabled = !running, onClick = {
                    if (name.trim().isEmpty()) { error = "请输入邀请备注。"; return@TextButton }
                    onSubmit(name.trim(), role)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("生成邀请码") }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") }
            }
        },
        dismissButton = {
            if (createdToken == null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            }
        })
}

@Composable
private fun FamilyAcceptDialog(running: Boolean, onDismiss: () -> Unit, onSubmit: (token: String) -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("用邀请码加入账本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("粘贴对方发来的邀请码。加入后可按邀请权限访问该账本。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("邀请码 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "邀请码" })
            }
        },
        confirmButton = { TextButton(enabled = !running && token.trim().isNotEmpty(), onClick = { onSubmit(token.trim()) },
            modifier = Modifier.heightIn(min = 48.dp)) { Text("加入账本") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun LedgerAcceptPreviewDialog(preview: RemoteLedgerInvitationPreview?, running: Boolean, onDismiss: () -> Unit,
    onReset: () -> Unit, onPreview: (String) -> Unit, onAccept: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (preview == null) "用邀请码加入账本" else "确认加入账本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (preview == null) {
                    Text("先查看账本名称和将获得的权限，确认后才会加入。", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = token, onValueChange = { token = it.take(64) }, label = { Text("邀请码 *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "邀请码" })
                } else {
                    Text(preview.ledger.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(preview.ledger.comment.ifBlank { "该账本没有填写说明。" }, color = Muted, fontSize = 13.sp)
                    Text("邀请权限：${ledgerMemberRoleLabel(preview.role)}", fontSize = 14.sp)
                    Text("邀请备注：${preview.inviteeName}", fontSize = 14.sp)
                    if (preview.inviterNickname.isNotBlank()) Text("邀请人：${preview.inviterNickname}", fontSize = 14.sp)
                    Text("有效期至：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(preview.expiredTime * 1000))}", color = Muted, fontSize = 12.sp)
                    TextButton(enabled = !running, onClick = { token = ""; onReset() }) { Text("重新输入邀请码") }
                }
            }
        },
        confirmButton = { TextButton(enabled = !running && token.trim().isNotEmpty(), onClick = {
            if (preview == null) onPreview(token.trim()) else onAccept(token.trim())
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (preview == null) "查看邀请" else "确认加入") } },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}
