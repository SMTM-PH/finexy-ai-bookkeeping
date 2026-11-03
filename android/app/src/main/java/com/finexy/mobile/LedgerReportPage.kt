package com.finexy.mobile

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger

/** Memory-only editable choices and one-shot file work across Activity recreation. */
internal class LedgerReportFileViewModel : ViewModel() {
    val form = mutableStateOf(LedgerReportForm.currentMonth())
    val amountsVisible = mutableStateOf(false)
    val pendingTag = mutableStateOf(LedgerReportTagChoice("any"))
    var draftScope: LedgerReportDraftScope? by mutableStateOf(null)
        private set
    var pending: Pair<Long, LedgerReportDelivery>? = null
    var saved: Pair<Long, Uri>? by mutableStateOf(null)
    var working by mutableStateOf(false)
    var message: String? by mutableStateOf(null)
    var failed by mutableStateOf(false)
    fun bindDrafts(scope: LedgerReportDraftScope, showAmountsInitially: Boolean) {
        if (draftScope == scope) return
        val previous = draftScope?.let { LedgerReportDraftChoices(it, form.value, amountsVisible.value, pendingTag.value) }
        val next = rebindLedgerReportDraft(previous, scope, showAmountsInitially)
        form.value = next.form; amountsVisible.value = next.amountsVisible; pendingTag.value = next.pendingTag
        clear(); draftScope = next.scope
    }
    fun clear() { pending = null; saved = null; message = null; failed = false }
    fun write(action: suspend () -> Unit) { viewModelScope.launch { action() } }
    fun beginWrite(): Boolean {
        if (working) return false
        working = true; return true
    }
}

private data class ReportOptions(val accounts: List<LedgerReportResource>, val categoryMetadata: List<LedgerReportResource>, val readableTags: List<LedgerReportResource>)
private fun LedgerReportPage.options() = ReportOptions(accounts.toList(), categoryMetadata.toList(), readableTags.toList())

