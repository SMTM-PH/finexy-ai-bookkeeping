import { TransactionType } from '@/core/transaction.ts';
import { CategoryType } from '@/core/category.ts';

export interface CurrencyAmountRecord {
    type: number;
    accountId: string;
    amount: number;
}

export interface CurrencyTotal {
    currency: string;
    income: number;
    expense: number;
    count: number;
}

export type CurrencyAccounts = Record<string, { currency: string } | undefined>;

/** Exact balance sums stay correct even when large signed values cancel. */
export function exactMinorUnitSum(amounts: readonly number[]): number | null {
    let total = 0n;
    for (const amount of amounts) {
        if (!Number.isSafeInteger(amount)) return null;
        total += BigInt(amount);
    }
    const limit = BigInt(Number.MAX_SAFE_INTEGER);
    return total < -limit || total > limit ? null : Number(total);
}

/** Only a real account supplies the currency. Missing metadata is never CNY. */
export function accountCurrency(accounts: CurrencyAccounts, accountId: string): string | null {
    const currency = accounts[accountId]?.currency;
    return currency && /^[A-Z]{3}$/.test(currency) ? currency : null;
}

/** Original integer minor units, grouped without exchange-rate conversion. */
export function summarizeCurrencyAmounts(records: CurrencyAmountRecord[], accounts: CurrencyAccounts): { totals: CurrencyTotal[]; unresolvedCount: number } {
    const totals = new Map<string, CurrencyTotal>();
    let unresolvedCount = 0;
    for (const record of records) {
        if (record.type !== TransactionType.Income && record.type !== TransactionType.Expense) continue;
        const currency = accountCurrency(accounts, record.accountId);
        if (!currency || !Number.isSafeInteger(record.amount)) { unresolvedCount++; continue; }
        const total = totals.get(currency) ?? { currency, income: 0, expense: 0, count: 0 };
        const field = record.type === TransactionType.Income ? 'income' : 'expense';
        const amount = total[field] + record.amount;
        if (!Number.isSafeInteger(amount)) { unresolvedCount++; continue; }
        total[field] = amount;
        total.count++;
        totals.set(currency, total);
    }
    return { totals: [...totals.values()].sort((a, b) => a.currency.localeCompare(b.currency)), unresolvedCount };
}

export function summarizeStatisticCurrencyAmounts(items: Array<{ accountId: string; categoryId: string; amount: number }>, accounts: CurrencyAccounts, categories: Record<string, { type: number } | undefined>): { totals: CurrencyTotal[]; unresolvedCount: number } {
    let unknownCategories = 0;
    const records = items.map(item => {
        const category = categories[item.categoryId];
        if (!category) unknownCategories++;
        return { accountId: item.accountId, amount: item.amount, type: category?.type === CategoryType.Income ? TransactionType.Income : category?.type === CategoryType.Expense ? TransactionType.Expense : TransactionType.Transfer };
    });
    const result = summarizeCurrencyAmounts(records, accounts);
    return { ...result, unresolvedCount: result.unresolvedCount + unknownCategories };
}

/** CSV values have an explicit two-decimal major-unit contract. */
export function minorAmountToDecimal(amount: number): string {
    if (!Number.isSafeInteger(amount)) throw new Error('金额不是有效的整数最小货币单位');
    const absolute = Math.abs(amount);
    return `${amount < 0 ? '-' : ''}${Math.trunc(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`;
}
