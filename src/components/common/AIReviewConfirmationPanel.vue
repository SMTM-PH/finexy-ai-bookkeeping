<template>
    <section v-if="active" ref="panel" class="ai-review-confirmation-panel" aria-label="AI 编辑草稿与入账恢复">
        <p class="ai-review-confirmation-status" role="status" aria-live="polite">{{ status }} · {{ ledgerName }}</p>
        <p v-if="error" role="alert">{{ error }}</p>
        <button v-if="error && !pending && !previewRequest" type="button" :disabled="busy" @click="initialize">重新核验本机状态</button>
        <template v-if="pending && pending.request.id === itemId">
            <p>{{ pending.receipt ? `原流水 ID：${pending.receipt.transactionId}` : '已保留原入账请求；编辑、忽略和替代入账已锁定。' }}</p>
            <p>{{ summary(pending.request) }}</p>
            <button type="button" :disabled="busy || !ready || (!pending.receipt && !canWrite)" @click="retry">{{ pending.receipt ? '重新读取原结果' : '重试原请求' }}</button>
        </template>
        <template v-else-if="recoveryDraft && recoveryDraft.input.id === itemId">
            <p>本机有原条目的已保存输入。继续编辑不会上传；丢弃只移除本机草稿。</p>
            <div class="ai-review-confirmation-actions">
                <button type="button" :disabled="busy || !canWrite" @click="resume">继续编辑已保存草稿</button>
                <button type="button" :disabled="busy || !canWrite" @click="discardRequested = true">丢弃本机草稿</button>
            </div>
            <div v-if="discardRequested" role="group" aria-label="确认丢弃 AI 草稿">
                <p>丢弃这份未确认输入？待复核原条目和账户余额保留。</p>
                <button type="button" :disabled="busy" @click="discard">确认丢弃</button>
                <button type="button" :disabled="busy" @click="discardRequested = false">继续保留</button>
            </div>
        </template>
        <template v-else-if="previewRequest">
            <p>确认入账到 {{ ledgerName }} · {{ typeName(previewRequest.transaction.type) }}</p>
            <p>{{ summary(previewRequest) }}</p>
            <p>{{ new Date((previewRequest.transaction.time + previewRequest.transaction.utcOffset * 60) * 1000).toISOString().slice(0, 19).replace('T', ' ') }} · UTC 偏移 {{ previewRequest.transaction.utcOffset }} 分钟</p>
            <p>确认后生成一笔流水，并将原条目标记为已确认。请核对账户、分类与资金方向。</p>
            <div class="ai-review-confirmation-actions">
                <button type="button" :disabled="busy || !canWrite" @click="confirm">确认入账</button>
                <button type="button" :disabled="busy" @click="previewRequest = null">继续编辑</button>
            </div>
        </template>
    </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useAIReviewLedgerStore } from '@/stores/aiReviewLedger.ts';
import { useAccountsStore } from '@/stores/account.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useI18n } from '@/locales/helpers.ts';
import { getTransactionStateIdentity } from '@/lib/userstate.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import { templateEditorAmount } from '@/lib/template_editor.ts';
import { assertAIReviewConfirm, type AIReviewConfirmRequest, type AIReviewEditorInput, type AIReviewEditorDraft, type PendingAIReviewConfirm } from '@/models/ai_review_ledger.ts';
import { TransactionType } from '@/core/transaction.ts';
import type { Transaction } from '@/models/transaction.ts';

const props = defineProps<{ active: boolean; ready: boolean; ledgerId: string; itemId: string; transaction: Transaction; canWrite: boolean;
    rawSourceAmount?: string; rawDestinationAmount?: string; transferDirection: 'internal' | 'out' | 'in'; validate: () => string | null }>();
const emit = defineEmits<{ (e: 'restore', value: AIReviewEditorInput): void; (e: 'restoreRequest', value: AIReviewConfirmRequest): void;
    (e: 'locked', value: boolean): void; (e: 'complete', value: { transactionId: string }): void }>();
