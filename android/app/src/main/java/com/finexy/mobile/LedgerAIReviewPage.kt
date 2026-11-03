package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal suspend fun loadAILedgerResources(api: FinexyApi, ledgerId: Long): PeriodicResources {
    api.requireLedgerAIReview(ledgerId)
    val role = api.periodicRole(ledgerId)
    val rawAccounts = api.listAccounts(ledgerId); requirePeriodicAccountsLedger(rawAccounts, ledgerId)
    val accounts = api.parseAccountResponse(rawAccounts).map { AccountEntity(id = it.id, name = it.name, currency = it.currency,
        balanceMinor = it.balanceMinor, hidden = it.hidden, parentId = it.parentId, type = it.type, category = it.category) }
    val rawCategories = api.listCategories(ledgerId = ledgerId); requirePeriodicCategories(rawCategories)
    val categories = api.parseCategoryResponse(rawCategories).map { CategoryEntity(id = it.id, name = it.name, parentId = it.parentId,
        type = it.type, hidden = it.hidden, icon = it.icon) }
    return PeriodicResources(accounts, categories, role, loading = false)
}

internal suspend fun deliverAIOriginal(context: android.content.Context, store: SecureStore, repository: TransactionRepository,
    api: FinexyApi, write: AIReviewRequestEntity) {
    SyncEngine.withoutSync {
        val observed = requireOriginalAIReviewDelivery(write, repository.aiReview.requests().singleOrNull { it.requestId == write.requestId })
        if (observed.state == AIReviewRequestEntity.PENDING) AIReviewScheduler.enqueueCurrent(context.applicationContext, store)
        val original = object : AIReviewRequestStore by repository.aiReview {
            override suspend fun requests() = listOf(requireOriginalAIReviewDelivery(observed,
                repository.aiReview.requests().singleOrNull { it.requestId == write.requestId }))
        }
        val result = AIReviewDelivery.forApi(original, api).push()
        if (result.errors.isNotEmpty()) throw result.errors.first()
        check(repository.aiReview.requests().none { it.requestId == write.requestId })
    }
}

