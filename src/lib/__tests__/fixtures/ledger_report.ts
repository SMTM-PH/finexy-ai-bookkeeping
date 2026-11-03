import fixture from '../../../../testdata/ledger_report/v1.json';
import type { LedgerReportDetail, LedgerReportMoney, LedgerReportPage } from '@/models/ledger_report.ts';
import { copyLedgerReportQuery } from '@/lib/ledger_report.ts';

const query = fixture.query;
const hash = 'ab'.repeat(32);
function money(currency: string, changes: Partial<Omit<LedgerReportMoney, 'currency'>> = {}): LedgerReportMoney {
    return { currency, income: '0', expense: '0', net: '0', adjustment: '0', transferIn: '0', transferOut: '0', ...changes };
}
function clone<T>(value: T): T { return structuredClone(value); }

/** A transport adapter for the shared synthetic fixture; expected aggregates below are explicit oracles. */
export function ledgerReportFixturePage(): LedgerReportPage {
    const metadata = (entry: { id: string; name: string; parentId?: string; hidden?: boolean; type?: number; currency?: string }) => ({ id: entry.id, name: entry.name, parentId: entry.parentId ?? '0', hidden: entry.hidden ?? false, type: entry.type, ...(entry.currency ? { currency: entry.currency } : {}) });
    const accounts = fixture.accounts.filter(account => account.ledgerId === '11').map(metadata);
    const categoryIDs = new Set(['1001', '1002', '1004', '1005', '1010', '1011', '1020', '1021']);
    const categoryMetadata = fixture.categories.filter(category => categoryIDs.has(category.id)).map(metadata);
    const readableTags = fixture.tags.filter(tag => fixture.expected.readableTagIds.includes(tag.id)).map(tag => ({ id: tag.id, name: tag.name, hidden: false }));
    const details = fixture.expected.detailIds.map(value => {
        const row = fixture.transactions.find(row => row.id === value)!;
        const partner = 'relatedId' in row ? fixture.transactions.find(other => other.id === row.relatedId) : undefined;
        const leg = (entry: typeof row) => {
            const account = fixture.accounts.find(account => account.id === entry.accountId)!;
            return { accountId: account.id, accountName: account.name, currency: account.currency, amount: entry.amount };
        };
        const tags = new Set<string>([...(row.tagIds ?? []), ...(partner?.tagIds ?? [])]);
        const kind = (row.type === 5 ? 4 : row.type) as LedgerReportDetail['type'];
        return {
            id: row.id, physicalIds: partner ? [row.id, partner.id] : [row.id], type: kind, time: row.transactionTime,
            sequence: (BigInt(row.transactionTime) * 1000n).toString(), date: '2026-10-10',
            categoryId: row.categoryId ?? '0',
            categoryName: row.categoryId ? fixture.categories.find(category => category.id === row.categoryId)!.name : '',
            source: row.type === 5 ? null : leg(row), destination: row.type === 5 ? leg(row) : partner ? leg(partner) : null,
            adjustment: kind === 1 ? row.relatedAccountAmount ?? '0' : '0',
            ...(kind === 1 ? { balanceTarget: row.amount } : {}),
            tags: readableTags.filter(tag => tags.has(tag.id)), hidden: false, comment: row.comment ?? '', recorderUid: '100', payerUid: '100'
        };
    });
    const totals = clone(fixture.expected.totals);
    const categoryMoney = {
        '0': [money('CNY', { adjustment: '1000' })],
        '1002': [money('CNY', { expense: '1000', net: '-1000' })],
        '1005': [money('CNY', { expense: '200', net: '-200' })],
        '1011': [money('CNY', { income: '10000', net: '10000' })],
        '1021': [money('CNY', { transferOut: '750' }), money('USD', { transferIn: '130' })]
    };
    const tagMoney = {
        '500': [money('CNY', { expense: '1000', net: '-1000', transferOut: '700' }), money('USD', { transferIn: '100' })],
        '501': [money('CNY', { expense: '1500', net: '-1500', transferOut: '700' }), money('USD', { transferIn: '100' })],
        'none': [money('CNY', { income: '10000', expense: '200', net: '9800', adjustment: '1000', transferOut: '50' }), money('USD', { transferIn: '30' })]
    };
    return {
        memberRole: 1, query: { ...copyLedgerReportQuery(query), version: 1, types: [1, 2, 3, 4], accounts: null, categories: null, tags: null, currencies: null, hidden: 'exclude', utcRanges: [{ start: '1790812800', endExclusive: '1793491200' }] },
        snapshotHash: hash, dataComplete: true, matchedCount: details.length, details, totals,
        trend: [{ key: '2026-10-10', count: details.length, money: clone(totals) }],
        categories: Object.entries(fixture.expected.categoryCounts).map(([key, count]) => ({ key, ...(key === '0' ? {} : { name: fixture.categories.find(category => category.id === key)!.name }), count, money: categoryMoney[key as keyof typeof categoryMoney] })),
        tagGroups: Object.entries(fixture.expected.tagCounts).map(([key, count]) => ({ key, ...(key === 'none' ? {} : { name: readableTags.find(tag => tag.id === key)!.name }), count, money: tagMoney[key as keyof typeof tagMoney] })),
        tagGroupsAdditive: false, readableTags, accounts, categoryMetadata, offset: 0, returnedCount: details.length, nextOffset: null, detailsComplete: true, exportAllowed: true
    };
}