const store = useAIReviewLedgerStore(), accounts = useAccountsStore(), ledgers = useLedgersStore(), settings = useSettingsStore();
const { parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const panel = ref<HTMLElement>();
const pending = ref<PendingAIReviewConfirm | null>(null), recoveryDraft = ref<AIReviewEditorDraft | null>(null), previewRequest = ref<AIReviewConfirmRequest | null>(null);
const busy = ref(false), saving = ref(false), error = ref(''), validationError = ref(false), discardRequested = ref(false), initialized = ref(false), removedElsewhere = ref(false);
let identity: string | null = null, generation = 0, revision: string | null = null, saveSerial = 0;
let jobs: Promise<void> = Promise.resolve();
const current = () => props.active && props.ready && identity === getTransactionStateIdentity() && props.ledgerId === ledgers.selectedLedgerId;
const ledgerName = computed(() => props.ledgerId === ledgers.selectedLedgerId ? ledgers.selectedLedger.name : '原账本');
const locked = computed(() => !initialized.value || (!!error.value && !validationError.value) || busy.value || !!pending.value || !!recoveryDraft.value || !!previewRequest.value || removedElsewhere.value || !props.canWrite || props.ledgerId !== ledgers.selectedLedgerId);
const status = computed(() => pending.value?.receipt ? '已确认入账，只读取原结果' : pending.value ? '入账结果待确认，请勿另建' : removedElsewhere.value ? '原内容已被另一页面处理，请返回列表核对' : saving.value ? '正在保存本机草稿' : recoveryDraft.value ? '可恢复本机草稿' : initialized.value ? (revision ? '原始输入已保存到本机；确认前不会入账' : '开始编辑后保存本机草稿；确认前不会入账') : '正在核验本机 AI 状态');
watch(locked, value => emit('locked', value), { immediate: true });
const fail = (reason: unknown) => { error.value = reason instanceof Error ? reason.message.replace('确认模板', '确认入账') : '操作未完成，原内容已保留，请重试'; };
const typeName = (type: number) => type === TransactionType.Transfer ? '转账' : type === TransactionType.Income ? '收入' : '支出（负金额为退款）';
function summary(request: AIReviewConfirmRequest): string {
    const tx = request.transaction;
    if (tx.hideAmount || !settings.appSettings.showAmountInHomePage) return '金额已隐藏';
    if (accounts.accountListLedgerId !== request.ledgerId) return '原账户及币种待核对';
    const side = (id: string, amount: number) => id === '0' ? '系统外' : accounts.allAccountsMap[id]
        ? `${accounts.allAccountsMap[id]!.name} · ${formatAmountToLocalizedNumeralsWithCurrency(amount, accounts.allAccountsMap[id]!.currency)}` : '账户及币种待核对';
    const source = side(tx.sourceAccountId, tx.sourceAmount);
    return tx.type === TransactionType.Transfer ? `${source} → ${side(tx.destinationAccountId, tx.destinationAmount)}` : source;
}
function snapshot(): AIReviewEditorInput {
    const draft = props.transaction.toTransactionDraft();
    if (!draft) throw new Error('AI 入账不支持余额调整，请重新核对类型');
    return { ledgerId: props.ledgerId, id: props.itemId,
        transaction: { ...draft, ledgerId: props.ledgerId, destinationAccountId: props.transaction.destinationAccountId, destinationAmount: props.transaction.destinationAmount, pictures: draft.pictures ?? [] },
        rawSourceAmount: props.rawSourceAmount, rawDestinationAmount: props.rawDestinationAmount,
        categorySelections: { expense: props.transaction.expenseCategoryId, income: props.transaction.incomeCategoryId, transfer: props.transaction.transferCategoryId }, transferDirection: props.transferDirection };
}
async function initialize(): Promise<void> {
    const serial = ++generation;
    initialized.value = false; busy.value = false; saving.value = false; error.value = ''; validationError.value = false; pending.value = null; recoveryDraft.value = null; previewRequest.value = null; removedElsewhere.value = false; revision = null;
    identity = getTransactionStateIdentity();
    if (!props.active || !props.ready) return;
    try {
        pending.value = store.readPendingConfirm(props.ledgerId);
        if (pending.value) {
            if (pending.value.request.id !== props.itemId) throw new Error('原账本有另一条入账结果待核对，请关闭并使用列表的恢复入口');
            emit('restoreRequest', pending.value.request);
        } else {
            recoveryDraft.value = store.readDraft(props.ledgerId);
            if (recoveryDraft.value && recoveryDraft.value.input.id !== props.itemId) throw new Error('原账本有另一条已保存草稿，请关闭并使用列表的恢复入口');
        }
        await nextTick();
        if (serial === generation && current()) initialized.value = true;
    } catch (reason) { if (serial === generation) fail(reason); }
}
watch(() => [props.active, props.ready, props.ledgerId, props.itemId], () => { void initialize(); }, { immediate: true });
function queueSave(): void {
    const serial = generation, ticket = ++saveSerial, input = snapshot(); saving.value = true; error.value = ''; validationError.value = false;
    jobs = jobs.then(async () => {
        if (serial !== generation || !current()) return;
        const next: AIReviewEditorDraft = { version: 1, identity: identity!, revision: generateRandomUUID(), input };
        await store.saveDraft(next, revision);
        if (serial === generation && current()) revision = next.revision;
    }).catch(reason => { if (serial === generation) fail(reason); }).finally(() => { if (serial === generation && ticket === saveSerial) saving.value = false; });
}
watch(() => [props.transaction, props.rawSourceAmount, props.rawDestinationAmount, props.transferDirection], () => {
    if (initialized.value && current() && !locked.value) { try { queueSave(); } catch (reason) { fail(reason); } }
}, { deep: true, flush: 'post' });
async function flush(persistInitial = true): Promise<void> {
    await nextTick(); await jobs;
    if (pending.value || recoveryDraft.value || removedElsewhere.value) return;
    if (persistInitial && initialized.value && current() && !locked.value && !revision) { queueSave(); await jobs; }
    if (error.value) throw new Error(error.value);
}
async function resume(): Promise<void> {
    if (!recoveryDraft.value || !current() || !props.canWrite) return;
    const serial = generation, draft = recoveryDraft.value;
    initialized.value = false; revision = draft.revision; error.value = ''; recoveryDraft.value = null;
    emit('restore', draft.input); await nextTick();
    if (serial === generation && current()) initialized.value = true;
}
async function discard(): Promise<void> {
    if (!recoveryDraft.value || !current() || !props.canWrite) return;
    const serial = generation; busy.value = true;
    try { await store.discardDraft(props.ledgerId, props.itemId, recoveryDraft.value.revision); if (serial !== generation || !current()) return; recoveryDraft.value = null; revision = null; discardRequested.value = false; error.value = ''; }
    catch (reason) { if (serial === generation && current()) fail(reason); } finally { if (serial === generation) busy.value = false; }
}
async function preview(): Promise<void> {
    const serial = generation;
    validationError.value = false; error.value = '';
    try {
        await flush(); if (serial !== generation || !current()) return;
        if (locked.value) throw new Error('请先处理原草稿或核验当前权限');
        validationError.value = true;
        const problem = props.validate(); if (problem) throw new Error(problem);
        const input = snapshot(), tx = props.transaction.toCreateRequest(generateRandomUUID(), props.ledgerId);
        const request: AIReviewConfirmRequest = { ledgerId: props.ledgerId, id: props.itemId, transaction: { ...tx, ledgerId: props.ledgerId,
            destinationAccountId: tx.destinationAccountId || '0',
            sourceAmount: props.transferDirection === 'in' && tx.type === TransactionType.Transfer ? 0 : templateEditorAmount(input.rawSourceAmount ?? tx.sourceAmount, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals),
            destinationAmount: tx.type === TransactionType.Transfer && props.transferDirection !== 'out' ? templateEditorAmount(input.rawDestinationAmount ?? tx.destinationAmount, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals) : 0 } };
        assertAIReviewConfirm(request, props.ledgerId); previewRequest.value = request; validationError.value = false;
        await nextTick(); panel.value?.scrollIntoView({ block: 'nearest' });
    } catch (reason) { if (serial === generation && current()) fail(reason); }
}
async function finish(serial: number): Promise<void> {
    const original = pending.value;
    if (!original?.receipt) return;
    const item = await store.get(original.request.ledgerId, original.request.id);
    if (item.status !== 2 || item.transactionId !== original.receipt.transactionId) throw new Error('原确认结果尚未完整读取，请重新读取；不会再次入账');
    if (serial !== generation || !current()) return;
    await store.acknowledgeConfirm(original.request.ledgerId, original.request.id, original.request.transaction.clientSessionId, original.receipt.transactionId);
    if (serial === generation && current()) emit('complete', { transactionId: original.receipt.transactionId });
}
async function confirm(): Promise<void> {
    const request = previewRequest.value; if (!request || busy.value || !current() || !props.canWrite) return;
    const serial = generation; busy.value = true; error.value = '';
    try { const result = await store.confirm(request, revision); if (serial !== generation || !current()) return; pending.value = result; previewRequest.value = null; await finish(serial); }
    catch (reason) { if (serial === generation && current()) { try { pending.value = store.readPendingConfirm(props.ledgerId); } catch (failure) { fail(failure); return; } if (pending.value) previewRequest.value = null; fail(reason); } }
    finally { if (serial === generation) busy.value = false; }
}
async function retry(): Promise<void> {
    if (!pending.value || pending.value.request.id !== props.itemId || busy.value || !current()) return;
    const serial = generation; busy.value = true; error.value = '';
    try { const result = await store.retryConfirm(props.ledgerId, props.itemId, pending.value.request.transaction.clientSessionId); if (serial !== generation || !current()) return; pending.value = result; await finish(serial); }
    catch (reason) { if (serial === generation && current()) fail(reason); } finally { if (serial === generation) busy.value = false; }
}
function changedElsewhere(event: StorageEvent): void {
    if (!current() || (event.key !== null && (!event.key.startsWith(identity!) || !event.key.endsWith(`:${props.ledgerId}`) || !event.key.includes('ai-review-')))) return;
    try {
        const original = pending.value; pending.value = store.readPendingConfirm(props.ledgerId);
        if (pending.value) { previewRequest.value = null; if (pending.value.request.id === props.itemId) emit('restoreRequest', pending.value.request); else fail(new Error('另一条入账结果待确认，请返回列表恢复原请求')); }
        else if (original) removedElsewhere.value = true;
        else {
            const draft = store.readDraft(props.ledgerId);
            if (draft && draft.revision !== revision) { previewRequest.value = null; recoveryDraft.value = draft; if (draft.input.id !== props.itemId) fail(new Error('另一条草稿已保存，请返回列表恢复原草稿')); }
            else if (!draft && revision) removedElsewhere.value = true;
        }
    } catch (reason) { fail(reason); }
}
onMounted(() => window.addEventListener('storage', changedElsewhere));
onUnmounted(() => { generation++; window.removeEventListener('storage', changedElsewhere); });
defineExpose({ preview, flush });
</script>

<style scoped>
.ai-review-confirmation-panel { margin: 12px 0; min-height: 48px; font-size: var(--f7-font-size, 14px); line-height: 1.5; overflow-wrap: anywhere; }
.ai-review-confirmation-status { min-height: 3em; }
.ai-review-confirmation-actions { position: sticky; bottom: 0; display: flex; flex-wrap: wrap; gap: 8px; padding: 8px 0; background: var(--finexy-dialog-panel, rgb(var(--v-theme-surface, 255, 255, 255))); }
.ai-review-confirmation-panel button { min-height: 44px; padding: 8px 16px; border: 1px solid currentColor; border-radius: 12px; color: inherit; background: transparent; font: inherit; cursor: pointer; }
.ai-review-confirmation-panel button:disabled { opacity: .55; cursor: default; }
.ai-review-confirmation-panel button:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
</style>
