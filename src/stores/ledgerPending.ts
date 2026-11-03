import { onScopeDispose, shallowRef, watch } from 'vue';
import { defineStore } from 'pinia';
import { useLedgersStore } from './ledger.ts';
import { useUserStore } from './user.ts';
import services from '@/lib/services.ts';
import { mobileLedgerRole } from '@/lib/mobile_ledger.ts';
import { buildLedgerPendingSummary, assertPendingLedgerTransaction, ledgerPendingTargetKey, ledgerPendingDraftRevision } from '@/lib/ledger_pending.ts';
import { ledgerReportStorageInvalidatesSession } from '@/lib/ledger_report_session.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, isUserLogined, inspectUserTransactionDraft, getUserPendingTransactionCreate,
    getUserTemplateEditorDraft, getUserPendingTemplateCreate, getUserPendingAIReviewCreate, getUserPendingAIReviewConfirm,
    getUserAIReviewEditorDraft, getUserAIReviewSourceDraft } from '@/lib/userstate.ts';
import { assertAIReviewCapabilities, assertAIReviewItem, assertAIReviewEditorDraft, assertPendingAIReviewCreate, assertPendingAIReviewConfirm,
    type AIReviewEditorDraft, type PendingAIReviewCreate, type PendingAIReviewConfirm } from '@/models/ai_review_ledger.ts';
import { assertAIReviewSourceDraft, type AIReviewSourceDraft } from '@/models/ai_review_source.ts';
import { assertPendingTemplateCreate, assertTemplateEditorDraft, type PendingTemplateCreate, type TemplateEditorDraft } from '@/models/template_creation.ts';
import { ScheduledOccurrence } from '@/models/scheduled_occurrence.ts';
import { isPeriodicStringId } from '@/models/transaction_template.ts';
import { LEDGER_PENDING_SOURCES, type LedgerPendingContribution, type LedgerPendingSource, type LedgerPendingSourceResult,
    type LedgerPendingSources, type LedgerPendingSummary, type LedgerPendingTarget } from '@/models/ledger_pending.ts';
import type { PendingTransactionCreate, TransactionDraft } from '@/models/transaction.ts';

interface Context { readonly identity: string; readonly username: string; readonly ledgerId: string; readonly generation: number }
type Sources = Record<LedgerPendingSource, LedgerPendingSourceResult>;
const MAX_QUEUE_ROWS = 10000;
const ready = (entries: readonly LedgerPendingContribution[] = []): LedgerPendingSourceResult => ({ state: 'ready', message: '', entries });
function waiting(): Sources {
    const next = {} as Sources;
    for (const source of LEDGER_PENDING_SOURCES) next[source] = { state: 'loading', message: '尚未核对', entries: [] };
    return next;
}
function failure(error: unknown, local = false): LedgerPendingSourceResult {
    const value = error as { status?: number; response?: { status?: number } } | null;
    const status = value?.response?.status ?? value?.status;
    return { state: status === 403 || status === 401 ? 'denied' : status === 404 || status === 405 ? 'unavailable' : 'error', entries: [],
        message: status === 401 ? '登录已失效，请重新登录后核对' : status === 403 ? '当前账本读取权限已失效，本机恢复记录仍保留' :
            status === 404 || status === 405 ? '当前服务不支持此队列或原账本不可用，请升级服务或核对原账本' :
                local ? '无法完整读取本机记录，请先解锁或恢复原记录；不会替代写入' : '未能完整读取当前账本队列，请重试' };
}

/** Read-only aggregation over queue APIs and existing local journals. Only
 * minimal navigation metadata survives the read. No writes, retries, receipt
 * acknowledgements, model calls or transaction-list requests are made here. */
