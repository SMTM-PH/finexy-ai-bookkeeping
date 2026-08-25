<template>
    <div class="liquid-ledger-shell layout-wrapper layout-nav-type-vertical layout-navbar-sticky layout-footer-static layout-content-width-fluid"
         :class="{ 'layout-overlay-nav': mdAndDown, 'finexy-home-route': !!route.meta['finexy'] }">
        <div class="ledger-flow-line" aria-hidden="true"></div>
        <div class="layout-vertical-nav" :class="{'visible': showVerticalOverlayMenu, 'scrolled': isVerticalNavScrolled, 'overlay-nav': mdAndDown}">
            <div class="nav-header">
                <router-link to="/" class="app-logo d-flex align-center gap-x-3 app-title-wrapper">
                    <div class="d-flex">
                        <img alt="logo" class="main-logo" :src="APPLICATION_LOGO_PATH" />
                    </div>
                    <div class="brand-lockup">
                        <h1 class="font-weight-bold text-xl">{{ tt('global.app.title') }}</h1>
                        <span>PERSONAL MONEY OS</span>
                    </div>
                </router-link>
            </div>
            <perfect-scrollbar
                tag="ul" class="nav-items"
                :options="{ wheelPropagation: false }"
                @ps-scroll-y="handleNavScroll"
            >
                <li class="nav-link home-link">
                    <router-link to="/">
                        <v-icon class="nav-item-icon" :icon="mdiHomeOutline"/>
                        <span class="nav-item-title">{{ tt('Overview') }}</span>
                    </router-link>
                </li>
                <li class="nav-section-title">
                    <div class="title-wrapper">
                        <span class="title-text">{{ tt('Transaction Data') }}</span>
                    </div>
                </li>
                <li class="nav-link">
                    <router-link to="/transaction/list?pageType=1&dateType=7">
                        <v-icon class="nav-item-icon" :icon="mdiListBoxOutline"/>
                        <span class="nav-item-title d-inline-block">{{ tt('Transaction Details') }}</span>
                        <v-btn density="compact" color="secondary" variant="text" size="22"
                               class="ms-1" :icon="true" v-if="showAddTransactionButtonInDesktopNavbar"
                               @click="showAddDialogInTransactionListPage">
                            <v-icon :icon="mdiPlusCircle" size="22" />
                            <v-tooltip activator="parent">{{ tt('Add Transaction') }}</v-tooltip>
                        </v-btn>
                    </router-link>
                </li>
                <li class="nav-link">
                    <router-link to="/statistics/transaction">
                        <v-icon class="nav-item-icon" :icon="mdiChartPieOutline"/>
                        <span class="nav-item-title">{{ tt('Statistics & Analysis') }}</span>
                    </router-link>
                </li>
                <li class="nav-link">
                    <router-link to="/insights/explorer">
                        <v-icon class="nav-item-icon" :icon="mdiCompassOutline"/>
                        <span class="nav-item-title">{{ tt('Insights Explorer') }}</span>
                    </router-link>
                </li>
                <li class="nav-section-title">
                    <div class="title-wrapper">
                        <span class="title-text">{{ tt('Basis Data') }}</span>
                    </div>
                </li>
                <li class="nav-link">
                    <router-link to="/account/list">
                        <v-icon class="nav-item-icon" :icon="mdiCreditCardOutline"/>
                        <span class="nav-item-title">{{ tt('Accounts') }}</span>
                    </router-link>
                </li>
                <li class="nav-link">
                    <router-link to="/product/assets">
                        <v-icon class="nav-item-icon" :icon="mdiDevices"/>
                        <span class="nav-item-title">已购产品</span>
                    </router-link>
                </li>
                <li class="nav-link">
                    <router-link to="/category/list">
                        <v-icon class="nav-item-icon" :icon="mdiViewDashboardOutline"/>
                        <span class="nav-item-title">{{ tt('Transaction Categories') }}</span>
                    </router-link>
                </li>
                <li class="nav-link">
                    <router-link to="/tag/list">
                        <v-icon class="nav-item-icon" :icon="mdiTagOutline"/>
                        <span class="nav-item-title">{{ tt('Transaction Tags') }}</span>
                    </router-link>
                </li>
                <li class="nav-link">
                    <router-link to="/template/list">
                        <v-icon class="nav-item-icon" :icon="mdiClipboardTextOutline"/>
                        <span class="nav-item-title">{{ tt('Transaction Templates') }}</span>
                    </router-link>
                </li>
                <li class="nav-link" v-if="isUserScheduledTransactionEnabled()">
                    <router-link to="/schedule/list">
                        <v-icon class="nav-item-icon" :icon="mdiClipboardTextClockOutline"/>
                        <span class="nav-item-title">{{ tt('Scheduled Transactions') }}</span>
                    </router-link>
                </li>
                <li class="nav-section-title">
                    <div class="title-wrapper">
                        <span class="title-text">{{ tt('Miscellaneous') }}</span>
                    </div>
                </li>
                <li class="nav-link">
                    <router-link to="/exchange_rates">
                        <v-icon class="nav-item-icon" :icon="mdiSwapHorizontal"/>
                        <span class="nav-item-title">{{ tt('Exchange Rates Data') }}</span>
                    </router-link>
                </li>
                <li class="nav-link">
                    <a href="javascript:void(0);" @click="showMobileQrCode = true">
                        <v-icon class="nav-item-icon" :icon="mdiCellphone"/>
                        <span class="nav-item-title">{{ tt('Use on Mobile Device') }}</span>
                    </a>
                </li>
                <li class="nav-link">
                    <router-link to="/about">
                        <v-icon class="nav-item-icon" :icon="mdiInformationOutline"/>
                        <span class="nav-item-title">{{ tt('About') }}</span>
                    </router-link>
                </li>
            </perfect-scrollbar>
        </div>

        <div class="layout-content-wrapper">
            <div class="layout-navbar navbar-blur" :class="{ 'quest-complete-navbar': todayRecorded }">
                <div class="navbar-content-container">
                    <div class="d-flex h-100 align-center">
                        <v-btn class="ms-n3 me-2 d-lg-none" color="default" variant="text"
                               :icon="true" @click="showVerticalOverlayMenu = true">
                            <v-icon :icon="mdiMenu" size="24" />
                        </v-btn>
                        <div class="app-logo d-flex align-center gap-x-3 app-title-wrapper" v-if="mdAndDown">
                            <div class="d-flex">
                                <img alt="logo" class="main-logo" :src="APPLICATION_LOGO_PATH" />
                            </div>
                            <h1 class="font-weight-medium text-xl">{{ tt('global.app.title') }}</h1>
                        </div>
                        <div class="game-hud d-none d-lg-flex" :class="{ complete: todayRecorded }">
                            <div class="hud-fox">
                                <img src="/img/desktop/game/chestnut-fox-cub.png" alt="钱包伙伴栗子" />
                                <span><strong class="hud-brand-name">栗子记账</strong></span>
                            </div>
                            <div v-if="todayRecorded" class="hud-completion">
                                <span><strong>今日已记账</strong></span>
                            </div>
                            <div class="hud-progress" :class="{ complete: todayRecorded }">
                                <span><small>今日进度</small><strong>{{ todayRecorded ? '已完成' : '等待记账' }}</strong></span>
                                <i><b :style="{ width: todayRecorded ? '100%' : '12%' }"></b></i>
                                <em v-if="todayRecorded">100%</em>
                            </div>
                            <div v-if="!todayRecorded" class="hud-streak"><small>连续记录</small><strong>待点燃</strong></div>
                        </div>
                        <v-spacer />
                        <v-btn class="hud-quick-add me-2" color="primary" variant="flat" :prepend-icon="mdiPlusCircle"
                               @click="quickEntryOpen = !quickEntryOpen">{{ todayRecorded ? '再记一笔' : '快速记账' }}</v-btn>
                        <v-btn color="primary" variant="text" class="me-1" :icon="true">
                            <v-badge color="error" :content="inAppReminders.length" :model-value="inAppReminders.length > 0">
                                <v-icon :icon="mdiBellOutline" size="24" />
                            </v-badge>
                            <v-tooltip activator="parent">站内提醒</v-tooltip>
                            <v-menu activator="parent" width="390" location="bottom end" offset="10px" :close-on-content-click="true">
                                <v-list lines="three">
                                    <v-list-subheader>站内提醒</v-list-subheader>
                                    <v-list-item v-if="!inAppReminders.length" title="暂无提醒" subtitle="预算、周期账单和 AI 待处理事项会显示在这里" />
                                    <v-list-item v-for="reminder in inAppReminders.slice(0, 8)" :key="reminder.key"
                                                 :to="reminder.to" :prepend-icon="reminder.icon"
                                                 :title="reminder.title" :subtitle="reminder.detail" />
                                    <v-list-item v-if="inAppReminders.length > 8" to="/schedule/list"
                                                 :title="`另有 ${inAppReminders.length - 8} 条提醒`" subtitle="打开周期账单查看全部" />
                                </v-list>
                            </v-menu>
                        </v-btn>
                        <v-btn color="default" variant="text" class="me-2" :icon="true" disabled>
                            <v-icon :icon="mdiWeatherSunny" size="24" />
                            <v-tooltip activator="parent">深色主题即将开放</v-tooltip>
                        </v-btn>
                        <v-avatar class="cursor-pointer" variant="tonal"
                                  :color="currentUserAvatar ? 'rgba(0,0,0,0)' : 'primary'">
                            <v-img :src="currentUserAvatar" v-if="currentUserAvatar">
                                <template #placeholder>
                                    <div class="d-flex align-center justify-center fill-height bg-light-primary">
                                        <v-icon color="primary" :icon="mdiAccount"/>
                                    </div>
                                </template>
                            </v-img>
                            <v-icon :icon="mdiAccount" v-else-if="!currentUserAvatar"/>
                            <v-menu activator="parent" width="230" location="bottom end" offset="14px">
                                <v-list>
                                    <v-list-item>
                                        <template #prepend>
                                            <v-list-item-action>
                                                <v-avatar variant="tonal"
                                                          :color="currentUserAvatar ? 'rgba(0,0,0,0)' : 'primary'">
                                                    <v-img :src="currentUserAvatar" v-if="currentUserAvatar">
                                                        <template #placeholder>
                                                            <div class="d-flex align-center justify-center fill-height bg-light-primary">
                                                                <v-icon color="primary" :icon="mdiAccount"/>
                                                            </div>
                                                        </template>
                                                    </v-img>
                                                    <v-icon :icon="mdiAccount" v-else-if="!currentUserAvatar"/>
                                                </v-avatar>
                                            </v-list-item-action>
                                        </template>
                                        <v-list-item-title class="ms-2">
                                            {{ currentNickName }}
                                        </v-list-item-title>
                                    </v-list-item>
                                    <v-divider class="my-2"/>
                                    <v-list-item :prepend-icon="mdiAccountCogOutline"
                                                 :title="tt('User Settings')"
                                                 to="/user/settings"></v-list-item>
                                    <v-list-item :prepend-icon="mdiCogOutline"
                                                 :title="tt('Application Settings')"
                                                 to="/app/settings"></v-list-item>
                                    <v-divider class="my-2"/>
                                    <v-list-item :prepend-icon="mdiLockOutline"
                                                 :title="tt('Lock Application')"
                                                 v-if="isEnableApplicationLock"
                                                 @click="lock"></v-list-item>
                                    <v-list-item :disabled="logouting"
                                                 :prepend-icon="mdiLogout"
                                                 :title="tt('Log Out')"
                                                 @click="logout"></v-list-item>
                                </v-list>
                            </v-menu>
                        </v-avatar>
                    </div>
                </div>
            </div>
            <transition name="quick-entry-drop">
                <div v-if="quickEntryOpen" class="hud-quick-entry">
                    <v-icon :icon="mdiPlusCircle" size="22"/>
                    <input v-model="quickEntryText" type="text" placeholder="说一句，例如：午餐 16 元" aria-label="自然语言快速记账" @keyup.enter="openQuickEntry" />
                    <button type="button" :disabled="!quickEntryText.trim()" @click="openQuickEntry">开始识别</button>
                </div>
            </transition>
            <div class="layout-page-content">
                <div class="page-content-container">
                    <router-view :key="currentRoutePath" />
                </div>
            </div>
        </div>

        <switch-to-mobile-dialog v-model:show="showMobileQrCode" />

        <div class="layout-overlay" :class="{ 'visible': showVerticalOverlayMenu }" @click="showVerticalOverlayMenu = false"></div>

        <v-overlay class="justify-center align-center" :persistent="true" v-model="showLoading">
            <v-progress-circular indeterminate></v-progress-circular>
        </v-overlay>

        <snack-bar ref="snackbar" />
    </div>
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';

