import { describe, expect, it } from 'vitest';
import fixture from '../../../testdata/ledger_report/v1.json';
import { ledgerReportBarWidth, ledgerReportDrillQuery, ledgerReportMonthRange, ledgerReportPanelOptions, ledgerReportPercentageChange, previousLedgerReportMonthQuery } from '../ledger_report_panel.ts';

describe('report production panel exact date, drill and display helpers', () => {
    it.each([['2024-02', '2024-02-29'], ['2025-02', '2025-02-28'], ['2026-12', '2026-12-31']])('uses calendar month boundaries for %s', (month, last) => {
        expect(ledgerReportMonthRange(month)).toEqual({ startDate: `${month}-01`, endDate: last });
    });
    it('keeps every nondate filter when comparing across year and leap-month boundaries', () => {
        const input = { ...fixture.query, startDate: '2027-01-01', endDate: '2027-01-31', timezone: 'America/New_York', tags: [{ mode: 'all' as const, ids: ['500', '501'] }], hidden: 'include' as const };
        const previous = previousLedgerReportMonthQuery(input);
        expect(previous.startDate).toBe('2026-12-01'); expect(previous.endDate).toBe('2026-12-31');
        expect(previous.timezone).toBe(input.timezone); expect(previous.tags).toEqual(input.tags); expect(previous.hidden).toBe('include');
        expect(() => previousLedgerReportMonthQuery({ ...input, startDate: '2027-01-02' })).toThrow();
    });
    it('narrows daily and tagged details without dropping the other dimensions', () => {
        const input = { ...fixture.query, accounts: { mode: 'exclude' as const, ids: ['111'] }, tags: [{ mode: 'notAny' as const, ids: ['501'] }] };
        const day = ledgerReportDrillQuery(input, 'day', '2026-10-10');
        expect(day.accounts?.ids).toEqual(['111']); expect(day.tags).toEqual(input.tags); expect(day.startDate).toBe(day.endDate);
        expect(ledgerReportDrillQuery(day, 'tag', '500').tags).toEqual([...input.tags, { mode: 'any', ids: ['500'] }].sort((a, b) => a.mode.localeCompare(b.mode)));
        expect(() => ledgerReportDrillQuery(day, 'day', '2026-10-11')).toThrow();
    });
    it('does not broaden current type or currency filters while drilling monetary summaries', () => {
        const input = { ...fixture.query, types: [3] as const, currencies: ['CNY'] };
        expect(ledgerReportDrillQuery(input, 'net', undefined, 'CNY').types).toEqual([3]);
        expect(() => ledgerReportDrillQuery(input, 'income', undefined, 'CNY')).toThrow();
        expect(() => ledgerReportDrillQuery(input, 'expense', undefined, 'USD')).toThrow();
    });
    it('drills category zero without approximating it as a transaction type', () => {
        const input = { ...fixture.query, accounts: { ids: ['100'], mode: 'exclude' as const, includeDescendants: false }, tags: [{ mode: 'notAny' as const, ids: ['500'] }] };
        const result = ledgerReportDrillQuery(input, 'category', '0');
        expect(result.categories).toEqual({ ids: ['0'], mode: 'include', includeDescendants: false });
        expect(result.types).toEqual([1, 2, 3, 4]); expect(result.accounts).toEqual(input.accounts);
        expect(result.tags).toEqual(input.tags); expect(result.startDate).toBe(input.startDate);
    });
    it('computes percentages and visual widths from integer strings above JS precision', () => {
        expect(ledgerReportBarWidth('9007199254740993', '9223372036854775807')).toBe('0.09%');
        expect(ledgerReportBarWidth('-50', '100')).toBe('50.00%');
        expect(ledgerReportBarWidth('0', '0')).toBe('0%');
        expect(() => ledgerReportBarWidth('101', '100')).toThrow();
        expect(ledgerReportPercentageChange('150', '100')).toBe('+50.00%');
        expect(ledgerReportPercentageChange('100', '0')).toBeNull();
        expect(ledgerReportPercentageChange('-50', '-100')).toBe('+50.00%');
    });
    it('keeps hidden/current-ledger historical options and separates same-name categories by parent and ID', () => {
        const accounts = fixture.accounts.filter(row => row.ledgerId === '11').map(row => ({ ...row, parentId: row.parentId ?? '0', hidden: row.hidden ?? false, subAccounts: undefined }));
        const group = accounts.find(row => row.id === '110')!;
        const child = accounts.find(row => row.id === '111')!;
        const tree = accounts.filter(row => row.id !== child.id).map(row => row.id === group.id ? { ...row, subAccounts: [child] } : row);
        const rows = fixture.categories.filter(row => !row.databaseOnly);
        const categories: Record<number, unknown[]> = {};
        for (const root of rows.filter(row => !row.parentId)) {
            (categories[root.type] ??= []).push({ ...root, parentId: '0', hidden: false, subCategories: rows.filter(row => row.parentId === root.id).map(row => ({ ...row, hidden: false })) });
        }
        const result = ledgerReportPanelOptions(tree, categories, '11');
        expect(result.categories.find(row => row.id === '0')?.label).toContain('无分类');
        expect(result.accounts.find(row => row.id === '111')?.label).toContain('已隐藏');
        expect(result.categories.filter(row => ['1002', '1005'].includes(row.id)).map(row => row.label)).toHaveLength(2);
        expect(result.categories.find(row => row.id === '1002')?.label).not.toBe(result.categories.find(row => row.id === '1005')?.label);
        expect(() => ledgerReportPanelOptions(tree, categories, '12')).toThrow();
    });
    it('offers the uncategorized query even when the ledger has no category resources', () => {
        const result = ledgerReportPanelOptions([], {}, '0');
        expect(result.categories.map(row => row.id)).toEqual(['0']);
    });
});
