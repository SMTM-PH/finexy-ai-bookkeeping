<template>
    <div class="finance-home">
        <div class="app">
            <header class="topbar">
                <div class="brand"><span class="logo">F</span>Finexy</div>
                <nav class="tabs" aria-label="主导航">
                    <router-link class="active" to="/">总览</router-link>
                    <router-link to="/transaction/list?pageType=1&dateType=7">流水</router-link>
                    <router-link to="/account/list">管理</router-link>
                    <router-link to="/schedule/list">计划</router-link>
                    <router-link to="/user/settings">账户</router-link>
                    <router-link to="/statistics/transaction">报表</router-link>
                </nav>
                <div class="top-actions">
                    <button class="ic" :class="{active:utilityPanel==='search'}" type="button" aria-label="搜索" :aria-expanded="utilityPanel==='search'" @click="toggleUtilityPanel('search')"><v-icon :icon="mdiMagnify" size="17"/></button>
                    <button class="ic" :class="{active:utilityPanel==='notifications'}" type="button" aria-label="通知" :aria-expanded="utilityPanel==='notifications'" @click="toggleUtilityPanel('notifications')"><v-icon :icon="mdiBellOutline" size="17"/><span v-if="attentionActivities.length" class="dot"></span></button>
                    <button class="ic" :class="{active:utilityPanel==='help'}" type="button" aria-label="帮助与信息" :aria-expanded="utilityPanel==='help'" @click="toggleUtilityPanel('help')"><v-icon :icon="mdiInformationOutline" size="17"/></button>
                    <button class="user" type="button" :aria-expanded="accountMenuOpen" @click="toggleAccountMenu">
                        <span class="avatar">{{ currentUserInitial }}</span>
                        <span class="uinfo"><b>{{ currentUserName }}</b><small>个人账本</small></span>
                        <v-icon :icon="mdiChevronDown" size="14"/>
                    </button>
                    <transition name="account-drop">
                        <section v-if="utilityPanel==='search'" class="utility-panel search-panel">
                            <header><div><b>全局搜索</b><small>查找交易或前往常用功能</small></div><button type="button" aria-label="关闭" @click="utilityPanel=null">×</button></header>
                            <label class="utility-search"><v-icon :icon="mdiMagnify" size="17"/><input v-model.trim="globalSearchQuery" autofocus placeholder="输入订单号、活动或状态" @keydown.enter="submitGlobalSearch"></label>
                            <div class="utility-results">
                                <small>{{ globalSearchQuery ? '匹配的近期活动' : '近期活动' }}</small>
                                <button v-for="row in globalSearchResults" :key="row.id" type="button" @click="openSearchResult(row)"><i :style="{background:row.color}">{{ row.icon }}</i><span><b>{{ row.name }}</b><small>{{ row.id }} · {{ row.status }}</small></span><strong>{{ row.price }}</strong></button>
                                <p v-if="!globalSearchResults.length">没有找到匹配内容</p>
                            </div>
                            <button class="utility-primary" type="button" @click="submitGlobalSearch">在全部流水中搜索</button>
                        </section>
                    </transition>
                    <transition name="account-drop">
                        <section v-if="utilityPanel==='notifications'" class="utility-panel notification-panel">
                            <header><div><b>通知</b><small>{{ attentionActivities.length ? `${attentionActivities.length} 项需要关注` : '没有需要处理的事项' }}</small></div><button type="button" aria-label="关闭" @click="utilityPanel=null">×</button></header>
                            <div v-if="attentionActivities.length" class="notice-list">
                                <button v-for="row in attentionActivities" :key="row.id" type="button" @click="openNotification(row)"><i :class="row.statusClass"></i><span><b>{{ row.name }}</b><small>{{ row.status }} · {{ row.date }}</small></span><v-icon :icon="mdiChevronRight" size="16"/></button>
                            </div>
                            <div v-else class="utility-empty"><v-icon :icon="mdiBellOutline" size="22"/><b>全部处理完毕</b><small>新的提醒会显示在这里</small></div>
                            <router-link class="utility-primary" to="/transaction/list">查看全部流水</router-link>
                        </section>
                    </transition>
                    <transition name="account-drop">
                        <section v-if="utilityPanel==='help'" class="utility-panel help-panel">
                            <header><div><b>帮助与信息</b><small>了解功能与应用状态</small></div><button type="button" aria-label="关闭" @click="utilityPanel=null">×</button></header>
                            <router-link to="/about"><v-icon :icon="mdiInformationOutline" size="18"/><span><b>关于 Finexy</b><small>版本、隐私与运行状态</small></span><v-icon :icon="mdiChevronRight" size="16"/></router-link>
                            <router-link to="/app/settings"><v-icon :icon="mdiCogOutline" size="18"/><span><b>应用设置</b><small>语言、显示与数据管理</small></span><v-icon :icon="mdiChevronRight" size="16"/></router-link>
                            <router-link to="/schedule/list"><v-icon :icon="mdiHelpCircleOutline" size="18"/><span><b>计划与提醒</b><small>管理周期交易和待办事项</small></span><v-icon :icon="mdiChevronRight" size="16"/></router-link>
                        </section>
                    </transition>
                    <transition name="account-drop">
                        <div v-if="accountMenuOpen" class="account-menu">
                            <div class="account-current">
                                <span class="account-avatar">{{ currentUserInitial }}</span>
                                <div><small>当前账号</small><strong>{{ currentUserName }}</strong></div>
                                <i>使用中</i>
                            </div>
                            <router-link to="/user/settings"><v-icon :icon="mdiCogOutline" size="17"/><span><b>账号设置</b><small>资料、安全与显示偏好</small></span></router-link>
                            <button type="button" @click="switchAccount"><v-icon :icon="mdiSwapHorizontal" size="17"/><span><b>切换账号</b><small>登录另一个已有账号</small></span></button>
                            <button type="button" :disabled="accountActionBusy" @click="logoutAccount"><v-icon :icon="mdiLogout" size="17"/><span><b>{{ accountActionBusy ? '正在退出' : '退出登录' }}</b><small>结束当前账号会话</small></span></button>
                        </div>
                    </transition>
                </div>
            </header>

            <div class="body">
                <aside class="rail">
                    <div class="stack">
                        <router-link class="ric" to="/category/list" aria-label="交易分类" title="交易分类"><v-icon :icon="mdiLayersOutline" size="18"/></router-link>
                        <router-link class="ric" to="/tag/list" aria-label="交易标签" title="交易标签"><v-icon :icon="mdiTagOutline" size="18"/></router-link>
                        <router-link class="ric" to="/template/list" aria-label="交易模板" title="交易模板"><v-icon :icon="mdiFileDocumentOutline" size="18"/></router-link>
                        <router-link class="ric" to="/exchange_rates" aria-label="汇率数据" title="汇率数据"><v-icon :icon="mdiWeb" size="18"/></router-link>
                        <router-link class="ric" to="/product/assets" aria-label="产品资产" title="产品资产"><v-icon :icon="mdiPackageVariantClosed" size="18"/></router-link>
                    </div>
                    <div class="stack bottom">
                        <router-link class="ric" to="/app/settings" aria-label="应用设置" title="应用设置"><v-icon :icon="mdiCogOutline" size="18"/></router-link>
                        <router-link class="ric" to="/about" aria-label="帮助与关于" title="帮助与关于"><v-icon :icon="mdiHelpCircleOutline" size="18"/></router-link>
                    </div>
                </aside>

                <main>
                    <h1>早上好，朋友</h1>
                    <p class="sub">掌握任务进度，随时了解财务状态。</p>

                    <div class="grid">
                        <section class="card balance-card">
                            <div class="bhead">
                                <span>总余额</span>
                                <div class="currency-switch">
                                    <button class="cur" :class="{active:balanceCurrencyMenuOpen}" type="button" :aria-expanded="balanceCurrencyMenuOpen" @click="balanceCurrencyMenuOpen=!balanceCurrencyMenuOpen"><span class="flag" :class="currencyFlag(balanceCurrency)"></span>{{ balanceCurrency }} <v-icon :icon="mdiChevronDown" size="12"/></button>
                                    <transition name="account-drop"><div v-if="balanceCurrencyMenuOpen" class="balance-menu">
                                        <small>余额显示币种</small>
                                        <button v-for="currency in balanceCurrencies" :key="currency" type="button" :class="{active:balanceCurrency===currency}" @click="selectBalanceCurrency(currency)"><span class="flag" :class="currencyFlag(currency)"></span><span><b>{{ currency }}</b><small>{{ currency===defaultCurrency?'本位币汇总':'同币种账户小计' }}</small></span><v-icon v-if="balanceCurrency===currency" :icon="mdiCheck" size="16"/></button>
                                        <div class="balance-menu-actions"><button type="button" @click="toggleBalanceVisibility"><v-icon :icon="showAmountInHomePage?mdiEyeOffOutline:mdiEyeOutline" size="16"/>{{ showAmountInHomePage?'隐藏金额':'显示金额' }}</button><button type="button" @click="refreshBalance"><v-icon :icon="mdiRefresh" size="16"/>刷新余额</button></div>
                                    </div></transition>
                                </div>
                            </div>
                            <div class="amount">{{ loadingOverview ? '—' : balanceDisplayAmount }}</div>
                            <div class="trend balance-meta"><b><i></i>实时汇总</b><span>{{ balanceAccountSummary }}</span></div>
                            <div class="bactions">
                                <button class="btn-dark" @click="openQuickTransaction(TransactionType.Expense)"><v-icon :icon="mdiMinusCircleOutline" size="16"/>记录支出</button>
                                <button class="btn-light" @click="openQuickTransaction(TransactionType.Income)"><v-icon :icon="mdiPlusCircleOutline" size="16"/>记录收入</button>
                            </div>
                            <div class="wallets">
                                <div class="whead"><b>钱包</b><span>|&nbsp;&nbsp;共 {{ displayAccountCount }} 个账户</span><button class="wallet-add" type="button" @click="openAccountEditor()">+ 添加账户</button></div>
                                <div v-if="displayWallets.length" class="wrow">
                                    <button v-for="account in displayWallets" :key="account.id" class="w" type="button" :aria-label="`查看${account.name}账户详情`" @click="openWalletDetails(account.raw)">
                                        <span class="wt"><span class="flag" :class="account.flag"></span><b>{{ account.code }}</b><i>⋮</i></span>
                                        <strong>{{ showAmountInHomePage?account.amount:'******' }}</strong><small>{{ account.name }} · {{ account.note }}</small><em :class="account.active ? 'ok' : 'off'">{{ account.active ? '正常' : '停用' }}</em>
                                    </button>
                                </div>
                                <div v-else class="wallet-empty"><span>还没有可用账户</span><button type="button" @click="openAccountEditor()">添加第一个账户</button></div>
                            </div>
                        </section>

                        <section class="panel stats">
                            <div class="stat orange">
                                <div class="shead"><span>总收益</span><i class="sic"><v-icon :icon="mdiWalletOutline" size="15"/></i></div>
                                <strong>{{ thisMonthIncome }}</strong><div class="strend"><b>↑ 7%</b><span>本月</span></div>
                            </div>
                            <div class="stat">
                                <div class="shead"><span>总支出</span><i class="sic"><v-icon :icon="mdiCreditCardOutline" size="15"/></i></div>
                                <strong>{{ thisMonthExpense }}</strong><div class="strend down"><b>↓ 5%</b><span>本月</span></div>
                            </div>
                            <div class="stat">
                                <div class="shead"><span>总收入</span><i class="sic"><v-icon :icon="mdiCashMultiple" size="15"/></i></div>
                                <strong>{{ thisMonthIncome }}</strong><div class="strend up"><b>↑ 8%</b><span>本月</span></div>
                            </div>
                            <div class="stat">
                                <div class="shead"><span>总营收</span><i class="sic"><v-icon :icon="mdiSackPercent" size="15"/></i></div>
                                <strong>{{ totalAssets }}</strong><div class="strend up"><b>↑ 4%</b><span>本月</span></div>
                            </div>
                        </section>

                        <section class="card chart-card">
                            <h3>总收入</h3><p class="csub">查看指定时间段内的收入</p>
                            <div class="plot-wrap">
                                <div class="plot-head"><b>盈亏情况</b><span class="legend"><i class="b"></i>盈利<i class="k"></i>亏损</span></div>
                                <div class="plot">
                                    <div v-for="top in [0,20,40,60,80]" :key="top" class="gl" :style="{top: `${top}%`}"></div><div class="gl gl-bottom"></div>
                                    <span v-for="(label,index) in ['50k','40k','30k','20k','10k']" :key="label" class="ylab" :style="{top: `${index*20}%`}">{{ label }}</span><span class="ylab ylab-bottom">00</span>
                                    <div class="bars"><div v-for="bar in chartBars" :key="bar.month" class="grp"><i class="p" :style="{height: `${bar.profit}%`}"></i><i class="l" :style="{height: `${bar.loss}%`}"></i><span>{{ bar.month }}</span></div></div>
                                </div>
                            </div>
                        </section>

                        <div class="col-left">
                            <section class="card limit" @click="router.push('/schedule/list?panel=budget')">
                                <h3>每月支出限额</h3>
                                <div class="limitbar"><div class="used" :style="{width: `${Math.min(budgetPercentage || 25.5,100)}%`}"></div><div class="rest"></div></div>
                                <div class="limitmeta"><span><b>{{ formatBudgetMoney(currentMonthExpense) }}</b> 已支出</span><b>{{ monthlyBudget ? formatBudgetMoney(monthlyBudget.amount) : '¥5,500.00' }}</b></div>
                            </section>
                            <section class="card finance-tasks">
                                <div class="mhead"><div><h3><v-icon :icon="mdiFileDocumentOutline" size="17"/>财务待办</h3><p>只汇总需要处理的事项，不重复展示钱包账户</p></div><button class="add" @click="router.push('/transaction/list')">查看流水</button></div>
                                <div class="task-list">
                                    <button type="button" :class="{done:pendingReviewCount===0}" @click="router.push({path:'/transaction/list',query:{search:'待确认'}})"><i class="task-icon pending">待</i><span><b>{{ pendingReviewCount ? '待确认记录' : '待确认队列已清空' }}</b><small>{{ pendingReviewCount ? '核对识别内容，完成入账或忽略' : '新识别出的异常记录会显示在这里' }}</small></span><strong>{{ pendingReviewCount ? `${pendingReviewCount} 笔` : '已完成' }}</strong><v-icon :icon="mdiChevronRight" size="17"/></button>
                                    <button type="button" @click="router.push('/schedule/list')"><i class="task-icon plan">计</i><span><b>周期计划</b><small>查看即将执行的固定收支</small></span><strong>查看</strong><v-icon :icon="mdiChevronRight" size="17"/></button>
                                </div>
                            </section>
                        </div>

                        <section class="card activities">
                            <div class="ahead"><h3>近期活动</h3><span v-if="selectedActivityCount" class="selected-summary">已选择 {{ selectedActivityCount }} 笔<button type="button" @click="clearActivitySelection">取消选择</button></span><label class="searchbox"><v-icon :icon="mdiMagnify" size="14"/><input v-model.trim="activityQuery" placeholder="搜索流水" aria-label="搜索近期活动"></label><button class="filter" :class="{active:activityFilterOpen}" :aria-expanded="activityFilterOpen" @click="activityFilterOpen=!activityFilterOpen"><v-icon :icon="mdiFilterOutline" size="14"/>筛选</button></div>
                            <transition name="account-drop"><div v-if="activityFilterOpen" class="activity-filters"><span>交易类型</span><button v-for="item in activityTypes" :key="item.value" type="button" :class="{active:activityType===item.value}" @click="activityType=item.value">{{ item.label }}</button><button class="reset-filter" type="button" @click="resetActivityFilters">清除</button></div></transition>
                            <div class="activity-table-wrap"><table><thead><tr><th class="check-col"><input v-model="allActivitiesSelected" :indeterminate="someActivitiesSelected" type="checkbox" class="cb" aria-label="全选近期活动"></th><th>流水号</th><th>活动</th><th>类型</th><th>金额</th><th>状态</th><th>日期</th><th class="more-col"></th></tr></thead>
                                <tbody><tr v-for="row in filteredActivities" :key="`${row.id}-${row.name}`" tabindex="0" @click="openActivity(row)" @keyup.enter="openActivity(row)"><td @click.stop><input v-model="row.selected" type="checkbox" class="cb" :aria-label="`选择 ${row.id}`"></td><td class="id">{{ row.id }}</td><td><span class="act"><span class="aic" :style="{background:row.color}">{{ row.icon }}</span>{{ row.name }}</span></td><td><span class="type-pill">{{ row.typeLabel }}</span></td><td class="price" :class="{income:row.type==='income'}">{{ row.price }}</td><td><span class="status"><i :class="row.statusClass"></i>{{ row.status }}</span></td><td class="date">{{ row.date }}</td><td class="row-actions"><button class="more" :aria-label="`${row.id} 更多操作`" :aria-expanded="activityMenuId===row.id" @click.stop="activityMenuId=activityMenuId===row.id?null:row.id"><v-icon :icon="mdiDotsHorizontal" size="16"/></button><div v-if="activityMenuId===row.id" class="activity-menu"><button type="button" @click.stop="openActivity(row)">查看详情</button><button type="button" @click.stop="viewActivityInList(row)">在流水中查看</button></div></td></tr><tr v-if="!filteredActivities.length"><td class="activity-empty" colspan="8"><b>{{ activityRows.length?'没有匹配的近期活动':'本月还没有流水' }}</b><span>{{ activityRows.length?'调整搜索或筛选条件后重试':'记录一笔支出或收入后会显示在这里' }}</span><button v-if="!activityRows.length" type="button" @click="router.push('/transaction/list?action=expense')">记录第一笔流水</button></td></tr></tbody>
                            </table></div>
                            <footer class="activity-footer"><span>显示 {{ filteredActivities.length }} 笔{{ activityRows.length?`，本月共 ${activityRows.length} 笔`:'' }}</span><button type="button" @click="router.push('/transaction/list')">查看全部流水<v-icon :icon="mdiChevronRight" size="15"/></button></footer>
                        </section>
                    </div>
                </main>
            </div>
        </div>

        <Teleport to="body"><transition name="drawer"><div v-if="selectedWallet" class="balance-overlay" @click.self="closeWalletDetails">
            <aside class="balance-drawer" role="dialog" aria-modal="true" aria-labelledby="wallet-detail-title">
                <button class="drawer-close" type="button" aria-label="关闭账户详情" @click="closeWalletDetails">×</button>
                <span class="drawer-mark" :class="currencyFlag(selectedWallet.currency)">{{ selectedWallet.name.slice(0,1) }}</span>
                <small>账户详情</small><h2 id="wallet-detail-title">{{ selectedWallet.name }}</h2><p>{{ walletCategoryLabel(selectedWallet.category) }} · {{ selectedWallet.currency }}</p>
                <strong class="drawer-balance">{{ showAmountInHomePage?formatWalletBalance(selectedWallet.balance,selectedWallet.currency):'******' }}</strong>
                <dl><div><dt>账户状态</dt><dd><i :class="selectedWallet.hidden?'off':'ok'"></i>{{ selectedWallet.hidden?'已停用':'正常' }}</dd></div><div><dt>币种</dt><dd>{{ selectedWallet.currency }}</dd></div><div><dt>账户说明</dt><dd>{{ selectedWallet.comment||'暂无说明' }}</dd></div></dl>
                <div class="drawer-actions"><button class="btn-dark" type="button" @click="openQuickTransaction(TransactionType.Expense,selectedWallet)">记录支出</button><button class="btn-light" type="button" @click="openQuickTransaction(TransactionType.Income,selectedWallet)">记录收入</button></div>
                <button class="drawer-edit" type="button" @click="openAccountEditor(selectedWallet)"><v-icon :icon="mdiPencilOutline" size="16"/>编辑账户</button>
            </aside>
        </div></transition></Teleport>

    <snack-bar ref="snackbar" />
    <TransactionEditDialog ref="transactionEditDialog" :type="TransactionEditPageType.Transaction"/>
    <AccountEditDialog ref="accountEditDialog"/>
    </div>
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';
import TransactionEditDialog from '@/views/desktop/transactions/list/dialogs/EditDialog.vue';
import AccountEditDialog from '@/views/desktop/accounts/list/dialogs/EditDialog.vue';
import { TransactionEditPageType } from '@/views/base/transactions/TransactionEditPageBase.ts';

