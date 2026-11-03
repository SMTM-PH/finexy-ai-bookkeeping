import { isPeriodicStringId } from '@/models/transaction_template.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';

/** URLs contain only a bounded locator, never editor input or a write command.
 * A parsed locator still requires the store's fresh verifyTarget check. */
export function parseLedgerPendingTarget(value: unknown): LedgerPendingTarget {
    if (typeof value !== 'string' || value.length > 2048) throw new Error('待处理定位无效');
    const row: unknown = JSON.parse(value);
    if (!row || typeof row !== 'object' || Array.isArray(row)) throw new Error('待处理定位无效');
    const item = row as { ledgerId?: unknown; kind?: unknown; id?: unknown; templateId?: unknown; scheduledUnixTime?: unknown; recovery?: unknown; clientSessionId?: unknown; draftRevision?: unknown; templateType?: unknown };
    if (!isPeriodicStringId(item.ledgerId)) throw new Error('原账本定位无效');
    const ledgerId = item.ledgerId as string;
    const locator = (input: unknown): string => {
        if (typeof input !== 'string' || !input || input.length > 256 || /[\u0000-\u001f]/.test(input)) throw new Error('原记录定位无效');
        return input;
    };
    if (item.kind === 'ai-review' && isPeriodicStringId(item.id) && item.id !== '0') return { kind: item.kind, ledgerId, id: item.id as string };
    if (item.kind === 'periodic-review' && isPeriodicStringId(item.templateId) && item.templateId !== '0' && Number.isSafeInteger(item.scheduledUnixTime) && Number(item.scheduledUnixTime) > 0 && Number(item.scheduledUnixTime) <= 253402300799) {
        return { kind: item.kind, ledgerId, templateId: item.templateId as string, scheduledUnixTime: item.scheduledUnixTime as number };
    }
    if (item.kind === 'transaction-create') {
        if (item.recovery === 'pending') return { kind: item.kind, ledgerId, recovery: 'pending', clientSessionId: locator(item.clientSessionId) };
        if (item.recovery === 'draft' && typeof item.draftRevision === 'string' && /^[a-f0-9]{64}$/.test(item.draftRevision)) {
            return { kind: item.kind, ledgerId, recovery: 'draft', draftRevision: item.draftRevision, ...(item.clientSessionId === undefined ? {} : { clientSessionId: locator(item.clientSessionId) }) };
        }
    }
    if (item.kind === 'template-create' || item.kind === 'ai-source') {
        if (item.kind === 'template-create' && item.templateType !== 1 && item.templateType !== 2) throw new Error('原模板类型无效');
        const base = item.kind === 'template-create' ? { kind: 'template-create' as const, ledgerId, templateType: item.templateType as 1 | 2 } : { kind: 'ai-source' as const, ledgerId };
        if (item.recovery === 'pending') return { ...base, recovery: 'pending', clientSessionId: locator(item.clientSessionId), draftRevision: item.draftRevision === null ? null : locator(item.draftRevision) };
        if (item.recovery === 'draft') return { ...base, recovery: 'draft', draftRevision: locator(item.draftRevision) };
    }
    throw new Error('待处理定位无效');
}

export function mobileLedgerPendingLocation(target: LedgerPendingTarget): string {
    const query = new URLSearchParams({ pendingTarget: JSON.stringify(target), ledgerId: target.ledgerId });
    if (target.kind === 'ai-review') query.set('aiReviewId', target.id);
    if (target.kind === 'template-create') query.set('templateType', String(target.templateType));
    const path = target.kind === 'periodic-review' ? '/schedule/review' : target.kind === 'ai-source' ? '/ai/source' : target.kind === 'template-create' ? '/template/add' : '/transaction/add';
    return `${path}?${query}`;
}

export function desktopLedgerPendingLocation(target: LedgerPendingTarget): { path: string; query: { pendingTarget: string } } {
    return { path: target.kind === 'periodic-review' ? '/schedule/list' : target.kind === 'template-create' ? target.templateType === 2 ? '/schedule/list' : '/template/list' : '/transaction/list', query: { pendingTarget: JSON.stringify(target) } };
}
