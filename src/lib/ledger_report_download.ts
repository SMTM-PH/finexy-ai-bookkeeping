import type { LedgerReportDetail, LedgerReportLeg, LedgerReportPage, LedgerReportType } from '@/models/ledger_report.ts';
import { formatLedgerReportAmount, validateLedgerReportAccumulatedDetails } from '@/lib/ledger_report.ts';

export const LEDGER_REPORT_CSV_COLUMNS = [
    'report_version', 'snapshot_hash', 'ledger_id', 'timezone', 'start_date', 'end_date', 'amount_unit', 'hidden_policy',
    'id', 'physical_ids_json', 'type', 'unix_seconds', 'sequence', 'local_date', 'category_id', 'category_name',
    'source_account_id', 'source_account_name', 'source_currency', 'source_amount_minor',
    'destination_account_id', 'destination_account_name', 'destination_currency', 'destination_amount_minor',
    'adjustment_minor', 'balance_target_minor', 'tag_ids_json', 'tag_names_json', 'hidden', 'comment', 'recorder_uid', 'payer_uid',
    'major_unit_scale', 'source_amount_major', 'destination_amount_major', 'adjustment_major', 'balance_target_major'
] as const;

function invalidDownload(): never {
    throw new Error('导出文件与当前报告不一致，请重新预览后重试');
}

/** Parse quoted CSV without normalizing embedded CR/LF or coercing ID/amount cells. */
export function parseLedgerReportCSV(text: string): string[][] {
    const records: string[][] = [];
    let row: string[] = [];
    let cell = '';
    let quoted = false;
    let closed = false;
    for (let i = 0; i < text.length; i++) {
        const char = text[i];
        if (quoted) {
            if (char === '"') {
                if (text[i + 1] === '"') { cell += '"'; ++i; }
                else { quoted = false; closed = true; }
            } else cell += char;
        } else if (char === '"') {
            if (cell || closed) invalidDownload();
            quoted = true;
        } else if (char === ',' || char === '\n' || char === '\r') {
            row.push(cell); cell = ''; closed = false;
            if (char !== ',') {
                if (char === '\r' && text[i + 1] === '\n') ++i;
                records.push(row); row = [];
            }
        } else {
            if (closed) invalidDownload();
            cell += char;
        }
    }
    if (quoted) invalidDownload();
    if (cell || closed || row.length) { row.push(cell); records.push(row); }
    return records;
}

function minor(value: string): bigint {
    if (!/^(?:0|-?[1-9]\d*)$/.test(value) || value.length > 20) invalidDownload();
    const amount = BigInt(value);
    if (amount < -9223372036854775808n || amount > 9223372036854775807n) invalidDownload();
    return amount;
}

function protectText(value: string): string {
    if (value.startsWith("'")) return "'" + value;
    const trimmed = value.replace(/^[\p{White_Space}\p{Cc}\p{Cf}]+/u, '');
    return /^[=+@-]/.test(trimmed) || /^[\t\r\n]/.test(value) ? "'" + value : value;
}

function originalText(value: string): string {
    const original = value.startsWith("'") ? value.slice(1) : value;
    if (protectText(original) !== value) invalidDownload();
    return original;
}

function detailKey(row: LedgerReportDetail): string {
    const leg = (value: LedgerReportLeg | null) => value && [value.accountId, value.accountName, value.currency, value.amount];
    return JSON.stringify([row.id, row.physicalIds, row.type, row.time, row.sequence, row.date, row.categoryId, row.categoryName,
        leg(row.source), leg(row.destination), row.adjustment, row.balanceTarget ?? null,
        row.tags.map(tag => [tag.id, tag.name]), row.hidden, row.comment, row.recorderUid, row.payerUid]);
}

export interface LedgerReportDownload {
    readonly blob: Blob;
    readonly filename: string;
}

