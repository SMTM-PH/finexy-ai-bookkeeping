import { AccountType } from '@/core/account.ts';
import { CategoryType } from '@/core/category.ts';
import { TransactionType } from '@/core/transaction.ts';
import { ALL_CURRENCIES } from '@/consts/currency.ts';
import type { AccountInfoResponse } from '@/models/account.ts';
import type { TransactionCategoryInfoResponse } from '@/models/transaction_category.ts';
import type { TransactionInfoResponse } from '@/models/transaction.ts';

export const MAX_BUDGET_AMOUNT = 9999999999999;

export function isBudgetConflictError(error: unknown): boolean {
    if (!error || typeof error !== 'object') return false;
    const info = error as { status?: unknown; response?: { status?: unknown } };
    return info.status === 409 || info.response?.status === 409;
}

/** Finexy stores all monetary amounts in hundredths. Parse decimal text without
 * multiplying a floating-point number, rounding, or accepting exponent notation. */
export function parseBudgetAmount(value: string): number | null {
    const text = value.trim();
    if (!/^(?:\d+)(?:\.\d{1,2})?$/.test(text) || text.length > 32) return null;
    const [whole = '', decimals = ''] = text.split('.');
    const amount = BigInt(whole) * 100n + BigInt(decimals.padEnd(2, '0'));
    return amount > BigInt(MAX_BUDGET_AMOUNT) ? null : Number(amount);
}

export function validBudgetMonth(yearMonth: number): boolean {
    return Number.isInteger(yearMonth) && yearMonth >= 200001 && yearMonth <= 999912 &&
        yearMonth % 100 >= 1 && yearMonth % 100 <= 12;
}

export function budgetMonthText(yearMonth: number): string {
    if (!validBudgetMonth(yearMonth)) throw new Error('请选择有效月份');
    return `${Math.trunc(yearMonth / 100)}-${String(yearMonth % 100).padStart(2, '0')}`;
}

export function budgetMonthFromText(text: string): number | null {
    if (!/^\d{4}-\d{2}$/.test(text)) return null;
    const value = Number(text.replace('-', ''));
    return validBudgetMonth(value) ? value : null;
}

export interface BudgetCategoryOption {
    readonly id: string;
    readonly name: string;
    readonly parentName: string;
}

export function budgetCategorySnapshot(response: Record<number, TransactionCategoryInfoResponse[]>): {
    all: Record<string, TransactionCategoryInfoResponse>;
    selectable: BudgetCategoryOption[];
} {
    if (!response || typeof response !== 'object' || Array.isArray(response)) throw new Error('分类响应不完整，请重试');
    const all: Record<string, TransactionCategoryInfoResponse> = {};
    const selectable: BudgetCategoryOption[] = [];
    const add = (category: TransactionCategoryInfoResponse, type: number, parentId: string): void => {
        if (!category || typeof category.id !== 'string' || !/^[1-9]\d*$/.test(category.id) || all[category.id] ||
            category.type !== type || category.parentId !== parentId || typeof category.hidden !== 'boolean' ||
            typeof category.name !== 'string' || (category.subCategories !== undefined && !Array.isArray(category.subCategories))) {
            throw new Error('分类响应归属或结构无效，请重试');
        }
        all[category.id] = category;
    };
    for (const [typeText, roots] of Object.entries(response)) {
        const type = Number(typeText);
        if (![CategoryType.Income, CategoryType.Expense, CategoryType.Transfer].includes(type) || !Array.isArray(roots)) {
            throw new Error('分类响应不完整，请重试');
        }
        for (const root of roots) {
            add(root, type, '0');
            for (const child of root.subCategories ?? []) {
                add(child, type, root.id);
                if (child.subCategories?.length) throw new Error('分类不是有效的末级结构，请重试');
                if (type === CategoryType.Expense && !root.hidden && !child.hidden) {
                    selectable.push({ id: child.id, name: child.name, parentName: root.name });
                }
            }
        }
    }
    return { all, selectable };
}

