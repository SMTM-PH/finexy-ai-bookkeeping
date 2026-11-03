import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { AIReviewConfirmRequest, AIReviewLedgerItem, LedgerAIReviewCreateRequest, PendingAIReviewConfirm, PendingAIReviewCreate, AIReviewEditorDraft } from '@/models/ai_review_ledger.ts';
import { fullReceiptCorners, type AIReviewSourceDraft } from '@/models/ai_review_source.ts';

const disk = vi.hoisted(() => ({ identity: 'synthetic-server/owner', username: 'synthetic-owner', fail: false,
    creates: new Map<string, PendingAIReviewCreate>(), confirms: new Map<string, PendingAIReviewConfirm>(), drafts: new Map<string, AIReviewEditorDraft>(), sources: new Map<string, AIReviewSourceDraft>() }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => disk.identity, getCurrentUserInfo: () => ({ username: disk.username }),
    withTransactionCreateLock: async (action: () => unknown) => action(),
    getUserPendingAIReviewCreate: (ledger: string) => disk.creates.get(`${disk.identity}/${ledger}`) ?? null,
    updateUserPendingAIReviewCreate: (ledger: string, value: PendingAIReviewCreate) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.creates.set(`${disk.identity}/${ledger}`, JSON.parse(JSON.stringify(value)));
    },
    clearUserPendingAIReviewCreate: (ledger: string) => { disk.creates.delete(`${disk.identity}/${ledger}`); },
    getUserPendingAIReviewConfirm: (ledger: string) => disk.confirms.get(`${disk.identity}/${ledger}`) ?? null,
    updateUserPendingAIReviewConfirm: (ledger: string, value: PendingAIReviewConfirm) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.confirms.set(`${disk.identity}/${ledger}`, JSON.parse(JSON.stringify(value)));
    },
    clearUserPendingAIReviewConfirm: (ledger: string) => { disk.confirms.delete(`${disk.identity}/${ledger}`); },
    getUserAIReviewEditorDraft: (ledger: string) => disk.drafts.get(`${disk.identity}/${ledger}`) ?? null,
    updateUserAIReviewEditorDraft: (ledger: string, draft: AIReviewEditorDraft) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.drafts.set(`${disk.identity}/${ledger}`, JSON.parse(JSON.stringify(draft)));
    },
    clearUserAIReviewEditorDraft: (ledger: string) => { disk.drafts.delete(`${disk.identity}/${ledger}`); },
    getUserAIReviewSourceDraft: (ledger: string) => disk.sources.get(`${disk.identity}/${ledger}`) ?? null,
    updateUserAIReviewSourceDraft: (ledger: string, value: AIReviewSourceDraft) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.sources.set(`${disk.identity}/${ledger}`, JSON.parse(JSON.stringify(value)));
    },
    clearUserAIReviewSourceDraft: (ledger: string) => { disk.sources.delete(`${disk.identity}/${ledger}`); },
}));
vi.mock('../ledger.ts', async () => {
    const { reactive } = await import('vue'); const state = reactive({ selectedLedgerId: '9' });
    return { useLedgersStore: () => state };
});
vi.mock('../user.ts', async () => {
    const { reactive } = await import('vue'); const state = reactive({ currentUserBasicInfo: { username: 'synthetic-owner' } });
    return { useUserStore: () => state };
});
vi.mock('@/lib/services.ts', () => ({ default: {
    listLedgerMembers: vi.fn(), getAIReviewCapabilities: vi.fn(), listLedgerAIReviewItems: vi.fn(), getLedgerAIReviewItem: vi.fn(),
    createLedgerAIReviewItem: vi.fn(), confirmLedgerAIReviewItem: vi.fn(), changeLedgerAIReviewItem: vi.fn(),
    recognizeLedgerTransactionText: vi.fn(), recognizeLedgerReceiptImage: vi.fn(), addTransaction: vi.fn(), resolveAIReviewItem: vi.fn(),
} }));
import services from '@/lib/services.ts';
import { useAIReviewLedgerStore } from '../aiReviewLedger.ts';
import { useLedgersStore } from '../ledger.ts';
import { assertAIReviewConfirm, assertAIReviewCreate, assertAIReviewItem } from '@/models/ai_review_ledger.ts';

