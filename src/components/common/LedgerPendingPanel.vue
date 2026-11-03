<template>
    <section class="ledger-pending-panel" :class="{ 'pending-compact': compact }" data-testid="ledger-pending" :aria-busy="loading">
        <header class="pending-header">
            <div><h3>当前账本待处理</h3><p>{{ ledgers.selectedLedger.name }} · {{ totalLabel }}</p></div>
            <button type="button" :disabled="loading || suspended" @click="refresh">{{ loading ? '正在读取…' : '刷新待处理' }}</button>
        </header>
        <p class="pending-copy">打开原条目后核对，明确确认才入账。</p>
        <div v-if="suspended" class="pending-warning" role="alert">账号状态已变化，请重新载入页面后核对原账本。<button type="button" @click="reloadSession">重新载入页面</button></div>
        <p v-else-if="panelError" class="pending-warning" role="alert">{{ panelError }}</p>
        <p v-if="!summary && !suspended" role="status">{{ loading ? '正在核对当前账本队列与本机恢复记录…' : '尚未完整读取，请刷新待处理。' }}</p>
        <template v-if="summary && !suspended">
            <p v-if="summary.exactTotal === null" class="pending-copy" role="status">部分来源尚未核验，已知数量不代表全部待处理。</p>
            <details v-if="sourceProblems.length" class="pending-problems">
                <summary>查看 {{ sourceProblems.length }} 项读取状态</summary>
                <p v-for="problem in sourceProblems" :key="problem.key">{{ problem.label }}：{{ problem.message }}</p>
            </details>
            <details v-for="group in groups" :key="group.key" class="pending-group" :open="!compact" :data-testid="`pending-${group.key}`">
                <summary><span>{{ group.label }}</span><strong>{{ countLabel(group.value.count, group.value.knownCount) }}</strong></summary>
                <p v-if="group.value.state === 'ready' && !group.value.entries.length" class="pending-copy">此类暂无待处理。</p>
                <p v-else-if="!group.value.entries.length" class="pending-copy">{{ stateLabel(group.value.state) }}，请核对读取状态后重试。</p>
                <ul v-if="group.value.entries.length" class="pending-entries">
                    <li v-for="(entry, index) in group.value.entries.slice(0, limits[group.key])" :key="entry.key" :data-pending-state="entry.state">
                        <div><b>{{ entryTitle(entry, index) }}</b><small>{{ entryStateLabels[entry.state] }}</small></div>
                        <div class="pending-entry-actions"><button v-for="target in entry.targets" :key="ledgerPendingTargetKey(target)" type="button" :disabled="!!opening || loading" @click="open(target)">{{ opening === ledgerPendingTargetKey(target) ? '正在核验…' : targetLabel(target) }}</button></div>
                    </li>
                </ul>
                <button v-if="group.value.entries.length > limits[group.key]" type="button" @click="limits[group.key] += 20">继续显示（剩余 {{ group.value.entries.length - limits[group.key] }} 项）</button>
            </details>
            <p class="pending-copy pending-updated">读取于 {{ new Date(summary.refreshedAt).toLocaleTimeString() }} · 各来源分别读取</p>
        </template>
    </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useLedgerPendingStore } from '@/stores/ledgerPending.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useUserStore } from '@/stores/user.ts';
import { ledgerPendingTargetKey } from '@/lib/ledger_pending.ts';
import { ledgerReportSessionChanged, ledgerReportStorageInvalidatesSession } from '@/lib/ledger_report_session.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, isUserLogined } from '@/lib/userstate.ts';
import type { LedgerPendingEntry, LedgerPendingReadState, LedgerPendingSection, LedgerPendingSource, LedgerPendingTarget } from '@/models/ledger_pending.ts';