export const useLedgerPendingStore = defineStore('ledgerPending', () => {
    const ledgers = useLedgersStore(), user = useUserStore();
    const summary = shallowRef<LedgerPendingSummary | null>(null);
    let generation = 0, active: Context | null = null, sources: Sources = waiting(), memberRole: number | null = null;

    function reset(): void { generation++; active = null; summary.value = null; sources = waiting(); memberRole = null; }
    watch(() => [ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], reset, { flush: 'sync' });
    function current(context: Context): boolean {
        return active === context && generation === context.generation && context.ledgerId === ledgers.selectedLedgerId &&
            context.identity === getTransactionStateIdentity() && context.username === getCurrentUserInfo()?.username &&
            context.username === user.currentUserBasicInfo?.username && isUserLogined();
    }
    function capture(ledgerId: string): Context {
        const identity = getTransactionStateIdentity(), username = getCurrentUserInfo()?.username;
        if (!identity || !username || !isUserLogined() || !isPeriodicStringId(ledgerId) || ledgerId !== ledgers.selectedLedgerId || username !== user.currentUserBasicInfo?.username) {
            throw new Error('账号或账本已变化，请重新打开当前账本待处理入口');
        }
        return { identity, username, ledgerId, generation };
    }
    function assertCurrent(context: Context): void { if (!current(context)) throw new Error('待处理读取已过期'); }
    function publish(context: Context): void {
        if (current(context)) summary.value = buildLedgerPendingSummary(context.ledgerId, sources as LedgerPendingSources, memberRole, Date.now());
    }
    function summaryFor(ledgerId = ledgers.selectedLedgerId): LedgerPendingSummary | null {
        return active && ledgerId === active.ledgerId && current(active) ? summary.value : null;
    }
    function collectLocal(context: Context): void {
        const ledgerId = context.ledgerId;
        const read = <T>(key: LedgerPendingSource, getter: () => T | null, validate: (value: T) => void): T | null => {
            try { const value = getter(); if (value !== null) validate(value); sources[key] = ready(); return value; }
            catch (error) { sources[key] = failure(error, true); return null; }
        };
        // These temporary references are discarded synchronously after
        // projection; they are never assigned to the store or summary.
        const tx = read<PendingTransactionCreate>('transactionPending', () => getUserPendingTransactionCreate(ledgerId), value => assertPendingLedgerTransaction(value, ledgerId));
        const txDraft = read<TransactionDraft>('transactionDraft', () => inspectUserTransactionDraft(ledgerId), value => {
            if ((value.ledgerId ?? '0') !== ledgerId || (value.clientSessionId !== undefined && (typeof value.clientSessionId !== 'string' || !value.clientSessionId || value.clientSessionId.length > 256))) throw new Error('草稿归属无效');
        });
        const template = read<PendingTemplateCreate>('templatePending', () => getUserPendingTemplateCreate(ledgerId), value => assertPendingTemplateCreate(value, ledgerId, context.identity));
        const normalDraft = read<TemplateEditorDraft>('normalTemplateDraft', () => getUserTemplateEditorDraft(ledgerId, 1), value => assertTemplateEditorDraft(value, ledgerId, 1));
        const scheduledDraft = read<TemplateEditorDraft>('scheduledTemplateDraft', () => getUserTemplateEditorDraft(ledgerId, 2), value => assertTemplateEditorDraft(value, ledgerId, 2));
        const aiCreate = read<PendingAIReviewCreate>('aiCreate', () => getUserPendingAIReviewCreate(ledgerId), value => assertPendingAIReviewCreate(value, ledgerId, context.identity));
        const aiConfirm = read<PendingAIReviewConfirm>('aiConfirm', () => getUserPendingAIReviewConfirm(ledgerId), value => assertPendingAIReviewConfirm(value, ledgerId, context.identity));
        const aiEditor = read<AIReviewEditorDraft>('aiEditor', () => getUserAIReviewEditorDraft(ledgerId), value => assertAIReviewEditorDraft(value, ledgerId, context.identity));
        const aiSource = read<AIReviewSourceDraft>('aiSource', () => getUserAIReviewSourceDraft(ledgerId), value => assertAIReviewSourceDraft(value, ledgerId, context.identity));
        const set = (key: LedgerPendingSource, value: LedgerPendingContribution | null) => { if (sources[key].state === 'ready') sources[key] = ready(value ? [value] : []); };
        const txKey = tx ? `transaction:${tx.clientSessionId}` : null;
        set('transactionPending', tx ? { key: txKey!, section: 'local', state: tx.receipt ? 'receipt' : 'unknown-result',
            targets: [{ kind: 'transaction-create', ledgerId, recovery: 'pending', clientSessionId: tx.clientSessionId }] } : null);
        set('transactionDraft', txDraft ? { key: tx && txDraft.clientSessionId === tx.clientSessionId ? txKey! : `transaction-draft:${txDraft.clientSessionId ?? 'local'}`,
            section: 'local', state: 'draft', targets: [{ kind: 'transaction-create', ledgerId, recovery: 'draft',
                ...(txDraft.clientSessionId ? { clientSessionId: txDraft.clientSessionId } : {}), draftRevision: ledgerPendingDraftRevision(txDraft) }] } : null);
        const templateKey = template ? `template-create:${template.request.clientSessionId}` : null;
        set('templatePending', template ? { key: templateKey!, section: 'local', state: template.receipt ? 'receipt' : 'unknown-result',
            targets: [{ kind: 'template-create', ledgerId, templateType: template.request.templateType as 1 | 2, recovery: 'pending',
                clientSessionId: template.request.clientSessionId, draftRevision: template.draftRevision }] } : null);
        for (const [key, draft, templateType] of [['normalTemplateDraft', normalDraft, 1], ['scheduledTemplateDraft', scheduledDraft, 2]] as const) {
            set(key, draft ? { key: template?.request.templateType === templateType && template.draftRevision === draft.revision ? templateKey! : `template-draft:${templateType}:${draft.revision}`,
                section: 'local', state: 'draft', targets: [{ kind: 'template-create', ledgerId, templateType, recovery: 'draft', draftRevision: draft.revision }] } : null);
        }
        const createKey = aiCreate ? aiCreate.receipt ? `ai:${aiCreate.receipt.id}` : `ai-create:${aiCreate.request.clientSessionId}` : null;
        set('aiCreate', aiCreate ? { key: createKey!, section: aiCreate.receipt ? 'ai' : 'local', state: aiCreate.receipt ? 'receipt' : 'unknown-result',
            targets: [{ kind: 'ai-source', ledgerId, recovery: 'pending', clientSessionId: aiCreate.request.clientSessionId, draftRevision: aiCreate.draftRevision ?? null }] } : null);
        set('aiConfirm', aiConfirm ? { key: `ai:${aiConfirm.request.id}`, section: 'ai', state: aiConfirm.receipt ? 'receipt' : 'unknown-result',
            targets: [{ kind: 'ai-review', ledgerId, id: aiConfirm.request.id }] } : null);
        set('aiEditor', aiEditor ? { key: `ai:${aiEditor.input.id}`, section: 'ai', state: 'draft', targets: [{ kind: 'ai-review', ledgerId, id: aiEditor.input.id }] } : null);
        set('aiSource', aiSource ? { key: aiCreate?.draftRevision === aiSource.revision ? createKey! : `ai-source:${aiSource.revision}`,
            section: aiCreate?.draftRevision === aiSource.revision && aiCreate.receipt ? 'ai' : 'local', state: 'draft',
            targets: [{ kind: 'ai-source', ledgerId, recovery: 'draft', draftRevision: aiSource.revision }] } : null);
        assertCurrent(context); publish(context);
    }
    function refreshLocal(ledgerId = ledgers.selectedLedgerId): void {
        if (!active || !current(active) || active.ledgerId !== ledgerId) { reset(); active = capture(ledgerId); }
        collectLocal(active);
    }
    async function authorize(context: Context, verifyDefaultSession = false): Promise<void> {
        assertCurrent(context);
        memberRole = null;
        if (context.ledgerId === '0') {
            // The implicit personal ledger has no member-list resource. A
            // navigation preflight still verifies the live login, read-only.
            if (verifyDefaultSession) {
                const response = await services.getProfile({ expectedUsername: context.username, ignoreError: true });
                assertCurrent(context);
                if (!response.data?.success || response.data.result?.username !== context.username) throw new Error('无法核验当前登录');
            }
            memberRole = 1; return;
        }
        const response = await services.listLedgerMembers({ ledgerId: context.ledgerId, expectedUsername: context.username, ignoreError: true });
        assertCurrent(context);
        if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('成员响应不完整');
        memberRole = mobileLedgerRole(context.ledgerId, response.data.result);
    }
    async function readAI(context: Context): Promise<LedgerPendingSourceResult> {
        const capability = await services.getAIReviewCapabilities(context.ledgerId, context.username); assertCurrent(context);
        if (!capability.data?.success || !capability.data.result) throw new Error('服务能力回包不完整');
        try { assertAIReviewCapabilities(capability.data.result, context.ledgerId); }
        catch { return { state: 'unavailable', message: '当前服务缺少可靠的账本 AI 队列能力，请升级服务；本机内容已保留', entries: [] }; }
        const entries: LedgerPendingContribution[] = [], seen = new Set<string>(); let beforeId = '0';
        while (true) {
            const response = await services.listLedgerAIReviewItems({ ledgerId: context.ledgerId, status: 1, beforeId, limit: 100 }, context.username); assertCurrent(context);
            if (!response.data?.success || !Array.isArray(response.data.result) || response.data.result.length > 100) throw new Error('队列响应不完整');
            let previous = beforeId === '0' ? null : BigInt(beforeId);
            for (const row of response.data.result) {
                assertAIReviewItem(row, context.ledgerId);
                if (row.status !== 1 || row.deleted || seen.has(row.id) || previous !== null && BigInt(row.id) >= previous) throw new Error('队列重复、乱序或状态失效');
                previous = BigInt(row.id); seen.add(row.id);
                entries.push({ key: `ai:${row.id}`, section: 'ai', state: 'pending', targets: [{ kind: 'ai-review', ledgerId: context.ledgerId, id: row.id }] });
            }
            if (entries.length > MAX_QUEUE_ROWS) throw new Error('队列过大，无法完整核对');
            if (response.data.result.length < 100) return ready(entries);
            beforeId = response.data.result[response.data.result.length - 1]!.id;
        }
    }
    async function readPeriodic(context: Context): Promise<LedgerPendingSourceResult> {
        const entries: LedgerPendingContribution[] = [], seen = new Set<string>();
        for (let offset = 0; ; offset += 100) {
            const response = await services.listScheduledOccurrences({ status: 1, offset, limit: 100, ledgerId: context.ledgerId, expectedUsername: context.username, ignoreError: true }); assertCurrent(context);
            if (!response.data?.success || !Array.isArray(response.data.result) || response.data.result.length > 100) throw new Error('周期队列响应不完整');
            for (const value of response.data.result) {
                const row = ScheduledOccurrence.of(value, context.ledgerId), key = `periodic:${row.templateId}:${row.scheduledUnixTime}`;
                if (!row.isPending || seen.has(key)) throw new Error('周期队列重复或状态失效');
                seen.add(key); entries.push({ key, section: 'periodic', state: 'pending', targets: [{ kind: 'periodic-review', ledgerId: context.ledgerId, templateId: row.templateId, scheduledUnixTime: row.scheduledUnixTime }] });
            }
            if (entries.length > MAX_QUEUE_ROWS) throw new Error('周期队列过大，无法完整核对');
            if (response.data.result.length < 100) return ready(entries);
        }
    }
    function targetSources(target: LedgerPendingTarget): LedgerPendingSource[] {
        const key = ledgerPendingTargetKey(target);
        return LEDGER_PENDING_SOURCES.filter(source => sources[source].state === 'ready' &&
            sources[source].entries.some(entry => entry.targets.some(value => ledgerPendingTargetKey(value) === key)));
    }
    /** Authorizes navigation only. Never switch ledgers, replay a request,
     * acknowledge a result, recognize content or perform a financial write.
     * Destination editors must recheck the supplied local locator when opened. */
    async function verifyTarget(target: LedgerPendingTarget): Promise<void> {
        const context = active;
        const stale = () => new Error('原待处理条目已变化或不可读取，请刷新当前账本后重新打开');
        if (!context || !current(context) || target.ledgerId !== context.ledgerId || !summaryFor(target.ledgerId) || !targetSources(target).length) throw stale();
        try { await authorize(context, true); }
        catch (error) {
            if (current(context)) { sources.ai = failure(error); sources.periodic = failure(error); publish(context); }
            throw new Error('无法核验原账本的当前读取权限，请刷新或重新登录；本机记录仍保留');
        }
        assertCurrent(context);
        // Re-read strict journals, including draft revisions, after the live
        // permission check. A stale notification cannot select a replacement.
        collectLocal(context);
        let matching = targetSources(target);
        if (matching.some(source => source !== 'ai' && source !== 'periodic')) return;
        const remote = target.kind === 'ai-review' ? 'ai' : target.kind === 'periodic-review' ? 'periodic' : null;
        if (!remote || !matching.includes(remote)) throw stale();
        try {
            const result = await (remote === 'ai' ? readAI(context) : readPeriodic(context));
            assertCurrent(context); sources[remote] = result;
        }
        catch (error) {
            if (current(context)) { sources[remote] = failure(error); publish(context); }
            throw stale();
        }
        assertCurrent(context);
        collectLocal(context);
        matching = targetSources(target);
        publish(context);
        if (!matching.length) throw stale();
    }
    async function refresh(ledgerId = ledgers.selectedLedgerId): Promise<void> {
        reset(); const context = capture(ledgerId); active = context;
        collectLocal(context);
        try { await authorize(context); }
        catch (error) { if (current(context)) { sources.ai = failure(error); sources.periodic = failure(error); publish(context); } return; }
        if (!current(context)) return;
        const results = await Promise.allSettled((['ai', 'periodic'] as const).map(async key => {
            try { const result = await (key === 'ai' ? readAI(context) : readPeriodic(context)); if (current(context)) sources[key] = result; }
            catch (error) { if (current(context)) sources[key] = failure(error); }
            publish(context);
        }));
        const rejected = results.find(result => result.status === 'rejected');
        if (rejected?.status === 'rejected' && current(context)) {
            // A projection invariant failure is never a complete/zero summary.
            reset(); throw new Error('待处理状态无法完整核对，请保留本机内容后重试');
        }
    }
    if (typeof window !== 'undefined') {
        const storageChanged = (event: StorageEvent) => {
            if (!active) return;
            const session = { identity: active.identity, username: active.username, hasToken: true };
            if (ledgerReportStorageInvalidatesSession(event.key, event.newValue, session,
                { identity: getTransactionStateIdentity(), username: getCurrentUserInfo()?.username ?? null, hasToken: isUserLogined() }, user.currentUserBasicInfo?.username ?? null)) {
                reset(); return;
            }
            if (event.key?.startsWith(active.identity) || event.key === 'ebk_user_draft_transaction') refreshLocal(active.ledgerId);
        };
        window.addEventListener('storage', storageChanged);
        onScopeDispose(() => window.removeEventListener('storage', storageChanged));
    }
    return { summaryFor, refresh, refreshLocal, verifyTarget, reset };
});
