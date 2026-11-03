import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import fixture from '../../../testdata/ledger_report/v1.json';
import { ledgerReportFixturePage } from '@/lib/__tests__/fixtures/ledger_report.ts';
import { LEDGER_REPORT_CSV_COLUMNS } from '@/lib/ledger_report_download.ts';
import type { LedgerReportPage, LedgerReportQuery } from '@/models/ledger_report.ts';

const identity = vi.hoisted(() => ({ server: 'https://fixture.invalid/report', username: 'synthetic-owner' }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => JSON.stringify([identity.server, identity.username]),
    getCurrentUserInfo: () => ({ username: identity.username })
}));
vi.mock('@/stores/ledger.ts', async () => {
    const { defineStore } = await import('pinia');
    const { ref } = await import('vue');
    return { useLedgersStore: defineStore('reportPaginationLedgers', () => ({ selectedLedgerId: ref('11') })) };
});
vi.mock('@/lib/services.ts', () => ({ default: { queryLedgerReport: vi.fn(), previewLedgerReportExport: vi.fn(), downloadLedgerReport: vi.fn() } }));
import services from '@/lib/services.ts';
import { useLedgerReportStore } from '../ledgerReport.ts';

const query: LedgerReportQuery = fixture.query;
const response = (result: unknown) => ({ data: { success: true, result } }) as never;
function page(offset: number, count = 2): LedgerReportPage {
    const full = ledgerReportFixturePage(), end = Math.min(offset + count, full.matchedCount);
    return { ...full, details: full.details.slice(offset, end), offset, returnedCount: end - offset, nextOffset: end < full.matchedCount ? end : null, detailsComplete: offset === 0 && end === full.matchedCount };
}
function deferred() {
    let resolve!: (value: never) => void;
    const promise = new Promise<never>(done => { resolve = done; });
    return { promise, resolve };
}
function proof(value: LedgerReportPage) {
    return { query: value.query, snapshotHash: value.snapshotHash, matchedCount: value.matchedCount, totals: value.totals, amountUnit: 'minor', includesHiddenAmounts: false, expiresAt: String(Math.floor(Date.now() / 1000) + 600), previewToken: 'synthetic.proof' };
}

