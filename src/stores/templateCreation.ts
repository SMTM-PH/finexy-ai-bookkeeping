import { defineStore } from 'pinia';
import { TransactionTemplate, type TransactionTemplateCreateRequest } from '@/models/transaction_template.ts';
import { assertPendingTemplateCreate, assertTemplateCreateRequest, assertTemplateCreationCapabilities, assertTemplateEditorDraft,
    type PendingTemplateCreate, type TemplateCreationType, type TemplateEditorDraft } from '@/models/template_creation.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, withTransactionCreateLock,
    getUserTemplateEditorDraft, updateUserTemplateEditorDraft, clearUserTemplateEditorDraft,
    getUserPendingTemplateCreate, updateUserPendingTemplateCreate, clearUserPendingTemplateCreate } from '@/lib/userstate.ts';
import services from '@/lib/services.ts';
import { usePeriodicScope } from './periodicScope.ts';

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

/** Durable state belongs to the original identity and ledger, rather than the
 * lifetime of a page/store. Only an explicit confirmation enters this journal. */
export const useTemplateCreationStore = defineStore('templateCreation', () => {
    const scope = usePeriodicScope(() => {});
    async function authorize(context: ReturnType<typeof scope.capture>): Promise<void> {
        try { await scope.authorize(context, 'manage'); }
        catch (error) {
            const message = error instanceof Error ? error.message : '无法核验原账本模板管理权限，请重试';
            throw Object.assign(new Error(message), { stale: (error as { stale?: boolean })?.stale === true });
        }
    }
    function identity(): string {
        const current = getTransactionStateIdentity();
        if (!current) throw new Error('请登录后恢复模板');
        return current;
    }
    function readPending(ledgerId: string): PendingTemplateCreate | null {
        const current = identity();
        const pending = getUserPendingTemplateCreate(ledgerId);
        if (pending) assertPendingTemplateCreate(pending, ledgerId, current);
        return pending ? copy(pending) : null;
    }
    function readDraft(ledgerId: string, kind: TemplateCreationType): TemplateEditorDraft | null {
        identity();
        const draft = getUserTemplateEditorDraft(ledgerId, kind);
        if (draft) assertTemplateEditorDraft(draft, ledgerId, kind);
        return draft ? copy(draft) : null;
    }
    async function saveDraft(draft: TemplateEditorDraft, expectedRevision: string | null): Promise<void> {
        const ledgerId = draft.input.ledgerId;
        const context = scope.capture(ledgerId);
        const frozen = copy(draft);
        assertTemplateEditorDraft(frozen, ledgerId, draft.input.templateType as TemplateCreationType);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPending(ledgerId)) throw new Error('已有模板结果待确认，请先处理原请求');
            const current = readDraft(ledgerId, draft.input.templateType as TemplateCreationType);
            if ((current?.revision ?? null) !== expectedRevision) throw new Error('另一个页面已更新草稿，请先重新读取');
            updateUserTemplateEditorDraft(ledgerId, draft.input.templateType as TemplateCreationType, frozen);
        });
    }
    async function discardDraft(ledgerId: string, kind: TemplateCreationType, revision: string): Promise<void> {
        const context = scope.capture(ledgerId);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPending(ledgerId)) throw new Error('结果待确认时不能丢弃原请求');
            if (readDraft(ledgerId, kind)?.revision !== revision) throw new Error('草稿已变化，请重新读取后确认丢弃');
            clearUserTemplateEditorDraft(ledgerId, kind);
        });
    }
    function assertActionCurrent(isActionCurrent?: () => boolean): void {
        if (isActionCurrent && !isActionCurrent()) throw Object.assign(new Error('原模板页面已离开，请返回原账本重新核对；原记录仍保留'), { stale: true });
    }
    async function confirm(request: TransactionTemplateCreateRequest, draftRevision: string | null, isActionCurrent?: () => boolean): Promise<PendingTemplateCreate> {
        const ledgerId = request.ledgerId ?? '0';
        const context = scope.capture(ledgerId);
        const frozen = copy({ ...request, ledgerId });
        assertTemplateCreateRequest(frozen, ledgerId);
        assertActionCurrent(isActionCurrent);
        await authorize(context);
        assertActionCurrent(isActionCurrent);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            assertActionCurrent(isActionCurrent);
            if (readPending(ledgerId)) throw new Error('已有模板待确认，请重试原请求，不能替代新增');
            if ((readDraft(ledgerId, request.templateType as TemplateCreationType)?.revision ?? null) !== draftRevision) {
                throw new Error('确认期间草稿已变化，请重新预览');
            }
            updateUserPendingTemplateCreate(ledgerId, { version: 1, identity: context.identity, request: frozen, draftRevision });
        });
        return retry(ledgerId, frozen.clientSessionId, isActionCurrent);
    }
    async function retry(ledgerId: string, clientSessionId: string, isActionCurrent?: () => boolean): Promise<PendingTemplateCreate> {
        const context = scope.capture(ledgerId);
        assertActionCurrent(isActionCurrent);
        const pending = readPending(ledgerId);
        if (!pending || pending.request.clientSessionId !== clientSessionId) throw new Error('原确认请求已变化，请重新读取');
        // An acknowledged server write may only be followed by reads, even if
        // page refresh failed or permission was revoked afterwards.
        if (pending.receipt) return pending;
        await authorize(context);
        assertActionCurrent(isActionCurrent);
        const capabilities = await services.getTemplateCreationCapabilities(context.username).catch((error: unknown) => {
            if ((error as { response?: { status?: number } })?.response?.status === 404) {
                throw new Error('当前服务不支持可靠模板恢复，请升级服务；原确认请求已保留');
            }
            throw new Error('未能核验服务模板恢复能力；原请求已保留，请重试');
        });
        scope.assertCurrent(context);
        assertActionCurrent(isActionCurrent);
        if (!capabilities.data?.success) throw new Error('未能核验服务模板恢复能力；原请求已保留');
        assertTemplateCreationCapabilities(capabilities.data.result, pending.request.templateType);
        const response = await services.addDurableTransactionTemplate(copy(pending.request), context.username).catch((error: unknown) => {
            if ((error as { response?: { status?: number } })?.response?.status === 403) {
                throw new Error('已失去模板管理权限，原确认请求已保留');
            }
            // Never propagate an Axios request/config (token and financial
            // contents) into the caller's generic error logger.
            throw new Error('模板结果尚未确认，请重试原请求');
        });
        if (context.identity !== getTransactionStateIdentity() || context.username !== getCurrentUserInfo()?.username) {
            throw new Error('账号已变化，原请求保留供原账号核对');
        }
        if (!response.data?.success || !response.data.result) throw new Error('模板回包不完整，请重试原请求');
        const result = response.data.result;
        const saved = TransactionTemplate.ofTemplate(result, ledgerId);
        if (saved.templateType !== pending.request.templateType || (result.deleted !== undefined && typeof result.deleted !== 'boolean')
            || (result.deleted === true && result.editable !== false)) {
            throw new Error('模板回包不匹配，请重试原请求');
        }
        const receipt = { id: saved.id, deleted: result.deleted === true };
        let recordedReceipt = receipt;
        await withTransactionCreateLock(() => {
            if (context.identity !== identity()) throw new Error('账号已变化，原请求保留供原账号核对');
            const current = readPending(ledgerId);
            if (!current) return; // Another tab has already handled this receipt.
            if (current.request.clientSessionId !== clientSessionId) return;
            if (current.receipt && current.receipt.id !== receipt.id) {
                throw new Error('创建回执不一致，请保留原记录后核对');
            }
            recordedReceipt = { ...receipt, deleted: current.receipt?.deleted === true || receipt.deleted };
            updateUserPendingTemplateCreate(ledgerId, { ...current, receipt: recordedReceipt });
        });
        return { ...pending, receipt: recordedReceipt };
    }
    async function acknowledge(ledgerId: string, clientSessionId: string, resultId: string): Promise<void> {
        const captured = identity();
        await withTransactionCreateLock(() => {
            if (captured !== identity()) throw new Error('账号已变化，请重新读取');
            const pending = readPending(ledgerId);
            if (!pending || pending.request.clientSessionId !== clientSessionId || pending.receipt?.id !== resultId) return;
            const kind = pending.request.templateType as TemplateCreationType;
            if (readDraft(ledgerId, kind)?.revision === pending.draftRevision) clearUserTemplateEditorDraft(ledgerId, kind);
            // Removing the optional draft first leaves the receipt intact if
            // storage fails, so the next page cannot issue a replacement write.
            clearUserPendingTemplateCreate(ledgerId);
        });
    }
    return { readDraft, saveDraft, discardDraft, readPending, confirm, retry, acknowledge };
});
