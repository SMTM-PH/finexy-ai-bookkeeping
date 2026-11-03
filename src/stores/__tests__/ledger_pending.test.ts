import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { AIReviewLedgerItem, PendingAIReviewConfirm, PendingAIReviewCreate, AIReviewEditorDraft } from '@/models/ai_review_ledger.ts';
import type { AIReviewSourceDraft } from '@/models/ai_review_source.ts';
import type { PendingTransactionCreate, TransactionDraft } from '@/models/transaction.ts';
import type { PendingTemplateCreate, TemplateEditorDraft } from '@/models/template_creation.ts';
import { fullReceiptCorners } from '@/models/ai_review_source.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import { ledgerPendingDraftRevision, ledgerPendingTargetKey } from '@/lib/ledger_pending.ts';

const disk = vi.hoisted(() => ({ server: 'https://synthetic.invalid/a', username: 'synthetic-owner', loggedIn: true,
    state: new Map<string, unknown>(), failures: new Set<string>() }));
vi.mock('@/lib/userstate.ts', () => {
    const read = (kind: string, ledgerId: string, variant = '') => {
        if (disk.failures.has(kind)) throw new Error('Synthetic inaccessible record');
        return disk.state.get(JSON.stringify([disk.server, disk.username, ledgerId, kind, variant])) ?? null;
    };
    return { getTransactionStateIdentity: () => JSON.stringify([disk.server, disk.username]), getCurrentUserInfo: () => ({ username: disk.username }), isUserLogined: () => disk.loggedIn,
        inspectUserTransactionDraft: (id: string) => read('transactionDraft', id), getUserPendingTransactionCreate: (id: string) => read('transactionPending', id),
        getUserTemplateEditorDraft: (id: string, type: number) => read('templateDraft', id, String(type)), getUserPendingTemplateCreate: (id: string) => read('templatePending', id),
        getUserPendingAIReviewCreate: (id: string) => read('aiCreate', id), getUserPendingAIReviewConfirm: (id: string) => read('aiConfirm', id),
        getUserAIReviewEditorDraft: (id: string) => read('aiEditor', id), getUserAIReviewSourceDraft: (id: string) => read('aiSource', id) };
});
vi.mock('@/stores/ledger.ts', async () => {
    const { defineStore } = await import('pinia'); const { ref } = await import('vue');
    return { useLedgersStore: defineStore('pendingTestLedger', () => ({ selectedLedgerId: ref('9') })) };
});
vi.mock('@/stores/user.ts', async () => {
    const { defineStore } = await import('pinia'); const { ref } = await import('vue');
    return { useUserStore: defineStore('pendingTestUser', () => ({ currentUserBasicInfo: ref({ username: 'synthetic-owner' }) })) };
});
vi.mock('@/lib/services.ts', () => ({ default: { getProfile: vi.fn(), listLedgerMembers: vi.fn(), getAIReviewCapabilities: vi.fn(), listLedgerAIReviewItems: vi.fn(), listScheduledOccurrences: vi.fn(),
    addTransaction: vi.fn(), addDurableTransactionTemplate: vi.fn(), createLedgerAIReviewItem: vi.fn(), confirmLedgerAIReviewItem: vi.fn(), confirmScheduledOccurrence: vi.fn() } }));
import services from '@/lib/services.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useUserStore } from '@/stores/user.ts';
import { useLedgerPendingStore } from '../ledgerPending.ts';

const response = (result: unknown) => ({ data: { success: true, result } }) as never;
const identity = () => JSON.stringify([disk.server, disk.username]);
const capability = (ledgerId = '9') => ({ ledgerId, contractVersion: 1, durableCreation: true, atomicConfirmation: true, payloadBinding: true, deletedReceipts: true,
    maxClientSessionIdBytes: 128, maxPageSize: 100, pagination: 'beforeId', reviewerScope: 'manager-or-creator', confirmationPath: 'v1/ai/review/confirm.json' });
const tx = (ledgerId = '9') => ({ ledgerId, clientSessionId: 'synthetic-stable-transaction', type: 3, time: 1791000000, utcOffset: 480,
    categoryId: '31', sourceAccountId: '21', destinationAccountId: '0', sourceAmount: -123, destinationAmount: 0, hideAmount: true, tagIds: [], pictureIds: [], comment: 'Synthetic private note' });
