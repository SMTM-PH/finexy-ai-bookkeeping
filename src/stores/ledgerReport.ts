import { ref, shallowRef, watch } from 'vue';
import { defineStore } from 'pinia';
import type { LedgerReportDetail, LedgerReportExportPreview, LedgerReportPage, LedgerReportQuery } from '@/models/ledger_report.ts';
import { copyLedgerReportQuery, ledgerReportQueryKey, validateLedgerReportAccumulatedDetails, validateLedgerReportPage, validateLedgerReportPreview } from '@/lib/ledger_report.ts';
import { validateLedgerReportDownload, type LedgerReportDownload } from '@/lib/ledger_report_download.ts';
import { getCurrentUserInfo, getTransactionStateIdentity } from '@/lib/userstate.ts';
import services from '@/lib/services.ts';
import { useLedgersStore } from './ledger.ts';

interface ReportContext {
    readonly identity: string;
    readonly username: string;
    readonly query: LedgerReportQuery;
    readonly key: string;
    readonly generation: number;
}

/** One in-memory report view. Financial rows and export proofs are never persisted. */
export const useLedgerReportStore = defineStore('ledgerReport', () => {
    const ledgers = useLedgersStore();
    const firstPage = shallowRef<LedgerReportPage | null>(null);
    const rows = shallowRef<readonly LedgerReportDetail[]>([]);
    const preview = shallowRef<LedgerReportExportPreview | null>(null);
    const loading = ref(false);
    const errorText = ref('');
    let generation = 0;
    let active: ReportContext | null = null;
    let nextOffset: number | null = null;

    function reset(): void {
        ++generation;
        active = null; firstPage.value = null; rows.value = []; preview.value = null;
        nextOffset = null; loading.value = false; errorText.value = '';
    }

    watch(() => ledgers.selectedLedgerId, reset, { flush: 'sync' });

    function isCurrent(context: ReportContext): boolean {
        return context === active && context.generation === generation && context.identity === getTransactionStateIdentity() &&
            context.username === getCurrentUserInfo()?.username && context.query.ledgerId === ledgers.selectedLedgerId;
    }

    function assertCurrent(context: ReportContext): void {
        if (!isCurrent(context)) throw new Error('账号、账本或查询已变化，请重新读取报告');
    }

    function contextFor(query: LedgerReportQuery): ReportContext {
        const copied = copyLedgerReportQuery(query);
        const identity = getTransactionStateIdentity();
        const username = getCurrentUserInfo()?.username;
        if (!identity || !username || copied.ledgerId !== ledgers.selectedLedgerId) {
            throw new Error('无法确认当前账号或选中账本，请重新打开报告');
        }
        return { identity, username, query: copied, key: ledgerReportQueryKey(copied), generation };
    }

    function reportFor(query: LedgerReportQuery): LedgerReportPage | null {
        return active && isCurrent(active) && ledgerReportQueryKey(query) === active.key ? firstPage.value : null;
    }

    function detailsFor(query: LedgerReportQuery): readonly LedgerReportDetail[] {
        return reportFor(query) ? rows.value : [];
    }

    function detailsCompleteFor(query: LedgerReportQuery): boolean {
        const page = reportFor(query);
        return !!page && nextOffset === null && rows.value.length === page.matchedCount;
    }

    function previewFor(query: LedgerReportQuery): LedgerReportExportPreview | null {
        const page = reportFor(query);
        const value = preview.value;
        return page && value?.snapshotHash === page.snapshotHash && BigInt(value.expiresAt) > BigInt(Math.floor(Date.now() / 1000)) ? value : null;
    }

    function resultOf(response: { readonly data?: { readonly success?: boolean; readonly result?: unknown } }): unknown {
        if (!response.data?.success || !Object.prototype.hasOwnProperty.call(response.data, 'result') || response.data.result == null) {
            throw new Error('报告响应不完整，请重试');
        }
        return response.data.result;
    }

    function fail(error: unknown, context: ReportContext): never {
        if (!isCurrent(context)) throw new Error('账号、账本或查询已变化，请重新读取报告');
        // Do not expose or log Axios request objects, credentials, or financial payloads.
        const info = error as { status?: number; response?: { status?: number } } | null;
        const status = info?.response?.status ?? info?.status;
        errorText.value = status === 409 ? '报告内容或权限已变化，请重新读取并预览' :
            status === 403 ? '当前账本或导出权限已失效，请重新读取' :
                status === 401 ? '登录已失效，请重新登录后读取报告' :
                    status === 404 || status === 405 ? '当前服务暂不支持账本报告，请升级服务端后重试' :
                error instanceof Error ? error.message : '无法完成报告请求，请重试';
        firstPage.value = null; rows.value = []; preview.value = null; nextOffset = null;
        throw Object.assign(new Error(errorText.value), typeof status === 'number' ? { status } : {});
    }

    async function load(query: LedgerReportQuery, count = 50): Promise<LedgerReportPage> {
        // Clear previous totals before validating or starting a different query.
        reset();
        const context = contextFor(query);
        if (!Number.isInteger(count) || count < 1 || count > 200) throw new Error('报告分页大小无效');
        active = context; loading.value = true;
        try {
            const response = await services.queryLedgerReport({ query: copyLedgerReportQuery(context.query), offset: 0, count }, context.username);
            assertCurrent(context);
            const page = validateLedgerReportPage(resultOf(response), context.query, 0, count);
            firstPage.value = page; rows.value = [...page.details]; nextOffset = page.nextOffset;
            return page;
        } catch (error) { return fail(error, context); }
        finally { if (isCurrent(context)) loading.value = false; }
    }

    async function loadNext(query: LedgerReportQuery, count = 50): Promise<void> {
        const context = active;
        const initial = reportFor(query);
        if (!context || !initial || loading.value || nextOffset === null) throw new Error('当前报告没有可继续读取的分页');
        if (!Number.isInteger(count) || count < 1 || count > 200) throw new Error('报告分页大小无效');
        const offset = nextOffset;
        preview.value = null; loading.value = true; errorText.value = '';
        try {
            const response = await services.queryLedgerReport({ query: copyLedgerReportQuery(context.query), offset, count,
                expectedSnapshotHash: initial.snapshotHash }, context.username);
            assertCurrent(context);
            const page = validateLedgerReportPage(resultOf(response), context.query, offset, count, initial.snapshotHash);
            const summary = (value: LedgerReportPage): string => JSON.stringify({ query: value.query, memberRole: value.memberRole,
                matchedCount: value.matchedCount, totals: value.totals, trend: value.trend, categories: value.categories,
                tagGroups: value.tagGroups, accounts: value.accounts, categoryMetadata: value.categoryMetadata, readableTags: value.readableTags });
            if (summary(page) !== summary(initial)) throw new Error('报告分页基线不一致，请重新读取');
            const ids = new Set(rows.value.map(row => row.id));
            if (page.details.some(row => ids.has(row.id)) || rows.value.length !== offset) throw new Error('报告分页重复或缺失，请重新读取');
            rows.value = validateLedgerReportAccumulatedDetails([...rows.value, ...page.details], initial, page.nextOffset === null);
            nextOffset = page.nextOffset;
            firstPage.value = { ...initial, exportAllowed: page.exportAllowed };
        } catch (error) { fail(error, context); }
        finally { if (isCurrent(context)) loading.value = false; }
    }

    async function prepareExport(query: LedgerReportQuery): Promise<LedgerReportExportPreview> {
        const context = active;
        const page = reportFor(query);
        if (!context || !page || loading.value || !page.exportAllowed) throw new Error('请先读取允许导出的当前报告');
        preview.value = null; loading.value = true; errorText.value = '';
        try {
            const response = await services.previewLedgerReportExport({ query: copyLedgerReportQuery(context.query),
                expectedSnapshotHash: page.snapshotHash }, context.username);
            assertCurrent(context);
            const value = validateLedgerReportPreview(resultOf(response), page);
            if (BigInt(value.expiresAt) <= BigInt(Math.floor(Date.now() / 1000))) throw new Error('导出预览已过期，请重新预览');
            preview.value = value;
            return value;
        } catch (error) { return fail(error, context); }
        finally { if (isCurrent(context)) loading.value = false; }
    }

    async function download(query: LedgerReportQuery, confirmation: { readonly confirmed: true; readonly snapshotHash: string;
        readonly previewToken: string; readonly includeHiddenAmounts?: boolean }): Promise<LedgerReportDownload> {
        const context = active;
        const page = reportFor(query);
        const proof = previewFor(query);
        if (!context || !page || !proof || loading.value || !page.exportAllowed || confirmation?.confirmed !== true ||
            confirmation.snapshotHash !== proof.snapshotHash || confirmation.previewToken !== proof.previewToken ||
            proof.includesHiddenAmounts && confirmation.includeHiddenAmounts !== true) throw new Error('请确认当前导出预览及隐藏金额范围');
        loading.value = true; errorText.value = '';
        try {
            const response = await services.downloadLedgerReport({ query: copyLedgerReportQuery(context.query), expectedSnapshotHash: page.snapshotHash,
                previewToken: proof.previewToken, confirmed: true, includeHiddenAmounts: confirmation.includeHiddenAmounts === true }, context.username);
            assertCurrent(context);
            const file = await validateLedgerReportDownload(response, page, rows.value);
            assertCurrent(context);
            preview.value = null;
            return file;
        } catch (error) { return fail(error, context); }
        finally { if (isCurrent(context)) loading.value = false; }
    }

    return { loading, errorText, reportFor, detailsFor, detailsCompleteFor, previewFor, load, loadNext, prepareExport, download, reset };
});
