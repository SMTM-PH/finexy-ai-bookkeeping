package com.finexy.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*

private fun LedgerPendingSection.title() = when (this) {
    LedgerPendingSection.AI -> "AI 待复核"
    LedgerPendingSection.PERIODIC -> "周期待确认"
    LedgerPendingSection.LOCAL_RECOVERY -> "本机恢复"
}
private fun LedgerPendingSection.icon() = when (this) {
    LedgerPendingSection.AI -> "list"
    LedgerPendingSection.PERIODIC -> "schedule"
    LedgerPendingSection.LOCAL_RECOVERY -> "connect"
}
private fun sectionSources(section: LedgerPendingSection) = LedgerPendingSource.entries.filter {
    it.section == section || section == LedgerPendingSection.AI && it == LedgerPendingSource.LOCAL_TRANSACTIONS
}
private fun sectionFailure(summary: LedgerPendingSummary, section: LedgerPendingSection): String? {
    val states = sectionSources(section).map { summary.sources[it]?.availability }
    return when {
        LedgerPendingAvailability.DENIED in states -> "部分来源无访问权限"
        LedgerPendingAvailability.FAILED in states -> "部分来源读取失败"
        LedgerPendingAvailability.UNAVAILABLE in states -> "部分来源暂不可核验"
        else -> null
    }
}
private fun LedgerPendingSource.title() = when (this) {
    LedgerPendingSource.AI_QUEUE -> "在线 AI 队列"
    LedgerPendingSource.OCCURRENCES -> "在线周期待确认"
    LedgerPendingSource.AI_REQUESTS -> "本机 AI 原请求与回执"
    LedgerPendingSource.AI_DRAFTS -> "本机 AI 原稿"
    LedgerPendingSource.LEDGER_WRITES -> "本机账本写入请求"
    LedgerPendingSource.LOCAL_TRANSACTIONS -> "本机流水与旧 AI 关联"
    LedgerPendingSource.TEMPLATE_REQUESTS -> "模板创建原请求与回执"
    LedgerPendingSource.TEMPLATE_DRAFTS -> "模板原表单"
    LedgerPendingSource.PERIODIC_ACTIONS -> "本机周期原操作"
    LedgerPendingSource.CAPTURE -> "拍照暂存恢复记录"
    LedgerPendingSource.LEGACY_AI_CACHE -> "本机旧 AI 待复核"
}
private fun LedgerPendingAvailability.label() = when (this) {
    LedgerPendingAvailability.NOT_LOADED -> "尚未读取"
    LedgerPendingAvailability.LOADING -> "正在读取"
    LedgerPendingAvailability.COMPLETE -> "已完整读取"
    LedgerPendingAvailability.FAILED -> "读取失败，请重新读取"
    LedgerPendingAvailability.DENIED -> "无访问权限，请核对登录与成员权限"
    LedgerPendingAvailability.UNAVAILABLE -> "暂不可核验，请核对服务器支持与本机状态"
}
private fun LedgerPendingKind.label() = when (this) {
    LedgerPendingKind.REMOTE_PENDING -> "等待确认"
    LedgerPendingKind.DRAFT -> "原稿未确认"
    LedgerPendingKind.LOCAL_SYNC -> "本机同步待处理"
    LedgerPendingKind.CONFLICT -> "存在同步冲突"
    LedgerPendingKind.LEGACY_AI -> "旧 AI 关联待核对"
    LedgerPendingKind.CAPTURE -> "拍照恢复待核对"
    LedgerPendingKind.RESULT_UNKNOWN -> "原操作结果待核验"
    LedgerPendingKind.RECEIPT -> "已有回执，原结果待读取"
}
internal fun pendingTargetTitle(target: LedgerPendingTarget): String = when (target) {
    is LedgerPendingTarget.AIItem -> "AI 原待复核条目"
    is LedgerPendingTarget.LegacyAIItem -> "旧 AI 原待复核条目"
    is LedgerPendingTarget.AIRequest -> if (target.itemId == 0L) "AI 来源原请求" else "AI 原子确认原请求"
    is LedgerPendingTarget.AIDraft -> if (target.itemId == 0L) "AI 原始来源" else "AI 原复核草稿"
    is LedgerPendingTarget.Capture -> "原拍照恢复记录"
    is LedgerPendingTarget.Occurrence -> if (target.action == 0) "原周期到期记录" else "周期原操作"
    is LedgerPendingTarget.Template -> if (target.requestId == null) "模板原表单" else "模板创建原请求"
    is LedgerPendingTarget.Transaction -> if (target.relatedAIItemId != null) "旧 AI 原关联流水" else if (target.ledgerId == 0L) "本机原流水" else "账本原写入请求"
}
private fun targetLocator(target: LedgerPendingTarget): List<Pair<String, String>> = when (target) {
    is LedgerPendingTarget.AIItem -> listOf("原条目" to target.itemId.toString())
    is LedgerPendingTarget.LegacyAIItem -> listOf("原条目" to target.itemId.toString(), "原版本" to target.revision.toString())
    is LedgerPendingTarget.AIRequest -> listOf("原请求" to target.requestId, "原草稿" to target.draftId, "原版本" to target.revision.toString()) +
        if (target.itemId > 0) listOf("原条目" to target.itemId.toString()) else emptyList()
    is LedgerPendingTarget.AIDraft -> listOf("原草稿" to target.draftId, "原版本" to target.revision.toString()) +
        if (target.itemId > 0) listOf("原条目" to target.itemId.toString()) else emptyList()
    is LedgerPendingTarget.Capture -> listOf("原拍照操作" to target.operationId, "原草稿" to target.draftId, "原版本" to target.revision.toString())
    is LedgerPendingTarget.Occurrence -> listOf("原模板" to target.templateId.toString(), "原到期标识" to target.scheduledUnixTime.toString()) +
        if (target.action != 0) listOf("原操作" to when (target.action) { 1 -> "确认"; 2 -> "忽略"; else -> "恢复" }, "排队标识" to target.queuedAt.toString()) else emptyList()
    is LedgerPendingTarget.Template -> listOf("模板类型" to if (target.templateType == 1) "普通模板" else "周期模板", "原草稿" to target.draftId,
        "原版本" to target.revision.toString()) + listOfNotNull(target.requestId?.let { "原请求" to it })
    is LedgerPendingTarget.Transaction -> listOf("本机原标识" to target.localId, "原版本" to target.revision.toString()) +
        listOfNotNull(target.relatedAIItemId?.let { "旧 AI 关联" to it.toString() })
}