describe('ledger report real validator pagination and asynchronous identity guards', () => {
    beforeEach(() => {
        setActivePinia(createPinia()); vi.clearAllMocks();
        identity.server = 'https://fixture.invalid/report'; identity.username = 'synthetic-owner';
    });

    it('sends one baseline on every page and completes the shared financial fixture exactly once', async () => {
        const store = useLedgerReportStore();
        for (const offset of [0, 2, 4, 6]) {
            vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(offset)));
            if (offset === 0) await store.load(query, 2);
            else await store.loadNext(query, 2);
            const request = vi.mocked(services.queryLedgerReport).mock.lastCall?.[0];
            expect(request?.offset).toBe(offset);
            expect(request?.expectedSnapshotHash).toBe(offset ? page(0).snapshotHash : undefined);
            expect(store.detailsCompleteFor(query)).toBe(offset === 6);
            expect(store.reportFor(query)?.totals).toEqual(fixture.expected.totals);
        }
        expect(store.detailsFor(query).map(row => row.id)).toEqual(fixture.expected.detailIds);
        await expect(store.loadNext(query, 2)).rejects.toThrow();
        expect(services.queryLedgerReport).toHaveBeenCalledTimes(4);
    });

    it.each(['metadata', 'totals', 'role'] as const)('rejects inconsistent %s even when a response repeats the old hash', async scenario => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0))); await store.load(query, 2);
        let next = page(2);
        if (scenario === 'metadata') {
            next = { ...next, accounts: next.accounts.map(row => row.id === '100' ? { ...row, name: 'Changed synthetic name' } : row),
                details: next.details.map(row => ({ ...row, source: row.source?.accountId === '100' ? { ...row.source, accountName: 'Changed synthetic name' } : row.source })) };
        } else if (scenario === 'totals') {
            const change = (rows: typeof next.totals) => rows.map(row => row.currency === 'CNY' ? { ...row, income: (BigInt(row.income) + 1n).toString(), net: (BigInt(row.net) + 1n).toString() } : row);
            next = { ...next, totals: change(next.totals), trend: next.trend.map(row => ({ ...row, money: change(row.money) })),
                categories: next.categories.map(row => row.key === '1011' ? { ...row, money: change(row.money) } : row), tagGroups: next.tagGroups.map(row => row.key === 'none' ? { ...row, money: change(row.money) } : row) };
        } else next = { ...next, memberRole: 4 };
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(next));
        await expect(store.loadNext(query, 2)).rejects.toThrow();
        expect(store.reportFor(query)).toBeNull(); expect(store.detailsFor(query)).toEqual([]);
    });

    it.each(['logical', 'physical'] as const)('rejects a %s ID repeated across individually valid pages', async scenario => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0))); await store.load(query, 2);
        const full = ledgerReportFixturePage();
        const next = page(2);
        const details = scenario === 'logical' ? [full.details[0]!, full.details[2]!] : [next.details[0]!, { ...next.details[1]!, physicalIds: ['5', '9'] }];
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response({ ...next, details }));
        await expect(store.loadNext(query, 2)).rejects.toThrow();
        expect(store.reportFor(query)).toBeNull(); expect(store.detailsCompleteFor(query)).toBe(false);
    });

    it('rejects server revocation without displaying a partial total as complete', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0))); await store.load(query, 2);
        vi.mocked(services.queryLedgerReport).mockRejectedValueOnce({ response: { status: 403 } });
        await expect(store.loadNext(query, 2)).rejects.toThrow();
        expect(store.reportFor(query)).toBeNull(); expect(store.detailsFor(query)).toEqual([]);
    });

    it('checks cumulative detail money after the final page rather than trusting each local page count', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0, 4))); await store.load(query, 4);
        const next = page(4, 4);
        // This is structurally valid and still matches its filters; its signed financial value disagrees with the baseline.
        const changed = { ...next, details: next.details.map(row => row.id === '1' ? { ...row, source: { ...row.source!, amount: '10001' } } : row) };
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(changed));
        await expect(store.loadNext(query, 4)).rejects.toThrow();
        expect(store.detailsCompleteFor(query)).toBe(false); expect(store.reportFor(query)).toBeNull();
    });

    it.each(['account', 'server'] as const)('discards a page response after the %s changes', async changed => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0))); await store.load(query, 2);
        const waiting = deferred(); vi.mocked(services.queryLedgerReport).mockReturnValueOnce(waiting.promise);
        const result = store.loadNext(query, 2);
        if (changed === 'account') identity.username = 'different-synthetic-user';
        else identity.server += '/different';
        waiting.resolve(response(page(2))); await expect(result).rejects.toThrow();
        expect(store.reportFor(query)).toBeNull(); expect(store.detailsFor(query)).toEqual([]);
    });

    it('discards an export preview when the captured account changes during the request', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0))); await store.load(query, 2);
        const waiting = deferred(); vi.mocked(services.previewLedgerReportExport).mockReturnValueOnce(waiting.promise);
        const result = store.prepareExport(query); identity.server += '/different';
        waiting.resolve(response(proof(page(0)))); await expect(result).rejects.toThrow();
        expect(store.previewFor(query)).toBeNull(); expect(services.downloadLedgerReport).not.toHaveBeenCalled();
    });

    it('discards late download bytes before any save result reaches another account', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(0))); await store.load(query, 2);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response(proof(page(0)))); const displayed = await store.prepareExport(query);
        const waiting = deferred(); vi.mocked(services.downloadLedgerReport).mockReturnValueOnce(waiting.promise);
        const result = store.download(query, { confirmed: true, snapshotHash: displayed.snapshotHash, previewToken: displayed.previewToken }); identity.username = 'different-synthetic-user';
        waiting.resolve({ status: 200, headers: {}, data: new Blob(['synthetic bytes']) } as never);
        await expect(result).rejects.toThrow(); expect(store.previewFor(query)).toBeNull(); expect(store.reportFor(query)).toBeNull();
    });
    it('checks identity again after asynchronous Blob reading finishes', async () => {
        const store = useLedgerReportStore();
        const empty = { ...ledgerReportFixturePage(), matchedCount: 0, details: [], totals: [], trend: [], categories: [], tagGroups: [], returnedCount: 0 };
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(empty)); await store.load(query);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response(proof(empty))); const displayed = await store.prepareExport(query);
        const content = LEDGER_REPORT_CSV_COLUMNS.join(',') + '\n';
        const blob = new Blob([content]);
        let release!: (value: ArrayBuffer) => void;
        const reading = vi.spyOn(blob, 'arrayBuffer').mockImplementation(() => new Promise<ArrayBuffer>(resolve => { release = resolve; }));
        vi.mocked(services.downloadLedgerReport).mockResolvedValueOnce({ status: 200, data: blob, headers: {
            'content-type': 'text/csv; charset=utf-8', 'content-disposition': 'attachment; filename="finexy-report-11-2026-10-01-2026-10-31.csv"',
            'x-finexy-report-snapshot': empty.snapshotHash, 'x-finexy-report-count': '0', 'x-finexy-report-amount-unit': 'minor'
        } } as never);
        const result = store.download(query, { confirmed: true, snapshotHash: displayed.snapshotHash, previewToken: displayed.previewToken });
        await vi.waitFor(() => expect(reading).toHaveBeenCalled());
        identity.username = 'different-synthetic-user';
        release(new TextEncoder().encode(content).buffer);
        await expect(result).rejects.toThrow(); expect(store.reportFor(query)).toBeNull();
    });
    it('does not apply an older confirmation dialog to a newly prepared preview of the same query', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValue(response(page(0)));
        await store.load(query, 2);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response({ ...proof(page(0)), previewToken: 'synthetic.first' }));
        const displayed = await store.prepareExport(query);
        await store.load(query, 2);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response({ ...proof(page(0)), previewToken: 'synthetic.second' }));
        const latest = await store.prepareExport(query);
        expect(latest.snapshotHash).toBe(displayed.snapshotHash);
        await expect(store.download(query, { confirmed: true, snapshotHash: displayed.snapshotHash, previewToken: displayed.previewToken })).rejects.toThrow();
        expect(services.downloadLedgerReport).not.toHaveBeenCalled();
        expect(store.previewFor(query)?.previewToken).toBe(latest.previewToken);
    });
});
