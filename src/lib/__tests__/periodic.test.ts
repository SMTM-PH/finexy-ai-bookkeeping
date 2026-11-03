import { describe, expect, it } from 'vitest';
import { TransactionType } from '@/core/transaction.ts';
import { formatPeriodicAmounts } from '../periodic.ts';
const accounts = { '1': { currency: 'CNY' }, '2': { currency: 'USD' } };
const format = (amount: number, currency: string) => `${currency} ${amount}`;
const item = { type: TransactionType.Expense, sourceAccountId: '1', destinationAccountId: '0', sourceAmount: -200, destinationAmount: 0, hideAmount: false };
describe('periodic money presentation', () => {
    it('keeps the refund sign instead of changing it into an expense', () => expect(formatPeriodicAmounts(item, accounts, format)).toBe('CNY -200'));
    it('keeps both original currencies on an internal transfer', () => expect(formatPeriodicAmounts({ ...item, type: TransactionType.Transfer, destinationAccountId: '2', sourceAmount: 700, destinationAmount: 100 }, accounts, format)).toBe('CNY 700 → USD 100'));
    it('shows only the real money side for external transfers', () => {
        expect(formatPeriodicAmounts({ ...item, type: TransactionType.Transfer, sourceAmount: 700 }, accounts, format)).toBe('CNY 700');
        expect(formatPeriodicAmounts({ ...item, type: TransactionType.Transfer, sourceAccountId: '0', destinationAccountId: '2', destinationAmount: 100 }, accounts, format)).toBe('USD 100');
    });
    it('never invents a currency for an unavailable account', () => expect(formatPeriodicAmounts(item, {}, format)).toBe('金额或币种待核对'));
    it('does not display an unsafe integer as an exact monetary amount', () => expect(formatPeriodicAmounts({ ...item, sourceAmount: Number.MAX_SAFE_INTEGER + 1 }, accounts, format)).toBe('金额或币种待核对'));
    it('honors record and global amount privacy', () => {
        expect(formatPeriodicAmounts({ ...item, hideAmount: true }, accounts, format)).toBe('••••');
        expect(formatPeriodicAmounts(item, accounts, format, false)).toBe('••••');
    });
});
