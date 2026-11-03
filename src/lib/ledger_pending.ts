import CryptoJS from 'crypto-js';
import { LEDGER_PENDING_SOURCES, type LedgerPendingEntry, type LedgerPendingEntryState, type LedgerPendingReadState, type LedgerPendingTarget,
    type LedgerPendingSection, type LedgerPendingSources, type LedgerPendingSummary, type LedgerPendingSource } from '@/models/ledger_pending.ts';
import type { PendingTransactionCreate } from '@/models/transaction.ts';
import type { TransactionDraft } from '@/models/transaction.ts';
import { assertPendingTransactionCreate } from '@/lib/transaction_submission.ts';

/** Ordinary drafts predate revisioned journals. An opaque digest identifies the
 * exact observed draft without copying its financial input into navigation. */
export function ledgerPendingDraftRevision(value: TransactionDraft): string {
    return CryptoJS.SHA256(JSON.stringify(value)).toString();
}

/** Compare only the public navigation contract; property insertion order and
 * a caller's incidental extra properties cannot select another journal. */
export function ledgerPendingTargetKey(target: LedgerPendingTarget): string {
    switch (target.kind) {
        case 'ai-review': return JSON.stringify([target.ledgerId, target.kind, target.id]);
        case 'periodic-review': return JSON.stringify([target.ledgerId, target.kind, target.templateId, target.scheduledUnixTime]);
        case 'transaction-create': return JSON.stringify([target.ledgerId, target.kind, target.recovery, target.clientSessionId ?? null,
            target.recovery === 'draft' ? target.draftRevision : null]);
        case 'template-create': return JSON.stringify([target.ledgerId, target.kind, target.templateType, target.recovery,
            target.recovery === 'pending' ? target.clientSessionId : null, target.draftRevision]);
        case 'ai-source': return JSON.stringify([target.ledgerId, target.kind, target.recovery,
            target.recovery === 'pending' ? target.clientSessionId : null, target.draftRevision]);
    }
}

const priority: Readonly<Record<LedgerPendingEntryState, number>> = { pending: 0, draft: 1, 'unknown-result': 2, receipt: 3 };
const relevant: Readonly<Record<LedgerPendingSection, readonly LedgerPendingSource[]>> = {
    ai: ['ai', 'aiCreate', 'aiConfirm', 'aiEditor', 'aiSource'],
    periodic: ['periodic'],
    local: ['transactionPending', 'transactionDraft', 'templatePending', 'normalTemplateDraft', 'scheduledTemplateDraft', 'aiCreate', 'aiSource']
};
function combinedState(states: readonly LedgerPendingReadState[]): LedgerPendingReadState {
    for (const state of ['loading', 'denied', 'error', 'unavailable'] as const) if (states.includes(state)) return state;
    return 'ready';
}

/** The source reads are independent observations, not one server transaction.
 * Known receipts and drafts merge into the same located object; this function
 * neither acknowledges a receipt nor retries any financial operation. */
export function buildLedgerPendingSummary(ledgerId: string, sources: LedgerPendingSources, memberRole: number | null, refreshedAt: number): LedgerPendingSummary {
    const entries = new Map<string, LedgerPendingEntry>();
    const sourceStates = {} as Record<LedgerPendingSource, LedgerPendingSummary['sourceStates'][LedgerPendingSource]>;
    for (const source of LEDGER_PENDING_SOURCES) {
        const result = sources[source];
        if (result.state !== 'ready' && result.entries.length) throw new Error('未完成的待处理读取不能发布条目');
        sourceStates[source] = Object.freeze({ state: result.state, message: result.message, count: result.state === 'ready' ? result.entries.length : null });
        for (const entry of result.entries) {
            if (!entry.key || !entry.targets.length || entry.targets.some(target => target.ledgerId !== ledgerId)) throw new Error('待处理条目不属于当前账本');
            const previous = entries.get(entry.key);
            if (previous && previous.section !== entry.section) throw new Error('同一待处理对象的分类不一致');
            const targets = [...(previous?.targets ?? [])];
            for (const target of entry.targets) if (!targets.some(value => ledgerPendingTargetKey(value) === ledgerPendingTargetKey(target))) targets.push(Object.freeze({ ...target }));
            entries.set(entry.key, Object.freeze({ key: entry.key, section: entry.section,
                state: previous && priority[previous.state] > priority[entry.state] ? previous.state : entry.state,
                sources: Object.freeze([...(previous?.sources ?? []), source]), targets: Object.freeze(targets) }));
        }
    }
    const sections = {} as Record<LedgerPendingSection, LedgerPendingSummary['sections'][LedgerPendingSection]>;
    for (const section of ['ai', 'periodic', 'local'] as const) {
        const rows = Object.freeze([...entries.values()].filter(entry => entry.section === section));
        const state = combinedState(relevant[section].map(source => sources[source].state));
        sections[section] = Object.freeze({ state, count: state === 'ready' ? rows.length : null, knownCount: rows.length, entries: rows });
    }
    return Object.freeze({ ledgerId, refreshedAt, memberRole, knownCount: entries.size,
        exactTotal: LEDGER_PENDING_SOURCES.every(source => sources[source].state === 'ready') ? entries.size : null,
        sections: Object.freeze(sections), sourceStates: Object.freeze(sourceStates) });
}

/** Mirrors the existing durable normal-create journal boundary without
 * instantiating the transaction store or loading financial transaction rows. */
export function assertPendingLedgerTransaction(value: PendingTransactionCreate, ledgerId: string): void {
    assertPendingTransactionCreate(value, ledgerId);
}
