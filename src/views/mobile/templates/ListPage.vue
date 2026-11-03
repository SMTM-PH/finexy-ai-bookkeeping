<template>
    <f7-page class="mobile-periodic-page" ptr @ptr:refresh="reload" @page:afterin="onPageAfterIn" @page:beforeout="leave">
        <f7-navbar :title="isSchedule ? '周期计划与待确认' : '交易模板'" :back-link="tt('Back')">
            <f7-nav-right>
                <f7-link v-if="canManage && section === 'plans'" :href="addLink" aria-label="新增当前账本模板" icon-f7="plus" />
            </f7-nav-right>
        </f7-navbar>
        <ledger-context-notice />
        <f7-block v-if="navigationError" strong inset role="alert" class="periodic-error">
            <p>{{ navigationError }}</p>
            <p>原待确认记录保留，不会打开其他账本或替代记录。</p>
            <f7-button fill @click="retryOriginal">重试原入口</f7-button>
            <f7-button outline href="/">返回总览</f7-button>
        </f7-block>
        <f7-block v-if="!navigationError" class="periodic-intro">
            <p v-if="navigationTarget">这里只定位原待确认记录。请核对内容，再明确选择确认或忽略。</p>
            <p v-if="isSchedule">到期后生成待确认记录，确认才入账。隐藏仍执行；暂停使用停用频率。执行时刻为模板时区午夜。</p>
            <p v-else>模板属于当前账本；应用后先检查完整字段，再确认记账。</p>
            <p v-if="!loading && role === LedgerMemberRole.Viewer">当前成员只读，可查看模板与待确认记录。</p>
            <p v-else-if="!loading && role === LedgerMemberRole.Member">你可以应用模板、确认或忽略到期记录；模板由所有者或管理员管理。</p>
        </f7-block>
        <f7-block v-if="isSchedule && !navigationTarget && !navigationError" class="periodic-tabs">
            <f7-segmented strong>
                <f7-button :active="section === 'plans'" @click="section = 'plans'">计划</f7-button>
                <f7-button :active="section === 'pending'" @click="section = 'pending'">待确认 {{ pending.length }}</f7-button>
                <f7-button :active="section === 'dismissed'" @click="section = 'dismissed'">已忽略 {{ dismissed.length }}</f7-button>
            </f7-segmented>
        </f7-block>
        <f7-block v-if="error && !navigationError" role="alert" class="periodic-error">{{ error }} <f7-button outline :disabled="busy" @click="load(true)">重新读取当前账本</f7-button></f7-block>
        <f7-block v-if="loading && !navigationError" role="status">正在读取当前账本的模板和待确认记录…</f7-block>
        <template v-else-if="!error && !navigationError">
            <f7-block v-if="section === 'plans'" class="periodic-options">
                <label><input v-model="showHidden" type="checkbox" /> 显示隐藏模板</label>
                <f7-button v-if="sortDirty && canManage" fill :disabled="busy" @click="saveOrder">保存模板顺序</f7-button>
                <f7-button v-if="sortDirty" outline :disabled="busy" @click="load(true)">放弃顺序调整</f7-button>
            </f7-block>
            <f7-block v-if="!displayRows.length" role="status">{{ navigationTarget ? '原待确认记录已处理，请回总览读取最新状态。' : section === 'pending' ? '当前账本没有待确认记录。到期只进入此队列，不会自动入账。' : section === 'dismissed' ? '当前账本没有已忽略记录。' : showHidden ? '当前账本还没有模板。' : '没有可见模板，可查看隐藏模板或新增。' }}</f7-block>
            <f7-card v-for="(row, index) in displayRows" :id="navigationTarget ? targetCardId : undefined" :tabindex="navigationTarget ? -1 : undefined" :key="rowKey(row)" class="periodic-card">
                <f7-card-content>
                    <h2>{{ row.name }}</h2>
                    <p class="periodic-amount">{{ amount(row) }}</p>
                    <p>{{ typeName(row.type) }} · {{ accountName(row.sourceAccountId) }}<template v-if="row.type === TransactionType.Transfer"> → {{ accountName(row.destinationAccountId) }}</template></p>
                    <p v-if="row instanceof TransactionTemplate">{{ categoryName(row.categoryId) }}<template v-if="isSchedule"> · {{ formatScheduledRule(row) }}</template><span v-if="row.hidden"> · 已隐藏（不等于暂停）</span></p>
                    <p v-if="isSchedule && row instanceof TransactionTemplate">{{ formatNextScheduledTime(row.nextScheduledTime) }} · 以设备时区显示</p>
                    <p v-if="row instanceof ScheduledOccurrence">到期 {{ formatNextScheduledTime(row.scheduledUnixTime) }} · 以设备时区显示</p>
                    <p v-if="row.comment" class="periodic-comment">{{ row.comment }}</p>
                    <p v-if="row.tagIds.length">保留 {{ row.tagIds.length }} 个原标签关联</p>
                    <div class="periodic-actions" v-if="row instanceof TransactionTemplate">
                        <f7-button v-if="!isSchedule && canWrite" outline :disabled="busy" @click="apply(row)">应用模板</f7-button>
                        <template v-if="canManage">
                            <f7-button outline :disabled="busy" @click="edit(row)">{{ isSchedule && !row.scheduledFrequencyType ? '编辑并启用' : '编辑模板' }}</f7-button>
                            <f7-button outline :disabled="busy" @click="mutate(row, 'hide')">{{ row.hidden ? '显示模板' : '隐藏模板' }}</f7-button>
                            <f7-button v-if="isSchedule && row.scheduledFrequencyType" outline :disabled="busy" @click="ask(row, 'pause')">暂停计划</f7-button>
                            <f7-button outline color="red" :disabled="busy" @click="ask(row, 'delete')">删除模板</f7-button>
                            <f7-button v-if="showHidden" outline :disabled="busy || index === 0" @click="move(row, -1)">上移</f7-button>
                            <f7-button v-if="showHidden" outline :disabled="busy || index === displayRows.length - 1" @click="move(row, 1)">下移</f7-button>
                        </template>
                    </div>
                    <div class="periodic-actions" v-else-if="canReview">
                        <f7-button v-if="row.isPending" fill :disabled="busy" @click="ask(row, 'confirm')">确认入账</f7-button>
                        <f7-button v-if="row.isPending" outline :disabled="busy" @click="ask(row, 'dismiss')">忽略记录</f7-button>
                        <f7-button v-if="row.isDismissed" outline :disabled="busy" @click="ask(row, 'restore')">恢复待确认</f7-button>
                    </div>
                </f7-card-content>
            </f7-card>
        </template>
        <f7-actions :opened="!!confirmation" close-by-outside-click close-on-escape @actions:closed="confirmation = null">
            <f7-actions-group v-if="confirmation">
                <f7-actions-label>{{ confirmationText }}</f7-actions-label>
                <f7-actions-label>{{ confirmation.row.name }} · {{ amount(confirmation.row) }} · {{ confirmation.ledgerName }}</f7-actions-label>
                <f7-actions-button :color="confirmation.action === 'delete' ? 'red' : undefined" :disabled="busy" @click="confirmAction">{{ confirmation.action === 'confirm' ? '确认入账' : confirmation.action === 'dismiss' ? '忽略记录' : confirmation.action === 'restore' ? '恢复待确认' : confirmation.action === 'pause' ? '暂停计划' : '删除模板' }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group><f7-actions-button bold :disabled="busy" @click="confirmation = null">取消</f7-actions-button></f7-actions-group>
        </f7-actions>
    </f7-page>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, useId, watch } from 'vue';