const props = withDefaults(defineProps<{ active?: boolean; compact?: boolean }>(), { active: true, compact: false });
const emit = defineEmits<{ open: [target: LedgerPendingTarget] }>();
const pending = useLedgerPendingStore(), ledgers = useLedgersStore(), user = useUserStore();
const loading = ref(false), suspended = ref(false), panelError = ref(''), opening = ref('');
const limits = reactive<Record<LedgerPendingSection, number>>({ ai: 10, periodic: 10, local: 10 });
let generation = 0;
const session = () => ({ identity: getTransactionStateIdentity(), username: getCurrentUserInfo()?.username ?? null, hasToken: isUserLogined() });
const initialSession = session();
const summary = computed(() => suspended.value || !props.active || !user.currentUserBasicInfo?.username ? null : pending.summaryFor(ledgers.selectedLedgerId));
const groups = computed(() => summary.value ? ([{ key: 'ai', label: 'AI 待复核' }, { key: 'periodic', label: '周期待确认' }, { key: 'local', label: '本机恢复' }] as const).map(group => ({ ...group, value: summary.value!.sections[group.key] })) : []);
const sourceLabels: Record<LedgerPendingSource, string> = { ai: 'AI 队列', periodic: '周期队列', transactionPending: '记账原请求', transactionDraft: '记账草稿', templatePending: '模板原请求', normalTemplateDraft: '普通模板草稿', scheduledTemplateDraft: '周期模板草稿', aiCreate: 'AI 保存原请求', aiConfirm: 'AI 入账原请求', aiEditor: 'AI 复核草稿', aiSource: 'AI 来源草稿' };
const sourceProblems = computed(() => summary.value ? (Object.keys(sourceLabels) as LedgerPendingSource[]).flatMap(key => {
    const value = summary.value!.sourceStates[key];
    return value.state === 'ready' ? [] : [{ key, label: sourceLabels[key], message: value.message || stateLabel(value.state) }];
}) : []);
const totalLabel = computed(() => summary.value ? countLabel(summary.value.exactTotal, summary.value.knownCount) : loading.value ? '正在核对' : '待核验');
const entryStateLabels = { pending: '待核对并确认', draft: '本机草稿，尚未提交', 'unknown-result': '提交结果待核对，保留原请求', receipt: '已有回执，只重新读取原结果' };
function countLabel(count: number | null, known: number): string { return count === null ? `已知 ${known} 项 · 待核验` : `${count} 项`; }
function stateLabel(state: LedgerPendingReadState): string { return { loading: '正在读取', ready: '已完整读取', error: '读取失败', unavailable: '当前服务不支持', denied: '权限失效' }[state]; }
function targetLabel(target: LedgerPendingTarget): string { return target.kind === 'ai-review' ? '打开原复核条目' : target.kind === 'periodic-review' ? '定位原到期记录' : target.kind === 'ai-source' ? '打开原来源' : target.kind === 'template-create' ? '恢复原模板' : '恢复原记账'; }
function entryTitle(entry: LedgerPendingEntry, index: number): string {
    const target = entry.targets[0];
    if (!target) return `待处理 ${index + 1}`;
    if (target.kind === 'ai-review') return `AI 条目 #${target.id}`;
    if (target.kind === 'periodic-review') return `周期 #${target.templateId} · ${new Date(target.scheduledUnixTime * 1000).toLocaleDateString()}`;
    return `${target.kind === 'ai-source' ? 'AI 来源' : target.kind === 'template-create' ? target.templateType === 2 ? '周期模板' : '普通模板' : '普通记账'} ${index + 1}`;
}
function checkSession(): boolean {
    if (suspended.value) return false;
    if (ledgerReportSessionChanged(initialSession, session(), user.currentUserBasicInfo?.username ?? null)) {
        suspended.value = true; generation++; loading.value = false; opening.value = ''; panelError.value = ''; pending.reset(); return false;
    }
    return true;
}
async function refresh(): Promise<void> {
    if (!props.active || !checkSession()) return;
    const serial = ++generation, ledgerId = ledgers.selectedLedgerId;
    loading.value = true; panelError.value = '';
    try { await pending.refresh(ledgerId); }
    catch { if (serial === generation && checkSession()) panelError.value = '未能核对待处理状态，请检查连接、解锁本机记录后重试。'; }
    finally { if (serial === generation) loading.value = false; }
}
async function open(target: LedgerPendingTarget): Promise<void> {
    if (opening.value || loading.value || !props.active || !checkSession()) return;
    const serial = generation; opening.value = ledgerPendingTargetKey(target); panelError.value = '';
    try {
        await pending.verifyTarget(target);
        if (serial !== generation || !props.active || !checkSession() || target.ledgerId !== ledgers.selectedLedgerId) return;
        emit('open', target);
    } catch { if (serial === generation && checkSession()) panelError.value = '原条目、账号或权限已变化，请刷新待处理后重新打开；原请求仍保留。'; }
    finally { if (serial === generation) opening.value = ''; }
}
function reloadSession(): void { window.location.reload(); }
function onStorage(event: StorageEvent): void {
    if (ledgerReportStorageInvalidatesSession(event.key, event.newValue, initialSession, session(), user.currentUserBasicInfo?.username ?? null)) {
        suspended.value = true; generation++; pending.reset(); loading.value = false;
    }
}
function onFocus(): void { if (props.active && checkSession()) void refresh(); }
function onVisibility(): void { if (document.visibilityState === 'visible') onFocus(); }
watch(() => [props.active, ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], () => {
    generation++; loading.value = false; panelError.value = ''; opening.value = '';
    if (props.active && checkSession()) void refresh();
}, { immediate: true, flush: 'sync' });
onMounted(() => { window.addEventListener('storage', onStorage); window.addEventListener('focus', onFocus); document.addEventListener('visibilitychange', onVisibility); });
onUnmounted(() => { generation++; window.removeEventListener('storage', onStorage); window.removeEventListener('focus', onFocus); document.removeEventListener('visibilitychange', onVisibility); });
</script>

