import { describe, expect, it } from 'vitest';
import { TransactionType } from '@/core/transaction.ts';
import { accountCurrency, exactMinorUnitSum, minorAmountToDecimal, summarizeCurrencyAmounts, summarizeStatisticCurrencyAmounts } from '../currency_totals.ts';

const accounts = { cny: { currency: 'CNY' }, usd: { currency: 'USD' } };
describe('currency totals', () => {
    it('keeps signed balance sums exact through intermediate overflow and rejects unsafe inputs or results', () => {
        const limit = Number.MAX_SAFE_INTEGER;
        expect(exactMinorUnitSum([limit, 2, -limit])).toBe(2);
        expect(exactMinorUnitSum([limit, 1])).toBeNull();
        expect(exactMinorUnitSum([-limit, -1])).toBeNull();
        expect(exactMinorUnitSum([limit + 1, -1])).toBeNull();
        expect(exactMinorUnitSum([0.5])).toBeNull();
        expect(exactMinorUnitSum([])).toBe(0);
    });
    it('keeps original currencies and refunds, excluding transfers and adjustments', () => {
        const result = summarizeCurrencyAmounts([
            { type: TransactionType.Income, accountId: 'cny', amount: 10000 },
            { type: TransactionType.Expense, accountId: 'cny', amount: 3200 },
            { type: TransactionType.Expense, accountId: 'cny', amount: -200 },
            { type: TransactionType.Expense, accountId: 'usd', amount: 700 },
            { type: TransactionType.Transfer, accountId: 'cny', amount: 99999 },
            { type: TransactionType.ModifyBalance, accountId: 'usd', amount: 99999 },
        ], accounts);
        expect(result).toEqual({ totals: [
            { currency: 'CNY', income: 10000, expense: 3000, count: 3 },
            { currency: 'USD', income: 0, expense: 700, count: 1 },
        ], unresolvedCount: 0 });
    });
    it('marks missing metadata and invalid or overflowing amounts incomplete', () => {
        expect(summarizeCurrencyAmounts([
            { type: TransactionType.Income, accountId: 'missing', amount: 500 },
            { type: TransactionType.Expense, accountId: 'usd', amount: 0.5 },
            { type: TransactionType.Income, accountId: 'cny', amount: Number.MAX_SAFE_INTEGER },
            { type: TransactionType.Income, accountId: 'cny', amount: 1 },
        ], accounts).unresolvedCount).toBe(3);
        expect(accountCurrency({ a: { currency: '' } }, 'a')).toBeNull();
        expect(accountCurrency(accounts, 'missing')).toBeNull();
    });
    it('exports major units including negative and sub-unit amounts', () => {
        expect([0, 1, 12345, -99].map(minorAmountToDecimal)).toEqual(['0.00', '0.01', '123.45', '-0.99']);
        expect(() => minorAmountToDecimal(1.2)).toThrow();
    });
    it('uses each statistic account currency and marks missing categories incomplete', () => {
        expect(summarizeStatisticCurrencyAmounts([
            { accountId: 'cny', categoryId: 'income', amount: 500 },
            { accountId: 'usd', categoryId: 'expense', amount: 200 },
            { accountId: 'usd', categoryId: 'missing', amount: 900 },
            { accountId: 'cny', categoryId: 'transfer', amount: 700 },
        ], accounts, { income: { type: 1 }, expense: { type: 2 }, transfer: { type: 3 } })).toEqual({
            totals: [{ currency: 'CNY', income: 500, expense: 0, count: 1 }, { currency: 'USD', income: 0, expense: 200, count: 1 }],
            unresolvedCount: 1,
        });
    });
});
