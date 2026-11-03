import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import { mobileLedgerPendingLocation, parseLedgerPendingTarget } from '@/lib/ledger_pending_navigation.ts';

/** A notification locator can only open its original read/recovery page. Extra
 * editor parameters must not turn a verified locator into a different form. */
export function mobilePendingRouteTarget(path: string, query: Readonly<Record<string, unknown>>): LedgerPendingTarget | null {
    if (!Object.hasOwn(query, 'pendingTarget')) return null;
    const target = parseLedgerPendingTarget(query['pendingTarget']);
    const expected = new URL(mobileLedgerPendingLocation(target), 'https://finexy.invalid');
    const expectedKeys = Array.from(expected.searchParams.keys());
    if (path !== expected.pathname || Object.keys(query).length !== expectedKeys.length ||
        expectedKeys.some(key => !Object.hasOwn(query, key) || key !== 'pendingTarget' && query[key] !== expected.searchParams.get(key))) {
        throw new Error('待处理入口与原记录不一致，请回总览重新核对');
    }
    return target;
}