import { ref, computed, onMounted, onUnmounted, useTemplateRef } from 'vue';

import { useDisplay } from 'vuetify';
import { useRoute, useRouter } from 'vue-router';

import { useI18n } from '@/locales/helpers.ts';

import { useRootStore } from '@/stores/index.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useUserStore } from '@/stores/user.ts';
import { useDesktopPageStore } from '@/stores/desktopPage.ts';
import { useTransactionTemplatesStore } from '@/stores/transactionTemplate.ts';
import { useMonthlyBudgetStore } from '@/stores/monthlyBudget.ts';
import { useAIReviewItemsStore } from '@/stores/aiReviewItem.ts';
import { useOverviewStore } from '@/stores/overview.ts';

import { APPLICATION_LOGO_PATH } from '@/consts/asset.ts';
import { TemplateType } from '@/core/template.ts';

import { getShareCacheImageBlob } from '@/lib/cache.ts';
import { isUserScheduledTransactionEnabled } from '@/lib/server_settings.ts';
import { setExpenseAndIncomeAmountColor } from '@/lib/ui/common.ts';
import { DAILY_QUEST_UPDATED_EVENT, isNoTransactionsDay } from '@/lib/daily_quest.ts';
import logger from '@/lib/logger.ts';

import {
    mdiMenu,
    mdiHomeOutline,
    mdiListBoxOutline,
    mdiPlusCircle,
    mdiCreditCardOutline,
    mdiDevices,
    mdiViewDashboardOutline,
    mdiTagOutline,
    mdiClipboardTextOutline,
    mdiClipboardTextClockOutline,
    mdiChartPieOutline,
    mdiCompassOutline,
    mdiSwapHorizontal,
    mdiCogOutline,
    mdiCellphone,
    mdiInformationOutline,
    mdiWeatherSunny,
    mdiAccount,
    mdiAccountCogOutline,
    mdiLockOutline,
    mdiLogout,
    mdiBellOutline,
    mdiGauge,
    mdiAlertCircleOutline
} from '@mdi/js';

