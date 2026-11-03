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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class PeriodicResources(
    val accounts: List<AccountEntity> = emptyList(), val categories: List<CategoryEntity> = emptyList(),
    val role: Int? = null, val loading: Boolean = true, val error: String? = null, val retry: () -> Unit = {}
) {
    val canManage get() = !loading && error == null && role in listOf(RemoteLedgerMember.ROLE_OWNER, RemoteLedgerMember.ROLE_ADMIN)
    val canReview get() = !loading && error == null && role in listOf(RemoteLedgerMember.ROLE_OWNER, RemoteLedgerMember.ROLE_ADMIN, RemoteLedgerMember.ROLE_MEMBER)
}

/** Navigation reads must not acknowledge a creation receipt or delete the original draft. */
private suspend fun refreshPendingPeriodicCache(api: FinexyApi, repository: TransactionRepository, ledgerId: Long,
    requireCurrent: () -> Unit = {}) {
    requireCurrent()
    val templates = api.parseTemplateResponse(api.listTemplates(1, ledgerId), ledgerId)
    val scheduled = api.listScheduledTemplatesIfEnabled(ledgerId)
    val occurrences = scheduled?.let {
        api.listOccurrences(ScheduledOccurrenceEntity.STATUS_PENDING, ledgerId) + api.listOccurrences(ScheduledOccurrenceEntity.STATUS_DISMISSED, ledgerId)
    }
    requireCurrent(); repository.replaceTemplates(templates, ledgerId)
    if (scheduled != null && occurrences != null) {
        requireCurrent(); repository.replaceScheduledTemplates(scheduled, ledgerId)
        requireCurrent(); repository.replaceOccurrences(occurrences, ledgerId)
    }
}

