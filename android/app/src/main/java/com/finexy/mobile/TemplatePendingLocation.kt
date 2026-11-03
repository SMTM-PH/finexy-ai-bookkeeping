package com.finexy.mobile

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*

internal const val TEMPLATE_PENDING_UNAVAILABLE = "原目标不可用，请返回待处理列表重新核对。"

internal enum class TemplatePendingRecoveryMode { READ_RECEIPT, RETRY_REQUEST }

/** Reading a known receipt needs read access; an unknown create still needs template management. */
internal fun templatePendingRecoveryMode(role: Int?, state: String?): TemplatePendingRecoveryMode? = when {
    role !in listOf(RemoteLedgerMember.ROLE_OWNER, RemoteLedgerMember.ROLE_ADMIN, RemoteLedgerMember.ROLE_MEMBER, RemoteLedgerMember.ROLE_VIEWER) -> null
    state == TemplateCreationEntity.POSTED -> TemplatePendingRecoveryMode.READ_RECEIPT
    state == TemplateCreationEntity.PENDING && role in listOf(RemoteLedgerMember.ROLE_OWNER, RemoteLedgerMember.ROLE_ADMIN) -> TemplatePendingRecoveryMode.RETRY_REQUEST
    else -> null
}

/** Re-read after every async boundary; successful explicit resume releases only the old revision. */
internal suspend fun verifyTemplatePendingLocation(guard: LedgerPendingPageGuard?, repository: TransactionRepository,
    ledgerId: Long, templateType: Int): Boolean {
    if (guard == null) return true
    if (guard.state.value == LedgerPendingPageStatus.RELEASED) return guard.isCurrent()
    if (!guard.verify()) return false
    if (guard.state.value == LedgerPendingPageStatus.RELEASED) return guard.isCurrent()
    val originalWrite = repository.templateCreations().singleOrNull { it.ledgerId == ledgerId && it.templateType == templateType }
    val originalDraft = repository.templateDraft(ledgerId, templateType)
    if (!templatePendingLocationMatches(guard.location.target, ledgerId, templateType, originalDraft, originalWrite)) {
        guard.invalidate()
        return false
    }
    return guard.verify()
}

@Composable
internal fun TemplatePendingLocationNotice(status: LedgerPendingPageStatus?, initialized: Boolean, onRetry: () -> Unit) {
    if (status == null) return
    val failed = status in setOf(LedgerPendingPageStatus.FAILED, LedgerPendingPageStatus.CLOSED)
    val text = when {
        failed -> TEMPLATE_PENDING_UNAVAILABLE
        !initialized || status == LedgerPendingPageStatus.CHECKING -> "正在核验原目标…"
        else -> "已定位原目标；打开页面不会提交、确认或重试。"
    }
    PanelCard {
        Text(text, color = if (failed) MaterialTheme.colorScheme.error else Muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; if (failed) error(text) })
        if (failed) TextButton(enabled = status != LedgerPendingPageStatus.CLOSED, onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("重新核验原目标") }
    }
}

/** Resolve only the original raw editor or immutable request, never a replacement in the same slot. */
internal fun templatePendingLocationMatches(target: LedgerPendingTarget?, ledgerId: Long, templateType: Int,
    draft: TemplateFormDraftEntity?, write: TemplateCreationEntity?): Boolean = runCatching {
    val original = target as? LedgerPendingTarget.Template ?: return@runCatching false
    if (original.ledgerId != ledgerId || original.templateType != templateType) return@runCatching false
    if (original.requestId != null) write != null && pendingTemplateRequestRecord(write).target == original
    else write == null && draft != null && pendingTemplateDraftRecord(draft).target == original
}.getOrDefault(false)

/** A queued intent is distinct from the same due row before an intent, or a later replacement. */
internal fun occurrencePendingLocationMatches(target: LedgerPendingTarget?, row: ScheduledOccurrenceEntity?): Boolean {
    val original = target as? LedgerPendingTarget.Occurrence ?: return false
    if (row == null || row.ledgerId != original.ledgerId || row.templateId != original.templateId ||
        row.scheduledUnixTime != original.scheduledUnixTime) return false
    return if (original.action == ScheduledOccurrenceEntity.ACTION_NONE) {
        row.status == ScheduledOccurrenceEntity.STATUS_PENDING && row.pendingAction == ScheduledOccurrenceEntity.ACTION_NONE && row.pendingActionQueuedAt == 0L
    } else row.status in setOf(ScheduledOccurrenceEntity.STATUS_PENDING, ScheduledOccurrenceEntity.STATUS_DISMISSED) &&
        row.pendingAction == original.action && row.pendingActionQueuedAt == original.queuedAt
}
