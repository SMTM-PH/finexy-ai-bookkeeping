package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Due schedules wait in this queue; nothing posts to the ledger without an
 * explicit confirmation here. Dismissed items stay restorable until the server
 * confirms or dismisses them permanently.
 */
@Composable
internal fun OccurrenceReviewPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, onBack: () -> Unit) {
    BackHandler { onBack() }
    val occurrences by repository.observeOccurrences().collectAsState(initial = null)
    val accounts by repository.observeAccounts().collectAsState(initial = emptyList())
    val categories by repository.observeCategories().collectAsState(initial = emptyList())
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmTarget by remember { mutableStateOf<ScheduledOccurrenceEntity?>(null) }
    var dismissTarget by remember { mutableStateOf<ScheduledOccurrenceEntity?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }
    val context = LocalContext.current.applicationContext

    fun refresh() {
        scope.launch {
            running = true; message = null
            runCatching {
                val loaded = api.listOccurrences(ScheduledOccurrenceEntity.STATUS_PENDING) +
                    api.listOccurrences(ScheduledOccurrenceEntity.STATUS_DISMISSED)
                repository.replaceOccurrences(loaded)
            }.onFailure { message = "刷新失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun act(item: ScheduledOccurrenceEntity, dismiss: Boolean) {
        scope.launch {
            running = true; message = null
            runCatching {
                api.setOccurrenceDismissed(item.templateId, item.scheduledUnixTime, dismiss)
                repository.applyOccurrenceAction(item.templateId, item.scheduledUnixTime,
                    if (dismiss) ScheduledOccurrenceEntity.STATUS_DISMISSED else ScheduledOccurrenceEntity.STATUS_PENDING)
            }.onSuccess { message = if (dismiss) "已忽略，可在此恢复" else "已恢复到待确认" }
                .onFailure { message = "${if (dismiss) "忽略" else "恢复"}失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun post(item: ScheduledOccurrenceEntity) {
        scope.launch {
            running = true; message = null
            runCatching {
                val transactionId = api.confirmOccurrence(item.templateId, item.scheduledUnixTime)
                repository.applyOccurrenceAction(item.templateId, item.scheduledUnixTime,
                    ScheduledOccurrenceEntity.STATUS_CONFIRMED, transactionId)
                SyncScheduler.enqueueCurrent(context, store, replace = true)
            }.onSuccess { message = "已确认入账，流水同步中" }
                .onFailure { message = "确认失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回设置" }) { Text("返回设置") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("待确认入账", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("到期计划不会自动入账；确认后才生成流水。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(enabled = !localMode && !running, onClick = ::refresh, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "刷新待确认列表" }) { Text("刷新") }
                }
                message?.let { Text(it, color = if (it.startsWith("刷新失败") || it.contains("失败")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
                if (localMode) Text("本地模式没有服务端周期计划。", color = Muted, fontSize = 13.sp)
                when {
                    occurrences == null -> Text("加载中…", color = Muted, fontSize = 13.sp)
                    else -> {
                        val items = occurrences.orEmpty()
                        val pending = items.filter { it.status == ScheduledOccurrenceEntity.STATUS_PENDING }
                        val dismissed = items.filter { it.status == ScheduledOccurrenceEntity.STATUS_DISMISSED }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle("待确认（${pending.size}）")
                            if (pending.isEmpty()) Text("没有待确认的计划。", color = Muted, fontSize = 13.sp)
                            pending.forEach { item -> OccurrenceRow(item, accounts, categories, running || localMode,
                                onConfirm = { confirmTarget = item }, onDismiss = { dismissTarget = item }, onRestore = { act(item, dismiss = false) }) }
                        }
                        if (dismissed.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("已忽略（${dismissed.size}）")
                                dismissed.forEach { item -> OccurrenceRow(item, accounts, categories, running || localMode,
                                    onConfirm = { confirmTarget = item }, onDismiss = { dismissTarget = item }, onRestore = { act(item, dismiss = false) }) }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("确认入账？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(occurrenceSummary(item, accounts, categories))
                    Text("确认后将立即创建一笔流水并更新余额；重复点击只会入账一次。", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(enabled = !running, onClick = { val target = item; confirmTarget = null; post(target) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("确认入账") } },
            dismissButton = { TextButton(onClick = { confirmTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
        )
    }
    dismissTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { dismissTarget = null },
            title = { Text("忽略这次计划？") },
            text = { Text("忽略后本次执行不会入账，但计划继续保留；已忽略的记录可以恢复。") },
            confirmButton = { TextButton(enabled = !running, onClick = { val target = item; dismissTarget = null; act(target, dismiss = true) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("忽略") } },
            dismissButton = { TextButton(onClick = { dismissTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
        )
    }
}

@Composable
private fun OccurrenceRow(item: ScheduledOccurrenceEntity, accounts: List<AccountEntity>, categories: List<CategoryEntity>, disabled: Boolean,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onRestore: () -> Unit) {
    val dismissed = item.status == ScheduledOccurrenceEntity.STATUS_DISMISSED
    val description = occurrenceSummary(item, accounts, categories)
    Surface(onClick = { if (!disabled && !dismissed) onConfirm() }, color = Panel, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.name.isNotBlank()) item.name else occurrenceTypeLabel(item.type), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (dismissed) "已忽略" else "待确认", color = Muted, fontSize = 12.sp)
            }
            Text(occurrenceAmount(item), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(occurrenceSummary(item, accounts, categories), color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dismissed) {
                    TextButton(enabled = !disabled, onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "恢复待确认：${item.name}" }) { Text("恢复") }
                } else {
                    TextButton(enabled = !disabled, onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "确认入账：${item.name}" }) { Text("确认入账") }
                    TextButton(enabled = !disabled, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "忽略：${item.name}" }) { Text("忽略", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

internal fun occurrenceTypeLabel(type: Int): String = when (type) {
    2 -> "收入"; 3 -> "支出"; 4 -> "转账"; else -> "未知类型"
}

internal fun occurrenceAmount(item: ScheduledOccurrenceEntity): String = when {
    item.hideAmount -> "¥ ····"
    item.type == 2 -> "+¥ %.2f".format(item.sourceAmountMinor / 100.0)
    item.type == 3 -> "-¥ %.2f".format(item.sourceAmountMinor / 100.0)
    item.type == 4 -> "¥ %.2f → ¥ %.2f".format(item.sourceAmountMinor / 100.0, item.destinationAmountMinor / 100.0)
    else -> "¥ %.2f".format(item.sourceAmountMinor / 100.0)
}

internal fun occurrenceSummary(item: ScheduledOccurrenceEntity, accounts: List<AccountEntity>, categories: List<CategoryEntity>): String {
    fun accountName(id: Long) = accounts.firstOrNull { it.id == id }?.name ?: "#$id"
    fun categoryName(id: Long) = categories.firstOrNull { it.id == id }?.name ?: "#$id"
    val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.scheduledUnixTime * 1000))
    val base = "${occurrenceTypeLabel(item.type)} · ${accountName(item.sourceAccountId)} · ${categoryName(item.categoryId)} · 计划 $when_"
    if (item.type == 4) return "$base → ${accountName(item.destinationAccountId)}"
    if (item.comment.isNotBlank()) return "$base · ${item.comment}"
    return base
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

/**
 * Management page for recurring plans (settings secondary page). Pausing only
 * blanks the frequency on the server and never reads as "hidden"; the plan
 * text must make clear that due plans wait for confirmation.
 */
@Composable
internal fun SchedulePlanPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, onBack: () -> Unit) {
    BackHandler { onBack() }
    val templates by repository.observeScheduledTemplates().collectAsState(initial = null)
    val accounts by repository.observeAccounts().collectAsState(initial = emptyList())
    val categories by repository.observeCategories().collectAsState(initial = emptyList())
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var previewDraft by remember { mutableStateOf<TemplateEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TemplateEntity?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }
    val context = LocalContext.current.applicationContext

    fun submit(template: TemplateEntity) {
        scope.launch {
            running = true; message = null
            runCatching {
                val remote = if (template.id == 0L) api.createTemplate(template) else api.modifyTemplate(template)
                repository.mergeTemplates(listOf(remote))
            }.onSuccess { message = if (template.id == 0L) "计划已创建" else "计划已更新" }
                .onFailure { message = "保存失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun remove(id: Long) {
        scope.launch {
            running = true; message = null
            runCatching { api.deleteTemplate(id); repository.removeTemplate(id) }
                .onSuccess { message = "计划已删除" }
                .onFailure { message = "删除失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun togglePause(template: TemplateEntity) {
        val next = if (template.scheduledFrequencyType == 0) runCatching { template.resumedSchedule() } else Result.success(template.pausedSchedule())
        val target = next.getOrElse { error -> message = error.message ?: "操作无效"; return }
        scope.launch {
            running = true; message = null
            runCatching { repository.mergeTemplates(listOf(api.modifyTemplate(target))) }
                .onSuccess { message = if (target.scheduledFrequencyType == 0) "计划已暂停；到期后不会生成待确认记录" else "计划已恢复，到期后将进入待确认队列" }
                .onFailure { message = "操作失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun move(template: TemplateEntity, delta: Int) {
        val ordered = templates.orEmpty().toMutableList()
        val index = ordered.indexOfFirst { it.id == template.id }
        val target = index + delta
        if (index < 0 || target < 0 || target >= ordered.size) return
        ordered[index] = ordered[target].also { ordered[target] = ordered[index] }
        val reindexed = ordered.mapIndexed { position, item -> item.copy(displayOrder = position) }
        scope.launch {
            running = true; message = null
            runCatching { api.moveTemplates(reindexed); repository.saveTemplates(reindexed) }
                .onSuccess { message = "顺序已更新" }
                .onFailure { message = "排序失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun refresh() {
        scope.launch {
            running = true; message = null
            runCatching { api.listScheduledTemplatesIfEnabled()?.let { repository.replaceScheduledTemplates(it) } }
                .onSuccess { message = null }
                .onFailure { message = "刷新失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回设置" }) { Text("返回设置") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("周期计划", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("到期后进入待确认队列，不会自动入账。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(enabled = !localMode && !running, onClick = { creating = true },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "周期计划：新增" }) { Text("新增") }
                    TextButton(enabled = !localMode && !running, onClick = ::refresh,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "刷新周期计划" }) { Text("刷新") }
                }
                message?.let { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
                if (localMode) Text("本地模式没有服务端周期计划。", color = Muted, fontSize = 13.sp)
                when {
                    templates == null -> Text("加载中…", color = Muted, fontSize = 13.sp)
                    templates.orEmpty().isEmpty() -> Text("还没有周期计划；点击“新增”创建第一个。", color = Muted, fontSize = 13.sp)
                    else -> templates.orEmpty().forEachIndexed { index, template ->
                        ScheduleRow(template, index, templates.orEmpty().size, running || localMode,
                            onEdit = { editing = it }, onPause = ::togglePause, onDelete = { deleteTarget = it }, onMove = ::move)
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        ScheduleEditorDialog(existing = editing, accounts = accounts, categories = categories,
            onDismiss = { creating = false; editing = null }, onPreview = { draft ->
                creating = false; editing = null; previewDraft = draft
            })
    }
    previewDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { previewDraft = null },
            title = { Text("确认计划内容") },
            text = { Text(scheduleSummary(draft), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(enabled = !running, onClick = { val target = draft; previewDraft = null; submit(target) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") } },
            dismissButton = { TextButton(enabled = !running, onClick = { previewDraft = null; editing = draft },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("返回修改") } }
        )
    }
    deleteTarget?.let { template ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除周期计划？") },
            text = { Text("将永久删除“${template.name}”。已有的流水和待确认记录不受影响。") },
            confirmButton = { TextButton(enabled = !running, onClick = { val target = template; deleteTarget = null; remove(target.id) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
        )
    }
}

@Composable
private fun ScheduleRow(template: TemplateEntity, index: Int, count: Int, disabled: Boolean,
    onEdit: (TemplateEntity) -> Unit, onPause: (TemplateEntity) -> Unit, onDelete: (TemplateEntity) -> Unit, onMove: (TemplateEntity, Int) -> Unit) {
    val paused = template.scheduledFrequencyType == 0
    val state = when { paused -> "已暂停"; template.hidden -> "已隐藏"; else -> "生效中" }
    val description = "周期计划 ${template.name}，${scheduleSummary(template)}，状态 $state"
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(state, color = if (paused || template.hidden) Muted else IncomeGreen, fontSize = 12.sp)
            }
            Text(scheduleSummary(template), color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(enabled = !disabled, onClick = { onEdit(template) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "编辑计划：${template.name}" }) { Text("编辑") }
                TextButton(enabled = !disabled, onClick = { onPause(template) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "暂停或恢复计划：${template.name}" }) {
                    Text(if (paused) "恢复" else "暂停")
                }
                TextButton(enabled = !disabled && index > 0, onClick = { onMove(template, -1) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "上移计划：${template.name}" }) { Text("上移") }
                TextButton(enabled = !disabled && index < count - 1, onClick = { onMove(template, 1) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "下移计划：${template.name}" }) { Text("下移") }
                TextButton(enabled = !disabled, onClick = { onDelete(template) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除计划：${template.name}" }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

internal fun scheduleFrequencyLabel(frequencyType: Int?, frequency: String?): String = when (frequencyType) {
    null -> "未设置"
    0 -> "已暂停"
    1 -> "每周：" + frequency.orEmpty().split(',').filter { it.isNotBlank() }.joinToString("、") { value ->
        value.toIntOrNull()?.let { listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六").getOrNull(it) } ?: value
    }
    2 -> "每月：" + frequency.orEmpty().split(',').filter { it.isNotBlank() }.joinToString("、") { value ->
        val day = value.toIntOrNull() ?: return@joinToString value
        if (day > 0) "${day}日" else "月末第${-day}天"
    }
    3 -> "每日"
    4 -> "每年：" + frequency.orEmpty().split(',').filter { it.isNotBlank() }.joinToString("、") { value ->
        value.toIntOrNull()?.let { "${it / 100}月${it % 100}日" } ?: value
    }
    5 -> "每 ${frequency.orEmpty()} 天（自开始日期）"
    else -> "未知频率"
}

internal fun scheduleOffsetLabel(utcOffset: Int): String {
    val sign = if (utcOffset >= 0) "+" else "-"
    val absolute = kotlin.math.abs(utcOffset)
    return "UTC$sign%02d:%02d".format(absolute / 60, absolute % 60)
}

internal fun scheduleSummary(template: TemplateEntity): String {
    val type = when (template.type) { 2 -> "收入"; 3 -> "支出"; 4 -> "转账"; else -> "未知类型" }
    val amount = when {
        template.hideAmount -> "¥ ····"
        template.type == 4 -> "¥ %.2f → ¥ %.2f".format(template.sourceAmountMinor / 100.0, template.destinationAmountMinor / 100.0)
        else -> "¥ %.2f".format(template.sourceAmountMinor / 100.0)
    }
    val range = listOfNotNull(template.scheduledStartDate, template.scheduledEndDate).joinToString(" 至 ")
    val parts = mutableListOf("$type $amount", scheduleFrequencyLabel(template.scheduledFrequencyType, template.scheduledFrequency))
    template.utcOffset?.let { parts += scheduleOffsetLabel(it) }
    if (range.isNotBlank()) parts += range
    if (template.comment.isNotBlank()) parts += template.comment
    return parts.joinToString(" · ")
}

private data class ScheduleDraftState(
    val template: TemplateEntity,
    val summary: String
)

/**
 * Full add/edit form for a recurring plan. The execution moment follows the
 * server contract: schedules run at midnight in the chosen timezone, so the
 * form picks the timezone instead of a time of day.
 */
@Composable
private fun ScheduleEditorDialog(existing: TemplateEntity?, accounts: List<AccountEntity>, categories: List<CategoryEntity>, onDismiss: () -> Unit, onPreview: (TemplateEntity) -> Unit) {
    val seed = existing
    var name by remember { mutableStateOf(seed?.name.orEmpty()) }
    var type by remember { mutableStateOf(seed?.type ?: TransactionRepository.TYPE_EXPENSE) }
    var amount by remember { mutableStateOf(seed?.let { "%.2f".format(Locale.US, it.sourceAmountMinor / 100.0) }.orEmpty()) }
    var destinationAmount by remember { mutableStateOf(seed?.takeIf { it.type == 4 && it.destinationAmountMinor != it.sourceAmountMinor }?.let { "%.2f".format(Locale.US, it.destinationAmountMinor / 100.0) }.orEmpty()) }
    var comment by remember { mutableStateOf(seed?.comment.orEmpty()) }
    var accountId by remember { mutableStateOf(seed?.sourceAccountId ?: 0L) }
    var destinationAccountId by remember { mutableStateOf(seed?.destinationAccountId ?: 0L) }
    var categoryId by remember { mutableStateOf(seed?.categoryId ?: 0L) }
    var frequencyType by remember { mutableStateOf(seed?.let { if (it.scheduledFrequencyType == 0) it.pausedFromFrequencyType ?: 3 else it.scheduledFrequencyType } ?: 3) }
    var frequencyValues by remember { mutableStateOf(seed?.let { if (it.scheduledFrequencyType == 0) it.pausedFromFrequency.orEmpty() else it.scheduledFrequency.orEmpty() }.orEmpty()) }
    var startDate by remember { mutableStateOf(seed?.scheduledStartDate.orEmpty()) }
    var endDate by remember { mutableStateOf(seed?.scheduledEndDate.orEmpty()) }
    var hideAmount by remember { mutableStateOf(seed?.hideAmount ?: false) }
    val deviceOffset = remember { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000 }
    var offsetText by remember { mutableStateOf(seed?.utcOffset?.let { scheduleOffsetText(it) } ?: scheduleOffsetText(deviceOffset)) }

    val amountMinor = runCatching { amount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
    val destinationAmountMinor = runCatching { destinationAmount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
    val offsetMinutes = parseOffsetText(offsetText)
    val frequencyValid = runCatching {
        val entity = TemplateEntity(1L, "校验", type, categoryId, accountId, amountMinor ?: 0, comment,
            "[]", false, templateType = 2, destinationAccountId = destinationAccountId,
            destinationAmountMinor = destinationAmountMinor ?: 0, hideAmount = hideAmount,
            scheduledFrequencyType = frequencyType, scheduledFrequency = frequencyValues,
            scheduledStartDate = startDate.ifBlank { null }, scheduledEndDate = endDate.ifBlank { null },
            utcOffset = offsetMinutes)
        entity.validateSchedule()
        true
    }.getOrDefault(false)
    val valid = name.trim().isNotEmpty() && amountMinor != null && amountMinor > 0 &&
        accountId > 0L && categoryId > 0L && frequencyValid &&
        (type != TransactionRepository.TYPE_TRANSFER || (destinationAccountId > 0L && destinationAccountId != accountId &&
            destinationAmountMinor != null && destinationAmountMinor > 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增周期计划" else "编辑周期计划") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("计划名称") }, singleLine = true)
                Text("流水类型", color = Muted, fontSize = 12.sp)
                SegmentsRow(listOf("支出", "收入", "转账"), when (type) {
                    TransactionRepository.TYPE_INCOME -> "收入"; TransactionRepository.TYPE_TRANSFER -> "转账"; else -> "支出"
                }) {
                    type = when (it) {
                        "收入" -> TransactionRepository.TYPE_INCOME
                        "转账" -> TransactionRepository.TYPE_TRANSFER
                        else -> TransactionRepository.TYPE_EXPENSE
                    }
                    if (type != TransactionRepository.TYPE_TRANSFER) destinationAccountId = 0L
                }
                OutlinedTextField(amount, { amount = it.filter { char -> char.isDigit() || char == '.' }.take(15) },
                    label = { Text(if (type == TransactionRepository.TYPE_TRANSFER) "转出金额" else "金额") }, prefix = { Text("¥") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotEmpty() && (amountMinor == null || amountMinor <= 0))
                if (type == TransactionRepository.TYPE_TRANSFER) {
                    OutlinedTextField(destinationAmount, { destinationAmount = it.filter { char -> char.isDigit() || char == '.' }.take(15) },
                        label = { Text("转入金额（币种不同时必填）") }, prefix = { Text("¥") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = destinationAmount.isNotEmpty() && (destinationAmountMinor == null || destinationAmountMinor <= 0))
                }
                Text("账户", color = Muted, fontSize = 12.sp)
                AccountPicker(accounts.filter { !it.hidden && it.type == 1 }, accountId) { accountId = it }
                if (accounts.none { !it.hidden && it.type == 1 }) Text("请先同步一个可用服务端账户。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (type == TransactionRepository.TYPE_TRANSFER) {
                    Text("转入账户", color = Muted, fontSize = 12.sp)
                    AccountPicker(accounts.filter { !it.hidden && it.type == 1 && it.id != accountId }, destinationAccountId) { destinationAccountId = it }
                }
                Text("分类", color = Muted, fontSize = 12.sp)
                CategoryPicker(categories, type, categoryId) { categoryId = it }
                Text("重复规则（执行时刻为所选时区的 00:00）", color = Muted, fontSize = 12.sp)
                SegmentsRow(listOf("每日", "每周", "每月", "每年", "每N天"), when (frequencyType) {
                    1 -> "每周"; 2 -> "每月"; 4 -> "每年"; 5 -> "每N天"; else -> "每日"
                }) {
                    frequencyType = when (it) {
                        "每周" -> 1; "每月" -> 2; "每年" -> 4; "每N天" -> 5; else -> 3
                    }
                    frequencyValues = when (frequencyType) {
                        3 -> ""; else -> frequencyValues
                    }
                }
                when (frequencyType) {
                    1 -> WeekdayChips(frequencyValues) { frequencyValues = it }
                    5 -> OutlinedTextField(frequencyValues, { frequencyValues = it.filter(Char::isDigit).take(4) },
                        label = { Text("间隔天数") }, singleLine = true)
                    else -> OutlinedTextField(frequencyValues, { frequencyValues = it.filter { char -> char.isDigit() || char == ',' }.take(100) },
                        label = { Text(if (frequencyType == 2) "日期（逗号分隔，1-31，负数为月末倒数）" else "月日（逗号分隔，如 102,509 或 229）") }, singleLine = true)
                }
                OutlinedTextField(offsetText, { offsetText = it.take(7) }, label = { Text("计划时区（±HH:MM）") },
                    singleLine = true, isError = offsetMinutes == null,
                    supportingText = { Text("当前 ${scheduleOffsetText(deviceOffset)}；执行时刻为该时区午夜") })
                OutlinedTextField(startDate, { startDate = it.filter { char -> char.isDigit() || char == '-' }.take(10) },
                    label = { Text("开始日期（可选，YYYY-MM-DD）") }, singleLine = true)
                OutlinedTextField(endDate, { endDate = it.filter { char -> char.isDigit() || char == '-' }.take(10) },
                    label = { Text("结束日期（可选）") }, singleLine = true)
                OutlinedTextField(comment, { comment = it.take(255) }, label = { Text("备注（可选）") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(hideAmount, { hideAmount = it }, enabled = true, modifier = Modifier.semantics { contentDescription = "隐藏金额" })
                    Text("在列表中隐藏金额", color = Muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val draft = TemplateEntity(existing?.id ?: 0L, name.trim(), type, categoryId, accountId, amountMinor!!,
                    comment.trim(), existing?.tagIdsJson ?: "[]", existing?.hidden ?: false, templateType = 2,
                    destinationAccountId = if (type == TransactionRepository.TYPE_TRANSFER) destinationAccountId else 0,
                    destinationAmountMinor = if (type == TransactionRepository.TYPE_TRANSFER) destinationAmountMinor!! else 0,
                    hideAmount = hideAmount, scheduledFrequencyType = frequencyType, scheduledFrequency = frequencyValues,
                    scheduledStartDate = startDate.ifBlank { null }, scheduledEndDate = endDate.ifBlank { null },
                    utcOffset = offsetMinutes, scheduledAt = 0, displayOrder = existing?.displayOrder ?: 0,
                    pausedFromFrequencyType = existing?.pausedFromFrequencyType, pausedFromFrequency = existing?.pausedFromFrequency)
                onPreview(draft)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("预览") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
    )
}

@Composable
private fun SegmentsRow(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label -> FilterChip(selected == label, { onSelect(label) }, { Text(label) }, modifier = Modifier.heightIn(min = 40.dp)) }
    }
}

@Composable
private fun AccountPicker(accounts: List<AccountEntity>, selected: Long, onSelect: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { account -> FilterChip(selected == account.id, { onSelect(account.id) }, { Text(account.name) }, modifier = Modifier.heightIn(min = 40.dp)) }
    }
}

@Composable
private fun CategoryPicker(categories: List<CategoryEntity>, transactionType: Int, selected: Long, onSelect: (Long) -> Unit) {
    val serverType = when (transactionType) { TransactionRepository.TYPE_INCOME -> 1; TransactionRepository.TYPE_TRANSFER -> 3; else -> 2 }
    val leaves = categories.filter { category ->
        !category.hidden && category.type == serverType && category.parentId != 0L &&
            categories.none { it.id == category.parentId && it.hidden }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leaves.forEach { category -> FilterChip(selected == category.id, { onSelect(category.id) }, { Text(category.name) }, modifier = Modifier.heightIn(min = 40.dp)) }
    }
    if (leaves.isEmpty()) Text("当前类型没有可用末级分类，请先同步分类。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
}

@Composable
private fun WeekdayChips(values: String, onChange: (String) -> Unit) {
    val selected = values.split(',').filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }.toSet()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { index, label ->
            FilterChip(index in selected, {
                val next = (if (index in selected) selected - index else selected + index).toSortedSet()
                onChange(next.joinToString(","))
            }, { Text(label) }, modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = "周$label" })
        }
    }
}

internal fun scheduleOffsetText(minutes: Int): String {
    val sign = if (minutes >= 0) "+" else "-"
    val absolute = kotlin.math.abs(minutes)
    return "%s%02d:%02d".format(sign, absolute / 60, absolute % 60)
}

internal fun parseOffsetText(text: String): Int? {
    val match = Regex("^([+-])(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
    val hours = match.groupValues[2].toIntOrNull() ?: return null
    val minutes = match.groupValues[3].toIntOrNull() ?: return null
    if (minutes % 15 != 0) return null
    val total = hours * 60 + minutes
    if (total > 840) return null
    return if (match.groupValues[1] == "-") -total else total
}