private fun pendingCount(summary: LedgerPendingSummary): String = when {
    summary.context == null -> "账本与会话待核验"
    summary.exactTotal == 0 -> "当前账本暂无待处理"
    summary.exactTotal != null -> "待处理 ${summary.exactTotal} 项"
    summary.busy -> "正在读取 · 已读 ${summary.knownCount} 个定位组"
    else -> "已读取 ${summary.knownCount} 个定位组 · 范围待核验"
}

@Composable
private fun PendingNotice(text: String, failure: Boolean = false) {
    Text(text, color = if (failure) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; if (failure) error(text) })
}

@Composable
internal fun LedgerBootstrapNotice(state: LedgerBootstrapState, onRetry: () -> Unit) {
    if (state.status == LedgerBootstrapStatus.READY || state.status == LedgerBootstrapStatus.NOT_STARTED) return
    PanelCard {
        Text("本机账本初始化", style = MaterialTheme.typography.titleMedium)
        if (state.status == LedgerBootstrapStatus.RUNNING) {
            PendingNotice("正在准备本机账本与启动同步…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            PendingNotice(requireNotNull(state.failure), true)
            TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重试初始化与启动同步") }
        }
    }
}

@Composable
private fun PendingAccess(summary: LedgerPendingSummary) {
    when (summary.permission) {
        LedgerPendingAvailability.COMPLETE -> Text(when {
            summary.context?.localMode == true -> "本机账本归属已核验，在线来源仍待核验。"
            summary.role == RemoteLedgerMember.ROLE_VIEWER -> "当前成员只读，可查看原操作。"
            else -> "当前账本访问已核验。"
        }, color = Muted, style = MaterialTheme.typography.bodySmall)
        LedgerPendingAvailability.DENIED -> PendingNotice("登录或账本访问已失效，请核对登录与成员权限。本机原操作仍保留。", true)
        LedgerPendingAvailability.FAILED -> PendingNotice("账本权限读取失败，请重新核验。", true)
        LedgerPendingAvailability.UNAVAILABLE -> PendingNotice(if (summary.context?.localMode == true) "本机模式：在线队列与访问权限尚未核验。" else "当前账本访问暂不可核验。")
        LedgerPendingAvailability.LOADING -> PendingNotice("正在核验当前账本访问权限…")
        LedgerPendingAvailability.NOT_LOADED -> PendingNotice("当前账本访问权限尚未核验。")
    }
    if (!summary.identityComplete) PendingNotice("部分创建结果未知，尚不能确认与在线条目的对应关系。")
    summary.navigationFailure?.let { PendingNotice("原定位未能打开：${it.label()}。请重新读取待处理后核对。", true) }
}

@Composable
internal fun LedgerPendingHomePanel(summary: LedgerPendingSummary, opening: Boolean,
    onNotifications: (LedgerPendingSection?) -> Unit, onRefresh: () -> Unit, preparing: Boolean = false) {
    PanelCard {
        Text("当前账本待处理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(if (preparing) "正在读取本机来源…" else pendingCount(summary), modifier = Modifier.testTag("pending-home-count").semantics { liveRegion = LiveRegionMode.Polite })
        if (summary.busy || preparing) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(8.dp)); PendingAccess(summary)
        LedgerPendingSection.entries.forEach { section ->
            TextButton(onClick = { onNotifications(section) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Mark(section.icon(), Muted, 20); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(section.title(), color = Ink)
                    val count = summary.section(section).size
                    Text(if (summary.exactTotal != null) "$count 项" else "已读 $count 个定位组 · 待核验", color = Muted, style = MaterialTheme.typography.bodySmall)
                    sectionFailure(summary, section)?.let { message -> PendingNotice(message, sectionSources(section).any {
                        summary.sources[it]?.availability in setOf(LedgerPendingAvailability.DENIED, LedgerPendingAvailability.FAILED)
                    }) }
                }
                Mark("chevron", Muted, 16)
            }
        }
        TextButton(onClick = onRefresh, enabled = !summary.busy && !opening && !preparing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (summary.busy || preparing) "正在读取…" else "重新读取待处理")
        }
    }
}

