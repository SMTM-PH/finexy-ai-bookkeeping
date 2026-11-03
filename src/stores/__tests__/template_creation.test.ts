import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { PendingTemplateCreate, TemplateEditorDraft } from '@/models/template_creation.ts';
import type { TransactionTemplateCreateRequest } from '@/models/transaction_template.ts';

const disk = vi.hoisted(() => ({ identity: 'synthetic-server/user', username: 'synthetic-user',
    pending: new Map<string, PendingTemplateCreate>(), drafts: new Map<string, TemplateEditorDraft>(), fail: false,
    lockBarrier: null as Promise<void> | null, lockEntered: null as (() => void) | null }));
vi.mock('@/lib/logger.ts', () => ({ default: { warn: vi.fn() } }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => disk.identity, getCurrentUserInfo: () => ({ username: disk.username }),
    withTransactionCreateLock: async (action: () => unknown) => { disk.lockEntered?.(); if (disk.lockBarrier) await disk.lockBarrier; return action(); },
    getUserPendingTemplateCreate: (ledger: string) => disk.pending.get(`${disk.identity}/${ledger}`) ?? null,
    updateUserPendingTemplateCreate: (ledger: string, value: PendingTemplateCreate) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.pending.set(`${disk.identity}/${ledger}`, JSON.parse(JSON.stringify(value)));
    },
    clearUserPendingTemplateCreate: (ledger: string) => { disk.pending.delete(`${disk.identity}/${ledger}`); },
    getUserTemplateEditorDraft: (ledger: string, kind: number) => disk.drafts.get(`${disk.identity}/${ledger}/${kind}`) ?? null,
    updateUserTemplateEditorDraft: (ledger: string, kind: number, value: TemplateEditorDraft) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.drafts.set(`${disk.identity}/${ledger}/${kind}`, JSON.parse(JSON.stringify(value)));
    },
    clearUserTemplateEditorDraft: (ledger: string, kind: number) => { disk.drafts.delete(`${disk.identity}/${ledger}/${kind}`); },
}));
vi.mock('../ledger.ts', async () => {
    const { reactive } = await import('vue');
    const state = reactive({ selectedLedgerId: '9' });
    return { useLedgersStore: () => state };
});
vi.mock('../user.ts', async () => {
    const { reactive } = await import('vue');
    const state = reactive({ currentUserBasicInfo: { username: 'synthetic-user' } });
    return { useUserStore: () => state };
});
vi.mock('@/lib/services.ts', () => ({ default: {
    listLedgerMembers: vi.fn(), getTemplateCreationCapabilities: vi.fn(), addDurableTransactionTemplate: vi.fn(), addTransactionTemplate: vi.fn(),
} }));
import services from '@/lib/services.ts';
import { useLedgersStore } from '../ledger.ts';
import { useTemplateCreationStore } from '../templateCreation.ts';

const response = (result: unknown) => ({ data: { success: true, result } }) as never;
const capabilities = { creationContractVersion: 1, durableCreation: true, payloadBinding: true, deletedReceipts: true,
    maxClientSessionIdBytes: 128, submissionScope: 'owner-creator-ledger', durableCreatePath: 'v1/transaction/templates/add_durable.json', templateTypes: [1, 2] };
const request = (ledgerId = '9', kind = 1): TransactionTemplateCreateRequest => ({ ledgerId, templateType: kind,
    clientSessionId: 'stable-key', name: 'Synthetic template', type: 3, categoryId: '11', sourceAccountId: '21', destinationAccountId: '0',
    sourceAmount: -123, destinationAmount: 0, hideAmount: true, tagIds: ['31'], comment: 'Synthetic only',
    ...(kind === 2 ? { scheduledFrequencyType: 3, scheduledFrequency: '1', utcOffset: 480, scheduledStartDate: '2020-01-01' } : {}) });
const result = (body = request(), extra = {}) => response({ ...body, id: '9007199254740997', utcOffset: body.utcOffset ?? 0,
    time: 0, timeSequenceId: '0', editable: true, hidden: false, displayOrder: 1, ...extra });
