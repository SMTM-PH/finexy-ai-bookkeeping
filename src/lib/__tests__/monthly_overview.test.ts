import { describe, expect, it } from 'vitest';
import type { LedgerReportPage, LedgerReportQuery } from '@/models/ledger_report.ts';
import { ledgerReportFixturePage } from '@/lib/__tests__/fixtures/ledger_report.ts';
import { MonthlyOverviewController, monthInTimezone, monthlyOverviewQuery, moveOverviewMonth, overviewBudgetUsage, overviewDrill,
    type MonthlyOverviewGateway, type MonthlyOverviewScope, type MonthlyOverviewState } from '../monthly_overview.ts';

const initial = (): MonthlyOverviewScope => ({ identity: 'synthetic-account', sessionEpoch: 1, ledgerId: '11', month: '2026-10',
    timezone: 'Asia/Shanghai', hidden: 'include', mode: 'online' });
const budget = { id: '200', ledgerId: '11', yearMonth: 202610, amount: 2000, revision: 1, currency: 'CNY', categoryAmounts: {} };
function page(query: LedgerReportQuery): LedgerReportPage {
    const fixture = ledgerReportFixturePage();
    return { ...fixture, query: { ...fixture.query, ...query } as LedgerReportPage['query'] };
}
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (value?: unknown) => void;
    const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function harness(gateway: MonthlyOverviewGateway) {
    let scope = initial(); let state: MonthlyOverviewState | null = null;
    const controller = new MonthlyOverviewController(gateway, () => scope, value => { state = value; });
    controller.bind(scope);
    return { controller, state: () => state!, scope: () => scope, bind: (next: MonthlyOverviewScope) => { scope = next; controller.bind(next); },
        mutate: (next: MonthlyOverviewScope) => { scope = next; } };
}
const ok = (): MonthlyOverviewGateway => ({ report: async query => page(query), budget: async () => budget });

