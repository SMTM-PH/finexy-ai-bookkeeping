import { ref } from 'vue';
import { defineStore } from 'pinia';
import { useLedgersStore } from './ledger.ts';
import { usePeriodicScope } from './periodicScope.ts';
import services from '@/lib/services.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, withTransactionCreateLock,
    getUserPendingAIReviewCreate, updateUserPendingAIReviewCreate, clearUserPendingAIReviewCreate,
    getUserPendingAIReviewConfirm, updateUserPendingAIReviewConfirm, clearUserPendingAIReviewConfirm,
    getUserAIReviewEditorDraft, updateUserAIReviewEditorDraft, clearUserAIReviewEditorDraft,
    getUserAIReviewSourceDraft, updateUserAIReviewSourceDraft, clearUserAIReviewSourceDraft } from '@/lib/userstate.ts';
import { assertAIReviewSourceDraft, type AIReviewSourceDraft, type AIReviewSourceImage } from '@/models/ai_review_source.ts';
import { removeAIReviewSourceImage } from '@/lib/ai_source_image.ts';
import { trimAIReviewSource, assertAIRecognition, assertAIReviewCapabilities, assertAIReviewItem, assertAIReviewCreate, assertAIReviewConfirm, assertAIReviewReceipt,
    assertPendingAIReviewCreate, assertPendingAIReviewConfirm, assertAIReviewEditorDraft,
    type AIReviewLedgerItem, type LedgerAIReviewCreateRequest, type AIReviewConfirmRequest,
    type PendingAIReviewCreate, type PendingAIReviewConfirm, type AIReviewEditorDraft } from '@/models/ai_review_ledger.ts';

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
type Context = ReturnType<ReturnType<typeof usePeriodicScope>['capture']>;
function safeFailure(error: unknown, fallback: string): Error {
    const status = (error as { response?: { status?: number } })?.response?.status;
    const message = status === 403 ? '已失去原账本操作权限，本机内容已保留'
        : status === 409 ? '原请求与服务器状态冲突，请保留内容后核对，不能替代入账'
        : status === 404 ? '当前服务或原对象不可用，请升级服务或重新核对；本机内容已保留' : fallback;
    // Do not forward Axios config/credentials or financial input to UI loggers.
    return Object.assign(new Error(message), { stale: (error as { stale?: boolean })?.stale === true });
}
function recognitionContent(value: AIReviewLedgerItem['recognizedData']) {
    if (!value) return null;
    return { type: value.type, time: value.time ?? 0, categoryId: value.categoryId ?? '0', sourceAccountId: value.sourceAccountId ?? '0',
        destinationAccountId: value.destinationAccountId ?? '0', sourceAmount: value.sourceAmount ?? 0,
        destinationAmount: value.destinationAmount ?? 0, tagIds: value.tagIds ?? [], comment: value.comment ?? '' };
}

/** The journal survives stores/pages. A known receipt is followed only by reads;
 * an unknown result can only resend the exact previously confirmed snapshot. */