const item = (id = '9007199254740997', ledgerId = '9'): AIReviewLedgerItem => ({ ledgerId, id, creatorUid: '11', confirmedUid: '0', transactionId: '0', sourceType: 1,
    sourceText: 'Synthetic private source', failureReason: '', createdUnixTime: 1791000000, status: 1, editable: true, deleted: false });
const occurrence = (templateId = '9007199254740998', scheduledUnixTime = 1791000000, ledgerId = '9') => ({ ledgerId, templateId, scheduledUnixTime, status: 1, transactionId: '0', snapshot: {
    name: 'Synthetic schedule', ...tx(ledgerId), type: 3, sourceAmount: 123 } });
function put(kind: string, value: unknown, variant = '', ledgerId = '9') { disk.state.set(JSON.stringify([disk.server, disk.username, ledgerId, kind, variant]), value); }
function remove(kind: string, variant = '', ledgerId = '9') { disk.state.delete(JSON.stringify([disk.server, disk.username, ledgerId, kind, variant])); }
function targetFor(store: ReturnType<typeof useLedgerPendingStore>, kind: LedgerPendingTarget['kind'], recovery?: 'pending' | 'draft'): LedgerPendingTarget {
    const target = Object.values(store.summaryFor()!.sections).flatMap(section => section.entries).flatMap(entry => entry.targets)
        .find(value => value.kind === kind && (!recovery || 'recovery' in value && value.recovery === recovery));
    expect(target).toBeDefined(); return target!;
}
const create = (): PendingAIReviewCreate => ({ version: 1, identity: identity(), draftRevision: 'source-revision',
    request: { ledgerId: '9', clientSessionId: 'synthetic-create', sourceType: 1, sourceText: 'Synthetic private source', failureReason: '' } });
const confirm = (): PendingAIReviewConfirm => ({ version: 1, identity: identity(), draftRevision: 'editor-revision', request: { ledgerId: '9', id: item().id, transaction: tx() } });
const sourceDraft = (): AIReviewSourceDraft => ({ version: 1, identity: identity(), revision: 'source-revision', failureReason: '',
    input: { ledgerId: '9', sourceType: 1, sourceText: 'Synthetic private source', corners: fullReceiptCorners(), rotation: 0 } });
const editorDraft = (): AIReviewEditorDraft => ({ version: 1, identity: identity(), revision: 'editor-revision', input: { ledgerId: '9', id: item().id, transaction: { ...tx(), pictures: [] },
    categorySelections: { expense: '31', income: '', transfer: '' }, transferDirection: 'internal' } });
const template = (): PendingTemplateCreate => ({ version: 1, identity: identity(), draftRevision: 'template-revision', request: { ...tx(), name: 'Synthetic template', templateType: 1, clientSessionId: 'synthetic-template' } });
const templateDraft = (revision = 'template-revision', templateType: 1 | 2 = 1): TemplateEditorDraft => ({ version: 1, revision, input: { ...tx(), name: '', sourceAmount: '1+', destinationAmount: '', templateType } });
function deferred() { let resolve!: (value: never) => void; const promise = new Promise<never>(done => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
    vi.resetAllMocks(); setActivePinia(createPinia()); disk.server = 'https://synthetic.invalid/a'; disk.username = 'synthetic-owner'; disk.loggedIn = true; disk.state.clear(); disk.failures.clear();
    vi.mocked(services.listLedgerMembers).mockImplementation(async ({ ledgerId }) => response([{ ledgerId, id: '101', uid: '11', role: 1, status: 1, isCurrentUser: true }]));
    vi.mocked(services.getAIReviewCapabilities).mockImplementation(async ledgerId => response(capability(ledgerId)));
    vi.mocked(services.getProfile).mockImplementation(async () => response({ username: disk.username }));
    vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response([])); vi.mocked(services.listScheduledOccurrences).mockResolvedValue(response([]));
});