type SnackBarType = InstanceType<typeof SnackBar>;

const display = useDisplay();
const route = useRoute();
const router = useRouter();

const { tt, initLocale } = useI18n();

const rootStore = useRootStore();
const settingsStore = useSettingsStore();
const userStore = useUserStore();
const desktopPageStore = useDesktopPageStore();
const transactionTemplatesStore = useTransactionTemplatesStore();
const monthlyBudgetStore = useMonthlyBudgetStore();
const aiReviewItemsStore = useAIReviewItemsStore();
const overviewStore = useOverviewStore();

const snackbar = useTemplateRef<SnackBarType>('snackbar');

const logouting = ref<boolean>(false);
const isVerticalNavScrolled = ref<boolean>(false);
const showVerticalOverlayMenu = ref<boolean>(false);
const showLoading = ref<boolean>(false);
const showMobileQrCode = ref<boolean>(false);
const quickEntryOpen = ref<boolean>(false);
const quickEntryText = ref<string>('');
const noTransactionsToday = ref<boolean>(isNoTransactionsDay());

const mdAndDown = computed<boolean>(() => display.mdAndDown.value);
const currentRoutePath = computed<string>(() => route.path);

const currentNickName = computed<string>(() => userStore.currentUserNickname || tt('User'));
const currentUserAvatar = computed<string | null>(() => userStore.getUserAvatarUrl(userStore.currentUserBasicInfo, true));

