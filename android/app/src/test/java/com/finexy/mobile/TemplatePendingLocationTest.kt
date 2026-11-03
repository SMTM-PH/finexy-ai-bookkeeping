package com.finexy.mobile

import com.finexy.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class TemplatePendingLocationTest {
    private fun draft(kind: Int = 1) = TemplateFormDraftEntity(9, kind, "original-draft", 7, 42, "{\"name\":\"synthetic\",\"amount\":\"1.+\"}", 1)
    private fun request(kind: Int = 1): TemplateCreationEntity {
        val schedule = if (kind == 2) ",\"scheduledFrequencyType\":3,\"scheduledFrequency\":\"0\",\"scheduledStartDate\":null,\"scheduledEndDate\":null,\"utcOffset\":480" else ""
        return TemplateCreationEntity(9, kind, "original-request", "original-draft", 7,
            "{\"ledgerId\":\"9\",\"templateType\":$kind,\"name\":\"synthetic\",\"type\":3,\"categoryId\":\"20\",\"sourceAccountId\":\"10\",\"destinationAccountId\":\"0\",\"sourceAmount\":1234,\"destinationAmount\":0,\"hideAmount\":false,\"tagIds\":[],\"comment\":\"\"$schedule}", createdAt = 1)
    }
    private fun occurrence() = ScheduledOccurrenceEntity(42, 1790000000, ScheduledOccurrenceEntity.STATUS_PENDING, ledgerId = 9)

    @Test fun originalRawEditorIncludesEditingIdAndIncompleteInputWithoutCreatingAnything() {
        for (kind in 1..2) {
            val saved = draft(kind)
            val target = pendingTemplateDraftRecord(saved).target
            assertTrue(templatePendingLocationMatches(target, 9, kind, saved, null))
            assertFalse(templatePendingLocationMatches(target, 9, kind, null, null))
            assertFalse(templatePendingLocationMatches(target, 9, kind, saved.copy(editingId = 43), null))
            assertFalse(templatePendingLocationMatches(target, 9, kind, saved.copy(formJson = "{\"name\":\"changed\"}"), null))
        }
    }

    @Test fun rawEditorCannotFollowRevisionDraftIdentityLedgerOrTypeReplacement() {
        val saved = draft()
        val target = pendingTemplateDraftRecord(saved).target
        for (replacement in listOf(saved.copy(revision = 8), saved.copy(draftId = "other-draft"), saved.copy(ledgerId = 10), saved.copy(templateType = 2)))
            assertFalse(templatePendingLocationMatches(target, 9, 1, replacement, null))
        assertFalse(templatePendingLocationMatches(target, 10, 1, saved, null))
        assertFalse(templatePendingLocationMatches(target, 9, 2, saved, null))
    }

    @Test fun aConfirmedRequestMustNotFallBackToItsRawEditorOrAnotherConfirmedRequest() {
        val sent = request()
        val target = pendingTemplateRequestRecord(sent).target
        assertTrue(templatePendingLocationMatches(target, 9, 1, null, sent))
        assertFalse(templatePendingLocationMatches(target, 9, 1, draft(), null))
        for (replacement in listOf(sent.copy(clientSessionId = "replacement"), sent.copy(draftId = "replacement"),
            sent.copy(draftRevision = 8), sent.copy(createdAt = 2), sent.copy(payloadJson = sent.payloadJson.replace("1234", "1235"))))
            assertFalse(templatePendingLocationMatches(target, 9, 1, draft(), replacement))
    }

    @Test fun aRawEditorAlreadyConfirmedElsewhereCannotBeReopenedAsAnUnconfirmedForm() {
        val saved = draft()
        assertFalse(templatePendingLocationMatches(pendingTemplateDraftRecord(saved).target, 9, 1, saved, request()))
    }

    @Test fun requestReceiptAdvancePreservesTheOriginalLocatorButBrokenReceiptsDoNot() {
        for (kind in 1..2) {
            val sent = request(kind)
            val target = pendingTemplateRequestRecord(sent).target
            assertTrue(templatePendingLocationMatches(target, 9, kind, null, sent.copy(state = TemplateCreationEntity.POSTED, serverId = 9007199254740993L)))
            assertFalse(templatePendingLocationMatches(target, 9, kind, null, sent.copy(state = TemplateCreationEntity.POSTED)))
        }
    }

    @Test fun malformedRawOrRequestDataAndWrongTargetKindsAreUnavailable() {
        val saved = draft(); val sent = request()
        assertFalse(templatePendingLocationMatches(pendingTemplateDraftRecord(saved).target, 9, 1, saved.copy(formJson = "{}broken"), null))
        assertFalse(templatePendingLocationMatches(pendingTemplateRequestRecord(sent).target, 9, 1, null, sent.copy(payloadJson = "{}")))
        assertFalse(templatePendingLocationMatches(LedgerPendingTarget.Occurrence(9, 42, 1790000000), 9, 1, saved, sent))
    }

    @Test fun dueRowLocationRequiresTheOriginalLedgerTemplateTimeAndPendingState() {
        val row = occurrence(); val target = LedgerPendingTarget.Occurrence(9, row.templateId, row.scheduledUnixTime)
        assertTrue(occurrencePendingLocationMatches(target, row))
        assertFalse(occurrencePendingLocationMatches(target, null))
        for (changed in listOf(row.copy(ledgerId = 10), row.copy(templateId = 43), row.copy(scheduledUnixTime = row.scheduledUnixTime + 1),
            row.copy(status = ScheduledOccurrenceEntity.STATUS_CONFIRMED), row.copy(status = ScheduledOccurrenceEntity.STATUS_DISMISSED),
            row.copy(pendingAction = ScheduledOccurrenceEntity.ACTION_CONFIRM, pendingActionQueuedAt = 10)))
            assertFalse(occurrencePendingLocationMatches(target, changed))
    }

    @Test fun queuedActionDoesNotFollowAnotherActionOrASecondQueueAttemptOnTheSameDueRow() {
        for (action in ScheduledOccurrenceEntity.ACTION_CONFIRM..ScheduledOccurrenceEntity.ACTION_RESTORE) {
            val row = occurrence().copy(pendingAction = action, pendingActionQueuedAt = 100,
                status = if (action == ScheduledOccurrenceEntity.ACTION_RESTORE) ScheduledOccurrenceEntity.STATUS_DISMISSED else ScheduledOccurrenceEntity.STATUS_PENDING)
            val target = LedgerPendingTarget.Occurrence(9, row.templateId, row.scheduledUnixTime, action, 100)
            assertTrue(occurrencePendingLocationMatches(target, row))
            assertFalse(occurrencePendingLocationMatches(target, row.copy(pendingActionQueuedAt = 101)))
            assertFalse(occurrencePendingLocationMatches(target, row.copy(pendingAction = 0, pendingActionQueuedAt = 0)))
            assertFalse(occurrencePendingLocationMatches(target, row.copy(pendingAction = if (action == 1) 2 else 1)))
        }
    }

    @Test fun completedQueuedActionAndDifferentTargetKindsAreUnavailable() {
        val row = occurrence().copy(pendingAction = 1, pendingActionQueuedAt = 100)
        val target = LedgerPendingTarget.Occurrence(9, row.templateId, row.scheduledUnixTime, 1, 100)
        assertFalse(occurrencePendingLocationMatches(target, row.copy(status = ScheduledOccurrenceEntity.STATUS_CONFIRMED)))
        assertFalse(occurrencePendingLocationMatches(pendingTemplateDraftRecord(draft()).target, row))
    }

    @Test fun knownReceiptCanBeReadAfterDowngradeWithoutGrantingUnknownCreateRetry() {
        for (role in 1..4) assertEquals(TemplatePendingRecoveryMode.READ_RECEIPT, templatePendingRecoveryMode(role, TemplateCreationEntity.POSTED))
        for (role in 1..2) assertEquals(TemplatePendingRecoveryMode.RETRY_REQUEST, templatePendingRecoveryMode(role, TemplateCreationEntity.PENDING))
        for (role in listOf(null, 0, 3, 4, 5)) assertNull(templatePendingRecoveryMode(role, TemplateCreationEntity.PENDING))
        for (role in listOf(null, 0, 5)) assertNull(templatePendingRecoveryMode(role, TemplateCreationEntity.POSTED))
        assertNull(templatePendingRecoveryMode(1, "unknown-state"))
    }
}
