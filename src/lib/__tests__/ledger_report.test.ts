import { describe, expect, it } from 'vitest';
import { ledgerReportFixturePage as fullPage } from './fixtures/ledger_report.ts';
import fixture from '../../../testdata/ledger_report/v1.json';
import type { LedgerReportMoney, LedgerReportQuery } from '@/models/ledger_report.ts';
import { copyLedgerReportQuery, formatLedgerReportAmount, ledgerReportQueryKey, validateLedgerReportAccumulatedDetails, validateLedgerReportPage, validateLedgerReportPreview } from '../ledger_report.ts';

const query: LedgerReportQuery = fixture.query;
const hash = 'ab'.repeat(32);
function money(currency: string, changes: Partial<Omit<LedgerReportMoney, 'currency'>> = {}): LedgerReportMoney {
    return { currency, income: '0', expense: '0', net: '0', adjustment: '0', transferIn: '0', transferOut: '0', ...changes };
}
function clone<T>(value: T): T { return structuredClone(value); }

describe('ledger report canonical request contract', () => {
    it('captures exact string IDs and normalizes ordering without mutating inputs', () => {
        const input = { ...query, types: [3, 2, 3] as const, accounts: { ids: ['9007199254740993', '2', '2'], mode: 'include' as const }, tags: [{ mode: 'any' as const, ids: ['501', '500'] }], currencies: ['USD', 'CNY', 'USD'] };
        const copied = copyLedgerReportQuery(input);
        expect(copied.accounts?.ids).toEqual(['2', '9007199254740993']);
        expect(copied.types).toEqual([2, 3]);
        expect(copied.currencies).toEqual(['CNY', 'USD']);
        expect(input.accounts.ids).toEqual(['9007199254740993', '2', '2']);
        expect(Object.isFrozen(copied.accounts?.ids)).toBe(true);
        expect(ledgerReportQueryKey(copied)).toBe(ledgerReportQueryKey({ ...copied, types: [3, 2], currencies: ['USD', 'CNY'] }));
        expect(ledgerReportQueryKey(copied)).not.toBe(ledgerReportQueryKey({ ...copied, ledgerId: '12' }));
    });
    it('accepts normalized response queries as captured request input while removing server-only fields', () => {
        const input = fullPage().query;
        expect(copyLedgerReportQuery(input)).not.toHaveProperty('utcRanges');
        expect(ledgerReportQueryKey(input)).toBe(ledgerReportQueryKey(query));
    });
    it.each([
        { ledgerId: 11 }, { ledgerId: '01' }, { ledgerId: '-1' }, { ledgerId: '9223372036854775808' },
        { startDate: '2026-02-30' }, { endDate: '2026-09-30' }, { endDate: '2037-10-31' },
        { timezone: '' }, { timezone: 'Local' }, { timezone: 'Unknown/Timezone' }, { types: [] }, { types: [5] },
        { accounts: { mode: 'include', ids: [] } }, { accounts: { mode: 'include', ids: ['0'] } },
        { categories: { mode: 'include', ids: [1002] } }, { tags: [{ mode: 'any', ids: ['0'] }] },
        { tags: [] }, { tags: [{ mode: 'none' }, { mode: 'any', ids: ['500'] }] }, { currencies: [] }, { currencies: ['cny'] }, { hidden: 'mask' }
    ])('rejects malformed query %j', patch => {
        expect(() => copyLedgerReportQuery({ ...query, ...patch } as unknown as LedgerReportQuery)).toThrow();
    });
    it('preserves the shared fixture five-dimensional query contracts without coercing amounts', () => {
        for (const scenario of fixture.cases) {
            const copied = copyLedgerReportQuery({ ...query, ...scenario.query } as LedgerReportQuery);
            expect(copied.ledgerId).toBe(fixture.query.ledgerId);
            expect(copied.timezone).toBe(fixture.timezone);
        }
        expect(fixture.expected.totals[0]!.income).toBe('10000');
    });
});

