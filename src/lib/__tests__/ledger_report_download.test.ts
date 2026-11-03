import { describe, expect, it } from 'vitest';
import type { LedgerReportPage } from '@/models/ledger_report.ts';
import { LEDGER_REPORT_CSV_COLUMNS, parseLedgerReportCSV, validateLedgerReportDownload } from '../ledger_report_download.ts';

const hash = 'a'.repeat(64);
const amount = '9007199254740993';
const money = { currency: 'CNY', income: amount, expense: '0', net: amount, adjustment: '0', transferIn: '0', transferOut: '0' };
const page: LedgerReportPage = { query: { version: 1, ledgerId: '9007199254740999', startDate: '2026-10-01', endDate: '2026-10-31', timezone: 'UTC',
    hidden: 'exclude', types: [1, 2, 3, 4], accounts: null, categories: null, tags: null, currencies: null,
    utcRanges: [{ start: '1790812800', endExclusive: '1793491200' }] }, snapshotHash: hash, matchedCount: 1, totals: [money],
    memberRole: 1, dataComplete: true, tagGroupsAdditive: false, exportAllowed: true, offset: 0, returnedCount: 1, nextOffset: null, detailsComplete: true,
    details: [{ id: '9007199254740993', physicalIds: ['9007199254740993'], type: 2, time: '1791633601', sequence: '1791633601007', date: '2026-10-10',
        categoryId: '1011', categoryName: 'Synthetic income', source: { accountId: '9007199254740997', accountName: '=SUM(1,2)', currency: 'CNY', amount },
        destination: null, adjustment: '0', tags: [], hidden: false, comment: '=CMD\r\n"quoted",中文', recorderUid: '100', payerUid: '100' }],
    trend: [{ key: '2026-10-10', count: 1, money: [money] }], categories: [{ key: '1011', count: 1, money: [money] }],
    tagGroups: [{ key: 'none', count: 1, money: [money] }], readableTags: [],
    accounts: [{ id: '9007199254740997', name: '=SUM(1,2)', parentId: '0', type: 1, hidden: false, currency: 'CNY' }],
    categoryMetadata: [{ id: '1010', parentId: '0', type: 1, name: 'Synthetic root', hidden: false },
        { id: '1011', parentId: '1010', type: 1, name: 'Synthetic income', hidden: false }] };
const record = ['1', hash, page.query.ledgerId, 'UTC', '2026-10-01', '2026-10-31', 'minor', 'exclude',
    '9007199254740993', '["9007199254740993"]', '2', '1791633601', '1791633601007', '2026-10-10', '1011', 'Synthetic income',
    '9007199254740997', "'=SUM(1,2)", 'CNY', amount, '', '', '', '', '0', '', '[]', '[]', 'false', "'=CMD\r\n\"quoted\",中文", '100', '100',
    '2', '90071992547409.93', '', '0.00', ''];
function csv(rows = [record]): string {
    const cell = (text: string) => /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
    return [LEDGER_REPORT_CSV_COLUMNS, ...rows].map(row => row.map(cell).join(',')).join('\n') + '\n';
}
function delivered(text = csv(), expected = page) {
    return { status: 200, data: new Blob([text]), headers: {
        'Content-Type': 'text/csv; charset=utf-8',
        'Content-Disposition': `attachment; filename="finexy-report-${expected.query.ledgerId}-2026-10-01-2026-10-31.csv"`,
        'X-Finexy-Report-Snapshot': hash, 'X-Finexy-Report-Count': String(expected.matchedCount), 'X-Finexy-Report-Amount-Unit': 'minor'
    } };
}

describe('report CSV attachment delivery and exact reparse', () => {
    it('preserves quoted CRLF/commas, string IDs and money above JS safe integer', async () => {
        expect(parseLedgerReportCSV(csv())[1]).toEqual(record);
        const file = await validateLedgerReportDownload(delivered(), page);
        expect(file.filename).toBe('finexy-report-9007199254740999-2026-10-01-2026-10-31.csv');
        expect(await file.blob.text()).toContain(amount);
    });

    it.each([
        { status: 409 }, { data: new Blob(['{"success":false}']) },
        { headers: { 'content-type': 'application/json' } },
        { headers: { 'content-disposition': 'attachment; filename="../other.csv"' } },
        { headers: { 'x-finexy-report-count': '2' } },
        { headers: { 'x-finexy-report-snapshot': 'b'.repeat(64) } }
    ])('rejects incorrect transport %j before saving', async mutation => {
        const input = delivered();
        await expect(validateLedgerReportDownload({ ...input, ...mutation, headers: { ...input.headers, ...mutation.headers } }, page)).rejects.toThrow();
    });

    it.each([[19, '9007199254740992'], [33, '90071992547409.92'], [8, '9e15'], [12, '7'], [1, 'b'.repeat(64)], [28, 'true'], [25, '10'], [9, '[1]']])(
        'rejects altered content at column %i', async (index, text) => {
            const changed = [...record]; changed[index as number] = String(text);
            await expect(validateLedgerReportDownload(delivered(csv([changed])), page)).rejects.toThrow();
        });

    it('rejects missing and duplicate logical rows, wrong totals and malformed UTF8', async () => {
        for (const text of [csv([]), csv([record, record]), csv().replace('report_version', 'unknown')]) {
            await expect(validateLedgerReportDownload(delivered(text), page)).rejects.toThrow();
        }
        await expect(validateLedgerReportDownload(delivered(), { ...page, totals: [{ ...money, net: '0' }] })).rejects.toThrow();
        await expect(validateLedgerReportDownload({ ...delivered(), data: new Blob([new Uint8Array([255, 254])]) }, page)).rejects.toThrow();
    });

    it('rejects substituted IDs, income direction and unprotected formulas despite equal totals', async () => {
        const changedID = [...record]; changedID[8] = '999900'; changedID[9] = '["999900"]';
        const changedDirection = [...record];
        for (let i = 0; i < 4; i++) { changedDirection[20 + i] = record[16 + i]!; changedDirection[16 + i] = ''; }
        changedDirection[34] = record[33]!; changedDirection[33] = '';
        const changedComment = [...record]; changedComment[29] = '=1+1';
        for (const changed of [changedID, changedDirection, changedComment]) {
            await expect(validateLedgerReportDownload(delivered(csv([changed])), page)).rejects.toThrow();
        }
    });

    it.each(['a,"unterminated', 'a,"closed"oops', 'a,b"quote'])('rejects malformed CSV %j', text => {
        expect(() => parseLedgerReportCSV(text)).toThrow();
    });

    it('accepts a real header-only empty report rather than an empty body', async () => {
        const empty = { ...page, matchedCount: 0, returnedCount: 0, details: [], totals: [], trend: [], categories: [], tagGroups: [] };
        const file = await validateLedgerReportDownload(delivered(csv([]), empty), empty);
        expect(file.blob.size).toBeGreaterThan(0);
        await expect(validateLedgerReportDownload(delivered('', empty), empty)).rejects.toThrow();
    });
});