const response = (result: unknown) => ({ data: { success: true, result } }) as never;
const capabilities = { ledgerId: '9', contractVersion: 1, durableCreation: true, atomicConfirmation: true,
    payloadBinding: true, deletedReceipts: true, maxClientSessionIdBytes: 128, maxPageSize: 100,
    pagination: 'beforeId', reviewerScope: 'manager-or-creator', confirmationPath: 'v1/ai/review/confirm.json', recognitionLedgerContext: true };
const request = (): AIReviewConfirmRequest => ({ ledgerId: '9', id: '9007199254740997', transaction: {
    ledgerId: '9', type: 3, time: 1760000000, utcOffset: 480, categoryId: '31', sourceAccountId: '21', destinationAccountId: '0',
    sourceAmount: -123, destinationAmount: 0, hideAmount: true, tagIds: ['41'], pictureIds: ['51'],
    geoLocation: { latitude: 20.1, longitude: 30.2 }, comment: 'Synthetic refund', clientSessionId: 'stable-confirm' } });
const createRequest = (): LedgerAIReviewCreateRequest => ({ ledgerId: '9', clientSessionId: 'stable-create', sourceType: 1,
    sourceText: 'Synthetic original text', failureReason: '', recognizedData: { ledgerId: '9', type: 3, sourceAmount: -123 } });
const item = (extra: Partial<AIReviewLedgerItem> = {}): AIReviewLedgerItem => ({ id: request().id, ledgerId: '9', creatorUid: '11', transactionId: '0',
    confirmedUid: '0', editable: true, deleted: false, status: 1, sourceType: 1, sourceText: createRequest().sourceText,
    recognizedData: createRequest().recognizedData, failureReason: '', createdUnixTime: 1760000000, ...extra });
const receipt = () => ({ id: request().id, ledgerId: '9', transactionId: '9007199254740999' });
function role(value = 1) { vi.mocked(services.listLedgerMembers).mockImplementation(async ({ ledgerId }) => response([
    { id: '10', ledgerId, uid: '11', role: value, status: 1, isCurrentUser: true },
])); }
beforeEach(() => {
    vi.resetAllMocks(); setActivePinia(createPinia()); disk.creates.clear(); disk.confirms.clear(); disk.drafts.clear(); disk.sources.clear(); disk.fail = false;
    disk.identity = 'synthetic-server/owner'; disk.username = 'synthetic-owner'; useLedgersStore().selectedLedgerId = '9'; role();
    vi.mocked(services.getAIReviewCapabilities).mockResolvedValue(response(capabilities));
    vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response([]));
    vi.mocked(services.getLedgerAIReviewItem).mockResolvedValue(response(item()));
    vi.mocked(services.createLedgerAIReviewItem).mockResolvedValue(response(item()));
    vi.mocked(services.confirmLedgerAIReviewItem).mockResolvedValue(response(receipt()));
    vi.mocked(services.changeLedgerAIReviewItem).mockResolvedValue(response(true));
    vi.mocked(services.recognizeLedgerTransactionText).mockResolvedValue(response({ type: 3, ledgerId: '9', sourceAmount: -123 }));
    vi.mocked(services.recognizeLedgerReceiptImage).mockResolvedValue(response({ type: 3, ledgerId: '9', sourceAmount: -123 }));
});

