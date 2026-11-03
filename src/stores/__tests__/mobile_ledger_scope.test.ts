import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

vi.mock('@/lib/services.ts', () => ({ default: {
    getAllAccounts: vi.fn(), getAllTransactionCategories: vi.fn(), addTransactionCategoryBatch: vi.fn(),
    getTransactions: vi.fn(), getAllTransactionsByMonth: vi.fn(), getTransaction: vi.fn(),
    listLedgers: vi.fn(), acceptLedgerInvitation: vi.fn(), getLedgerOverview: vi.fn(),
    listSavingsGoals: vi.fn(), listSavingsGoalAccounts: vi.fn(), listSavingsGoalFunds: vi.fn()
} }));
vi.mock('@/stores/setting.ts', () => ({ useSettingsStore: () => ({ accountCategoryDisplayOrders: [], appSettings: { autoSaveTransactionDraft: 'enabled' } }) }));
vi.mock('@/stores/user.ts', () => ({ useUserStore: () => ({ currentUserLanguage: 'zh-Hans', defaultCurrency: 'CNY' }) }));
vi.mock('@/stores/exchangeRates.ts', () => ({ useExchangeRatesStore: () => ({ getExchangedAmount: (amount: number) => amount }) }));
vi.mock('@/stores/overview.ts', () => ({ useOverviewStore: () => ({}) }));
vi.mock('@/stores/statistics.ts', () => ({ useStatisticsStore: () => ({}) }));
vi.mock('@/stores/explorer.ts', () => ({ useExplorersStore: () => ({}) }));
vi.mock('@/lib/userstate.ts', () => ({ getUserTransactionDraft: () => null, updateUserTransactionDraft: vi.fn(), clearUserTransactionDraft: vi.fn(), getTransactionStateIdentity: () => null, getUserSelectedLedgerId: () => '0', updateUserSelectedLedgerId: vi.fn() }));
vi.mock('@/lib/logger.ts', () => ({ default: { error: vi.fn(), warn: vi.fn(), info: vi.fn(), debug: vi.fn() } }));

import services from '@/lib/services.ts';
import { useAccountsStore } from '../account.ts';
import { useTransactionCategoriesStore } from '../transactionCategory.ts';
import { useTransactionsStore } from '../transaction.ts';
import { useLedgersStore } from '../ledger.ts';
import { useSavingsGoalsStore } from '../savingsGoal.ts';

const ledger = (id: string) => ({ id, ownerUid: '1', type: 1, name: `Book ${id}`, comment: '', createdTime: 1 });
const account = (id: string, ledgerId = id) => ({ id, ledgerId, parentId: '0', name: `Account ${id}`, category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 100, comment: '', displayOrder: 0, hidden: false });
const transaction = (id: string, ledgerId = id) => ({ id, ledgerId, timeSequenceId: '1790800000001', type: 3, categoryId: '1', time: 1790800000, utcOffset: 480, sourceAccountId: '1', destinationAccountId: '0', sourceAmount: 100, destinationAmount: 0, hideAmount: false, tagIds: [], comment: `Row ${id}`, editable: true });
const response = (result: unknown) => ({ data: { success: true, result } }) as never;
function pending() {
    let resolve!: (value: never) => void;
    const promise = new Promise<never>(done => { resolve = done; });
    return { promise, resolve };
}

