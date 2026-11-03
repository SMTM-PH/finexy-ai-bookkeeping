import { describe, expect, it } from 'vitest';
import type { AccountInfoResponse } from '@/models/account.ts';
import type { TransactionCategoryInfoResponse } from '@/models/transaction_category.ts';
import type { TransactionInfoResponse } from '@/models/transaction.ts';
import { budgetAccountSnapshot, budgetCategorySnapshot, budgetMonthFromText, budgetMonthText, calculateBudgetUsage, isBudgetConflictError, parseBudgetAmount } from '../monthly_budget.ts';

function account(id: string, currency = 'CNY', ledgerId = '2', patch: Partial<AccountInfoResponse> = {}): AccountInfoResponse {
    return { id, name: 'fixture', parentId: '0', category: 1, type: 1, icon: '1', color: '', currency, balance: 0, comment: '', displayOrder: 0, hidden: false, ledgerId, ...patch };
}
function category(id: string, parentId: string, patch: Partial<TransactionCategoryInfoResponse> = {}): TransactionCategoryInfoResponse {
    return { id, name: `分类${id}`, parentId, type: 2, icon: '1', color: '', comment: '', displayOrder: 0, hidden: false, ...patch };
}
function transaction(id: string, patch: Partial<TransactionInfoResponse> = {}): TransactionInfoResponse {
    return { id, ledgerId: '2', timeSequenceId: id, type: 3, categoryId: '11', time: 1700000000, utcOffset: 480, sourceAccountId: '1', destinationAccountId: '0', sourceAmount: 100, destinationAmount: 0, hideAmount: false, tagIds: [], comment: '', editable: true, ...patch };
}
const categories = { '10': category('10', '0', { subCategories: [category('11', '10')] }), '11': category('11', '10') };
const accounts = { '1': account('1'), '2': account('2', 'USD') };

describe('monthly budget exact money and month inputs', () => {
    it('identifies version conflicts without guessing that other failures are conflicts', () => {
        expect(isBudgetConflictError({ status: 409, error: { errorMessage: 'fixture' } })).toBe(true);
        expect(isBudgetConflictError({ response: { status: 409 } })).toBe(true);
        expect(isBudgetConflictError({ status: 500 })).toBe(false);
        expect(isBudgetConflictError(new Error('fixture'))).toBe(false);
        expect(isBudgetConflictError(null)).toBe(false);
    });
    it('parses decimal digits exactly within the backend amount contract', () => {
        expect(parseBudgetAmount('29.99')).toBe(2999);
        expect(parseBudgetAmount(' 0.01 ')).toBe(1);
        expect(parseBudgetAmount('0001.2')).toBe(120);
        expect(parseBudgetAmount('99999999999.99')).toBe(9999999999999);
        expect(parseBudgetAmount('100000000000.00')).toBeNull();
    });
    it.each(['', '-1', '+1', '1e3', '1,000.00', '1.001', '.5', '1.', 'NaN', 'Infinity'])('rejects ambiguous or lossy money text %s', input => {
        expect(parseBudgetAmount(input)).toBeNull();
    });
    it('keeps month selection valid across calendar boundaries', () => {
        expect(budgetMonthFromText('2026-12')).toBe(202612);
        expect(budgetMonthText(202701)).toBe('2027-01');
        expect(budgetMonthFromText('2026-13')).toBeNull();
        expect(budgetMonthFromText('2026-00')).toBeNull();
        expect(budgetMonthFromText('2026-1')).toBeNull();
        expect(budgetMonthFromText('1999-12')).toBeNull();
        expect(budgetMonthFromText('2000-01')).toBe(200001);
    });
});