import type { Router } from 'framework7/types';
import { useI18n } from '@/locales/helpers.ts';
import { useI18nUIComponents } from '@/lib/ui/mobile.ts';
import { useTransactionTemplatesStore } from '@/stores/transactionTemplate.ts';
import { useScheduledOccurrencesStore } from '@/stores/scheduledOccurrence.ts';
import { useAccountsStore } from '@/stores/account.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useUserStore } from '@/stores/user.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { TemplateType, ScheduledTemplateFrequencyType } from '@/core/template.ts';
import { TransactionType } from '@/core/transaction.ts';
import { TransactionTemplate } from '@/models/transaction_template.ts';
import { ScheduledOccurrence } from '@/models/scheduled_occurrence.ts';
import { LedgerMemberRole } from '@/models/ledger.ts';
import { formatPeriodicAmounts } from '@/lib/periodic.ts';
import { formatScheduledRule, formatNextScheduledTime } from '@/lib/scheduled_transaction.ts';
import { canManageMobileLedger, canWriteMobileLedger, mobileLedgerRole } from '@/lib/mobile_ledger.ts';
import { getTransactionStateIdentity, isUserLogined } from '@/lib/userstate.ts';
import { useLedgerPendingStore } from '@/stores/ledgerPending.ts';
import { mobilePendingRouteTarget } from '@/lib/mobile_pending_route.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import services from '@/lib/services.ts';
import LedgerContextNotice from '@/components/mobile/LedgerContextNotice.vue';

