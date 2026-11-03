<template>
    <section class="monthly-budget-panel" :aria-labelledby="titleId" :aria-busy="loading || saving">
        <header class="budget-header">
            <div><h3 :id="titleId">月度预算</h3><p>{{ ledgerName }} · {{ monthLabel }}</p></div>
            <div class="budget-header-actions">
                <button type="button" :aria-pressed="!showAmounts" @click="toggleAmounts">{{ showAmounts ? '隐藏预算金额' : '显示预算金额' }}</button>
                <button type="button" :disabled="loading || saving" @click="reload">{{ loading ? '读取中' : '刷新预算' }}</button>
            </div>
        </header>
        <div class="budget-month-controls">
            <button type="button" aria-label="预算上一个月" :disabled="saving || month <= 200001" @click="moveMonth(-1)">上个月</button>
            <label>预算月份<input type="month" :value="monthText" min="2000-01" max="9999-12" :disabled="saving" @change="changeMonth" /></label>
            <button type="button" aria-label="预算下一个月" :disabled="saving || month >= 999912" @click="moveMonth(1)">下个月</button>
        </div>
        <p class="budget-help">每个账本每月一份预算。只统计预算币种的支出，退款冲减支出；收入、转账和余额调整不占用预算，不折算其他币种。</p>
        <p class="budget-help">未保存输入仅在当前页面保留，关闭页面或重载后不会恢复。切换账本或月份会留在原目标，不会迁写。</p>
        <p v-if="retainedDraft" class="budget-notice" role="status">未保存输入保留在原账本与月份，返回可继续编辑。当前页面的草稿尚未上传。</p>
        <p v-if="restoredDraft" class="budget-notice" role="status">已恢复此账本本月的未保存输入。保存前将重新核验权限与分类。</p>
        <p v-if="loading" class="budget-notice" role="status">正在读取此账本的预算与成员权限…</p>
        <div v-if="budgetError || permissionError" class="budget-error" role="alert">
            <p>{{ permissionError || budgetError }}</p><button type="button" :disabled="loading || saving" @click="reload">重试读取预算与权限</button>
        </div>
        <template v-if="budgetLoaded && !permissionError && role !== null">
            <p class="budget-permission">{{ canManage ? '所有者和管理员可管理预算，所有成员可查看。' : '你可以查看此账本预算，由所有者或管理员管理。' }}</p>
            <p v-if="!currentBudget" class="budget-notice">{{ monthLabel }}尚未设置预算。{{ canManage ? '填写总额度并保存后生效。' : '请联系账本所有者或管理员设置。' }}</p>
            <div v-else class="budget-overview">
                <dl>
                    <div><dt>本月总额度 · {{ currentBudget.currency }}</dt><dd>{{ money(currentBudget.amount, currentBudget.currency) }}</dd></div>
                    <div><dt>同币种净支出</dt><dd>{{ usageLoading ? '读取中' : usageError || usage.expense === null ? '未完整计算' : money(usage.expense, currentBudget.currency) }}</dd></div>
                    <div><dt>{{ showAmounts && usageRemaining !== null && usageRemaining < 0 ? '超出额度' : '剩余额度' }}</dt><dd>{{ usageLoading ? '读取中' : usageError || usageRemaining === null ? '未完整计算' : money(Math.abs(usageRemaining), currentBudget.currency) }}</dd></div>
                </dl>
                <template v-if="!usageLoading && !usageError && usage.expense !== null && showAmounts">
                    <p class="budget-progress-label" :class="usageClass">{{ usageStatus }} · 使用率 {{ usagePercent }}%</p>
                    <progress :value="Math.max(0, Math.min(usage.expense, currentBudget.amount))" :max="currentBudget.amount" :aria-label="`本月预算使用率 ${usagePercent}%`" :class="usageClass" />
                    <p v-if="usage.expense < 0" class="budget-help">本月退款超过支出，净支出为负，因此剩余额度超过总额度。</p>
                </template>
                <p v-else-if="!showAmounts" class="budget-help">金额、使用率和进度已隐藏。</p>
                <div v-if="savedCategoryLimits.length" class="saved-category-limits">
                    <h4>分类额度与剩余</h4>
                    <p class="budget-help">分类额度独立提醒，不会相加或替代总额度。未设置分类额度的支出仍计入总预算。</p>
                    <dl>
                        <div v-for="limit in savedCategoryLimits" :key="limit.id">
                            <dt>{{ limit.name }}<small>{{ limit.parentName }}</small></dt>
                            <dd>额度 {{ money(limit.amount, currentBudget.currency) }}<small>{{ usageLoading ? '支出读取中' : usageError || usage.expense === null ? '剩余未完整计算' : categoryRemaining(limit.id, limit.amount, currentBudget.currency) }}</small></dd>
                        </div>
                    </dl>
                </div>
            </div>
            <div v-if="usageError" class="budget-error" role="alert"><p>{{ usageError }}</p><button type="button" :disabled="usageLoading || saving" @click="reloadUsage">重试读取本月支出</button></div>
            <p v-if="saveError" class="budget-error" role="alert">{{ saveError }} 输入已保留在此账本与月份，可核对后重试。</p>
            <p v-if="saveStatus" class="budget-notice" role="status">{{ saveStatus }}</p>
            <section v-if="conflict" class="budget-conflict" aria-label="预算版本冲突">
                <p role="alert">服务器预算已变化，你的输入已保留。先读取并核对服务器额度，再明确采用该版本作为保存基线。</p>
                <button type="button" :disabled="loading || saving" @click="reload">读取服务器预算核对</button>
                <template v-if="serverBudget !== undefined">
                    <p v-if="serverBudget === null">服务器当前未设置此月预算。采用后，保存会按你的输入新建预算。</p>
                    <template v-else>
                        <p>服务器总额度：{{ money(serverBudget.amount, serverBudget.currency) }} · {{ serverBudget.currency }}</p>
                        <ul v-if="Object.keys(serverBudget.categoryAmounts).length">
                            <li v-for="(amount, id) in serverBudget.categoryAmounts" :key="id">{{ categoryMap[id]?.name ?? '原分类信息未读取' }}：{{ money(amount, serverBudget.currency) }}</li>
                        </ul>
                        <p v-else>服务器未设置分类额度。</p>
                        <p>采用后保留你的输入，下次保存将替换刚才核对的总额度、币种与全部分类额度；期间服务器再变化仍会阻止覆盖。</p>
                    </template>
                    <p v-if="!showAmounts">请先显示预算金额以核对服务器额度。</p>
                    <button type="button" :disabled="!showAmounts || loading || saving || !canManage" @click="adoptServerBaseline">保留我的输入并采用此版本</button>
                </template>
            </section>
            <form v-if="canManage" class="budget-editor" @submit.prevent="save">
                <h4>{{ currentBudget ? '修改本月额度' : '设置本月额度' }}</h4>
                <p v-if="!showAmounts" class="budget-help">金额已隐藏。如需修改额度，请先点击“显示预算金额”。</p>
                <fieldset v-if="showAmounts" :disabled="loading || saving || !budgetLoaded || !!budgetError || !!permissionError">
                    <div class="budget-fields">
                        <label>预算币种<select v-model="currency" aria-label="预算币种"><option v-for="code in currencies" :key="code" :value="code">{{ code }}</option></select></label>
                        <label>本月总额度<input v-model="totalInput" type="text" inputmode="decimal" autocomplete="off" placeholder="例如 3000.00" :aria-invalid="totalInput !== '' && !validTotal" :aria-describedby="amountHelpId" /></label>
                    </div>
                    <p :id="amountHelpId" class="budget-help">请输入大于零的金额，最多两位小数，不使用千位分隔符。币种改变后，原数额保持不变，请核对后保存。</p>
                    <details class="budget-categories">
                        <summary>可选分类额度</summary>
                        <p class="budget-help">仅选择可见的末级支出分类。留空表示不单独限制；分类额度可与总额度不同。</p>
                        <div v-if="categoriesError" class="budget-error" role="alert"><p>{{ categoriesError }}</p><button type="button" :disabled="loading || saving" @click="reload">重试读取分类</button></div>
                        <p v-else-if="!categories.length" class="budget-help">没有可用的末级支出分类，仍可设置总额度。</p>
                        <div class="budget-category-inputs">
                            <label v-for="category in categories" :key="category.id">
                                <span>{{ category.name }}<small>{{ category.parentName }}</small></span>
                                <input v-model="categoryInputs[category.id]" :type="showAmounts ? 'text' : 'password'" inputmode="decimal" autocomplete="off" :aria-label="`${category.parentName} / ${category.name}分类额度（${currency}）`" placeholder="不单独限制" />
                            </label>
                        </div>
                        <div v-for="category in unavailableInputs" :key="category.id" class="unavailable-budget-category">
                            <p>{{ category.name }}：原额度 {{ category.amount === null ? '输入无效' : money(category.amount, currency) }}。此分类已停用或不可用，请明确移除后再保存。</p>
                            <button type="button" @click="removeCategoryInput(category.id)">移除此分类额度</button>
                        </div>
                    </details>
                </fieldset>
                <footer>
                    <button v-if="currentBudget" class="budget-danger" type="button" :disabled="loading || saving || conflict" @click="openRemove">清除本月限额</button>
                    <button class="budget-primary" type="submit" :disabled="!showAmounts || loading || saving || conflict || !validTotal || !!categoriesError || !!budgetError || !!permissionError">{{ saving ? '保存中' : '保存本月额度' }}</button>
                </footer>
            </form>
        </template>
        <dialog ref="removeDialog" class="budget-confirm" :aria-labelledby="removeTitleId" @cancel="cancelRemove" @close="removeContext = null">
            <form @submit.prevent="confirmRemove">
                <h3 :id="removeTitleId">清除此月预算？</h3>
                <p>{{ removeContext?.ledgerName }} · {{ removeContext ? budgetMonthText(removeContext.yearMonth) : '' }}</p>
                <p>总额度与全部分类额度将一并清除，已有流水与账户余额保留。此操作影响该账本所有成员。</p>
                <div class="budget-confirm-actions"><button type="button" :disabled="saving" @click="cancelRemove">保留预算</button><button class="budget-danger" type="submit" :disabled="saving">{{ saving ? '清除中' : '确认清除本月预算' }}</button></div>
            </form>
        </dialog>
    </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, useTemplateRef, watch } from 'vue';
