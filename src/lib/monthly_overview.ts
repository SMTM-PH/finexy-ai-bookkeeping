import type { LedgerReportPage, LedgerReportQuery } from '@/models/ledger_report.ts';
import type { MonthlyBudgetInfoResponse } from '@/models/monthly_budget.ts';
import { copyLedgerReportQuery, LedgerReportContractError } from '@/lib/ledger_report.ts';
import { ALL_CURRENCIES } from '@/consts/currency.ts';
import { ledgerReportMonthRange, ledgerReportDrillQuery, type LedgerReportDrill } from '@/lib/ledger_report_panel.ts';

export interface MonthlyOverviewScope {
    readonly identity: string;
    readonly sessionEpoch: number;
    readonly ledgerId: string;
    readonly month: string;
    readonly timezone: string;
    readonly hidden: 'include' | 'exclude';
    readonly mode: 'online' | 'offline' | 'local' | 'synchronizing';
}
export type MonthlySection<T> =
    { readonly kind: 'loading' | 'unavailable'; readonly value: null; readonly message: string } |
    { readonly kind: 'ready' | 'empty'; readonly value: T; readonly message: string } |
    { readonly kind: 'failure'; readonly value: null; readonly message: string; readonly reason: MonthlyFailureKind };
export type MonthlyFailureKind = 'authentication' | 'forbidden' | 'unavailable' | 'contract' | 'network' | 'other';
export class MonthlyOverviewContractError extends Error {
    constructor() { super('月度数据响应不完整，请重新读取'); this.name = 'MonthlyOverviewContractError'; }
}
function validId(value: unknown, zero = false): value is string {
    return typeof value === 'string' && value.length <= 19 && /^(0|[1-9]\d*)$/.test(value) &&
        BigInt(value) <= (1n << 63n) - 1n && (zero || value !== '0');
}
function validAmount(value: unknown): value is number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= 9999999999999;
}
/** Detached limit metadata; neither a server timezone nor a usage snapshot. */
export function validateOverviewBudget(value: unknown, ledgerId: string, yearMonth: number): MonthlyBudgetInfoResponse | null {
    if (!validId(ledgerId, true) || !Number.isInteger(yearMonth) || yearMonth < 200001 || yearMonth > 999812 || yearMonth % 100 < 1 || yearMonth % 100 > 12) throw new MonthlyOverviewContractError();
    if (value === null) return null;
    const budget = value as MonthlyBudgetInfoResponse | undefined;
    if (!budget || typeof budget !== 'object' || Array.isArray(budget) || !validId(budget.id) || budget.ledgerId !== ledgerId ||
        budget.yearMonth !== yearMonth || !validAmount(budget.amount) || !Number.isSafeInteger(budget.revision) || budget.revision < 1 ||
        typeof budget.currency !== 'string' || !ALL_CURRENCIES[budget.currency] || !budget.categoryAmounts ||
        typeof budget.categoryAmounts !== 'object' || Array.isArray(budget.categoryAmounts) ||
        Object.entries(budget.categoryAmounts).some(([key, amount]) => !validId(key) || !validAmount(amount))) throw new MonthlyOverviewContractError();
    return Object.freeze({ ...budget, categoryAmounts: Object.freeze({ ...budget.categoryAmounts }) });
}
function readFailure(error: unknown, section: 'report' | 'budget'): MonthlySection<never> {
    const details = error as { status?: number; code?: string; response?: { status?: number } } | null;
    const status = details?.response?.status ?? details?.status;
    const subject = section === 'report' ? '完整月报' : '该月预算';
    const reason: MonthlyFailureKind = status === 401 ? 'authentication' : status === 403 ? 'forbidden' :
        status === 404 || status === 405 || status === 501 ? 'unavailable' :
        error instanceof LedgerReportContractError || error instanceof MonthlyOverviewContractError ? 'contract' :
        ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(details?.code ?? '') ? 'network' : 'other';
    const message = reason === 'authentication' ? '登录已失效，请重新登录后读取。' : reason === 'forbidden' ?
        '当前账本权限已变化，请重新核验访问权限。' : reason === 'unavailable' ?
        `${subject}暂不可用，请核对账本及服务器支持情况。` : reason === 'contract' ?
        `${subject}响应不完整，请重新读取。` : reason === 'network' ?
        `无法取得${subject}，请恢复网络后重试。` : `未能读取${subject}，请重试。`;
    return { kind: 'failure', reason, value: null, message };
}
export interface MonthlyOverviewState {
    readonly scope: MonthlyOverviewScope;
    readonly query: LedgerReportQuery;
    readonly report: MonthlySection<LedgerReportPage>;
    readonly budget: MonthlySection<MonthlyBudgetInfoResponse | null>;
}
export interface MonthlyOverviewGateway {
    /** Return only a page validated by validateLedgerReportPage, including dataComplete. */
    report(query: LedgerReportQuery): Promise<LedgerReportPage>;
    budget(ledgerId: string, yearMonth: number): Promise<MonthlyBudgetInfoResponse | null>;
}
export function monthlyOverviewQuery(scope: MonthlyOverviewScope): LedgerReportQuery {
    if (!scope.identity || !Number.isSafeInteger(scope.sessionEpoch) || scope.sessionEpoch < 0 || scope.month < '2000-01' ||
        !['online', 'offline', 'local', 'synchronizing'].includes(scope.mode)) throw new MonthlyOverviewContractError();
    return copyLedgerReportQuery({ ledgerId: scope.ledgerId, ...ledgerReportMonthRange(scope.month),
        timezone: scope.timezone, types: [1, 2, 3, 4], hidden: scope.hidden });
}
export function monthInTimezone(now: Date, timezone: string): string {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit' }).formatToParts(now);
    return `${parts.find(part => part.type === 'year')!.value}-${parts.find(part => part.type === 'month')!.value}`;
}
export function moveOverviewMonth(month: string, delta: -1 | 1): string {
    ledgerReportMonthRange(month);
    const date = new Date(`${month}-01T00:00:00Z`);
    date.setUTCMonth(date.getUTCMonth() + delta);
    const next = date.toISOString().slice(0, 7);
    ledgerReportMonthRange(next);
    if (next < '2000-01') throw new MonthlyOverviewContractError();
    return next;
}
const scopeKey = (scope: MonthlyOverviewScope): string => JSON.stringify([scope.identity, scope.sessionEpoch, scope.ledgerId,
    scope.month, scope.timezone, scope.hidden, scope.mode]);