const props = defineProps<{ f7route: Router.Route; f7router: Router.Router }>();
const { tt, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const { showToast } = useI18nUIComponents();
const templatesStore = useTransactionTemplatesStore();
const queue = useScheduledOccurrencesStore();
const accounts = useAccountsStore();
const categories = useTransactionCategoriesStore();
const ledgers = useLedgersStore();
const user = useUserStore();
const settings = useSettingsStore();
const pendingStore = useLedgerPendingStore();
const navigationError = ref('');
const openedIdentity = getTransactionStateIdentity();
const openedUsername = user.currentUserBasicInfo?.username;
let navigationTarget: Extract<LedgerPendingTarget, { kind: 'periodic-review' }> | null = null;
let navigationInvalidated = false;
let active = true;
try {
    const target = mobilePendingRouteTarget(props.f7route.path, props.f7route.query);
    if (target && target.kind !== 'periodic-review') throw new Error('原待确认入口无效');
    navigationTarget = target;
} catch { navigationError.value = '原待确认记录定位无效，请回总览重新读取。'; }
const targetCardId = useId();
const isSchedule = props.f7route.path.startsWith('/schedule/');
const templateType = isSchedule ? TemplateType.Schedule.type : TemplateType.Normal.type;
const section = ref<'plans' | 'pending' | 'dismissed'>(props.f7route.path === '/schedule/review' ? 'pending' : 'plans');
const showHidden = ref(false);
const loading = ref(true);
const busy = ref(false);
const error = ref('');
const role = ref<number | null>(null);
const validLedgerId = ref<string | null>(null);
const sortDirty = ref(false);
let generation = 0;
type Row = TransactionTemplate | ScheduledOccurrence;
type Action = 'confirm' | 'dismiss' | 'restore' | 'pause' | 'delete';
const confirmation = ref<{ row: Row; action: Action; ledgerName: string; identity: string | null } | null>(null);
const current = computed(() => validLedgerId.value === ledgers.selectedLedgerId);
const canManage = computed(() => current.value && !error.value && canManageMobileLedger(role.value));
const canWrite = computed(() => current.value && !error.value && canWriteMobileLedger(role.value));
const canReview = computed(() => canWrite.value && queue.loadedLedgerId === ledgers.selectedLedgerId && queue.canReview);
const plans = computed(() => current.value && templatesStore.loadedLedgerId === ledgers.selectedLedgerId ? templatesStore.allTransactionTemplates[templateType] ?? [] : []);
const pending = computed(() => current.value && queue.loadedLedgerId === ledgers.selectedLedgerId ? queue.pendingOccurrences : []);
const dismissed = computed(() => current.value && queue.loadedLedgerId === ledgers.selectedLedgerId ? queue.dismissedOccurrences : []);
const displayRows = computed<Row[]>(() => navigationTarget ? pending.value.filter(row => matchesOriginal(row))
    : section.value === 'pending' ? pending.value : section.value === 'dismissed' ? dismissed.value : plans.value.filter(row => showHidden.value || !row.hidden));
const addLink = computed(() => `/template/add?templateType=${templateType}&ledgerId=${ledgers.selectedLedgerId}`);
const confirmationText = computed(() => confirmation.value?.action === 'confirm' ? '确认后按到期时捕获的字段入账；重复确认会核对同一笔流水。'
    : confirmation.value?.action === 'dismiss' ? '忽略后不入账，仍可在已忽略列表恢复。'
    : confirmation.value?.action === 'restore' ? '恢复后仍待确认，不会自动入账。'
    : confirmation.value?.action === 'pause' ? '暂停未来到期；已捕获的待确认记录保留。重新启用须编辑原规则。'
    : '删除此模板；已有流水和已捕获的待确认记录保留。');
const rowKey = (row: Row) => row instanceof TransactionTemplate ? row.id : `${row.templateId}:${row.scheduledUnixTime}`;
const typeName = (type: number) => type === TransactionType.Expense ? '支出 / 退款' : type === TransactionType.Income ? '收入' : '转账';
const accountName = (id: string) => accounts.accountListLedgerId === ledgers.selectedLedgerId ? accounts.allAccountsMap[id]?.name || '账户待核对' : '账户待核对';
const categoryName = (id: string) => categories.loadedLedgerId === ledgers.selectedLedgerId ? categories.allTransactionCategoriesMap[id]?.name || '分类待核对' : '分类待核对';
function amount(row: Row): string {
    return formatPeriodicAmounts(row, accounts.accountListLedgerId === ledgers.selectedLedgerId ? accounts.allAccountsMap : {}, formatAmountToLocalizedNumeralsWithCurrency, settings.appSettings.showAmountInHomePage);
}
function messageOf(reason: unknown): string {
    const failure = reason as { message?: string; error?: { errorMessage?: string } };
    return reason instanceof Error ? reason.message : failure?.error?.errorMessage || failure?.message || '操作失败，请保留当前记录后重试。';
}
function matchesOriginal(row: ScheduledOccurrence): boolean {
    return !!navigationTarget && row.ledgerId === navigationTarget.ledgerId && row.templateId === navigationTarget.templateId && row.scheduledUnixTime === navigationTarget.scheduledUnixTime;
}
function assertNavigationCurrent(): void {
    if (navigationTarget && (navigationInvalidated || navigationTarget.ledgerId !== ledgers.selectedLedgerId ||
        openedIdentity !== getTransactionStateIdentity() || openedUsername !== user.currentUserBasicInfo?.username || !isUserLogined())) {
        throw new Error('账号或账本已变化，请回总览核对原待确认记录后重新打开。');
    }
}
function retryOriginal(): void { props.f7router.navigate(props.f7route.url, { reloadCurrent: true, animate: false }); }
function targetCurrent(row: Row): boolean {
    return active && !navigationError.value && current.value && row.ledgerId === ledgers.selectedLedgerId &&
        (!navigationTarget || row instanceof ScheduledOccurrence && matchesOriginal(row) && !navigationInvalidated && openedIdentity === getTransactionStateIdentity());
}
async function load(force = false): Promise<void> {
    if (!active || navigationError.value) return;
    const serial = ++generation;
    const ledgerId = ledgers.selectedLedgerId;
    const identity = getTransactionStateIdentity();
    const stillCurrent = () => active && serial === generation && ledgerId === ledgers.selectedLedgerId && identity === getTransactionStateIdentity();
    loading.value = true; error.value = ''; role.value = null; validLedgerId.value = null; confirmation.value = null;
    try {
        if (navigationTarget) {
            assertNavigationCurrent();
            await pendingStore.refresh(navigationTarget.ledgerId);
            await pendingStore.verifyTarget(navigationTarget);
            assertNavigationCurrent();
            if (!stillCurrent()) return;
        }
        const memberResult = ledgerId === '0' ? LedgerMemberRole.Owner : await services.listLedgerMembers({ ledgerId }).then(response => {
            if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取当前账本成员权限');
            return mobileLedgerRole(ledgerId, response.data.result);
        });
        if (!stillCurrent()) return;
        await Promise.all([
            accounts.loadAllAccounts({ force, ledgerId }).catch(reason => { if (!(reason as { isUpToDate?: boolean })?.isUpToDate) throw reason; }),
            categories.loadAllCategories({ force, ledgerId }).catch(reason => { if (!(reason as { isUpToDate?: boolean })?.isUpToDate) throw reason; }),
            templatesStore.loadAllTemplates({ force, ledgerId, templateType }).catch(reason => { if (!(reason as { isUpToDate?: boolean })?.isUpToDate) throw reason; }),
            isSchedule ? queue.load(ledgerId) : Promise.resolve()
        ]);
        if (!stillCurrent()) return;
        if (navigationTarget && !queue.pendingOccurrences.some(matchesOriginal)) throw new Error('原待确认记录已变化或已处理，请回总览重新读取。');
        role.value = memberResult; validLedgerId.value = ledgerId; sortDirty.value = false;
        if (navigationTarget) {
            await nextTick();
            if (stillCurrent()) {
                const target = document.getElementById(targetCardId);
                target?.focus({ preventScroll: true });
                target?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
            }
        }
    } catch (reason) {
        if (stillCurrent()) {
            if (navigationTarget) navigationError.value = '无法核验原待确认记录，请重试原入口或回总览核对。';
            else error.value = messageOf(reason);
        }
    }
    finally { if (stillCurrent()) loading.value = false; }
}
function reload(done?: () => void): void { void load(true).finally(() => done?.()); }
function edit(row: TransactionTemplate): void {
    if (!canManage.value || busy.value || !targetCurrent(row)) return;
    props.f7router.navigate(`/template/edit?id=${row.id}&templateType=${row.templateType}&ledgerId=${row.ledgerId}`);
}
function apply(row: TransactionTemplate): void {
    if (!canWrite.value || busy.value || !targetCurrent(row)) return;
    props.f7router.navigate(`/transaction/add?templateId=${row.id}&ledgerId=${row.ledgerId}`);
}
function clone(row: TransactionTemplate): TransactionTemplate {
    return TransactionTemplate.ofTemplate({ ...row, categoryId: row.categoryId, tagIds: [...row.tagIds] }, row.ledgerId);
}
function ask(row: Row, action: Action): void {
    if (busy.value || !targetCurrent(row) || (row instanceof TransactionTemplate ? !canManage.value : !canReview.value)) return;
    confirmation.value = { row: row instanceof TransactionTemplate ? clone(row) : row, action, ledgerName: ledgers.selectedLedger.name, identity: getTransactionStateIdentity() };
}
async function run(row: Row, action: () => Promise<unknown>): Promise<void> {
    if (busy.value || !targetCurrent(row)) return;
    const serial = generation;
    const identity = getTransactionStateIdentity();
    busy.value = true; error.value = '';
    try { await action(); }
    catch (reason) { if (serial === generation && targetCurrent(row) && identity === getTransactionStateIdentity()) error.value = messageOf(reason); }
    finally { busy.value = false; }
}
async function mutate(row: TransactionTemplate, action: 'hide'): Promise<void> {
    if (!canManage.value || !targetCurrent(row)) return;
    const captured = clone(row);
    await run(captured, () => action === 'hide' ? templatesStore.hideTemplate({ template: captured, hidden: !captured.hidden }) : Promise.resolve());
}
async function confirmAction(): Promise<void> {
    const selected = confirmation.value;
    if (!selected || selected.identity !== getTransactionStateIdentity() || !targetCurrent(selected.row)) { confirmation.value = null; return; }
    const { row, action } = selected;
    confirmation.value = null;
    await run(row, async () => {
        if (row instanceof TransactionTemplate) {
            if (!canManage.value) throw new Error('当前成员不能管理模板');
            if (action === 'delete') await templatesStore.deleteTemplate({ template: row });
            else if (action === 'pause') {
                row.scheduledFrequencyType = ScheduledTemplateFrequencyType.Disabled.type; row.scheduledFrequency = '';
                await templatesStore.saveTemplateContent({ template: row, isEdit: true, clientSessionId: '' });
            }
        } else if (row instanceof ScheduledOccurrence) {
            if (!canReview.value) throw new Error('当前成员不能处理待确认记录');
            if (action === 'confirm') { await queue.confirm(row); showToast('已确认入账；账户余额下次读取时更新。'); }
            else if (action === 'dismiss') await queue.dismiss(row);
            else if (action === 'restore') await queue.restore(row);
        }
    });
}
async function move(row: TransactionTemplate, direction: number): Promise<void> {
    if (!canManage.value || !targetCurrent(row)) return;
    const from = plans.value.findIndex(item => item.id === row.id);
    await run(row, async () => {
        await templatesStore.changeTemplateDisplayOrder({ templateType, templateId: row.id, from, to: from + direction });
        sortDirty.value = true;
    });
}
async function saveOrder(): Promise<void> {
    const row = plans.value[0];
    if (!row || !canManage.value) return;
    await run(row, async () => { await templatesStore.updateTemplateDisplayOrders({ templateType }); sortDirty.value = false; });
}
function onPageAfterIn(): void { active = true; if (!busy.value) void load(); }
function leave(): void { active = false; ++generation; confirmation.value = null; validLedgerId.value = null; }
watch(() => [ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], () => {
    ++generation; confirmation.value = null; validLedgerId.value = null; role.value = null; sortDirty.value = false;
    if (navigationTarget) {
        navigationInvalidated = true;
        navigationError.value = '账号或账本已变化，请回总览核对原待确认记录后重新打开。';
        loading.value = false;
    } else void load();
}, { flush: 'sync' });
function checkNavigationSession(): void {
    if (!navigationTarget) return;
    try { assertNavigationCurrent(); }
    catch {
        navigationInvalidated = true; ++generation; confirmation.value = null; validLedgerId.value = null; role.value = null;
        navigationError.value = '账号或账本已变化，请回总览核对原待确认记录后重新打开。';
        loading.value = false;
    }
}
window.addEventListener('storage', checkNavigationSession);
window.addEventListener('focus', checkNavigationSession);
document.addEventListener('visibilitychange', checkNavigationSession);
onUnmounted(() => {
    window.removeEventListener('storage', checkNavigationSession);
    window.removeEventListener('focus', checkNavigationSession);
    document.removeEventListener('visibilitychange', checkNavigationSession);
});
void load();
</script>

<style scoped>
.periodic-intro, .periodic-tabs, .periodic-options, .periodic-error { margin: 16px; }
.periodic-intro p { margin: 8px 0; line-height: 1.6; }
.periodic-options { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.periodic-options label { display: flex; align-items: center; gap: 8px; min-height: 44px; }
.periodic-options input { width: 18px; height: 18px; }
.periodic-card { margin: 12px 16px; }
.periodic-card h2 { margin: 0 0 8px; font-size: 18px; overflow-wrap: anywhere; }
.periodic-card p { margin: 6px 0; overflow-wrap: anywhere; }
.periodic-amount { font-variant-numeric: tabular-nums; font-size: 18px; font-weight: 600; }
.periodic-comment { white-space: pre-wrap; }
.periodic-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.periodic-actions .button, .periodic-tabs .button, .periodic-options .button, .periodic-error .button { min-height: 44px; height: auto; white-space: normal; }
.periodic-actions .button { flex: 1 1 110px; }
</style>
