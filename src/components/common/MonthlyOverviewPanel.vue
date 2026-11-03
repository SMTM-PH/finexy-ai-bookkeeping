<template>
    <section class="monthly-overview" aria-label="当前账本月度总览">
        <header class="monthly-heading">
            <h2>月度总览</h2>
            <button type="button" :disabled="!canRead" @click="controller.load()">重新读取</button>
        </header>
        <div class="monthly-controls">
            <button type="button" aria-label="查看上月" @click="move(-1)">上月</button>
            <label>月份<input type="month" :value="scope.month" min="2000-01" max="9998-12" @change="changeMonth" /></label>
            <button type="button" aria-label="查看下月" @click="move(1)">下月</button>
            <button type="button" @click="emit('month', monthInTimezone(new Date(), scope.timezone))">本月</button>
            <label>报告时区<input :value="scope.timezone" :aria-describedby="descriptionId" @change="changeTimezone" /></label>
        </div>
        <p :id="descriptionId" class="monthly-copy">{{ ledgerName }} · {{ scope.timezone }} 自然月 · {{ scope.hidden === 'include' ? '含隐藏记录' : '排除隐藏记录' }} · 原币种，不折算</p>
        <p v-if="identitySuspended" role="alert">登录账号已在其他页面变化，月度总览已清除。请刷新页面后重新读取。</p>
        <p v-if="formError" role="alert">{{ formError }}</p>
        <template v-if="state">
            <div :aria-busy="state.report.kind === 'loading'">
                <p :role="state.report.kind === 'failure' ? 'alert' : 'status'">{{ state.report.message }}</p>
                <div v-if="state.report.value" class="monthly-currencies">
                    <p v-if="state.report.value.matchedCount === 0">该月暂无流水。</p>
                    <section v-for="row in state.report.value.totals" :key="row.currency" class="monthly-currency">
                        <h3>{{ row.currency }}</h3>
                        <div class="monthly-metrics">
                            <button v-for="metric in metrics" :key="metric.key" type="button" @click="drill(metric.drill, row.currency)">
                                <span>{{ metric.label }}</span><strong>{{ money(row[metric.key]) }}</strong>
                            </button>
                        </div>
                        <p class="monthly-copy">退款已冲减净支出，不计作收入。</p>
                        <button type="button" @click="drill('expense', row.currency)">查看同范围支出与退款</button>
                    </section>
                    <button type="button" @click="emit('report', state.query)">查看该月完整报告</button>
                </div>
            </div>
            <section class="monthly-budget" :aria-busy="state.budget.kind === 'loading'">
                <h3>该月预算</h3>
                <p :role="state.budget.kind === 'failure' ? 'alert' : 'status'">{{ state.budget.message }}</p>
                <template v-if="state.budget.value">
                    <p>额度 · {{ state.budget.value.currency }} {{ money(String(state.budget.value.amount)) }}</p>
                    <template v-if="usage">
                        <p>同币种净支出 {{ money(usage.expense) }} · 剩余 {{ money(usage.remaining) }} · 已用 {{ usage.percent }}</p>
                        <progress max="10000" :value="usage.progressBasisPoints" aria-label="预算使用进度" />
                        <p v-if="usage.warning" role="status">{{ usage.warning === 2 ? '该月净支出已达到或超过预算。' : '该月预算已使用八成以上。' }}</p>
                        <button type="button" @click="drill('expense', state.budget.value.currency)">查看同范围预算支出</button>
                    </template>
                    <p v-else-if="!showAmounts">预算使用额 •••••• · 剩余 ••••••</p>
                    <p v-else class="monthly-copy">完整月报尚不可用，或本页排除了隐藏记录；暂不计算预算使用率。</p>
                    <p class="monthly-copy">使用额取本页 {{ scope.timezone }} 自然月、含隐藏记录的完整月报，仅计 {{ state.budget.value.currency }} 净支出。其他币种不折算。</p>
                </template>
                <button v-if="state.budget.value || state.budget.kind === 'empty' && canManageBudget && scope.mode === 'online'" type="button" @click="emit('budget', { ledgerId: scope.ledgerId, month: scope.month })">{{ state.budget.value ? '查看该月预算' : '设置该月预算' }}</button>
            </section>
        </template>
        <section v-if="$slots['pending']" class="monthly-pending">
            <h3>当前账本待处理</h3>
            <p class="monthly-copy">本账本全部月份的待处理，不随月度筛选变化。</p>
            <slot v-if="active && !identitySuspended" name="pending" />
        </section>
    </section>
