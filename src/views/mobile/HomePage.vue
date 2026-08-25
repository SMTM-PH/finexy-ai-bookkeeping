<template>
    <f7-page ptr @ptr:refresh="reload" @page:afterin="onPageAfterIn">
        <f7-navbar>
            <f7-nav-title :title="tt('global.app.title')"></f7-nav-title>
        </f7-navbar>

        <section v-if="!loading && !mobileWalletAccounts.length" class="mobile-onboarding" aria-labelledby="mobile-onboarding-title">
            <div class="mobile-onboarding-icon"><f7-icon f7="wallet_pass"></f7-icon></div>
            <div class="mobile-onboarding-copy">
                <small>开始使用 · 0 / 1</small>
                <strong id="mobile-onboarding-title">先添加一个账户</strong>
                <span>填写当前余额后，才能准确记录支出与资金流转。</span>
            </div>
            <f7-link href="/account/add" class="mobile-onboarding-action" aria-label="添加首个账户">
                添加
            </f7-link>
        </section>

        <section class="mobile-wallet" aria-labelledby="mobile-wallet-title">
            <div class="mobile-section-heading">
                <div>
                    <small>DIGITAL WALLET</small>
                    <strong id="mobile-wallet-title">我的钱包</strong>
                </div>
                <f7-link href="/account/list">管理</f7-link>
            </div>
            <div v-if="loading" class="mobile-wallet-skeleton skeleton-text" aria-label="正在加载账户">
                <span></span><span></span>
            </div>
            <div v-else-if="mobileWalletAccounts.length" class="mobile-wallet-stack">
                <f7-link v-for="account in mobileWalletAccounts" :key="account.id" href="/account/list"
                         class="mobile-wallet-card" :class="mobileWalletCardClass(account.category)"
                         :aria-label="`查看账户 ${account.name}`">
                    <span class="mobile-wallet-card-top">
                        <small>{{ mobileWalletCategoryLabel(account.category) }}</small>
                        <f7-icon :f7="account.isLiability ? 'creditcard' : 'wave_3_right'"></f7-icon>
                    </span>
                    <span class="mobile-wallet-chip" aria-hidden="true"></span>
                    <span class="mobile-wallet-card-bottom">
                        <span><small>{{ account.isLiability ? '待还金额' : '可用余额' }}</small><strong>{{ formatMobileWalletBalance(account.balance, account.currency) }}</strong></span>
                        <b>{{ account.name }}</b>
                    </span>
                </f7-link>
            </div>
            <f7-link v-else href="/account/add" class="mobile-wallet-empty">
                <f7-icon f7="plus"></f7-icon>
                <span><strong>添加账户卡</strong><small>微信零钱、银行卡、信用卡或现金</small></span>
                <f7-icon f7="chevron_right"></f7-icon>
            </f7-link>
        </section>

        <f7-card class="home-summary-card no-margin-top" :class="{ 'skeleton-text': loading }">
            <f7-card-header class="display-block" style="padding-top: 120px;">
                <p class="no-margin">
                    <span class="card-header-content" v-if="loading">
                        <span class="home-summary-month">Month</span>
                        <span>·</span>
                        <small>Expense</small>
                    </span>
                    <span class="card-header-content" v-else-if="!loading">
                        <span class="home-summary-month">{{ displayDateRange?.thisMonth?.displayTime }}</span>
                        <span>·</span>
                        <small>{{ tt('Expense') }}</small>
                    </span>
                </p>
                <p class="no-margin">
                    <span class="month-expense" v-if="loading">0.00 USD</span>
                    <span class="month-expense" v-else-if="!loading">{{ transactionOverview && transactionOverview.thisMonth ? getDisplayExpenseAmount(transactionOverview.thisMonth) : '-' }}</span>
                    <f7-link class="display-inline-flex margin-inline-start-half" @click="showAmountInHomePage = !showAmountInHomePage">
                        <f7-icon class="ebk-hide-icon" :f7="showAmountInHomePage ? 'eye_slash_fill' : 'eye_fill'"></f7-icon>
                    </f7-link>
                </p>
                <p class="no-margin">
                    <small class="home-summary-misc" v-if="loading">Monthly income 0.00 USD</small>
                    <small class="home-summary-misc" v-else-if="!loading">
                        <span>{{ tt('Monthly income') }}</span>
                        <span>{{ transactionOverview && transactionOverview.thisMonth ? getDisplayIncomeAmount(transactionOverview.thisMonth) : '-' }}</span>
                    </small>
                </p>
            </f7-card-header>
        </f7-card>

        <f7-list strong inset dividers class="margin-top overview-transaction-list" :class="{ 'skeleton-text': loading }">
            <f7-list-item :link="`/transaction/list?${overviewStore.getTransactionListPageParams({ dateType: DateRange.Today.type })}`" chevron-center>
                <template #media>
                    <f7-icon f7="calendar_today"></f7-icon>
                </template>
                <template #title>
                    <div class="padding-top-half">
                        <span v-if="loading">Today</span>
                        <span v-else-if="!loading">{{ tt('Today') }}</span>
                    </div>
                </template>
                <template #footer>
                    <div class="overview-transaction-footer padding-bottom-half">
                        <span v-if="loading">MM/DD/YYYY</span>
                        <span v-else-if="!loading">{{ displayDateRange?.today?.displayTime }}</span>
                    </div>
                </template>
                <template #after>
                    <div class="overview-transaction-amount">
                        <div class="text-income text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.today && transactionOverview.today.valid">{{ getDisplayIncomeAmount(transactionOverview.today) }}</small>
                        </div>
                        <div class="text-expense text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.today && transactionOverview.today.valid">{{ getDisplayExpenseAmount(transactionOverview.today) }}</small>
                        </div>
                    </div>
                </template>
            </f7-list-item>

            <f7-list-item :link="`/transaction/list?${overviewStore.getTransactionListPageParams({ dateType: DateRange.ThisWeek.type })}`" chevron-center>
                <template #media>
                    <f7-icon f7="calendar"></f7-icon>
                </template>
                <template #title>
                    <div class="padding-top-half">
                        <span v-if="loading">This Week</span>
                        <span v-else-if="!loading">{{ tt('This Week') }}</span>
                    </div>
                </template>
                <template #footer>
                    <div class="overview-transaction-footer padding-bottom-half">
                        <span v-if="loading">MM/DD</span>
                        <span v-else-if="!loading">{{ displayDateRange?.thisWeek?.startTime }}</span>
                        <span>-</span>
                        <span v-if="loading">MM/DD</span>
                        <span v-else-if="!loading">{{ displayDateRange?.thisWeek?.endTime }}</span>
                    </div>
                </template>
                <template #after>
                    <div class="overview-transaction-amount">
                        <div class="text-income text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.thisWeek && transactionOverview.thisWeek.valid">{{ getDisplayIncomeAmount(transactionOverview.thisWeek) }}</small>
                        </div>
                        <div class="text-expense text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.thisWeek && transactionOverview.thisWeek.valid">{{ getDisplayExpenseAmount(transactionOverview.thisWeek) }}</small>
                        </div>
                    </div>
                </template>
            </f7-list-item>

            <f7-list-item :link="`/transaction/list?${overviewStore.getTransactionListPageParams({ dateType: DateRange.ThisMonth.type })}`" chevron-center>
                <template #media>
                    <f7-icon f7="calendar"></f7-icon>
                </template>
                <template #title>
                    <div class="padding-top-half">
                        <span v-if="loading">This Month</span>
                        <span v-else-if="!loading">{{ tt('This Month') }}</span>
                    </div>
                </template>
                <template #footer>
                    <div class="overview-transaction-footer padding-bottom-half">
                        <span v-if="loading">MM/DD</span>
                        <span v-else-if="!loading">{{ displayDateRange?.thisMonth?.startTime }}</span>
                        <span>-</span>
                        <span v-if="loading">MM/DD</span>
                        <span v-else-if="!loading">{{ displayDateRange?.thisMonth?.endTime }}</span>
                    </div>
                </template>
                <template #after>
                    <div class="overview-transaction-amount">
                        <div class="text-income text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.thisMonth && transactionOverview.thisMonth.valid">{{ getDisplayIncomeAmount(transactionOverview.thisMonth) }}</small>
                        </div>
                        <div class="text-expense text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.thisMonth && transactionOverview.thisMonth.valid">{{ getDisplayExpenseAmount(transactionOverview.thisMonth) }}</small>
                        </div>
                    </div>
                </template>
            </f7-list-item>

            <f7-list-item :link="`/transaction/list?${overviewStore.getTransactionListPageParams({ dateType: DateRange.ThisYear.type })}`" chevron-center>
                <template #media>
                    <f7-icon f7="square_stack_3d_up"></f7-icon>
                </template>
                <template #title>
                    <div class="padding-top-half">
                        <span v-if="loading">This Year</span>
                        <span v-else-if="!loading">{{ tt('This Year') }}</span>
                    </div>
                </template>
                <template #footer>
                    <div class="overview-transaction-footer padding-bottom-half">
                        <span v-if="loading">YYYY</span>
                        <span v-else-if="!loading">{{ displayDateRange?.thisYear?.displayTime }}</span>
                    </div>
                </template>
                <template #after>
                    <div class="overview-transaction-amount">
                        <div class="text-income text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.thisYear && transactionOverview.thisYear.valid">{{ getDisplayIncomeAmount(transactionOverview.thisYear) }}</small>
                        </div>
                        <div class="text-expense text-align-right">
                            <small v-if="loading">0.00 USD</small>
                            <small v-else-if="!loading && transactionOverview.thisYear && transactionOverview.thisYear.valid">{{ getDisplayExpenseAmount(transactionOverview.thisYear) }}</small>
                        </div>
                    </div>
                </template>
            </f7-list-item>
        </f7-list>

        <f7-toolbar tabbar icons bottom class="main-tabbar">
            <f7-link class="link" href="/transaction/list">
                <f7-icon f7="square_list"></f7-icon>
                <span class="tabbar-label">{{ tt('Details') }}</span>
            </f7-link>
            <f7-link class="link" href="/account/list">
                <f7-icon f7="creditcard"></f7-icon>
                <span class="tabbar-label">{{ tt('Accounts') }}</span>
            </f7-link>
            <!-- "homepage-add-button" must have the "dragenabled" class, otherwise the popover disappears immediately after the second long press -->
            <f7-link id="homepage-add-button" class="link dragenabled"
                     href="/transaction/add" @taphold="openTransactionTemplatePopover">
                <f7-icon f7="plus_square" class="ebk-tarbar-big-icon"></f7-icon>
            </f7-link>
            <f7-link class="link" href="/statistic/transaction">
                <f7-icon f7="chart_pie"></f7-icon>
                <span class="tabbar-label">{{ tt('Statistics') }}</span>
            </f7-link>
            <f7-link class="link" href="/settings">
                <f7-icon f7="gear_alt"></f7-icon>
                <span class="tabbar-label">{{ tt('Settings') }}</span>
            </f7-link>
        </f7-toolbar>

        <f7-popover class="template-popover-menu" target-el="#homepage-add-button"
                    v-model:opened="showTransactionTemplatePopover">
            <f7-list dividers v-if="isTransactionFromAITextRecognitionEnabled() || isTransactionFromAIImageRecognitionEnabled() || (allTransactionTemplates && allTransactionTemplates.length)">
                <f7-list-item key="AIClipboardTextRecognition" link="#" no-chevron popover-close
                              :title="tt('AI Clipboard Text Recognition')"
                              @click="addByRecognizingClipboardText"
                              v-if="isTransactionFromAITextRecognitionEnabled()">
                    <template #media>
                        <f7-icon f7="wand_stars"></f7-icon>
                    </template>
                </f7-list-item>
                <f7-list-item key="AIImageRecognition" link="#" no-chevron popover-close
                              :title="tt('AI Image Recognition')"
                              @click="showAIReceiptImageRecognitionSheet = true"
                              v-if="isTransactionFromAIImageRecognitionEnabled()">
                    <template #media>
                        <f7-icon f7="wand_stars"></f7-icon>
                    </template>
                </f7-list-item>
                <f7-list-item popover-close :key="template.id" :title="template.name"
                              :link="'/transaction/add?templateId=' + template.id"
                              v-for="template in allTransactionTemplates">
                    <template #media>
                        <f7-icon f7="doc_plaintext"></f7-icon>
                    </template>
                </f7-list-item>
            </f7-list>
        </f7-popover>

        <a-i-image-recognition-sheet ref="aiImageRecognitionSheet"
                                     v-model:show="showAIReceiptImageRecognitionSheet"
                                     @recognition:change="onReceiptRecognitionChanged"/>
    </f7-page>