import { ref, computed, useTemplateRef } from 'vue';
import { useRouter } from 'vue-router';

import { useI18n } from '@/locales/helpers.ts';
import { useHomePageBase } from '@/views/base/HomePageBase.ts';

import { useAccountsStore } from '@/stores/account.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useOverviewStore } from '@/stores/overview.ts';
import { useMonthlyBudgetStore } from '@/stores/monthlyBudget.ts';
import { useTransactionsStore } from '@/stores/transaction.ts';
import { useAIReviewItemsStore } from '@/stores/aiReviewItem.ts';
import { useRootStore } from '@/stores/index.ts';
import { useUserStore } from '@/stores/user.ts';
import { AccountCategory } from '@/core/account.ts';
import type { Account } from '@/models/account.ts';
import { isUserLogined, isUserUnlocked } from '@/lib/userstate.ts';
import type { MonthlyBudgetInfoResponse } from '@/models/monthly_budget.ts';
import { Transaction } from '@/models/transaction.ts';
import { TransactionType } from '@/core/transaction.ts';

import {
    mdiWalletOutline,
    mdiMagnify,
    mdiBellOutline,
    mdiInformationOutline,
    mdiChevronDown,
    mdiChevronRight,
    mdiFileDocumentOutline,
    mdiLayersOutline,
    mdiTagOutline,
    mdiWeb,
    mdiPackageVariantClosed,
    mdiCogOutline,
    mdiHelpCircleOutline,
    mdiSwapHorizontal,
    mdiCreditCardOutline,
    mdiCashMultiple,
    mdiSackPercent,
    mdiFilterOutline,
    mdiDotsHorizontal,
    mdiLogout,
    mdiCheck,
    mdiEyeOutline,
    mdiEyeOffOutline,
    mdiRefresh,
    mdiMinusCircleOutline,
    mdiPlusCircleOutline,
    mdiPencilOutline
} from '@mdi/js';