const editorDraft = (revision = 'revision-a', id = request().id): AIReviewEditorDraft => ({ version: 1, identity: disk.identity, revision, input: {
    ledgerId: '9', id, rawSourceAmount: '=12+', rawDestinationAmount: 'unfinished', transferDirection: 'internal',
    categorySelections: { expense: '31', income: '', transfer: '' }, transaction: {
        ...request().transaction, timeZone: 'Asia/Shanghai', destinationAmount: -123, pictures: [{ pictureId: '51', originalUrl: 'synthetic-local-url' }],
    },
} });
const sourceDraft = (revision = 'source-a'): AIReviewSourceDraft => ({ version: 1, identity: disk.identity, revision, input: {
    ledgerId: '9', sourceType: 1, sourceText: createRequest().sourceText, corners: fullReceiptCorners(), rotation: 0,
}, recognizedData: createRequest().recognizedData, failureReason: '' });
describe('durable AI source drafts', () => {
    it('restores original source and recognition without a provider or creation call', async () => {
        await useAIReviewLedgerStore().saveSourceDraft(sourceDraft(), null);
        setActivePinia(createPinia());
        expect(useAIReviewLedgerStore().readSourceDraft('9')).toEqual(sourceDraft());
        expect(useAIReviewLedgerStore().readSourceDraft('10')).toBeNull();
        expect(services.recognizeLedgerTransactionText).not.toHaveBeenCalled(); expect(services.createLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('freezes the matching revision before a lost reply and acknowledges only the original receipt', async () => {
        const store = useAIReviewLedgerStore(); await store.saveSourceDraft(sourceDraft(), null);
        vi.mocked(services.createLedgerAIReviewItem).mockRejectedValueOnce(new Error('Synthetic lost reply'));
        await expect(store.create(createRequest(), 'source-a')).rejects.toThrow('重试原请求');
        expect(store.readPendingCreate('9')?.draftRevision).toBe('source-a');
        await expect(store.saveSourceDraft(sourceDraft('source-b'), 'source-a')).rejects.toThrow('待确认');
        await expect(store.discardSourceDraft('9', 'source-a')).rejects.toThrow('待确认');
        await store.retryCreate('9', 'stable-create');
        await store.acknowledgeCreate('9', 'other-key', item().id); expect(store.readSourceDraft('9')).not.toBeNull();
        await store.acknowledgeCreate('9', 'stable-create', item().id); expect(store.readSourceDraft('9')).toBeNull();
        expect(services.addTransaction).not.toHaveBeenCalled();
    });
    it('rejects a second page revision or mismatched source without any creation HTTP', async () => {
        const store = useAIReviewLedgerStore(); await store.saveSourceDraft(sourceDraft(), null);
        await expect(store.saveSourceDraft(sourceDraft('source-b'), null)).rejects.toThrow('另一页面');
        await expect(store.create(createRequest(), 'source-b')).rejects.toThrow('草稿已变化');
        await expect(store.create({ ...createRequest(), sourceText: 'Different original' }, 'source-a')).rejects.toThrow('快照不匹配');
        expect(services.createLedgerAIReviewItem).not.toHaveBeenCalled(); expect(store.readSourceDraft('9')).toEqual(sourceDraft());
    });
    it('storage denial and transplanted draft preserve the original and prevent replacement', async () => {
        const store = useAIReviewLedgerStore(); disk.fail = true;
        await expect(store.saveSourceDraft(sourceDraft(), null)).rejects.toThrow('Storage denied');
        expect(store.readSourceDraft('9')).toBeNull(); disk.fail = false;
        await store.saveSourceDraft(sourceDraft(), null); disk.sources.set(`${disk.identity}/10`, sourceDraft());
        expect(() => store.readSourceDraft('10')).toThrow('原账本');
        expect(store.readSourceDraft('9')).toEqual(sourceDraft());
    });
});
describe('ledger AI editor drafts', () => {
    it('keeps unfinished expressions and complete fields across store recreation without HTTP', async () => {
        const store = useAIReviewLedgerStore(), original = editorDraft();
        await store.saveDraft(original, null);
        original.input.transaction.tagIds!.push('42');
        setActivePinia(createPinia());
        expect(useAIReviewLedgerStore().readDraft('9')).toEqual(editorDraft());
        expect(useAIReviewLedgerStore().readDraft('10')).toBeNull();
        expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('rejects stale revision and another item without replacing original input', async () => {
        const store = useAIReviewLedgerStore(); await store.saveDraft(editorDraft(), null);
        await expect(store.saveDraft(editorDraft('revision-b'), null)).rejects.toThrow('另一个页面');
        await expect(store.saveDraft(editorDraft('revision-b', '888'), 'revision-a')).rejects.toThrow('另一个页面');
        await expect(store.confirm(request(), 'stale-revision')).rejects.toThrow('草稿已变化');
        expect(store.readDraft('9')).toEqual(editorDraft()); expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('freezes original draft revision before HTTP and clears only after matching receipt acknowledgement', async () => {
        const store = useAIReviewLedgerStore(); await store.saveDraft(editorDraft(), null);
        vi.mocked(services.confirmLedgerAIReviewItem).mockRejectedValueOnce(new Error('Synthetic lost response'));
        await expect(store.confirm(request(), 'revision-a')).rejects.toThrow('重试原请求');
        expect(store.readPendingConfirm('9')?.draftRevision).toBe('revision-a');
        await expect(store.discardDraft('9', request().id, 'revision-a')).rejects.toThrow('结果待确认');
        await expect(store.saveDraft(editorDraft('revision-b'), 'revision-a')).rejects.toThrow('结果待确认');
        await store.retryConfirm('9', request().id, 'stable-confirm');
        await store.acknowledgeConfirm('9', request().id, 'stable-confirm', '999');
        expect(store.readDraft('9')).not.toBeNull();
        await store.acknowledgeConfirm('9', request().id, 'stable-confirm', receipt().transactionId);
        expect(store.readDraft('9')).toBeNull(); expect(store.readPendingConfirm('9')).toBeNull();
    });
    it('storage failure leaves no new draft or confirmation and corrupt draft blocks replacement', async () => {
        const store = useAIReviewLedgerStore(); disk.fail = true;
        await expect(store.saveDraft(editorDraft(), null)).rejects.toThrow('Storage denied');
        expect(store.readDraft('9')).toBeNull(); disk.fail = false;
        disk.drafts.set(`${disk.identity}/9`, { ...editorDraft(), identity: 'other-account' });
        expect(() => store.readDraft('9')).toThrow('草稿损坏');
        await expect(store.confirm(request(), null)).rejects.toThrow('草稿损坏');
        expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('discard requires exact original item and revision, and affects only that local draft', async () => {
        const store = useAIReviewLedgerStore(); await store.saveDraft(editorDraft(), null);
        await expect(store.discardDraft('9', '888', 'revision-a')).rejects.toThrow('已变化');
        await expect(store.discardDraft('9', request().id, 'revision-b')).rejects.toThrow('已变化');
        await store.discardDraft('9', request().id, 'revision-a'); expect(store.readDraft('9')).toBeNull();
        expect(services.changeLedgerAIReviewItem).not.toHaveBeenCalled();
    });
});

describe('ledger AI journal', () => {
    it('persists before confirmation HTTP, freezes full fields and resumes after store recreation', async () => {
        const original = request();
        vi.mocked(services.confirmLedgerAIReviewItem).mockImplementationOnce(async body => {
            expect([...disk.confirms.values()][0]?.request).toEqual(body); throw { config: { private: 'synthetic secret' } };
        });
        await expect(useAIReviewLedgerStore().confirm(original)).rejects.toThrow('重试原请求');
        (original.transaction as { sourceAmount: number }).sourceAmount = 555;
        original.transaction.tagIds.push('42');
        setActivePinia(createPinia()); const restored = useAIReviewLedgerStore();
        await expect(restored.confirm({ ...request(), id: '999' })).rejects.toThrow('原请求');
        const result = await restored.retryConfirm('9', request().id, 'stable-confirm');
        expect(result.receipt).toEqual(receipt());
        expect(vi.mocked(services.confirmLedgerAIReviewItem).mock.calls.map(call => call[0])).toEqual([request(), request()]);
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.resolveAIReviewItem).not.toHaveBeenCalled();
    });
    it('persists creating source content and replays same key after lost response', async () => {
        vi.mocked(services.createLedgerAIReviewItem).mockImplementationOnce(async body => {
            expect([...disk.creates.values()][0]?.request).toEqual(body); throw new Error('Lost');
        });
        await expect(useAIReviewLedgerStore().create(createRequest())).rejects.toThrow('重试原请求');
        setActivePinia(createPinia()); const restored = useAIReviewLedgerStore();
        await expect(restored.create({ ...createRequest(), clientSessionId: 'replacement' })).rejects.toThrow('原请求');
        await restored.retryCreate('9', 'stable-create');
        expect(vi.mocked(services.createLedgerAIReviewItem).mock.calls.map(call => call[0])).toEqual([createRequest(), createRequest()]);
    });
    it('accepts server Unicode whitespace normalization while retaining original frozen source', async () => {
        const sourceText = '\u0085\ufeffSynthetic source\u0085';
        vi.mocked(services.createLedgerAIReviewItem).mockResolvedValue(response(item({ sourceText: '\ufeffSynthetic source' })));
        const store = useAIReviewLedgerStore(); await store.create({ ...createRequest(), sourceText });
        expect(store.readPendingCreate('9')?.request.sourceText).toBe(sourceText);
    });
    it.each([null, false, '', {}])('corrupt optional receipt cannot permit a fresh HTTP retry: %j', invalid => {
        disk.creates.set(`${disk.identity}/9`, { version: 1, identity: disk.identity, request: createRequest(), receipt: invalid } as PendingAIReviewCreate);
        disk.confirms.set(`${disk.identity}/9`, { version: 1, identity: disk.identity, request: request(), receipt: invalid } as PendingAIReviewConfirm);
        const store = useAIReviewLedgerStore(); expect(() => store.readPendingCreate('9')).toThrow(); expect(() => store.readPendingConfirm('9')).toThrow();
    });
    it.each(['create', 'confirm'] as const)('blocks %s HTTP when local persistence fails', async kind => {
        disk.fail = true; const store = useAIReviewLedgerStore();
        await expect(kind === 'create' ? store.create(createRequest()) : store.confirm(request())).rejects.toThrow('Storage denied');
        expect(services.createLedgerAIReviewItem).not.toHaveBeenCalled(); expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('retains unknown confirmation if recording a valid response fails', async () => {
        vi.mocked(services.confirmLedgerAIReviewItem).mockImplementationOnce(async () => { disk.fail = true; return response(receipt()); });
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow('Storage denied');
        expect(store.readPendingConfirm('9')?.receipt).toBeUndefined(); disk.fail = false;
        await store.retryConfirm('9', request().id, 'stable-confirm'); expect(services.confirmLedgerAIReviewItem).toHaveBeenCalledTimes(2);
    });
    it('never writes again after a known receipt, even when permission is revoked', async () => {
        const store = useAIReviewLedgerStore(); await store.confirm(request()); role(4);
        await store.retryConfirm('9', request().id, 'stable-confirm'); expect(services.confirmLedgerAIReviewItem).toHaveBeenCalledTimes(1);
        await store.acknowledgeConfirm('9', request().id, 'wrong-key', receipt().transactionId); expect(store.readPendingConfirm('9')).not.toBeNull();
        await store.acknowledgeConfirm('9', request().id, 'stable-confirm', receipt().transactionId); expect(store.readPendingConfirm('9')).toBeNull();
    });
    it.each([{ atomicConfirmation: false }, { payloadBinding: false }, { contractVersion: 2 }, { ledgerId: '0' },
        { pagination: 'page' }, { maxPageSize: 0 }, { reviewerScope: 'any-member' }, { confirmationPath: 'v1/transactions/add.json' }])('keeps original confirmation for incompatible capability %j', async change => {
        vi.mocked(services.getAIReviewCapabilities).mockResolvedValue(response({ ...capabilities, ...change }));
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow('升级服务');
        expect(store.readPendingConfirm('9')?.request).toEqual(request()); expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('keeps original confirmation on old-server 404 and never uses legacy writes', async () => {
        vi.mocked(services.getAIReviewCapabilities).mockRejectedValue({ response: { status: 404 } });
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow('升级服务');
        expect(store.readPendingConfirm('9')).not.toBeNull(); expect(services.addTransaction).not.toHaveBeenCalled();
    });
    it.each([{ id: '1' }, { ledgerId: '0' }, { transactionId: '0' }, { transactionId: 9007199254740999 }, {}])('keeps unknown result for malformed confirmation reply %j', async change => {
        vi.mocked(services.confirmLedgerAIReviewItem).mockResolvedValue(response(Object.keys(change).length ? { ...receipt(), ...change } : {}));
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow();
        expect(store.readPendingConfirm('9')?.receipt).toBeUndefined();
    });
    it.each([403, 409, 500])('sanitizes failed writes and retains journal for HTTP %s', async status => {
        vi.mocked(services.confirmLedgerAIReviewItem).mockRejectedValue({ response: { status }, config: { token: 'synthetic private' } });
        const store = useAIReviewLedgerStore(); let caught: unknown;
        try { await store.confirm(request()); } catch (error) { caught = error; }
        expect(caught).toBeInstanceOf(Error); expect(JSON.stringify(caught)).not.toContain('synthetic private'); expect(store.readPendingConfirm('9')).not.toBeNull();
    });
    it('viewer reads but cannot create or confirm', async () => {
        role(4); const store = useAIReviewLedgerStore(); await store.load();
        await expect(store.create(createRequest())).rejects.toThrow(); await expect(store.confirm(request())).rejects.toThrow();
        expect(services.createLedgerAIReviewItem).not.toHaveBeenCalled(); expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('member cannot confirm another creator item reported uneditable', async () => {
        role(3); vi.mocked(services.getLedgerAIReviewItem).mockResolvedValue(response(item({ creatorUid: '99', editable: false })));
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow('不可确认');
        expect(store.readPendingConfirm('9')).toBeNull();
    });
    it('revocation denies unknown retry but keeps original content', async () => {
        const store = useAIReviewLedgerStore(); vi.mocked(services.confirmLedgerAIReviewItem).mockRejectedValueOnce(new Error('Lost'));
        await expect(store.confirm(request())).rejects.toThrow(); role(4);
        await expect(store.retryConfirm('9', request().id, 'stable-confirm')).rejects.toThrow();
        expect(services.confirmLedgerAIReviewItem).toHaveBeenCalledTimes(1); expect(store.readPendingConfirm('9')?.request).toEqual(request());
    });
    it('stores late confirmation receipt in original ledger without displaying it in new ledger', async () => {
        let release!: (value: never) => void;
        vi.mocked(services.confirmLedgerAIReviewItem).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
        const store = useAIReviewLedgerStore(), pending = store.confirm(request());
        await vi.waitFor(() => expect(release).toBeTypeOf('function')); useLedgersStore().selectedLedgerId = '10'; release(response(receipt()));
        await pending; expect(store.items).toEqual([]); expect(store.readPendingConfirm('9')?.receipt).toEqual(receipt());
        expect(store.readPendingConfirm('10')).toBeNull();
    });
    it('account switch cannot save old response under new identity', async () => {
        vi.mocked(services.confirmLedgerAIReviewItem).mockImplementationOnce(async () => { disk.identity = 'other-server/user'; disk.username = 'other'; return response(receipt()); });
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow('账号已变化');
        expect(store.readPendingConfirm('9')).toBeNull(); disk.identity = 'synthetic-server/owner'; disk.username = 'synthetic-owner';
        expect(store.readPendingConfirm('9')?.receipt).toBeUndefined();
    });
    it('prevents dismissing or deleting the item with an unknown confirmation', async () => {
        vi.mocked(services.confirmLedgerAIReviewItem).mockRejectedValueOnce(new Error('Lost'));
        const store = useAIReviewLedgerStore(); await expect(store.confirm(request())).rejects.toThrow();
        for (const action of ['dismiss', 'restore', 'delete'] as const) await expect(store.change(action, '9', request().id)).rejects.toThrow('待核对');
        expect(services.changeLedgerAIReviewItem).not.toHaveBeenCalled();
    });
});

describe('complete ledger queue snapshots', () => {
    it('reads all ID cursor pages without converting IDs to JS numbers', async () => {
        const first = Array.from({ length: 100 }, (_, i) => item({ id: String(9007199254742000n - BigInt(i)) }));
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValueOnce(response(first)).mockResolvedValueOnce(response([item({ id: '9007199254740997' })]));
        const store = useAIReviewLedgerStore(); await store.load(); expect(store.items).toHaveLength(101); expect(store.loaded).toBe(true);
        expect(vi.mocked(services.listLedgerAIReviewItems).mock.calls[1]![0].beforeId).toBe(first[99]!.id);
    });
    it.each([null, {}, { success: true }, { success: false, result: [] }])('invalid response is not empty: %j', async data => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue({ data } as never);
        const store = useAIReviewLedgerStore(); await expect(store.load()).rejects.toThrow(); expect(store.loaded).toBe(false); expect(store.errorMessage).toBeTruthy();
    });
    it.each([{ page: [item(), item()] }, { page: [item({ ledgerId: '10' })] }, { page: [item({ deleted: true, editable: false })] },
        { page: [item({ status: 3 })] }])('rejects repeated, foreign, deleted or wrong-status page', async ({ page }) => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValue(response(page));
        const store = useAIReviewLedgerStore(); await expect(store.load()).rejects.toThrow(); expect(store.loaded).toBe(false);
    });
    it('second-page failure never publishes a partial first page', async () => {
        vi.mocked(services.listLedgerAIReviewItems).mockResolvedValueOnce(response(Array.from({ length: 100 }, (_, i) => item({ id: String(1000 - i) })))).mockRejectedValueOnce(new Error('Lost'));
        const store = useAIReviewLedgerStore(); await expect(store.load()).rejects.toThrow(); expect(store.items).toEqual([]); expect(store.loaded).toBe(false);
    });
    it('late page after ledger switch cannot populate the new ledger', async () => {
        let release!: (value: never) => void; vi.mocked(services.listLedgerAIReviewItems).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
        const store = useAIReviewLedgerStore(), pending = store.load(); await vi.waitFor(() => expect(release).toBeTypeOf('function'));
        useLedgersStore().selectedLedgerId = '10'; release(response([item()])); await expect(pending).rejects.toThrow(); expect(store.items).toEqual([]);
    });
});

describe('recognition and strict frozen input', () => {
    it('viewer recognition requires advertised context and passes original ledger and username', async () => {
        role(4); const store = useAIReviewLedgerStore(); await store.recognizeText('9', 'Synthetic text');
        expect(services.recognizeLedgerTransactionText).toHaveBeenCalledWith({ ledgerId: '9', text: 'Synthetic text' }, 'synthetic-owner');
        vi.mocked(services.getAIReviewCapabilities).mockResolvedValue(response({ ...capabilities, recognitionLedgerContext: undefined }));
        await expect(store.recognizeText('9', 'Synthetic text')).rejects.toThrow('升级服务'); expect(services.recognizeLedgerTransactionText).toHaveBeenCalledTimes(1);
    });
    it('foreign recognition result is never accepted', async () => {
        vi.mocked(services.recognizeLedgerTransactionText).mockResolvedValue(response({ type: 3, ledgerId: '10', sourceAmount: -123 }));
        await expect(useAIReviewLedgerStore().recognizeText('9', 'Synthetic text')).rejects.toThrow('原账本');
    });
    it.each([{ type: 1 }, { sourceAmount: 0.5 }, { sourceAccountId: '9007199254740993.5' }, { categoryId: '0' },
        { ledgerId: '10' }, { clientSessionId: ' ' }, { utcOffset: 841 }, { time: 0 }, { pictureIds: ['1', '1'] },
        { geoLocation: { latitude: 99, longitude: 0 } }, { destinationAccountId: '2' }])('rejects invalid frozen transaction %j', async change => {
        const body = { ...request(), transaction: { ...request().transaction, ...change } } as AIReviewConfirmRequest;
        expect(() => assertAIReviewConfirm(body, '9')).toThrow(); await expect(useAIReviewLedgerStore().confirm(body)).rejects.toThrow();
        expect(services.confirmLedgerAIReviewItem).not.toHaveBeenCalled();
    });
    it('supports external incoming/outgoing transfers and keeps absent side exactly zero', () => {
        const base = request();
        for (const change of [{ sourceAccountId: '0', sourceAmount: 0, destinationAccountId: '21', destinationAmount: 123 },
            { sourceAccountId: '21', sourceAmount: 123, destinationAccountId: '0', destinationAmount: 0 }]) {
            expect(() => assertAIReviewConfirm({ ...base, transaction: { ...base.transaction, type: 4, ...change } }, '9')).not.toThrow();
        }
        expect(() => assertAIReviewConfirm({ ...base, transaction: { ...base.transaction, type: 4, sourceAccountId: '0', sourceAmount: 123, destinationAccountId: '21', destinationAmount: 123 } }, '9')).toThrow();
    });
    it('enforces UTF8 source size, ID range, retained legacy receipts and corrupted journals', () => {
        expect(() => assertAIReviewCreate({ ...createRequest(), sourceText: '字'.repeat(2667) }, '9')).toThrow();
        expect(() => assertAIReviewItem(item({ id: '9223372036854775808' }), '9')).toThrow();
        expect(() => assertAIReviewItem(item({ status: 2, transactionId: '0' }), '9')).not.toThrow();
        disk.confirms.set(`${disk.identity}/9`, { version: 1, identity: 'foreign', request: request() });
        expect(() => useAIReviewLedgerStore().readPendingConfirm('9')).toThrow('账号不匹配');
    });
});