@Composable
internal fun rememberPeriodicResources(store: SecureStore, repository: TransactionRepository, localMode: Boolean, ledgerId: Long,
    preservePendingRecovery: Boolean = false, pendingContext: LedgerPendingContext? = null, loadAllowed: Boolean = true): PeriodicResources {
    val appContext = LocalContext.current.applicationContext
    val pendingTransport = remember(store, repository) { FinexyLedgerPendingTransport(appContext, store, repository) }
    var resources by remember(store, repository, ledgerId) { mutableStateOf(PeriodicResources()) }
    var retry by remember(store, repository, ledgerId) { mutableIntStateOf(0) }
    LaunchedEffect(store, repository, ledgerId, localMode, preservePendingRecovery, pendingContext, loadAllowed, retry) {
        resources = PeriodicResources(loading = !localMode)
        if (!loadAllowed) return@LaunchedEffect
        if (!localMode) {
            try {
                fun requireCurrent() { if (pendingContext != null) check(pendingTransport.context(ledgerId) == pendingContext) { TEMPLATE_PENDING_UNAVAILABLE } }
                requireCurrent()
                val api = FinexyApi(store)
                val role = api.periodicRole(ledgerId)
                val rawAccounts = api.listAccounts(ledgerId)
                requirePeriodicAccountsLedger(rawAccounts, ledgerId)
                val accounts = api.parseAccountResponse(rawAccounts).map {
                    AccountEntity(id = it.id, name = it.name, currency = it.currency, balanceMinor = it.balanceMinor,
                        hidden = it.hidden, parentId = it.parentId, category = it.category, type = it.type,
                        icon = it.icon, color = it.color, comment = it.comment, displayOrder = it.displayOrder,
                        creditCardStatementDate = it.creditCardStatementDate)
                }
                val rawCategories = api.listCategories(ledgerId = ledgerId)
                requirePeriodicCategories(rawCategories)
                val categories = api.parseCategoryResponse(rawCategories).map {
                    CategoryEntity(id = it.id, name = it.name, parentId = it.parentId, type = it.type, icon = it.icon,
                        color = it.color, hidden = it.hidden, comment = it.comment, displayOrder = it.displayOrder)
                }
                if (preservePendingRecovery) refreshPendingPeriodicCache(api, repository, ledgerId, ::requireCurrent)
                else SyncEngine(api, repository).refreshPeriodic(ledgerId)
                requireCurrent()
                resources = PeriodicResources(accounts, categories, role, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { resources = PeriodicResources(loading = false, error = error.message ?: "请稍后重试") }
        }
    }
    return resources.copy(retry = { retry++ })
}

/**
 * Due schedules wait in this queue; nothing posts to the ledger without an
 * explicit confirmation here. Dismissed items stay restorable until the server
 * confirms or dismisses them permanently.
 */
@Composable
internal fun OccurrenceReviewPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, ledgerId: Long = 0, fixtureResources: PeriodicResources? = null,
    initialPendingLocation: LedgerPendingPageLocation? = null, onBack: () -> Unit) {
    BackHandler { onBack() }
    val pendingGuard = rememberLedgerPendingPageGuard(initialPendingLocation, store, repository, localMode, ledgerId)
    val pendingState = pendingGuard?.state?.collectAsState()?.value
    var pendingAccessVerified by remember(pendingGuard) { mutableStateOf(pendingGuard == null) }
    val originalTarget = initialPendingLocation?.target as? LedgerPendingTarget.Occurrence
    var resumedAction by rememberSaveable(initialPendingLocation) { mutableIntStateOf(originalTarget?.action ?: 0) }
    var resumedQueuedAt by rememberSaveable(initialPendingLocation) { mutableLongStateOf(originalTarget?.queuedAt ?: 0) }
    var handledOriginal by rememberSaveable(initialPendingLocation) { mutableStateOf(false) }
    val occurrences by remember(repository, ledgerId) { repository.observeOccurrences(ledgerId) }.collectAsState(initial = null)
    val resources = fixtureResources ?: rememberPeriodicResources(store, repository, localMode, ledgerId,
        preservePendingRecovery = pendingGuard != null, pendingContext = initialPendingLocation?.context, loadAllowed = pendingAccessVerified)
    val accounts = resources.accounts
    val categories = resources.categories
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmTarget by remember { mutableStateOf<ScheduledOccurrenceEntity?>(null) }
    var dismissTarget by remember { mutableStateOf<ScheduledOccurrenceEntity?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }
    val context = LocalContext.current.applicationContext
    val currentTarget = originalTarget?.copy(action = resumedAction, queuedAt = resumedQueuedAt)
    val originalRow = occurrences?.singleOrNull { originalTarget != null && it.templateId == originalTarget.templateId && it.scheduledUnixTime == originalTarget.scheduledUnixTime }
    val pendingObjectsReady = pendingAccessVerified && !resources.loading && resources.error == null && occurrences != null
    val originalMatches = occurrencePendingLocationMatches(currentTarget, originalRow)
    val pendingReady = pendingGuard == null || pendingObjectsReady && originalMatches && pendingState in setOf(LedgerPendingPageStatus.READY, LedgerPendingPageStatus.RELEASED)
    LaunchedEffect(pendingGuard) {
        if (pendingGuard != null) {
            confirmTarget = null; dismissTarget = null
            if (originalTarget == null) pendingGuard.invalidate() else pendingAccessVerified = pendingGuard.verify()
        }
    }
    LaunchedEffect(pendingGuard, occurrences, resources.loading, resources.error, running) {
        if (pendingGuard != null && pendingObjectsReady && !running) {
            if (handledOriginal) pendingGuard.isCurrent()
            else if (!originalMatches) pendingGuard.invalidate() else pendingGuard.verify()
        }
    }
    suspend fun verifiedOriginal(item: ScheduledOccurrenceEntity): ScheduledOccurrenceEntity {
        if (pendingGuard == null) return item
        check(pendingGuard.verify()) { TEMPLATE_PENDING_UNAVAILABLE }
        val row = repository.allOccurrences().singleOrNull { it.ledgerId == ledgerId && it.templateId == item.templateId && it.scheduledUnixTime == item.scheduledUnixTime }
        if (!occurrencePendingLocationMatches(currentTarget, row)) {
            pendingGuard.invalidate(); error(TEMPLATE_PENDING_UNAVAILABLE)
        }
        check(pendingGuard.verify()) { TEMPLATE_PENDING_UNAVAILABLE }
        return requireNotNull(row) { TEMPLATE_PENDING_UNAVAILABLE }
    }
    fun review(item: ScheduledOccurrenceEntity, dismiss: Boolean) {
        if (pendingGuard == null) { if (dismiss) dismissTarget = item else confirmTarget = item; return }
        scope.launch {
            running = true
            try { val current = verifiedOriginal(item); if (dismiss) dismissTarget = current else confirmTarget = current }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = TEMPLATE_PENDING_UNAVAILABLE }
            finally { running = false }
        }
    }

    fun refresh() {
        resources.retry()
        scope.launch {
            running = true; message = null
            try {
                if (pendingGuard != null) {
                    pendingAccessVerified = pendingGuard.verify(); check(pendingAccessVerified) { TEMPLATE_PENDING_UNAVAILABLE }
                    refreshPendingPeriodicCache(api, repository, ledgerId)
                }
                else SyncEngine(api, repository).refreshPeriodic(ledgerId)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = "刷新失败：${error.message ?: "请稍后重试"}" }
            finally { running = false }
        }
    }
    fun deliver(item: ScheduledOccurrenceEntity, action: Int) {
        scope.launch {
            running = true; message = null
            try {
                require(item.ledgerId == ledgerId) { "记录不属于打开时的账本" }
                val current = verifiedOriginal(item)
                if (pendingGuard != null) {
                    check(api.periodicRole(ledgerId) in RemoteLedgerMember.ROLE_OWNER..RemoteLedgerMember.ROLE_MEMBER) { "当前成员只读；原待确认记录仍保留。" }
                    check(pendingGuard.isCurrent()) { TEMPLATE_PENDING_UNAVAILABLE }
                }
                val intent = if (pendingGuard != null && current.pendingAction != ScheduledOccurrenceEntity.ACTION_NONE) {
                    require(current.pendingAction == action) { TEMPLATE_PENDING_UNAVAILABLE }
                    requireNotNull(repository.pendingPeriodicActions().singleOrNull {
                        it.ledgerId == ledgerId && it.templateId == current.templateId && it.scheduledUnixTime == current.scheduledUnixTime &&
                            it.action == current.pendingAction && it.queuedAt == current.pendingActionQueuedAt
                    }) { TEMPLATE_PENDING_UNAVAILABLE }
                } else repository.enqueuePeriodicAction(ledgerId, current.templateId, current.scheduledUnixTime, action)
                if (pendingGuard != null) {
                    check(pendingGuard.isCurrent()) { TEMPLATE_PENDING_UNAVAILABLE }
                    resumedAction = intent.action; resumedQueuedAt = intent.queuedAt
                    check(pendingGuard.release()) { TEMPLATE_PENDING_UNAVAILABLE }
                }
                SyncScheduler.enqueueCurrent(context, store, replace = true)
                SyncEngine(api, repository).performPeriodicAction(intent)
                if (pendingGuard != null) handledOriginal = true
                message = "操作已确认；流水和余额由同步更新"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message = "结果待核对：${error.message ?: "请重试原操作"}"
                if (pendingGuard?.isCurrent() == true) resources.retry()
            }
            finally { running = false }
        }
    }
    fun act(item: ScheduledOccurrenceEntity, dismiss: Boolean) = deliver(item, if (dismiss) ScheduledOccurrenceEntity.ACTION_DISMISS else ScheduledOccurrenceEntity.ACTION_RESTORE)
    fun post(item: ScheduledOccurrenceEntity) = deliver(item, ScheduledOccurrenceEntity.ACTION_CONFIRM)

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回设置" }) { Text("返回设置") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("待确认入账", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("到期计划不会自动入账；确认后才生成流水。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(enabled = !localMode && !running, onClick = ::refresh, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "刷新待确认列表" }) { Text("刷新") }
                }
                if (handledOriginal && pendingState == LedgerPendingPageStatus.RELEASED) Text("原操作已处理，请返回待处理列表核对。", color = Muted)
                else TemplatePendingLocationNotice(if (pendingGuard != null && resources.error != null) LedgerPendingPageStatus.FAILED else pendingState,
                    pendingObjectsReady, onRetry = { scope.launch {
                        pendingAccessVerified = pendingGuard?.verify() != false
                        if (pendingAccessVerified) resources.retry()
                    } })
                message?.let { Text(it, color = if (it.startsWith("刷新失败") || it.contains("失败")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
                resources.error?.let { Text(if (pendingGuard == null) "账本读取失败：$it" else TEMPLATE_PENDING_UNAVAILABLE, color = MaterialTheme.colorScheme.error) }
                if (resources.loading) Text("正在核验当前账本与成员权限…", color = Muted)
                if (resources.role == RemoteLedgerMember.ROLE_VIEWER) Text("当前成员只读", color = Muted)
                if (localMode) Text("本地模式没有服务端周期计划。", color = Muted, fontSize = 13.sp)
                when {
                    !pendingReady -> Unit
                    resources.loading -> Text("正在读取当前账本…", color = Muted)
                    resources.error != null -> Text("保留上次缓存，当前读取未完成。", color = Muted)
                    occurrences == null -> Text("加载中…", color = Muted, fontSize = 13.sp)
                    else -> {
                        val items = occurrences.orEmpty().filter { pendingGuard == null || originalTarget != null && it.templateId == originalTarget.templateId && it.scheduledUnixTime == originalTarget.scheduledUnixTime }
                        val pending = items.filter { it.status == ScheduledOccurrenceEntity.STATUS_PENDING }
                        val dismissed = items.filter { it.status == ScheduledOccurrenceEntity.STATUS_DISMISSED }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle("待确认（${pending.size}）")
                            if (pending.isEmpty()) Text("没有待确认的计划。", color = Muted, fontSize = 13.sp)
                            pending.forEach { item -> OccurrenceRow(item, accounts, categories, running || localMode || !resources.canReview, canReview = resources.canReview,
                                onConfirm = { review(item, false) }, onDismiss = { review(item, true) }, onRestore = { act(item, dismiss = false) }, onRetry = { deliver(item, item.pendingAction) }) }
                        }
                        if (dismissed.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("已忽略（${dismissed.size}）")
                                dismissed.forEach { item -> OccurrenceRow(item, accounts, categories, running || localMode || !resources.canReview, canReview = resources.canReview,
                                    onConfirm = { review(item, false) }, onDismiss = { review(item, true) }, onRestore = { act(item, dismiss = false) }, onRetry = { deliver(item, item.pendingAction) }) }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmTarget?.takeIf { pendingReady }?.let { item ->
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
    dismissTarget?.takeIf { pendingReady }?.let { item ->
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
    canReview: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit, onRestore: () -> Unit, onRetry: () -> Unit = {}) {
    val dismissed = item.status == ScheduledOccurrenceEntity.STATUS_DISMISSED
    val description = occurrenceSummary(item, accounts, categories)
    Surface(onClick = { if (!disabled && !dismissed && item.pendingAction == 0) onConfirm() }, color = Panel, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.name.isNotBlank()) item.name else occurrenceTypeLabel(item.type), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (dismissed) "已忽略" else "待确认", color = Muted, fontSize = 12.sp)
            }
            Text(occurrenceAmount(item, accounts), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(occurrenceSummary(item, accounts, categories), color = Muted, fontSize = 12.sp)
            if (canReview) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.pendingAction != ScheduledOccurrenceEntity.ACTION_NONE) {
                    Text("原操作结果待确认", color = Muted, fontSize = 12.sp)
                    TextButton(enabled = !disabled, onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试原操作") }
                } else if (dismissed) {
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

internal fun periodicAmount(type: Int, sourceId: Long, destinationId: Long, amount: Long, destinationAmount: Long, hidden: Boolean, accounts: List<AccountEntity>): String {
    if (hidden) return "金额已隐藏"
    fun money(id: Long, value: Long): String = accounts.singleOrNull { it.id == id }?.currency?.takeIf { it.isNotBlank() }?.let { currencyMoney(value, it) } ?: "账户或币种待核对"
    return when (type) {
        TransactionRepository.TYPE_INCOME -> "+${money(sourceId, amount)}"
        TransactionRepository.TYPE_EXPENSE -> if (amount < 0) "退款 +${money(sourceId, -amount)}" else "-${money(sourceId, amount)}"
        TransactionRepository.TYPE_TRANSFER -> "${if (sourceId == 0L) "外部" else money(sourceId, amount)} → ${if (destinationId == 0L) "外部" else money(destinationId, destinationAmount)}"
        else -> "类型待核对"
    }
}

internal fun occurrenceAmount(item: ScheduledOccurrenceEntity, accounts: List<AccountEntity> = emptyList()): String = periodicAmount(item.type, item.sourceAccountId, item.destinationAccountId, item.sourceAmountMinor, item.destinationAmountMinor, item.hideAmount, accounts)

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
internal fun SchedulePlanPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean, ledgerId: Long = 0, fixtureResources: PeriodicResources? = null,
    initialPendingLocation: LedgerPendingPageLocation? = null, onBack: () -> Unit) {
    BackHandler { onBack() }
    val pendingGuard = rememberLedgerPendingPageGuard(initialPendingLocation, store, repository, localMode, ledgerId)
    val pendingState = pendingGuard?.state?.collectAsState()?.value
    var pendingAccessVerified by remember(pendingGuard) { mutableStateOf(pendingGuard == null) }
    val templates by remember(repository, ledgerId) { repository.observeScheduledTemplates(ledgerId) }.collectAsState(initial = null)
    val resources = fixtureResources ?: rememberPeriodicResources(store, repository, localMode, ledgerId,
        preservePendingRecovery = pendingGuard != null, pendingContext = initialPendingLocation?.context, loadAllowed = pendingAccessVerified)
    val accounts = resources.accounts
    val categories = resources.categories
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var previewDraft by remember { mutableStateOf<TemplateEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TemplateEntity?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }
    val context = LocalContext.current.applicationContext

    val draftSession = rememberTemplateDraftSession(repository, ledgerId, 2)
    val writes by repository.observeTemplateCreations().collectAsState(initial = null)
    val write = writes?.singleOrNull { it.ledgerId == ledgerId && it.templateType == 2 }
    val draftRows by remember(repository, ledgerId) { repository.observeTemplateDraft(ledgerId, 2).map { listOfNotNull(it) } }.collectAsState(initial = null)
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    val ledgerName = if (ledgerId == 0L) "默认个人账本" else ledgers.singleOrNull { it.id == ledgerId }?.name ?: "当前账本"
    var editorFields by remember { mutableStateOf<Map<String, String>?>(null) }
    var previewSnapshot by remember { mutableStateOf<TemplateFormDraftEntity?>(null) }
    var discardDraft by remember { mutableStateOf(false) }
    val pendingObjectsReady = pendingAccessVerified && !resources.loading && resources.error == null && templates != null && writes != null && draftRows != null && draftSession.loaded
    val originalMatches = templatePendingLocationMatches(initialPendingLocation?.target, ledgerId, 2, draftRows?.singleOrNull(), write)
    val pendingReady = pendingGuard == null || pendingObjectsReady && (pendingState == LedgerPendingPageStatus.RELEASED || pendingState == LedgerPendingPageStatus.READY && originalMatches)
    val showOtherPlans = pendingGuard == null || pendingState == LedgerPendingPageStatus.RELEASED
    LaunchedEffect(pendingGuard) {
        if (pendingGuard != null) {
            creating = false; editing = null; editorFields = null; previewDraft = null; deleteTarget = null; discardDraft = false
            if ((initialPendingLocation?.target as? LedgerPendingTarget.Template)?.templateType != 2) pendingGuard.invalidate()
            else pendingAccessVerified = pendingGuard.verify()
        }
    }
    LaunchedEffect(pendingGuard, templates, writes, draftRows, draftSession.loaded, resources.loading, resources.error, running) {
        if (pendingGuard != null && pendingObjectsReady && !running) {
            if (pendingGuard.state.value == LedgerPendingPageStatus.RELEASED) pendingGuard.isCurrent()
            else if (!originalMatches) pendingGuard.invalidate()
            else pendingGuard.verify()
        }
    }
    LaunchedEffect(write?.clientSessionId) { if (write == null && !creating && editing == null && previewDraft == null) draftSession.load() }

    fun openSavedDraft(saved: TemplateFormDraftEntity): Boolean {
        val seed = if (saved.editingId == 0L) null else templates.orEmpty().singleOrNull { it.id == saved.editingId }
        if (saved.editingId > 0 && seed == null) { message = if (pendingGuard == null) "原计划不在当前列表中，请重新读取；草稿仍保留。" else TEMPLATE_PENDING_UNAVAILABLE; return false }
        editorFields = templateFormFields(saved.formJson); editing = seed; creating = seed == null
        return true
    }
    fun continueDraft() {
        if (pendingGuard == null) { draftSession.draft?.let(::openSavedDraft); return }
        scope.launch {
            running = true
            try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                draftSession.load()
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                if (openSavedDraft(requireNotNull(draftSession.draft) { TEMPLATE_PENDING_UNAVAILABLE })) {
                    if (!pendingGuard.release()) { creating = false; editing = null; message = TEMPLATE_PENDING_UNAVAILABLE }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = TEMPLATE_PENDING_UNAVAILABLE }
            finally { running = false }
        }
    }


    fun submit(template: TemplateEntity) {
        scope.launch {
            running = true; message = null
            runCatching {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                saveConfirmedTemplate(context, store, repository, api, draftSession, template, requireNotNull(previewSnapshot))
                editorFields = null
            }.onSuccess { message = if (template.id == 0L) "计划已创建" else "计划已更新" }
                .onFailure { if (it is CancellationException) throw it; message = "保存失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun remove(id: Long) {
        scope.launch {
            running = true; message = null
            runCatching {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                api.deleteTemplate(id, ledgerId); repository.removeTemplate(id, ledgerId)
            }
                .onSuccess { message = "计划已删除" }
                .onFailure { if (it is CancellationException) throw it; message = "删除失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun togglePause(template: TemplateEntity) {
        val next = if (template.scheduledFrequencyType == 0) runCatching { template.resumedSchedule() } else Result.success(template.pausedSchedule())
        val target = next.getOrElse { error -> message = error.message ?: "操作无效"; return }
        scope.launch {
            running = true; message = null
            runCatching {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                val remote = api.modifyTemplate(target)
                // Keep the captured pre-pause rule only after the server has acknowledged the change.
                repository.saveTemplates(listOf(target))
                repository.mergeTemplates(listOf(remote))
            }
                .onSuccess { message = if (target.scheduledFrequencyType == 0) "计划已暂停；到期后不会生成待确认记录" else "计划已恢复，到期后将进入待确认队列" }
                .onFailure { if (it is CancellationException) throw it; message = "操作失败：${it.message ?: "请稍后重试"}" }
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
            runCatching {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                api.moveTemplates(reindexed); repository.saveTemplates(reindexed)
            }
                .onSuccess { message = "顺序已更新" }
                .onFailure { if (it is CancellationException) throw it; message = "排序失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }
    fun refresh() {
        resources.retry()
        scope.launch {
            running = true; message = null
            runCatching {
                if (pendingGuard != null) {
                    pendingAccessVerified = pendingGuard.verify(); check(pendingAccessVerified) { TEMPLATE_PENDING_UNAVAILABLE }
                    refreshPendingPeriodicCache(api, repository, ledgerId)
                }
                else SyncEngine(api, repository).refreshPeriodic(ledgerId)
            }
                .onSuccess { message = null }
                .onFailure { if (it is CancellationException) throw it; message = "刷新失败：${it.message ?: "请稍后重试"}" }
            running = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding()) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回设置" }) { Text("返回设置") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("周期计划", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text("当前账本 · 到期后进入待确认队列，不会自动入账。", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(enabled = !localMode && !running && pendingReady && showOtherPlans && resources.canManage && draftSession.ready && draftSession.draft == null && write == null && writes != null,
                        onClick = { editing = null; editorFields = null; creating = true },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "周期计划：新增" }) { Text("新增") }
                    TextButton(enabled = !localMode && !running, onClick = ::refresh,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "刷新周期计划" }) { Text("刷新") }
                }
                TemplatePendingLocationNotice(if (pendingGuard != null && (resources.error != null || draftSession.failure != null)) LedgerPendingPageStatus.FAILED else pendingState,
                    pendingObjectsReady, onRetry = { scope.launch {
                        pendingAccessVerified = pendingGuard?.verify() != false
                        if (pendingAccessVerified) { draftSession.load(); resources.retry() }
                    } })
                message?.let { Text(it, color = if (it.contains("失败")) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp) }
                resources.error?.let { Text(if (pendingGuard == null) "账本读取失败：$it" else TEMPLATE_PENDING_UNAVAILABLE, color = MaterialTheme.colorScheme.error) }
                if (resources.loading) Text("正在核验当前账本与成员权限…", color = Muted)
                if (resources.role == RemoteLedgerMember.ROLE_VIEWER) Text("当前成员只读", color = Muted)
                if (localMode) Text("本地模式没有服务端周期计划。", color = Muted, fontSize = 13.sp)
                if (pendingReady) TemplateRecoveryPanel(draftSession, write, ledgerName, !running && writes != null &&
                    (resources.canManage || pendingGuard != null && templatePendingRecoveryMode(resources.role, write?.state) == TemplatePendingRecoveryMode.READ_RECEIPT),
                    onContinue = ::continueDraft, onDiscard = { scope.launch {
                        if (verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) discardDraft = true else message = TEMPLATE_PENDING_UNAVAILABLE
                    } }, onReload = { scope.launch {
                        if (verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) draftSession.load() else message = TEMPLATE_PENDING_UNAVAILABLE
                    } },
                    onRetry = { scope.launch {
                        running = true
                        try {
                            check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                            val displayed = requireNotNull(write) { TEMPLATE_PENDING_UNAVAILABLE }
                            val original = requireNotNull(repository.templateCreations().singleOrNull { it.ledgerId == ledgerId && it.templateType == 2 }) { TEMPLATE_PENDING_UNAVAILABLE }
                            check(pendingTemplateRequestRecord(original).target == pendingTemplateRequestRecord(displayed).target) { TEMPLATE_PENDING_UNAVAILABLE }
                            if (pendingGuard != null) check(templatePendingLocationMatches(pendingGuard.location.target, ledgerId, 2, null, original)) { TEMPLATE_PENDING_UNAVAILABLE }
                            check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                            val mode = if (pendingGuard == null) null else requireNotNull(templatePendingRecoveryMode(api.periodicRole(ledgerId), original.state)) { "当前成员不能重试模板创建；原请求仍保留。" }
                            check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                            if (mode == TemplatePendingRecoveryMode.READ_RECEIPT) SyncEngine(api, repository).refreshPeriodic(ledgerId)
                            else { SyncScheduler.enqueueCurrent(context, store, replace = true); SyncEngine(api, repository).performTemplateCreation(original) }
                            check(pendingGuard?.release() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                            draftSession.load(); message = "创建结果已核对"
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { message = "创建结果待确认：${error.message}" }
                        finally { running = false }
                    } }, canEdit = resources.canManage)
                when {
                    !pendingReady -> Unit
                    resources.loading -> Text("正在读取当前账本…", color = Muted)
                    resources.error != null -> Text("保留上次缓存，当前读取未完成。", color = Muted)
                    templates == null -> Text("加载中…", color = Muted, fontSize = 13.sp)
                    templates.orEmpty().isEmpty() && showOtherPlans -> Text("还没有周期计划；点击“新增”创建第一个。", color = Muted, fontSize = 13.sp)
                    else -> templates.orEmpty().filter { showOtherPlans }.forEachIndexed { index, template ->
                        ScheduleRow(template, accounts, index, templates.orEmpty().size, running || localMode || !resources.canManage, canManage = resources.canManage,
                            onEdit = { if (draftSession.ready && draftSession.draft == null && write == null && writes != null) { editing = it; editorFields = null }
                                else message = "请先处理本机草稿或已确认的创建请求。" }, onPause = ::togglePause, onDelete = { deleteTarget = it }, onMove = ::move)
                    }
                }
            }
        }
    }
    if ((creating || editing != null) && pendingReady) {
        ScheduleEditorDialog(existing = editing, accounts = accounts, categories = categories, ledgerId = ledgerId,
            onDismiss = { creating = false; editing = null }, onPreview = { draft -> scope.launch {
                try {
                    check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                    previewSnapshot = draftSession.snapshot()
                    check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                    editorFields = templateFormFields(requireNotNull(previewSnapshot).formJson)
                    creating = false; editing = null; previewDraft = draft }
                catch (error: Exception) { message = "草稿未确认：${error.message}" }
            } }, initialFields = editorFields, draftStatus = draftSession.status,
            persistReady = draftSession.ready && resources.canManage && write == null && writes != null && pendingReady,
            onFormChange = { if (pendingGuard?.isCurrent() != false) draftSession.changed(editing?.id ?: 0L, it) }, onRetryDraft = { if (pendingGuard?.isCurrent() != false) draftSession.retrySave() })
    }
    previewDraft?.takeIf { pendingReady }?.let { draft ->
        AlertDialog(
            onDismissRequest = { previewDraft = null },
            title = { Text("确认计划内容") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ledgerName); Text(draft.name); Text(scheduleSummary(draft, accounts)); Text("备注：${draft.comment.ifBlank { "无" }}")
                Text("分类：${categories.singleOrNull { it.id == draft.categoryId }?.name ?: "待核对"}")
            } },
            confirmButton = { TextButton(enabled = !running && resources.canManage && draftSession.ready && write == null, onClick = { val target = draft; previewDraft = null; submit(target) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") } },
            dismissButton = { TextButton(enabled = !running, onClick = { previewDraft = null; if (draft.id == 0L) { creating = true; editing = null } else editing = draft },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("返回修改") } }
        )
    }
    deleteTarget?.takeIf { pendingReady }?.let { template ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除周期计划？") },
            text = { Text("将永久删除“${template.name}”。已有的流水和待确认记录不受影响。") },
            confirmButton = { TextButton(enabled = !running, onClick = { val target = template; deleteTarget = null; remove(target.id) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
        )
    }
    if (discardDraft && pendingReady) AlertDialog(onDismissRequest = { discardDraft = false }, title = { Text("丢弃本机草稿？") },
        text = { Text("只删除此账本尚未确认的表单输入，无法恢复。") },
        confirmButton = { TextButton(enabled = draftSession.ready && !running && write == null, onClick = {
            discardDraft = false; scope.launch { running = true; try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 2)) { TEMPLATE_PENDING_UNAVAILABLE }
                draftSession.discard(); pendingGuard?.release(); editorFields = null
            }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = "丢弃失败：${error.message}" }
                finally { running = false } }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认丢弃") } },
        dismissButton = { TextButton(onClick = { discardDraft = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun ScheduleRow(template: TemplateEntity, accounts: List<AccountEntity>, index: Int, count: Int, disabled: Boolean, canManage: Boolean = true,
    onEdit: (TemplateEntity) -> Unit, onPause: (TemplateEntity) -> Unit, onDelete: (TemplateEntity) -> Unit, onMove: (TemplateEntity, Int) -> Unit) {
    val paused = template.scheduledFrequencyType == 0
    val state = when { paused -> "已暂停"; template.hidden -> "已隐藏"; else -> "生效中" }
    val description = "周期计划 ${template.name}，${scheduleSummary(template, accounts)}，状态 $state"
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(state, color = if (paused || template.hidden) Muted else IncomeGreen, fontSize = 12.sp)
            }
            Text(scheduleSummary(template, accounts), color = Muted, fontSize = 12.sp)
            if (canManage) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

internal fun scheduleSummary(template: TemplateEntity, accounts: List<AccountEntity> = emptyList()): String {
    val type = when (template.type) { 2 -> "收入"; 3 -> "支出"; 4 -> "转账"; else -> "未知类型" }
    val amount = periodicAmount(template.type, template.sourceAccountId, template.destinationAccountId, template.sourceAmountMinor, template.destinationAmountMinor, template.hideAmount, accounts)
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
private fun ScheduleEditorDialog(existing: TemplateEntity?, accounts: List<AccountEntity>, categories: List<CategoryEntity>, ledgerId: Long = 0, onDismiss: () -> Unit, onPreview: (TemplateEntity) -> Unit, initialFields: Map<String, String>? = null,
    draftStatus: String = "", persistReady: Boolean = true, onFormChange: (Map<String, String>) -> Unit = {}, onRetryDraft: (() -> Unit)? = null) {
    val seed = existing
    var name by remember { mutableStateOf(initialFields?.get("name") ?: (seed?.name.orEmpty())) }
    var type by remember { mutableStateOf(initialFields?.get("type")?.toIntOrNull() ?: (seed?.type ?: TransactionRepository.TYPE_EXPENSE)) }
    var amount by remember { mutableStateOf(initialFields?.get("amount") ?: (seed?.let { java.math.BigDecimal.valueOf(it.sourceAmountMinor).movePointLeft(2).toPlainString() }.orEmpty())) }
    var destinationAmount by remember { mutableStateOf(initialFields?.get("destinationAmount") ?: (seed?.let { java.math.BigDecimal.valueOf(it.destinationAmountMinor).movePointLeft(2).toPlainString() }.orEmpty())) }
    var comment by remember { mutableStateOf(initialFields?.get("comment") ?: (seed?.comment.orEmpty())) }
    var accountId by remember { mutableStateOf(initialFields?.get("accountId")?.toLongOrNull() ?: (seed?.sourceAccountId ?: 0L)) }
    var destinationAccountId by remember { mutableStateOf(initialFields?.get("destinationAccountId")?.toLongOrNull() ?: (seed?.destinationAccountId ?: 0L)) }
    var categoryId by remember { mutableStateOf(initialFields?.get("categoryId")?.toLongOrNull() ?: (seed?.categoryId ?: 0L)) }
    var frequencyType by remember { mutableStateOf(initialFields?.get("frequencyType")?.toIntOrNull() ?: (seed?.let { if (it.scheduledFrequencyType == 0) it.pausedFromFrequencyType ?: 3 else it.scheduledFrequencyType } ?: 3)) }
    var frequencyValues by remember { mutableStateOf(initialFields?.get("frequencyValues") ?: (seed?.let { if (it.scheduledFrequencyType == 0) it.pausedFromFrequency.orEmpty() else it.scheduledFrequency.orEmpty() }.orEmpty().ifBlank { if (frequencyType == 3) "0" else "" })) }
    var startDate by remember { mutableStateOf(initialFields?.get("startDate") ?: (seed?.scheduledStartDate.orEmpty())) }
    var endDate by remember { mutableStateOf(initialFields?.get("endDate") ?: (seed?.scheduledEndDate.orEmpty())) }
    var hideAmount by remember { mutableStateOf(initialFields?.get("hideAmount")?.toBooleanStrictOrNull() ?: (seed?.hideAmount ?: false)) }
    val deviceOffset = remember { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000 }
    var offsetText by remember { mutableStateOf(initialFields?.get("offsetText") ?: (seed?.utcOffset?.let { scheduleOffsetText(it) } ?: scheduleOffsetText(deviceOffset))) }

    val rawFields = mapOf("name" to name,
        "type" to type.toString(),
        "amount" to amount,
        "destinationAmount" to destinationAmount,
        "comment" to comment,
        "accountId" to accountId.toString(),
        "destinationAccountId" to destinationAccountId.toString(),
        "categoryId" to categoryId.toString(),
        "frequencyType" to frequencyType.toString(),
        "frequencyValues" to frequencyValues,
        "startDate" to startDate,
        "endDate" to endDate,
        "hideAmount" to hideAmount.toString(),
        "offsetText" to offsetText)
    SideEffect { onFormChange(rawFields) }
    val amountMinor = runCatching { amount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
    val sourceAccount = accounts.singleOrNull { it.id == accountId }
    val destinationAccount = accounts.singleOrNull { it.id == destinationAccountId }
    val sameCurrency = sourceAccount != null && destinationAccount != null && sourceAccount.currency == destinationAccount.currency
    val destinationAmountMinor = if (sameCurrency) amountMinor else runCatching { destinationAmount.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
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
    val valid = name.trim().isNotEmpty() && amountMinor != null && amountMinor in -9_999_999_999_999L..9_999_999_999_999L && (type == TransactionRepository.TYPE_EXPENSE || amountMinor >= 0) &&
        accounts.any { it.id == accountId && it.type == 1 && !it.hidden && (it.parentId == 0L || accounts.any { parent -> parent.id == it.parentId && !parent.hidden }) } &&
        categories.any { it.id == categoryId && !it.hidden && it.parentId > 0 && it.type == when (type) { 2 -> 1; 4 -> 3; else -> 2 } && categories.any { parent -> parent.id == it.parentId && !parent.hidden } } && frequencyValid &&
        (type != TransactionRepository.TYPE_TRANSFER || (destinationAccountId > 0L && destinationAccountId != accountId &&
            destinationAmountMinor != null && destinationAmountMinor in 0..9_999_999_999_999L && accounts.any { it.id == destinationAccountId && it.type == 1 && !it.hidden && (it.parentId == 0L || accounts.any { parent -> parent.id == it.parentId && !parent.hidden }) }))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增周期计划" else "编辑周期计划") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draftStatus.isNotBlank()) Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(draftStatus, color = if (draftStatus.contains("失败")) MaterialTheme.colorScheme.error else Muted) }
                if (draftStatus.contains("失败") && onRetryDraft != null) TextButton(onClick = onRetryDraft, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试保存草稿") }
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
                    categoryId = 0L
                    if (type != TransactionRepository.TYPE_TRANSFER) destinationAccountId = 0L
                }
                OutlinedTextField(amount, { amount = it.filter { char -> char.isDigit() || char == '.' || char == '-' }.take(16) },
                    label = { Text(if (type == TransactionRepository.TYPE_TRANSFER) "转出金额" else "金额") }, prefix = { Text(sourceAccount?.currency.orEmpty()) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotEmpty() && (amountMinor == null || amountMinor !in -9_999_999_999_999L..9_999_999_999_999L || (type != 3 && amountMinor < 0)),
                    supportingText = { if (type == 3) Text("负金额表示退款，冲减支出。") })
                if (type == TransactionRepository.TYPE_TRANSFER && !sameCurrency) {
                    OutlinedTextField(destinationAmount, { destinationAmount = it.filter { char -> char.isDigit() || char == '.' }.take(15) },
                        label = { Text("转入金额（币种不同时必填）") }, prefix = { Text(destinationAccount?.currency.orEmpty()) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = destinationAmount.isNotEmpty() && (destinationAmountMinor == null || destinationAmountMinor <= 0))
                }
                if (type == TransactionRepository.TYPE_TRANSFER && sameCurrency) Text("同币种转账将按转出金额记入。", color = Muted)
                Text("账户", color = Muted, fontSize = 12.sp)
                AccountPicker(accounts.filter { !it.hidden && it.type == 1 }, accountId, if (type == 2) "目标" else "来源") { accountId = it }
                if (accounts.none { !it.hidden && it.type == 1 }) Text("请先同步一个可用服务端账户。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (type == TransactionRepository.TYPE_TRANSFER) {
                    Text("转入账户", color = Muted, fontSize = 12.sp)
                    AccountPicker(accounts.filter { !it.hidden && it.type == 1 && it.id != accountId }, destinationAccountId, "转入") { destinationAccountId = it }
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
                        3 -> "0"; else -> frequencyValues
                    }
                }
                when (frequencyType) {
                    1 -> WeekdayChips(frequencyValues) { frequencyValues = it }
                    5 -> OutlinedTextField(frequencyValues, { frequencyValues = it.filter(Char::isDigit).take(4) },
                        label = { Text("间隔天数") }, singleLine = true)
                    else -> OutlinedTextField(frequencyValues, { frequencyValues = it.filter { char -> char.isDigit() || char == ',' || char == '-' }.take(100) },
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
            TextButton(enabled = valid && persistReady, onClick = {
                val draft = TemplateEntity(existing?.id ?: 0L, name.trim(), type, categoryId, accountId, amountMinor!!,
                    comment.trim(), existing?.tagIdsJson ?: "[]", existing?.hidden ?: false, templateType = 2,
                    destinationAccountId = if (type == TransactionRepository.TYPE_TRANSFER) destinationAccountId else 0,
                    destinationAmountMinor = if (type == TransactionRepository.TYPE_TRANSFER) destinationAmountMinor!! else 0,
                    hideAmount = hideAmount, scheduledFrequencyType = frequencyType, scheduledFrequency = frequencyValues,
                    scheduledStartDate = startDate.ifBlank { null }, scheduledEndDate = endDate.ifBlank { null },
                    utcOffset = offsetMinutes, scheduledAt = 0, displayOrder = existing?.displayOrder ?: 0,
                    pausedFromFrequencyType = existing?.pausedFromFrequencyType, pausedFromFrequency = existing?.pausedFromFrequency, ledgerId = ledgerId)
                onPreview(draft)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("预览") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }
    )
}

@Composable
private fun SegmentsRow(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label -> FilterChip(selected == label, { onSelect(label) }, { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}

@Composable
private fun AccountPicker(accounts: List<AccountEntity>, selected: Long, direction: String, onSelect: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { account -> FilterChip(selected == account.id, { onSelect(account.id) }, { Text(account.name) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "周期${direction}账户：${account.name} · ${account.currency} · ${account.id}" }) }
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
        leaves.forEach { category -> FilterChip(selected == category.id, { onSelect(category.id) }, { Text(category.name) }, modifier = Modifier.heightIn(min = 48.dp)) }
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
            }, { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "周$label" })
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