describe('mobile ledger cache boundaries', () => {
    beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); });

    it('preserves the known ledger list and selection on missing result, 403 and 404', async () => {
        const store = useLedgersStore();
        vi.mocked(services.listLedgers).mockResolvedValueOnce(response([ledger('9')]));
        await store.load(); store.select('9');
        vi.mocked(services.listLedgers).mockResolvedValueOnce({ data: { success: true } } as never);
        await expect(store.load()).rejects.toThrow();
        expect(store.selectedLedgerId).toBe('9'); expect(store.ledgers).toHaveLength(1);
        expect(store.featureDisabled).toBe(false); expect(store.loadError).not.toBe('');
        for (const status of [403, 404]) {
            vi.mocked(services.listLedgers).mockRejectedValueOnce({ response: { status } });
            await expect(store.load()).rejects.toEqual({ response: { status } });
            expect(store.selectedLedgerId).toBe('9'); expect(store.ledgers).toHaveLength(1);
            expect(store.featureDisabled).toBe(status === 404);
        }
    });

    it('uses a valid empty response to remove inaccessible ledgers, but reset invalidates an old response', async () => {
        const store = useLedgersStore();
        vi.mocked(services.listLedgers).mockResolvedValueOnce(response([ledger('9')]));
        await store.load(); store.select('9');
        vi.mocked(services.listLedgers).mockResolvedValueOnce(response([]));
        await store.load(); expect(store.selectedLedgerId).toBe('0');
        const request = pending(); vi.mocked(services.listLedgers).mockReturnValueOnce(request.promise);
        const result = store.load(); store.reset(); request.resolve(response([ledger('9')])); await result;
        expect(store.ledgers).toEqual([]); expect(store.loading).toBe(false);
    });

    it('accepting an invitation loads the list without switching the selected ledger', async () => {
        const store = useLedgersStore();
        vi.mocked(services.acceptLedgerInvitation).mockResolvedValueOnce(response(ledger('9')));
        vi.mocked(services.listLedgers).mockResolvedValueOnce(response([ledger('9')]));
        expect((await store.accept('synthetic')).id).toBe('9');
        expect(store.selectedLedgerId).toBe('0');
    });

    it('does not accept an overview from another ledger', async () => {
        vi.mocked(services.getLedgerOverview).mockResolvedValueOnce(response({ ledgerId: '8', ledgerName: 'Other', activeMemberCount: 1, accountCount: 0, transactionCount: 0, savingsGoalCount: 0, balances: [] }));
        await expect(useLedgersStore().loadOverview('9')).rejects.toThrow();
    });

    it('discards late accounts from a prior ledger and preserves current data on malformed responses', async () => {
        const store = useAccountsStore(); const old = pending();
        vi.mocked(services.getAllAccounts).mockReturnValueOnce(old.promise).mockResolvedValueOnce(response([account('2')]));
        const previous = store.loadAllAccounts({ force: false, ledgerId: '1' });
        const rejected = expect(previous).rejects.toMatchObject({ stale: true });
        await store.loadAllAccounts({ force: false, ledgerId: '2' });
        old.resolve(response([account('1')])); await rejected;
        expect(store.accountListLedgerId).toBe('2'); expect(store.allAccounts.map(item => item.id)).toEqual(['2']);
        vi.mocked(services.getAllAccounts).mockResolvedValueOnce({ data: { success: true } } as never);
        await expect(store.loadAllAccounts({ force: true, ledgerId: '2' })).rejects.toBeDefined();
        expect(store.allAccounts.map(item => item.id)).toEqual(['2']);
    });

    it('discards old categories before backfill and treats an explicit empty non-default list as valid', async () => {
        const store = useTransactionCategoriesStore(); const old = pending();
        vi.mocked(services.getAllTransactionCategories).mockReturnValueOnce(old.promise).mockResolvedValueOnce(response({ 1: [], 2: [], 3: [] }));
        const previous = store.loadAllCategories({ force: false, ledgerId: '0' });
        const rejected = expect(previous).rejects.toMatchObject({ stale: true });
        await store.loadAllCategories({ force: false, ledgerId: '2' });
        old.resolve(response({ 1: [], 2: [], 3: [] })); await rejected;
        expect(store.loadedLedgerId).toBe('2'); expect(store.transactionCategoryListStateInvalid).toBe(false);
        expect(services.addTransactionCategoryBatch).not.toHaveBeenCalled();
    });

    it('keeps ledgerId on pagination, drops late rows and never replaces valid history with a missing result', async () => {
        const store = useTransactionsStore(); const old = pending();
        vi.mocked(services.getTransactions).mockReturnValueOnce(old.promise).mockResolvedValueOnce(response({ items: [transaction('2')], nextTimeSequenceId: 1790800000000 }));
        const previous = store.loadTransactions({ reload: true, ledgerId: '1', autoExpand: true, defaultCurrency: 'CNY' });
        const rejected = expect(previous).rejects.toMatchObject({ stale: true });
        await store.loadTransactions({ reload: true, ledgerId: '2', autoExpand: true, defaultCurrency: 'CNY' });
        old.resolve(response({ items: [transaction('1')] })); await rejected;
        expect(store.transactions[0]?.items[0]?.id).toBe('2'); expect(store.transactionListLedgerId).toBe('2');
        vi.mocked(services.getTransactions).mockResolvedValueOnce({ data: { success: true } } as never);
        await expect(store.loadTransactions({ reload: false, ledgerId: '2', autoExpand: true, defaultCurrency: 'CNY' })).rejects.toBeDefined();
        expect(services.getTransactions).toHaveBeenLastCalledWith(expect.objectContaining({ ledgerId: '2', maxTime: 1790800000000 }));
        expect(store.transactions[0]?.items[0]?.id).toBe('2');
        vi.mocked(services.getAllTransactionsByMonth).mockResolvedValueOnce(response({ items: [] }));
        await store.loadMonthlyAllTransactions({ year: 2026, month: 10, ledgerId: '2', autoExpand: true, defaultCurrency: 'CNY' });
        expect(services.getAllTransactionsByMonth).toHaveBeenCalledWith(expect.objectContaining({ ledgerId: '2' }));
        expect(store.transactions).toEqual([]);
    });

    it('does not create duplicate default category batches when switching away and back during backfill', async () => {
        const store = useTransactionCategoriesStore(); const backfill = pending();
        vi.mocked(services.getAllTransactionCategories).mockResolvedValue(response({ 1: [], 2: [], 3: [] }));
        vi.mocked(services.addTransactionCategoryBatch).mockReturnValueOnce(backfill.promise);
        const first = store.loadAllCategories({ force: false, ledgerId: '0' });
        const rejected = expect(first).rejects.toMatchObject({ stale: true });
        await vi.waitFor(() => expect(services.addTransactionCategoryBatch).toHaveBeenCalledTimes(1));
        await store.loadAllCategories({ force: false, ledgerId: '2' });
        const restored = store.loadAllCategories({ force: false, ledgerId: '0' });
        await Promise.resolve();
        expect(services.addTransactionCategoryBatch).toHaveBeenCalledTimes(1);
        backfill.resolve(response({ 1: [], 2: [], 3: [] }));
        await rejected; await restored;
        expect(store.loadedLedgerId).toBe('0');
    });

    it('rejects responses with account and transaction ledger ownership mismatches', async () => {
        vi.mocked(services.getAllAccounts).mockResolvedValueOnce(response([account('1', '0')]));
        await expect(useAccountsStore().loadAllAccounts({ force: false, ledgerId: '2' })).rejects.toBeDefined();
        vi.mocked(services.getTransactions).mockResolvedValueOnce(response({ items: [transaction('1', '0')] }));
        await expect(useTransactionsStore().loadTransactions({ reload: true, ledgerId: '2', autoExpand: true, defaultCurrency: 'CNY' })).rejects.toBeDefined();
        vi.mocked(services.getTransaction).mockResolvedValueOnce(response(transaction('1', '0')));
        await expect(useTransactionsStore().getTransaction({ transactionId: '1', ledgerId: '2' })).rejects.toBeDefined();
    });

    it('savings goal errors preserve current data; old responses and account caches cannot survive scope reset', async () => {
        const store = useSavingsGoalsStore();
        const goal = (id: string) => ({ id, ledgerId: id, uid: '1', name: 'Synthetic goal', targetAmount: 1000, savedAmount: 0, achieved: false, comment: '' });
        vi.mocked(services.listSavingsGoals).mockResolvedValueOnce(response([goal('2')]));
        await store.load('2');
        vi.mocked(services.listSavingsGoals).mockResolvedValueOnce({ data: { success: true } } as never);
        await expect(store.load('2')).rejects.toThrow(); expect(store.goals[0]?.ledgerId).toBe('2');
        vi.mocked(services.listSavingsGoals).mockRejectedValueOnce({ response: { status: 403 } });
        await expect(store.load('2')).rejects.toBeDefined(); expect(store.goals[0]?.ledgerId).toBe('2');
        const old = pending(); vi.mocked(services.listSavingsGoals).mockReturnValueOnce(old.promise).mockResolvedValueOnce(response([goal('3')]));
        const previous = store.load('2'); await store.load('3'); old.resolve(response([goal('2')])); await previous;
        expect(store.loadedLedgerId).toBe('3'); expect(store.goals[0]?.ledgerId).toBe('3');
        vi.mocked(services.listSavingsGoalAccounts).mockResolvedValueOnce(response([account('3')]));
        await store.loadFundAccounts('3'); expect(store.fundAccounts).toHaveLength(1);
        store.reset(); expect(store.fundAccounts).toEqual([]); expect(store.loaded).toBe(false);
    });
});