describe('monthly home context and authoritative summaries', () => {
    it('uses the selected IANA calendar at a UTC month boundary', () => {
        const now = new Date('2026-11-01T00:30:00Z');
        expect(monthInTimezone(now, 'America/Los_Angeles')).toBe('2026-10');
        expect(monthInTimezone(now, 'Asia/Shanghai')).toBe('2026-11');
        expect(monthlyOverviewQuery({ ...initial(), month: '2024-02', timezone: 'America/New_York' })).toMatchObject({
            startDate: '2024-02-01', endDate: '2024-02-29', timezone: 'America/New_York', hidden: 'include' });
        expect(moveOverviewMonth('2026-12', 1)).toBe('2027-01');
    });
    it('preserves ledger zero, int64 ID strings and all filters on drilldown', async () => {
        const h = harness(ok()); h.bind({ ...initial(), ledgerId: '0' });
        await h.controller.load();
        const drill = overviewDrill(h.state(), 'expense', 'CNY');
        expect(drill).toMatchObject({ ledgerId: '0', startDate: '2026-10-01', endDate: '2026-10-31',
            timezone: 'Asia/Shanghai', types: [3], currencies: ['CNY'], hidden: 'include' });
        expect(monthlyOverviewQuery({ ...initial(), ledgerId: '9223372036854775807' }).ledgerId).toBe('9223372036854775807');
    });
    it('keeps a valid budget when the report fails and never treats failure as zero usage', async () => {
        const h = harness({ ...ok(), report: async () => { throw new Error('synthetic unavailable'); } });
        await h.controller.load();
        expect(h.state().report.kind).toBe('failure'); expect(h.state().budget.value).toEqual(budget);
        expect(overviewBudgetUsage(h.state(), h.scope())).toBeNull();
    });
    it('keeps the complete report when the budget independently fails', async () => {
        const h = harness({ ...ok(), budget: async () => { throw new Error('synthetic unavailable'); } });
        await h.controller.load();
        expect(h.state().report.kind).toBe('ready'); expect(h.state().budget.kind).toBe('failure');
        expect(overviewBudgetUsage(h.state(), h.scope())).toBeNull();
    });
    it('uses only authoritative same-currency expense, including refunds, with exact integer arithmetic', async () => {
        const h = harness(ok()); await h.controller.load();
        // Shared financial fixture: net CNY expense 1200; transfer totals are separate.
        expect(overviewBudgetUsage(h.state(), h.scope())).toEqual({ expense: '1200', limit: '2000', remaining: '800', percent: '60.00%',
            progressBasisPoints: 6000, warning: 0 });
        const huge = '9007199254740993';
        const state = h.state();
        const money = state.report.value!.totals[0]!;
        const complete = { ...state, report: { ...state.report, value: { ...state.report.value!, totals: [{ ...money, currency: 'CNY', expense: huge }] } } } as MonthlyOverviewState;
        expect(overviewBudgetUsage(complete, h.scope())?.remaining).toBe('-9007199254738993');
        const refund = { ...state, report: { ...state.report, value: { ...state.report.value!, totals: [{ ...money, currency: 'CNY', expense: '-500' }] } } } as MonthlyOverviewState;
        expect(overviewBudgetUsage(refund, h.scope())).toEqual({ expense: '-500', limit: '2000', remaining: '2500', percent: '-25.00%',
            progressBasisPoints: 0, warning: 0 });
    });
    it('allows genuine zero only after a complete source and a same-currency limit', async () => {
        const h = harness({ ...ok(), report: async query => ({ ...page(query), matchedCount: 0, totals: [] }) });
        await h.controller.load();
        expect(h.state().report.kind).toBe('empty');
        expect(overviewBudgetUsage(h.state(), h.scope())?.expense).toBe('0');
    });
    it('separates an absent budget from a zero limit', async () => {
        const h = harness({ ...ok(), budget: async () => null });
        await h.controller.load();
        expect(h.state().budget.kind).toBe('empty');
        expect(h.state().budget.value).toBeNull();
        expect(overviewBudgetUsage(h.state(), h.scope())).toBeNull();
    });
    it.each(['offline', 'local', 'synchronizing'] as const)('%s never requests or labels partial Room history a report', async mode => {
        let calls = 0; const h = harness({ report: async query => { calls++; return page(query); }, budget: async () => { calls++; return budget; } });
        h.bind({ ...initial(), mode }); await h.controller.load();
        expect(calls).toBe(0); expect(h.state().report.kind).toBe('unavailable'); expect(h.state().report.value).toBeNull();
    });
    it('rejects a delayed old account/ledger/month result after A -> B -> A', async () => {
        const slow = deferred<LedgerReportPage>(), h = harness({ ...ok(), report: () => slow.promise });
        const oldQuery = h.state().query, old = h.controller.load();
        h.bind({ ...initial(), ledgerId: '12', sessionEpoch: 2 }); h.bind({ ...initial(), sessionEpoch: 3 });
        slow.resolve(page(oldQuery)); await old;
        expect(h.state().scope.sessionEpoch).toBe(3); expect(h.state().report.value).toBeNull();
    });
    it('drops completion when live context changed before a bind event is delivered', async () => {
        const slow = deferred<LedgerReportPage>(), h = harness({ ...ok(), report: () => slow.promise });
        const query = h.state().query, old = h.controller.load(); h.mutate({ ...initial(), sessionEpoch: 2 });
        slow.resolve(page(query)); await old; expect(h.state().report.value).toBeNull();
    });
    it('ignores an older retry failure after a newer successful read', async () => {
        const slow = deferred<LedgerReportPage>(); let calls = 0;
        const h = harness({ ...ok(), report: query => ++calls === 1 ? slow.promise : Promise.resolve(page(query)) });
        const old = h.controller.load(); await h.controller.load(); slow.reject(new Error('late')); await old;
        expect(h.state().report.kind).toBe('ready');
    });
    it('rejects timezone/hidden/month mismatch in budget scope and never merges FX currencies', async () => {
        const h = harness(ok()); await h.controller.load();
        expect(overviewBudgetUsage(h.state(), { ...h.scope(), timezone: 'UTC' })).toBeNull();
        expect(overviewBudgetUsage(h.state(), { ...h.scope(), hidden: 'exclude' })).toBeNull();
        expect(overviewBudgetUsage(h.state(), { ...h.scope(), month: '2026-11' })).toBeNull();
        const s = h.state(); expect(s.report.value!.totals.map(row => row.currency)).toEqual(['CNY', 'USD']);
    });
});