@Composable
internal fun LedgerAIReviewPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean,
    ledgerId: Long, onBack: () -> Unit, initialPendingLocation: LedgerPendingPageLocation? = null, onSource: () -> Unit) {
    if (initialPendingLocation != null) {
        LedgerAIPendingReviewPage(store, repository, localMode, ledgerId, initialPendingLocation, onBack)
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember(store, ledgerId) { FinexyApi(store) }
    val server = remember(store) { store.get(FinexyApi.KEY_SERVER_URL) }; val token = remember(store) { store.get(FinexyApi.KEY_TOKEN) }
    fun current() { check(server == store.get(FinexyApi.KEY_SERVER_URL) && token == store.get(FinexyApi.KEY_TOKEN)) }
    var resources by remember(ledgerId) { mutableStateOf(PeriodicResources()) }
    var status by remember(ledgerId) { mutableIntStateOf(1) }
    val rows by remember(repository, ledgerId, status) { repository.aiReview.observeItems(ledgerId, status) }.collectAsState(initial = null)
    val writes by repository.aiReview.observeRequests().collectAsState(initial = null)
    val drafts by remember(repository, ledgerId) { repository.aiReview.observeDrafts(ledgerId) }.collectAsState(initial = null)
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    val ledgerName = if (ledgerId == 0L) "默认个人账本" else ledgers.singleOrNull { it.id == ledgerId }?.name ?: "当前选定账本"
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LedgerAIReviewItem?>(null) }
    var action by remember { mutableStateOf<Pair<LedgerAIReviewItem, String>?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    suspend fun refresh() {
        val capturedStatus = status
        resources = PeriodicResources()
        try {
            check(!localMode); current()
            val dictionary = loadAILedgerResources(api, ledgerId)
            val complete = api.listLedgerAIReviewItems(ledgerId, capturedStatus)
            current(); if (status != capturedStatus) return
            repository.aiReview.replaceSnapshot(ledgerId, capturedStatus, complete)
            resources = dictionary
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { resources = PeriodicResources(loading = false, error = "读取失败或失去访问权限，请重新核验；本机草稿和原请求仍保留。") }
    }
    LaunchedEffect(ledgerId, status) { refresh() }
    BackHandler { if (selected != null) selected = null else onBack() }
    selected?.let { item ->
        LedgerAIReviewEditor(store, repository, item, ledgerName, resources, onBack = { selected = null }, onChanged = {
            selected = null; scope.launch { refresh() }
        })
        return
    }
    AIPageScaffold("AI 待复核", onBack) {
        Text("目标账本：$ledgerName", style = MaterialTheme.typography.titleMedium)
        Text("确认入账会同时确认原队列条目；忽略和恢复不改变余额。", color = Muted)
        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !resources.loading && !running && !localMode,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新此账本队列") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(status == 1, { status = 1 }, label = { Text("待复核") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(status == 3, { status = 3 }, label = { Text("已忽略") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        if (resources.loading || running) LinearProgressIndicator(Modifier.fillMaxWidth())
        resources.error?.let { StatusText(it, true) }
        message?.let { StatusText(it, true) }
        if (!resources.loading && resources.error == null) {
            if (!resources.canReview) Text("当前成员只读，可查看此账本队列。", color = Muted)
            Row { Checkbox(showOriginal, { showOriginal = it }); Text("显示原始来源与识别建议", modifier = Modifier.padding(top = 12.dp)) }
            writes.orEmpty().filter { it.ledgerId == ledgerId }.forEach { write -> ReviewPanel {
                Text(if (write.action == "create") "来源保存结果待核对" else "原确认入账结果待核对", style = MaterialTheme.typography.titleMedium)
                Text(if (write.state == AIReviewRequestEntity.POSTED) "已有回执，只会重新读取原条目。" else "保留原请求，不能替代提交。", color = Muted)
                Button(onClick = { scope.launch {
                    running = true
                    try {
                        current(); val role = api.periodicRole(ledgerId)
                        check(role in 1..4 && (write.state == AIReviewRequestEntity.POSTED || role in 1..3))
                        current(); deliverAIOriginal(context, store, repository, api, write); current(); refresh()
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "原操作结果仍待核对，请重试；请求不会被替换。" }
                    finally { running = false }
                } }, enabled = !running && (write.state == AIReviewRequestEntity.POSTED || resources.canReview), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (write.state == AIReviewRequestEntity.POSTED) "重新读取原条目" else "重试原请求") }
            } }
            drafts.orEmpty().filter { it.itemId > 0 }.forEach { draft ->
                OutlinedButton(onClick = { scope.launch {
                    try { current(); val remote = api.getLedgerAIReviewItem(ledgerId, draft.itemId); current(); selected = remote }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "原条目无法读取，编辑草稿仍保留，请重新核验。" }
                } }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复本机编辑草稿 · ${draft.itemId}") }
            }
            if (rows == null) Text("正在读取本机队列…", color = Muted)
            else if (rows.orEmpty().isEmpty()) ReviewPanel {
                Text(if (status == 1) "没有待复核条目" else "没有已忽略条目", style = MaterialTheme.typography.titleMedium)
                Text("可以先录入来源，AI 识别与待复核都不会自动入账。", color = Muted)
            }
            rows.orEmpty().forEach { cached ->
                val item = runCatching { validateLedgerAICache(cached) }.getOrNull()
                if (item == null) StatusText("本机条目不完整，请刷新；不会按空队列处理。", true)
                else ReviewPanel {
                    Text(if (item.sourceType == 2) "票据图片来源" else "文字来源", style = MaterialTheme.typography.titleMedium)
                    if (showOriginal) {
                        Text(item.sourceText)
                        item.recognized?.let { Text("AI 建议 · ${it.comment.ifBlank { "描述待补充" }}", color = Muted) }
                    } else Text("原始来源与金额已隐藏；打开核对后可查看。", color = Muted)
                    Button(onClick = { selected = item }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (resources.canReview && item.editable && status == 1) "核对并确认入账" else "查看条目与本机草稿") }
                    if (resources.canReview && item.editable) {
                        OutlinedButton(onClick = { action = item to if (status == 1) "dismiss" else "restore" }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (status == 1) "忽略此条目" else "恢复此条目") }
                        if (status == 3) TextButton(onClick = { action = item to "delete" }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除已忽略条目", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        OutlinedButton(onClick = onSource, enabled = !running && !localMode, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("录入或恢复本机来源") }
    }
    if ((context as? PrivacyActivity)?.isPrivacyLocked != true) action?.let { (item, verb) ->
        val title = when (verb) { "dismiss" -> "忽略此待复核条目？"; "restore" -> "恢复此待复核条目？"; else -> "删除已忽略条目？" }
        AlertDialog(onDismissRequest = { action = null }, title = { Text(title) }, text = { AIScrollableConfirmationText("目标账本：$ledgerName。${if (verb == "delete") "删除后不能从队列恢复，已有流水不受影响。" else "此操作不改变余额。"}") },
            confirmButton = { TextButton(onClick = { action = null; scope.launch {
                running = true
                try { current(); api.changeLedgerAIReviewItem(ledgerId, item.id, verb); current(); refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "操作未核对成功，请刷新原条目后重试；不会替代入账。" }
                finally { running = false }
            } }) { Text("确认") } }, dismissButton = { TextButton(onClick = { action = null }) { Text("取消") } })
    }
}

/** A pending route reads its original item only. It never refreshes the whole queue. */
@Composable
private fun LedgerAIPendingReviewPage(store: SecureStore, repository: TransactionRepository, localMode: Boolean,
    ledgerId: Long, location: LedgerPendingPageLocation, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val guard = rememberLedgerPendingPageGuard(location, store, repository, localMode, ledgerId)
    val status = guard?.state?.collectAsState()?.value
    val api = remember(store, ledgerId) { FinexyApi(store) }
    val itemId = location.target.reviewItemId()
    val writes by repository.aiReview.observeRequests().collectAsState(initial = null)
    val ledgers by repository.observeLedgers().collectAsState(initial = emptyList())
    val ledgerName = if (ledgerId == 0L) "默认个人账本" else ledgers.singleOrNull { it.id == ledgerId }?.name ?: "原账本"
    var resources by remember(location) { mutableStateOf(PeriodicResources()) }
    var remote by remember(location) { mutableStateOf<LedgerAIReviewItem?>(null) }
    var failed by remember(location) { mutableStateOf(false) }
    var running by remember(location) { mutableStateOf(false) }
    var role by remember(location) { mutableStateOf<Int?>(null) }
    var originalDraft by remember(location) { mutableStateOf<AIReviewDraftEntity?>(null) }
    var originalRequest by remember(location) { mutableStateOf<AIReviewRequestEntity?>(null) }
    var message by remember(location) { mutableStateOf<String?>(null) }
    suspend fun loadOriginal() {
        failed = false; resources = PeriodicResources(); role = null
        try {
            check(!localMode && location.context.ledgerId == ledgerId)
            val targetId = requireNotNull(itemId)
            val pageGuard = requireNotNull(guard)
            check(pageGuard.verify())
            originalDraft = repository.aiReview.draft(ledgerId, targetId)
            originalRequest = repository.aiReview.requests().singleOrNull { it.ledgerId == ledgerId && it.itemId == targetId }
            check(pageGuard.isCurrent())
            role = api.periodicRole(ledgerId)
            val original = api.getLedgerAIReviewItem(ledgerId, targetId)
            check(original.ledgerId == ledgerId && original.id == targetId)
            val dictionary = loadAILedgerResources(api, ledgerId)
            check(pageGuard.verify())
            resources = dictionary; remote = original
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            failed = true; resources = PeriodicResources(loading = false,
                error = "原条目或当前权限无法读取。原草稿与原请求保留，不会打开替代表单。")
        }
    }
    LaunchedEffect(location) { loadOriginal() }
    // Keep this child mounted while its shared guard checks an action. It owns
    // the Room initialization gate and must not be recreated on CHECKING.
    remote?.let { item ->
        LedgerAIReviewEditor(store, repository, item, ledgerName, resources, initialPendingLocation = location,
            pendingGuard = guard, onBack = onBack, onChanged = onBack)
        return
    }
    BackHandler(onBack = onBack)
    val candidate = writes?.singleOrNull { it.ledgerId == ledgerId && it.itemId == itemId }
    val exactRequest = candidate?.takeIf {
        samePendingReviewRequest(originalRequest, it) && matchPendingReviewLocation(location.target, ledgerId, itemId ?: 0, originalDraft, it)
    }
    AIPageScaffold("原 AI 待处理", onBack) {
        Text("目标账本：$ledgerName", style = MaterialTheme.typography.titleMedium)
        if (!failed || running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在核验原条目…", color = Muted) }
        resources.error?.let { StatusText(it, true) }
        message?.let { StatusText(it, true) }
        if (failed && exactRequest != null && guard?.isCurrent() == true && status == LedgerPendingPageStatus.READY) ReviewPanel {
            Text("原条目尚未读取成功，原确认请求仍精确保留。")
            Text(if (exactRequest.state == AIReviewRequestEntity.POSTED) "已有回执，只重新读取原结果；不会新增或排其他同步。" else "只允许明确重试此原请求，不重新生成内容或请求标识。", color = Muted)
            Button(onClick = { scope.launch {
                running = true; message = null
                try {
                    val pageGuard = requireNotNull(guard)
                    check(pageGuard.verify())
                    val fresh = repository.aiReview.requests().singleOrNull { it.ledgerId == ledgerId && it.itemId == itemId }
                    check(samePendingReviewRequest(exactRequest, fresh))
                    val original = requireNotNull(fresh)
                    val currentRole = api.periodicRole(ledgerId)
                    check(currentRole in 1..4 && (original.state == AIReviewRequestEntity.POSTED || currentRole in 1..3))
                    check(pageGuard.isCurrent())
                    deliverAIOriginal(context, store, repository, api, original)
                    check(pageGuard.release()); onBack()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "原结果仍未核对，请保留原请求后重试。" }
                finally { running = false }
            } }, enabled = !running && (exactRequest.state == AIReviewRequestEntity.POSTED || role == 1 || role == 2 || role == 3),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (exactRequest.state == AIReviewRequestEntity.POSTED) "重新读取原结果" else "重试原确认请求") }
        }
        OutlinedButton(onClick = { scope.launch { loadOriginal() } }, enabled = !running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新核验原目标") }
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回原入口") }
    }
}
