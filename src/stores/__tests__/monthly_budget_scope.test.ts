import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const scope = vi.hoisted(() => ({ server: 'https://synthetic.invalid/a', username: 'synthetic-owner' }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => JSON.stringify([scope.server, scope.username]),
    getCurrentUserInfo: () => ({ username: scope.username })
}));
vi.mock('@/lib/logger.ts', () => ({ default: { warn: vi.fn() } }));
vi.mock('@/lib/services.ts', () => ({ default: { getMonthlyBudget: vi.fn(), setMonthlyBudget: vi.fn(), deleteMonthlyBudget: vi.fn() } }));
import services from '@/lib/services.ts';
import { useMonthlyBudgetStore } from '../monthlyBudget.ts';

const month = 202610;
const ledger = '9007199254740993';
const budget = (ledgerId = ledger, amount = 12000) => ({ id: '9007199254740997', ledgerId, yearMonth: month, amount, revision: 1, currency: 'USD', categoryAmounts: {} });
const response = (result: unknown) => ({ data: { success: true, result } }) as never;
function deferred() {
    let resolve!: (value: never) => void;
    const promise = new Promise<never>(done => { resolve = done; });
    return { resolve, promise };
}

describe('monthly budget identity, ledger and response boundaries', () => {
    beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); scope.server = 'https://synthetic.invalid/a'; scope.username = 'synthetic-owner'; });

    it('keeps large string ledger IDs, personal and shared months separate', async () => {
        const store = useMonthlyBudgetStore();
        vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response(budget())).mockResolvedValueOnce(response(budget('0', 3000)));
        await store.load(month, false, ledger); await store.load(month);
        expect(store.budgetFor(month, ledger)?.amount).toBe(12000);
        expect(store.budgetFor(month, '0')?.amount).toBe(3000);
        expect(services.getMonthlyBudget).toHaveBeenNthCalledWith(1, { yearMonth: month, ledgerId: ledger, expectedUsername: 'synthetic-owner' });
    });

    it('cannot reuse a cache after an account or server path changes', async () => {
        const store = useMonthlyBudgetStore();
        vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response(budget())); await store.load(month, false, ledger);
        scope.username = 'synthetic-other'; expect(store.budgetFor(month, ledger)).toBeUndefined();
        scope.username = 'synthetic-owner'; scope.server += '/second'; expect(store.budgetFor(month, ledger)).toBeUndefined();
    });

    it('only an explicit successful null is an unset budget, preserving known data on malformed reads', async () => {
        const store = useMonthlyBudgetStore();
        vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response(budget())); await store.load(month, false, ledger);
        for (const invalid of [{ data: { success: true } }, response({ ...budget(), ledgerId: '0' }), response({ ...budget(), amount: 0.5 }), response({ ...budget(), currency: 'ZZZ' })]) {
            vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(invalid as never);
            await expect(store.load(month, true, ledger)).rejects.toThrow(); expect(store.budgetFor(month, ledger)?.amount).toBe(12000);
        }
        vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response(null)); expect(await store.load(month, true, ledger)).toBeNull();
    });

    it('rejects old-account and reset responses without populating current cache', async () => {
        const store = useMonthlyBudgetStore(); const first = deferred();
        vi.mocked(services.getMonthlyBudget).mockReturnValueOnce(first.promise);
        const load = store.load(month, false, ledger); scope.username = 'synthetic-other'; first.resolve(response(budget()));
        await expect(load).rejects.toThrow(); expect(store.budgetFor(month, ledger)).toBeUndefined();
        const second = deferred(); vi.mocked(services.getMonthlyBudget).mockReturnValueOnce(second.promise);
        const resetLoad = store.load(month, false, ledger); store.reset(); second.resolve(response(budget()));
        await expect(resetLoad).rejects.toThrow(); expect(store.budgetFor(month, ledger)).toBeUndefined();
    });

    it('a late read cannot overwrite a newer acknowledged save', async () => {
        const store = useMonthlyBudgetStore(); const pending = deferred();
        vi.mocked(services.getMonthlyBudget).mockReturnValueOnce(pending.promise); const load = store.load(month, true, ledger);
        vi.mocked(services.setMonthlyBudget).mockResolvedValueOnce(response(budget(ledger, 15000)));
        await store.save(month, 15000, {}, ledger, 'USD', 1); pending.resolve(response(budget()));
        await expect(load).rejects.toThrow(); expect(store.budgetFor(month, ledger)?.amount).toBe(15000);
        expect(services.setMonthlyBudget).toHaveBeenCalledWith({ ledgerId: ledger, yearMonth: month, amount: 15000, currency: 'USD', expectedRevision: 1, categoryAmounts: {} }, 'synthetic-owner');
    });

    it('failed or malformed deletes cannot masquerade as successful clearing', async () => {
        const store = useMonthlyBudgetStore(); vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response(budget())); await store.load(month, false, ledger);
        vi.mocked(services.deleteMonthlyBudget).mockResolvedValueOnce(response(null));
        await expect(store.remove(month, ledger)).rejects.toThrow(); expect(store.budgetFor(month, ledger)?.amount).toBe(12000);
        vi.mocked(services.deleteMonthlyBudget).mockResolvedValueOnce(response(true)); await store.remove(month, ledger);
        expect(store.budgetFor(month, ledger)).toBeNull();
        expect(services.deleteMonthlyBudget).toHaveBeenLastCalledWith({ ledgerId: ledger, yearMonth: month, expectedRevision: 1 }, 'synthetic-owner');
    });

    it('keeps the saved limit and submitted baseline after conflict until explicit recovery', async () => {
        const store = useMonthlyBudgetStore();
        vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response(budget()));
        await store.load(month, false, ledger);
        vi.mocked(services.setMonthlyBudget).mockRejectedValueOnce({ response: { status: 409, data: { errorMessage: '预算已更新，请重新读取' } } });
        await expect(store.save(month, 16000, {}, ledger, 'USD', 1)).rejects.toMatchObject({ status: 409 });
        expect(store.budgetFor(month, ledger)?.amount).toBe(12000);
        expect(services.setMonthlyBudget).toHaveBeenCalledTimes(1);
        vi.mocked(services.getMonthlyBudget).mockResolvedValueOnce(response({ ...budget(ledger, 18000), revision: 2 }));
        await store.load(month, true, ledger);
        vi.mocked(services.setMonthlyBudget).mockResolvedValueOnce(response({ ...budget(ledger, 16000), revision: 3 }));
        await store.save(month, 16000, {}, ledger, 'USD', 1);
        expect(services.setMonthlyBudget).toHaveBeenLastCalledWith({ ledgerId: ledger, yearMonth: month, amount: 16000, currency: 'USD', expectedRevision: 1, categoryAmounts: {} }, 'synthetic-owner');
    });

    it('rejects invalid months, numeric IDs and unsafe amounts before HTTP', async () => {
        const store = useMonthlyBudgetStore();
        await expect(store.load(202613, false, ledger)).rejects.toThrow();
        await expect(store.load(month, false, 9 as never)).rejects.toThrow();
        await expect(store.save(month, Number.MAX_SAFE_INTEGER, {}, ledger, 'CNY')).rejects.toThrow();
        await expect(store.save(month, 100, { '20': 1.25 }, ledger, 'CNY')).rejects.toThrow();
        expect(services.getMonthlyBudget).not.toHaveBeenCalled(); expect(services.setMonthlyBudget).not.toHaveBeenCalled();
    });
});