const showAddTransactionButtonInDesktopNavbar = computed<boolean>(() => settingsStore.appSettings.showAddTransactionButtonInDesktopNavbar);
const isEnableApplicationLock = computed<boolean>(() => settingsStore.appSettings.applicationLock);
const todayRecorded = computed<boolean>(() => {
    const today = overviewStore.transactionOverview.today;
    return noTransactionsToday.value || (!!today && (today.incomeAmount !== 0 || today.expenseAmount !== 0));
});

interface InAppReminder {
    key: string;
    title: string;
    detail: string;
    to: string;
    icon: string;
}

const currentYearMonth = computed<number>(() => {
    const now = new Date();
    return now.getFullYear() * 100 + now.getMonth() + 1;
});

const inAppReminders = computed<InAppReminder[]>(() => {
    const reminders: InAppReminder[] = [];
    const budget = monthlyBudgetStore.budgets[currentYearMonth.value];
    const expense = overviewStore.transactionOverview.thisMonth?.expenseAmount ?? 0;
    if (budget && budget.amount > 0) {
        const percentage = expense * 100 / budget.amount;
        if (percentage >= 100) {
            reminders.push({ key: 'budget-exceeded', title: '本月预算已超出', detail: `当前已使用 ${percentage.toFixed(1)}%`, to: '/', icon: mdiGauge });
        } else if (percentage >= 80) {
            reminders.push({ key: 'budget-warning', title: '本月预算接近上限', detail: `当前已使用 ${percentage.toFixed(1)}%`, to: '/', icon: mdiGauge });
        }
    }

    if (aiReviewItemsStore.items.length > 0) {
        reminders.push({
            key: 'ai-review', title: `${aiReviewItemsStore.items.length} 条 AI 记录待处理`,
            detail: '自动入账信息不完整，请检查后确认', to: '/transaction/list?search=待确认', icon: mdiAlertCircleOutline
        });
    }

    const now = Math.floor(Date.now() / 1000);
    const sevenDaysLater = now + 7 * 24 * 60 * 60;
    const templates = transactionTemplatesStore.allTransactionTemplates[TemplateType.Schedule.type] || [];
    templates.filter(template => !template.hidden && template.nextScheduledTime && template.nextScheduledTime > now && template.nextScheduledTime <= sevenDaysLater)
        .sort((left, right) => (left.nextScheduledTime || 0) - (right.nextScheduledTime || 0))
        .forEach(template => {
            reminders.push({
                key: `schedule-${template.id}`, title: template.name,
                detail: `将在 ${new Date((template.nextScheduledTime || 0) * 1000).toLocaleString('zh-CN')} 自动入账`,
                to: '/schedule/list', icon: mdiClipboardTextClockOutline
            });
        });
    return reminders;
});