/** Memory-only owner. Bind on every session/ledger/mode event, including A -> B -> A. */
export class MonthlyOverviewController {
    private generation = 0;
    private current: MonthlyOverviewState | null = null;
    private closed = false;
    constructor(private readonly gateway: MonthlyOverviewGateway,
        private readonly liveScope: () => MonthlyOverviewScope,
        private readonly publish: (state: MonthlyOverviewState | null) => void) {}
    bind(scope: MonthlyOverviewScope): void {
        if (this.closed) throw new Error('月度总览已关闭');
        ++this.generation;
        // Invalid replacement scopes must also clear the previous financial view.
        this.current = null; this.publish(null);
        const query = monthlyOverviewQuery(scope);
        const blocked = scope.mode !== 'online';
        const message = scope.mode === 'local' ? '仅本机记录，历史范围未核验；不作为完整月报。' :
            scope.mode === 'synchronizing' ? '正在同步，完成后重新读取完整月报。' : '离线，完整月报与在线预算暂不可用。';
        this.current = { scope: Object.freeze({ ...scope }), query,
            report: { kind: blocked ? 'unavailable' : 'loading', value: null, message: blocked ? message : '正在读取完整月报…' },
            budget: { kind: blocked ? 'unavailable' : 'loading', value: null, message: blocked ? message : '正在读取本月预算…' } };
        this.publish(this.current);
    }
    close(): void { ++this.generation; this.closed = true; this.current = null; this.publish(null); }
    invalidate(): void { ++this.generation; this.current = null; this.publish(null); }
    private accepts(serial: number, captured: MonthlyOverviewState): boolean {
        return !this.closed && serial === this.generation && this.current?.scope === captured.scope &&
            scopeKey(captured.scope) === scopeKey(this.liveScope());
    }
    private update(serial: number, captured: MonthlyOverviewState, patch: Partial<MonthlyOverviewState>): void {
        if (!this.accepts(serial, captured)) return;
        this.current = { ...this.current!, ...patch };
        this.publish(this.current);
    }
    async load(): Promise<void> {
        const captured = this.current;
        if (!captured || captured.scope.mode !== 'online') return;
        const serial = ++this.generation;
        if (!this.accepts(serial, captured)) { this.invalidate(); return; }
        this.update(serial, captured, { report: { kind: 'loading', value: null, message: '正在读取完整月报…' },
            budget: { kind: 'loading', value: null, message: '正在读取本月预算…' } });
        await Promise.allSettled([
            this.gateway.report(captured.query).then(page => {
                if (JSON.stringify(copyLedgerReportQuery(page.query)) !== JSON.stringify(captured.query) || !page.dataComplete) throw new MonthlyOverviewContractError();
                this.update(serial, captured, { report: { kind: page.matchedCount === 0 ? 'empty' : 'ready', value: page,
                    message: page.matchedCount === 0 ? '该月暂无流水。' : '完整在线汇总。' } });
            }).catch(error => this.update(serial, captured, { report: readFailure(error, 'report') })),
            this.gateway.budget(captured.scope.ledgerId, Number(captured.scope.month.replace('-', ''))).then(value => {
                const budget = validateOverviewBudget(value, captured.scope.ledgerId, Number(captured.scope.month.replace('-', '')));
                this.update(serial, captured, { budget: { kind: budget === null ? 'empty' : 'ready', value: budget,
                    message: budget === null ? '该月尚未设置预算。' : '本账本该月固定币种额度。' } });
            }).catch(error => this.update(serial, captured, { budget: readFailure(error, 'budget') }))
        ]);
    }
}
/** Usage is defined by this page's complete IANA-month report, not the legacy
 * settings panel's request-offset snapshot. A limit itself has no timezone.
 * Budget usage always includes hidden records; amount privacy is independent. */