describe('ledger report response integrity', () => {
    it('validates shared fixture rows, original currencies, refunds, transfer legs and adjustment target', () => {
        const value = fullPage();
        const page = validateLedgerReportPage(value, query, 0, 50);
        expect(page.details.map(row => row.id)).toEqual(fixture.expected.detailIds);
        expect(page.totals).toEqual(fixture.expected.totals);
        expect(page.details.find(row => row.id === '5')?.physicalIds).toEqual(fixture.expected.transfer.physicalIds);
        expect(page.details.find(row => row.id === '3')?.source?.amount).toBe('-500');
        expect(Object.isFrozen(page.details[0])).toBe(true);
        expect(page).not.toBe(value);
    });

    it('drills into uncategorized rows with string category zero and no fabricated resource', () => {
        const value = fullPage();
        const filter = { ids: ['0'], mode: 'include' as const, includeDescendants: true };
        const request: LedgerReportQuery = { ...query, categories: filter };
        const totals = [money('CNY', { adjustment: '1000' })];
        const details = value.details.filter(row => row.categoryId === '0');
        const scoped = { ...value, query: { ...value.query, categories: filter }, details, matchedCount: 1, returnedCount: 1,
            totals, trend: [{ key: '2026-10-10', count: 1, money: totals }], categories: [{ key: '0', count: 1, money: totals }],
            tagGroups: [{ key: 'none', count: 1, money: totals }], categoryMetadata: [] };
        const page = validateLedgerReportPage(scoped, request, 0);
        expect(page.details.map(row => row.id)).toEqual(['9']); expect(page.categoryMetadata).toEqual([]);
        expect(ledgerReportQueryKey(request)).not.toBe(ledgerReportQueryKey(query));
        expect(() => validateLedgerReportPage({ ...value, query: scoped.query }, request, 0)).toThrow();
    });
    it('keeps all aggregate totals on partial pages and requires exact subsequent baseline', () => {
        const value = fullPage();
        const first = { ...value, details: value.details.slice(0, 2), returnedCount: 2, nextOffset: 2, detailsComplete: false };
        expect(validateLedgerReportPage(first, query, 0, 2).totals).toEqual(fixture.expected.totals);
        const second = { ...value, details: value.details.slice(2, 4), offset: 2, returnedCount: 2, nextOffset: 4, detailsComplete: false };
        expect(validateLedgerReportPage(second, query, 2, 2, hash).details.map(row => row.id)).toEqual(['7', '5']);
        expect(() => validateLedgerReportPage(second, query, 2, 2)).toThrow();
        expect(() => validateLedgerReportPage(second, query, 2, 2, 'ff'.repeat(32))).toThrow();
    });
    it('accepts Go omitempty group names while retaining the exact resource ID and metadata label', () => {
        const value = fullPage();
        const page = { ...value, categoryMetadata: value.categoryMetadata.map(row => row.id === '1002' ? { ...row, name: '' } : row),
            details: value.details.map(row => row.categoryId === '1002' ? { ...row, categoryName: '' } : row),
            categories: value.categories.map(row => row.key === '1002' ? { key: row.key, count: row.count, money: row.money } : row) };
        expect(validateLedgerReportPage(page, query, 0).categories.find(row => row.key === '1002')?.name).toBeUndefined();
    });
    it('accepts a proven real empty snapshot but does not repair missing data to zero', () => {
        const empty = { ...fullPage(), matchedCount: 0, returnedCount: 0, details: [], totals: [], trend: [], categories: [], tagGroups: [] };
        expect(validateLedgerReportPage(empty, query, 0).matchedCount).toBe(0);
        expect(() => validateLedgerReportPage({ ...empty, totals: undefined }, query, 0)).toThrow();
        expect(() => validateLedgerReportPage({ ...empty, dataComplete: false }, query, 0)).toThrow();
    });
    it.each([
        ['wrong ledger', (page: Record<string, unknown>) => { page['query'] = { ...fullPage().query, ledgerId: '12' }; }],
        ['wrong query filter', (page: Record<string, unknown>) => { page['query'] = { ...fullPage().query, types: [3] }; }],
        ['missing normalized query field', (page: Record<string, unknown>) => { const q = { ...fullPage().query } as Record<string, unknown>; delete q['hidden']; page['query'] = q; }],
        ['incomplete', (page: Record<string, unknown>) => { page['dataComplete'] = false; }],
        ['invalid hash', (page: Record<string, unknown>) => { page['snapshotHash'] = 'wrong'; }],
        ['wrong count', (page: Record<string, unknown>) => { page['returnedCount'] = 7; }],
        ['wrong next', (page: Record<string, unknown>) => { page['nextOffset'] = 8; }],
        ['wrong complete', (page: Record<string, unknown>) => { page['detailsComplete'] = false; }],
        ['missing metadata', (page: Record<string, unknown>) => { page['accounts'] = []; }],
        ['missing parent', (page: Record<string, unknown>) => { page['categoryMetadata'] = fullPage().categoryMetadata.filter(row => row.id !== '1001'); }],
        ['missing tag dictionary', (page: Record<string, unknown>) => { page['readableTags'] = []; }],
        ['additive tags', (page: Record<string, unknown>) => { page['tagGroupsAdditive'] = true; }],
        ['wrong role', (page: Record<string, unknown>) => { page['memberRole'] = 5; }],
        ['missing permission', (page: Record<string, unknown>) => { delete page['exportAllowed']; }],
        ['wrong group count', (page: Record<string, unknown>) => { page['trend'] = [{ ...fullPage().trend[0], count: 7 }]; }],
        ['wrong net', (page: Record<string, unknown>) => { page['totals'] = [{ ...fixture.expected.totals[0], net: '8801' }, fixture.expected.totals[1]]; }],
        ['lossy numeric amount', (page: Record<string, unknown>) => { page['totals'] = [{ ...fixture.expected.totals[0], income: 10000 }, fixture.expected.totals[1]]; }],
        ['mismatched grouped money', (page: Record<string, unknown>) => { page['trend'] = [{ ...fullPage().trend[0], money: [money('CNY', { income: '1', net: '1' })] }]; }]
    ])('rejects %s', (_name, mutate) => {
        const page = clone(fullPage()) as unknown as Record<string, unknown>;
        mutate(page);
        expect(() => validateLedgerReportPage(page, query, 0)).toThrow();
    });
    it.each([
        { id: 9 }, { physicalIds: ['9', '9'] }, { time: 1791633609 }, { sequence: '1791633610000' },
        { date: '2026-10-11' }, { hidden: true }, { categoryId: '1002' }, { balanceTarget: '999' },
        { source: null }, { recorderUid: '0' }, { adjustment: '9223372036854775808' }
    ])('rejects malformed logical detail %j', changes => {
        const page = fullPage();
        expect(() => validateLedgerReportPage({ ...page, details: [{ ...page.details[0], ...changes }, ...page.details.slice(1)] }, query, 0)).toThrow();
    });
    it('rejects repeated logical IDs, physical transfer reuse and reordered details', () => {
        const page = fullPage();
        expect(() => validateLedgerReportPage({ ...page, details: [page.details[0], page.details[0], ...page.details.slice(2)] }, query, 0)).toThrow();
        const transfer = { ...page.details[3], physicalIds: ['5', '9'] };
        expect(() => validateLedgerReportPage({ ...page, details: [...page.details.slice(0, 3), transfer, ...page.details.slice(4)] }, query, 0)).toThrow();
        expect(() => validateLedgerReportPage({ ...page, details: [...page.details].reverse() }, query, 0)).toThrow();
    });
    it('rejects a correctly echoed filter whose returned rows escape its scope', () => {
        const page = fullPage();
        const filtered: LedgerReportQuery = { ...query, accounts: { mode: 'include', ids: ['101'] } };
        expect(() => validateLedgerReportPage({ ...page, query: { ...page.query, accounts: { ...filtered.accounts, includeDescendants: false } } }, filtered, 0)).toThrow();
    });
    it('allows more than 200 accumulated rows while preserving the HTTP page and total-source bounds', () => {
        const base = fullPage();
        const income = base.details.find(row => row.id === '1')!;
        const rows = Array.from({ length: 201 }, (_, index) => ({ ...income, id: String(1000 + index), physicalIds: [String(1000 + index)], sequence: (BigInt(income.time) * 1000n + BigInt(index)).toString(), source: { ...income.source!, amount: '100' } })).reverse();
        const totals = [money('CNY', { income: '20100', net: '20100' })];
        const baseline = { ...base, matchedCount: rows.length, details: rows.slice(0, 50), returnedCount: 50, nextOffset: 50, detailsComplete: false, totals,
            trend: [{ key: '2026-10-10', count: 201, money: totals }], categories: [{ key: '1011', name: income.categoryName, count: 201, money: totals }], tagGroups: [{ key: 'none', count: 201, money: totals }] };
        const page = validateLedgerReportPage(baseline, query, 0, 50);
        expect(validateLedgerReportAccumulatedDetails(rows, page, true)).toHaveLength(201);
        expect(() => validateLedgerReportPage({ ...baseline, details: rows }, query, 0, 201)).toThrow();
        expect(() => validateLedgerReportAccumulatedDetails(Array.from({ length: 50001 }, () => income), page, true)).toThrow();
        expect(() => validateLedgerReportAccumulatedDetails(rows.slice(0, 200), page, true)).toThrow();
    });
});