/** Validate the delivered attachment, including amounts, before offering a save. */
export async function validateLedgerReportDownload(response: {
    readonly status: number;
    readonly headers: Record<string, unknown>;
    readonly data: unknown;
}, page: LedgerReportPage, knownDetails: readonly LedgerReportDetail[] = page.details): Promise<LedgerReportDownload> {
    const header = (name: string): string => {
        const entries = Object.entries(response.headers).filter(([key]) => key.toLowerCase() === name);
        if (entries.length !== 1) invalidDownload();
        const entry = entries[0];
        return typeof entry?.[1] === 'string' ? entry[1] : '';
    };
    const filename = `finexy-report-${page.query.ledgerId}-${page.query.startDate}-${page.query.endDate}.csv`;
    const disposition = header('content-disposition');
    if (response.status !== 200 || !(response.data instanceof Blob) || !response.data.size ||
        !/^text\/csv(?:\s*;|$)/i.test(header('content-type')) ||
        header('x-finexy-report-snapshot') !== page.snapshotHash ||
        header('x-finexy-report-count') !== String(page.matchedCount) || header('x-finexy-report-amount-unit') !== 'minor' ||
        !disposition.startsWith('attachment;') ||
        !new RegExp(`(?:^|;)\\s*filename="?${filename.replaceAll('.', '\\.')}"?(?:;|$)`).test(disposition)) invalidDownload();

    let text: string;
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(await response.data.arrayBuffer()); }
    catch { return invalidDownload(); }
    const records = parseLedgerReportCSV(text);
    if (JSON.stringify(records.shift()) !== JSON.stringify(LEDGER_REPORT_CSV_COLUMNS) || records.length !== page.matchedCount) invalidDownload();
    const ids = new Set<string>();
    const physicalIDs = new Set<string>();
    const details: LedgerReportDetail[] = [];
    const tagMetadata = new Map(page.readableTags.map(tag => [tag.id, tag]));
    const totals = new Map<string, { income: bigint; expense: bigint; adjustment: bigint; transferIn: bigint; transferOut: bigint }>();
    const id = (value: string, zero = false): boolean => typeof value === 'string' &&
        (zero ? /^(?:0|[1-9]\d*)$/ : /^[1-9]\d*$/).test(value) && value.length <= 19 && BigInt(value) <= 9223372036854775807n;
    for (const row of records) {
        if (row.length !== LEDGER_REPORT_CSV_COLUMNS.length) invalidDownload();
        const at = (index: number): string => row[index] ?? invalidDownload();
        if (at(0) !== '1' || at(1) !== page.snapshotHash || at(2) !== page.query.ledgerId || at(3) !== page.query.timezone ||
            at(4) !== page.query.startDate || at(5) !== page.query.endDate || at(6) !== 'minor' || at(7) !== page.query.hidden ||
            !id(at(8)) || ids.has(at(8)) || !['1', '2', '3', '4'].includes(at(10)) ||
            !page.query.types.includes(Number(at(10)) as 1 | 2 | 3 | 4) || !id(at(11), true) || !id(at(12), true) ||
            BigInt(at(12)) < BigInt(at(11)) * 1000n || BigInt(at(12)) >= (BigInt(at(11)) + 1n) * 1000n ||
            at(13) < page.query.startDate || at(13) > page.query.endDate || !/^\d{4}-\d{2}-\d{2}$/.test(at(13)) ||
            !id(at(14), true) || !id(at(30), true) || !id(at(31), true) || !['true', 'false'].includes(at(28)) ||
            page.query.hidden === 'exclude' && at(28) === 'true' || at(32) !== '2') invalidDownload();
        ids.add(at(8));
        const json = (value: string): unknown => {
            try { return JSON.parse(value) as unknown; }
            catch { return invalidDownload(); }
        };
        const physical = json(at(9));
        const tags = json(at(26));
        const names = json(at(27));
        if (!Array.isArray(physical) || !physical.length || !physical.every(value => typeof value === 'string' && id(value)) ||
            !physical.includes(at(8)) || new Set(physical).size !== physical.length ||
            !Array.isArray(tags) || !tags.every(value => typeof value === 'string' && id(value)) || new Set(tags).size !== tags.length ||
            !Array.isArray(names) || !names.every(value => typeof value === 'string') || tags.length !== names.length) invalidDownload();
        for (const value of physical) {
            if (physicalIDs.has(value)) invalidDownload();
            physicalIDs.add(value);
        }
        const adjustment = minor(at(24));
        if (at(35) !== formatLedgerReportAmount(at(24)) ||
            (at(25) ? at(36) !== formatLedgerReportAmount(at(25)) : at(36) !== '')) invalidDownload();
        let legs = 0;
        for (const [base, major, destination] of [[16, 33, false], [20, 34, true]] as const) {
            if (!at(base)) {
                if ([1, 2, 3].some(offset => at(base + offset)) || at(major)) invalidDownload();
                continue;
            }
            ++legs;
            const currency = at(base + 2);
            const amount = minor(at(base + 3));
            if (!id(at(base)) || !/^[A-Z]{3}$/.test(currency) || at(major) !== formatLedgerReportAmount(at(base + 3))) invalidDownload();
            const sum = totals.get(currency) ?? { income: 0n, expense: 0n, adjustment: 0n, transferIn: 0n, transferOut: 0n };
            if (at(10) === '1') sum.adjustment += adjustment;
            if (at(10) === '2') sum.income += amount;
            if (at(10) === '3') sum.expense += amount;
            if (at(10) === '4') {
                if (destination) sum.transferIn += amount;
                else sum.transferOut += amount;
            }
            totals.set(currency, sum);
        }
        if (!legs || at(10) !== '4' && legs !== 1 || at(10) !== '1' && (adjustment !== 0n || at(25)) ||
            at(10) === '1' && !at(25)) invalidDownload();
        const leg = (base: number): LedgerReportLeg | null => at(base) ? {
            accountId: at(base), accountName: originalText(at(base + 1)), currency: at(base + 2), amount: at(base + 3)
        } : null;
        details.push({ id: at(8), physicalIds: physical, type: Number(at(10)) as LedgerReportType, time: at(11), sequence: at(12), date: at(13),
            categoryId: at(14), categoryName: originalText(at(15)), source: leg(16), destination: leg(20), adjustment: at(24),
            ...(at(25) ? { balanceTarget: at(25) } : {}), tags: tags.map((tagId, index) => {
                const resource = tagMetadata.get(tagId);
                if (!resource || resource.name !== names[index]) invalidDownload();
                return resource;
            }), hidden: at(28) === 'true', comment: originalText(at(29)), recorderUid: at(30), payerUid: at(31) });
    }
    if (totals.size !== page.totals.length) invalidDownload();
    for (const expected of page.totals) {
        const sum = totals.get(expected.currency);
        if (!sum || Object.entries(sum).some(([key, value]) => value.toString() !== expected[key as keyof typeof sum]) ||
            (sum.income - sum.expense).toString() !== expected.net) invalidDownload();
    }
    const validated = validateLedgerReportAccumulatedDetails(details, page, true);
    const byID = new Map(validated.map(row => [row.id, row]));
    const knownIDs = new Set<string>();
    for (const known of knownDetails) {
        const delivered = byID.get(known.id);
        if (knownIDs.has(known.id) || !delivered || detailKey(known) !== detailKey(delivered)) invalidDownload();
        knownIDs.add(known.id);
    }
    return { blob: response.data, filename };
}
