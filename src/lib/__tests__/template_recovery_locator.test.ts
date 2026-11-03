import { describe, expect, it } from 'vitest';
import { assertTemplateRecoveryLocator, type TemplateRecoveryTarget } from '../template_recovery_locator.ts';
import type { PendingTemplateCreate, TemplateEditorDraft } from '@/models/template_creation.ts';
const request = () => ({ ledgerId: '9007199254740993', templateType: 1, clientSessionId: 'original-key', name: 'Synthetic template', type: 3,
    categoryId: '11', sourceAccountId: '21', destinationAccountId: '0', sourceAmount: 123, destinationAmount: 0, hideAmount: true, tagIds: [], comment: 'Synthetic' });
const pending = (): PendingTemplateCreate => ({ version: 1, identity: 'synthetic-user', request: request(), draftRevision: 'original-revision' });
const draft = (): TemplateEditorDraft => ({ version: 1, revision: 'original-revision', input: request() });
const target = (recovery: 'pending' | 'draft'): TemplateRecoveryTarget => recovery === 'pending'
    ? { kind: 'template-create', ledgerId: request().ledgerId, templateType: 1, recovery, clientSessionId: 'original-key', draftRevision: 'original-revision' }
    : { kind: 'template-create', ledgerId: request().ledgerId, templateType: 1, recovery, draftRevision: 'original-revision' };
describe('template recovery final locator guard', () => {
    it('accepts the exact original pending request and a later receipt for that request', () => {
        const original = pending();
        expect(() => assertTemplateRecoveryLocator(target('pending'), request().ledgerId, 1, original, null)).not.toThrow();
        expect(() => assertTemplateRecoveryLocator(target('pending'), request().ledgerId, 1, { ...original, receipt: { id: '9007199254740995', deleted: false } }, null, original)).not.toThrow();
    });
    it.each(['missing', 'key', 'revision', 'ledger', 'type'])('rejects a replaced pending %s without consuming replacement content', change => {
        const next = pending();
        const value = change === 'missing' ? null : change === 'key' ? { ...next, request: { ...next.request, clientSessionId: 'replacement-key' } }
            : change === 'revision' ? { ...next, draftRevision: 'replacement-revision' }
            : { ...next, request: { ...next.request, ...(change === 'ledger' ? { ledgerId: '0' } : { templateType: 2 }) } };
        expect(() => assertTemplateRecoveryLocator(target('pending'), request().ledgerId, 1, value, null)).toThrow('不会打开替代');
    });
    it.each(['missing', 'revision', 'ledger', 'type', 'submitted'])('rejects a replaced draft %s', change => {
        const next = draft();
        const value = change === 'missing' ? null : change === 'revision' ? { ...next, revision: 'replacement-revision' }
            : change === 'ledger' || change === 'type' ? { ...next, input: { ...next.input, ...(change === 'ledger' ? { ledgerId: '0' } : { templateType: 2 }) } } : next;
        expect(() => assertTemplateRecoveryLocator(target('draft'), request().ledgerId, 1, change === 'submitted' ? pending() : null, value)).toThrow('不会打开替代');
    });
    it('pins original pending snapshots even without a notification locator', () => {
        const original = pending();
        expect(() => assertTemplateRecoveryLocator(undefined, request().ledgerId, 1, { ...original, request: { ...original.request, sourceAmount: 456 } }, null, original)).toThrow();
        expect(() => assertTemplateRecoveryLocator(undefined, request().ledgerId, 1, null, null, original)).toThrow();
    });
    it('rejects a different receipt ID under the same original request', () => {
        const original = { ...pending(), receipt: { id: '9007199254740995', deleted: false } };
        expect(() => assertTemplateRecoveryLocator(undefined, request().ledgerId, 1, { ...original, receipt: { id: '9007199254740997', deleted: false } }, null, original)).toThrow();
    });
    it('releases a draft locator after explicit continue/discard so new revisions are allowed', () => {
        expect(() => assertTemplateRecoveryLocator(target('draft'), request().ledgerId, 1, null, draft())).not.toThrow();
        expect(() => assertTemplateRecoveryLocator(undefined, request().ledgerId, 1, null, { ...draft(), revision: 'new-edit-revision' })).not.toThrow();
    });
    it('keeps default zero and scheduled-template identities exact', () => {
        expect(() => assertTemplateRecoveryLocator(target('draft'), '0', 1, null, draft())).toThrow();
        expect(() => assertTemplateRecoveryLocator(target('draft'), request().ledgerId, 2, null, draft())).toThrow();
    });
});