</template>

<script setup lang="ts">
import { computed, onUnmounted, shallowRef, ref, watch, useId } from 'vue';
import type { LedgerReportMoney, LedgerReportQuery } from '@/models/ledger_report.ts';
import { formatLedgerReportAmount } from '@/lib/ledger_report.ts';
import type { LedgerReportDrill } from '@/lib/ledger_report_panel.ts';
import { MonthlyOverviewController, monthlyOverviewQuery, monthInTimezone, moveOverviewMonth, overviewBudgetUsage, overviewDrill,
    type BudgetUsageScope, type MonthlyOverviewScope, type MonthlyOverviewState } from '@/lib/monthly_overview.ts';
import { monthlyOverviewGateway } from '@/lib/monthly_overview_gateway.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, isUserLogined } from '@/lib/userstate.ts';
import { ledgerReportSessionChanged, ledgerReportStorageInvalidatesSession, type LedgerReportSession } from '@/lib/ledger_report_session.ts';

const props = withDefaults(defineProps<{ scope: MonthlyOverviewScope; ledgerName: string; username: string; showAmounts: boolean; canManageBudget: boolean;
    active?: boolean; budgetUsageScope?: BudgetUsageScope | null }>(), { active: true });
const emit = defineEmits<{ month: [value: string]; timezone: [value: string]; report: [query: LedgerReportQuery];
    budget: [target: { ledgerId: string; month: string }] }>();
const state = shallowRef<MonthlyOverviewState | null>(null), formError = ref('');
const descriptionId = `monthly-overview-${useId()}`;
const identitySuspended = ref(false);
function sessionSnapshot(): LedgerReportSession {
    return { identity: getTransactionStateIdentity(), username: getCurrentUserInfo()?.username ?? null, hasToken: isUserLogined() };
}
const initialSession = sessionSnapshot();
function sessionCurrent(): boolean { return !identitySuspended.value && !ledgerReportSessionChanged(initialSession, sessionSnapshot(), props.username); }
const controller = new MonthlyOverviewController(monthlyOverviewGateway(() => props.username),
    () => ({ ...props.scope, identity: props.active && sessionCurrent() ? getTransactionStateIdentity() ?? '' : '' }), value => { state.value = value; });
const canRead = computed(() => props.active && !identitySuspended.value && state.value?.scope.mode === 'online' &&
    state.value.report.kind !== 'loading' && state.value.budget.kind !== 'loading');
function invalidateSession(): void { identitySuspended.value = true; controller.invalidate(); }
function changedElsewhere(event: StorageEvent): void {
    if (event.storageArea === window.localStorage && ledgerReportStorageInvalidatesSession(event.key, event.newValue,
        initialSession, sessionSnapshot(), props.username)) invalidateSession();
}
function checkSession(): void { if (!sessionCurrent()) invalidateSession(); }
function visibilityChanged(): void { if (document.visibilityState === 'visible') checkSession(); }
const usage = computed(() => !props.showAmounts || !state.value ? null : overviewBudgetUsage(state.value,
    props.budgetUsageScope === undefined ? { ledgerId: props.scope.ledgerId, month: props.scope.month, timezone: props.scope.timezone, hidden: 'include' } : props.budgetUsageScope));