describe('current-ledger pending reads and minimal navigation metadata', () => {
    it('publishes real zero only after every remote and local source completes', async () => {
        const store = useLedgerPendingStore(), held = deferred(); vi.mocked(services.listLedgerAIReviewItems).mockReturnValueOnce(held.promise);
        const read = store.refresh(); await vi.waitFor(() => expect(services.listLedgerAIReviewItems).toHaveBeenCalled());
        expect(store.summaryFor()?.exactTotal).toBeNull(); expect(store.summaryFor()?.sections.ai.count).toBeNull();
        held.resolve(response([])); await read;
        expect(store.summaryFor()?.exactTotal).toBe(0); expect(store.summaryFor()?.sections.ai.count).toBe(0);
    });
    it('merges one known AI object across server row, create receipt, confirm receipt, editor and source draft', async () => {
        put('aiCreate', { ...create(), receipt: { id: item().id, deleted: false } }); put('aiConfirm', { ...confirm(), receipt: { ledgerId: '9', id: item().id, transactionId: '9007199254740999' } });
        put('aiEditor', editorDraft()); put('aiSource', sourceDraft()); vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response([item()]));
        const store = useLedgerPendingStore(); await store.refresh(); const summary = store.summaryFor()!;
        expect(summary.exactTotal).toBe(1); expect(summary.sections.ai.entries[0]?.state).toBe('receipt'); expect(summary.sections.local.count).toBe(0);
        expect(summary.sections.ai.entries[0]?.sources).toHaveLength(5); expect(summary.sections.ai.entries[0]?.targets).toHaveLength(3);
        expect(JSON.stringify(summary)).not.toMatch(/Synthetic private|sourceAmount|sourceText|transactionId|request/);
    });
    it('keeps an unknown create and its matching source draft as one local recovery without inventing a server ID', async () => {
        put('aiCreate', create()); put('aiSource', sourceDraft()); const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.exactTotal).toBe(1); expect(store.summaryFor()?.sections.local.entries[0]?.state).toBe('unknown-result');
        expect(store.summaryFor()?.sections.local.entries[0]?.targets).toEqual([
            { kind: 'ai-source', ledgerId: '9', recovery: 'pending', clientSessionId: 'synthetic-create', draftRevision: 'source-revision' },
            { kind: 'ai-source', ledgerId: '9', recovery: 'draft', draftRevision: 'source-revision' }
        ]);
    });
    it('preserves a newer unsubmitted source draft as a distinct operation', async () => {
        put('aiCreate', create()); put('aiSource', { ...sourceDraft(), revision: 'newer-source' }); const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.exactTotal).toBe(2);
    });
    it('deduplicates normal pending and matching optional draft, and distinguishes a known receipt', async () => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx(), receipt: { id: '9007199254740999' } } satisfies PendingTransactionCreate);
        put('transactionDraft', tx() satisfies TransactionDraft); const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.sections.local.count).toBe(1); expect(store.summaryFor()?.sections.local.entries[0]?.state).toBe('receipt');
        expect(store.summaryFor()?.sections.local.entries[0]?.sources).toEqual(['transactionPending', 'transactionDraft']);
    });
    it('keeps different normal draft keys separate and older journals without receipt unknown', async () => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx() }); put('transactionDraft', { ...tx(), clientSessionId: 'newer-draft' });
        const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.sections.local.count).toBe(2); expect(store.summaryFor()?.sections.local.entries[0]?.state).toBe('unknown-result');
    });
    it('merges matching template revisions while preserving another kind and newer edits', async () => {
        put('templatePending', template()); put('templateDraft', templateDraft(), '1'); put('templateDraft', templateDraft('scheduled-other', 2), '2');
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sections.local.count).toBe(2);
        expect(store.summaryFor()?.sections.local.entries[0]?.targets).toEqual([
            { kind: 'template-create', templateType: 1, ledgerId: '9', recovery: 'pending', clientSessionId: 'synthetic-template', draftRevision: 'template-revision' },
            { kind: 'template-create', templateType: 1, ledgerId: '9', recovery: 'draft', draftRevision: 'template-revision' }
        ]);
        put('templateDraft', templateDraft('newer'), '1'); store.refreshLocal(); expect(store.summaryFor()?.sections.local.count).toBe(3);
    });
    it('reads all large-ID AI cursor pages before publishing a complete count', async () => {
        const rows = Array.from({ length: 101 }, (_, index) => item(String(9007199254741999n - BigInt(index))));
        vi.mocked(services.listLedgerAIReviewItems).mockImplementation(async request => response(request.beforeId === '0' ? rows.slice(0, 100) : rows.slice(100)));
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sections.ai.count).toBe(101);
        expect(services.listLedgerAIReviewItems).toHaveBeenLastCalledWith(expect.objectContaining({ beforeId: rows[99]!.id, ledgerId: '9' }), disk.username);
    });
    it('keeps a failed later page unknown and never publishes its first hundred as complete', async () => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValueOnce(response(Array.from({ length: 100 }, (_, index) => item(String(1000 - index))))).mockRejectedValueOnce(new Error('Synthetic network failure'));
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sections.ai.count).toBeNull();
        expect(store.summaryFor()?.sections.ai.entries).toEqual([]); expect(store.summaryFor()?.exactTotal).toBeNull();
    });
    it.each(['duplicate', 'wrong-ledger', 'wrong-status'] as const)('rejects invalid AI %s without false zero', async kind => {
        const rows = kind === 'duplicate' ? [item(), item()] : [kind === 'wrong-ledger' ? item(undefined, '10') : { ...item(), status: 3 }];
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response(rows)); const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.sourceStates.ai.state).toBe('error'); expect(store.summaryFor()?.sourceStates.ai.count).toBeNull();
    });
    it('uses exact periodic occurrence tuples and only the pending status', async () => {
        vi.mocked(services.listScheduledOccurrences).mockResolvedValue(response([occurrence('91', 1791000000), occurrence('91', 1791086400)]));
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sections.periodic.count).toBe(2);
        expect(services.listScheduledOccurrences).toHaveBeenCalledWith({ ledgerId: '9', status: 1, offset: 0, limit: 100, expectedUsername: disk.username, ignoreError: true });
    });
    it('rejects a repeated periodic occurrence instead of accepting an offset race as a complete list', async () => {
        vi.mocked(services.listScheduledOccurrences).mockResolvedValue(response([occurrence(), occurrence()])); const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.sections.periodic.count).toBeNull(); expect(store.summaryFor()?.sourceStates.periodic.state).toBe('error');
    });
    it.each([401, 403, 404, 405, 500])('retains local recovery when remote queues fail with HTTP %i', async status => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx() }); vi.mocked(services.listLedgerMembers).mockRejectedValue({ response: { status, data: { errorMessage: 'Synthetic private financial content' } } });
        const store = useLedgerPendingStore(); await store.refresh(); const summary = store.summaryFor()!;
        expect(summary.exactTotal).toBeNull(); expect(summary.sections.local.count).toBe(1); expect(summary.sourceStates.ai.state).toBe(status === 401 || status === 403 ? 'denied' : status === 404 || status === 405 ? 'unavailable' : 'error');
        expect(JSON.stringify(summary)).not.toContain('Synthetic private');
    });
    it('distinguishes an old AI capability contract from a successfully empty queue', async () => {
        vi.mocked(services.getAIReviewCapabilities).mockResolvedValue(response({ ...capability(), atomicConfirmation: false }));
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sourceStates.ai.state).toBe('unavailable');
        expect(store.summaryFor()?.sourceStates.ai.count).toBeNull(); expect(services.listLedgerAIReviewItems).not.toHaveBeenCalled();
    });
    it('preserves other valid local entries when a draft is locked, damaged or an unpartitioned legacy record', async () => {
        disk.failures.add('transactionDraft'); put('aiSource', sourceDraft()); const store = useLedgerPendingStore(); await store.refresh();
        expect(store.summaryFor()?.sourceStates.transactionDraft.state).toBe('error'); expect(store.summaryFor()?.sourceStates.transactionDraft.count).toBeNull();
        expect(store.summaryFor()?.knownCount).toBe(1); expect(store.summaryFor()?.exactTotal).toBeNull();
    });
    it('rejects wrong-identity journals and invalid receipt IDs without treating them as absent', async () => {
        put('aiCreate', { ...create(), identity: 'different-server-user' }); put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx(), receipt: { id: 9007199254740993 } });
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sourceStates.aiCreate.state).toBe('error');
        expect(store.summaryFor()?.sourceStates.transactionPending.state).toBe('error'); expect(store.summaryFor()?.exactTotal).toBeNull();
    });
    it('allows a viewer to read but never offers aggregation as a write operation', async () => {
        vi.mocked(services.listLedgerMembers).mockResolvedValue(response([{ ledgerId: '9', id: '101', uid: '11', role: 4, status: 1, isCurrentUser: true }]));
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response([item()])); const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.memberRole).toBe(4);
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
        expect(services.createLedgerAIReviewItem).not.toHaveBeenCalled(); expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled(); expect(services.confirmScheduledOccurrence).not.toHaveBeenCalled();
    });
    it('keeps implicit ledger zero explicit in requests and does not borrow shared-ledger records', async () => {
        put('aiSource', sourceDraft()); useLedgersStore().selectedLedgerId = '0'; const store = useLedgerPendingStore(); await store.refresh('0');
        expect(store.summaryFor('0')?.exactTotal).toBe(0); expect(services.listLedgerMembers).not.toHaveBeenCalled();
        expect(services.getAIReviewCapabilities).toHaveBeenCalledWith('0', disk.username);
    });
    it('clears synchronously on a ledger switch and discards the preceding pending response', async () => {
        const held = deferred(); vi.mocked(services.listLedgerAIReviewItems).mockReturnValueOnce(held.promise);
        const store = useLedgerPendingStore(), old = store.refresh(); await vi.waitFor(() => expect(services.listLedgerAIReviewItems).toHaveBeenCalled());
        useLedgersStore().selectedLedgerId = '10'; expect(store.summaryFor('9')).toBeNull(); await store.refresh('10');
        held.resolve(response([item()])); await old; expect(store.summaryFor('10')?.exactTotal).toBe(0);
    });
    it.each(['server', 'account', 'logout', 'reset'] as const)('never exposes late rows after %s changes', async kind => {
        const held = deferred(); vi.mocked(services.listLedgerAIReviewItems).mockReturnValueOnce(held.promise); const store = useLedgerPendingStore(), old = store.refresh();
        await vi.waitFor(() => expect(services.listLedgerAIReviewItems).toHaveBeenCalled());
        if (kind === 'server') disk.server += '/other'; else if (kind === 'account') { disk.username = 'other'; useUserStore().currentUserBasicInfo = { username: 'other' } as never; }
        else if (kind === 'logout') disk.loggedIn = false; else store.reset();
        expect(store.summaryFor()).toBeNull(); held.resolve(response([item()])); await old; expect(store.summaryFor()).toBeNull();
    });
    it('does not let an older refresh overwrite a newer queue result', async () => {
        const held = deferred(); vi.mocked(services.listLedgerAIReviewItems).mockReturnValueOnce(held.promise).mockResolvedValue(response([item('42')]));
        const store = useLedgerPendingStore(), old = store.refresh(); await vi.waitFor(() => expect(services.listLedgerAIReviewItems).toHaveBeenCalled()); await store.refresh();
        held.resolve(response([item('43')])); await old; expect(store.summaryFor()?.sections.ai.entries[0]?.key).toBe('ai:42');
    });
    it('projects a local refresh without reloading server queues or sending financial HTTP', async () => {
        const store = useLedgerPendingStore(); await store.refresh(); put('aiSource', sourceDraft()); store.refreshLocal();
        expect(store.summaryFor()?.exactTotal).toBe(1); expect(services.listLedgerAIReviewItems).toHaveBeenCalledTimes(1); expect(services.listScheduledOccurrences).toHaveBeenCalledTimes(1);
    });
    it('fails closed at the queue bound rather than publishing a truncated count', async () => {
        vi.mocked(services.listLedgerAIReviewItems).mockImplementation(async request => {
            const start = request.beforeId === '0' ? 20000 : Number(request.beforeId) - 1;
            return response(Array.from({ length: 100 }, (_, index) => item(String(start - index))));
        });
        const store = useLedgerPendingStore(); await store.refresh(); expect(store.summaryFor()?.sourceStates.ai.state).toBe('error');
        expect(store.summaryFor()?.sections.ai.count).toBeNull(); expect(store.summaryFor()?.sections.ai.entries).toEqual([]);
        expect(services.listLedgerAIReviewItems).toHaveBeenCalledTimes(101);
    });
});