const draft = (revision = 'draft-1'): TemplateEditorDraft => ({ version: 1, revision, input: { ...request(), ledgerId: '9', name: '', sourceAmount: '1.', destinationAmount: '', categoryId: '0' } });
function role(value = 1) {
    vi.mocked(services.listLedgerMembers).mockImplementation(async ({ ledgerId }) => response([{ id: '41', ledgerId, uid: '51', role: value, status: 1, isCurrentUser: true }]));
}
beforeEach(() => {
    vi.resetAllMocks(); setActivePinia(createPinia()); disk.pending.clear(); disk.drafts.clear(); disk.fail = false; disk.lockBarrier = null; disk.lockEntered = null;
    disk.identity = 'synthetic-server/user'; disk.username = 'synthetic-user'; useLedgersStore().selectedLedgerId = '9'; role();
    vi.mocked(services.getTemplateCreationCapabilities).mockResolvedValue(response(capabilities));
    vi.mocked(services.addDurableTransactionTemplate).mockImplementation(async body => result(body));
});

describe('explicit durable template creation', () => {
    it('persists before HTTP and resumes the exact confirmed snapshot after a store restart', async () => {
        const original = { ...request() };
        vi.mocked(services.addDurableTransactionTemplate).mockImplementationOnce(async body => {
            expect([...disk.pending.values()][0]?.request).toEqual(body); throw new Error('Lost response');
        });
        await expect(useTemplateCreationStore().confirm(original, null)).rejects.toThrow('模板结果尚未确认');
        original.sourceAmount = 999; original.tagIds.push('32');
        setActivePinia(createPinia());
        const store = useTemplateCreationStore();
        await expect(store.confirm({ ...request(), clientSessionId: 'replacement' }, null)).rejects.toThrow('重试原请求');
        const saved = await store.retry('9', 'stable-key');
        expect(vi.mocked(services.addDurableTransactionTemplate).mock.calls[1]![0]).toEqual(request());
        expect(saved.receipt?.id).toBe('9007199254740997');
        expect(services.addTransactionTemplate).not.toHaveBeenCalled();
    });
    it('blocks HTTP if confirmation cannot be stored', async () => {
        disk.fail = true;
        await expect(useTemplateCreationStore().confirm(request(), null)).rejects.toThrow('Storage denied');
        expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
    });
    it('keeps the original request if saving a valid receipt fails', async () => {
        vi.mocked(services.addDurableTransactionTemplate).mockImplementationOnce(async body => { disk.fail = true; return result(body); });
        const store = useTemplateCreationStore();
        await expect(store.confirm(request(), null)).rejects.toThrow('Storage denied');
        expect(store.readPending('9')?.receipt).toBeUndefined();
        disk.fail = false; await store.retry('9', 'stable-key');
        expect(vi.mocked(services.addDurableTransactionTemplate).mock.calls.map(call => call[0])).toEqual([request(), request()]);
    });
    it.each([{}, { creationContractVersion: 2 }, { durableCreation: false }, { payloadBinding: false }, { deletedReceipts: false },
        { maxClientSessionIdBytes: 127 }, { submissionScope: 'user' }, { durableCreatePath: 'v1/transaction/templates/add.json' }, { templateTypes: [1] }])('retains periodic confirmation for an incompatible capability: %j', async change => {
        vi.mocked(services.getTemplateCreationCapabilities).mockResolvedValue(response(Object.keys(change).length ? { ...capabilities, ...change } : {}));
        const store = useTemplateCreationStore();
        await expect(store.confirm(request('9', 2), null)).rejects.toThrow('升级服务');
        expect(store.readPending('9')?.request).toEqual(request('9', 2));
        expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
        expect(services.addTransactionTemplate).not.toHaveBeenCalled();
    });
    it('treats old-server 404 as an upgrade requirement and never falls back', async () => {
        vi.mocked(services.getTemplateCreationCapabilities).mockRejectedValue({ response: { status: 404 } });
        await expect(useTemplateCreationStore().confirm(request(), null)).rejects.toThrow('升级服务');
        expect(services.addTransactionTemplate).not.toHaveBeenCalled();
    });
    it.each([{ id: '12', ledgerId: '0' }, { id: '12', templateType: 2 }, { id: '12', sourceAmount: 0.5 }, { deleted: 'true' }, { deleted: true, editable: true }])('keeps unknown state for malformed replies: %j', async extra => {
        vi.mocked(services.addDurableTransactionTemplate).mockResolvedValue(result(request(), extra));
        const store = useTemplateCreationStore();
        await expect(store.confirm(request(), null)).rejects.toBeDefined();
        expect(store.readPending('9')?.receipt).toBeUndefined();
    });
    it('never POSTs an acknowledged receipt even after read failure or revoked permissions', async () => {
        const store = useTemplateCreationStore(); const confirmed = await store.confirm(request(), null);
        role(4); vi.mocked(services.addDurableTransactionTemplate).mockClear(); vi.mocked(services.getTemplateCreationCapabilities).mockClear();
        expect(await store.retry('9', 'stable-key')).toEqual(confirmed);
        expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
        expect(services.getTemplateCreationCapabilities).not.toHaveBeenCalled();
        await store.acknowledge('9', 'other-key', confirmed.receipt!.id); expect(store.readPending('9')).toBeDefined();
        await store.acknowledge('9', 'stable-key', confirmed.receipt!.id); expect(store.readPending('9')).toBeNull();
    });
    it('preserves deleted tombstones as receipts rather than creating alternatives', async () => {
        vi.mocked(services.addDurableTransactionTemplate).mockResolvedValue(result(request(), { deleted: true, editable: false }));
        const store = useTemplateCreationStore();
        expect((await store.confirm(request(), null)).receipt).toEqual({ id: '9007199254740997', deleted: true });
        await store.retry('9', 'stable-key'); expect(services.addDurableTransactionTemplate).toHaveBeenCalledTimes(1);
    });
    it.each([3, 4])('rechecks role %s before replaying an unknown write', async permission => {
        vi.mocked(services.addDurableTransactionTemplate).mockRejectedValueOnce(new Error('Lost response'));
        const store = useTemplateCreationStore(); await expect(store.confirm(request(), null)).rejects.toThrow();
        role(permission); await expect(store.retry('9', 'stable-key')).rejects.toThrow('无法管理');
        expect(services.addDurableTransactionTemplate).toHaveBeenCalledTimes(1);
    });
    it('rejects a late reply after identity changes without writing to the new account', async () => {
        vi.mocked(services.addDurableTransactionTemplate).mockImplementationOnce(async body => { disk.identity = 'other-server/user'; return result(body); });
        await expect(useTemplateCreationStore().confirm(request(), null)).rejects.toThrow('账号已变化');
        expect(disk.pending.size).toBe(1); expect([...disk.pending.values()][0]?.receipt).toBeUndefined();
    });
    it('records the receipt in its original ledger after selection changes during HTTP', async () => {
        vi.mocked(services.addDurableTransactionTemplate).mockImplementationOnce(async body => { useLedgersStore().selectedLedgerId = '10'; return result(body); });
        const store = useTemplateCreationStore(); await store.confirm(request(), null);
        expect(store.readPending('9')?.receipt?.id).toBe('9007199254740997'); expect(store.readPending('10')).toBeNull();
    });
    it('isolates default/A/B ledgers and servers while keeping stable keys', async () => {
        const store = useTemplateCreationStore();
        for (const id of ['0', '9', '10']) { useLedgersStore().selectedLedgerId = id; await store.confirm(request(id), null); }
        expect(disk.pending.size).toBe(3); disk.identity = 'other-server/user'; expect(store.readPending('10')).toBeNull();
    });
    it('preserves unfinished raw input without sending and detects another-page draft edits', async () => {
        const store = useTemplateCreationStore(); await store.saveDraft(draft(), null);
        expect(store.readDraft('9', 1)?.input.sourceAmount).toBe('1.');
        expect(store.readDraft('9', 1)?.input.destinationAmount).toBe('');
        expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
        await store.saveDraft(draft('draft-2'), 'draft-1');
        await expect(store.confirm(request(), 'draft-1')).rejects.toThrow('草稿已变化');
        await expect(store.saveDraft(draft('draft-3'), 'draft-1')).rejects.toThrow('另一个页面');
        await expect(store.discardDraft('9', 1, 'draft-1')).rejects.toThrow('草稿已变化');
        await store.discardDraft('9', 1, 'draft-2'); expect(store.readDraft('9', 1)).toBeNull();
    });
    it('locks the draft while a request is unknown and clears only the confirmed revision', async () => {
        const store = useTemplateCreationStore(); await store.saveDraft(draft(), null); await store.confirm(request(), 'draft-1');
        await expect(store.saveDraft(draft('draft-2'), 'draft-1')).rejects.toThrow('待确认');
        await expect(store.discardDraft('9', 1, 'draft-1')).rejects.toThrow('不能丢弃');
        const key = `${disk.identity}/9/1`; disk.drafts.set(key, draft('future-edit'));
        await store.acknowledge('9', 'stable-key', '9007199254740997'); expect(store.readDraft('9', 1)?.revision).toBe('future-edit');
    });
    it('blocks malformed stored requests instead of replacing the key', async () => {
        disk.pending.set(`${disk.identity}/9`, { version: 1, identity: disk.identity, draftRevision: null, request: { ...request(), ledgerId: '10' } });
        await expect(useTemplateCreationStore().confirm(request(), null)).rejects.toThrow();
        expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
    });
    it.each([
        { clientSessionId: '密'.repeat(43) }, { sourceAmount: 0.5 }, { name: ' ' }, { categoryId: '9007199254740993.1' },
        { scheduledFrequencyType: 3, scheduledFrequency: '2' }, { scheduledFrequencyType: 1, scheduledFrequency: '7' },
        { scheduledFrequencyType: 2, scheduledFrequency: '0' }, { scheduledFrequencyType: 4, scheduledFrequency: '230' },
        { scheduledFrequencyType: 5, scheduledFrequency: '0' }, { scheduledFrequencyType: 5, scheduledFrequency: '01' },
        { scheduledFrequencyType: 5, scheduledFrequency: '9223372036854775808' }, { scheduledStartDate: '2026-02-30' },
        { scheduledStartDate: '2026-10-06', scheduledEndDate: '2026-10-05' },
    ])('does not freeze an invalid confirmation: %j', async change => {
        await expect(useTemplateCreationStore().confirm({ ...request('9', 2), ...change }, null)).rejects.toThrow();
        expect(disk.pending.size).toBe(0); expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
    });
    it('binds live membership checks to the captured username without global stale-login error handling', async () => {
        await useTemplateCreationStore().confirm(request(), null);
        expect(services.listLedgerMembers).toHaveBeenCalledWith({ ledgerId: '9', expectedUsername: 'synthetic-user', ignoreError: true });
    });
    it('does not revive an old confirmation after leaving and returning during permission verification', async () => {
        let release!: (value: never) => void;
        vi.mocked(services.listLedgerMembers).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
        let active = true, generation = 0;
        const ticket = generation;
        const saving = useTemplateCreationStore().confirm(request(), null, () => active && generation === ticket);
        active = false; generation++; active = true; generation++;
        release(response([{ id: '41', ledgerId: '9', uid: '51', role: 1, status: 1, isCurrentUser: true }]));
        await expect(saving).rejects.toMatchObject({ stale: true });
        expect(disk.pending.size).toBe(0); expect(services.getTemplateCreationCapabilities).not.toHaveBeenCalled(); expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
    });
    it('rechecks the original page after a queued journal lock before persisting or sending confirmation', async () => {
        let release!: () => void, entered!: () => void;
        disk.lockBarrier = new Promise(resolve => { release = resolve; });
        const waiting = new Promise<void>(resolve => { entered = resolve; }); disk.lockEntered = entered;
        let active = true;
        const saving = useTemplateCreationStore().confirm(request(), null, () => active);
        await waiting; active = false; release();
        await expect(saving).rejects.toMatchObject({ stale: true });
        expect(disk.pending.size).toBe(0); expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
    });
    it('retains the immutable request without HTTP after leaving during capability verification', async () => {
        let release!: (value: never) => void, entered!: () => void;
        const waiting = new Promise<void>(resolve => { entered = resolve; });
        vi.mocked(services.getTemplateCreationCapabilities).mockImplementationOnce(() => { entered(); return new Promise(resolve => { release = resolve; }); });
        let active = true;
        const store = useTemplateCreationStore(), saving = store.confirm(request(), null, () => active);
        await waiting; active = false; release(response(capabilities));
        await expect(saving).rejects.toMatchObject({ stale: true });
        expect(store.readPending('9')?.request).toEqual(request()); expect(store.readPending('9')?.receipt).toBeUndefined();
        expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
        active = true; await store.retry('9', 'stable-key', () => active);
        expect(services.addDurableTransactionTemplate).toHaveBeenCalledTimes(1);
    });
    it('still stores a valid receipt after an already sent POST outlives its page', async () => {
        let release!: (value: never) => void, entered!: () => void;
        const waiting = new Promise<void>(resolve => { entered = resolve; });
        vi.mocked(services.addDurableTransactionTemplate).mockImplementationOnce(() => { entered(); return new Promise(resolve => { release = resolve; }); });
        let active = true;
        const store = useTemplateCreationStore(), saving = store.confirm(request(), null, () => active);
        await waiting; active = false; release(result());
        const saved = await saving;
        expect(saved.receipt?.id).toBe('9007199254740997'); expect(store.readPending('9')?.receipt).toEqual(saved.receipt);
        expect(services.addDurableTransactionTemplate).toHaveBeenCalledTimes(1);
    });
});