@Composable
private fun PendingPageTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Mark("back", Muted, 18); Text("返回") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
    }
}

@Composable
internal fun LedgerPendingNotificationsPage(summary: LedgerPendingSummary, ledgerName: String, opening: Boolean,
    initialSection: LedgerPendingSection?, onRefresh: () -> Unit, onOpen: (LedgerPendingTarget) -> Unit, onBack: () -> Unit, preparing: Boolean = false) {
    BackHandler { onBack() }
    var expanded by remember(summary.context) { mutableStateOf(initialSection?.let { setOf(it) } ?: emptySet()) }
    var limits by remember(summary.context) { mutableStateOf(emptyMap<LedgerPendingSection, Int>()) }
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("pending-notifications"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("heading") { PendingPageTitle("当前账本通知", onBack) }
                item("summary") {
                    PanelCard {
                        Text(if (summary.context == null) "账本待核验" else ledgerName, style = MaterialTheme.typography.titleMedium)
                        Text(if (preparing) "正在读取本机来源…" else pendingCount(summary), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Spacer(Modifier.height(8.dp)); PendingAccess(summary)
                        if (summary.busy || opening || preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = onRefresh, enabled = !summary.busy && !opening && !preparing, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取待处理") }
                    }
                }
                LedgerPendingSection.entries.forEach { section ->
                    val entries = summary.section(section)
                    item("section-${section.name}") {
                        TextButton(onClick = { expanded = if (section in expanded) expanded - section else expanded + section },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                stateDescription = if (section in expanded) "已展开" else "已收起"
                            }) {
                            Mark(section.icon(), Muted, 20); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(section.title(), color = Ink, style = MaterialTheme.typography.titleMedium)
                                Text(if (summary.exactTotal != null) "${entries.size} 项" else "已读 ${entries.size} 个定位组 · 待核验", color = Muted)
                            }
                            Text(if (section in expanded) "收起" else "展开", color = Muted)
                        }
                    }
                    if (section in expanded) {
                        item("sources-${section.name}") {
                            PanelCard {
                                Text("读取状态", style = MaterialTheme.typography.titleMedium)
                                sectionSources(section).forEach { source ->
                                    val availability = summary.sources[source]?.availability ?: LedgerPendingAvailability.NOT_LOADED
                                    Spacer(Modifier.height(8.dp))
                                    Text(source.title(), style = MaterialTheme.typography.bodySmall)
                                    PendingNotice(availability.label(), availability in setOf(LedgerPendingAvailability.FAILED, LedgerPendingAvailability.DENIED))
                                }
                            }
                        }
                        if (entries.isEmpty()) item("empty-${section.name}") {
                            PendingNotice(if (summary.exactTotal != null) "此组暂无待处理。" else "此组尚未读到定位组，读取范围仍待核验。")
                        }
                        val limit = limits[section] ?: 20
                        items(entries.take(limit), key = { "${section.name}:${it.key}" }) { entry ->
                            PendingEntryRow(entry, !summary.busy && !opening && summary.permission == LedgerPendingAvailability.COMPLETE, onOpen)
                        }
                        if (entries.size > limit) item("more-${section.name}") {
                            OutlinedButton(onClick = { limits = limits + (section to (limit + 20)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("继续显示 · 已显示 $limit / ${entries.size} 个定位组")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingEntryRow(entry: LedgerPendingEntry, enabled: Boolean, onOpen: (LedgerPendingTarget) -> Unit) {
    var alternatives by remember(entry.key) { mutableStateOf(false) }
    val primary = entry.targets.first()
    PanelCard {
        Text(pendingTargetTitle(primary), style = MaterialTheme.typography.titleMedium)
        Text(entry.kind.label(), color = Muted, style = MaterialTheme.typography.bodySmall)
        targetLocator(primary).take(2).forEach { (label, value) -> Text("$label：$value", color = Muted, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { onOpen(primary) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("核验并查看原操作") }
        if (entry.targets.size > 1) {
            TextButton(onClick = { alternatives = !alternatives }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (alternatives) "收起关联原入口" else "查看 ${entry.targets.size - 1} 个关联原入口") }
            if (alternatives) entry.targets.drop(1).forEach { target ->
                OutlinedButton(onClick = { onOpen(target) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(pendingTargetTitle(target)) }
            }
        }
    }
}

/** Read-only intermediate destination; target editors can consume the exact handoff later. */
@Composable
internal fun LedgerPendingOriginPage(destination: PendingDestination, summary: LedgerPendingSummary,
    status: PendingDestinationStatus, opening: Boolean, onVerify: (PendingDestination) -> Unit, onBack: () -> Unit,
    onContinue: ((PendingDestination) -> Unit)? = null) {
    BackHandler { onBack() }
    LaunchedEffect(destination.navigationId) { onVerify(destination) }
    val valid = status == PendingDestinationStatus.VERIFIED && destination.context == summary.context && destination.initialVerifiedTicket.isCurrent()
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PendingPageTitle("原操作定位", onBack)
                if (destination.context != summary.context) PendingNotice("所属账本或会话已变化，请返回重新核验。", true)
                else PanelCard {
                    Text(pendingTargetTitle(destination.target), style = MaterialTheme.typography.titleMedium)
                    Text(if (destination.context.ledgerId == 0L) "默认个人账本" else "原账本标识：${destination.context.ledgerId}", color = Muted)
                    Spacer(Modifier.height(12.dp))
                    targetLocator(destination.target).forEach { (label, value) -> Text("$label：$value", style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(12.dp))
                    if (status == PendingDestinationStatus.CHECKING || opening) { LinearProgressIndicator(Modifier.fillMaxWidth()); PendingNotice("正在重新核验原权限与定位…") }
                    else if (valid) PendingNotice("原权限与定位已核验。后续操作仍需在原流程中确认。")
                    else PendingNotice("原定位已变化或暂不可访问，请重新核验；不会改开另一条记录。", true)
                    if (onContinue == null) PendingNotice("当前版本尚不能从此入口继续处理，请保留原记录。")
                    Button(onClick = { if (destination.initialVerifiedTicket.isCurrent()) onContinue?.invoke(destination) else onVerify(destination) },
                        enabled = valid && !opening && onContinue != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续原流程") }
                    TextButton(onClick = { onVerify(destination) }, enabled = !opening && !summary.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新核验原定位") }
                }
            }
        }
    }
}

/** Saved route intent without its memory-held original location never opens a generic editor. */
@Composable
internal fun LedgerPendingLocationUnavailablePage(onBack: () -> Unit) {
    BackHandler { onBack() }
    Surface(Modifier.fillMaxSize(), color = CanvasBlack) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PendingPageTitle("原操作暂不可用", onBack)
                PendingNotice("原定位已变化或未能恢复，请返回重新读取待处理后核对。", true)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回总览") }
            }
        }
    }
}