describe('fresh read-only pending navigation authorization', () => {
    it('checks a viewer live and locates the original pending request without replaying or acknowledging it', async () => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx(), receipt: { id: '9007199254740999' } });
        const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'transaction-create', 'pending');
        vi.mocked(services.listLedgerMembers).mockResolvedValue(response([{ ledgerId: '9', id: '101', uid: '11', role: 4, status: 1, isCurrentUser: true }]));
        await store.verifyTarget(target);
        expect(services.listLedgerMembers).toHaveBeenLastCalledWith({ ledgerId: '9', expectedUsername: disk.username, ignoreError: true });
        expect(store.summaryFor()?.memberRole).toBe(4);
        expect(services.listLedgerAIReviewItems).toHaveBeenCalledTimes(1); expect(services.listScheduledOccurrences).toHaveBeenCalledTimes(1);
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.addDurableTransactionTemplate).not.toHaveBeenCalled();
        expect(services.createLedgerAIReviewItem).not.toHaveBeenCalled(); expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
        expect(services.confirmScheduledOccurrence).not.toHaveBeenCalled();
        expect(store.summaryFor()?.sections.local.entries[0]?.state).toBe('receipt');
    });
    it('checks the live default-ledger login using a profile, preserving explicit zero', async () => {
        useLedgersStore().selectedLedgerId = '0';
        put('transactionPending', { clientSessionId: tx('0').clientSessionId, request: tx('0') }, '', '0');
        const store = useLedgerPendingStore(); await store.refresh('0'); await store.verifyTarget(targetFor(store, 'transaction-create'));
        expect(services.getProfile).toHaveBeenCalledWith({ expectedUsername: disk.username, ignoreError: true });
        expect(services.listLedgerMembers).not.toHaveBeenCalled(); expect(store.summaryFor()?.ledgerId).toBe('0');
    });
    it.each(['wrong-user', 'missing-result', 'expired'] as const)('rejects default-ledger %s authentication without a fallback', async reason => {
        useLedgersStore().selectedLedgerId = '0'; put('transactionPending', { clientSessionId: tx('0').clientSessionId, request: tx('0') }, '', '0');
        const store = useLedgerPendingStore(); await store.refresh('0'); const target = targetFor(store, 'transaction-create');
        if (reason === 'expired') vi.mocked(services.getProfile).mockRejectedValue({ response: { status: 401 } });
        else vi.mocked(services.getProfile).mockResolvedValue(response(reason === 'wrong-user' ? { username: 'other' } : undefined));
        await expect(store.verifyTarget(target)).rejects.toThrow('无法核验');
        expect(store.summaryFor()?.knownCount).toBe(1); expect(store.summaryFor()?.exactTotal).toBeNull(); expect(useLedgersStore().selectedLedgerId).toBe('0');
    });
    it('does not let an old notification select a removed or replacement ordinary pending journal', async () => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx() });
        const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'transaction-create');
        remove('transactionPending'); await expect(store.verifyTarget(target)).rejects.toThrow('原待处理'); expect(store.summaryFor()?.knownCount).toBe(0);
        put('transactionPending', { clientSessionId: 'replacement', request: { ...tx(), clientSessionId: 'replacement' } }); store.refreshLocal();
        await expect(store.verifyTarget(target)).rejects.toThrow('原待处理'); expect(store.summaryFor()?.knownCount).toBe(1);
    });
    it.each([true, false])('rejects a changed ordinary draft with stable-key present=%s without exposing financial content', async hasKey => {
        // Public timestamps and opaque hashes may contain the digits of a
        // private amount. Only the exact amount or its private fields are a leak.
        vi.spyOn(Date, 'now').mockReturnValue(1792999999000);
        const original = { ...tx(), clientSessionId: hasKey ? tx().clientSessionId : undefined };
        put('transactionDraft', original); const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'transaction-create', 'draft');
        expect(target).toMatchObject({ draftRevision: ledgerPendingDraftRevision(original) });
        put('transactionDraft', { ...original, sourceAmount: -999 }); await expect(store.verifyTarget(target)).rejects.toThrow('原待处理');
        const summary = store.summaryFor();
        const containsPrivateAmount = (value: unknown): boolean => value === -999 || value === '-999'
            || value !== null && typeof value === 'object' && Object.values(value).some(containsPrivateAmount);
        expect(summary?.knownCount).toBe(1); expect(JSON.stringify(summary)).not.toMatch(/Synthetic private|sourceAmount/);
        expect(containsPrivateAmount(summary)).toBe(false);
    });
    it('compares navigation keys independent of property order', async () => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx() }); const store = useLedgerPendingStore(); await store.refresh();
        const target: LedgerPendingTarget = { clientSessionId: tx().clientSessionId, ledgerId: '9', recovery: 'pending', kind: 'transaction-create' };
        expect(ledgerPendingTargetKey(target)).toBe(ledgerPendingTargetKey(targetFor(store, 'transaction-create'))); await store.verifyTarget(target);
    });
    it.each(['template-pending', 'template-draft', 'ai-pending', 'ai-draft'] as const)('rejects a replaced %s using its original stable locator', async kind => {
        if (kind === 'template-pending') put('templatePending', template());
        else if (kind === 'template-draft') put('templateDraft', templateDraft(), '1');
        else if (kind === 'ai-pending') put('aiCreate', create()); else put('aiSource', sourceDraft());
        const store = useLedgerPendingStore(); await store.refresh();
        const target = targetFor(store, kind.startsWith('template') ? 'template-create' : 'ai-source', kind.endsWith('pending') ? 'pending' : 'draft');
        if (kind === 'template-pending') put('templatePending', { ...template(), request: { ...template().request, clientSessionId: 'replacement-template' } });
        else if (kind === 'template-draft') put('templateDraft', templateDraft('replacement-revision'), '1');
        else if (kind === 'ai-pending') put('aiCreate', { ...create(), request: { ...create().request, clientSessionId: 'replacement-ai' } });
        else put('aiSource', { ...sourceDraft(), revision: 'replacement-revision' });
        await expect(store.verifyTarget(target)).rejects.toThrow('原待处理'); expect(store.summaryFor()?.knownCount).toBe(1);
    });
    it('retains unreadable local data as unknown and refuses its formerly observed target', async () => {
        put('aiSource', sourceDraft()); const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'ai-source');
        disk.failures.add('aiSource'); await expect(store.verifyTarget(target)).rejects.toThrow('原待处理');
        expect(store.summaryFor()?.sourceStates.aiSource.count).toBeNull(); expect(store.summaryFor()?.exactTotal).toBeNull();
    });
    it('retains local receipts when live membership is revoked and does not present a false zero', async () => {
        put('transactionPending', { clientSessionId: tx().clientSessionId, request: tx() }); const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'transaction-create');
        vi.mocked(services.listLedgerMembers).mockRejectedValue({ response: { status: 403, data: { errorMessage: 'Synthetic private financial content' } } });
        await expect(store.verifyTarget(target)).rejects.toThrow('读取权限');
        expect(store.summaryFor()?.memberRole).toBeNull(); expect(store.summaryFor()?.knownCount).toBe(1); expect(store.summaryFor()?.exactTotal).toBeNull();
        expect(store.summaryFor()?.sourceStates.ai.state).toBe('denied'); expect(JSON.stringify(store.summaryFor())).not.toContain('Synthetic private');
    });
    it.each(['missing-member', 'wrong-ledger', 'duplicate-member', 'inactive'] as const)('refuses malformed %s membership on navigation', async kind => {
        put('aiSource', sourceDraft()); const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'ai-source');
        const member = { ledgerId: '9', id: '101', uid: '11', role: 1, status: 1, isCurrentUser: true };
        vi.mocked(services.listLedgerMembers).mockResolvedValue(response(kind === 'missing-member' ? [] : kind === 'wrong-ledger' ? [{ ...member, ledgerId: '10' }] :
            kind === 'duplicate-member' ? [member, member] : [{ ...member, status: 2 }]));
        await expect(store.verifyTarget(target)).rejects.toThrow('读取权限'); expect(useLedgersStore().selectedLedgerId).toBe('9');
    });
    it('rejects a foreign ledger or invented target before making a permission request', async () => {
        put('aiSource', sourceDraft()); const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'ai-source');
        const count = vi.mocked(services.listLedgerMembers).mock.calls.length;
        await expect(store.verifyTarget({ ...target, ledgerId: '0' })).rejects.toThrow('原待处理');
        await expect(store.verifyTarget({ kind: 'ai-review', ledgerId: '9', id: '999' })).rejects.toThrow('原待处理');
        expect(services.listLedgerMembers).toHaveBeenCalledTimes(count);
    });
    it('re-reads a server-only AI object and refuses it once it is no longer pending', async () => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValueOnce(response([item()])).mockResolvedValue(response([]));
        const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'ai-review');
        await expect(store.verifyTarget(target)).rejects.toThrow('原待处理'); expect(store.summaryFor()?.sections.ai.count).toBe(0);
        expect(services.listLedgerAIReviewItems).toHaveBeenCalledTimes(2);
    });
    it('locates a known AI confirmation receipt without requiring its completed row to remain pending', async () => {
        put('aiConfirm', { ...confirm(), receipt: { ledgerId: '9', id: item().id, transactionId: '9007199254740999' } });
        const store = useLedgerPendingStore(); await store.refresh(); await store.verifyTarget(targetFor(store, 'ai-review'));
        expect(services.listLedgerAIReviewItems).toHaveBeenCalledTimes(1); expect(store.summaryFor()?.sections.ai.entries[0]?.state).toBe('receipt');
    });
    it('rechecks the exact periodic tuple instead of opening the next occurrence for the same template', async () => {
        vi.mocked(services.listScheduledOccurrences).mockResolvedValueOnce(response([occurrence()])).mockResolvedValue(response([occurrence(undefined, 1791086400)]));
        const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'periodic-review');
        await expect(store.verifyTarget(target)).rejects.toThrow('原待处理'); expect(store.summaryFor()?.sections.periodic.count).toBe(1);
    });
    it('keeps a failed navigation re-read unknown instead of acknowledging the old server row', async () => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValueOnce(response([item()])).mockRejectedValue({ response: { status: 500 } });
        const store = useLedgerPendingStore(); await store.refresh(); await expect(store.verifyTarget(targetFor(store, 'ai-review'))).rejects.toThrow('原待处理');
        expect(store.summaryFor()?.sections.ai.count).toBeNull(); expect(store.summaryFor()?.sections.ai.entries).toEqual([]);
    });
    it.each(['ledger', 'server', 'account', 'logout', 'refresh'] as const)('rejects navigation after %s changes during permission verification', async kind => {
        put('aiSource', sourceDraft()); const store = useLedgerPendingStore(); await store.refresh(); const target = targetFor(store, 'ai-source');
        const held = deferred(); vi.mocked(services.listLedgerMembers).mockReturnValueOnce(held.promise); const verification = store.verifyTarget(target);
        const rejected = expect(verification).rejects.toThrow();
        if (kind === 'ledger') { useLedgersStore().selectedLedgerId = '10'; await store.refresh('10'); }
        else if (kind === 'server') disk.server += '/changed';
        else if (kind === 'account') { disk.username = 'other'; useUserStore().currentUserBasicInfo = { username: 'other' } as never; }
        else if (kind === 'logout') disk.loggedIn = false; else await store.refresh();
        held.resolve(response([{ ledgerId: '9', id: '101', uid: '11', role: 1, status: 1, isCurrentUser: true }])); await rejected;
        if (kind === 'ledger') expect(store.summaryFor('10')?.exactTotal).toBe(0);
        else if (kind === 'refresh') expect(store.summaryFor()?.knownCount).toBe(1);
        else expect(store.summaryFor()).toBeNull();
    });
    it('does not overwrite a newer summary with an old navigation queue response', async () => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValueOnce(response([item()])); const store = useLedgerPendingStore(); await store.refresh();
        const target = targetFor(store, 'ai-review'), held = deferred(); vi.mocked(services.listLedgerAIReviewItems).mockReturnValueOnce(held.promise);
        const verification = store.verifyTarget(target), rejected = expect(verification).rejects.toThrow();
        await vi.waitFor(() => expect(services.listLedgerAIReviewItems).toHaveBeenCalledTimes(2));
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response([item('42')])); await store.refresh();
        held.resolve(response([item('43')])); await rejected; expect(store.summaryFor()?.sections.ai.entries[0]?.key).toBe('ai:42');
    });
});
