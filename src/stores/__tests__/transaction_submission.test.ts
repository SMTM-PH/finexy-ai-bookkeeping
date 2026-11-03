import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { PendingTransactionCreate } from '@/models/transaction.ts';
const disk = vi.hoisted(() => ({ pending: null as PendingTransactionCreate | null, user: 'synthetic-owner', fail: false, failDraft: false, loggedIn: true, draft: null as import('@/models/transaction.ts').TransactionDraft | null, clearedDrafts: [] as string[], lockBarrier: null as Promise<void> | null, afterPendingWrite: null as (() => void) | null }));
vi.mock('@/lib/services.ts', () => ({ default: { addTransaction: vi.fn(), modifyTransaction: vi.fn(), getTransaction: vi.fn() } }));
vi.mock('@/stores/setting.ts', () => ({ useSettingsStore: () => ({ appSettings: { autoSaveTransactionDraft: 'enabled' } }) }));
vi.mock('@/stores/user.ts', () => ({ useUserStore: () => ({ currentUserLanguage: 'zh-Hans', defaultCurrency: 'CNY' }) }));
vi.mock('@/stores/account.ts', () => ({ useAccountsStore: () => ({ accountListStateInvalid: true }) }));
vi.mock('@/stores/transactionCategory.ts', () => ({ useTransactionCategoriesStore: () => ({}) }));
vi.mock('@/stores/exchangeRates.ts', () => ({ useExchangeRatesStore: () => ({}) }));
vi.mock('@/stores/overview.ts', () => ({ useOverviewStore: () => ({ transactionOverviewStateInvalid: true }) }));
vi.mock('@/stores/statistics.ts', () => ({ useStatisticsStore: () => ({ transactionStatisticsStateInvalid: true }) }));
vi.mock('@/stores/explorer.ts', () => ({ useExplorersStore: () => ({ transactionExplorerStateInvalid: true }) }));
vi.mock('@/lib/logger.ts', () => ({ default: { error: vi.fn(), warn: vi.fn() } }));
vi.mock('@/lib/userstate.ts', () => ({
    getCurrentUserInfo: () => ({ username: disk.user }), getTransactionStateIdentity: () => disk.user, isUserLogined: () => disk.loggedIn,
    getUserTransactionDraft: () => disk.draft, inspectUserTransactionDraft: () => disk.draft,
    updateUserTransactionDraft: (value: import('@/models/transaction.ts').TransactionDraft) => { if (disk.failDraft) throw new Error('Draft storage denied'); disk.draft = JSON.parse(JSON.stringify(value)); },
    clearUserTransactionDraft: (ledgerId: string) => { disk.clearedDrafts.push(ledgerId); disk.draft = null; },
    getUserPendingTransactionCreate: () => disk.pending,
    updateUserPendingTransactionCreate: (value: PendingTransactionCreate) => {
        if (disk.fail) throw new Error('Storage denied');
        disk.pending = JSON.parse(JSON.stringify(value));
        disk.afterPendingWrite?.();
    },
    clearUserPendingTransactionCreate: () => { disk.pending = null; },
    // The production IndexedDB serialization is exercised with real browser tabs.
    withTransactionCreateLock: async (action: () => unknown) => { if (disk.lockBarrier) await disk.lockBarrier; return action(); }
}));
import services from '@/lib/services.ts';
import { Transaction } from '@/models/transaction.ts';
import { useTransactionsStore } from '../transaction.ts';
import { ledgerPendingDraftRevision } from '@/lib/ledger_pending.ts';
const form = () => Transaction.ofCreateRequest({ ledgerId: '9', clientSessionId: 'first-key', type: 3, time: 1700000000,
    utcOffset: 480, categoryId: '20', sourceAccountId: '10', destinationAccountId: '0', sourceAmount: 123,
    destinationAmount: 0, hideAmount: false, tagIds: [], pictureIds: [], comment: 'synthetic' });
