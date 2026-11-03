import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { LedgerReportPage, LedgerReportQuery } from '@/models/ledger_report.ts';
import { LEDGER_REPORT_CSV_COLUMNS } from '@/lib/ledger_report_download.ts';

const scope = vi.hoisted(() => ({ server: 'https://synthetic.invalid/a', username: 'synthetic-owner' }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => JSON.stringify([scope.server, scope.username]),
    getCurrentUserInfo: () => ({ username: scope.username })
}));
vi.mock('@/stores/ledger.ts', async () => {
    const { defineStore } = await import('pinia');
    const { ref } = await import('vue');
    return { useLedgersStore: defineStore('reportTestLedgers', () => ({ selectedLedgerId: ref('11') })) };
});
vi.mock('@/lib/services.ts', () => ({ default: { queryLedgerReport: vi.fn(), previewLedgerReportExport: vi.fn(), downloadLedgerReport: vi.fn() } }));
import services from '@/lib/services.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useLedgerReportStore } from '../ledgerReport.ts';

const query: LedgerReportQuery = { ledgerId: '11', startDate: '2026-10-01', endDate: '2026-10-31', timezone: 'UTC' };
const response = (result: unknown) => ({ data: { success: true, result } }) as never;
const page = (request = query): LedgerReportPage => ({ memberRole: 1,
    query: { ...request, version: 1, types: [1, 2, 3, 4], accounts: null, categories: null, tags: null, currencies: null,
        hidden: request.hidden ?? 'exclude', utcRanges: [{ start: '1790812800', endExclusive: '1793491200' }] },
    snapshotHash: 'a'.repeat(64), dataComplete: true, matchedCount: 0, details: [], totals: [], trend: [], categories: [],
    tagGroups: [], tagGroupsAdditive: false, readableTags: [], accounts: [], categoryMetadata: [], offset: 0,
    returnedCount: 0, nextOffset: null, detailsComplete: true, exportAllowed: true });
const proof = (value = page()) => ({ query: value.query, snapshotHash: value.snapshotHash, matchedCount: value.matchedCount,
    totals: value.totals, amountUnit: 'minor', includesHiddenAmounts: value.query.hidden === 'include',
    expiresAt: String(Math.floor(Date.now() / 1000) + 600), previewToken: 'synthetic.proof' });
const confirmation = () => ({ confirmed: true as const, snapshotHash: proof().snapshotHash, previewToken: proof().previewToken });
function deferred() {
    let resolve!: (value: never) => void;
    const promise = new Promise<never>(done => { resolve = done; });
    return { promise, resolve };
}
function emptyCSV(value = page()) {
    return { status: 200, data: new Blob([LEDGER_REPORT_CSV_COLUMNS.join(',') + '\n']), headers: {
        'content-type': 'text/csv; charset=utf-8', 'content-disposition': `attachment; filename="finexy-report-${value.query.ledgerId}-${value.query.startDate}-${value.query.endDate}.csv"`,
        'x-finexy-report-snapshot': value.snapshotHash, 'x-finexy-report-count': '0', 'x-finexy-report-amount-unit': 'minor'
    } } as never;
}

