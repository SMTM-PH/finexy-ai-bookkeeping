import { describe, expect, it } from 'vitest';
import { mobilePendingRouteTarget } from '@/lib/mobile_pending_route.ts';
import { mobileLedgerPendingLocation } from '@/lib/ledger_pending_navigation.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';

const ledgerId = '9007199254740993';
const targets: LedgerPendingTarget[] = [
    { kind: 'ai-review', ledgerId, id: '9007199254740995' },
    { kind: 'periodic-review', ledgerId, templateId: '9007199254740997', scheduledUnixTime: 1792540800 },
    { kind: 'transaction-create', ledgerId, recovery: 'pending', clientSessionId: 'original-transaction' },
    { kind: 'transaction-create', ledgerId, recovery: 'draft', draftRevision: 'a'.repeat(64) },
    { kind: 'template-create', ledgerId, templateType: 1, recovery: 'pending', clientSessionId: 'original-template', draftRevision: null },
    { kind: 'template-create', ledgerId, templateType: 2, recovery: 'draft', draftRevision: 'original-template-draft' },
    { kind: 'ai-source', ledgerId, recovery: 'pending', clientSessionId: 'original-source', draftRevision: null },
    { kind: 'ai-source', ledgerId: '0', recovery: 'draft', draftRevision: 'original-source-draft' }
];
function location(target: LedgerPendingTarget) {
    const url = new URL(mobileLedgerPendingLocation(target), 'https://finexy.invalid');
    return { path: url.pathname, query: Object.fromEntries(url.searchParams) };
}

describe('mobile notification route isolation', () => {
    it.each(targets)('keeps the original %j locator with exact large IDs', target => {
        const route = location(target);
        expect(mobilePendingRouteTarget(route.path, route.query)).toEqual(target);
    });
    it('leaves ordinary routes without a pending locator on their existing path', () => {
        expect(mobilePendingRouteTarget('/transaction/add', { amount: '125' })).toBeNull();
    });
    it.each(['id', 'amount', 'type', 'templateId', 'noTransactionDraft'])('rejects extra editor parameter %s', key => {
        const route = location(targets[2]!);
        expect(() => mobilePendingRouteTarget(route.path, { ...route.query, [key]: 'replacement' })).toThrow();
    });
    it('cannot use a source locator to open a transaction form', () => {
        const route = location(targets[6]!);
        expect(() => mobilePendingRouteTarget('/transaction/add', route.query)).toThrow();
    });
    it('rejects missing or changed ledger and target-specific query parameters', () => {
        for (const target of [targets[0]!, targets[4]!]) {
            const route = location(target);
            for (const key of Object.keys(route.query).filter(key => key !== 'pendingTarget')) {
                const missing = { ...route.query }; delete missing[key];
                expect(() => mobilePendingRouteTarget(route.path, missing)).toThrow();
                expect(() => mobilePendingRouteTarget(route.path, { ...route.query, [key]: '0' })).toThrow();
            }
        }
    });
    it.each([null, [], ['{}'], '', '{'])('never treats malformed locator %j as an ordinary route', value => {
        expect(() => mobilePendingRouteTarget('/transaction/add', { pendingTarget: value, ledgerId })).toThrow();
    });
});