describe('ledger report export preview and exact formatting', () => {
    const preview = () => {
        const page = fullPage();
        return { query: page.query, snapshotHash: hash, matchedCount: page.matchedCount, totals: page.totals, amountUnit: 'minor', includesHiddenAmounts: false, expiresAt: '1791634200', previewToken: 'cGF5bG9hZA.c2lnbmF0dXJl' };
    };
    it('binds the actual preview to the validated page rather than accepting an unrelated receipt', () => {
        const page = validateLedgerReportPage(fullPage(), query, 0);
        expect(validateLedgerReportPreview(preview(), page).snapshotHash).toBe(hash);
        for (const patch of [{ snapshotHash: 'ff'.repeat(32) }, { matchedCount: 0 }, { amountUnit: 'major' }, { includesHiddenAmounts: true }, { expiresAt: 1791634200 }, { previewToken: '' }, { totals: [] }]) {
            expect(() => validateLedgerReportPreview({ ...preview(), ...patch }, page)).toThrow();
        }
        expect(() => validateLedgerReportPreview(preview(), { ...page, exportAllowed: false })).toThrow();
    });
    it.each([
        ['0', '0.00'], ['1', '0.01'], ['-1', '-0.01'], ['10000', '100.00'], ['-500', '-5.00'],
        ['9007199254740993', '90071992547409.93'], ['9223372036854775807', '92233720368547758.07'], ['-9223372036854775808', '-92233720368547758.08']
    ])('formats %s without a floating-point conversion', (input, expected) => {
        expect(formatLedgerReportAmount(input)).toBe(expected);
    });
    it.each(['', '-0', '01', '+1', '1.00', '1e3', 'NaN', '9223372036854775808', '-9223372036854775809'])('rejects ambiguous/out-of-range money %s', value => {
        expect(() => formatLedgerReportAmount(value)).toThrow();
    });
});
