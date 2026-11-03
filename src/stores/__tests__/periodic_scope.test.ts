import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
const identity = vi.hoisted(() => ({ server: 'https://synthetic.invalid/a', username: 'synthetic-owner' }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => JSON.stringify([identity.server, identity.username]),
    getCurrentUserInfo: () => ({ username: identity.username }),
}));
vi.mock('@/lib/logger.ts', () => ({ default: { warn: vi.fn() } }));
vi.mock('../ledger.ts', async () => {
    const { reactive } = await import('vue');
    const state = reactive({ selectedLedgerId: '0' });
    return { useLedgersStore: () => state };
});
vi.mock('../user.ts', async () => {
    const { reactive } = await import('vue');
    const state = reactive({ currentUserBasicInfo: { username: 'synthetic-owner' } });
    return { useUserStore: () => state };
});
vi.mock('@/lib/services.ts', () => ({ default: {
    listLedgerMembers: vi.fn(), getAllTransactionTemplates: vi.fn(), getTransactionTemplate: vi.fn(),
    addTransactionTemplate: vi.fn(), modifyTransactionTemplate: vi.fn(), hideTransactionTemplate: vi.fn(),
    moveTransactionTemplate: vi.fn(), deleteTransactionTemplate: vi.fn(), listScheduledOccurrences: vi.fn(),
    confirmScheduledOccurrence: vi.fn(), dismissScheduledOccurrence: vi.fn(), restoreScheduledOccurrence: vi.fn(),
} }));
import services from '@/lib/services.ts';
import { useLedgersStore } from '../ledger.ts';
import { useUserStore } from '../user.ts';
import { useTransactionTemplatesStore } from '../transactionTemplate.ts';
import { useScheduledOccurrencesStore } from '../scheduledOccurrence.ts';
import { TransactionTemplate, type TransactionTemplateInfoResponse } from '@/models/transaction_template.ts';
import { ScheduledOccurrence, type ScheduledOccurrenceInfoResponse } from '@/models/scheduled_occurrence.ts';

const ledger = '9007199254740993';
const response = (result: unknown) => ({ data: { success: true, result } }) as never;
const template = (ledgerId = ledger, id = '9007199254740997'): TransactionTemplateInfoResponse => ({
    id, ledgerId, timeSequenceId: '0', templateType: 2, name: 'Synthetic plan', type: 3, categoryId: '11', time: 0,
    utcOffset: 480, sourceAccountId: '21', destinationAccountId: '0', sourceAmount: 250, destinationAmount: 0,
    hideAmount: false, tagIds: ['31'], comment: '', editable: true, hidden: false, displayOrder: 1,
    scheduledFrequencyType: 1, scheduledFrequency: '1', scheduledAt: 960, nextScheduledTime: 1791043200,
});
const occurrence = (ledgerId = ledger, templateId = '9007199254740997', status = 1): ScheduledOccurrenceInfoResponse => ({
    ledgerId, templateId, scheduledUnixTime: 1791043200, status, transactionId: '0',
    snapshot: { ledgerId, name: 'Synthetic plan', type: 3, categoryId: '11', sourceAccountId: '21', destinationAccountId: '0', sourceAmount: 250, destinationAmount: 0, utcOffset: 480, hideAmount: false, tagIds: ['31'], comment: '' },
});
function deferred() {
    let resolve!: (value: never) => void;
    const promise = new Promise<never>(done => { resolve = done; });
    return { resolve, promise };
}
function members(role = 1) {
    vi.mocked(services.listLedgerMembers).mockResolvedValue(response([{ id: '41', ledgerId: ledger, uid: '51', role, status: 1, isCurrentUser: true }]));
}
async function waitFor(call: () => boolean) { await vi.waitFor(() => expect(call()).toBe(true)); }