type SnackBarType = InstanceType<typeof SnackBar>;
type TransactionDialogType = InstanceType<typeof TransactionEditDialog>;
type AccountDialogType = InstanceType<typeof AccountEditDialog>;
type ActivityType = 'all' | 'expense' | 'income' | 'transfer';
interface ActivityRow {
    id: string;
    transactionId: string;
    icon: string;
    color: string;
    name: string;
    price: string;
    status: string;
    statusClass: string;
    date: string;
    selected: boolean;
    type: Exclude<ActivityType, 'all'>;
    typeLabel: string;
    raw: Transaction;
}

const router = useRouter();
const { formatNumberToLocalizedNumerals, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const {
    showAmountInHomePage,
    netAssets,
    totalAssets,
    transactionOverview,
    getDisplayIncomeAmount,
    getDisplayExpenseAmount
} = useHomePageBase();

const accountsStore = useAccountsStore();
const transactionCategoriesStore = useTransactionCategoriesStore();
const overviewStore = useOverviewStore();
const monthlyBudgetStore = useMonthlyBudgetStore();
const transactionsStore = useTransactionsStore();
const aiReviewItemsStore = useAIReviewItemsStore();
const rootStore = useRootStore();
const userStore = useUserStore();

const snackbar = useTemplateRef<SnackBarType>('snackbar');
const transactionEditDialog = useTemplateRef<TransactionDialogType>('transactionEditDialog');
const accountEditDialog = useTemplateRef<AccountDialogType>('accountEditDialog');

const loadingOverview = ref<boolean>(true);
const balanceCurrencyMenuOpen = ref<boolean>(false);
const balanceCurrency = ref<string>(userStore.currentUserDefaultCurrency || 'CNY');
const selectedWallet = ref<Account | null>(null);
const accountMenuOpen = ref<boolean>(false);
const accountActionBusy = ref<boolean>(false);
const utilityPanel = ref<'search' | 'notifications' | 'help' | null>(null);
const globalSearchQuery = ref<string>('');
const currentUserName = computed<string>(() => userStore.currentUserNickname || '当前用户');
const currentUserInitial = computed<string>(() => currentUserName.value.trim().slice(0, 1).toUpperCase() || '用');
const walletAccounts = computed<Account[]>(() => accountsStore.allVisiblePlainAccounts.slice(0, 5));
const pendingReviewCount = computed<number>(() => aiReviewItemsStore.items.length);

const defaultCurrency = computed<string>(() => userStore.currentUserDefaultCurrency || 'CNY');
const displayAccountCount = computed<string>(() => formatNumberToLocalizedNumerals(walletAccounts.value.length));
const balanceCurrencies = computed<string[]>(() => Array.from(new Set([defaultCurrency.value, ...walletAccounts.value.map(account => account.currency)])));
const balanceDisplayAmount = computed<string>(() => {
    if (!showAmountInHomePage.value) {
        return '******';
    }

    if (balanceCurrency.value === defaultCurrency.value) {
        return netAssets.value;
    }

    const currencyBalance = walletAccounts.value.filter(account => account.currency === balanceCurrency.value).reduce((total, account) => total + account.balance, 0);
    return formatAmountToLocalizedNumeralsWithCurrency(currencyBalance, balanceCurrency.value);
});
const balanceAccountSummary = computed<string>(() => {
    const count = balanceCurrency.value === defaultCurrency.value ? walletAccounts.value.length : walletAccounts.value.filter(account => account.currency === balanceCurrency.value).length;
    return `${formatNumberToLocalizedNumerals(count)} 个账户 · ${balanceCurrency.value}`;
});
const currentYearMonth = computed<number>(() => {
    const now = new Date();
    return now.getFullYear() * 100 + now.getMonth() + 1;
});
const monthlyBudget = computed<MonthlyBudgetInfoResponse | null>(() => monthlyBudgetStore.budgets[currentYearMonth.value] ?? null);
const currentMonthExpense = computed<number>(() => transactionOverview.value.thisMonth?.expenseAmount ?? 0);
const budgetPercentage = computed<number>(() => monthlyBudget.value ? currentMonthExpense.value * 100 / monthlyBudget.value.amount : 0);
const thisMonthIncome = computed<string>(() => transactionOverview.value.thisMonth ? getDisplayIncomeAmount(transactionOverview.value.thisMonth) : '¥0.00');
const thisMonthExpense = computed<string>(() => transactionOverview.value.thisMonth ? getDisplayExpenseAmount(transactionOverview.value.thisMonth) : '¥0.00');

const chartBars = [
    { month: '1月', profit: 70, loss: 36 },
    { month: '2月', profit: 84, loss: 32 },
    { month: '3月', profit: 66, loss: 24 },
    { month: '4月', profit: 76, loss: 34 },
    { month: '5月', profit: 74, loss: 42 },
    { month: '6月', profit: 94, loss: 56 },
    { month: '7月', profit: 78, loss: 32 },
    { month: '8月', profit: 60, loss: 26 }
];

const activityQuery = ref<string>('');
const activityFilterOpen = ref<boolean>(false);
const activityType = ref<ActivityType>('all');
const activityMenuId = ref<string | null>(null);
const activityTypes: Array<{label:string;value:ActivityType}> = [{label:'全部',value:'all'},{label:'支出',value:'expense'},{label:'收入',value:'income'},{label:'转账',value:'transfer'}];
const activityRows = ref<ActivityRow[]>([]);
const filteredActivities = computed(() => {
    const query = activityQuery.value.trim().toLowerCase();
    return activityRows.value.filter(row => (activityType.value === 'all' || row.type === activityType.value) && (!query || `${row.id}${row.name}${row.status}${row.typeLabel}`.toLowerCase().includes(query)));
});
const selectedActivityCount = computed(() => activityRows.value.filter(row => row.selected).length);
const allActivitiesSelected = computed({get:()=>filteredActivities.value.length>0&&filteredActivities.value.every(row=>row.selected),set:(selected:boolean)=>filteredActivities.value.forEach(row=>row.selected=selected)});
const someActivitiesSelected = computed(()=>selectedActivityCount.value>0&&!allActivitiesSelected.value);
const attentionActivities = computed(() => activityRows.value.filter(row => row.status !== '已完成'));
const globalSearchResults = computed(() => {
    const query = globalSearchQuery.value.trim().toLowerCase();
    if (!query) return activityRows.value.slice(0, 3);
    return activityRows.value.filter(row => `${row.id}${row.name}${row.status}`.toLowerCase().includes(query)).slice(0, 5);
});

const displayWallets = computed(() => {
    return walletAccounts.value.slice(0, 3).map(account => ({
        id: account.id,
        flag: currencyFlag(account.currency),
        code: account.currency || 'CNY',
        amount: formatWalletBalance(account.balance, account.currency),
        name: account.name,
        note: walletCategoryLabel(account.category),
        active: !account.hidden,
        raw: account
    }));
});

function formatBudgetMoney(value: number): string {
    return formatAmountToLocalizedNumeralsWithCurrency(value, 'CNY');
}

function currencyFlag(currency: string): string {
    if (currency === 'CNY') return 'cn';
    if (currency === 'USD') return 'blue';
    if (currency === 'EUR') return 'gold';
    return 'currency-alt';
}

function selectBalanceCurrency(currency: string): void {
    balanceCurrency.value = currency;
    balanceCurrencyMenuOpen.value = false;
}

function toggleBalanceVisibility(): void {
    showAmountInHomePage.value = !showAmountInHomePage.value;
    balanceCurrencyMenuOpen.value = false;
}

function refreshBalance(): void {
    balanceCurrencyMenuOpen.value = false;
    reload(true);
}

function openQuickTransaction(type: number, account?: Account | null): void {
    balanceCurrencyMenuOpen.value = false;
    selectedWallet.value = null;
    transactionEditDialog.value?.open({ type, accountId: account?.id, noTransactionDraft: true }).then(() => reload(false)).catch(error => {
        if (error && !error.canceled && !error.processed) snackbar.value?.showError(error);
    });
}

function openWalletDetails(account: Account): void {
    balanceCurrencyMenuOpen.value = false;
    selectedWallet.value = account;
}

function closeWalletDetails(): void {
    selectedWallet.value = null;
}

function openAccountEditor(account?: Account | null): void {
    balanceCurrencyMenuOpen.value = false;
    selectedWallet.value = null;
    accountEditDialog.value?.open(account ? { id: account.id, currentAccount: account } : undefined).then(() => reload(false)).catch(error => {
        if (error && !error.processed) snackbar.value?.showError(error);
    });
}

function transactionToActivity(transaction: Transaction): ActivityRow {
    const isIncome = transaction.type === TransactionType.Income;
    const isTransfer = transaction.type === TransactionType.Transfer;
    const type: ActivityRow['type'] = isIncome ? 'income' : isTransfer ? 'transfer' : 'expense';
    const typeLabel = isIncome ? '收入' : isTransfer ? '转账' : '支出';
    const currency = transaction.sourceAccount?.currency || userStore.currentUserDefaultCurrency;
    const amount = formatAmountToLocalizedNumeralsWithCurrency(transaction.sourceAmount, currency);
    const prefix = isIncome ? '+' : isTransfer ? '↔ ' : '-';
    const date = new Date(transaction.time * 1000);
    const title = transaction.comment || transaction.category?.name || typeLabel;

    return {
        id: `TX_${transaction.id.slice(-6).toUpperCase()}`,
        transactionId: transaction.id,
        icon: (transaction.category?.name || typeLabel).slice(0, 1),
        color: isIncome ? '#149C63' : isTransfer ? '#4F46E5' : '#F05537',
        name: title,
        price: `${prefix}${amount}`,
        status: transaction.editable ? '已完成' : '只读',
        statusClass: transaction.editable ? 'c' : 'g',
        date: date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }),
        selected: false,
        type,
        typeLabel,
        raw: transaction
    };
}

