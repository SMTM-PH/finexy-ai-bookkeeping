package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun aiDictionaryStamp(resources: PeriodicResources) =
    resources.accounts.sortedBy { it.id }.map { listOf(it.id.toString(), it.currency, it.type.toString(), it.parentId.toString(), it.hidden.toString()) } to
        resources.categories.sortedBy { it.id }.map { listOf(it.id.toString(), it.type.toString(), it.parentId.toString(), it.hidden.toString()) }

@Composable
internal fun LedgerAIReviewEditor(store: SecureStore, repository: TransactionRepository, item: LedgerAIReviewItem,
    ledgerName: String, resources: PeriodicResources, initialPendingLocation: LedgerPendingPageLocation? = null,
    pendingGuard: LedgerPendingPageGuard? = null, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mutex = remember(item.id) { Mutex() }
    val api = remember(store) { FinexyApi(store) }
    val server = remember(store) { store.get(FinexyApi.KEY_SERVER_URL) }; val token = remember(store) { store.get(FinexyApi.KEY_TOKEN) }
    val guardStatus = pendingGuard?.state?.collectAsState()?.value
    val pendingTarget = initialPendingLocation?.target
    val pendingRoute = initialPendingLocation != null
    val acceptedLocation = !pendingRoute || pendingGuard != null && pendingGuard.location == initialPendingLocation &&
        pendingTarget?.ledgerId == item.ledgerId && pendingTarget?.reviewItemId() == item.id
    var sessionValid by remember { mutableStateOf(true) }
    fun current() {
        sessionValid = server == store.get(FinexyApi.KEY_SERVER_URL) && token == store.get(FinexyApi.KEY_TOKEN) &&
            (!pendingRoute || acceptedLocation && pendingGuard?.isCurrent() == true)
        check(sessionValid)
    }
    var form by remember(item.id) { mutableStateOf(AIReviewEditorForm.initial(item)) }
    var saved by remember(item.id) { mutableStateOf<AIReviewDraftEntity?>(null) }
    var ready by remember(item.id) { mutableStateOf(false) }
    var restoring by remember(item.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<AIReviewDraftEntity, String>?>(null) }
    var discard by remember { mutableStateOf(false) }
    var permissionVerified by remember { mutableStateOf(true) }
    var expectedWrite by remember(item.id) { mutableStateOf<AIReviewRequestEntity?>(null) }
    var locationReady by remember(item.id) { mutableStateOf(!pendingRoute) }
    var initializationVersion by remember(item.id) { mutableIntStateOf(0) }
    var initializationFailed by remember(item.id) { mutableStateOf(false) }
    var edited by remember(item.id) { mutableStateOf(false) }
    val writes by repository.aiReview.observeRequests().collectAsState(initial = null)
    val observedDraft by remember(repository, item.ledgerId, item.id) { repository.aiReview.observeDraft(item.ledgerId, item.id) }.collectAsState(initial = null)
    val candidateWrite = writes?.singleOrNull { it.ledgerId == item.ledgerId && it.itemId == item.id }
    // Do not show a replacement request for even one composition while an
    // asynchronous observer/effect is catching up with Room.
    val localSnapshotCurrent = !pendingRoute || ready && writes != null && samePendingReviewRequest(expectedWrite, candidateWrite) && observedDraft == saved &&
        (guardStatus == LedgerPendingPageStatus.RELEASED || pendingTarget != null &&
            matchPendingReviewLocation(pendingTarget, item.ledgerId, item.id, saved, candidateWrite))
    val locationCurrent = !pendingRoute || acceptedLocation && locationReady && localSnapshotCurrent &&
        guardStatus in setOf(LedgerPendingPageStatus.READY, LedgerPendingPageStatus.RELEASED) && pendingGuard?.isCurrent() == true
    val write = candidateWrite.takeIf { locationCurrent }
    val editable = locationCurrent && ready && !restoring && !busy && write == null && writes != null && resources.canReview && permissionVerified && item.editable && item.status == 1 && !item.deleted
    LaunchedEffect(item.id, initializationVersion) {
        locationReady = !pendingRoute; ready = false; initializationFailed = false
        try {
            check(acceptedLocation)
            if (pendingRoute) check(requireNotNull(pendingGuard).verify())
            current()
            val originalDraft = repository.aiReview.draft(item.ledgerId, item.id)
            val originalWrite = repository.aiReview.requests().singleOrNull { it.ledgerId == item.ledgerId && it.itemId == item.id }
            current()
            if (pendingRoute) {
                check(pendingGuard?.state?.value == LedgerPendingPageStatus.RELEASED || requireNotNull(pendingTarget).let {
                    matchPendingReviewLocation(it, item.ledgerId, item.id, originalDraft, originalWrite)
                })
                check(requireNotNull(pendingGuard).verify())
                check(repository.aiReview.draft(item.ledgerId, item.id) == originalDraft)
                check(samePendingReviewRequest(originalWrite, repository.aiReview.requests().singleOrNull { it.ledgerId == item.ledgerId && it.itemId == item.id }))
                current()
            }
            saved = originalDraft; expectedWrite = originalWrite; restoring = saved != null; ready = true; locationReady = true
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { initializationFailed = true; message = "本机编辑草稿无法读取，请返回重试；不会覆盖原内容。" }
    }
    suspend fun verifyOriginal() {
        if (!pendingRoute) { current(); return }
        val guard = requireNotNull(pendingGuard)
        check(acceptedLocation && guard.verify())
        current()
        val actualDraft = repository.aiReview.draft(item.ledgerId, item.id)
        val actualWrite = repository.aiReview.requests().singleOrNull { it.ledgerId == item.ledgerId && it.itemId == item.id }
        check(actualDraft == saved && samePendingReviewRequest(expectedWrite, actualWrite))
        if (guard.state.value != LedgerPendingPageStatus.RELEASED) check(requireNotNull(pendingTarget).let {
            matchPendingReviewLocation(it, item.ledgerId, item.id, actualDraft, actualWrite)
        })
        current()
    }
    suspend fun snapshot(): AIReviewDraftEntity = mutex.withLock {
        current(); val captured = form
        if (pendingRoute) {
            check(samePendingReviewRequest(expectedWrite, repository.aiReview.requests().singleOrNull { it.ledgerId == item.ledgerId && it.itemId == item.id }))
            current()
        }
        val updated = if (saved?.formJson == captured.json()) requireNotNull(saved)
            else repository.aiReview.saveDraft(item.ledgerId, item.id, captured.json(), saved)
        current(); saved = updated; saving = form.json() != updated.formJson
        if (pendingTarget is LedgerPendingTarget.AIItem && pendingGuard?.state?.value == LedgerPendingPageStatus.READY) check(requireNotNull(pendingGuard).release())
        check(form == captured) { "输入已变化" }; updated
    }
    fun edit(key: String, value: String) {
        if (!editable || pendingRoute && pendingGuard?.isCurrent() != true) return
        edited = true
        form = form.changed(key, value); saving = true; preview = null; message = null
        scope.launch {
            try { snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { saving = false; message = "草稿保存失败或其他页面已更新；保留当前输入，请重新核验后提交。" }
        }
    }
    fun back() {
        if (pendingRoute && (!locationCurrent || pendingGuard?.isCurrent() != true)) { onBack(); return }
        scope.launch {
            try { if (ready && !restoring && write == null && item.status == 1 && item.editable && (!pendingRoute || edited && saving)) snapshot(); onBack() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "编辑内容尚未保存，返回前请重试保存或明确丢弃。" }
        }
    }
    BackHandler { if (preview != null) preview = null else back() }
    fun field(label: String, key: String, multiline: Boolean = false): @Composable () -> Unit = {
        OutlinedTextField(form.fields[key].orEmpty(), { edit(key, it) }, label = { Text(label) },
            enabled = editable, singleLine = !multiline, modifier = Modifier.fillMaxWidth())
    }
    if (!sessionValid) {
        AIPageScaffold("重新核验账号", onBack) { StatusText("账号或服务器已变化，原编辑内容保留在原身份分区，请返回重新登录。", true) }
        return
    }
    AIPageScaffold("核对 AI 条目", ::back) {
        Text("目标账本：$ledgerName", style = MaterialTheme.typography.titleMedium)
        if (pendingRoute && !locationCurrent) {
            if (!initializationFailed && (!ready || guardStatus == LedgerPendingPageStatus.CHECKING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在核验原条目、本机草稿与原请求…", color = Muted)
            } else StatusText("原条目、草稿或请求已变化，或当前会话无法核验。原内容保留，不会打开替代表单。", true)
            message?.let { StatusText(it, true) }
            TextButton(onClick = { initializationVersion++ }, enabled = !busy && guardStatus != LedgerPendingPageStatus.CHECKING,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("重新核验原目标") }
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回原入口") }
            return@AIPageScaffold
        }
        Text(item.sourceText)
        Text("AI 字段是建议。账户、分类未匹配时保持待选择；确认前不改变余额。", color = Muted)
        message?.let { StatusText(it, true) }
        if (busy || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!ready || writes == null) Text("正在读取本机草稿和原请求…", color = Muted)
        if (restoring) ReviewPanel {
            Text("发现此条目的本机编辑草稿")
            Button(onClick = { scope.launch {
                busy = true
                try {
                    verifyOriginal()
                    val expected = requireNotNull(saved)
                    val actual = repository.aiReview.draft(item.ledgerId, item.id)
                    current()
                    val originalForm = if (pendingRoute) restoreExpectedAIReviewEditor(expected, actual) else AIReviewEditorForm.read(expected)
                    check(originalForm.fields.keys.containsAll(AIReviewEditorForm.initial(item).fields.keys))
                    form = originalForm; restoring = false
                    if (pendingRoute) check(requireNotNull(pendingGuard).release())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "原草稿不完整或已变化，请保留原内容并返回核验。" }
                finally { busy = false }
            }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续编辑原始输入") }
        }
        write?.let { original -> ReviewPanel {
            Text(if (original.state == AIReviewRequestEntity.POSTED) "已确认，等待读取原条目" else "原确认入账结果待核对")
            Text("当前编辑不能替代原请求。", color = Muted)
            Button(onClick = { scope.launch {
                busy = true
                try {
                    verifyOriginal()
                    val fresh = repository.aiReview.requests().singleOrNull { it.ledgerId == item.ledgerId && it.itemId == item.id }
                    check(samePendingReviewRequest(original, fresh))
                    val exact = requireNotNull(fresh)
                    val role = api.periodicRole(item.ledgerId)
                    check(role in 1..4 && (exact.state == AIReviewRequestEntity.POSTED || role in 1..3))
                    current(); deliverAIOriginal(context, store, repository, api, exact); current(); onChanged()
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "原确认结果仍待核对，请重试；不会生成替代入账。" }
                finally { busy = false }
            } }, enabled = !busy && (original.state == AIReviewRequestEntity.POSTED || resources.canReview), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (original.state == AIReviewRequestEntity.POSTED) "重新读取原条目" else "重试原确认请求") }
        } }
        if (ready && !restoring && write == null) {
            if (!editable) Text("此条目已确认、已忽略、已删除或当前没有编辑权限；本机草稿仍保留。", color = Muted)
            AIFormChoice("流水类型", form.fields.getValue("type"), listOf("3" to "支出 / 退款", "2" to "收入", "4" to "转账"), editable) { edit("type", it) }
            val type = form.fields.getValue("type")
            val direction = form.fields.getValue("direction")
            if (type == "4") AIFormChoice("转账方向", direction, listOf("internal" to "账户间转账", "out" to "转出到系统外", "in" to "从系统外转入"), editable) { edit("direction", it) }
            val accounts = resources.accounts.filter { it.id > 0 && it.type == 1 && !it.hidden && (it.parentId == 0L || resources.accounts.any { parent -> parent.id == it.parentId && !parent.hidden }) }
            val choices = accounts.map { it.id.toString() to "${it.name} · ${it.currency} · ${it.id}" }
            if (type != "4" || direction != "in") {
                AIFormChoice(if (type == "2") "目标账户" else "来源账户", form.fields.getValue("sourceAccountId"), choices, editable) { edit("sourceAccountId", it) }
                field(if (type == "3") "金额（退款保留负号）" else "金额", "sourceAmount")()
            }
            if (type == "4" && direction != "out") {
                AIFormChoice("转入账户", form.fields.getValue("destinationAccountId"), choices, editable) { edit("destinationAccountId", it) }
                val source = accounts.singleOrNull { it.id.toString() == form.fields["sourceAccountId"] }
                val destination = accounts.singleOrNull { it.id.toString() == form.fields["destinationAccountId"] }
                if (direction == "internal" && source != null && destination != null && source.currency == destination.currency)
                    Text("同币种转入金额与转出金额一致。", color = Muted)
                else field("转入金额", "destinationAmount")()
            }
            val categoryType = when (type) { "2" -> 1; "4" -> 3; else -> 2 }
            val categories = resources.categories.filter { it.isAISelectableLeaf(resources.categories, categoryType) }
            AIFormChoice("分类", form.fields.getValue("categoryId"), categories.map {
                it.id.toString() to "${resources.categories.singleOrNull { parent -> parent.id == it.parentId }?.name.orEmpty()} / ${it.name} · ${it.id}"
            }, editable) { edit("categoryId", it) }
            if (choices.isEmpty() || categories.isEmpty()) StatusText("缺少可用账户或末级分类，请返回刷新字典后继续。", true)
            field("日期与时间（如 2026-10-19T12:00:00）", "dateTime")()
            field("时区（如 Asia/Shanghai）", "timeZone")()
            field("UTC 偏移分钟（上海为 480）", "utcOffset")()
            field("备注", "comment", true)()
            Row { Checkbox(form.fields.getValue("hideAmount") == "true", { edit("hideAmount", it.toString()) }, enabled = editable); Text("隐藏此笔金额", Modifier.padding(top = 12.dp)) }
            Text("识别返回的标签关联随原草稿保留；不会按同名标签静默替换。", color = Muted)
            Text(if (saving) "正在保存原始输入…" else if (saved?.formJson == form.json()) "原始输入已保存到本机" else "预览前将保存原始输入", color = Muted)
            Button(onClick = { scope.launch {
                busy = true; message = null
                try { verifyOriginal(); val captured = snapshot(); val payload = form.payload(item.ledgerId, item.id, resources.accounts, resources.categories); preview = captured to payload }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "请核对账户、分类、未完成金额、备注和日期时区后重试；输入已保留。" }
                finally { busy = false }
            } }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("预览确认入账") }
        }
        if (saved != null && write == null && ready) TextButton(onClick = { discard = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃此条目本机编辑", color = MaterialTheme.colorScheme.error) }
    }
    if (locationCurrent && (context as? PrivacyActivity)?.isPrivacyLocked != true) {
        preview?.let { (captured, payload) ->
            val tx = org.json.JSONObject(payload).getJSONObject("transaction")
            val source = resources.accounts.singleOrNull { it.id.toString() == tx.getString("sourceAccountId") }
            val destination = resources.accounts.singleOrNull { it.id.toString() == tx.getString("destinationAccountId") }
            AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("确认入账到 $ledgerName") },
                text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确认后会创建一笔流水并同时确认原队列条目。")
                    source?.let { Text("${it.name} · ${currencyMoney(tx.getLong("sourceAmount"), it.currency)}") }
                    destination?.let { Text("转入 ${it.name} · ${currencyMoney(tx.getLong("destinationAmount"), it.currency)}") }
                    Text("${form.fields["dateTime"]} · ${form.fields["timeZone"]}")
                    Text(tx.getString("comment"))
                } },
                confirmButton = { TextButton(onClick = { scope.launch {
                    busy = true; message = null
                    try {
                        verifyOriginal(); current(); check(saved == captured && form.json() == captured.formJson)
                        val latest = loadAILedgerResources(api, item.ledgerId); current()
                        check(latest.canReview && aiDictionaryStamp(latest) == aiDictionaryStamp(resources))
                        val remote = api.getLedgerAIReviewItem(item.ledgerId, item.id); current()
                        check(remote.ledgerId == item.ledgerId && remote.id == item.id && remote.editable && remote.status == 1 && !remote.deleted)
                        val original = repository.aiReview.enqueue(captured, "confirm", payload)
                        expectedWrite = original
                        preview = null
                        deliverAIOriginal(context, store, repository, api, original)
                        current(); onChanged()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        sessionValid = server == store.get(FinexyApi.KEY_SERVER_URL) && token == store.get(FinexyApi.KEY_TOKEN) &&
                            (!pendingRoute || pendingGuard?.isCurrent() == true)
                        preview = null; permissionVerified = false; message = "确认未完成：权限、字典或原条目可能已变化。请返回队列刷新后继续；已提交的原请求会保留。"
                    }
                    finally { busy = false }
                } }, enabled = !busy) { Text("确认入账") } },
                dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("继续编辑") } })
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("丢弃本机编辑？") }, text = { Text("仅删除此条目的本机输入，服务器来源与已有流水保留。") },
            confirmButton = { TextButton(onClick = { discard = false; scope.launch {
                try {
                    verifyOriginal(); current(); val original = saved
                    if (original != null) { check(repository.aiReview.draft(item.ledgerId, item.id) == original); current(); check(repository.aiReview.discardDraft(original)) }
                    onChanged()
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "草稿不能丢弃，请先核对原请求或重新读取。" }
            } }) { Text("确认丢弃", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("保留编辑") } })
    }
}

@Composable
internal fun AIFormChoice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = { open = true }, enabled = enabled && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(choices.singleOrNull { it.first == selected }?.second ?: "待选择 · $label")
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("选择$label") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { (id, name) -> TextButton(onClick = { onSelect(id); open = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(name) } }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } })
}
