<template>
    <section v-if="active" ref="panel" class="template-creation-panel" aria-label="模板草稿与创建恢复">
        <p class="template-creation-status" role="status" aria-live="polite">{{ status }} · {{ ledgerName }}</p>
        <p v-if="error" role="alert">{{ error }}</p>
        <button v-if="error && !pending && !previewRequest" type="button" :disabled="busy" @click="initialize">重新核验本机状态</button>
        <template v-if="pending">
            <p>{{ pending.request.name }} · {{ pending.receipt ? `原模板 ID：${pending.receipt.id}` : '已保留原确认请求' }}</p>
            <p v-if="!pending.receipt">{{ summary(pending.request) }}</p>
            <div class="template-creation-actions">
                <button type="button" :disabled="busy || !ready || (!pending.receipt && !canManage)" @click="retry">{{ pending.receipt ? '重新读取原模板' : '重试原请求' }}</button>
            </div>
        </template>
        <template v-else-if="recoveryDraft">
            <p>本机有已保存输入。继续编辑不会上传；丢弃只移除本机草稿。</p>
            <div class="template-creation-actions">
                <button type="button" :disabled="busy || !canManage" @click="resume">继续编辑已保存草稿</button>
                <button type="button" :disabled="busy || !canManage" @click="discardRequested = true">丢弃本机草稿</button>
            </div>
            <div v-if="discardRequested" role="group" aria-label="确认丢弃草稿">
                <p>丢弃这份未确认输入？已有模板、流水和余额保留。</p>
                <button type="button" :disabled="busy" @click="discard">确认丢弃</button>
                <button type="button" :disabled="busy" @click="discardRequested = false">继续保留</button>
            </div>
        </template>
        <template v-else-if="previewRequest">
            <p>确认在 {{ ledgerName }} 创建{{ previewRequest.templateType === 2 ? '周期计划' : '普通模板' }}：{{ previewRequest.name }}</p>
            <p>{{ summary(previewRequest) }}</p>
            <p v-if="previewRequest.templateType === 2">规则 {{ previewRequest.scheduledFrequency }} · UTC {{ previewRequest.utcOffset }} 分钟 · {{ previewRequest.scheduledStartDate || '未设置开始日期' }}{{ previewRequest.scheduledEndDate ? ` 至 ${previewRequest.scheduledEndDate}` : '' }}。到期只生成待确认记录。</p>
            <div class="template-creation-actions">
                <button type="button" :disabled="busy || !canManage" @click="confirm">确认创建模板</button>
                <button type="button" :disabled="busy" @click="previewRequest = null">继续编辑</button>
            </div>
        </template>
    </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useTemplateCreationStore } from '@/stores/templateCreation.ts';
import { useTransactionTemplatesStore } from '@/stores/transactionTemplate.ts';
import { useAccountsStore } from '@/stores/account.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useI18n } from '@/locales/helpers.ts';
import { getTransactionStateIdentity } from '@/lib/userstate.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import { templateEditorAmount } from '@/lib/template_editor.ts';
import { assertTemplateCreateRequest, type PendingTemplateCreate, type TemplateCreationType, type TemplateEditorDraft, type TemplateEditorInput } from '@/models/template_creation.ts';
import { TransactionTemplate, type TransactionTemplateCreateRequest } from '@/models/transaction_template.ts';
import { TransactionType } from '@/core/transaction.ts';
import type { Transaction } from '@/models/transaction.ts';
import { assertTemplateRecoveryLocator, type TemplateRecoveryTarget } from '@/lib/template_recovery_locator.ts';

const props = defineProps<{ active: boolean; ready: boolean; ledgerId: string; template: Transaction; canManage: boolean;
    rawSourceAmount?: string; rawDestinationAmount?: string; validate: () => string | null; expectedRecoveryTarget?: TemplateRecoveryTarget;
    captureActionCurrent?: () => () => boolean }>();
const emit = defineEmits<{ (e: 'restore', value: TemplateEditorInput): void; (e: 'locked', value: boolean): void;
    (e: 'complete', value: { deleted: boolean; id: string }): void }>();