function clearActivitySelection(): void {
    activityRows.value.forEach(row => row.selected = false);
}

function resetActivityFilters(): void {
    activityQuery.value = '';
    activityType.value = 'all';
}

function openActivity(row: ActivityRow): void {
    activityMenuId.value = null;
    transactionEditDialog.value?.open({ id: row.transactionId, currentTransaction: row.raw }).then(() => reload(false)).catch(error => {
        if (error && !error.processed) snackbar.value?.showError(error);
    });
}

function viewActivityInList(row: ActivityRow): void {
    activityMenuId.value = null;
    router.push({ path: '/transaction/list', query: { search: row.transactionId } });
}

function formatWalletBalance(balance: number, currency: string): string {
    return formatAmountToLocalizedNumeralsWithCurrency(balance, currency);
}

function walletCategoryLabel(category: number): string {
    const labels: Record<number, string> = {
        [AccountCategory.Cash.type]: '现金',
        [AccountCategory.CheckingAccount.type]: '储蓄账户',
        [AccountCategory.SavingsAccount.type]: '储蓄账户',
        [AccountCategory.CreditCard.type]: '信用账户',
        [AccountCategory.VirtualAccount.type]: '数字余额',
        [AccountCategory.DebtAccount.type]: '负债账户',
        [AccountCategory.Receivables.type]: '应收款',
        [AccountCategory.CertificateOfDeposit.type]: '定期存款',
        [AccountCategory.InvestmentAccount.type]: '投资账户'
    };
    return labels[category] || '资金账户';
}

function toggleUtilityPanel(panel: 'search' | 'notifications' | 'help'): void {
    utilityPanel.value = utilityPanel.value === panel ? null : panel;
    accountMenuOpen.value = false;
}

function toggleAccountMenu(): void {
    accountMenuOpen.value = !accountMenuOpen.value;
    utilityPanel.value = null;
}

function submitGlobalSearch(): void {
    const search = globalSearchQuery.value.trim();
    utilityPanel.value = null;
    router.push({ path: '/transaction/list', query: search ? { search } : { search: '1' } });
}

function openSearchResult(row: { id: string; name: string }): void {
    utilityPanel.value = null;
    router.push({ path: '/transaction/list', query: { search: row.id || row.name } });
}

function openNotification(row: { id: string }): void {
    utilityPanel.value = null;
    router.push({ path: '/transaction/list', query: { search: row.id } });
}

function switchAccount(): void {
    accountMenuOpen.value = false;
    router.push({ path: '/login', query: { switch: '1' } });
}

