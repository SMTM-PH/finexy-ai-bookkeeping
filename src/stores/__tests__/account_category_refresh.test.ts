import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

vi.mock('@/lib/services.ts', () => ({ default: { getAllAccounts: vi.fn(), getAllTransactionCategories: vi.fn(), addTransactionCategoryBatch: vi.fn() } }));
vi.mock('@/stores/setting.ts', () => ({ useSettingsStore: () => ({ accountCategoryDisplayOrders: [] }) }));
vi.mock('@/stores/user.ts', () => ({ useUserStore: () => ({ currentUserLanguage: 'zh-Hans', defaultCurrency: 'CNY' }) }));
vi.mock('@/stores/exchangeRates.ts', () => ({ useExchangeRatesStore: () => ({ getExchangedAmount: (amount: number) => amount }) }));
vi.mock('@/lib/logger.ts', () => ({ default: { error: vi.fn(), warn: vi.fn(), info: vi.fn(), debug: vi.fn() } }));

import services from '@/lib/services.ts';
import { useAccountsStore } from '../account.ts';
import { useTransactionCategoriesStore } from '../transactionCategory.ts';

const response = (result: unknown) => ({ data: { success: true, result } }) as never;

describe('account and category refresh completion', () => {
    beforeEach(() => { setActivePinia(createPinia()); vi.resetAllMocks(); });

    it('loads a valid empty account response into each newly selected ledger', async () => {
        const store = useAccountsStore();
        vi.mocked(services.getAllAccounts).mockResolvedValue(response([]));
        await expect(store.loadAllAccounts({ force: true, ledgerId: '1' })).resolves.toEqual([]);
        await expect(store.loadAllAccounts({ force: true, ledgerId: '2' })).resolves.toEqual([]);
        expect(store.accountListLedgerId).toBe('2');
        expect(store.accountListStateInvalid).toBe(false);
        expect(store.allAccountsMap).toEqual({});
    });

    it('retains the existing unchanged-account signal only for an already valid ledger cache', async () => {
        const store = useAccountsStore();
        vi.mocked(services.getAllAccounts).mockResolvedValue(response([]));
        await store.loadAllAccounts({ force: true, ledgerId: '1' });
        await expect(store.loadAllAccounts({ force: true, ledgerId: '1' })).rejects.toMatchObject({ isUpToDate: true });
        store.updateAccountListInvalidState(true);
        await expect(store.loadAllAccounts({ force: true, ledgerId: '1' })).resolves.toEqual([]);
        expect(store.accountListStateInvalid).toBe(false);
    });

    it('does not treat missing or null account results as an unchanged successful read', async () => {
        const store = useAccountsStore();
        vi.mocked(services.getAllAccounts).mockResolvedValueOnce(response([]));
        await store.loadAllAccounts({ force: true, ledgerId: '1' });
        for (const malformed of [{ data: { success: true } }, response(null)]) {
            vi.mocked(services.getAllAccounts).mockResolvedValueOnce(malformed as never);
            await expect(store.loadAllAccounts({ force: true, ledgerId: '1' })).rejects.toEqual({ message: 'Unable to retrieve account list' });
        }
    });

    it('accepts the complete empty category map when switching ledgers without backfill', async () => {
        const store = useTransactionCategoriesStore();
        vi.mocked(services.getAllTransactionCategories).mockResolvedValue(response({}));
        await expect(store.loadAllCategories({ force: true, ledgerId: '1' })).resolves.toEqual({ 1: [], 2: [], 3: [] });
        await expect(store.loadAllCategories({ force: true, ledgerId: '2' })).resolves.toEqual({ 1: [], 2: [], 3: [] });
        expect(store.loadedLedgerId).toBe('2');
        expect(store.transactionCategoryListStateInvalid).toBe(false);
        expect(services.addTransactionCategoryBatch).not.toHaveBeenCalled();
    });

    it('retains the unchanged-category signal and reloads an invalidated same-ledger cache', async () => {
        const store = useTransactionCategoriesStore();
        vi.mocked(services.getAllTransactionCategories).mockResolvedValue(response({}));
        await store.loadAllCategories({ force: true, ledgerId: '1' });
        await expect(store.loadAllCategories({ force: true, ledgerId: '1' })).rejects.toMatchObject({ isUpToDate: true });
        store.updateTransactionCategoryListInvalidState(true);
        await expect(store.loadAllCategories({ force: true, ledgerId: '1' })).resolves.toEqual({ 1: [], 2: [], 3: [] });
        expect(store.transactionCategoryListStateInvalid).toBe(false);
    });

    it('rejects missing, null and malformed category groups instead of using an empty cached map', async () => {
        const store = useTransactionCategoriesStore();
        vi.mocked(services.getAllTransactionCategories).mockResolvedValueOnce(response({}));
        await store.loadAllCategories({ force: true, ledgerId: '1' });
        for (const malformed of [{ data: { success: true } }, response(null), response({ 1: null })]) {
            vi.mocked(services.getAllTransactionCategories).mockResolvedValueOnce(malformed as never);
            await expect(store.loadAllCategories({ force: true, ledgerId: '1' })).rejects.toEqual({ message: 'Unable to retrieve category list' });
        }
    });
});