export function budgetAccountSnapshot(response: AccountInfoResponse[], ledgerId: string): Record<string, AccountInfoResponse> {
    if (!Array.isArray(response)) throw new Error('账户响应不完整，请重试');
    const all: Record<string, AccountInfoResponse> = {};
    const add = (account: AccountInfoResponse, parentId: string): void => {
        if (!account || typeof account.id !== 'string' || !/^[1-9]\d*$/.test(account.id) || all[account.id] ||
            (account.ledgerId ?? '0') !== ledgerId || account.parentId !== parentId ||
            ![AccountType.SingleAccount.type, AccountType.MultiSubAccounts.type].includes(account.type) ||
            (account.subAccounts !== undefined && !Array.isArray(account.subAccounts))) {
            throw new Error('账户响应归属或结构无效，请重试');
        }
        all[account.id] = account;
        if (account.type === AccountType.SingleAccount.type) {
            if (account.subAccounts?.length || !ALL_CURRENCIES[account.currency]) throw new Error('账户币种或末级结构无效，请重试');
        } else {
            for (const child of account.subAccounts ?? []) {
                if (child.type !== AccountType.SingleAccount.type) throw new Error('账户不是有效的末级结构，请重试');
                add(child, account.id);
            }
        }
    };
    for (const root of response) add(root, '0');
    return all;
}

export interface BudgetUsage {
    readonly expense: number | null;
    readonly categoryExpenses: Record<string, number>;
    readonly problem: string | null;
}

/** Only a complete monthly snapshot supplies zero. Hidden historical accounts
 * and categories remain part of spending; refunds are signed expenses. */
export function calculateBudgetUsage(
    items: readonly TransactionInfoResponse[], totalCount: number,
    accounts: Record<string, AccountInfoResponse>, categories: Record<string, TransactionCategoryInfoResponse>,
    ledgerId: string, currency: string
): BudgetUsage {
    const incomplete = (problem: string): BudgetUsage => ({ expense: null, categoryExpenses: {}, problem });
    if (!ALL_CURRENCIES[currency]) return incomplete('预算币种无效，无法计算使用率');
    if (!Array.isArray(items) || !Number.isSafeInteger(totalCount) || totalCount !== items.length) {
        return incomplete('本月流水未完整读取，无法计算使用率');
    }
    const seen = new Set<string>();
    let expense = 0n;
    const categoryExpenses = new Map<string, bigint>();
    for (const item of items) {
        if (!item || typeof item.id !== 'string' || !/^[1-9]\d*$/.test(item.id) || seen.has(item.id) ||
            (item.ledgerId ?? '0') !== ledgerId ||
            ![TransactionType.ModifyBalance, TransactionType.Income, TransactionType.Expense, TransactionType.Transfer].includes(item.type)) {
            return incomplete('本月流水归属或结构无效，无法计算使用率');
        }
        seen.add(item.id);
        if (item.type !== TransactionType.Expense) continue;
        const account = accounts[item.sourceAccountId];
        const category = categories[item.categoryId];
        if (!account || account.type !== AccountType.SingleAccount.type || (account.ledgerId ?? '0') !== ledgerId ||
            !ALL_CURRENCIES[account.currency] || !category || category.type !== CategoryType.Expense ||
            !Number.isSafeInteger(item.sourceAmount)) {
            return incomplete('部分支出的账户、分类或金额未完整读取，无法计算使用率');
        }
        if (account.currency !== currency) continue;
        const amount = BigInt(item.sourceAmount);
        expense += amount;
        categoryExpenses.set(item.categoryId, (categoryExpenses.get(item.categoryId) ?? 0n) + amount);
    }
    const safe = BigInt(Number.MAX_SAFE_INTEGER);
    if (expense < -safe || expense > safe || [...categoryExpenses.values()].some(value => value < -safe || value > safe)) {
        return incomplete('本月金额超出可精确汇总范围，无法计算使用率');
    }
    return { expense: Number(expense), categoryExpenses: Object.fromEntries([...categoryExpenses].map(([id, amount]) => [id, Number(amount)])), problem: null };
}
