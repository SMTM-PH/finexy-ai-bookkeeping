package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The ledger is captured by the parent; this page never falls back to a different ledger. */
@Composable
internal fun NormalTemplatePage(store: SecureStore, repository: TransactionRepository, localMode: Boolean,
    ledgerId: Long, onApply: (TemplateEntrySeed) -> Unit, fixtureResources: PeriodicResources? = null,
    initialPendingLocation: LedgerPendingPageLocation? = null, onBack: () -> Unit) {
    BackHandler { onBack() }
    val pendingGuard = rememberLedgerPendingPageGuard(initialPendingLocation, store, repository, localMode, ledgerId)
    val pendingState = pendingGuard?.state?.collectAsState()?.value
    var pendingAccessVerified by remember(pendingGuard) { mutableStateOf(pendingGuard == null) }
    val templates by remember(repository, ledgerId) { repository.observeNormalTemplates(ledgerId) }.collectAsState(initial = null)
    val ledgerRows by repository.observeLedgers().collectAsState(initial = emptyList())
    val ownTags by repository.observeTags().collectAsState(initial = emptyList())
    val resources = fixtureResources ?: rememberPeriodicResources(store, repository, localMode, ledgerId,
        preservePendingRecovery = pendingGuard != null, pendingContext = initialPendingLocation?.context, loadAllowed = pendingAccessVerified)
    val ledgerName = if (ledgerId == 0L) "默认个人账本" else ledgerRows.firstOrNull { it.id == ledgerId }?.name ?: "当前账本"
    val scope = rememberCoroutineScope()
    val api = remember(store) { FinexyApi(store) }
    val context = LocalContext.current.applicationContext
    val draftSession = rememberTemplateDraftSession(repository, ledgerId, 1)
    val writes by repository.observeTemplateCreations().collectAsState(initial = null)
    val write = writes?.singleOrNull { it.ledgerId == ledgerId && it.templateType == 1 }
    val draftRows by remember(repository, ledgerId) { repository.observeTemplateDraft(ledgerId, 1).map { listOfNotNull(it) } }.collectAsState(initial = null)
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<TemplateEntity?>(null) }
    var editorFields by remember { mutableStateOf<Map<String, String>?>(null) }
    var previewSnapshot by remember { mutableStateOf<TemplateFormDraftEntity?>(null) }
    var discardDraft by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TemplateEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TemplateEntity?>(null) }
    var applyTarget by remember { mutableStateOf<TemplateEntity?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    val pendingObjectsReady = pendingAccessVerified && !resources.loading && resources.error == null && templates != null && writes != null && draftRows != null && draftSession.loaded
    val originalMatches = templatePendingLocationMatches(initialPendingLocation?.target, ledgerId, 1, draftRows?.singleOrNull(), write)
    val pendingReady = pendingGuard == null || pendingObjectsReady && (pendingState == LedgerPendingPageStatus.RELEASED || pendingState == LedgerPendingPageStatus.READY && originalMatches)
    val showOtherTemplates = pendingGuard == null || pendingState == LedgerPendingPageStatus.RELEASED
    LaunchedEffect(pendingGuard) {
        if (pendingGuard != null) {
            editorOpen = false; editor = null; editorFields = null; preview = null
            discardDraft = false; deleteTarget = null; applyTarget = null
            if ((initialPendingLocation?.target as? LedgerPendingTarget.Template)?.templateType != 1) pendingGuard.invalidate()
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
    LaunchedEffect(write?.clientSessionId) {
        if (write == null && !editorOpen && preview == null) draftSession.load()
    }

    fun openSavedDraft(saved: TemplateFormDraftEntity): Boolean {
        val seed = if (saved.editingId == 0L) null else templates.orEmpty().singleOrNull { it.id == saved.editingId }
        if (saved.editingId > 0 && seed == null) { message = if (pendingGuard == null) "原模板不在当前列表中，请重新读取；草稿仍保留。" else TEMPLATE_PENDING_UNAVAILABLE; return false }
        editor = seed; editorFields = templateFormFields(saved.formJson); editorOpen = true
        return true
    }
    fun continueDraft() {
        if (pendingGuard == null) { draftSession.draft?.let(::openSavedDraft); return }
        scope.launch {
            running = true
            try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) { TEMPLATE_PENDING_UNAVAILABLE }
                draftSession.load()
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) { TEMPLATE_PENDING_UNAVAILABLE }
                val saved = requireNotNull(draftSession.draft) { TEMPLATE_PENDING_UNAVAILABLE }
                if (openSavedDraft(saved)) {
                    if (!pendingGuard.release()) { editorOpen = false; message = TEMPLATE_PENDING_UNAVAILABLE }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = TEMPLATE_PENDING_UNAVAILABLE }
            finally { running = false }
        }
    }

    fun mutate(action: suspend () -> Unit) {
        scope.launch {
            running = true; message = null
            try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) { TEMPLATE_PENDING_UNAVAILABLE }
                action()
                check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                message = "已保存"
                try { SyncEngine(api, repository).refreshPeriodic(ledgerId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "操作已保存，刷新失败；请重新读取列表。" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message = if (pendingGuard == null) "操作未确认：${error.message ?: "请先重新读取列表核对结果"}"
                    else if (!pendingGuard.isCurrent()) TEMPLATE_PENDING_UNAVAILABLE else "操作未确认，原请求仍保留；请重新读取权限后重试。"
                if (pendingGuard?.isCurrent() == true) resources.retry()
            }
            finally { running = false }
        }
    }
    fun apply(item: TemplateEntity, stripTags: Boolean = false) {
        scope.launch {
            running = true; message = null
            try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) { TEMPLATE_PENDING_UNAVAILABLE }
                // Fixture resources are only used by the non-exported QA host; real actions recheck membership.
                val role = if (fixtureResources != null) requireNotNull(resources.role) else api.periodicRole(ledgerId)
                val current = if (fixtureResources != null) item else api.parseTemplateResponse(api.listTemplates(1, ledgerId), ledgerId)
                    .singleOrNull { it.id == item.id }?.toNormalEntity() ?: error("此模板已不存在，请重新读取")
                require(current.ledgerId == ledgerId) { "模板账本不一致" }
                if (stripTags) require(current.tagIdsJson == item.tagIdsJson) { "模板标签已改变，请重新确认" }
                if (ledgerId > 0 && role != RemoteLedgerMember.ROLE_OWNER && templateTagIds(current.tagIdsJson).isNotEmpty() && !stripTags) {
                    applyTarget = current
                } else {
                    // Fresh account/category validation is required when applying an online template.
                    val accounts = if (fixtureResources != null) resources.accounts else {
                        val raw = api.listAccounts(ledgerId); requirePeriodicAccountsLedger(raw, ledgerId)
                        api.parseAccountResponse(raw).map { AccountEntity(id = it.id, name = it.name, currency = it.currency, balanceMinor = it.balanceMinor, hidden = it.hidden, parentId = it.parentId,
                            category = it.category, type = it.type, icon = it.icon, color = it.color, comment = it.comment, displayOrder = it.displayOrder, creditCardStatementDate = it.creditCardStatementDate) }
                    }
                    val categories = if (fixtureResources != null) resources.categories else {
                        val raw = api.listCategories(ledgerId = ledgerId); requirePeriodicCategories(raw)
                        api.parseCategoryResponse(raw).map {
                            CategoryEntity(id = it.id, name = it.name, parentId = it.parentId, type = it.type, icon = it.icon, color = it.color, hidden = it.hidden, comment = it.comment, displayOrder = it.displayOrder) }
                    }
                    check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                    onApply(applyNormalTemplate(current, ledgerId, role, accounts, categories, stripTags))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = "套用失败：${error.message ?: "请重新读取后重试"}" }
            finally { running = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Column(Modifier.safeDrawingPadding().imePadding().widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回设置") }
            Text("流水模板", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(ledgerName, color = Muted)
            Text("套用后先核对新流水，保存时才入账。", color = Muted)
            TemplatePendingLocationNotice(if (pendingGuard != null && (resources.error != null || draftSession.failure != null)) LedgerPendingPageStatus.FAILED else pendingState,
                pendingObjectsReady, onRetry = { scope.launch {
                    pendingAccessVerified = pendingGuard?.verify() != false
                    if (pendingAccessVerified) { draftSession.load(); resources.retry() }
                } })
            TextButton(onClick = {
                if (pendingGuard == null) resources.retry() else scope.launch {
                    pendingAccessVerified = pendingGuard.verify(); if (pendingAccessVerified) resources.retry()
                }
            }, enabled = !running && !localMode, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取模板") }
            message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (it.contains("失败") || it.contains("未确认")) MaterialTheme.colorScheme.error else Muted) }
            if (pendingReady) TemplateRecoveryPanel(draftSession, write, ledgerName, !running && writes != null &&
                (resources.canManage || pendingGuard != null && templatePendingRecoveryMode(resources.role, write?.state) == TemplatePendingRecoveryMode.READ_RECEIPT),
                onContinue = ::continueDraft, onDiscard = { scope.launch {
                    if (verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) discardDraft = true else message = TEMPLATE_PENDING_UNAVAILABLE
                } }, onReload = { scope.launch {
                    if (verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) draftSession.load() else message = TEMPLATE_PENDING_UNAVAILABLE
                } }, onRetry = { mutate {
                    val displayed = requireNotNull(write) { TEMPLATE_PENDING_UNAVAILABLE }
                    val original = requireNotNull(repository.templateCreations().singleOrNull { it.ledgerId == ledgerId && it.templateType == 1 }) { TEMPLATE_PENDING_UNAVAILABLE }
                    check(pendingTemplateRequestRecord(original).target == pendingTemplateRequestRecord(displayed).target) { TEMPLATE_PENDING_UNAVAILABLE }
                    if (pendingGuard != null) check(templatePendingLocationMatches(pendingGuard.location.target, ledgerId, 1, null, original)) { TEMPLATE_PENDING_UNAVAILABLE }
                    check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                    val mode = if (pendingGuard == null) null else requireNotNull(templatePendingRecoveryMode(api.periodicRole(ledgerId), original.state)) { "当前成员不能重试模板创建；原请求仍保留。" }
                    check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                    if (mode == TemplatePendingRecoveryMode.READ_RECEIPT) SyncEngine(api, repository).refreshPeriodic(ledgerId)
                    else { SyncScheduler.enqueueCurrent(context, store, replace = true); SyncEngine(api, repository).performTemplateCreation(original) }
                    check(pendingGuard?.release() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                    draftSession.load()
                } }, canEdit = resources.canManage)
            when {
                !pendingReady -> Unit
                localMode -> Text("登录服务器后可管理当前账本模板。", color = Muted)
                resources.loading -> Text("正在核验账本与成员权限…", color = Muted)
                resources.error != null -> Text("读取失败：${resources.error}", color = MaterialTheme.colorScheme.error)
                else -> {
                    if (resources.role == RemoteLedgerMember.ROLE_VIEWER) Text("当前成员只读", color = Muted)
                    if (resources.canManage && showOtherTemplates) Button(enabled = !running && draftSession.ready && draftSession.draft == null && write == null && writes != null,
                        onClick = { editor = null; editorFields = null; editorOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("新增模板") }
                    if (showOtherTemplates) FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("显示已隐藏模板") }, modifier = Modifier.heightIn(min = 48.dp))
                    val rows = templates.orEmpty().filter { showOtherTemplates && (showHidden || !it.hidden) }
                    if (templates == null) Text("正在加载模板…", color = Muted)
                    else if (rows.isEmpty() && showOtherTemplates) Text("此账本没有${if (showHidden) "" else "可见"}流水模板。", color = Muted)
                    rows.forEach { item -> PanelCard {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(normalTemplateSummary(item, resources.accounts), color = Muted)
                        if (item.hidden) Text("已隐藏", color = Muted)
                        if (resources.canReview && !item.hidden) TextButton(enabled = !running, onClick = { apply(item) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "套用模板：${item.name}" }) { Text("套用") }
                        if (resources.canManage) {
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(enabled = !running && draftSession.ready && draftSession.draft == null && write == null && writes != null,
                                    onClick = { editor = item; editorFields = null; editorOpen = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "编辑模板：${item.name}" }) { Text("编辑") }
                                TextButton(enabled = !running, onClick = { mutate { api.hideTemplate(item.id, !item.hidden, ledgerId) } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${if (item.hidden) "恢复显示模板" else "隐藏模板"}：${item.name}" }) { Text(if (item.hidden) "恢复显示" else "隐藏") }
                                TextButton(enabled = !running, onClick = { deleteTarget = item }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除模板：${item.name}" }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                            val index = templates.orEmpty().indexOfFirst { it.id == item.id }
                            TextButton(enabled = !running && index > 0, onClick = { val ordered = templates.orEmpty().toMutableList(); ordered.add(index - 1, ordered.removeAt(index)); mutate { api.moveTemplates(ordered) } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "上移模板：${item.name}" }) { Text("上移") }
                        }
                    } }
                }
            }
        }
    }
    if (editorOpen && pendingReady) TemplateEditorDialog(editor, resources.accounts, resources.categories,
        if (resources.role == RemoteLedgerMember.ROLE_OWNER) ownTags else emptyList(),
        onDismiss = { editorOpen = false }, onPreview = { target -> scope.launch {
            try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) { TEMPLATE_PENDING_UNAVAILABLE }
                previewSnapshot = draftSession.snapshot()
                check(pendingGuard?.isCurrent() != false) { TEMPLATE_PENDING_UNAVAILABLE }
                preview = target; editorOpen = false; editorFields = templateFormFields(requireNotNull(previewSnapshot).formJson)
            }
            catch (error: Exception) { message = "草稿未确认：${error.message}" }
        } }, initialFields = editorFields, draftStatus = draftSession.status,
        persistReady = draftSession.ready && resources.canManage && write == null && writes != null && pendingReady,
        onFormChange = { if (pendingGuard?.isCurrent() != false) draftSession.changed(editor?.id ?: 0L, it) }, onRetryDraft = { if (pendingGuard?.isCurrent() != false) draftSession.retrySave() },
        ledgerId = ledgerId, allowTagEditing = resources.role == RemoteLedgerMember.ROLE_OWNER)
    preview?.takeIf { pendingReady }?.let { draft -> AlertDialog(onDismissRequest = { preview = null; editorOpen = true }, title = { Text("确认模板内容") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ledgerName); Text(draft.name); Text(normalTemplateSummary(draft, resources.accounts))
            Text("分类：${resources.categories.singleOrNull { it.id == draft.categoryId }?.name ?: "待核对"}")
            Text("备注：${draft.comment.ifBlank { "无" }}")
            Text("标签关联：${templateTagIds(draft.tagIdsJson).size} 个${if (resources.role != RemoteLedgerMember.ROLE_OWNER) "（保留原关联）" else ""}")
        } }, confirmButton = { TextButton(enabled = !running && resources.canManage && draftSession.ready && write == null, onClick = { preview = null; mutate {
            validateNormalTemplate(draft, ledgerId, resources.accounts, resources.categories)
            saveConfirmedTemplate(context, store, repository, api, draftSession, draft, requireNotNull(previewSnapshot))
            editor = null; editorFields = null
        } }) { Text("确认保存") } }, dismissButton = { TextButton(onClick = { preview = null; editorOpen = true }) { Text("返回修改") } }) }
    deleteTarget?.takeIf { pendingReady }?.let { item -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除流水模板？") },
        text = { Text("将永久删除“${item.name}”。已有流水保留，此操作无法撤销。") },
        confirmButton = { TextButton(enabled = !running, onClick = { deleteTarget = null; mutate { api.deleteTemplate(item.id, ledgerId) } }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }) }
    applyTarget?.takeIf { pendingReady }?.let { item -> AlertDialog(onDismissRequest = { applyTarget = null }, title = { Text("确认模板标签") },
        text = { Text("此模板含账本所有者的标签。你只能在新流水中去除这些标签后继续，其他模板字段保留。模板本身不会改变。") },
        confirmButton = { TextButton(enabled = !running, onClick = { applyTarget = null; apply(item, stripTags = true) }) { Text("去除标签并继续") } },
        dismissButton = { TextButton(onClick = { applyTarget = null }) { Text("取消") } }) }
    if (discardDraft && pendingReady) AlertDialog(onDismissRequest = { discardDraft = false }, title = { Text("丢弃本机草稿？") },
        text = { Text("只删除此账本尚未确认的表单输入，无法恢复。") },
        confirmButton = { TextButton(enabled = draftSession.ready && !running && write == null, onClick = {
            discardDraft = false; scope.launch { running = true; try {
                check(verifyTemplatePendingLocation(pendingGuard, repository, ledgerId, 1)) { TEMPLATE_PENDING_UNAVAILABLE }
                draftSession.discard(); pendingGuard?.release(); editor = null; editorFields = null
            }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = "丢弃失败：${error.message}" }
                finally { running = false } }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认丢弃") } },
        dismissButton = { TextButton(onClick = { discardDraft = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

internal fun normalTemplateSummary(item: TemplateEntity, accounts: List<AccountEntity>): String {
    val source = accounts.singleOrNull { it.id == item.sourceAccountId }
    val destination = accounts.singleOrNull { it.id == item.destinationAccountId }
    val kind = when (item.type) { 2 -> "收入"; 4 -> "转账"; else -> if (item.sourceAmountMinor < 0) "退款" else "支出" }
    val amount = if (item.hideAmount) "金额已隐藏" else currencyMoney(item.sourceAmountMinor, source?.currency ?: "币种待核对")
    return "$kind · $amount · ${source?.name ?: "账户待核对"}" + if (item.type == 4) " → ${destination?.name ?: "账户待核对"} · ${if (item.hideAmount) "金额已隐藏" else currencyMoney(item.destinationAmountMinor, destination?.currency ?: "币种待核对")}" else ""
}

private fun RemoteTemplate.toNormalEntity() = TemplateEntity(id, name, type, categoryId, sourceAccountId, sourceAmountMinor, comment,
    tagIdsJson, hidden, templateType = templateType, destinationAccountId = destinationAccountId,
    destinationAmountMinor = destinationAmountMinor, hideAmount = hideAmount, displayOrder = displayOrder, ledgerId = ledgerId)
