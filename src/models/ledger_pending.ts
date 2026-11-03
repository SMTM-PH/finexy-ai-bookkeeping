/** Read-only navigation metadata. Never retain amounts, source text, editor
 * input, financial requests or server credentials in the pending summary. */
export const LEDGER_PENDING_SOURCES = ['ai', 'periodic', 'transactionPending', 'transactionDraft', 'templatePending',
    'normalTemplateDraft', 'scheduledTemplateDraft', 'aiCreate', 'aiConfirm', 'aiEditor', 'aiSource'] as const;
export type LedgerPendingSource = typeof LEDGER_PENDING_SOURCES[number];
export type LedgerPendingSection = 'ai' | 'periodic' | 'local';
export type LedgerPendingReadState = 'loading' | 'ready' | 'unavailable' | 'denied' | 'error';
export type LedgerPendingEntryState = 'pending' | 'draft' | 'unknown-result' | 'receipt';

export type LedgerPendingTarget = { readonly ledgerId: string } & (
    { readonly kind: 'ai-review'; readonly id: string } |
    { readonly kind: 'periodic-review'; readonly templateId: string; readonly scheduledUnixTime: number } |
    { readonly kind: 'transaction-create'; readonly recovery: 'pending'; readonly clientSessionId: string } |
    { readonly kind: 'transaction-create'; readonly recovery: 'draft'; readonly clientSessionId?: string; readonly draftRevision: string } |
    { readonly kind: 'template-create'; readonly templateType: 1 | 2; readonly recovery: 'pending'; readonly clientSessionId: string; readonly draftRevision: string | null } |
    { readonly kind: 'template-create'; readonly templateType: 1 | 2; readonly recovery: 'draft'; readonly draftRevision: string } |
    { readonly kind: 'ai-source'; readonly recovery: 'pending'; readonly clientSessionId: string; readonly draftRevision: string | null } |
    { readonly kind: 'ai-source'; readonly recovery: 'draft'; readonly draftRevision: string }
);
export interface LedgerPendingContribution {
    readonly key: string;
    readonly section: LedgerPendingSection;
    readonly state: LedgerPendingEntryState;
    readonly targets: readonly LedgerPendingTarget[];
}
export interface LedgerPendingEntry extends LedgerPendingContribution {
    readonly sources: readonly LedgerPendingSource[];
}
export interface LedgerPendingSourceState {
    readonly state: LedgerPendingReadState;
    /** Only a successfully completed read has a numeric count. This is a
     * source count before cross-source deduplication, not a section subtotal. */
    readonly count: number | null;
    readonly message: string;
}
export interface LedgerPendingSourceResult {
    readonly state: LedgerPendingReadState;
    readonly message: string;
    readonly entries: readonly LedgerPendingContribution[];
}
export type LedgerPendingSources = Readonly<Record<LedgerPendingSource, LedgerPendingSourceResult>>;
export interface LedgerPendingSectionSummary {
    readonly state: LedgerPendingReadState;
    readonly count: number | null;
    readonly knownCount: number;
    readonly entries: readonly LedgerPendingEntry[];
}
export interface LedgerPendingSummary {
    readonly ledgerId: string;
    readonly refreshedAt: number;
    readonly memberRole: number | null;
    readonly knownCount: number;
    readonly exactTotal: number | null;
    readonly sections: Readonly<Record<LedgerPendingSection, LedgerPendingSectionSummary>>;
    readonly sourceStates: Readonly<Record<LedgerPendingSource, LedgerPendingSourceState>>;
}