import { ALL_CURRENCIES } from '@/consts/currency.ts';
import { LedgerMemberRole } from '@/models/ledger.ts';
import type { MonthlyBudgetInfoResponse } from '@/models/monthly_budget.ts';
import type { TransactionCategoryInfoResponse } from '@/models/transaction_category.ts';
import type { TransactionInfoResponse } from '@/models/transaction.ts';
import type { AccountInfoResponse } from '@/models/account.ts';
import { useMonthlyBudgetStore } from '@/stores/monthlyBudget.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useUserStore } from '@/stores/user.ts';
import { mobileLedgerRole } from '@/lib/mobile_ledger.ts';
import { getCurrentUserInfo, getTransactionStateIdentity } from '@/lib/userstate.ts';
import { exactMinorUnitSum, minorAmountToDecimal } from '@/lib/currency_totals.ts';
import {
    budgetAccountSnapshot, budgetCategorySnapshot, budgetMonthFromText, budgetMonthText,
    calculateBudgetUsage, isBudgetConflictError, parseBudgetAmount, validBudgetMonth, type BudgetCategoryOption, type BudgetUsage
} from '@/lib/monthly_budget.ts';
import services from '@/lib/services.ts';

const props = defineProps<{ ledgerId: string; ledgerName: string; yearMonth?: number }>();
const budgetStore = useMonthlyBudgetStore();
const userStore = useUserStore();
const settingsStore = useSettingsStore();
const titleId = `monthly-budget-${useId()}`;
const amountHelpId = `${titleId}-amount-help`;
const removeTitleId = `${titleId}-remove-title`;
const removeDialog = useTemplateRef<HTMLDialogElement>('removeDialog');
const now = new Date();
const month = ref(validBudgetMonth(props.yearMonth ?? 0) ? props.yearMonth! : now.getFullYear() * 100 + now.getMonth() + 1);
const monthText = computed(() => budgetMonthText(month.value));
const monthLabel = computed(() => `${Math.trunc(month.value / 100)}年${month.value % 100}月`);
const currencies = Object.keys(ALL_CURRENCIES).sort();
const defaultCurrency = () => ALL_CURRENCIES[userStore.currentUserDefaultCurrency] ? userStore.currentUserDefaultCurrency : 'CNY';
const currency = ref(defaultCurrency());
const totalInput = ref('');
const categoryInputs = ref<Record<string, string>>({});
const categories = ref<BudgetCategoryOption[]>([]);
const categoryMap = ref<Record<string, TransactionCategoryInfoResponse>>({});
const currentBudget = ref<MonthlyBudgetInfoResponse | null>(null);
const budgetLoaded = ref(false);
const loading = ref(false);
const saving = ref(false);
const role = ref<number | null>(null);
const budgetError = ref('');
const permissionError = ref('');
const categoriesError = ref('');
const saveError = ref('');
const saveStatus = ref('');
const baseRevision = ref(0);
const conflict = ref(false);
const serverBudget = ref<MonthlyBudgetInfoResponse | null | undefined>(undefined);
const usageLoading = ref(false);
const usageError = ref('');
const usage = ref<BudgetUsage>({ expense: null, categoryExpenses: {}, problem: null });
const usageItems = ref<TransactionInfoResponse[] | null>(null);
const usageCount = ref(0);
const usageAccounts = ref<Record<string, AccountInfoResponse>>({});
const restoredDraft = ref(false);
const retainedDraft = ref(false);
const showAmounts = computed(() => settingsStore.appSettings.showAmountInHomePage);
const canManage = computed(() => role.value === LedgerMemberRole.Owner || role.value === LedgerMemberRole.Admin);
const validTotal = computed(() => (parseBudgetAmount(totalInput.value) ?? 0) > 0);
const usagePercent = computed(() => currentBudget.value && usage.value.expense !== null ? Math.round(usage.value.expense / currentBudget.value.amount * 1000) / 10 : 0);
const usageRemaining = computed(() => currentBudget.value && usage.value.expense !== null ? exactMinorUnitSum([currentBudget.value.amount, -usage.value.expense]) : null);
const usageClass = computed(() => usagePercent.value > 100 ? 'budget-over' : usagePercent.value >= 80 ? 'budget-near' : '');
const usageStatus = computed(() => usagePercent.value > 100 ? '已超出预算' : usagePercent.value >= 80 ? '接近预算上限' : '预算额度内');
const savedCategoryLimits = computed(() => Object.entries(currentBudget.value?.categoryAmounts ?? {}).map(([id, amount]) => ({
    id, amount, name: categoryMap.value[id]?.name ?? '分类信息未完整读取',
    parentName: categoryMap.value[categoryMap.value[id]?.parentId ?? '']?.name ?? ''
})));
const unavailableInputs = computed(() => Object.entries(categoryInputs.value).filter(([id, input]) => input.trim() && !categories.value.some(category => category.id === id)).map(([id, input]) => ({
    id, amount: parseBudgetAmount(input), name: categoryMap.value[id]?.name ?? '原分类已不可用'
})));