function logoutAccount(): void {
    if (accountActionBusy.value) {
        return;
    }

    accountActionBusy.value = true;
    rootStore.logout().then(() => {
        router.replace('/login').then(() => window.location.reload());
    }).catch(error => {
        accountActionBusy.value = false;
        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function reload(force: boolean): void {
    loadingOverview.value = true;
    const now = new Date();

    const promises = [
        accountsStore.loadAllAccounts({ force: false }),
        transactionCategoriesStore.loadAllCategories({ force: false }),
        overviewStore.loadTransactionOverview({ force: force, loadLast11Months: true }),
        monthlyBudgetStore.load(currentYearMonth.value, force),
        aiReviewItemsStore.load(),
        transactionsStore.loadMonthlyAllTransactions({ year: now.getFullYear(), month: now.getMonth() + 1, autoExpand: true, defaultCurrency: userStore.currentUserDefaultCurrency }).then(result => {
            activityRows.value = result.items.slice(0, 5).map(transactionToActivity);
            activityMenuId.value = null;
        })
    ];

    Promise.all(promises).then(() => {
        loadingOverview.value = false;

        if (force) {
            snackbar.value?.showMessage('余额数据已更新');
        }
    }).catch(error => {
        loadingOverview.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

if (isUserLogined() && isUserUnlocked()) {
    reload(false);
}
</script>

<style scoped>
/* finexy-replica.html exact layout tokens and geometry */
.finance-home {
    --page:#F1F2F6; --card:#FFFFFF; --panel:#F7F8FA; --line:#EFF0F4;
    --ink:#12141A; --muted:#6E7684; --faint:#9AA1AD;
    --orange:#F05537; --orange-2:#EE4E31;
    --green:#1DBF73; --red:#F04444; --olive:#A8A832; --blue:#4F46E5;
    --r-lg:22px; --r-md:16px;
    position:fixed; inset:0; z-index:1000; overflow:auto;
    min-width:1160px; padding:34px 92px; background:var(--page); color:var(--ink);
    font-family:"Inter","Microsoft YaHei UI","PingFang SC","Segoe UI",system-ui,sans-serif;
    -webkit-font-smoothing:antialiased;
}
.finance-home,.finance-home *{box-sizing:border-box}
.finance-home button{font:inherit;background:none;border:0;cursor:pointer;color:inherit}
.finance-home a{color:inherit;text-decoration:none}
.app{min-height:1018px;background:var(--card);border-radius:30px;padding:16px 22px 22px;box-shadow:0 1px 2px rgba(16,24,40,.03)}
.topbar{position:relative;display:flex;align-items:center;height:56px}
.brand{display:flex;align-items:center;gap:10px;background:var(--panel);border-radius:14px;padding:7px 14px 7px 8px;font-weight:800;font-size:15.5px;letter-spacing:-.02em}
.logo{display:grid;place-items:center;width:34px;height:34px;border-radius:11px;color:#fff;font-weight:900;font-size:19px;font-style:italic;background:linear-gradient(135deg,#F36447,var(--orange-2));box-shadow:inset 0 1px 0 rgba(255,255,255,.3)}
.tabs{display:flex;align-items:center;gap:34px;margin-left:64px}
.tabs a{font-size:13.5px;font-weight:500;color:var(--muted);cursor:pointer;padding:9px 4px;border-radius:999px;white-space:nowrap}
.tabs a.active{background:var(--ink);color:#fff;padding:9px 22px;font-weight:600}
.top-actions{position:relative;margin-left:auto;display:flex;align-items:center;gap:10px}
.ic{position:relative;display:grid!important;place-items:center;width:38px;height:38px;border-radius:50%;background:#fff!important;border:1px solid var(--line)!important}
.ic:hover{background:var(--panel)!important}.ic .dot{position:absolute;top:9px;right:10px;width:6px;height:6px;border-radius:50%;background:var(--orange)}
.user{display:flex;align-items:center;gap:9px;border:1px solid var(--line)!important;border-radius:999px;padding:4px 12px 4px 5px!important}
.user:hover{background:var(--panel)}
.avatar{display:grid;place-items:center;width:32px;height:32px;border-radius:50%;color:#fff;font-size:11px;font-weight:800;background:linear-gradient(135deg,#8D5524,#5C3A1E)}
.uinfo{text-align:left;line-height:1.25}.uinfo b{display:block;font-size:12px;font-weight:700}.uinfo small{display:block;font-size:10.5px;color:var(--faint);max-width:118px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.account-menu{position:absolute;z-index:80;top:51px;right:0;width:285px;padding:9px;border:1px solid var(--line);border-radius:18px;background:#fff;box-shadow:0 18px 45px rgba(18,20,26,.16)}
.account-current{display:grid;grid-template-columns:40px 1fr auto;align-items:center;gap:10px;padding:10px;margin-bottom:6px;border-radius:13px;background:var(--panel)}
.account-avatar{display:grid;width:40px;height:40px;place-items:center;border-radius:50%;color:#fff;background:linear-gradient(135deg,#8D5524,#5C3A1E);font-size:12px;font-weight:800}
.account-current div{display:grid;gap:2px;min-width:0}.account-current small{color:var(--faint);font-size:9.5px}.account-current strong{overflow:hidden;text-overflow:ellipsis;font-size:12px;white-space:nowrap}.account-current i{padding:4px 7px;border-radius:999px;color:var(--green);background:#eaf9f1;font-size:9px;font-style:normal;font-weight:700}
.account-menu>a,.account-menu>button{display:grid!important;width:100%;grid-template-columns:34px 1fr;align-items:center;gap:9px;padding:9px!important;border-radius:11px!important;text-align:left}
.account-menu>a:hover,.account-menu>button:hover{background:var(--panel)!important}.account-menu>a>.v-icon,.account-menu>button>.v-icon{display:grid;width:34px;height:34px;place-items:center;border-radius:10px;background:#f0f1f4}
.account-menu>a>span,.account-menu>button>span{display:grid;gap:2px}.account-menu b{font-size:11px}.account-menu a small,.account-menu button small{color:var(--faint);font-size:9.5px}
.account-drop-enter-active,.account-drop-leave-active{transition:.18s}.account-drop-enter-from,.account-drop-leave-to{opacity:0;transform:translateY(-6px)}
.ic.active{color:#fff;background:var(--ink)!important;border-color:var(--ink)!important}
.utility-panel{position:absolute;z-index:79;top:51px;right:0;width:360px;padding:12px;border:1px solid var(--line);border-radius:20px;background:#fff;box-shadow:0 20px 50px rgba(18,20,26,.16)}
.utility-panel header{display:flex;align-items:flex-start;justify-content:space-between;padding:4px 4px 12px}.utility-panel header>div{display:grid;gap:3px}.utility-panel header b{font-size:15px}.utility-panel header small{color:var(--muted);font-size:12px}.utility-panel header>button{display:grid;width:30px;height:30px;place-items:center;border-radius:50%;background:var(--panel);font-size:18px}
.utility-search{display:flex;height:44px;align-items:center;gap:8px;padding:0 12px;border:1px solid var(--line);border-radius:12px;background:var(--panel);color:var(--muted)}.utility-search input{min-width:0;flex:1;border:0;outline:0;background:transparent;color:var(--ink);font-size:13px}.utility-search input::placeholder{color:var(--muted)}
.utility-results{display:grid;gap:3px;margin-top:12px}.utility-results>small{padding:0 5px 5px;color:var(--muted);font-size:11.5px}.utility-results>button{display:grid;width:100%;grid-template-columns:34px 1fr auto;align-items:center;gap:9px;padding:8px;border-radius:11px;text-align:left}.utility-results>button:hover{background:var(--panel)}.utility-results>button>i{display:grid;width:34px;height:34px;place-items:center;border-radius:9px;color:#fff;font-size:11px;font-style:normal;font-weight:800}.utility-results>button>span{display:grid;gap:2px}.utility-results b{font-size:12.5px}.utility-results small{color:var(--muted);font-size:11.5px}.utility-results strong{font-size:12px}.utility-results>p{margin:8px 0 12px;color:var(--muted);font-size:12.5px;text-align:center}
.utility-primary{display:flex!important;width:100%;height:42px;align-items:center;justify-content:center;margin-top:10px;border-radius:999px!important;color:#fff!important;background:var(--ink)!important;font-size:12.5px;font-weight:700}
.notice-list{display:grid;gap:3px}.notice-list>button{display:grid;width:100%;grid-template-columns:8px 1fr 18px;align-items:center;gap:10px;padding:11px 9px;border-radius:11px;text-align:left}.notice-list>button:hover{background:var(--panel)}.notice-list>button>i{width:8px;height:8px;border-radius:50%;background:var(--olive)}.notice-list>button>i.p{background:var(--red)}.notice-list>button>span{display:grid;gap:3px}.notice-list b{font-size:13px}.notice-list small{color:var(--muted);font-size:11.5px}
.utility-empty{display:grid;justify-items:center;gap:5px;padding:25px 10px;color:var(--muted)}.utility-empty b{color:var(--ink);font-size:13px}.utility-empty small{font-size:12px}
.help-panel>a{display:grid;grid-template-columns:36px 1fr 18px;align-items:center;gap:10px;padding:11px 9px;border-radius:12px}.help-panel>a:hover{background:var(--panel)}.help-panel>a>.v-icon:first-child{display:grid;width:36px;height:36px;place-items:center;border-radius:10px;background:var(--panel)}.help-panel>a>span{display:grid;gap:3px}.help-panel b{font-size:13px}.help-panel small{color:var(--muted);font-size:11.5px}
.body{display:flex;gap:26px;margin-top:14px}.rail{display:flex;flex-direction:column;gap:12px;width:56px;flex:none}
.stack{display:flex;flex-direction:column;align-items:center;gap:4px;background:var(--panel);border-radius:18px;padding:8px 0}.stack.bottom{margin-top:auto}
.ric{display:grid!important;place-items:center;width:40px;height:40px;border-radius:50%;color:#4A4F5A}.ric:hover{background:#EDEFF3}.ric.active{background:var(--ink);color:#fff}
main{flex:1;min-width:0}h1{font-size:36px;font-weight:800;letter-spacing:-.03em;line-height:1.2;margin:0}.sub{margin-top:6px;font-size:13.5px;color:var(--muted)}
.grid{display:grid;grid-template-columns:1.04fr .94fr 1.02fr;gap:18px;margin-top:24px}.card{background:var(--card);border:1px solid var(--line);border-radius:var(--r-lg);padding:22px}.panel{background:var(--panel);border-radius:var(--r-lg)}.col-left{display:grid;gap:18px;align-content:start}.activities{grid-column:2/4}
.balance-card{position:relative}.bhead{display:flex;align-items:center;justify-content:space-between}.bhead>span{font-size:13px;color:var(--muted);font-weight:500}.currency-switch{position:relative}.cur{display:flex;align-items:center;gap:6px;border:1px solid var(--line)!important;border-radius:9px;padding:5px 10px!important;font-size:12px;font-weight:600}.cur:hover,.cur.active{background:var(--panel)!important}.cur .flag{width:15px;height:15px}
.balance-menu{position:absolute;z-index:45;top:38px;right:0;width:250px;padding:9px;border:1px solid var(--line);border-radius:15px;background:#fff;box-shadow:0 16px 38px rgba(18,20,26,.15)}.balance-menu>small{display:block;padding:4px 7px 7px;color:var(--muted);font-size:11.5px;font-weight:650}.balance-menu>button{display:grid;width:100%;grid-template-columns:22px 1fr 18px;align-items:center;gap:8px;padding:8px!important;border-radius:10px!important;text-align:left}.balance-menu>button:hover,.balance-menu>button.active{background:var(--panel)!important}.balance-menu>button>span:nth-child(2){display:grid;gap:2px}.balance-menu b{font-size:12.5px}.balance-menu button small{color:var(--muted);font-size:11px}.balance-menu-actions{display:grid;grid-template-columns:1fr 1fr;gap:5px;margin-top:6px;padding-top:7px;border-top:1px solid var(--line)}.balance-menu-actions button{display:flex;align-items:center;justify-content:center;gap:5px;padding:8px 5px!important;border-radius:9px!important;background:var(--panel)!important;font-size:11px;font-weight:650}.balance-menu-actions button:hover{color:var(--orange)}
.amount{margin-top:10px;font-size:31px;font-weight:800;letter-spacing:-.03em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.trend{display:flex;align-items:center;gap:7px;margin-top:6px;font-size:12px}.trend b{color:var(--green);font-weight:700}.trend span{color:var(--faint)}
.balance-meta b{display:flex;align-items:center;gap:5px}.balance-meta b i{width:6px;height:6px;border-radius:50%;background:var(--green);box-shadow:0 0 0 3px rgba(8,122,73,.1)}
.bactions{display:flex;gap:12px;margin-top:18px}.btn-dark,.btn-light{flex:1;display:flex;align-items:center;justify-content:center;gap:9px;height:45px;border-radius:999px;font-size:13.5px;font-weight:600}.btn-dark{background:var(--ink)!important;color:#fff!important}.btn-dark:hover{background:#262932!important}.btn-light{background:#F3F4F6!important}.btn-light:hover{background:#E9EBEE!important}
.wallets{margin-top:20px;background:var(--panel);border-radius:var(--r-md);padding:16px}.whead{display:flex;align-items:baseline;gap:8px;font-size:12.5px}.whead b{font-weight:700}.whead span{color:var(--faint);font-size:11.5px}.wallet-add{margin-left:auto;color:var(--ink);font-size:11.5px;font-weight:700}.wallet-add:hover{color:var(--orange)}.wrow{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:12px}.w{display:block;width:100%;background:#fff!important;border-radius:13px;padding:12px!important;text-align:left}.w:hover{transform:translateY(-1px);box-shadow:0 7px 18px rgba(18,20,26,.06)}.wt{display:flex;align-items:center;gap:6px}
.flag{position:relative;display:inline-block;width:17px;height:17px;border-radius:50%;overflow:hidden;flex:none;box-shadow:inset 0 0 0 1px rgba(0,0,0,.08)}.flag.cn{background:#DE2910}.flag.cn:after{content:"★";position:absolute;left:3px;top:-1px;color:#FFDE00;font-size:8px}.flag.blue{background:linear-gradient(180deg,#2563EB 50%,#fff 50%)}.flag.gold{background:linear-gradient(180deg,#151515 33.3%,#DD0000 33.3% 66.6%,#FFCE00 66.6%)}.flag.currency-alt{background:linear-gradient(135deg,#4f46e5,#2e9bff)}
.wt b{font-size:12px;font-weight:700}.wt i{margin-left:auto;font-style:normal;color:var(--faint);font-size:13px}.w strong{display:block;margin-top:9px;font-size:14px;font-weight:800;letter-spacing:-.01em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.w small{display:block;margin-top:3px;min-height:21px;font-size:9.5px;color:var(--faint)}.w em{display:block;margin-top:7px;font-size:10.5px;font-weight:700;font-style:normal}.w em.ok{color:var(--green)}.w em.off{color:var(--red)}
.wallet-empty{display:flex;min-height:92px;align-items:center;justify-content:center;flex-direction:column;gap:9px;margin-top:12px;border:1px dashed #dfe2e8;border-radius:13px;color:var(--muted);font-size:12px}.wallet-empty button{padding:7px 12px!important;border-radius:999px!important;color:#fff!important;background:var(--ink)!important;font-size:11.5px;font-weight:700}
.balance-overlay{--panel:#f7f8fa;--line:#eff0f4;--ink:#12141a;--muted:#4f5b6b;--orange:#f05537;--green:#087a49;--red:#c92b35;position:fixed;z-index:2500;inset:0;background:rgba(18,20,26,.2);backdrop-filter:blur(2px);color:var(--ink);font-family:"Inter","Microsoft YaHei UI","PingFang SC","Segoe UI",system-ui,sans-serif}.balance-overlay button{font:inherit;color:inherit;border:0;cursor:pointer}.balance-drawer{position:absolute;top:18px;right:18px;bottom:18px;width:390px;padding:30px;border-radius:24px;background:#fff;box-shadow:0 26px 80px rgba(18,20,26,.24)}.drawer-close{position:absolute;top:18px;right:18px;display:grid!important;width:38px;height:38px;place-items:center;border-radius:50%;background:var(--panel)!important;font-size:20px}.drawer-mark{display:grid;width:50px;height:50px;place-items:center;margin-top:20px;border-radius:15px;color:#fff;background:var(--ink);font-size:17px;font-weight:850}.drawer-mark.cn{background:#de2910}.drawer-mark.blue{background:#2563eb}.drawer-mark.gold{background:linear-gradient(135deg,#151515,#dd0000 60%,#ffce00)}.drawer-mark.currency-alt{background:linear-gradient(135deg,#4f46e5,#2e9bff)}.balance-drawer>small{display:block;margin-top:17px;color:var(--orange);font-size:11.5px;font-weight:750}.balance-drawer h2{margin:4px 0 3px;font-size:24px}.balance-drawer>p{margin:0;color:var(--muted);font-size:12.5px}.drawer-balance{display:block;margin-top:25px;font-size:30px;letter-spacing:-.03em}.balance-drawer dl{margin:25px 0}.balance-drawer dl>div{display:flex;align-items:center;justify-content:space-between;padding:13px 0;border-bottom:1px solid var(--line);font-size:12.5px}.balance-drawer dt{color:var(--muted)}.balance-drawer dd{display:flex;align-items:center;gap:7px;max-width:220px;margin:0;text-align:right}.balance-drawer dd i{width:7px;height:7px;border-radius:50%}.balance-drawer dd i.ok{background:var(--green)}.balance-drawer dd i.off{background:var(--red)}.drawer-actions{display:flex;gap:9px}.drawer-actions .btn-dark,.drawer-actions .btn-light{height:44px}.drawer-edit{display:flex!important;width:100%;height:42px;align-items:center;justify-content:center;gap:7px;margin-top:9px;border:1px solid var(--line)!important;border-radius:999px!important;font-size:12.5px;font-weight:700;background:#fff}.drawer-edit:hover{background:var(--panel)!important}.drawer-enter-active,.drawer-leave-active{transition:.2s}.drawer-enter-from,.drawer-leave-to{opacity:0}.drawer-enter-from .balance-drawer,.drawer-leave-to .balance-drawer{transform:translateX(28px)}.balance-drawer{transition:transform .2s}.balance-overlay .btn-dark,.balance-overlay .btn-light{flex:1;display:flex;align-items:center;justify-content:center;border-radius:999px;font-size:13.5px;font-weight:600}.balance-overlay .btn-dark{color:#fff;background:var(--ink)}.balance-overlay .btn-light{background:var(--panel)}
.stats{display:grid;grid-template-columns:1fr 1fr;grid-auto-rows:1fr;gap:10px;padding:10px}.stat{background:#fff;border-radius:var(--r-md);padding:16px;min-width:0}.shead{display:flex;align-items:center;justify-content:space-between}.shead span{font-size:13px;font-weight:600;color:#3A3F4A}.sic{display:grid;place-items:center;width:30px;height:30px;border-radius:50%;background:#F3F4F6;color:#3A3F4A;font-size:13px;font-style:normal}.stat strong{display:block;margin-top:12px;font-size:26px;font-weight:800;letter-spacing:-.02em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.strend{display:flex;align-items:center;gap:6px;margin-top:8px;font-size:11.5px}.strend b{font-weight:700}.strend span{color:var(--faint)}.strend.up b{color:var(--green)}.strend.down b{color:var(--red)}.stat.orange{background:linear-gradient(135deg,#F36447,var(--orange-2));color:#fff}.stat.orange .shead span{color:rgba(255,255,255,.92)}.stat.orange .sic{background:rgba(255,255,255,.16);color:#fff}.stat.orange .strend b{color:#fff}.stat.orange .strend span{color:rgba(255,255,255,.72)}
.chart-card h3{font-size:16px;font-weight:800;letter-spacing:-.01em;margin:0}.csub{margin-top:4px;font-size:11.5px;color:var(--faint)}.plot-wrap{margin-top:14px;background:var(--panel);border-radius:var(--r-md);padding:16px 16px 8px}.plot-head{display:flex;align-items:center;justify-content:space-between;font-size:12.5px;font-weight:700}.legend{display:flex;align-items:center;gap:6px;font-size:11px;font-weight:500;color:var(--muted)}.legend i{display:inline-block;width:9px;height:9px;border-radius:2.5px;margin:0 2px 0 12px}.legend .b{background:var(--blue)}.legend .k{background:var(--ink)}
.plot{position:relative;height:196px;margin-top:12px;padding-left:30px}.gl{position:absolute;left:30px;right:0;border-top:1.5px dashed #E6E8ED}.gl-bottom{top:calc(100% - 24px)}.ylab{position:absolute;left:0;transform:translateY(-50%);font-size:10px;color:var(--faint);font-weight:500}.ylab-bottom{top:calc(100% - 24px)}.bars{position:absolute;inset:0 0 24px 30px;display:flex;align-items:flex-end;justify-content:space-around}.grp{position:relative;display:flex;align-items:flex-end;gap:4px;height:100%}.grp i{width:15px;border-radius:5px 5px 0 0}.grp .p{background:repeating-linear-gradient(-45deg,rgba(255,255,255,.42) 0 3px,transparent 3px 9px),linear-gradient(180deg,#F36447,var(--orange-2))}.grp .l{background:var(--ink)}.grp span{position:absolute;left:50%;transform:translateX(-50%);bottom:-20px;font-size:10.5px;color:var(--faint);white-space:nowrap}
.limit{cursor:pointer}.limit h3,.mycards h3{font-size:15px;font-weight:800;display:flex;align-items:center;gap:8px;margin:0}.limitbar{display:flex;height:10px;border-radius:999px;overflow:hidden;margin-top:18px}.limitbar .used{background:linear-gradient(90deg,#F36447,var(--orange-2));width:25.5%;border-radius:999px 0 0 999px}.limitbar .rest{flex:1;background:repeating-linear-gradient(-45deg,#E9EAEF 0 4px,#F4F5F8 4px 9px)}.limitmeta{display:flex;justify-content:space-between;margin-top:10px;font-size:12px}.limitmeta span{color:var(--faint)}.limitmeta b{font-weight:700;color:var(--ink)}
.mycards .add{margin-left:auto;background:#F3F4F6!important;border-radius:999px;padding:8px 14px!important;font-size:11.5px;font-weight:700}.mycards .add:hover{background:#E9EBEE!important}.mhead{display:flex;align-items:center}.cards{display:flex;gap:12px;margin-top:16px}.cc{position:relative;flex:1;height:158px;border-radius:18px;padding:14px;color:#fff;overflow:hidden}.cc.black{background:linear-gradient(140deg,#262932,#101216)}.cc.black:after{content:"";position:absolute;right:-46px;top:-46px;width:150px;height:150px;border:26px solid rgba(255,255,255,.045);border-radius:50%}.cc.orange{background:linear-gradient(140deg,#F4674B,var(--orange-2))}.cc .spark{position:absolute;color:rgba(255,255,255,.75);font-size:15px}.cc .s1{right:18px;top:38px}.cc .s2{right:36px;top:66px;font-size:10px}.cc .s3{right:16px;top:84px;font-size:9px}.cc .nfc{position:absolute;left:14px;top:14px;opacity:.85}.cc .active-pill{position:absolute;left:44px;top:13px;display:flex;align-items:center;gap:5px;background:#fff;color:var(--ink);border-radius:999px;padding:4px 10px;font-size:9.5px;font-weight:800}.cc .active-pill:before{content:"";width:5px;height:5px;border-radius:50%;background:var(--green)}.mc{position:absolute;right:14px;top:14px;display:flex}.mc i{width:22px;height:22px;border-radius:50%}.mc i:first-child{background:#EB001B;margin-right:-9px}.mc i:last-child{background:#F79E1B;opacity:.92}.cc .cnum{position:absolute;left:14px;bottom:14px;right:10px;display:flex;gap:10px;align-items:flex-end}.cc .cnum>div{flex:none;min-width:0}.cc label{display:block;font-size:7.5px;color:rgba(255,255,255,.55);text-transform:uppercase;letter-spacing:.03em;white-space:nowrap}.cc .cnum b{display:block;margin-top:3px;font-size:10.5px;font-weight:700;letter-spacing:.03em;white-space:nowrap}
.activities{display:flex;flex-direction:column}.ahead{display:flex;align-items:center;gap:12px}.ahead h3{font-size:16px;font-weight:800;letter-spacing:-.01em;margin:0}.searchbox{margin-left:auto;display:flex;align-items:center;gap:8px;border:1px solid var(--line);border-radius:11px;padding:0 12px;height:38px;width:212px;color:var(--faint)}.searchbox input{border:0;outline:0;background:none;font-size:12.5px;width:100%;color:var(--ink)}.searchbox input::placeholder{color:var(--faint)}.filter{display:flex;align-items:center;gap:8px;border:1px solid var(--line)!important;border-radius:11px;height:38px;padding:0 15px!important;font-size:12.5px;font-weight:600}.filter:hover{background:var(--panel)}
table{width:100%;border-collapse:collapse;margin-top:8px}th{font-size:11.5px;font-weight:600;color:var(--faint);text-align:left;padding:12px 8px;border-bottom:1px solid var(--line)}td{font-size:12.5px;padding:13px 8px;border-bottom:1px solid #F4F5F8;color:#3A3F4A;vertical-align:middle;white-space:nowrap}tr:last-child td{border-bottom:0}td.id{font-weight:600;color:var(--ink)}td.price{font-weight:700;color:var(--ink)}td.date{color:var(--muted)}.check-col{width:30px}.more-col{width:34px}.act{display:flex;align-items:center;gap:11px;font-weight:600;color:var(--ink)}.aic{display:grid;place-items:center;width:28px;height:28px;border-radius:8px;color:#fff;font-size:12px;font-weight:800;flex:none}.status{display:flex;align-items:center;gap:8px;font-size:12.5px}.status i{width:7px;height:7px;border-radius:50%;flex:none}.status .c{background:var(--green)}.status .p{background:var(--red)}.status .g{background:var(--olive)}.more{color:var(--faint);padding:0 4px!important}.cb{appearance:none;width:16px;height:16px;border:1.5px solid #D8DBE1;border-radius:5px;cursor:pointer;vertical-align:middle;position:relative}.cb:checked{background:var(--ink);border-color:var(--ink)}.cb:checked:after{content:"✓";position:absolute;inset:0;display:grid;place-items:center;color:#fff;font-size:10px;font-weight:900}tbody tr{transition:background .15s}tbody tr:hover{background:#FAFBFC}
.selected-summary{display:flex;align-items:center;gap:8px;padding:6px 9px;border-radius:999px;color:var(--ink);background:var(--panel);font-size:12px;font-weight:650}.selected-summary button{color:var(--orange);font-size:11.5px;font-weight:700}.filter.active{color:#fff;background:var(--ink)!important;border-color:var(--ink)!important}.activity-filters{display:flex;align-items:center;gap:7px;margin-top:12px;padding:9px 11px;border-radius:12px;background:var(--panel)}.activity-filters>span{margin-right:3px;color:var(--muted);font-size:12px;font-weight:650}.activity-filters button{padding:6px 11px!important;border-radius:999px!important;color:var(--muted);background:#fff!important;font-size:12px;font-weight:650}.activity-filters button.active{color:#fff;background:var(--ink)!important}.activity-filters .reset-filter{margin-left:auto;color:var(--orange);background:transparent!important}.activity-table-wrap{overflow-x:auto}.activity-table-wrap table{min-width:790px}.type-pill{display:inline-flex;padding:4px 8px;border-radius:999px;color:var(--muted);background:var(--panel);font-size:11.5px;font-weight:650}.price.income{color:var(--green)}.activities tbody tr:not(:has(.activity-empty)){cursor:pointer}.activities tbody tr:focus-visible{outline:2px solid rgba(240,85,55,.45);outline-offset:-2px}.row-actions{position:relative}.activity-menu{position:absolute;z-index:20;top:36px;right:4px;display:grid;width:126px;padding:5px;border:1px solid var(--line);border-radius:11px;background:#fff;box-shadow:0 12px 28px rgba(18,20,26,.14)}.activity-menu button{padding:8px 9px!important;border-radius:8px!important;text-align:left;font-size:11.5px;font-weight:650}.activity-menu button:hover{background:var(--panel)!important}.activity-empty{height:145px!important;text-align:center!important;white-space:normal}.activity-empty b,.activity-empty span{display:block}.activity-empty b{color:var(--ink);font-size:14px}.activity-empty span{margin-top:5px;color:var(--muted);font-size:12.5px}.activity-empty button{margin-top:12px;padding:8px 14px!important;border-radius:999px!important;color:#fff!important;background:var(--ink)!important;font-size:12px;font-weight:700}.activity-footer{display:flex;align-items:center;justify-content:space-between;margin-top:7px;padding-top:12px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}.activity-footer button{display:flex;align-items:center;gap:4px;color:var(--ink);font-weight:700}.activity-footer button:hover{color:var(--orange)}
/* Readability pass: retain the Finexy geometry while making functional copy legible. */
.finance-home{--muted:#4F5B6B;--faint:#667085;--green:#087A49;--red:#C92B35}
.finance-home main h1{font-size:40px;line-height:1.15}.finance-home main h1,.finance-home main h2,.finance-home main h3{color:#12141A!important;opacity:1!important;-webkit-text-fill-color:#12141A!important;text-shadow:none!important}.sub{font-size:14.5px;line-height:1.6}
.uinfo b{font-size:13px}.uinfo small{font-size:12px;color:var(--muted)}
.account-current small{font-size:11.5px;color:var(--muted)}.account-current strong{font-size:13px}.account-current i{font-size:11px;color:#06683f}
.account-menu b{font-size:13px}.account-menu a small,.account-menu button small{font-size:12px;color:var(--muted);line-height:1.45}
.bhead>span{font-size:14px;color:var(--muted)}.cur{font-size:13px}.trend{font-size:13px}.trend span{color:var(--muted)}
.whead{font-size:13.5px}.whead span{font-size:12.5px;color:var(--muted)}.wt b{font-size:13px}.wt i{color:var(--muted)}
.w strong{font-size:15px}.w small{font-size:12px;color:var(--muted);line-height:1.4}.w em{font-size:12px}
.shead span{font-size:14px}.strend{font-size:12.5px}.strend span{color:var(--muted)}
.chart-card h3,.ahead h3{font-size:18px}.csub{font-size:13px;color:var(--muted);line-height:1.5}.plot-head{font-size:13.5px}.legend{font-size:12px;color:var(--muted)}
.ylab,.grp span{font-size:11.5px;color:var(--muted)}
.limit h3,.mycards h3{font-size:16px}.limitmeta{font-size:13px}.limitmeta span{color:var(--muted)}.mycards .add{font-size:12.5px}
.cc .active-pill{font-size:11px}.cc label{font-size:10.5px;color:rgba(255,255,255,.78)}.cc .cnum b{font-size:12px}
.searchbox input,.filter{font-size:13px}.searchbox,.searchbox input::placeholder{color:var(--muted)}
table th{font-size:12.5px;color:var(--muted)}table td{font-size:13px}.status{font-size:13px}
/* Readability v2: dense cards use a consistent functional type floor. */
.finance-home{--muted:#475467;--faint:#5f6b7a}.finance-home .card,.finance-home .panel{color:#12141a}.utility-panel header small,.utility-results>small,.utility-results small,.notice-list small,.help-panel small{color:#475467;font-size:12.5px;line-height:1.45}.utility-results b,.notice-list b,.help-panel b{font-size:13.5px}.balance-menu>small{color:#475467;font-size:12.5px}.balance-menu b{font-size:13px}.balance-menu button small,.balance-menu-actions button{color:#475467;font-size:12px}.whead{font-size:14px}.whead span,.wallet-add{font-size:13px}.w{border:1px solid #e5e8ed}.wt b{font-size:13.5px}.w strong{font-size:16px}.w small{min-height:0;font-size:12.5px;color:#475467}.w em{font-size:12.5px}.shead span{color:#344054;font-size:14.5px;font-weight:700}.stat strong{font-size:28px}.strend{font-size:13px}.strend span{color:#475467}.chart-card h3,.ahead h3{font-size:19px}.csub{font-size:13.5px;color:#475467}.plot-head{font-size:14px}.legend{font-size:12.5px;color:#475467}.ylab,.grp span{font-size:12px;color:#475467}.limit h3,.mycards h3{font-size:17px}.limitmeta{font-size:13.5px}.limitmeta span{color:#475467}.mycards .add{font-size:13px}.ahead{gap:14px}.searchbox input,.filter{font-size:13.5px}.activities th{color:#475467;font-size:13px;font-weight:700}.activities td{color:#344054;font-size:13.5px}.activities .act,.activities td.id,.activities td.price{font-weight:700}.type-pill,.activity-menu button{font-size:12.5px}.status{font-size:13.5px}.activity-footer{color:#475467;font-size:12.5px}.balance-drawer>small{font-size:12.5px}.balance-drawer>p,.balance-drawer dl>div{font-size:13.5px}.drawer-edit{font-size:13px}
@media(max-width:1500px){
    .activity-table-wrap{overflow-x:visible}
    .activity-table-wrap table{min-width:0;table-layout:auto}
    .activities th:nth-child(2),.activities td:nth-child(2),.activities th:nth-child(4),.activities td:nth-child(4){display:none}
    .activities th,.activities td{padding-left:6px;padding-right:6px}
    .activities .act{gap:8px}
    .activities .date{font-size:12px}
}
@media(max-width:1250px){.finance-home{padding:28px 40px}.tabs{gap:22px;margin-left:38px}}
@media(prefers-reduced-motion:reduce){.finance-home *{transition:none!important}}
.finance-tasks .mhead>div{display:grid;gap:3px}.finance-tasks h3{display:flex;align-items:center;gap:8px;margin:0;color:#12141a;font-size:17px;font-weight:800}.finance-tasks .mhead p{margin:0;color:#475467;font-size:12.5px;line-height:1.45}.finance-tasks .add{margin-left:auto;padding:8px 14px!important;border-radius:999px;background:#f3f4f6!important;font-size:13px;font-weight:700}.finance-tasks .add:hover{background:#e9ebee!important}.task-list{display:grid;gap:8px;margin-top:14px}.task-list>button{display:grid;width:100%;grid-template-columns:36px minmax(0,1fr) auto 18px;align-items:center;gap:10px;padding:11px!important;border:1px solid #e7eaf0!important;border-radius:13px!important;background:#fff!important;text-align:left}.task-list>button:hover{border-color:#cfd5df!important;box-shadow:0 6px 16px rgba(18,20,26,.06);transform:translateY(-1px)}.task-list>button.done{background:#f8faf9!important}.task-list>button.done>strong{color:#087a49}.task-icon{display:grid;width:36px;height:36px;place-items:center;border-radius:10px;font-size:11px;font-style:normal;font-weight:850}.task-icon.pending{color:#b54708;background:#fff4e5}.task-list>button.done .task-icon.pending{color:#087a49;background:#eaf8f1}.task-icon.plan{color:#3f3ac4;background:#eeedff}.task-list span{display:grid;gap:3px;min-width:0}.task-list span b{color:#12141a;font-size:13.5px}.task-list span small{overflow:hidden;color:#475467;font-size:12.5px;text-overflow:ellipsis;white-space:nowrap}.task-list>button>strong{color:#12141a;font-size:13px}.task-list>button>.v-icon{color:#667085}
</style>