const metrics: { key: keyof Omit<LedgerReportMoney, 'currency'>; label: string; drill: LedgerReportDrill }[] = [
    { key: 'income', label: '收入', drill: 'income' }, { key: 'expense', label: '净支出（含退款）', drill: 'expense' },
    { key: 'net', label: '收支结余', drill: 'net' }, { key: 'transferIn', label: '转入', drill: 'transfer' },
    { key: 'transferOut', label: '转出', drill: 'transfer' }, { key: 'adjustment', label: '余额调整', drill: 'adjustment' }
];
function money(minor: string): string { return props.showAmounts ? formatLedgerReportAmount(minor) : '••••••'; }
function drill(kind: LedgerReportDrill, currency: string): void { if (state.value) emit('report', overviewDrill(state.value, kind, currency)); }
function move(delta: -1 | 1): void {
    try { emit('month', moveOverviewMonth(props.scope.month, delta)); formError.value = ''; }
    catch { formError.value = '该月份超出支持范围。'; }
}
function changeMonth(event: Event): void {
    const month = (event.target as HTMLInputElement).value;
    try { monthlyOverviewQuery({ ...props.scope, month }); emit('month', month); formError.value = ''; }
    catch { formError.value = '请选择有效月份。'; }
}
function changeTimezone(event: Event): void {
    const timezone = (event.target as HTMLInputElement).value.trim();
    try { monthlyOverviewQuery({ ...props.scope, timezone }); emit('timezone', timezone); formError.value = ''; }
    catch { formError.value = '请输入有效的 IANA 时区，例如 Asia/Shanghai。'; }
}
watch(() => [props.scope, props.active, props.username] as const, ([scope, active]) => {
    if (!active) { controller.invalidate(); return; }
    if (!sessionCurrent()) { invalidateSession(); return; }
    try { controller.bind(scope); formError.value = ''; void controller.load(); }
    catch { controller.invalidate(); formError.value = '无法核验当前账本或月份，请重新选择。'; }
}, { immediate: true, deep: true, flush: 'sync' });
window.addEventListener('storage', changedElsewhere);
window.addEventListener('focus', checkSession);
document.addEventListener('visibilitychange', visibilityChanged);
onUnmounted(() => {
    controller.close(); window.removeEventListener('storage', changedElsewhere); window.removeEventListener('focus', checkSession);
    document.removeEventListener('visibilitychange', visibilityChanged);
});
</script>

<style scoped>
.monthly-overview{color:var(--ink,var(--f7-text-color));display:grid;gap:16px;min-width:0}
.monthly-heading,.monthly-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.monthly-heading h2{margin:0;flex:1;font-size:18px}.monthly-overview h3{font-size:16px;margin:0 0 8px}
.monthly-overview button,.monthly-overview input{min-height:44px;font:inherit}.monthly-overview button{border:1px solid var(--line,var(--f7-list-item-border-color));border-radius:12px;padding:8px 12px;color:inherit;background:var(--panel,var(--f7-card-bg-color))}
.monthly-overview button:focus-visible,.monthly-overview input:focus-visible{outline:2px solid var(--orange,var(--f7-theme-color));outline-offset:2px}
.monthly-overview button:disabled{opacity:.55}.monthly-controls label{display:grid;gap:4px;max-width:100%}.monthly-controls input{box-sizing:border-box;max-width:100%;padding:8px;border:1px solid var(--line,var(--f7-list-item-border-color));border-radius:8px;background:var(--panel,var(--f7-card-bg-color));color:inherit}
.monthly-copy{color:var(--muted,var(--f7-label-text-color));font-size:13px;line-height:1.5;margin:0}
.monthly-currencies{display:grid;gap:16px}.monthly-currency,.monthly-budget,.monthly-pending{padding:16px;border:1px solid var(--line,var(--f7-list-item-border-color));border-radius:20px;background:var(--panel,var(--f7-card-bg-color));min-width:0}
.monthly-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,170px),1fr));gap:8px;margin-bottom:12px}.monthly-metrics button{display:grid;text-align:left;gap:8px;min-width:0}.monthly-metrics strong{font-family:'JetBrains Mono Variable',monospace;font-size:18px;overflow-wrap:anywhere;font-weight:500}.monthly-metrics span{font-size:14px}
.monthly-currency>.monthly-copy{margin-bottom:8px}.monthly-overview p{overflow-wrap:anywhere}
.monthly-budget progress{width:100%;accent-color:var(--orange,var(--f7-theme-color))}
</style>