export const useAIReviewLedgerStore = defineStore('aiReviewLedger', () => {
    const ledgers = useLedgersStore();
    const items = ref<AIReviewLedgerItem[]>([]);
    const loading = ref(false), loaded = ref(false), errorMessage = ref('');
    let loadSerial = 0;
    const scope = usePeriodicScope(() => { loadSerial++; items.value = []; loading.value = false; loaded.value = false; errorMessage.value = ''; });
    function identity(): string {
        const current = getTransactionStateIdentity();
        if (!current) throw new Error('请登录后恢复原账本 AI 内容');
        return current;
    }
    function assertIdentity(context: Context): void {
        if (context.identity !== identity() || context.username !== getCurrentUserInfo()?.username) {
            throw new Error('账号已变化，原请求保留供原账号核对');
        }
    }
    async function authorize(context: Context, write = false): Promise<void> {
        try { await scope.authorize(context, write ? 'review' : 'read'); }
        catch (error) { throw safeFailure(error, '未能核验原账本权限，请重试；本机内容已保留'); }
        scope.assertCurrent(context);
    }
    async function capability(context: Context, recognition = false): Promise<void> {
        const response = await services.getAIReviewCapabilities(context.ledgerId, context.username)
            .catch(error => { throw safeFailure(error, '未能核验服务的 AI 能力，请重试；本机内容已保留'); });
        scope.assertCurrent(context);
        if (!response.data?.success) throw new Error('服务 AI 能力回包不完整，请重试');
        assertAIReviewCapabilities(response.data.result, context.ledgerId, recognition);
    }
    async function verifyCapabilities(ledgerId: string, recognition = false): Promise<void> {
        const context = scope.capture(ledgerId); await authorize(context); await capability(context, recognition);
    }
    async function recognizeText(ledgerId: string, text: string) {
        if (!text.trim()) throw new Error('请先输入需要识别的内容');
        const context = scope.capture(ledgerId); await authorize(context); await capability(context, true);
        const response = await services.recognizeLedgerTransactionText({ ledgerId, text }, context.username)
            .catch(error => { throw safeFailure(error, '文字识别失败，请保留输入后重试或改为手动记账'); });
        scope.assertCurrent(context);
        if (!response.data?.success) throw new Error('文字识别回包不完整，请重试');
        assertAIRecognition(response.data.result, ledgerId);
        return copy(response.data.result);
    }
    async function recognizeImage(ledgerId: string, imageFile: File, cancelableUuid?: string) {
        const context = scope.capture(ledgerId); await authorize(context); await capability(context, true);
        const response = await services.recognizeLedgerReceiptImage({ ledgerId, imageFile, cancelableUuid }, context.username)
            .catch(error => { throw safeFailure(error, '图片识别未完成，请保留原图后重试或改为手动记账'); });
        scope.assertCurrent(context);
        if (!response.data?.success) throw new Error('图片识别回包不完整，请重试');
        assertAIRecognition(response.data.result, ledgerId);
        return copy(response.data.result);
    }
    async function load(ledgerId = ledgers.selectedLedgerId, status = 1): Promise<void> {
        if (![1, 2, 3].includes(status)) throw new Error('待复核状态无效');
        const context = scope.capture(ledgerId), serial = ++loadSerial;
        loading.value = true; loaded.value = false; errorMessage.value = ''; items.value = [];
        try {
            await authorize(context); await capability(context);
            const snapshot: AIReviewLedgerItem[] = [], seen = new Set<string>();
            let beforeId = '0';
            while (true) {
                const response = await services.listLedgerAIReviewItems({ ledgerId, status, beforeId, limit: 100 }, context.username)
                    .catch(error => { throw safeFailure(error, '未能完整读取待复核队列，请重试'); });
                scope.assertCurrent(context);
                if (serial !== loadSerial) return;
                if (!response.data?.success || !Array.isArray(response.data.result) || response.data.result.length > 100) {
                    throw new Error('待复核分页回包不完整，请重试');
                }
                const page = response.data.result;
                let last = beforeId === '0' ? null : BigInt(beforeId);
                for (const item of page) {
                    assertAIReviewItem(item, ledgerId);
                    if (item.deleted || item.status !== status || seen.has(item.id) || (last !== null && BigInt(item.id) >= last)) {
                        throw new Error('待复核分页重复、乱序或状态不匹配，请重新读取');
                    }
                    last = BigInt(item.id); seen.add(item.id); snapshot.push(copy(item));
                }
                if (page.length < 100) break;
                beforeId = page[page.length - 1]!.id;
            }
            scope.assertCurrent(context);
            if (serial === loadSerial) { items.value = snapshot; loaded.value = true; }
        } catch (error) {
            if (serial !== loadSerial) throw Object.assign(new Error('原队列读取已过期，请使用当前账本结果'), { stale: true });
            if (serial === loadSerial) { items.value = []; errorMessage.value = error instanceof Error ? error.message : '未能读取队列，请重试'; }
            throw error;
        } finally { if (serial === loadSerial) loading.value = false; }
    }
    async function get(ledgerId: string, id: string): Promise<AIReviewLedgerItem> {
        const context = scope.capture(ledgerId);
        await authorize(context);
        const response = await services.getLedgerAIReviewItem({ ledgerId, id }, context.username)
            .catch(error => { throw safeFailure(error, '未能读取原待复核记录，请重试'); });
        scope.assertCurrent(context);
        if (!response.data?.success) throw new Error('待复核详情回包不完整，请重试');
        assertAIReviewItem(response.data.result, ledgerId);
        if (response.data.result.id !== id) throw new Error('待复核详情 ID 不匹配，请重新读取');
        return copy(response.data.result);
    }
    function readPendingCreate(ledgerId: string): PendingAIReviewCreate | null {
        const current = identity(), pending = getUserPendingAIReviewCreate(ledgerId);
        if (pending) assertPendingAIReviewCreate(pending, ledgerId, current);
        return pending ? copy(pending) : null;
    }
    function readPendingConfirm(ledgerId: string): PendingAIReviewConfirm | null {
        const current = identity(), pending = getUserPendingAIReviewConfirm(ledgerId);
        if (pending) assertPendingAIReviewConfirm(pending, ledgerId, current);
        return pending ? copy(pending) : null;
    }
    function readDraft(ledgerId: string): AIReviewEditorDraft | null {
        const captured = identity(), draft = getUserAIReviewEditorDraft(ledgerId);
        if (draft) assertAIReviewEditorDraft(draft, ledgerId, captured);
        return draft ? copy(draft) : null;
    }
    async function saveDraft(draft: AIReviewEditorDraft, expectedRevision: string | null): Promise<void> {
        const frozen = copy(draft), context = scope.capture(draft.input.ledgerId);
        assertAIReviewEditorDraft(frozen, context.ledgerId, context.identity);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPendingConfirm(context.ledgerId)) throw new Error('AI 入账结果待确认，请先处理原请求');
            const current = readDraft(context.ledgerId);
            if ((current?.revision ?? null) !== expectedRevision || (current && current.input.id !== frozen.input.id)) {
                throw new Error('另一个页面已更新原草稿，请重新读取后继续编辑');
            }
            updateUserAIReviewEditorDraft(context.ledgerId, frozen);
        });
    }
    async function discardDraft(ledgerId: string, id: string, revision: string): Promise<void> {
        const context = scope.capture(ledgerId);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPendingConfirm(ledgerId)) throw new Error('结果待确认时不能丢弃原请求');
            const draft = readDraft(ledgerId);
            if (draft?.input.id !== id || draft.revision !== revision) throw new Error('原草稿已变化，请重新读取');
            clearUserAIReviewEditorDraft(ledgerId);
        });
    }
    function readSourceDraft(ledgerId: string): AIReviewSourceDraft | null {
        const captured = identity(), draft = getUserAIReviewSourceDraft(ledgerId);
        if (draft) assertAIReviewSourceDraft(draft, ledgerId, captured);
        return draft ? copy(draft) : null;
    }
    async function saveSourceDraft(draft: AIReviewSourceDraft, expectedRevision: string | null): Promise<void> {
        const frozen = copy(draft), context = scope.capture(draft.input.ledgerId);
        assertAIReviewSourceDraft(frozen, context.ledgerId, context.identity);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPendingCreate(context.ledgerId)) throw new Error('原队列保存结果待确认，请先处理原请求');
            if ((readSourceDraft(context.ledgerId)?.revision ?? null) !== expectedRevision) throw new Error('另一页面已更新来源草稿，请恢复原内容后继续');
            updateUserAIReviewSourceDraft(context.ledgerId, frozen);
        });
    }
    async function discardSourceDraft(ledgerId: string, revision: string): Promise<void> {
        const context = scope.capture(ledgerId); let image: AIReviewSourceImage | undefined;
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPendingCreate(ledgerId)) throw new Error('保存结果待确认时不能丢弃原请求');
            const draft = readSourceDraft(ledgerId);
            if (draft?.revision !== revision) throw new Error('来源草稿已变化，请重新读取');
            image = draft.input.image; clearUserAIReviewSourceDraft(ledgerId);
        });
        if (image) await removeAIReviewSourceImage(ledgerId, image).catch(() => {});
    }
    async function create(request: LedgerAIReviewCreateRequest, draftRevision?: string | null): Promise<PendingAIReviewCreate> {
        const frozen = copy(request), context = scope.capture(request.ledgerId);
        assertAIReviewCreate(frozen, context.ledgerId); await authorize(context, true);
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPendingCreate(context.ledgerId)) throw new Error('已有待复核创建结果需要核对，请重试原请求');
            if (draftRevision !== undefined) {
                const draft = readSourceDraft(context.ledgerId);
                if ((draft?.revision ?? null) !== draftRevision) throw new Error('来源草稿已变化，请恢复原内容后重新预览');
                if (draft && (frozen.sourceType !== draft.input.sourceType || frozen.sourceText !== draft.input.sourceText
                    || JSON.stringify(recognitionContent(frozen.recognizedData)) !== JSON.stringify(recognitionContent(draft.recognizedData)) || frozen.failureReason !== draft.failureReason)) throw new Error('原来源与保存快照不匹配，请重新预览');
            }
            updateUserPendingAIReviewCreate(context.ledgerId, { version: 1, identity: context.identity, request: frozen, draftRevision });
        });
        return retryCreate(context.ledgerId, frozen.clientSessionId);
    }
    async function retryCreate(ledgerId: string, clientSessionId: string): Promise<PendingAIReviewCreate> {
        const context = scope.capture(ledgerId), pending = readPendingCreate(ledgerId);
        if (!pending || pending.request.clientSessionId !== clientSessionId) throw new Error('原创建请求已变化，请重新读取');
        if (pending.receipt) return pending;
        await authorize(context, true); await capability(context);
        const response = await services.createLedgerAIReviewItem(copy(pending.request), context.username)
            .catch(error => { throw safeFailure(error, '待复核创建结果尚未确认，请重试原请求'); });
        assertIdentity(context);
        if (!response.data?.success) throw new Error('待复核创建回包不完整，请重试原请求');
        const result = response.data.result; assertAIReviewItem(result, ledgerId);
        if (result.sourceType !== pending.request.sourceType || result.sourceText !== trimAIReviewSource(pending.request.sourceText)
            || result.failureReason !== trimAIReviewSource(pending.request.failureReason)
            || JSON.stringify(recognitionContent(result.recognizedData)) !== JSON.stringify(recognitionContent(pending.request.recognizedData))) {
            throw new Error('创建回包内容不匹配，请保留原请求并核对');
        }
        let receipt = { id: result.id, deleted: result.deleted };
        await withTransactionCreateLock(() => {
            assertIdentity(context);
            const current = readPendingCreate(ledgerId);
            if (!current || current.request.clientSessionId !== clientSessionId) return;
            if (current.receipt && current.receipt.id !== receipt.id) throw new Error('原创建回执不一致，请保留内容后核对');
            receipt = { ...receipt, deleted: receipt.deleted || current.receipt?.deleted === true };
            updateUserPendingAIReviewCreate(ledgerId, { ...current, receipt });
        });
        return { ...pending, receipt };
    }
    async function confirm(request: AIReviewConfirmRequest, draftRevision?: string | null): Promise<PendingAIReviewConfirm> {
        const frozen = copy(request), context = scope.capture(request.ledgerId);
        assertAIReviewConfirm(frozen, context.ledgerId);
        if (readPendingConfirm(context.ledgerId)) throw new Error('已有 AI 入账结果需要核对，请重试原请求');
        await authorize(context, true);
        const item = await get(context.ledgerId, frozen.id);
        if (!item.editable || item.deleted || item.status !== 1) throw new Error('原条目当前不可确认，请重新读取状态和权限');
        await withTransactionCreateLock(() => {
            scope.assertCurrent(context);
            if (readPendingConfirm(context.ledgerId)) throw new Error('已有 AI 入账结果需要核对，请重试原请求');
            if (draftRevision !== undefined) {
                const draft = readDraft(context.ledgerId);
                if ((draft?.revision ?? null) !== draftRevision || (draft && draft.input.id !== frozen.id)) throw new Error('确认期间原草稿已变化，请重新读取并预览');
            }
            updateUserPendingAIReviewConfirm(context.ledgerId, { version: 1, identity: context.identity, request: frozen, draftRevision });
        });
        return retryConfirm(context.ledgerId, frozen.id, frozen.transaction.clientSessionId);
    }
    async function retryConfirm(ledgerId: string, id: string, clientSessionId: string): Promise<PendingAIReviewConfirm> {
        const context = scope.capture(ledgerId), pending = readPendingConfirm(ledgerId);
        if (!pending || pending.request.id !== id || pending.request.transaction.clientSessionId !== clientSessionId) {
            throw new Error('原确认请求已变化，请重新读取');
        }
        if (pending.receipt) return pending;
        await authorize(context, true); await capability(context);
        const response = await services.confirmLedgerAIReviewItem(copy(pending.request), context.username)
            .catch(error => { throw safeFailure(error, 'AI 入账结果尚未确认，请重试原请求'); });
        assertIdentity(context);
        if (!response.data?.success) throw new Error('AI 确认回包不完整，请重试原请求');
        const receipt = response.data.result; assertAIReviewReceipt(receipt, pending.request);
        await withTransactionCreateLock(() => {
            assertIdentity(context);
            const current = readPendingConfirm(ledgerId);
            if (!current || current.request.id !== id || current.request.transaction.clientSessionId !== clientSessionId) return;
            if (current.receipt && current.receipt.transactionId !== receipt.transactionId) throw new Error('原流水回执不一致，请保留内容后核对');
            updateUserPendingAIReviewConfirm(ledgerId, { ...current, receipt: copy(receipt) });
        });
        // A ledger switch may hide the completed result but must not lose its
        // original receipt. No mutation of the next ledger's displayed rows.
        if (ledgerId === ledgers.selectedLedgerId) { items.value = items.value.filter(item => item.id !== id); loadSerial++; loading.value = false; loaded.value = false; }
        return { ...pending, receipt: copy(receipt) };
    }
    async function acknowledgeCreate(ledgerId: string, key: string, id: string): Promise<void> {
        const captured = identity(); let image: AIReviewSourceImage | undefined;
        await withTransactionCreateLock(() => {
            if (captured !== identity()) throw new Error('账号已变化，请重新读取');
            const pending = readPendingCreate(ledgerId);
            if (pending?.request.clientSessionId === key && pending.receipt?.id === id) {
                const draft = readSourceDraft(ledgerId);
                if (pending.draftRevision && draft?.revision === pending.draftRevision) { image = draft.input.image; clearUserAIReviewSourceDraft(ledgerId); }
                clearUserPendingAIReviewCreate(ledgerId);
            }
        });
        if (image) await removeAIReviewSourceImage(ledgerId, image).catch(() => {});
    }
    async function acknowledgeConfirm(ledgerId: string, id: string, key: string, transactionId: string): Promise<void> {
        const captured = identity();
        await withTransactionCreateLock(() => {
            if (captured !== identity()) throw new Error('账号已变化，请重新读取');
            const pending = readPendingConfirm(ledgerId);
            if (pending?.request.id === id && pending.request.transaction.clientSessionId === key && pending.receipt?.transactionId === transactionId) {
                const draft = readDraft(ledgerId);
                if (pending.draftRevision && draft?.revision === pending.draftRevision && draft.input.id === id) clearUserAIReviewEditorDraft(ledgerId);
                clearUserPendingAIReviewConfirm(ledgerId);
            }
        });
    }
    async function change(action: 'dismiss' | 'restore' | 'delete', ledgerId: string, id: string): Promise<void> {
        const context = scope.capture(ledgerId); await authorize(context, true);
        if (readPendingConfirm(ledgerId)?.request.id === id) throw new Error('原入账结果待核对，不能忽略、恢复或删除');
        const item = await get(ledgerId, id);
        if (!item.editable || item.deleted || (action === 'dismiss' && item.status !== 1) || (action === 'restore' && item.status !== 3)) {
            throw new Error('条目当前不可操作，请重新读取状态和权限');
        }
        await capability(context);
        const response = await services.changeLedgerAIReviewItem(action, { ledgerId, id }, context.username)
            .catch(error => { throw safeFailure(error, '未能确认状态操作，请重新读取原条目'); });
        scope.assertCurrent(context);
        if (!response.data?.success || response.data.result !== true) throw new Error('状态操作回包不完整，请重新读取原条目');
        // The prior snapshot is stale even when a background load was in flight.
        loadSerial++; items.value = []; loading.value = false; loaded.value = false;
    }
    return { items, loading, loaded, errorMessage, loadedLedgerId: scope.loadedLedgerId, canWrite: scope.canReview,
        load, get, verifyCapabilities, recognizeText, recognizeImage, readDraft, saveDraft, discardDraft, readSourceDraft, saveSourceDraft, discardSourceDraft, readPendingCreate, readPendingConfirm, create, retryCreate, confirm, retryConfirm,
        acknowledgeCreate, acknowledgeConfirm, change, reset: scope.reset };
});
