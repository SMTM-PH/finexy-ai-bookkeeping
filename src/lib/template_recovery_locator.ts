import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import type { PendingTemplateCreate, TemplateEditorDraft } from '@/models/template_creation.ts';

export type TemplateRecoveryTarget = Extract<LedgerPendingTarget, { kind: 'template-create' }>;

/** Guard the final local read, after asynchronous navigation/permission checks.
 * A locator can inspect its original journal, never select its replacement. */
export function assertTemplateRecoveryLocator(target: TemplateRecoveryTarget | undefined, ledgerId: string, templateType: number,
    pending: PendingTemplateCreate | null, draft: TemplateEditorDraft | null, originalPending?: PendingTemplateCreate | null): void {
    const stale = () => new Error('原模板请求或草稿已变化，请返回待处理列表刷新；不会打开替代内容');
    if (originalPending && (!pending || pending.request.clientSessionId !== originalPending.request.clientSessionId ||
        pending.draftRevision !== originalPending.draftRevision || JSON.stringify(pending.request) !== JSON.stringify(originalPending.request) ||
        originalPending.receipt && (!pending.receipt || originalPending.receipt.id !== pending.receipt.id))) throw stale();
    if (!target) return;
    if (target.ledgerId !== ledgerId || target.templateType !== templateType) throw stale();
    if (target.recovery === 'pending') {
        if (!pending || pending.request.ledgerId !== ledgerId || pending.request.templateType !== templateType ||
            pending.request.clientSessionId !== target.clientSessionId || pending.draftRevision !== target.draftRevision) throw stale();
    } else if (pending || !draft || draft.input.ledgerId !== ledgerId || draft.input.templateType !== templateType || draft.revision !== target.draftRevision) throw stale();
}