</template>

<script setup lang="ts">
import AIImageRecognitionSheet, { type AIImageRecognitionResult } from '@/components/mobile/AIImageRecognitionSheet.vue';

import { ref, computed, useTemplateRef } from 'vue';
import type { Router } from 'framework7/types';

import { useI18n } from '@/locales/helpers.ts';
import { useI18nUIComponents, isiOS } from '@/lib/ui/mobile.ts';
import { useHomePageBase } from '@/views/base/HomePageBase.ts';

import { useSettingsStore } from '@/stores/setting.ts';
import { useAccountsStore } from '@/stores/account.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useTransactionTemplatesStore } from '@/stores/transactionTemplate.ts';
import { useOverviewStore } from '@/stores/overview.ts';

import { DateRange } from '@/core/datetime.ts';
import { AccountCategory } from '@/core/account.ts';
import { TemplateType } from '@/core/template.ts';
import type { Account } from '@/models/account.ts';
import { TransactionTemplate } from '@/models/transaction_template.ts';

import { isFunction } from '@/lib/common.ts';
import { isUserLogined, isUserUnlocked } from '@/lib/userstate.ts';
import { getShareCacheImageBlob } from '@/lib/cache.ts';
import {
    isTransactionFromAITextRecognitionEnabled,
    isTransactionFromAIImageRecognitionEnabled
} from '@/lib/server_settings.ts';
import logger from '@/lib/logger.ts';