interface Context { identity: string; username: string; ledgerId: string; ledgerName: string; yearMonth: number; generation: number; key: string }
interface Draft { currency: string; total: string; categories: Record<string, string>; baseRevision: number; conflict: boolean }
const drafts = new Map<string, Draft>();
let context: Context | null = null;
let generation = 0;
let usageGeneration = 0;
let cleanForm = '';
const removeContext = ref<(Context & { expectedRevision: number }) | null>(null);
const formKey = () => JSON.stringify([currency.value, totalInput.value, Object.entries(categoryInputs.value).filter(([, input]) => input.trim()).sort(([a], [b]) => a.localeCompare(b))]);
const dirty = () => cleanForm !== '' && formKey() !== cleanForm;

function isCurrent(captured: Context): boolean {
    return context?.generation === captured.generation && captured.identity === getTransactionStateIdentity() &&
        captured.username === getCurrentUserInfo()?.username && props.ledgerId === captured.ledgerId && month.value === captured.yearMonth;
}
function requireCurrent(captured: Context): void {
    if (!isCurrent(captured)) throw new Error('账本、月份或账号已变化，原操作已停止');
}
function errorMessage(error: unknown, fallback: string): string {
    if (error instanceof Error) return error.message;
    const info = error as { error?: { errorMessage?: unknown }; message?: unknown };
    return typeof info?.error?.errorMessage === 'string' ? info.error.errorMessage : typeof info?.message === 'string' ? info.message : fallback;
}
function rememberDraft(force = false): void {
    if (context && (dirty() || conflict.value || force)) {
        drafts.set(context.key, { currency: currency.value, total: totalInput.value, categories: { ...categoryInputs.value }, baseRevision: baseRevision.value, conflict: conflict.value });
        retainedDraft.value = true;
    }
}
function applyBudget(budget: MonthlyBudgetInfoResponse | null): void {
    baseRevision.value = budget?.revision ?? 0;
    currency.value = budget?.currency ?? defaultCurrency();
    totalInput.value = budget ? minorAmountToDecimal(budget.amount) : '';
    categoryInputs.value = Object.fromEntries(Object.entries(budget?.categoryAmounts ?? {}).map(([id, amount]) => [id, minorAmountToDecimal(amount)]));
    cleanForm = formKey();
}
function switchScope(): void {
    rememberDraft();
    generation++;
    usageGeneration++;
    cancelRemove();
    context = null;
    loading.value = false;
    saving.value = false;
    usageLoading.value = false;
    currentBudget.value = null;
    budgetLoaded.value = false;
    role.value = null;
    categories.value = [];
    categoryMap.value = {};
    usageItems.value = null;
    usageAccounts.value = {};
    usage.value = { expense: null, categoryExpenses: {}, problem: null };
    budgetError.value = permissionError.value = categoriesError.value = usageError.value = saveError.value = saveStatus.value = '';
    restoredDraft.value = false;
    conflict.value = false;
    serverBudget.value = undefined;
    applyBudget(null);
    const identity = getTransactionStateIdentity();
    const username = getCurrentUserInfo()?.username;
    if (!identity || !username || !/^(0|[1-9]\d*)$/.test(props.ledgerId)) {
        budgetError.value = '无法确认当前账号或账本，请重新登录后重试';
        return;
    }
    context = { identity, username, ledgerId: props.ledgerId, ledgerName: props.ledgerName, yearMonth: month.value, generation, key: JSON.stringify([identity, props.ledgerId, month.value]) };
    const draft = drafts.get(context.key);
    if (draft) {
        currency.value = draft.currency;
        totalInput.value = draft.total;
        categoryInputs.value = { ...draft.categories };
        baseRevision.value = draft.baseRevision;
        conflict.value = draft.conflict;
        restoredDraft.value = true;
    }
    void load(context);
}
async function readRole(captured: Context): Promise<number> {
    requireCurrent(captured);
    if (captured.ledgerId === '0') return LedgerMemberRole.Owner;
    const response = await services.listLedgerMembers({ ledgerId: captured.ledgerId });
    requireCurrent(captured);
    if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('成员权限未完整读取，请重试');
    return mobileLedgerRole(captured.ledgerId, response.data.result);
}
async function readCategories(captured: Context): Promise<ReturnType<typeof budgetCategorySnapshot>> {
    requireCurrent(captured);
    const response = await services.getAllTransactionCategories(captured.ledgerId);
    requireCurrent(captured);
    if (!response.data?.success || !Object.prototype.hasOwnProperty.call(response.data, 'result')) throw new Error('分类未完整读取，请重试');
    return budgetCategorySnapshot(response.data.result);
}
async function load(captured: Context): Promise<void> {
    loading.value = true;
    budgetError.value = permissionError.value = categoriesError.value = '';
    const keepInput = dirty() || conflict.value;
    if (conflict.value) serverBudget.value = undefined;
    const results = await Promise.allSettled([budgetStore.load(captured.yearMonth, true, captured.ledgerId), readRole(captured), readCategories(captured)]);
    if (!isCurrent(captured)) return;
    const [budgetResult, roleResult, categoryResult] = results;
    if (budgetResult.status === 'fulfilled') {
        currentBudget.value = budgetResult.value;
        budgetLoaded.value = true;
        if (keepInput && (budgetResult.value?.revision ?? 0) !== baseRevision.value) {
            conflict.value = true;
            serverBudget.value = budgetResult.value;
        } else if (conflict.value) {
            serverBudget.value = budgetResult.value;
        }
        if (!keepInput) {
            applyBudget(budgetResult.value);
            const draft = drafts.get(captured.key);
            if (draft) {
                currency.value = draft.currency;
                totalInput.value = draft.total;
                categoryInputs.value = { ...draft.categories };
                baseRevision.value = draft.baseRevision;
                conflict.value = draft.conflict;
                restoredDraft.value = true;
            }
        }
    } else {
        budgetError.value = errorMessage(budgetResult.reason, '预算读取失败，请重试');
    }
    if (roleResult.status === 'fulfilled') role.value = roleResult.value;
    else { role.value = null; permissionError.value = errorMessage(roleResult.reason, '无法核验当前账本成员权限，请重试'); }
    if (categoryResult.status === 'fulfilled') {
        categories.value = categoryResult.value.selectable;
        categoryMap.value = categoryResult.value.all;
    } else categoriesError.value = errorMessage(categoryResult.reason, '分类读取失败，请重试');
    loading.value = false;
    if (!permissionError.value && budgetLoaded.value) void loadUsage(captured);
}
async function loadUsage(captured: Context): Promise<void> {
    const sequence = ++usageGeneration;
    usageLoading.value = true;
    usageError.value = '';
    usage.value = { expense: null, categoryExpenses: {}, problem: null };
    try {
        const [transactions, accounts] = await Promise.all([
            services.getAllTransactionsByMonth({ ledgerId: captured.ledgerId, year: Math.trunc(captured.yearMonth / 100), month: captured.yearMonth % 100, type: 0, categoryIds: '', accountIds: '', tagFilter: '', amountFilter: '', keyword: '', matchMode: 0 }),
            services.getAllAccounts({ ledgerId: captured.ledgerId, visibleOnly: false })
        ]);
        if (!isCurrent(captured) || sequence !== usageGeneration) return;
        if (!transactions.data?.success || !Array.isArray(transactions.data.result?.items) ||
            !accounts.data?.success || !Array.isArray(accounts.data.result)) throw new Error('本月流水或账户未完整读取，不能计算为零');
        usageAccounts.value = budgetAccountSnapshot(accounts.data.result, captured.ledgerId);
        usageItems.value = transactions.data.result.items;
        usageCount.value = transactions.data.result.totalCount;
        updateUsage();
    } catch (error) {
        if (!isCurrent(captured) || sequence !== usageGeneration) return;
        usageItems.value = null;
        usageError.value = errorMessage(error, '本月支出读取失败，请重试');
    } finally {
        if (isCurrent(captured) && sequence === usageGeneration) usageLoading.value = false;
    }
}
function updateUsage(): void {
    if (!context || !usageItems.value) return;
    if (categoriesError.value) { usageError.value = '支出分类未完整读取，无法计算使用率'; return; }
    usage.value = calculateBudgetUsage(usageItems.value, usageCount.value, usageAccounts.value, categoryMap.value, context.ledgerId, currentBudget.value?.currency ?? currency.value);
    usageError.value = usage.value.problem ?? '';
}
function reload(): void { if (context && !loading.value && !saving.value) void load(context); else if (!context) switchScope(); }
function reloadUsage(): void { if (context && !usageLoading.value && !saving.value) void loadUsage(context); }
function moveMonth(delta: number): void {
    if (saving.value) return;
    const date = new Date(Math.trunc(month.value / 100), month.value % 100 - 1 + delta, 1);
    const next = date.getFullYear() * 100 + date.getMonth() + 1;
    if (validBudgetMonth(next)) month.value = next;
}
function changeMonth(event: Event): void {
    const input = event.target as HTMLInputElement;
    const next = budgetMonthFromText(input.value);
    if (next === null || saving.value) { input.value = monthText.value; return; }
    month.value = next;
}
function money(amount: number, code: string): string { return showAmounts.value ? `${code} ${minorAmountToDecimal(amount)}` : '******'; }
function categoryRemaining(id: string, amount: number, code: string): string {
    if (!showAmounts.value) return '剩余 ******';
    const remaining = exactMinorUnitSum([amount, -(usage.value.categoryExpenses[id] ?? 0)]);
    if (remaining === null) return '剩余超出可精确汇总范围';
    return `${remaining < 0 ? '超出' : '剩余'} ${money(Math.abs(remaining), code)}`;
}
function toggleAmounts(): void { settingsStore.setShowAmountInHomePage(!showAmounts.value); }
function removeCategoryInput(id: string): void { delete categoryInputs.value[id]; saveError.value = ''; }
function adoptServerBaseline(): void {
    if (!context || !isCurrent(context) || loading.value || saving.value || !canManage.value || !showAmounts.value || serverBudget.value === undefined) return;
    const adoptedBudget = serverBudget.value;
    baseRevision.value = adoptedBudget?.revision ?? 0;
    cleanForm = JSON.stringify([adoptedBudget?.currency ?? defaultCurrency(), adoptedBudget ? minorAmountToDecimal(adoptedBudget.amount) : '', Object.entries(adoptedBudget?.categoryAmounts ?? {}).map(([id, amount]) => [id, minorAmountToDecimal(amount)]).sort(([a], [b]) => a!.localeCompare(b!))]);
    conflict.value = false;
    serverBudget.value = undefined;
    saveError.value = '';
    saveStatus.value = '已采用核对过的服务器版本，保留了你的输入。请核对并点击“保存本月额度”完成替换。';
    rememberDraft(true);
}
async function save(): Promise<void> {
    if (!context || conflict.value || !showAmounts.value || saving.value || loading.value || !canManage.value || !budgetLoaded.value) return;
    const captured = { ...context };
    const total = parseBudgetAmount(totalInput.value);
    const selectedCurrency = currency.value;
    const submittedInputs = { ...categoryInputs.value };
    const submittedRevision = baseRevision.value;
    if (total === null || total <= 0 || !ALL_CURRENCIES[selectedCurrency]) { saveError.value = '请填写有效的币种与大于零的总额度'; return; }
    saving.value = true;
    saveError.value = saveStatus.value = '';
    try {
        const [liveRole, liveCategories] = await Promise.all([readRole(captured), readCategories(captured)]);
        requireCurrent(captured);
        role.value = liveRole;
        categories.value = liveCategories.selectable;
        categoryMap.value = liveCategories.all;
        if (liveRole !== LedgerMemberRole.Owner && liveRole !== LedgerMemberRole.Admin) throw new Error('当前成员权限已变化，只有所有者和管理员可保存预算');
        const amounts: Record<string, number> = {};
        for (const [id, input] of Object.entries(submittedInputs)) {
            if (!input.trim()) continue;
            const amount = parseBudgetAmount(input);
            if (amount === null || amount <= 0) throw new Error('分类额度必须大于零且最多两位小数；不限制时请留空');
            if (!liveCategories.selectable.some(category => category.id === id)) throw new Error('部分分类已不可用，请明确移除原分类额度后再保存');
            amounts[id] = amount;
        }
        requireCurrent(captured);
        const saved = await budgetStore.save(captured.yearMonth, total, amounts, captured.ledgerId, selectedCurrency, submittedRevision);
        requireCurrent(captured);
        currentBudget.value = saved;
        applyBudget(saved);
        drafts.delete(captured.key);
        restoredDraft.value = false;
        conflict.value = false;
        serverBudget.value = undefined;
        saveStatus.value = '本月预算已保存。';
        void loadUsage(captured);
    } catch (error) {
        if (isCurrent(captured)) {
            if (isBudgetConflictError(error)) {
                conflict.value = true;
                serverBudget.value = undefined;
                saveError.value = '服务器预算已变化，请读取服务器预算并核对后再保存。';
                rememberDraft();
            } else saveError.value = errorMessage(error, '预算保存失败，请核对后重试');
        }
    } finally {
        if (isCurrent(captured)) saving.value = false;
    }
}
function openRemove(): void {
    if (!context || conflict.value || !canManage.value || saving.value || !currentBudget.value) return;
    removeContext.value = { ...context, expectedRevision: baseRevision.value };
    removeDialog.value?.showModal();
}
function cancelRemove(): void { removeDialog.value?.close(); removeContext.value = null; }
async function confirmRemove(): Promise<void> {
    const captured = removeContext.value;
    if (!captured || saving.value) return;
    saving.value = true;
    saveError.value = saveStatus.value = '';
    try {
        requireCurrent(captured);
        const liveRole = await readRole(captured);
        requireCurrent(captured);
        role.value = liveRole;
        if (liveRole !== LedgerMemberRole.Owner && liveRole !== LedgerMemberRole.Admin) throw new Error('当前成员权限已变化，无法清除此账本预算');
        await budgetStore.remove(captured.yearMonth, captured.ledgerId, captured.expectedRevision);
        requireCurrent(captured);
        currentBudget.value = null;
        applyBudget(null);
        drafts.delete(captured.key);
        restoredDraft.value = false;
        conflict.value = false;
        serverBudget.value = undefined;
        saveStatus.value = '本月预算已清除，已有流水与余额保留。';
    } catch (error) {
        if (isCurrent(captured)) {
            if (isBudgetConflictError(error)) {
                conflict.value = true;
                serverBudget.value = undefined;
                saveError.value = '服务器预算已变化，本次没有清除，请读取服务器预算核对。';
                rememberDraft();
            } else saveError.value = errorMessage(error, '预算清除失败，请重试');
        }
    } finally {
        if (isCurrent(captured)) saving.value = false;
        if (removeContext.value?.generation === captured.generation) cancelRemove();
    }
}
function beforeUnload(event: BeforeUnloadEvent): void {
    if (!dirty()) return;
    event.preventDefault();
    event.returnValue = '';
}
watch(() => props.yearMonth, value => { if (value !== undefined && validBudgetMonth(value)) month.value = value; });
watch(() => [props.ledgerId, month.value, userStore.currentUserBasicInfo?.username], switchScope, { immediate: true, flush: 'sync' });
window.addEventListener('beforeunload', beforeUnload);
onBeforeUnmount(() => { rememberDraft(); generation++; usageGeneration++; cancelRemove(); window.removeEventListener('beforeunload', beforeUnload); });
</script>