const store = useTemplateCreationStore(), templates = useTransactionTemplatesStore(), accounts = useAccountsStore(), ledgers = useLedgersStore(), settings = useSettingsStore();
const { parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const panel = ref<HTMLElement>();
const pending = ref<PendingTemplateCreate | null>(null), recoveryDraft = ref<TemplateEditorDraft | null>(null);
const previewRequest = ref<TransactionTemplateCreateRequest | null>(null);
const busy = ref(false), saving = ref(false), error = ref(''), discardRequested = ref(false), initialized = ref(false), removedElsewhere = ref(false);
let identity: string | null = null, generation = 0, revision: string | null = null, saveSerial = 0;
let jobs: Promise<void> = Promise.resolve();
let locatorReleased = false, pinnedPending: PendingTemplateCreate | null = null;
let recoveryTarget = props.expectedRecoveryTarget, recoveryLedgerId = props.ledgerId;
const ledgerName = computed(() => props.ledgerId === ledgers.selectedLedgerId ? ledgers.selectedLedger.name : '原账本');
const locked = computed(() => busy.value || !!pending.value || !!recoveryDraft.value || !!previewRequest.value || removedElsewhere.value);
const status = computed(() => pending.value?.receipt ? '已确认创建，只读取原结果' : pending.value ? '创建结果待确认，请勿另建' : removedElsewhere.value ? '原请求已被另一页面处理或移除，请返回列表核对' : saving.value ? '正在保存本机草稿' : recoveryDraft.value ? '可恢复本机草稿' : initialized.value ? (revision ? '原始输入已保存到本机；确认前不会上传' : '开始输入后保存本机草稿；确认前不会上传') : '正在核验本机模板状态');
watch(locked, value => emit('locked', value), { immediate: true });
const current = () => props.active && identity === getTransactionStateIdentity() && props.ledgerId === ledgers.selectedLedgerId;
const fail = (reason: unknown) => { error.value = reason instanceof Error ? reason.message : '模板操作失败，原记录已保留，请重试'; };
function rejectReplacement(reason: unknown): void {
    generation++;
    initialized.value = false; pending.value = null; recoveryDraft.value = null; previewRequest.value = null;
    removedElsewhere.value = true; busy.value = false; saving.value = false; discardRequested.value = false;
    fail(reason);
}
function guardRecovery(nextPending: PendingTemplateCreate | null, nextDraft: TemplateEditorDraft | null): void {
    if (!(props.template instanceof TransactionTemplate)) throw new Error('模板输入尚未就绪');
    assertTemplateRecoveryLocator(locatorReleased ? undefined : props.expectedRecoveryTarget, props.ledgerId, props.template.templateType, nextPending, nextDraft, pinnedPending);
}
function pinPending(value: PendingTemplateCreate): void { pinnedPending = JSON.parse(JSON.stringify(value)) as PendingTemplateCreate; }
function summary(request: TransactionTemplateCreateRequest): string {
    if (request.hideAmount || !settings.appSettings.showAmountInHomePage) return '金额已隐藏';
    const account = accounts.accountListLedgerId === props.ledgerId ? accounts.allAccountsMap[request.sourceAccountId] : undefined;
    if (!account) return '账户或币种待核对';
    let value = `${account.name} · ${formatAmountToLocalizedNumeralsWithCurrency(request.sourceAmount, account.currency)}`;
    if (request.type === TransactionType.Transfer) {
        const destination = accounts.allAccountsMap[request.destinationAccountId];
        value += destination ? ` → ${destination.name} · ${formatAmountToLocalizedNumeralsWithCurrency(request.destinationAmount, destination.currency)}` : ' → 转入账户待核对';
    }
    return value;
}
function snapshot(): TemplateEditorInput {
    if (!(props.template instanceof TransactionTemplate)) throw new Error('模板输入尚未就绪');
    const { clientSessionId: _key, ...request } = props.template.toTemplateCreateRequest('');
    void _key;
    return { ...request, ledgerId: props.ledgerId, utcOffset: props.template.utcOffset,
        sourceAmount: props.rawSourceAmount ?? props.template.sourceAmount,
        destinationAmount: props.rawDestinationAmount ?? props.template.destinationAmount,
        categorySelections: { expense: props.template.expenseCategoryId, income: props.template.incomeCategoryId, transfer: props.template.transferCategoryId } };
}
async function initialize(): Promise<void> {
    const serial = ++generation;
    if (recoveryTarget !== props.expectedRecoveryTarget || recoveryLedgerId !== props.ledgerId) {
        locatorReleased = false; pinnedPending = null;
        recoveryTarget = props.expectedRecoveryTarget; recoveryLedgerId = props.ledgerId;
    }
    initialized.value = false; busy.value = false; saving.value = false; error.value = ''; pending.value = null; recoveryDraft.value = null; previewRequest.value = null; removedElsewhere.value = false; revision = null;
    identity = getTransactionStateIdentity();
    if (!props.active || !props.ready || !(props.template instanceof TransactionTemplate)) { pinnedPending = null; return; }
    try {
        const nextPending = store.readPending(props.ledgerId);
        const nextDraft = nextPending ? null : store.readDraft(props.ledgerId, props.template.templateType as TemplateCreationType);
        guardRecovery(nextPending, nextDraft);
        pending.value = nextPending; recoveryDraft.value = nextDraft;
        if (nextPending) { pinPending(nextPending); emit('restore', nextPending.request); }
        await nextTick();
        if (serial === generation && current()) initialized.value = true;
    } catch (reason) { rejectReplacement(reason); }
}
watch(() => [props.active, props.ready, props.ledgerId, props.expectedRecoveryTarget], () => { void initialize(); }, { immediate: true });
watch(() => [props.template, props.rawSourceAmount, props.rawDestinationAmount], () => {
    if (!initialized.value || !current() || locked.value || !props.ready) return;
    queueSave();
}, { deep: true, flush: 'post' });
function queueSave(): void {
    const serial = generation, ticket = ++saveSerial, input = snapshot(); saving.value = true; error.value = '';
    jobs = jobs.then(async () => {
        if (serial !== generation || !current()) return;
        const next = { version: 1 as const, revision: generateRandomUUID(), input };
        await store.saveDraft(next, revision);
        if (serial === generation && current()) revision = next.revision;
    }).catch(reason => { if (serial === generation) fail(reason); }).finally(() => { if (serial === generation && ticket === saveSerial) saving.value = false; });
}
async function flush(): Promise<void> {
    await nextTick(); await jobs;
    if (pending.value || recoveryDraft.value || removedElsewhere.value) return;
    if (initialized.value && current() && !locked.value && (!revision || error.value)) { queueSave(); await jobs; }
    if (error.value) throw new Error(error.value);
}
async function resume(): Promise<void> {
    if (!recoveryDraft.value || !current() || !props.canManage) return;
    try {
        const nextPending = store.readPending(props.ledgerId), nextDraft = store.readDraft(props.ledgerId, recoveryDraft.value.input.templateType as TemplateCreationType);
        guardRecovery(nextPending, nextDraft);
        if (nextPending || !nextDraft || nextDraft.revision !== recoveryDraft.value.revision) throw new Error('原草稿已变化，请返回待处理列表刷新');
    } catch (reason) { rejectReplacement(reason); return; }
    const serial = generation;
    const draft = recoveryDraft.value; initialized.value = false; revision = draft.revision; error.value = ''; recoveryDraft.value = null;
    locatorReleased = true;
    emit('restore', draft.input); await nextTick();
    if (serial === generation && current()) initialized.value = true;
}
async function discard(): Promise<void> {
    if (!recoveryDraft.value || !current() || !props.canManage) return;
    const serial = generation;
    busy.value = true;
    try {
        const nextPending = store.readPending(props.ledgerId), nextDraft = store.readDraft(props.ledgerId, recoveryDraft.value.input.templateType as TemplateCreationType);
        guardRecovery(nextPending, nextDraft);
        if (nextPending || !nextDraft || nextDraft.revision !== recoveryDraft.value.revision) { rejectReplacement(new Error('原草稿已变化，请返回待处理列表刷新')); return; }
        await store.discardDraft(props.ledgerId, recoveryDraft.value.input.templateType as TemplateCreationType, recoveryDraft.value.revision);
        if (serial !== generation || !current()) return;
        recoveryDraft.value = null; revision = null; discardRequested.value = false; error.value = ''; locatorReleased = true;
    }
    catch (reason) { if (serial === generation && current()) rejectReplacement(reason); } finally { if (serial === generation) busy.value = false; }
}
async function preview(): Promise<void> {
    try {
        await flush(); if (!current() || locked.value || !props.canManage) throw new Error('请先处理原草稿或核验当前管理权限');
        const problem = props.validate(); if (problem) throw new Error(problem);
        const input = snapshot();
        const request = props.template instanceof TransactionTemplate ? props.template.toTemplateCreateRequest(generateRandomUUID()) : null;
        if (!request) throw new Error('模板输入尚未就绪');
        const completed = { ...request, sourceAmount: templateEditorAmount(input.sourceAmount, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals),
            destinationAmount: request.type === TransactionType.Transfer ? templateEditorAmount(input.destinationAmount, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals) : 0 };
        assertTemplateCreateRequest(completed, props.ledgerId); previewRequest.value = completed;
        await nextTick(); panel.value?.scrollIntoView({ block: 'nearest' });
    } catch (reason) { fail(reason); }
}
async function finish(serial = generation, isActionCurrent?: () => boolean): Promise<void> {
    const original = pending.value;
    if (!original?.receipt || isActionCurrent && !isActionCurrent()) return;
    const rows = await templates.loadAllTemplates({ templateType: original.request.templateType, ledgerId: original.request.ledgerId, force: true }).catch(reason => {
        if (reason?.isUpToDate) return templates.allTransactionTemplates[original.request.templateType] ?? [];
        throw reason;
    });
    if (serial !== generation || !current() || isActionCurrent && !isActionCurrent()) return;
    const deleted = original.receipt.deleted || !rows.some(row => row.id === original.receipt!.id);
    await store.acknowledge(original.request.ledgerId, original.request.clientSessionId, original.receipt.id);
    if (serial === generation && current() && (!isActionCurrent || isActionCurrent())) emit('complete', { deleted, id: original.receipt.id });
}
async function confirm(): Promise<void> {
    const request = previewRequest.value; if (!request || busy.value) return;
    const serial = generation;
    const parentCurrent = props.captureActionCurrent?.();
    const actionCurrent = () => serial === generation && current() && (!parentCurrent || parentCurrent());
    busy.value = true; error.value = '';
    try { const result = await store.confirm(request, revision, actionCurrent); if (!actionCurrent()) return; pending.value = result; pinPending(result); previewRequest.value = null; await finish(serial, actionCurrent); }
    catch (reason) { if (actionCurrent()) { try { const next = store.readPending(props.ledgerId); guardRecovery(next, null); pending.value = next; if (next) pinPending(next); } catch (failure) { rejectReplacement(failure); return; } if (pending.value) previewRequest.value = null; fail(reason); } }
    finally { if (serial === generation) busy.value = false; }
}
async function retry(): Promise<void> {
    if (!pending.value || busy.value) return;
    const serial = generation;
    const parentCurrent = props.captureActionCurrent?.();
    const actionCurrent = () => serial === generation && current() && (!parentCurrent || parentCurrent());
    busy.value = true; error.value = '';
    try { guardRecovery(store.readPending(props.ledgerId), null); const result = await store.retry(props.ledgerId, pending.value.request.clientSessionId, actionCurrent); if (!actionCurrent()) return; pending.value = result; pinPending(result); await finish(serial, actionCurrent); }
    catch (reason) { if (actionCurrent()) fail(reason); } finally { if (serial === generation) busy.value = false; }
}
function changedElsewhere(event: StorageEvent): void {
    if (!current() || !event.key?.startsWith(identity!) || !event.key.includes('template-')) return;
    try {
        const nextPending = store.readPending(props.ledgerId);
        const draft = nextPending || !(props.template instanceof TransactionTemplate) ? null : store.readDraft(props.ledgerId, props.template.templateType as TemplateCreationType);
        guardRecovery(nextPending, draft);
        if (nextPending) { pending.value = nextPending; pinPending(nextPending); previewRequest.value = null; emit('restore', nextPending.request); }
        else if (draft && draft.revision !== revision) { previewRequest.value = null; recoveryDraft.value = draft; }
    } catch (reason) { rejectReplacement(reason); }
}
onMounted(() => window.addEventListener('storage', changedElsewhere));
onUnmounted(() => { generation++; window.removeEventListener('storage', changedElsewhere); });
defineExpose({ preview, flush });
</script>

<style scoped>
.template-creation-panel { margin: 12px 0; min-height: 48px; font-size: var(--f7-font-size, 14px); line-height: 1.5; overflow-wrap: anywhere; }
.template-creation-status { min-height: 3em; }
.template-creation-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.template-creation-panel button { min-height: 44px; padding: 8px 16px; border: 1px solid currentColor; border-radius: 12px; color: inherit; background: transparent; font: inherit; cursor: pointer; }
.template-creation-panel button:disabled { opacity: .55; cursor: default; }
.template-creation-panel button:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
</style>