type AIImageRecognitionSheetType = InstanceType<typeof AIImageRecognitionSheet>;

const props = defineProps<{
    f7router: Router.Router;
}>();

const { tt, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const { showToast } = useI18nUIComponents();

const {
    showAmountInHomePage,
    displayDateRange,
    transactionOverview,
    getDisplayIncomeAmount,
    getDisplayExpenseAmount
} = useHomePageBase();

const settingsStore = useSettingsStore();
const accountsStore = useAccountsStore();
const transactionCategoriesStore = useTransactionCategoriesStore();
const transactionTemplatesStore = useTransactionTemplatesStore();
const overviewStore = useOverviewStore();

const aiImageRecognitionSheet = useTemplateRef<AIImageRecognitionSheetType>('aiImageRecognitionSheet');

const loading = ref<boolean>(true);
const showTransactionTemplatePopover = ref<boolean>(false);
const showAIReceiptImageRecognitionSheet = ref<boolean>(false);
const mobileWalletAccounts = computed<Account[]>(() => accountsStore.allVisiblePlainAccounts.slice(0, 4));

const allTransactionTemplates = computed<TransactionTemplate[]>(() => {
    const allTemplates = transactionTemplatesStore.allVisibleTemplates;
    return allTemplates[TemplateType.Normal.type] || [];
});

function formatMobileWalletBalance(balance: number, currency: string): string {
    return formatAmountToLocalizedNumeralsWithCurrency(balance, currency);
}

function mobileWalletCardClass(category: number): string {
    if (category === AccountCategory.CreditCard.type || category === AccountCategory.DebtAccount.type) return 'mobile-wallet-card-coral';
    if (category === AccountCategory.VirtualAccount.type) return 'mobile-wallet-card-cyan';
    if (category === AccountCategory.Cash.type) return 'mobile-wallet-card-amber';
    if (category === AccountCategory.InvestmentAccount.type) return 'mobile-wallet-card-ink';
    return 'mobile-wallet-card-violet';
}

function mobileWalletCategoryLabel(category: number): string {
    if (category === AccountCategory.Cash.type) return '现金';
    if (category === AccountCategory.CreditCard.type) return '信用账户';
    if (category === AccountCategory.VirtualAccount.type) return '数字余额';
    if (category === AccountCategory.DebtAccount.type) return '负债账户';
    if (category === AccountCategory.InvestmentAccount.type) return '投资账户';
    return '储蓄账户';
}

function openTransactionTemplatePopover(): void {
    if (isTransactionFromAIImageRecognitionEnabled() || (allTransactionTemplates.value && allTransactionTemplates.value.length)) {
        showTransactionTemplatePopover.value = true;
    }
}

function init(): void {
    if (isUserLogined() && isUserUnlocked()) {
        loading.value = true;

        const promises = [
            getShareCacheImageBlob(),
            accountsStore.loadAllAccounts({ force: false }),
            transactionCategoriesStore.loadAllCategories({ force: false }),
            transactionTemplatesStore.loadAllTemplates({ templateType: TemplateType.Normal.type,  force: false }),
            overviewStore.loadTransactionOverview({ force: false })
        ];

        Promise.all(promises).then(responses => {
            if (responses[0] && responses[0] instanceof Blob) {
                aiImageRecognitionSheet.value?.loadImage(responses[0]);
                showAIReceiptImageRecognitionSheet.value = true;
            }

            loading.value = false;
        }).catch(error => {
            loading.value = false;

            if (!error.processed) {
                showToast(error.message || error);
            }
        });
    }
}

function reload(done?: () => void): void {
    const force = !!done;

    overviewStore.loadTransactionOverview({
        force: force
    }).then(() => {
        done?.();

        if (force) {
            showToast('Data has been updated');
        }
    }).catch(error => {
        done?.();

        if (!error.processed) {
            showToast(error.message || error);
        }
    });
}

function addByRecognizingClipboardText(): void {
    if (navigator.clipboard && isFunction(navigator.clipboard.readText) && !isiOS()) {
        navigator.clipboard.readText().then(text => {
            const clipboardText = text && text.trim() ? text.trim() : '';
            props.f7router.navigate('/transaction/add', {
                props: {
                    autoRecognizeClipboardText: clipboardText,
                }
            });
        }).catch(error => {
            logger.error('failed to read clipboard', error);
            props.f7router.navigate('/transaction/add', {
                props: {
                    autoRecognizeClipboardText: '',
                }
            });
        });
    } else {
        props.f7router.navigate('/transaction/add', {
            props: {
                autoRecognizeClipboardText: '',
            }
        });
    }
}

function onReceiptRecognitionChanged(result: AIImageRecognitionResult): void {
    const recognizedResponse = result.response;
    const autoUploadRecognizedImage = settingsStore.appSettings.autoUploadTransactionPictureForAIRecognition;
    const params: string[] = [];

    if (recognizedResponse.type) {
        params.push(`type=${recognizedResponse.type}`);
    }

    if (recognizedResponse.time) {
        params.push(`time=${recognizedResponse.time}`);
    }

    if (recognizedResponse.categoryId) {
        params.push(`categoryId=${recognizedResponse.categoryId}`);
    }

    if (recognizedResponse.sourceAccountId) {
        params.push(`accountId=${recognizedResponse.sourceAccountId}`);
    }

    if (recognizedResponse.destinationAccountId) {
        params.push(`destinationAccountId=${recognizedResponse.destinationAccountId}`);
    }

    if (recognizedResponse.sourceAmount) {
        params.push(`amount=${recognizedResponse.sourceAmount}`);
    }

    if (recognizedResponse.destinationAmount) {
        params.push(`destinationAmount=${recognizedResponse.destinationAmount}`);
    }

    if (recognizedResponse.tagIds) {
        params.push(`tagIds=${recognizedResponse.tagIds.join(',')}`);
    }

    if (recognizedResponse.comment) {
        params.push(`comment=${encodeURIComponent(recognizedResponse.comment)}`);
    }

    params.push(`noTransactionDraft=true`);

    props.f7router.navigate(`/transaction/add?${params.join('&')}`, {
        props: {
            autoUploadPicture: autoUploadRecognizedImage ? result.imageFile : undefined,
        }
    });
}

function onPageAfterIn(): void {
    if (!loading.value) {
        reload();
    }
}

init();
</script>

<style>
.mobile-onboarding {
    display: grid;
    grid-template-columns: 44px minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    margin: 16px;
    padding: 14px;
    border: 1px solid color-mix(in srgb, var(--f7-theme-color) 24%, transparent);
    border-radius: 18px;
    background: color-mix(in srgb, var(--f7-theme-color) 8%, var(--f7-card-bg-color));
}
.mobile-onboarding-icon { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 14px; color: var(--f7-theme-color); background: color-mix(in srgb, var(--f7-theme-color) 14%, transparent); }
.mobile-onboarding-copy { display: grid; gap: 2px; min-width: 0; }
.mobile-onboarding-copy small { color: var(--f7-theme-color); font-size: 10px; font-weight: 750; letter-spacing: .08em; }
.mobile-onboarding-copy strong { font-size: 15px; }
.mobile-onboarding-copy span { color: var(--f7-text-color-secondary); font-size: 12px; line-height: 1.4; }
.mobile-onboarding-action { min-width: 52px; min-height: 44px; justify-content: center; font-weight: 700; touch-action: manipulation; }

.mobile-wallet { margin: 20px 16px 24px; }
.mobile-section-heading { display: flex; align-items: end; justify-content: space-between; margin-bottom: 12px; }
.mobile-section-heading > div { display: grid; gap: 3px; }
.mobile-section-heading small { color: var(--f7-theme-color); font: 750 10px/1 ui-monospace, monospace; letter-spacing: .14em; }
.mobile-section-heading strong { font-size: 22px; line-height: 1.2; }
.mobile-section-heading > a { min-width: 52px; min-height: 44px; justify-content: flex-end; touch-action: manipulation; }
.mobile-wallet-stack { display: grid; }
.mobile-wallet-card {
    position: relative;
    display: grid;
    min-height: 164px;
    padding: 16px;
    overflow: hidden;
    border: 1px solid rgba(255,255,255,.24);
    border-radius: 20px;
    color: #fff;
    box-shadow: 0 14px 28px rgba(13, 20, 48, .18);
    touch-action: manipulation;
    transition: transform 180ms ease, filter 180ms ease;
}
.mobile-wallet-card + .mobile-wallet-card { margin-top: -104px; }
.mobile-wallet-card::after { content: ""; position: absolute; width: 190px; height: 190px; inset: -120px -60px auto auto; border: 1px solid rgba(255,255,255,.18); border-radius: 50%; pointer-events: none; }
.mobile-wallet-card:active { transform: scale(.985); filter: brightness(.94); }
.mobile-wallet-card-violet { background: linear-gradient(145deg, #6b5ce8, #352c81); }
.mobile-wallet-card-cyan { background: linear-gradient(145deg, #117f8d, #15345d); }
.mobile-wallet-card-coral { background: linear-gradient(145deg, #bd5468, #552a64); }
.mobile-wallet-card-amber { background: linear-gradient(145deg, #b87927, #58352c); }
.mobile-wallet-card-ink { background: linear-gradient(145deg, #29344c, #0b101d); }
.mobile-wallet-card-top, .mobile-wallet-card-bottom { position: relative; z-index: 1; display: flex; justify-content: space-between; }
.mobile-wallet-card-top { align-items: center; align-self: start; font-weight: 750; letter-spacing: .1em; opacity: .82; }
.mobile-wallet-chip { align-self: start; width: 36px; height: 26px; margin-top: 14px; border: 1px solid rgba(79,55,22,.3); border-radius: 7px; background: linear-gradient(135deg, #f4dc9d, #b98737); }
.mobile-wallet-card-bottom { align-items: end; align-self: end; }
.mobile-wallet-card-bottom > span { display: grid; }
.mobile-wallet-card-bottom small { font-size: 10px; opacity: .66; }
.mobile-wallet-card-bottom strong { font-size: 18px; font-variant-numeric: tabular-nums; }
.mobile-wallet-card-bottom b { max-width: 42%; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.mobile-wallet-empty { display: grid; grid-template-columns: 44px minmax(0,1fr) 24px; align-items: center; gap: 12px; min-height: 78px; padding: 12px; border: 1px dashed color-mix(in srgb, var(--f7-theme-color) 38%, transparent); border-radius: 18px; background: color-mix(in srgb, var(--f7-theme-color) 6%, var(--f7-card-bg-color)); touch-action: manipulation; }
.mobile-wallet-empty > i:first-child { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 14px; background: color-mix(in srgb, var(--f7-theme-color) 13%, transparent); }
.mobile-wallet-empty > span { display: grid; gap: 2px; }
.mobile-wallet-empty small { color: var(--f7-text-color-secondary); font-size: 11px; }
.mobile-wallet-skeleton { display: grid; gap: 10px; }
.mobile-wallet-skeleton span { display: block; height: 164px; border-radius: 20px; background: var(--f7-skeleton-color); }

.home-summary-card {
    position: relative;
    overflow: hidden;
    color: #fff;
    border: 0;
    background:
        radial-gradient(circle at 80% 10%, rgba(40, 199, 203, 0.48), transparent 11rem),
        radial-gradient(circle at 5% 110%, rgba(139, 124, 255, 0.8), transparent 17rem),
        linear-gradient(145deg, #111a35, #0c3540);
    box-shadow: 0 22px 52px rgba(20, 28, 62, 0.24);
    animation: mobile-summary-enter 480ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.home-summary-card::after {
    content: "";
    position: absolute;
    width: 210px;
    height: 210px;
    inset: -125px -60px auto auto;
    border: 1px solid rgba(255, 255, 255, 0.16);
    border-radius: 50%;
    pointer-events: none;
}

.home-summary-card .home-summary-month {
    font-size: 1.3em;
}

.home-summary-card .month-expense {
    font-size: 1.5em;
}

.home-summary-card .home-summary-misc {
    opacity: 0.6;
}

.home-summary-misc > span {
    margin-inline-end: 4px;
}

.home-summary-misc > span:last-child {
    margin-inline-end: 0;
}

.dark .home-summary-card {
    background:
        radial-gradient(circle at 80% 10%, rgba(40, 199, 203, 0.42), transparent 11rem),
        linear-gradient(145deg, #111a35, #0b2a35);
}

.dark .home-summary-card a {
    color: var(--f7-text-color);
    opacity: 0.6;
}

.overview-transaction-list .item-title > div {
    overflow: hidden;
    text-overflow: ellipsis;
}

.overview-transaction-list .item-after {
    max-width: 100%;
}

.overview-transaction-list .overview-transaction-footer {
    padding-top: 6px;
    font-size: var(--ebk-large-footer-font-size);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.overview-transaction-list .overview-transaction-footer > span {
    margin-inline-end: 4px;
}

.overview-transaction-list .overview-transaction-amount {
    max-width: 100%;
}

.overview-transaction-list .overview-transaction-amount > div {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
}

.tabbar.main-tabbar .link i + span.tabbar-label {
    margin-top: var(--ebk-icon-text-margin);
}

.tabbar.main-tabbar .link i.ebk-tarbar-big-icon {
    font-size: var(--ebk-big-icon-button-size);
    width: var(--ebk-big-icon-button-size);
    height: var(--ebk-big-icon-button-size);
    line-height: var(--ebk-big-icon-button-size);
}

.template-popover-menu .popover-inner {
    max-height: 400px;
    overflow-y: auto;
}

@keyframes mobile-summary-enter {
    from { opacity: 0; transform: translateY(12px) scale(0.985); }
    to { opacity: 1; transform: translateY(0) scale(1); }
}

@media (max-width: 374px) {
    .mobile-onboarding { grid-template-columns: 44px minmax(0, 1fr); }
    .mobile-onboarding-action { grid-column: 2; justify-content: flex-start; }
    .mobile-wallet-card { min-height: 152px; }
    .mobile-wallet-card + .mobile-wallet-card { margin-top: -94px; }
}

@media (orientation: landscape) and (max-height: 520px) {
    .mobile-wallet-stack { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    .mobile-wallet-card + .mobile-wallet-card { margin-top: 0; }
}

@media (prefers-reduced-motion: reduce) {
    .mobile-wallet-card { transition: none; }
    .mobile-wallet-card:active { transform: none; }
}
</style>