<style scoped>
.monthly-budget-panel {
    --budget-surface: var(--surface, var(--f7-block-strong-bg-color, Canvas));
    --budget-ink: var(--ink, var(--f7-text-color, CanvasText));
    --budget-copy: color-mix(in srgb, var(--card-copy, var(--muted, var(--f7-block-footer-text-color, CanvasText))) 70%, var(--budget-ink));
    --budget-border: var(--line, var(--f7-list-item-border-color, GrayText));
    --budget-error: color-mix(in srgb, var(--f7-color-red, rgb(var(--v-theme-error))) 75%, var(--budget-ink));
    --budget-warning: color-mix(in srgb, var(--f7-color-orange, rgb(var(--v-theme-warning))) 70%, var(--budget-ink));
    display: grid; gap: 16px; padding: 16px; margin-block: 4px 18px;
    border: 1px solid var(--budget-border); border-radius: 16px;
    color: var(--budget-ink); background: var(--budget-surface);
    font-family: var(--ledger-font-body, inherit); font-synthesis: none;
    font-size: max(14px, 1em); line-height: 1.6; min-width: 0;
}
.monthly-budget-panel :is(h3, h4, p, dl, dd, fieldset) { margin: 0; }
.monthly-budget-panel :is(h3, h4) { font: inherit; font-weight: 650; }
.monthly-budget-panel :is(button, input, select, summary) { font: inherit; color: inherit; -webkit-text-fill-color: currentColor; }
.monthly-budget-panel :is(button, input, select) { box-sizing: border-box; min-height: 44px; min-width: 0; border: 1px solid var(--budget-border); border-radius: 11px; padding: 8px 12px; background: var(--budget-surface); }
.monthly-budget-panel button { min-width: 44px; cursor: pointer; white-space: normal; overflow-wrap: anywhere; }
.monthly-budget-panel :is(button, input, select, summary):focus-visible { outline: 2px solid currentColor; outline-offset: 3px; }
.monthly-budget-panel :disabled { opacity: .55; cursor: default; }
.monthly-budget-panel fieldset { display: grid; gap: 12px; border: 0; padding: 0; min-width: 0; }
.monthly-budget-panel input, .monthly-budget-panel select { width: 100%; }
.monthly-budget-panel input { font-variant-numeric: tabular-nums; }
.budget-header, .budget-header-actions, .budget-month-controls, .budget-editor footer, .budget-confirm-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.budget-header { justify-content: space-between; align-items: flex-start; }
.budget-header > div:first-child { min-width: 0; overflow-wrap: anywhere; }
.budget-header-actions { flex-shrink: 0; }
.budget-header p, .budget-help, .budget-permission, .monthly-budget-panel small { color: var(--budget-copy); font-size: max(13px, .93em); }
.budget-month-controls label, .budget-fields label { display: grid; gap: 4px; min-width: 0; }
.budget-month-controls label { flex: 1 1 160px; }
.budget-overview, .budget-editor { display: grid; gap: 12px; }
.budget-overview dl { display: grid; gap: 12px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.budget-overview dl > div { min-width: 0; overflow-wrap: anywhere; }
.budget-overview dt { color: var(--budget-copy); }
.budget-overview dd { font-variant-numeric: tabular-nums; font-weight: 600; }
.monthly-budget-panel small { display: block; font-weight: 400; }
.budget-overview progress { width: 100%; height: 10px; accent-color: var(--budget-ink); }
.budget-near { color: var(--budget-warning); accent-color: var(--budget-warning) !important; }
.budget-over { color: var(--budget-error); accent-color: var(--budget-error) !important; }
.saved-category-limits { display: grid; gap: 8px; border-top: 1px solid var(--budget-border); padding-top: 12px; }
.saved-category-limits dl { display: grid; grid-template-columns: 1fr; gap: 0; }
.saved-category-limits dl > div { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; padding-block: 10px; border-bottom: 1px solid var(--budget-border); }
.saved-category-limits dd { text-align: end; }
.budget-fields { display: grid; grid-template-columns: minmax(100px, 1fr) minmax(160px, 2fr); gap: 12px; }
.budget-categories summary { min-height: 44px; display: list-item; align-content: center; cursor: pointer; font-weight: 600; }
.budget-category-inputs { display: grid; gap: 0; }
.budget-category-inputs label { display: grid; grid-template-columns: minmax(0, 1fr) minmax(140px, 180px); gap: 12px; align-items: center; padding-block: 10px; border-bottom: 1px solid var(--budget-border); }
.budget-category-inputs label > span { min-width: 0; overflow-wrap: anywhere; }
.budget-notice, .budget-error, .budget-conflict, .unavailable-budget-category { border: 1px solid var(--budget-border); border-radius: 12px; padding: 12px; overflow-wrap: anywhere; }
.budget-conflict { display: grid; gap: 12px; }
.budget-error { color: var(--budget-error); display: grid; gap: 8px; }
.budget-editor footer { justify-content: flex-end; }
.budget-primary { background: var(--budget-ink) !important; color: var(--budget-surface) !important; border-color: var(--budget-ink) !important; }
.budget-danger { color: var(--budget-error) !important; }
.budget-confirm { max-width: min(480px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); overflow-y: auto; padding: 24px; margin: auto; border: 1px solid var(--budget-border); border-radius: 18px; color: var(--budget-ink); background: var(--budget-surface); }
.budget-confirm::backdrop { background: color-mix(in srgb, CanvasText 50%, transparent); }
.budget-confirm form { display: grid; gap: 16px; }
.budget-confirm-actions { justify-content: flex-end; }
@media (max-width: 600px) {
    .budget-fields, .budget-overview dl, .budget-category-inputs label { grid-template-columns: minmax(0, 1fr); }
    .budget-header-actions { width: 100%; flex-shrink: 1; }
    .budget-header-actions button { flex: 1 1 120px; }
    .budget-editor footer > button { flex: 1 1 160px; }
    .saved-category-limits dl > div { flex-direction: column; gap: 4px; }
    .saved-category-limits dd { text-align: start; }
}
</style>
