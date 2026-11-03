import { describe, expect, it } from 'vitest';
import { getAccountBalanceDisplay, summarizeVisibleAccountBalances } from '../account_presentation.ts';

describe('account presentation', () => {
    it('separates currencies and excludes hidden account balances', () => {
        expect(summarizeVisibleAccountBalances([
            { currency: 'CNY', balance: 12500, hidden: false, isLiability: false },
            { currency: 'CNY', balance: -3200, hidden: false, isLiability: true },
            { currency: 'CNY', balance: 990000, hidden: true, isLiability: false },
            { currency: 'USD', balance: 730, hidden: false, isLiability: false },
        ])).toEqual([
            { currency: 'CNY', assets: 12500, liabilities: 3200, net: 9300 },
            { currency: 'USD', assets: 730, liabilities: 0, net: 730 },
        ]);
    });
    it('retains overdrafts and credit overpayments in net balances', () => {
        expect(summarizeVisibleAccountBalances([
            { currency: 'CNY', balance: -100, hidden: false, isLiability: false },
            { currency: 'CNY', balance: 250, hidden: false, isLiability: true },
        ])).toEqual([{ currency: 'CNY', assets: -100, liabilities: -250, net: 150 }]);
        expect(summarizeVisibleAccountBalances([])).toEqual([]);
    });
    it('labels liabilities as owed or overpaid without changing stored signs', () => {
        expect(getAccountBalanceDisplay(-1234, true)).toEqual({ label: '待还款', amount: 1234 });
        expect(getAccountBalanceDisplay(0, true)).toEqual({ label: '待还款', amount: 0 });
        expect(getAccountBalanceDisplay(300, true)).toEqual({ label: '溢缴款', amount: 300 });
        expect(getAccountBalanceDisplay(-100, false)).toEqual({ label: '账户余额', amount: -100 });
    });
});