const options = (transaction = form(), clientSessionId = 'first-key') => ({ transaction, clientSessionId, ledgerId: '9', defaultCurrency: 'CNY', isEdit: false, persistCreate: true });
const response = () => ({ data: { success: true, result: { ...disk.pending!.request, id: '9007199254740993', timeSequenceId: '9007199254740994', editable: true } } }) as never;
beforeEach(() => { vi.clearAllMocks(); setActivePinia(createPinia()); disk.pending = null; disk.user = 'synthetic-owner'; disk.fail = false; disk.failDraft = false; disk.loggedIn = true; disk.draft = null; disk.clearedDrafts = []; disk.lockBarrier = null; disk.afterPendingWrite = null; });
describe('ordinary transaction durable write journal shared by desktop and mobile', () => {
    it('writes before HTTP and only explicitly retries the immutable original after a lost response', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async request => {
            expect(disk.pending?.request).toEqual(request); throw new Error('response lost');
        });
        await expect(store.saveTransaction(options())).rejects.toBeDefined();
        const original = JSON.parse(JSON.stringify(disk.pending));
        const edited = form(); edited.sourceAmount = 999; edited.comment = 'changed after request';
        vi.mocked(services.addTransaction).mockImplementationOnce(async request => {
            expect(request).toEqual(original.request); return response();
        });
        const result = await store.saveTransaction({ ...options(edited, 'replacement-key'), retrySubmission: original });
        expect(result.submissionClientSessionId).toBe('first-key');
        expect(disk.pending).toEqual({ ...original, receipt: { id: '9007199254740993' } });
        await store.acknowledgePendingTransactionCreate('9', result.submissionClientSessionId!);
        expect(disk.pending).toBeNull();
    });
    it('never sends a request when persistence fails', async () => {
        disk.fail = true;
        await expect(useTransactionsStore().saveTransaction(options())).rejects.toThrow('Storage denied');
        expect(services.addTransaction).not.toHaveBeenCalled();
    });
    it('keeps the journal for malformed or wrong-ledger success responses', async () => {
        vi.mocked(services.addTransaction).mockResolvedValueOnce({ data: { success: true, result: { id: '1', ledgerId: '0' } } } as never);
        await expect(useTransactionsStore().saveTransaction(options())).rejects.toBeDefined();
        expect(disk.pending?.clientSessionId).toBe('first-key');
    });
    it('does not acknowledge a different tab request', async () => {
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        const store = useTransactionsStore(); await store.saveTransaction(options());
        await store.acknowledgePendingTransactionCreate('9', 'another-key');
        expect(disk.pending?.clientSessionId).toBe('first-key');
    });
    it('accepts omitted zero destination fields in the actual single-account API contract', async () => {
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => {
            const wire = JSON.parse(JSON.stringify({ ...disk.pending!.request, id: '9007199254740993', timeSequenceId: '9007199254740994', editable: true,
                destinationAccountId: undefined, destinationAmount: undefined }));
            return { data: { success: true, result: wire } } as never;
        });
        expect((await useTransactionsStore().saveTransaction(options())).id).toBe('9007199254740993');
    });
    it('rejects a late success after the login identity changes', async () => {
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => {
            const result = response(); disk.user = 'another-user'; return result;
        });
        await expect(useTransactionsStore().saveTransaction(options())).rejects.toBeDefined();
        expect(disk.pending?.clientSessionId).toBe('first-key');
    });
    it('retains the actual submission if another tab acknowledges it before a failed response', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => {
            disk.pending = null; throw new Error('response lost after other tab acknowledgement');
        });
        const failure = await store.saveTransaction(options()).catch(error => error);
        expect(failure.submission.clientSessionId).toBe('first-key');
        const changed = form(); changed.sourceAmount = 999;
        vi.mocked(services.addTransaction).mockImplementationOnce(async request => {
            expect(request).toEqual(failure.submission.request); return response();
        });
        const result = await store.saveTransaction({ ...options(changed, 'different-tab-key'), retrySubmission: failure.submission });
        expect(result.submissionClientSessionId).toBe('first-key');
    });

    it('persists a known receipt before returning and recovers through GET only after form edits', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        await store.saveTransaction(options());
        expect(disk.pending?.receipt?.id).toBe('9007199254740993');
        const edited = form(); edited.sourceAmount = 777; edited.comment = 'newer editor content';
        vi.mocked(services.getTransaction).mockImplementationOnce(async request => {
            expect(request).toEqual({ id: '9007199254740993', ledgerId: '9', withPictures: true, expectedUsername: disk.user, ignoreError: true });
            return response();
        });
        expect((await store.saveTransaction({ ...options(edited, 'replacement-key'), retrySubmission: disk.pending! })).id).toBe('9007199254740993');
        expect(services.addTransaction).toHaveBeenCalledTimes(1);
        expect(services.getTransaction).toHaveBeenCalledTimes(1);
    });
    it.each(['wrong-id', 'deleted', 'wrong-ledger'])('retains known receipt without another POST when readback is %s', async kind => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        await store.saveTransaction(options());
        vi.mocked(services.getTransaction).mockImplementationOnce(async () => {
            if (kind === 'deleted') throw new Error('original row not found');
            const result = JSON.parse(JSON.stringify(response())) as { data: { result: Record<string, unknown> } };
            result.data.result[kind === 'wrong-id' ? 'id' : 'ledgerId'] = kind === 'wrong-id' ? '9007199254740995' : '10';
            return result as never;
        });
        await expect(store.saveTransaction({ ...options(), retrySubmission: disk.pending! })).rejects.toBeDefined();
        expect(disk.pending?.receipt?.id).toBe('9007199254740993');
        expect(services.addTransaction).toHaveBeenCalledTimes(1);
    });
    it('records financial receipt before checking an incomplete attachment reply', async () => {
        const store = useTransactionsStore(), transaction = form();
        transaction.setPictures([{ pictureId: '50', originalUrl: 'synthetic/image' }] as never);
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        await expect(store.saveTransaction(options(transaction))).rejects.toBeDefined();
        expect(disk.pending?.receipt?.id).toBe('9007199254740993');
        vi.mocked(services.getTransaction).mockImplementationOnce(async () => response());
        await expect(store.saveTransaction({ ...options(), retrySubmission: disk.pending! })).rejects.toBeDefined();
        expect(services.addTransaction).toHaveBeenCalledTimes(1);
    });
    it('keeps a validated receipt in failure state when its disk write fails and restores it before GET', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => { disk.fail = true; return response(); });
        const failure = await store.saveTransaction(options()).catch(error => error);
        expect(failure.submission.receipt).toEqual({ id: '9007199254740993' });
        expect(disk.pending?.receipt).toBeUndefined();
        disk.fail = false;
        vi.mocked(services.getTransaction).mockImplementationOnce(async () => response());
        await store.saveTransaction({ ...options(form(), 'replacement-key'), retrySubmission: failure.submission });
        expect(disk.pending?.receipt?.id).toBe('9007199254740993');
        expect(services.addTransaction).toHaveBeenCalledTimes(1);
        expect(services.getTransaction).toHaveBeenCalledTimes(1);
    });
    it('refuses to acknowledge unknown writes and preserves an unrelated newer draft', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockRejectedValueOnce(new Error('lost response'));
        await expect(store.saveTransaction(options())).rejects.toBeDefined();
        await expect(store.acknowledgePendingTransactionCreate('9', 'first-key')).rejects.toThrow('尚未确认');
        expect(disk.pending).not.toBeNull();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        await store.saveTransaction({ ...options(), retrySubmission: disk.pending! });
        disk.draft = { clientSessionId: 'a-newer-draft' };
        await store.acknowledgePendingTransactionCreate('9', 'first-key');
        expect(disk.pending).toBeNull(); expect(disk.draft?.clientSessionId).toBe('a-newer-draft'); expect(disk.clearedDrafts).toEqual([]);
    });
    it('clears only the matching original-ledger draft when acknowledging the receipt', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        await store.saveTransaction(options()); disk.draft = { clientSessionId: 'first-key' };
        await store.acknowledgePendingTransactionCreate('9', 'first-key');
        expect(disk.clearedDrafts).toEqual(['9']); expect(disk.pending).toBeNull();
    });
    it('blocks saved-user metadata without a live login and never acknowledges across logout', async () => {
        const store = useTransactionsStore(); disk.loggedIn = false;
        await expect(store.saveTransaction(options())).rejects.toThrow('登录账号');
        expect(services.addTransaction).not.toHaveBeenCalled(); disk.loggedIn = true;
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response()); await store.saveTransaction(options());
        disk.loggedIn = false;
        await expect(store.acknowledgePendingTransactionCreate('9', 'first-key')).rejects.toThrow('登录账号');
        expect(disk.pending?.receipt).toBeDefined();
    });
    it('refuses corrupt optional receipt values before any HTTP', async () => {
        disk.pending = { clientSessionId: 'first-key', request: form().toCreateRequest('first-key', '9'), receipt: { id: '0' } };
        await expect(useTransactionsStore().saveTransaction(options())).rejects.toThrow('记录无效');
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('never substitutes another tab request for an explicitly retried original', async () => {
        const original: PendingTransactionCreate = { clientSessionId: 'original-key', request: form().toCreateRequest('original-key', '9'), receipt: { id: '9007199254740993' } };
        disk.pending = { clientSessionId: 'newer-key', request: form().toCreateRequest('newer-key', '9') };
        await expect(useTransactionsStore().saveTransaction({ ...options(), retrySubmission: original })).rejects.toThrow('不会提交替代请求');
        expect(disk.pending.clientSessionId).toBe('newer-key'); expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('does not acknowledge a mismatching original receipt ID', async () => {
        const store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => response()); await store.saveTransaction(options());
        await expect(store.acknowledgePendingTransactionCreate('9', 'first-key', '9007199254740995')).rejects.toThrow('回执已变化');
        expect(disk.pending?.receipt?.id).toBe('9007199254740993');
        await store.acknowledgePendingTransactionCreate('9', 'first-key', '9007199254740993'); expect(disk.pending).toBeNull();
    });
    it('binds the exact restored keyless draft before HTTP so receipt cleanup cannot expose it as a fresh add', async () => {
        disk.draft = { ...form().toTransactionDraft(), ledgerId: '9' };
        const revision = ledgerPendingDraftRevision(disk.draft), store = useTransactionsStore();
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => {
            expect(disk.draft?.clientSessionId).toBe('first-key'); expect(disk.pending?.clientSessionId).toBe('first-key'); return response();
        });
        const saved = await store.saveTransaction({ ...options(), expectedDraftRevision: revision });
        await store.acknowledgePendingTransactionCreate('9', saved.submissionClientSessionId!, saved.id);
        expect(disk.draft).toBeNull(); expect(disk.pending).toBeNull();
    });
    it('rejects changed draft input before creating a pending journal or performing HTTP', async () => {
        disk.draft = { ...form().toTransactionDraft(), ledgerId: '9' };
        const revision = ledgerPendingDraftRevision(disk.draft);
        disk.draft = { ...disk.draft, comment: 'another tab newer input' };
        await expect(useTransactionsStore().saveTransaction({ ...options(), expectedDraftRevision: revision })).rejects.toThrow('原草稿已变化');
        expect(disk.draft.comment).toBe('another tab newer input'); expect(disk.pending).toBeNull(); expect(services.addTransaction).not.toHaveBeenCalled();
    });
    it('retains a stable pending key when binding a keyless draft fails and resumes binding before the first POST', async () => {
        disk.draft = { ...form().toTransactionDraft(), ledgerId: '9' };
        const revision = ledgerPendingDraftRevision(disk.draft), store = useTransactionsStore(); disk.failDraft = true;
        await expect(store.saveTransaction({ ...options(), expectedDraftRevision: revision })).rejects.toThrow('Draft storage denied');
        expect(disk.pending?.clientSessionId).toBe('first-key'); expect(services.addTransaction).not.toHaveBeenCalled();
        disk.failDraft = false; vi.mocked(services.addTransaction).mockImplementationOnce(async () => response());
        await store.saveTransaction({ ...options(form(), 'replacement-key'), expectedDraftRevision: revision, retrySubmission: disk.pending! });
        expect(disk.draft.clientSessionId).toBe('first-key'); expect(services.addTransaction).toHaveBeenCalledTimes(1);
    });
    it.each([false, true])('refuses a new form save when another tab has an existing journal (receipt=%s)', async known => {
        disk.pending = { clientSessionId: 'another-tab-key', request: form().toCreateRequest('another-tab-key', '9'), ...(known ? { receipt: { id: '9007199254740993' } } : {}) };
        const original = JSON.parse(JSON.stringify(disk.pending));
        await expect(useTransactionsStore().saveTransaction(options(form(), 'new-form-key'))).rejects.toThrow('明确重试');
        expect(disk.pending).toEqual(original);
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('requires an explicit retry even if a new save accidentally supplies the pending key', async () => {
        disk.pending = { clientSessionId: 'first-key', request: form().toCreateRequest('first-key', '9') };
        await expect(useTransactionsStore().saveTransaction(options())).rejects.toThrow('明确重试');
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('refuses a competing journal that appears while the new save awaits its lock and only replays after explicit retry', async () => {
        let release!: () => void;
        disk.lockBarrier = new Promise<void>(resolve => { release = resolve; });
        const store = useTransactionsStore(), saving = store.saveTransaction(options(form(), 'new-form-key'));
        disk.pending = { clientSessionId: 'other-tab-key', request: form().toCreateRequest('other-tab-key', '9') };
        const original = JSON.parse(JSON.stringify(disk.pending)); release();
        await expect(saving).rejects.toThrow('明确重试');
        expect(disk.pending).toEqual(original); expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
        disk.lockBarrier = null;
        vi.mocked(services.addTransaction).mockImplementationOnce(async request => { expect(request).toEqual(original.request); return response(); });
        const saved = await store.saveTransaction({ ...options(form(), 'other-tab-key'), retrySubmission: original });
        expect(saved.submissionClientSessionId).toBe('other-tab-key'); expect(services.addTransaction).toHaveBeenCalledTimes(1);
    });
    it('rejects an explicit retry whose request bytes changed under the same key', async () => {
        disk.pending = { clientSessionId: 'first-key', request: form().toCreateRequest('first-key', '9') };
        const obsolete = { ...disk.pending, request: { ...disk.pending.request, comment: 'obsolete content' } };
        await expect(useTransactionsStore().saveTransaction({ ...options(), retrySubmission: obsolete })).rejects.toThrow('原请求内容已变化');
        expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('does not revive an old save after leaving and returning while the journal lock is awaited', async () => {
        let release!: () => void;
        disk.lockBarrier = new Promise<void>(resolve => { release = resolve; });
        let generation = 0, active = true;
        const ticket = generation, current = () => active && generation === ticket;
        const saving = useTransactionsStore().saveTransaction({ ...options(), isActionCurrent: current });
        active = false; generation++; active = true; generation++;
        release();
        await expect(saving).rejects.toMatchObject({ stale: true });
        expect(disk.pending).toBeNull(); expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('retains a prepared request without HTTP when navigation is invalidated during journal persistence', async () => {
        let active = true;
        disk.afterPendingWrite = () => { active = false; };
        const saving = useTransactionsStore().saveTransaction({ ...options(), isActionCurrent: () => active });
        await expect(saving).rejects.toMatchObject({ stale: true, submission: { clientSessionId: 'first-key' } });
        expect(disk.pending?.clientSessionId).toBe('first-key'); expect(services.addTransaction).not.toHaveBeenCalled(); expect(services.getTransaction).not.toHaveBeenCalled();
    });
    it('persists a valid in-flight receipt after the original page leaves without replaying the write', async () => {
        let active = true;
        vi.mocked(services.addTransaction).mockImplementationOnce(async () => { active = false; return response(); });
        const saved = await useTransactionsStore().saveTransaction({ ...options(), isActionCurrent: () => active });
        expect(saved.id).toBe('9007199254740993'); expect(disk.pending?.receipt?.id).toBe(saved.id);
        expect(services.addTransaction).toHaveBeenCalledTimes(1); expect(services.getTransaction).not.toHaveBeenCalled();
    });
});