let reminderRefreshTimer: number | undefined;

function refreshInAppReminders(force: boolean): void {
    const tasks: Promise<unknown>[] = [
        monthlyBudgetStore.load(currentYearMonth.value, force),
        overviewStore.loadTransactionOverview({ force, loadLast11Months: false }),
        aiReviewItemsStore.load()
    ];
    if (isUserScheduledTransactionEnabled()) {
        tasks.push(transactionTemplatesStore.loadAllTemplates({ templateType: TemplateType.Schedule.type, force }));
    }
    void Promise.allSettled(tasks);
}

function refreshDailyQuestState(): void {
    noTransactionsToday.value = isNoTransactionsDay();
}

onMounted(() => {
    refreshInAppReminders(false);
    window.addEventListener(DAILY_QUEST_UPDATED_EVENT, refreshDailyQuestState);
    window.addEventListener('storage', refreshDailyQuestState);
    reminderRefreshTimer = window.setInterval(() => refreshInAppReminders(true), 15 * 60 * 1000);
});

onUnmounted(() => {
    window.removeEventListener(DAILY_QUEST_UPDATED_EVENT, refreshDailyQuestState);
    window.removeEventListener('storage', refreshDailyQuestState);
    if (reminderRefreshTimer !== undefined) window.clearInterval(reminderRefreshTimer);
});

function handleNavScroll(e: Event): void {
    isVerticalNavScrolled.value = (e.target as HTMLElement).scrollTop > 0;
}

function clearShareImageCache(): void {
    getShareCacheImageBlob().then(blob => {
        if (blob) {
            logger.warn('desktop version does not support receving shared image, the share image cache has been cleared');
        }
    });
}

function lock(): void {
    rootStore.lock();
    router.replace('/unlock');
}

function logout(): void {
    logouting.value = true;
    showLoading.value = true;

    rootStore.logout().then(() => {
        logouting.value = false;
        showLoading.value = false;

        settingsStore.clearAppSettings();

        const localeDefaultSettings = initLocale(userStore.currentUserLanguage, settingsStore.appSettings.timeZone);
        settingsStore.updateLocalizedDefaultSettings(localeDefaultSettings);

        setExpenseAndIncomeAmountColor(userStore.currentUserExpenseAmountColor, userStore.currentUserIncomeAmountColor);

        router.replace('/login');
    }).catch(error => {
        logouting.value = false;
        showLoading.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function showAddDialogInTransactionListPage(): void {
    desktopPageStore.setShowAddTransactionDialogInTransactionList();
}

function openQuickEntry(): void {
    if (!quickEntryText.value.trim()) return;
    router.push({ path: '/', query: { quickEntry: quickEntryText.value.trim() } });
    quickEntryOpen.value = false;
}

clearShareImageCache();
</script>

<style>
.main-logo {
    width: 1.75rem;
    height: 1.75rem;
}

.nav-link.home-link > a:not(.router-link-exact-active):hover::before {
    opacity: calc(var(--v-hover-opacity)* var(--v-theme-overlay-multiplier));
}

/* The Finexy home page owns its complete shell. Keep the legacy shell on every other route. */
.liquid-ledger-shell.finexy-home-route > .ledger-flow-line,
.liquid-ledger-shell.finexy-home-route > .layout-vertical-nav,
.liquid-ledger-shell.finexy-home-route > .layout-content-wrapper > .layout-navbar,
.liquid-ledger-shell.finexy-home-route > .layout-content-wrapper > .hud-quick-entry {
    display: none !important;
}
</style>