describe('ledger report identity, snapshot and explicit export boundaries', () => {
    beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); scope.username = 'synthetic-owner'; scope.server = 'https://synthetic.invalid/a'; });

    it('keeps explicit default and large string ledger IDs through the request', async () => {
        const store = useLedgerReportStore();
        for (const ledgerId of ['0', '9007199254740993']) {
            useLedgersStore().selectedLedgerId = ledgerId;
            const request = { ...query, ledgerId };
            vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page(request)));
            await store.load(request);
            expect(store.reportFor(request)?.query.ledgerId).toBe(ledgerId);
            expect(services.queryLedgerReport).toHaveBeenLastCalledWith(expect.objectContaining({ query: expect.objectContaining({ ledgerId }) }), scope.username);
            expect(store.detailsCompleteFor(request)).toBe(true);
        }
    });

    it('does not expose a previous server or account report', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page())); await store.load(query);
        scope.server += '/other'; expect(store.reportFor(query)).toBeNull(); expect(store.detailsFor(query)).toEqual([]);
        scope.server = 'https://synthetic.invalid/a'; scope.username = 'other'; expect(store.reportFor(query)).toBeNull();
    });

    it('clears the active report and preview synchronously when selecting another ledger', async () => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page())); await store.load(query);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response(proof())); await store.prepareExport(query);
        useLedgersStore().selectedLedgerId = '12';
        expect(store.reportFor(query)).toBeNull(); expect(store.previewFor(query)).toBeNull();
        await expect(store.load(query)).rejects.toThrow(); expect(services.queryLedgerReport).toHaveBeenCalledTimes(1);
    });

    it('rejects late responses after account changes or reset', async () => {
        const store = useLedgerReportStore();
        for (const change of [() => { scope.username = 'other'; }, () => store.reset()]) {
            scope.username = 'synthetic-owner'; const pending = deferred();
            vi.mocked(services.queryLedgerReport).mockReturnValueOnce(pending.promise);
            const result = store.load(query); change(); pending.resolve(response(page()));
            await expect(result).rejects.toThrow(); expect(store.reportFor(query)).toBeNull();
        }
    });

    it('an older query cannot replace a newer result or end its loading state', async () => {
        const store = useLedgerReportStore(); const first = deferred(); const second = deferred();
        vi.mocked(services.queryLedgerReport).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
        const old = store.load(query); const latest = store.load(query);
        first.resolve(response(page())); await expect(old).rejects.toThrow(); expect(store.loading).toBe(true);
        second.resolve(response(page())); await latest; expect(store.loading).toBe(false); expect(store.reportFor(query)).not.toBeNull();
    });

    it('captures a defensive query copy before asynchronous work', async () => {
        const store = useLedgerReportStore(); const input = { ...query }; const pending = deferred();
        vi.mocked(services.queryLedgerReport).mockReturnValueOnce(pending.promise);
        const result = store.load(input); input.ledgerId = '12'; pending.resolve(response(page())); await result;
        expect(store.reportFor(query)).not.toBeNull(); expect(store.reportFor(input)).toBeNull();
    });

    it.each([undefined, null, [], { ...page(), dataComplete: false }, { ...page(), query: { ...page().query, ledgerId: '12' } }])(
        'does not turn malformed response %j into an empty report', async value => {
            const store = useLedgerReportStore(); vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(value));
            await expect(store.load(query)).rejects.toThrow(); expect(store.reportFor(query)).toBeNull(); expect(store.detailsCompleteFor(query)).toBe(false);
        });

    it('requires an active export-capable report before preview', async () => {
        const store = useLedgerReportStore(); await expect(store.prepareExport(query)).rejects.toThrow();
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response({ ...page(), exportAllowed: false })); await store.load(query);
        await expect(store.prepareExport(query)).rejects.toThrow(); expect(services.previewLedgerReportExport).not.toHaveBeenCalled();
    });
    it.each([401, 403, 404, 405, 409])('preserves visible service/permission status %i without old-report fallback', async status => {
        const store = useLedgerReportStore();
        vi.mocked(services.queryLedgerReport).mockRejectedValueOnce({ response: { status } });
        await expect(store.load(query)).rejects.toMatchObject({ status });
        expect(store.reportFor(query)).toBeNull(); expect(store.errorText).toBeTruthy();
    });

    it('clears a preview on query refresh and refuses unconfirmed download', async () => {
        const store = useLedgerReportStore(); vi.mocked(services.queryLedgerReport).mockResolvedValue(response(page())); await store.load(query);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response(proof())); await store.prepareExport(query);
        await expect(store.download(query, { confirmed: false } as never)).rejects.toThrow();
        await store.load(query); await expect(store.download(query, confirmation())).rejects.toThrow();
        expect(services.downloadLedgerReport).not.toHaveBeenCalled();
    });

    it('requires a separate acknowledgement for hidden amounts', async () => {
        const store = useLedgerReportStore(); const request = { ...query, hidden: 'include' as const }; const value = page(request);
        vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(value)); await store.load(request);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response(proof(value))); await store.prepareExport(request);
        await expect(store.download(request, confirmation())).rejects.toThrow(); expect(services.downloadLedgerReport).not.toHaveBeenCalled();
        vi.mocked(services.downloadLedgerReport).mockResolvedValueOnce(emptyCSV(value));
        const file = await store.download(request, { ...confirmation(), includeHiddenAmounts: true }); expect(file.filename).toMatch(/\.csv$/);
    });

    it('accepts only a validated actual CSV attachment and consumes the preview', async () => {
        const store = useLedgerReportStore(); vi.mocked(services.queryLedgerReport).mockResolvedValueOnce(response(page())); await store.load(query);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response(proof())); await store.prepareExport(query);
        vi.mocked(services.downloadLedgerReport).mockResolvedValueOnce(emptyCSV());
        const result = await store.download(query, confirmation());
        expect(result.blob.size).toBeGreaterThan(0); expect(result.filename).toBe('finexy-report-11-2026-10-01-2026-10-31.csv');
        expect(store.previewFor(query)).toBeNull(); await expect(store.download(query, confirmation())).rejects.toThrow();
    });

    it('rejects stale export snapshots and expires proofs without an HTTP download', async () => {
        const store = useLedgerReportStore(); vi.mocked(services.queryLedgerReport).mockResolvedValue(response(page())); await store.load(query);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response({ ...proof(), snapshotHash: 'b'.repeat(64) }));
        await expect(store.prepareExport(query)).rejects.toThrow(); expect(store.reportFor(query)).toBeNull();
        await store.load(query);
        vi.mocked(services.previewLedgerReportExport).mockResolvedValueOnce(response({ ...proof(), expiresAt: '1' }));
        await expect(store.prepareExport(query)).rejects.toThrow(); expect(services.downloadLedgerReport).not.toHaveBeenCalled();
    });
});
