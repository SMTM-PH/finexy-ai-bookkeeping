import services from '@/lib/services.ts';
import { Transaction } from '@/models/transaction.ts';

/** Read every bounded API page; a changed page/ledger discards the old snapshot. */
export async function loadWorkspaceTransactions(ledgerId: string | undefined, isCurrent: () => boolean): Promise<Transaction[] | null> {
    const rows: Transaction[] = [];
    const seen = new Set<string>();
    let maxTime = 0;
    for (let page = 0; page < 2000; page++) {
        if (!isCurrent()) return null;
        const response = await services.getTransactions({
            ledgerId: ledgerId ?? '0', maxTime, minTime: 0, count: 50, page: 1, withCount: false,
            type: 0, categoryIds: '', accountIds: '', tagFilter: '', amountFilter: '', keyword: '', matchMode: 0,
        });
        if (!isCurrent()) return null;
        const data = response.data;
        if (!data?.success || !Array.isArray(data.result?.items)) throw new Error('无法读取当前账本流水，请重试。');
        for (const row of Transaction.ofMulti(data.result.items)) {
            if (!row.id || seen.has(row.id)) throw new Error('流水分页数据发生变化，请刷新重试。');
            seen.add(row.id);
            rows.push(row);
        }
        const next = data.result.nextTimeSequenceId;
        if (next === undefined || next === null) return rows;
        // Go serializes this millisecond time cursor as a decimal string.
        // It is a bounded timestamp, never an entity ID.
        const cursor = Number(next);
        if (!/^\d+$/.test(String(next)) || !Number.isSafeInteger(cursor) || cursor <= 0 || (maxTime > 0 && cursor >= maxTime)) throw new Error('流水分页异常，请刷新重试。');
        maxTime = cursor;
    }
    throw new Error('流水数量过多，暂时无法完整读取。');
}
