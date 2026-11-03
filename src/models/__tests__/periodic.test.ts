import { describe, expect, it } from 'vitest';
import { LedgerDeletePreview } from '../ledger.ts';
import { ScheduledOccurrence, type ScheduledOccurrenceInfoResponse } from '../scheduled_occurrence.ts';
import { isPeriodicStringId, TransactionTemplate, type TransactionTemplateInfoResponse } from '../transaction_template.ts';

const preview = { ledgerId: '9', ledgerName: 'Synthetic', activeMemberCount: 1, pendingInvitationCount: 0, accountCount: 0, transactionCount: 0, savingsGoalCount: 0, savingsGoalFundCount: 0, canDelete: true, blockingReasons: [] as string[] };
const row: ScheduledOccurrenceInfoResponse = { ledgerId: '9', templateId: '9007199254740993', scheduledUnixTime: 1791043200, status: 1, transactionId: '0', snapshot: { ledgerId: '9', name: 'Synthetic', type: 3, categoryId: '11', sourceAccountId: '21', destinationAccountId: '0', sourceAmount: -100, destinationAmount: 0, utcOffset: 480, hideAmount: false, tagIds: ['31'], comment: '' } };
describe('periodic DTO and delete boundaries', () => {
    it('retains legacy zero counts and blocks a template-only or dismissed-only ledger', () => {
        expect(LedgerDeletePreview.of(preview).transactionTemplateCount).toBe(0);
        for (const [field, blocker] of [['transactionTemplateCount', 'transactionTemplates'], ['scheduledOccurrenceCount', 'scheduledOccurrences']]) {
            const blocked = LedgerDeletePreview.of({ ...preview, [field!]: 1, blockingReasons: [blocker!], canDelete: false });
            expect(blocked.canDelete).toBe(false);
            expect(() => LedgerDeletePreview.of({ ...preview, [field!]: 1 })).toThrow();
        }
    });
    it('rejects negative or fractional periodic counts rather than hiding a deletion blocker', () => {
        for (const amount of [-1, 0.5, Number.MAX_SAFE_INTEGER + 1]) expect(() => LedgerDeletePreview.of({ ...preview, scheduledOccurrenceCount: amount })).toThrow();
    });
    it('accepts exact signed 64-bit string IDs and rejects lossy numbers or overflow', () => {
        expect(isPeriodicStringId('9223372036854775807')).toBe(true);
        for (const id of ['9223372036854775808', '01', '-1', 9007199254740992]) expect(isPeriodicStringId(id)).toBe(false);
    });
    it('freezes the captured ledger and tags while preserving a refund sign', () => {
        const input = { ...row, snapshot: { ...row.snapshot, tagIds: [...row.snapshot.tagIds] } };
        const parsed = ScheduledOccurrence.of(input, '9'); input.snapshot.tagIds.push('32');
        expect(parsed.sourceAmount).toBe(-100); expect(parsed.tagIds).toEqual(['31']); expect(Object.isFrozen(parsed)).toBe(true);
        expect(parsed.actionRequest.ledgerId).toBe('9');
    });
    it('only defaults an old missing ledger field to zero and never to the selected shared ledger', () => {
        const legacy = { ...row, ledgerId: undefined, snapshot: { ...row.snapshot, ledgerId: undefined } };
        expect(ScheduledOccurrence.of(legacy, '0').ledgerId).toBe('0');
        expect(() => ScheduledOccurrence.of(legacy, '9')).toThrow();
        expect(() => ScheduledOccurrence.of({ ...row, snapshot: { ...row.snapshot, ledgerId: '0' } }, '9')).toThrow();
    });
    it('rejects fractional amounts and missing transfer sides without corrupting an existing queue', () => {
        expect(() => ScheduledOccurrence.of({ ...row, snapshot: { ...row.snapshot, sourceAmount: 0.5 } })).toThrow();
        expect(() => ScheduledOccurrence.of({ ...row, snapshot: { ...row.snapshot, type: 4, sourceAmount: 100 } })).toThrow();
    });
    it('accepts the real API omission of zero destination fields only for non-transfer templates', () => {
        const fixture = { ...row.snapshot, id: '41', ledgerId: '9', timeSequenceId: '0', time: 0, templateType: 2, name: 'Synthetic', editable: true, displayOrder: 1, hidden: false, scheduledFrequencyType: 3, scheduledFrequency: '1', destinationAccountId: undefined, destinationAmount: undefined } as unknown as TransactionTemplateInfoResponse;
        expect(TransactionTemplate.ofTemplate(fixture).destinationAccountId).toBe('0');
        expect(TransactionTemplate.ofTemplate(fixture).destinationAmount).toBe(0);
        expect(() => TransactionTemplate.ofTemplate({ ...fixture, type: 4 })).toThrow();
        expect(() => TransactionTemplate.ofTemplate({ ...fixture, destinationAccountId: null as never })).toThrow();
    });
    it('clones an editable model without losing its category getter or sharing tags', () => {
        const fixture = { ...row.snapshot, id: '41', timeSequenceId: '0', time: 0, templateType: 2, editable: true, displayOrder: 1, hidden: true, scheduledFrequencyType: 3, scheduledFrequency: '1' } as TransactionTemplateInfoResponse;
        const original = TransactionTemplate.ofTemplate(fixture);
        const clone = TransactionTemplate.ofTemplate(original);
        expect(clone.categoryId).toBe('11'); expect(clone.ledgerId).toBe('9'); expect(clone.hidden).toBe(true);
        clone.scheduledFrequencyType = 0; clone.tagIds.push('32');
        expect(original.scheduledFrequencyType).toBe(3); expect(original.tagIds).toEqual(['31']);
    });
});