<style scoped>
.ledger-pending-panel { --pending-surface: var(--surface, var(--card, var(--f7-card-bg-color, Canvas))); --pending-ink: var(--ink, var(--f7-text-color, CanvasText)); --pending-muted: color-mix(in srgb, var(--muted, var(--f7-block-footer-text-color, CanvasText)) 74%, var(--pending-ink)); --pending-line: var(--line, var(--f7-list-item-border-color, GrayText)); --pending-accent: var(--orange, var(--f7-theme-color, Highlight)); display: grid; gap: 12px; min-width: 0; padding: 20px; background: var(--pending-surface); color: var(--pending-ink); border: 1px solid var(--pending-line); border-radius: 18px; font: max(14px, 1em)/1.6 var(--ledger-font-body, 'Microsoft YaHei UI', sans-serif); font-synthesis: none; overflow-wrap: anywhere; }
.ledger-pending-panel :is(h3, p, ul) { margin: 0; }
.ledger-pending-panel h3 { font: inherit; font-size: 1.15em; font-weight: 650; color: inherit; }
.pending-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.pending-header > div { flex: 1 1 170px; min-width: 0; }
.pending-header p, .pending-copy, .pending-entries small, .pending-problems p { color: var(--pending-muted); font-size: max(13px, .93em); }
.ledger-pending-panel button { min-height: 48px; max-width: 100%; padding: 10px 12px; border: 1px solid var(--pending-line); border-radius: 11px; color: inherit; background: var(--pending-surface); font: inherit; line-height: 1.4; cursor: pointer; white-space: normal; overflow-wrap: anywhere; }
.ledger-pending-panel :is(button, summary):focus-visible { outline: 2px solid var(--pending-accent); outline-offset: 3px; }
.ledger-pending-panel button:disabled { opacity: .55; cursor: default; }
.pending-group, .pending-problems { border-top: 1px solid var(--pending-line); }
.pending-group > summary, .pending-problems > summary { min-height: 48px; padding-block: 10px; cursor: pointer; }
.pending-group > summary > strong { float: inline-end; font-weight: 600; margin-inline-start: 12px; }
.pending-entries { list-style: none; padding: 0; }
.pending-entries li { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding-block: 12px; border-bottom: 1px solid var(--pending-line); }
.pending-entries li > div:first-child { min-width: 0; flex: 1 1 180px; }
.pending-entries b, .pending-entries small { display: block; }
.pending-entry-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.pending-warning { padding-inline-start: 12px; border-inline-start: 3px solid var(--pending-accent); }
.pending-warning button { display: block; margin-top: 8px; }
.pending-updated { font-size: 12px; }
@media (max-width: 650px) { .ledger-pending-panel { padding: 16px; } .pending-entry-actions { width: 100%; } .pending-entry-actions button { flex: 1 1 140px; } }
@media (prefers-reduced-motion: reduce) { .ledger-pending-panel * { animation: none !important; transition: none !important; scroll-behavior: auto !important; } }
</style>
