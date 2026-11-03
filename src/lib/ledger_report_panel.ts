import type { AccountInfoResponse } from '@/models/account.ts';
import type { TransactionCategoryInfoResponse } from '@/models/transaction_category.ts';
import type { LedgerReportQuery, LedgerReportType } from '@/models/ledger_report.ts';
import { budgetAccountSnapshot, budgetCategorySnapshot } from '@/lib/monthly_budget.ts';
import { copyLedgerReportQuery, formatLedgerReportAmount } from '@/lib/ledger_report.ts';

export interface LedgerReportOption {
    readonly id: string;
    readonly label: string;
    readonly parentId: string;
    readonly hidden: boolean;
    readonly currency?: string;
}

export function ledgerReportPanelOptions(accounts: unknown, categories: unknown, ledgerId: string): { accounts: LedgerReportOption[]; categories: LedgerReportOption[] } {
    const accountMap = budgetAccountSnapshot(accounts as AccountInfoResponse[], ledgerId);
    const categoryMap = budgetCategorySnapshot(categories as Record<number, TransactionCategoryInfoResponse[]>).all;
    const accountOptions = Object.values(accountMap).map(account => ({ id: account.id, parentId: account.parentId,
        label: `${account.parentId === '0' ? '' : `${accountMap[account.parentId]?.name} / `}${account.name} · ${account.type === 2 ? '账户组' : account.currency}${account.hidden ? ' · 已隐藏' : ''} · #${account.id}`,
        hidden: account.hidden, ...(account.type === 1 ? { currency: account.currency } : {}) }));
    const names: Record<number, string> = { 1: '收入', 2: '支出', 3: '转账' };
    const categoryOptions = Object.values(categoryMap).map(category => ({ id: category.id, parentId: category.parentId,
        label: `${names[category.type]} · ${category.parentId === '0' ? '' : `${categoryMap[category.parentId]?.name} / `}${category.name}${category.hidden ? ' · 已隐藏' : ''} · #${category.id}`,
        hidden: category.hidden }));
    // Zero is a query condition, not an owner category resource from metadata.
    categoryOptions.unshift({ id: '0', parentId: '0', label: '无分类（余额调整或储蓄目标转账） · #0', hidden: false });
    return { accounts: accountOptions, categories: categoryOptions };
}

export function ledgerReportMonthRange(month: string): { startDate: string; endDate: string } {
    if (!/^\d{4}-(?:0[1-9]|1[0-2])$/.test(month) || month < '1970-01' || month > '9998-12') throw new Error('请选择有效月份');
    const startDate = `${month}-01`;
    const next = new Date(`${startDate}T00:00:00Z`);
    next.setUTCMonth(next.getUTCMonth() + 1);
    next.setUTCDate(0);
    return { startDate, endDate: next.toISOString().slice(0, 10) };
}

export function previousLedgerReportMonthQuery(input: LedgerReportQuery): LedgerReportQuery {
    const query = copyLedgerReportQuery(input), range = ledgerReportMonthRange(query.startDate.slice(0, 7));
    if (range.startDate !== query.startDate || range.endDate !== query.endDate || query.startDate === '1970-01-01') throw new Error('请先选择完整月份，再比较上月');
    const date = new Date(`${range.startDate}T00:00:00Z`);
    date.setUTCMonth(date.getUTCMonth() - 1);
    return copyLedgerReportQuery({ ...query, ...ledgerReportMonthRange(date.toISOString().slice(0, 7)) });
}

export type LedgerReportDrill = 'day' | 'category' | 'tag' | 'income' | 'expense' | 'net' | 'transfer' | 'adjustment';

/** Every branch narrows an already displayed result; existing date/account/tag clauses remain. */
export function ledgerReportDrillQuery(input: LedgerReportQuery, kind: LedgerReportDrill, key?: string, currency?: string): LedgerReportQuery {
    const query = copyLedgerReportQuery(input);
    if (kind === 'day') {
        if (!key || key < query.startDate || key > query.endDate) throw new Error('此日期不在当前报告范围内');
        return copyLedgerReportQuery({ ...query, startDate: key, endDate: key });
    }
    if (kind === 'category') {
        return copyLedgerReportQuery({ ...query, categories: { mode: 'include', ids: [key ?? ''], includeDescendants: false } });
    }
    if (kind === 'tag') return copyLedgerReportQuery({ ...query, tags: key === 'none' ? [{ mode: 'none' }] : [...(query.tags ?? []), { mode: 'any', ids: [key ?? ''] }] });
    const types: readonly LedgerReportType[] = kind === 'income' ? [2] : kind === 'expense' ? [3] : kind === 'net' ? [2, 3] : kind === 'transfer' ? [4] : [1];
    const narrowed = query.types!.filter(type => types.includes(type));
    if (!narrowed.length) throw new Error('当前类型筛选没有此类流水');
    if (currency && query.currencies && !query.currencies.includes(currency)) throw new Error('此币种仅随跨币种转账展示，请保留原币种筛选查看转账');
    return copyLedgerReportQuery({ ...query, types: narrowed, ...(currency ? { currencies: [currency] } : {}) });
}

export function ledgerReportBarWidth(amount: string, maximum: string): string {
    formatLedgerReportAmount(amount); formatLedgerReportAmount(maximum);
    const value = BigInt(amount), limit = BigInt(maximum);
    const absolute = value < 0n ? -value : value, peak = limit < 0n ? -limit : limit;
    if (peak === 0n) return '0%';
    const ratio = absolute * 10000n / peak;
    if (ratio > 10000n) throw new Error('图表范围与金额不一致');
    return `${ratio / 100n}.${(ratio % 100n).toString().padStart(2, '0')}%`;
}

export function ledgerReportPercentageChange(current: string, previous: string): string | null {
    formatLedgerReportAmount(current); formatLedgerReportAmount(previous);
    const before = BigInt(previous);
    if (before === 0n) return null;
    const difference = BigInt(current) - before;
    const hundredths = difference * 10000n / (before < 0n ? -before : before);
    const absolute = hundredths < 0n ? -hundredths : hundredths;
    return `${hundredths > 0n ? '+' : hundredths < 0n ? '-' : ''}${absolute / 100n}.${(absolute % 100n).toString().padStart(2, '0')}%`;
}
