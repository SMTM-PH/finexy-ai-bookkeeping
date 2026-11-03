<template>
    <f7-page class="mobile-ai-review-page" @page:afterin="enter" @page:beforeout="leave">
        <f7-navbar title="AI 待复核" :back-link="tt('Back')" />
        <ledger-context-notice />
        <f7-block>
            <p>AI 结果需要人工核对。预览并确认后才入账。</p>
            <div class="ai-review-actions">
                <button type="button" :aria-pressed="status === 1" :disabled="busy" @click="select(1)">待复核</button>
                <button type="button" :aria-pressed="status === 3" :disabled="busy" @click="select(3)">已忽略</button>
                <button type="button" :disabled="busy || loading" @click="load">刷新当前账本</button>
            </div>
            <p v-if="recoveryId" role="status">本机有原条目的草稿或入账回执，即使条目已移出队列也可以核对。</p>
            <button v-if="recoveryId" type="button" :disabled="busy" @click="open(recoveryId)">恢复原条目</button>
            <p v-if="error" role="alert">{{ error }}</p>
            <p v-if="loading" role="status">正在完整读取当前账本 AI 队列…</p>
            <p v-else-if="!error && !rows.length" role="status">{{ status === 1 ? '当前账本没有待复核条目。' : '当前账本没有已忽略条目。' }}</p>
        </f7-block>
        <template v-if="!loading && !error">
            <f7-card v-for="row in rows" :key="row.id" class="mobile-ai-review-card">
                <f7-card-content>
                    <p>{{ row.sourceText.slice(0, 160) }}</p>
                    <p v-if="row.failureReason">识别未完成，可手动核对。</p>
                    <p v-else>{{ row.recognizedData ? 'AI 猜测，请核对账户、分类与实际币种。' : '尚无结构化结果，请手动填写。' }}</p>
                    <div class="ai-review-actions">
                        <button type="button" :disabled="busy" @click="open(row.id)">{{ row.editable && status === 1 && store.canWrite ? '编辑并入账' : '查看条目' }}</button>
                        <button v-if="row.editable && store.canWrite && row.id !== recoveryId" type="button" :disabled="busy" @click="confirmation = row">{{ status === 1 ? '忽略条目' : '恢复到待复核' }}</button>
                    </div>
                </f7-card-content>
            </f7-card>
        </template>
        <f7-block v-if="confirmation" role="group" aria-label="确认 AI 条目状态操作">
            <p>{{ status === 1 ? '忽略这条记录？不会改变余额。' : '恢复这条记录到当前账本待复核队列？' }}</p>
            <p>{{ confirmation.sourceText.slice(0, 160) }}</p>
            <div class="ai-review-actions">
                <button type="button" :disabled="busy" @click="change">确认{{ status === 1 ? '忽略' : '恢复' }}</button>
                <button type="button" :disabled="busy" @click="confirmation = null">取消</button>
            </div>
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import type { Router } from 'framework7/types';
import LedgerContextNotice from '@/components/mobile/LedgerContextNotice.vue';
import { useAIReviewLedgerStore } from '@/stores/aiReviewLedger.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useUserStore } from '@/stores/user.ts';
import { getTransactionStateIdentity } from '@/lib/userstate.ts';
import { useI18n } from '@/locales/helpers.ts';
import type { AIReviewLedgerItem } from '@/models/ai_review_ledger.ts';

const props = defineProps<{ f7router: Router.Router }>();
const { tt } = useI18n();
const store = useAIReviewLedgerStore(), ledgers = useLedgersStore(), user = useUserStore();
const status = ref(1), rows = ref<AIReviewLedgerItem[]>([]), loading = ref(true), busy = ref(false), error = ref('');
const recoveryId = ref(''), confirmation = ref<AIReviewLedgerItem | null>(null);
let active = true, generation = 0;
function leave(): void { active = false; generation++; confirmation.value = null; }
function enter(): void { active = true; void load(); }
async function load(): Promise<void> {
    const serial = ++generation, ledgerId = ledgers.selectedLedgerId, identity = getTransactionStateIdentity(), selectedStatus = status.value;
    const current = () => active && serial === generation && ledgerId === ledgers.selectedLedgerId && identity === getTransactionStateIdentity();
    rows.value = []; loading.value = true; error.value = ''; recoveryId.value = ''; confirmation.value = null;
    try {
        recoveryId.value = store.readPendingConfirm(ledgerId)?.request.id || store.readDraft(ledgerId)?.input.id || '';
        await store.load(ledgerId, selectedStatus);
        if (current() && store.loaded && store.loadedLedgerId === ledgerId) rows.value = [...store.items];
    } catch { if (current()) error.value = '未能完整读取当前账本队列或本机恢复状态，请重新读取；原内容已保留。'; }
    finally { if (current()) loading.value = false; }
}
function select(value: number): void { status.value = value; void load(); }
function open(id: string): void {
    props.f7router.navigate(`/transaction/add?ledgerId=${ledgers.selectedLedgerId}&aiReviewId=${id}`);
}
async function change(): Promise<void> {
    const row = confirmation.value, ledgerId = ledgers.selectedLedgerId, identity = getTransactionStateIdentity();
    if (!row || busy.value || row.ledgerId !== ledgerId) return;
    busy.value = true;
    try {
        await store.change(status.value === 1 ? 'dismiss' : 'restore', ledgerId, row.id);
        if (active && ledgerId === ledgers.selectedLedgerId && identity === getTransactionStateIdentity()) await load();
    } catch { if (active && ledgerId === ledgers.selectedLedgerId && identity === getTransactionStateIdentity()) error.value = '状态操作尚未核对，请刷新原条目；不会自动入账。'; }
    finally { busy.value = false; confirmation.value = null; }
}
watch(() => [ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], () => {
    generation++; rows.value = []; recoveryId.value = ''; confirmation.value = null; error.value = ''; loading.value = true;
    if (active) void load();
}, { flush: 'sync' });
onUnmounted(leave);
void load();
</script>

<style scoped>
.mobile-ai-review-page { overflow-wrap: anywhere; }
.ai-review-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.mobile-ai-review-page button { min-height: 44px; padding: 8px 12px; border: 1px solid currentColor; border-radius: 12px; color: var(--f7-text-color); background: var(--f7-block-strong-bg-color); font: inherit; }
.mobile-ai-review-page button[aria-pressed="true"] { font-weight: 700; border-width: 2px; }
.mobile-ai-review-page button:disabled { opacity: .55; }
.mobile-ai-review-page button:focus-visible { outline: 2px solid var(--f7-theme-color); outline-offset: 2px; }
</style>