export interface BudgetUsageScope { readonly ledgerId: string; readonly month: string; readonly timezone: string; readonly hidden: 'include' | 'exclude' }
export function overviewBudgetUsage(state: MonthlyOverviewState, proof: BudgetUsageScope | null):
    { readonly expense: string; readonly limit: string; readonly remaining: string; readonly percent: string;
      readonly progressBasisPoints: number; readonly warning: 0 | 1 | 2 } | null {
    const budget = state.budget.value, page = state.report.value, scope = state.scope;
    if (state.budget.kind !== 'ready' || !['ready', 'empty'].includes(state.report.kind) || !budget || !page?.dataComplete ||
        scope.mode !== 'online' || scope.hidden !== 'include' || !proof || proof.ledgerId !== scope.ledgerId || proof.month !== scope.month ||
        proof.timezone !== scope.timezone || proof.hidden !== scope.hidden ||
        JSON.stringify(copyLedgerReportQuery(page.query)) !== JSON.stringify(monthlyOverviewQuery(scope))) return null;
    validateOverviewBudget(budget, scope.ledgerId, Number(scope.month.replace('-', '')));
    // An absent currency is zero only after the validated complete source succeeded.
    const expense = page.totals.find(row => row.currency === budget.currency)?.expense ?? '0';
    const limit = BigInt(budget.amount), spent = BigInt(expense), hundredths = spent * 10000n / limit;
    const absolute = hundredths < 0n ? -hundredths : hundredths;
    const progress = hundredths < 0n ? 0n : hundredths > 10000n ? 10000n : hundredths;
    return { expense, limit: limit.toString(), remaining: (limit - spent).toString(),
        percent: `${hundredths < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}%`,
        progressBasisPoints: Number(progress), warning: spent >= limit ? 2 : spent * 10n >= limit * 8n ? 1 : 0 };
}
export function overviewDrill(state: MonthlyOverviewState, kind: LedgerReportDrill, currency?: string): LedgerReportQuery {
    if (!['ready', 'empty'].includes(state.report.kind) || !state.report.value?.dataComplete ||
        JSON.stringify(copyLedgerReportQuery(state.report.value.query)) !== JSON.stringify(state.query)) throw new MonthlyOverviewContractError();
    return ledgerReportDrillQuery(state.query, kind, undefined, currency);
}