describe('periodic ledger isolation and captured operations', () => {
    beforeEach(() => {
        vi.resetAllMocks(); setActivePinia(createPinia());
        identity.server = 'https://synthetic.invalid/a'; identity.username = 'synthetic-owner';
        useLedgersStore().selectedLedgerId = ledger;
        useUserStore().currentUserBasicInfo = { username: identity.username } as never;
        members();
    });
    it('retains exact large IDs and only explicitly selects the current ledger', async () => {
        const store = useTransactionTemplatesStore();
        vi.mocked(services.getAllTransactionTemplates).mockResolvedValue(response([template()]));
        await store.loadAllTemplates({ templateType: 2, ledgerId: ledger });
        expect(store.allTransactionTemplates[2]?.[0]?.ledgerId).toBe(ledger);
        expect(services.getAllTransactionTemplates).toHaveBeenCalledWith({ templateType: 2, ledgerId: ledger, expectedUsername: identity.username });
        await expect(store.loadAllTemplates({ templateType: 2 })).rejects.toThrow();
        expect(services.getAllTransactionTemplates).toHaveBeenCalledTimes(1);
    });
    it('clears visible rows synchronously on ledger and account switches', async () => {
        const store = useTransactionTemplatesStore();
        vi.mocked(services.getAllTransactionTemplates).mockResolvedValue(response([template()]));
        await store.loadAllTemplates({ templateType: 2, ledgerId: ledger });
        useLedgersStore().selectedLedgerId = '0'; expect(store.allTransactionTemplates).toEqual({});
        expect(store.loadedLedgerId).toBe('0');
        vi.mocked(services.getAllTransactionTemplates).mockResolvedValue(response([template('0')]));
        await store.loadAllTemplates({ templateType: 2 });
        identity.username = 'synthetic-other'; useUserStore().currentUserBasicInfo = { username: identity.username } as never;
        expect(store.allTransactionTemplates).toEqual({});
    });
    it('preserves known rows on missing result, wrong scope and duplicate templates', async () => {
        const store = useTransactionTemplatesStore();
        vi.mocked(services.getAllTransactionTemplates).mockResolvedValueOnce(response([template()]));
        await store.loadAllTemplates({ templateType: 2, ledgerId: ledger });
        for (const invalid of [{ data: { success: true } }, response([template('0')]), response([template(), template()])]) {
            vi.mocked(services.getAllTransactionTemplates).mockResolvedValueOnce(invalid as never);
            await expect(store.loadAllTemplates({ templateType: 2, force: true, ledgerId: ledger })).rejects.toThrow();
            expect(store.allTransactionTemplates[2]).toHaveLength(1);
        }
    });
    it('rejects an old server response instead of filling the new context', async () => {
        const store = useTransactionTemplatesStore(); const pending = deferred();
        vi.mocked(services.getAllTransactionTemplates).mockReturnValueOnce(pending.promise);
        const request = store.loadAllTemplates({ templateType: 2, ledgerId: ledger });
        await waitFor(() => vi.mocked(services.getAllTransactionTemplates).mock.calls.length === 1);
        identity.server += '/second'; pending.resolve(response([template()]));
        await expect(request).rejects.toThrow(); expect(store.allTransactionTemplates).toEqual({});
    });
    it('a late read cannot overwrite an acknowledged save, and edits during authorization cannot alter the submitted snapshot', async () => {
        const store = useTransactionTemplatesStore(); const oldRead = deferred();
        vi.mocked(services.getAllTransactionTemplates).mockReturnValueOnce(oldRead.promise);
        const read = store.loadAllTemplates({ templateType: 2, ledgerId: ledger });
        await waitFor(() => vi.mocked(services.getAllTransactionTemplates).mock.calls.length === 1);
        const permissions = deferred(); vi.mocked(services.listLedgerMembers).mockReturnValueOnce(permissions.promise);
        const draft = TransactionTemplate.ofTemplate(template());
        vi.mocked(services.modifyTransactionTemplate).mockResolvedValueOnce(response({ ...template(), sourceAmount: 500 }));
        const saving = store.saveTemplateContent({ template: draft, isEdit: true, clientSessionId: 'synthetic-key' });
        draft.sourceAmount = 999; draft.tagIds.push('32'); draft.ledgerId = '0';
        permissions.resolve(response([{ ledgerId: ledger, role: 1, status: 1, isCurrentUser: true }]));
        await saving;
        expect(services.modifyTransactionTemplate).toHaveBeenCalledWith(expect.objectContaining({ ledgerId: ledger, sourceAmount: 250, tagIds: ['31'] }), identity.username);
        oldRead.resolve(response([template()])); await expect(read).rejects.toThrow();
        expect(store.allTransactionTemplates[2]?.[0]?.sourceAmount).toBe(500);
    });
    it.each([3, 4])('role %s cannot modify templates', async role => {
        members(role); const store = useTransactionTemplatesStore();
        await expect(store.saveTemplateContent({ template: TransactionTemplate.ofTemplate(template()), isEdit: true, clientSessionId: '' })).rejects.toThrow();
        expect(services.modifyTransactionTemplate).not.toHaveBeenCalled();
    });
    it('reads all pages and both queue statuses before committing any replacement', async () => {
        const store = useScheduledOccurrencesStore();
        const page = Array.from({ length: 100 }, (_, index) => occurrence(ledger, String(1000 + index)));
        vi.mocked(services.listScheduledOccurrences).mockResolvedValueOnce(response(page)).mockResolvedValueOnce(response([occurrence()])).mockResolvedValueOnce(response([occurrence(ledger, '5001', 3)]));
        await store.load(ledger); expect(store.pendingOccurrences).toHaveLength(101); expect(store.dismissedOccurrences).toHaveLength(1);
        expect(services.listScheduledOccurrences).toHaveBeenNthCalledWith(2, { status: 1, offset: 100, limit: 100, ledgerId: ledger, expectedUsername: identity.username });
        vi.mocked(services.listScheduledOccurrences).mockResolvedValueOnce(response([])).mockResolvedValueOnce({ data: { success: true } } as never);
        await expect(store.load(ledger)).rejects.toThrow(); expect(store.occurrences).toHaveLength(102);
    });
    it('403 remains a visible failure and never masquerades as an empty queue', async () => {
        const store = useScheduledOccurrencesStore();
        vi.mocked(services.listScheduledOccurrences).mockResolvedValueOnce(response([occurrence()])).mockResolvedValueOnce(response([]));
        await store.load(ledger);
        vi.mocked(services.listScheduledOccurrences).mockRejectedValueOnce({ response: { status: 403 } });
        await expect(store.load(ledger)).rejects.toMatchObject({ status: 403 });
        expect(store.pendingOccurrences).toHaveLength(1); expect(store.featureDisabled).toBe(false);
    });
    it('member confirmations keep captured ledger and reject a mismatched response', async () => {
        members(3); const store = useScheduledOccurrencesStore(); const row = ScheduledOccurrence.of(occurrence());
        vi.mocked(services.confirmScheduledOccurrence).mockResolvedValueOnce(response({ ledgerId: '0', transactionId: '77' }));
        await expect(store.confirm(row)).rejects.toThrow();
        vi.mocked(services.confirmScheduledOccurrence).mockResolvedValueOnce(response({ ledgerId: ledger, transactionId: '9007199254740999' }));
        expect(await store.confirm(row)).toBe('9007199254740999');
        expect(services.confirmScheduledOccurrence).toHaveBeenLastCalledWith({ ledgerId: ledger, templateId: row.templateId, scheduledUnixTime: row.scheduledUnixTime }, identity.username);
    });
    it('viewer review writes and old-ledger objects never reach HTTP', async () => {
        members(4); const store = useScheduledOccurrencesStore(); const row = ScheduledOccurrence.of(occurrence());
        await expect(store.confirm(row)).rejects.toThrow(); await expect(store.dismiss(row)).rejects.toThrow(); await expect(store.restore(row)).rejects.toThrow();
        useLedgersStore().selectedLedgerId = '0'; await expect(store.confirm(row)).rejects.toThrow();
        expect(services.confirmScheduledOccurrence).not.toHaveBeenCalled(); expect(services.dismissScheduledOccurrence).not.toHaveBeenCalled();
    });
    it('an in-flight confirmation cannot remove or refresh a newly selected ledger queue', async () => {
        const store = useScheduledOccurrencesStore(); const pending = deferred();
        vi.mocked(services.confirmScheduledOccurrence).mockReturnValueOnce(pending.promise);
        const write = store.confirm(ScheduledOccurrence.of(occurrence()));
        await waitFor(() => vi.mocked(services.confirmScheduledOccurrence).mock.calls.length === 1);
        useLedgersStore().selectedLedgerId = '0';
        pending.resolve(response({ ledgerId: ledger, transactionId: '77' }));
        await expect(write).rejects.toThrow(); expect(store.loadedLedgerId).toBe('0'); expect(store.occurrences).toEqual([]);
    });
});