@Composable
internal fun LedgerReportPage(store: SecureStore, ledgerId: Long, ledgerName: String,
    showAmountsByDefault: Boolean = true, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = context as? ComponentActivity
    val model: LedgerReportViewModel = viewModel(key = "ledger-report-${store.namespace}")
    val files: LedgerReportFileViewModel = viewModel(key = "ledger-report-files-${store.namespace}")
    val observedState by model.state.collectAsState()
    val epoch by model.contextEpoch.collectAsState()
    val draftScope by model.draftScope.collectAsState()
    fun draftsAreCurrent(): Boolean {
        val latest = model.draftScope.value
        return files.draftScope == latest && latest.owner?.let { it.namespace == store.namespace && it.ledgerId == ledgerId } == true
    }
    val draftsCurrent = files.draftScope == draftScope && draftScope.owner?.let {
        it.namespace == store.namespace && it.ledgerId == ledgerId
    } == true
    val state = observedState.takeIf { draftsCurrent && it.ledgerId == ledgerId }
        ?: LedgerReportState(ledgerId = ledgerId)
    var observedEpoch by remember(model) { mutableStateOf(epoch) }
    var form by files.form
    var options by remember { mutableStateOf<ReportOptions?>(state.report?.options()) }
    var filtersOpen by remember { mutableStateOf(true) }
    var storedAmountsVisible by files.amountsVisible
    val amountsVisible = draftsCurrent && storedAmountsVisible
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<LedgerReportDetail?>(null) }
    var comparison by remember { mutableStateOf<LedgerReportPage?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var stack by remember { mutableStateOf<List<LedgerReportQuery>>(emptyList()) }
    var hiddenAck by remember(state.confirmation) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun leave() { if (!files.working) { model.leave(); files.bindDrafts(model.draftScope.value, false); onBack() } }
    BackHandler(onBack = ::leave)
    DisposableEffect(model, store.namespace, ledgerId) {
        model.enter(appContext, store, ledgerId)
        files.bindDrafts(model.draftScope.value, showAmountsByDefault)
        onDispose { if (activity?.isChangingConfigurations != true) { model.leave(); files.bindDrafts(model.draftScope.value, false) } }
    }
    LaunchedEffect(draftScope) { files.bindDrafts(model.draftScope.value, showAmountsByDefault) }
    LaunchedEffect(epoch) {
        if (observedEpoch != epoch) {
            observedEpoch = epoch; options = null
            detail = null; comparison = null; stack = emptyList(); files.clear(); error = null
        }
    }
    LaunchedEffect(state.report) { state.report?.let { options = it.options() } }
    fun change(next: LedgerReportForm) {
        if (!draftsAreCurrent()) return
        model.draftChanged()
        if (!draftsAreCurrent()) return
        form = next; files.clear(); detail = null; comparison = null; stack = emptyList(); error = null
    }
    fun load(query: LedgerReportQuery, rememberPrevious: Boolean = false) {
        if (files.working || !draftsAreCurrent()) return
        if (rememberPrevious) state.report?.query?.let { stack = stack + it }
        form = LedgerReportForm.from(query); files.clear(); detail = null; comparison = null; error = null
        scope.launch { model.load(query) }
    }
    fun apply() {
        if (!draftsAreCurrent()) return
        val query = runCatching { form.query(ledgerId) }.getOrNull()
        if (query == null) { error = "日期、时区或筛选不完整，请核对后重新读取。"; return }
        stack = emptyList(); load(query)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val pending = files.pending; files.pending = null
        if (uri == null) { files.failed = false; files.message = "已取消保存，尚未写入文件。"; model.cancelExport() }
        else if (pending == null || !model.isCurrent(pending.first)) {
            files.failed = true; files.message = "报告已变化，未写入所选文件；请重新预览导出。"
        } else {
            val download = pending.second.takeIfCurrent()
            if (download == null) { files.failed = true; files.message = "导出确认已失效，未写入文件；请重新预览。" }
            else if (files.beginWrite()) {
                files.saved = null; files.message = null
                files.write {
                    try {
                        withContext(Dispatchers.IO) {
                            LedgerReportDocumentWriter.writeAndVerify(download.bytes(), { model.isCurrent(pending.first) },
                                { appContext.contentResolver.openOutputStream(uri, "wt") },
                                { appContext.contentResolver.openInputStream(uri) })
                        }
                        if (model.isCurrent(pending.first)) {
                            files.saved = pending.first to uri; files.failed = false
                            files.message = "CSV 已写入并重新读取核验。可打开文件或交给系统分享。"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        files.failed = true
                        files.message = "上次文件保存或重新核验未完成，请检查所选文件；它可能包含部分内容。"
                    } finally { files.working = false }
                }
            }
        }
    }
    fun savedIntent(share: Boolean) {
        val saved = files.saved
        if (saved == null || !model.isCurrent(saved.first)) { files.clear(); return }
        runCatching {
            val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, saved.second)
                clipData = ClipData.newRawUri("报告 CSV", saved.second)
            } else Intent(Intent.ACTION_VIEW).setDataAndType(saved.second, "text/csv")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(if (share) Intent.createChooser(intent, "分享报告 CSV") else intent)
            files.failed = false; files.message = if (share) "已交给系统选择分享方式，请在目标应用核对。" else "已请求系统打开，请在文件应用核对。"
        }.onFailure { files.failed = true; files.message = "系统未能打开此文件，请从文件应用检查。" }
    }

    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    TextButton(onClick = ::leave, enabled = !files.working, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (files.working) "正在保存…" else "返回")
                    }
                    Text("当前账本报告", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("$ledgerName · 账本 #$ledgerId", color = Muted)
                    Text("仅使用完整在线结果；本机未上传内容不进入报告。不同币种分别统计。", color = Muted)
                }
                item {
                    PanelCard {
                        TextButton(onClick = { filtersOpen = !filtersOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (filtersOpen) "收起筛选" else "展开日期、类型、账户、分类和标签")
                        }
                        if (filtersOpen && draftsCurrent) key(draftScope.revision) {
                            ReportFilters(form, options.takeIf { observedEpoch == epoch }, state.busy || files.working,
                                files.pendingTag.value, { if (draftsAreCurrent()) files.pendingTag.value = it }, ::change)
                        }
                        if (!draftsCurrent) Text("请核对服务器、登录和当前账本后重新进入报告。", color = Muted)
                        Button(onClick = ::apply, enabled = draftsCurrent && !state.busy && !files.working,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("读取报告") }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(reportSourceLabel(state), color = Muted)
                        error?.let { SecurityFeedback(it, true) }
                        state.failure?.let { SecurityFeedback(it.message, true) }
                        state.notice?.let { SecurityFeedback(it) }
                        files.message?.takeIf { observedEpoch == epoch && state.ledgerId == ledgerId }?.let { SecurityFeedback(it, files.failed) }
                        if (files.working) Text("正在写入并核验所选文件，请稍候…", color = Muted,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        files.saved?.takeIf { model.isCurrent(it.first) }?.let {
                            TextButton(onClick = { savedIntent(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开已保存 CSV") }
                            TextButton(onClick = { savedIntent(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("分享已保存 CSV") }
                        }
                    }
                }
                val page = state.report
                if (page != null && state.completeOnlineSource) {
                    item {
                        PanelCard {
                            Text("${page.query.startDate} 至 ${page.query.endDate} · ${page.query.timezone}")
                            Text("完整在线来源 · ${page.matchedCount} 笔 · 已读取明细 ${state.details.size} 笔", color = Muted)
                            Text(if (page.query.includesHiddenAmounts) "包含隐藏记录" else "不含隐藏记录", color = Muted)
                            TextButton(onClick = {
                                if (draftsAreCurrent()) { storedAmountsVisible = !storedAmountsVisible; if (!storedAmountsVisible) model.cancelExport() }
                            }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (amountsVisible) "隐藏报告金额" else "显示报告金额")
                            }
                            if (page.matchedCount == 0) Text("该范围没有流水，可调整筛选后重新读取。")
                            page.totals.forEach { ReportMoney(it, amountsVisible) }
                            if (stack.isNotEmpty()) TextButton(onClick = {
                                val previous = stack.last(); stack = stack.dropLast(1); load(previous)
                            }, enabled = !files.working, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回上一层报告") }
                            if (runCatching { previousFullReportMonth(page.query) }.isSuccess) TextButton(onClick = {
                                val query = previousFullReportMonth(page.query); val ticket = model.captureTicket()
                                comparing = true; comparison = null
                                scope.launch {
                                    try { val result = model.compare(query); if (ticket != null && model.isCurrent(ticket)) comparison = result }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { if (ticket != null && model.isCurrent(ticket)) error = "无法取得相同筛选的上月报告，请重试。" }
                                    finally { comparing = false }
                                }
                            }, enabled = !comparing && !files.working, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (comparing) "正在读取上月…" else "比较上一个自然月") }
                            comparison?.let { previous ->
                                Text("上月：${previous.query.startDate} 至 ${previous.query.endDate} · 相同非日期筛选", color = Muted)
                                if (previous.matchedCount == 0) Text("上月该范围没有流水。")
                                previous.totals.forEach { ReportMoney(it, amountsVisible) }
                            }
                            OutlinedButton(onClick = { scope.launch { model.prepareExport() } },
                                enabled = page.exportAllowed && amountsVisible && !files.working,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("预览 CSV 导出") }
                            if (!page.exportAllowed) Text("服务器未开放此账本报告导出。", color = Muted)
                        }
                    }
                    for ((kind, title, groups) in listOf(Triple("day", "每日趋势", page.trend), Triple("category", "分类排行", page.categories), Triple("tag", "标签分组", page.tagGroups))) {
                        item { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }); if (kind == "tag") Text("同一流水可有多个标签，分组不能相加。", color = Muted) }
                        items(groups, key = { "$kind:${it.key}" }) { group ->
                            ReportGroup(group, kind, page, amountsVisible, !files.working) { currency ->
                                runCatching { narrowLedgerReport(page.query, kind, group.key, currency) }
                                    .onSuccess { load(it, true) }.onFailure { error = "无法保留原筛选下钻，请重新读取。" }
                            }
                        }
                    }
                    item { Text("明细", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
                    items(state.details, key = { "detail:${it.id}" }) { row ->
                        PanelCard {
                            Text("${row.date} · ${reportTypeLabel(row.type)}")
                            Text(if (row.categoryId == 0L) "无分类" else "${row.categoryName} · #${row.categoryId}", color = Muted)
                            ReportLegs(row, amountsVisible)
                            TextButton(onClick = { detail = row }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看流水详情 #${row.id}") }
                        }
                    }
                    item {
                        if (state.nextOffset != null) Button(onClick = { files.clear(); comparison = null; scope.launch { model.loadNext() } },
                            enabled = !files.working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多明细") }
                        else Text("该范围明细已读取完毕。", color = Muted)
                    }
                }
            }
        }
    }
    detail?.takeIf { state.completeOnlineSource && it in state.details }?.let { row -> AlertDialog(onDismissRequest = { detail = null }, title = { Text("流水详情 #${row.id}") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${row.date} · ${reportTypeLabel(row.type)} · ${form.timezone}"); ReportLegs(row, amountsVisible)
            Text("分类：${if (row.categoryId == 0L) "无分类" else "${row.categoryName} #${row.categoryId}"}")
            Text("标签：${row.tags.joinToString { "${it.name} #${it.id}" }.ifEmpty { "无标签" }}")
            if (amountsVisible) Text("备注：${row.comment.ifEmpty { "无" }}")
        }
    }, confirmButton = { TextButton(onClick = { detail = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } }) }
    state.confirmation?.takeIf { amountsVisible }?.let { shown ->
        AlertDialog(onDismissRequest = { model.cancelExport() }, title = { Text("确认导出 CSV") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$ledgerName · ${shown.query.startDate} 至 ${shown.query.endDate} · ${shown.query.timezone}")
                Text("${shown.matchedCount} 笔，以下为已应用的筛选。")
                ReportQuerySummary(shown.query, options)
                shown.totals.forEach { ReportMoney(it, true) }
                if (shown.includesHiddenAmounts) ReportCheck("另行确认导出隐藏记录的实际金额", hiddenAck, { hiddenAck = it })
                Text("确认后选择系统文件位置；写入并重新核验后才会提示保存成功。", color = Muted)
            }
        }, confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val delivery = model.download(shown, hiddenAck) ?: return@launch
                    val ticket = model.captureTicket(delivery) ?: return@launch
                    files.pending = ticket to delivery
                    runCatching { createFile.launch(delivery.filename) }.onFailure {
                        files.pending = null; files.failed = true; files.message = "系统文件选择器不可用，请重新预览后重试。"
                    }
                }
            }, enabled = !shown.includesHiddenAmounts || hiddenAck, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认并选择文件位置") }
        }, dismissButton = { TextButton(onClick = { model.cancelExport() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
}

@Composable
private fun ReportFilters(form: LedgerReportForm, options: ReportOptions?, busy: Boolean,
    pendingTag: LedgerReportTagChoice, tagChoice: (LedgerReportTagChoice) -> Unit, change: (LedgerReportForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("日期", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(form.start, { change(form.copy(start = it)) }, label = { Text("开始日期 YYYY-MM-DD") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.end, { change(form.copy(end = it)) }, label = { Text("结束日期 YYYY-MM-DD") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.timezone, { change(form.copy(timezone = it)) }, label = { Text("报告时区，例如 Asia/Shanghai") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("类型", style = MaterialTheme.typography.titleMedium)
        for (type in listOf(3, 2, 4, 1)) ReportCheck(reportTypeLabel(type), type in form.types, { checked -> change(form.copy(types = if (checked) form.types + type else form.types - type)) }, !busy)
        ReportResources("账户", options?.accounts.orEmpty(), form.accountIds, form.excludeAccounts, form.accountChildren, busy,
            { change(form.copy(accountIds = it)) }, { change(form.copy(excludeAccounts = it)) }, { change(form.copy(accountChildren = it)) })
        ReportResources("分类", options?.categoryMetadata.orEmpty(), form.categoryIds, form.excludeCategories, form.categoryChildren, busy,
            { change(form.copy(categoryIds = it)) }, { change(form.copy(excludeCategories = it)) }, { change(form.copy(categoryChildren = it)) }, unclassified = true)
        Text("标签", style = MaterialTheme.typography.titleMedium)
        val tagMode = pendingTag.mode; val tagIds = pendingTag.ids
        val modes = listOf("any" to "含任一", "all" to "含全部", "notAny" to "排除任一", "notAll" to "排除全部", "none" to "无标签")
        modes.forEach { (mode, label) -> ReportCheck(label, tagMode == mode, { tagChoice(pendingTag.copy(mode = mode)) }, !busy) }
        if (tagMode != "none") options?.readableTags.orEmpty().forEach { tag ->
            ReportCheck("${tag.name}${if (tag.hidden) " · 已隐藏" else ""} · #${tag.id}", tag.id in tagIds,
                { tagChoice(pendingTag.copy(ids = if (it) tagIds + tag.id else tagIds - tag.id)) }, !busy)
        }
        if (tagMode == "none" && form.tags.isNotEmpty()) Text("无标签不能与其他标签条件并用；选择后将替换已有条件。", color = Muted)
        else if (form.tags.any { it.mode == "none" }) Text("添加其他标签条件将替换当前的无标签条件。", color = Muted)
        OutlinedButton(onClick = { change(form.copy(tags = if (tagMode == "none") listOf(LedgerReportTagChoice("none")) else form.tags.filterNot { it.mode == "none" } + LedgerReportTagChoice(tagMode, tagIds))) },
            enabled = !busy && (tagMode == "none" || tagIds.isNotEmpty()) && form.tags.size < 20, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (tagMode == "none") "使用无标签条件" else "添加标签条件（同时满足）") }
        form.tags.forEachIndexed { index, clause -> TextButton(onClick = { change(form.copy(tags = form.tags.filterIndexed { i, _ -> i != index })) },
            enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除：${modes.firstOrNull { it.first == clause.mode }?.second} ${clause.ids.joinToString { id -> options?.readableTags?.find { it.id == id }?.name ?: "#$id" }}") } }
        Text("币种 · 未选择时按全部原币种分别统计", style = MaterialTheme.typography.titleMedium)
        options?.accounts.orEmpty().mapNotNull { it.currency }.distinct().sorted().forEach { code ->
            ReportCheck(code, code in form.currencies, { change(form.copy(currencies = if (it) form.currencies + code else form.currencies - code)) }, !busy)
        }
        ReportCheck("包含隐藏记录", form.hidden, { change(form.copy(hidden = it)) }, !busy)
        if (options == null) Text("先读取一次报告以取得当前账本的账户、分类及可读标签选项。", color = Muted)
        Text("修改筛选后须重新读取；旧报告和导出确认立即失效。", color = Muted)
    }
}

@Composable
private fun ReportResources(title: String, resources: List<LedgerReportResource>, ids: Set<Long>, exclude: Boolean, children: Boolean,
    busy: Boolean, select: (Set<Long>) -> Unit, excludeChange: (Boolean) -> Unit, childrenChange: (Boolean) -> Unit, unclassified: Boolean = false) {
    var expanded by remember(title) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$title · ${if (ids.isEmpty()) "全部" else "已选 ${ids.size} 项"} · ${if (expanded) "收起" else "展开"}") }
    if (expanded) {
        ReportCheck("排除所选$title", exclude, excludeChange, !busy)
        ReportCheck("包含子级", children, childrenChange, !busy)
        fun choose(id: Long, value: Boolean) = select(if (value) ids + id else ids - id)
        if (unclassified) ReportCheck("无分类 · #0", 0L in ids, { choose(0L, it) }, !busy)
        resources.forEach { row ->
            val parent = resources.firstOrNull { it.id == row.parentId }?.name
            ReportCheck("${parent?.let { "$it / " }.orEmpty()}${row.name}${if (row.hidden) " · 已隐藏" else ""} · #${row.id}", row.id in ids, { choose(row.id, it) }, !busy)
        }
    }
}

@Composable
private fun ReportCheck(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled); Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun ReportQuerySummary(query: LedgerReportQuery, options: ReportOptions?) {
    val form = LedgerReportForm.from(query)
    fun selected(ids: Set<Long>, resources: List<LedgerReportResource>, exclude: Boolean, descendants: Boolean): String =
        if (ids.isEmpty()) "全部" else (if (exclude) "排除 " else "包含 ") + ids.sorted().joinToString("、") { id ->
            if (id == 0L) "无分类 #0" else "${resources.find { it.id == id }?.name.orEmpty()} #$id"
        } + if (descendants) "（含子级）" else ""
    Text("类型：${form.types.sorted().joinToString { reportTypeLabel(it) }}")
    Text("账户：${selected(form.accountIds, options?.accounts.orEmpty(), form.excludeAccounts, form.accountChildren)}")
    Text("分类：${selected(form.categoryIds, options?.categoryMetadata.orEmpty(), form.excludeCategories, form.categoryChildren)}")
    val modes = mapOf("any" to "含任一", "all" to "含全部", "notAny" to "排除任一", "notAll" to "排除全部", "none" to "无标签")
    Text("标签：${form.tags.joinToString("；且 ") { clause -> modes[clause.mode].orEmpty() + clause.ids.joinToString("、") { id -> "${options?.readableTags?.find { it.id == id }?.name.orEmpty()} #$id" } }.ifEmpty { "全部" }}")
    Text("币种：${form.currencies.sorted().joinToString().ifEmpty { "全部原币种，分别合计" }}")
    Text(if (form.hidden) "隐藏记录：包含实际金额" else "隐藏记录：不含")
}

private fun reportTypeLabel(type: Int) = when (type) { 1 -> "余额调整"; 2 -> "收入"; 3 -> "支出（退款冲减）"; 4 -> "转账"; else -> "未知类型" }
private fun reportSourceLabel(state: LedgerReportState) = when (state.source) {
    LedgerReportSource.ONLINE_COMPLETE -> "完整在线报告"
    LedgerReportSource.OFFLINE -> "当前离线，未以本机部分流水计算报告。"
    LedgerReportSource.LOCAL_ONLY -> "当前为本机模式，未取得完整在线报告。"
    LedgerReportSource.SYNCHRONIZING -> "正在同步，结束后请重新读取完整在线报告。"
    LedgerReportSource.NO_SESSION -> "请核对服务器、登录和当前账本后重新进入报告。"
    LedgerReportSource.CLOSED -> "报告已关闭。"
    LedgerReportSource.NOT_LOADED -> if (state.busy) "正在读取并核验完整在线报告…" else "尚未读取，请确认筛选后读取报告。"
}

@Composable
private fun ReportMoney(money: LedgerReportMoney, visible: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(money.currency, style = MaterialTheme.typography.titleMedium)
        for ((label, value) in listOf("收入" to money.income, "净支出" to money.expense, "结余" to money.net,
            "调整差额" to money.adjustment, "转入" to money.transferIn, "转出" to money.transferOut)) {
            Text("$label：${if (visible) LedgerReportContract.formatAmount(value) else "••••••"}")
        }
    }
}

@Composable
private fun ReportGroup(group: LedgerReportGroup, kind: String, page: LedgerReportPage, visible: Boolean, enabled: Boolean, drill: (String) -> Unit) {
    PanelCard {
        val resource = if (kind == "category") page.categoryMetadata.firstOrNull { it.id.toString() == group.key } else null
        val parent = page.categoryMetadata.firstOrNull { it.id == resource?.parentId }?.name
        Text(when (kind) { "day" -> group.key; "category" -> if (group.key == "0") "无分类 · #0" else "${parent?.let { "$it / " }.orEmpty()}${group.name.orEmpty()} · #${group.key}"; else -> if (group.key == "none") "无标签" else "${group.name.orEmpty()} · #${group.key}" })
        Text("${group.count} 笔", color = Muted)
        group.money.forEach { money ->
            Text("${money.currency} · 净支出 ${if (visible) LedgerReportContract.formatAmount(money.expense) else "••••••"} · 收入 ${if (visible) LedgerReportContract.formatAmount(money.income) else "••••••"}")
            if (visible) {
                val largest = (if (kind == "day") page.trend else if (kind == "category") page.categories else page.tagGroups)
                    .flatMap { it.money }.filter { it.currency == money.currency }.maxOfOrNull { BigInteger.valueOf(it.expense).abs() } ?: BigInteger.ZERO
                if (largest.signum() > 0) {
                    val progress = BigInteger.valueOf(money.expense).abs().multiply(BigInteger.valueOf(10000)).divide(largest).toFloat() / 10000f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            TextButton(onClick = { drill(money.currency) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看此组 ${money.currency} 明细") }
        }
    }
}

@Composable
private fun ReportLegs(row: LedgerReportDetail, visible: Boolean) {
    row.source?.let { Text("${if (row.type == 2) "目标" else "来源"}：${it.accountName} · ${it.currency} ${if (visible) LedgerReportContract.formatAmount(it.amount) else "••••••"}") }
    row.destination?.let { Text("目标：${it.accountName} · ${it.currency} ${if (visible) LedgerReportContract.formatAmount(it.amount) else "••••••"}") }
    if (row.type == 1) {
        Text("实际调整差额：${if (visible) LedgerReportContract.formatAmount(row.adjustment) else "••••••"}")
        Text("目标余额：${if (visible) row.balanceTarget?.let(LedgerReportContract::formatAmount).orEmpty() else "••••••"}")
    }
}