describe('monthly budget resource snapshots', () => {
    it('only offers visible expense leaves with a visible matching parent', () => {
        const result = budgetCategorySnapshot({
            1: [category('30', '0', { type: 1, subCategories: [category('31', '30', { type: 1 })] })],
            2: [category('10', '0', { subCategories: [category('11', '10'), category('12', '10', { hidden: true })] }), category('20', '0', { hidden: true, subCategories: [category('21', '20')] })]
        });
        expect(result.selectable).toEqual([{ id: '11', name: '分类11', parentName: '分类10' }]);
        expect(result.all['12']?.hidden).toBe(true);
        expect(result.all['21']?.parentId).toBe('20');
        expect(budgetCategorySnapshot({}).selectable).toEqual([]);
    });
    it('rejects incomplete trees, duplicate IDs and foreign parents rather than repairing them', () => {
        expect(() => budgetCategorySnapshot({ 2: [category('10', '0', { subCategories: [category('11', '99')] })] })).toThrow();
        expect(() => budgetCategorySnapshot({ 2: [category('10', '0'), category('10', '0')] })).toThrow();
        expect(() => budgetCategorySnapshot({ 2: [category('10', '0', { subCategories: [category('11', '10', { type: 1 })] })] })).toThrow();
        expect(() => budgetCategorySnapshot({ 2: null } as unknown as Record<number, TransactionCategoryInfoResponse[]>)).toThrow();
    });
    it('reads hidden historical accounts and grouped children with exact string IDs', () => {
        const result = budgetAccountSnapshot([account('9007199254740993', 'CNY', '2', { hidden: true }), account('3', '', '2', { type: 2, subAccounts: [account('4', 'USD', '2', { parentId: '3', hidden: true })] })], '2');
        expect(result['9007199254740993']?.id).toBe('9007199254740993');
        expect(result['4']?.hidden).toBe(true);
    });
    it('rejects wrong ledgers, missing shared-ledger ownership and malformed groups', () => {
        expect(() => budgetAccountSnapshot([account('1', 'CNY', '0')], '2')).toThrow();
        expect(() => budgetAccountSnapshot([account('1', 'CNY', '2', { ledgerId: undefined })], '2')).toThrow();
        expect(() => budgetAccountSnapshot([account('3', '', '2', { type: 2, subAccounts: [account('4', 'USD', '2', { parentId: '99' })] })], '2')).toThrow();
        expect(() => budgetAccountSnapshot([account('1', 'XYZ')], '2')).toThrow();
    });
});

describe('monthly budget spending usage', () => {
    it('includes signed refunds and hidden expenses but excludes income, transfers, adjustments and other currencies', () => {
        const items = [transaction('1', { sourceAmount: 3200 }), transaction('2', { sourceAmount: -200 }), transaction('3', { type: 2, sourceAmount: 9000 }), transaction('4', { type: 4, sourceAmount: 8000 }), transaction('5', { type: 1, sourceAmount: 8000 }), transaction('6', { sourceAccountId: '2', sourceAmount: 700 })];
        expect(calculateBudgetUsage(items, items.length, { ...accounts, '1': account('1', 'CNY', '2', { hidden: true }) }, categories, '2', 'CNY')).toEqual({ expense: 3000, categoryExpenses: { '11': 3000 }, problem: null });
    });
    it('only displays zero for a complete valid empty monthly snapshot', () => {
        expect(calculateBudgetUsage([], 0, {}, {}, '2', 'CNY')).toEqual({ expense: 0, categoryExpenses: {}, problem: null });
        expect(calculateBudgetUsage([], 1, {}, {}, '2', 'CNY').expense).toBeNull();
        expect(calculateBudgetUsage([], undefined as unknown as number, {}, {}, '2', 'CNY').expense).toBeNull();
    });
    it('does not guess currency or category when metadata is absent, including other-currency expenses', () => {
        expect(calculateBudgetUsage([transaction('1')], 1, {}, categories, '2', 'CNY').expense).toBeNull();
        expect(calculateBudgetUsage([transaction('1')], 1, accounts, {}, '2', 'CNY').expense).toBeNull();
        expect(calculateBudgetUsage([transaction('1', { sourceAccountId: '2' })], 1, accounts, {}, '2', 'CNY').expense).toBeNull();
        expect(calculateBudgetUsage([transaction('1', { sourceAmount: 0.5 })], 1, accounts, categories, '2', 'CNY').expense).toBeNull();
    });
    it('fails closed for duplicate rows, wrong ledger and wrong transaction type', () => {
        expect(calculateBudgetUsage([transaction('1'), transaction('1')], 2, accounts, categories, '2', 'CNY').expense).toBeNull();
        expect(calculateBudgetUsage([transaction('1', { ledgerId: '3' })], 1, accounts, categories, '2', 'CNY').expense).toBeNull();
        expect(calculateBudgetUsage([transaction('1', { type: 99 })], 1, accounts, categories, '2', 'CNY').expense).toBeNull();
    });
    it('keeps cancelling large amounts exact and rejects unsafe final sums', () => {
        const limit = Number.MAX_SAFE_INTEGER;
        const items = [transaction('1', { sourceAmount: limit }), transaction('2', { sourceAmount: 2 }), transaction('3', { sourceAmount: -limit })];
        expect(calculateBudgetUsage(items, 3, accounts, categories, '2', 'CNY').expense).toBe(2);
        expect(calculateBudgetUsage(items.slice(0, 2), 2, accounts, categories, '2', 'CNY').expense).toBeNull();
    });
    it('preserves a net refund amount so remaining budget can exceed the total', () => {
        expect(calculateBudgetUsage([transaction('1', { sourceAmount: -500 })], 1, accounts, categories, '2', 'CNY')).toEqual({ expense: -500, categoryExpenses: { '11': -500 }, problem: null });
    });
});
