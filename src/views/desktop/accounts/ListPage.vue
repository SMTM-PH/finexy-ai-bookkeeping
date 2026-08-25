<template>
    <v-row class="match-height">
        <v-col cols="12">
            <v-card>
                <v-layout>
                    <v-navigation-drawer :permanent="alwaysShowNav" v-model="showNav">
                        <div class="account-narrative">
                            <span>YOUR ASSET STORY</span>
                            <h2>钱不是数字，<br>是正在发生的选择。</h2>
                            <p>沿着账户轨道浏览资产、负债与日常资金。每一张卡，代表一种资金角色。</p>
                        </div>
                        <div class="mx-6 my-4">
                            <span class="text-subtitle-2">{{ tt('Net assets') }}</span>
                            <p class="account-statistic-item-value text-income text-truncate mt-1 mb-3">
                                <span v-if="!loading || allAccountCount > 0">{{ netAssets }}</span>
                                <span v-else-if="loading && allAccountCount <= 0">
                                    <v-skeleton-loader class="skeleton-no-margin pt-2 pb-1" type="text" :loading="true"></v-skeleton-loader>
                                </span>
                            </p>
                            <span class="text-subtitle-2">{{ tt('Total liabilities') }}</span>
                            <p class="account-statistic-item-value text-expense text-truncate mt-1 mb-3">
                                <span v-if="!loading || allAccountCount > 0">{{ totalLiabilities }}</span>
                                <span v-else-if="loading && allAccountCount <= 0">
                                    <v-skeleton-loader class="skeleton-no-margin pt-2 pb-1" type="text" :loading="true"></v-skeleton-loader>
                                </span>
                            </p>
                            <span class="text-subtitle-2">{{ tt('Total assets') }}</span>
                            <p class="account-statistic-item-value mt-1">
                                <span v-if="!loading || allAccountCount > 0">{{ totalAssets }}</span>
                                <span v-else-if="loading && allAccountCount <= 0">
                                    <v-skeleton-loader class="skeleton-no-margin pt-2 pb-1" type="text" :loading="true"></v-skeleton-loader>
                                </span>
                            </p>
                        </div>
                        <v-divider />
                        <v-tabs show-arrows class="account-category-tabs my-4" direction="vertical"
                                :disabled="loading" v-model="activeAccountCategoryType">
                            <v-tab class="tab-text-truncate" :key="accountCategory.type" :value="accountCategory.type"
                                   v-for="accountCategory in AccountCategory.values(customAccountCategoryOrder)"
                                   v-show="!hideAccountCategoriesWithoutAccounts || (allCategorizedAccountsMap[accountCategory.type] && allCategorizedAccountsMap[accountCategory.type]!.accounts.length > 0)">
                                <ItemIcon icon-type="account" :icon-id="accountCategory.defaultAccountIconId" />
                                <div class="d-flex flex-column text-truncate ms-2">
                                    <small class="text-truncate text-start smaller" v-if="!loading || allAccountCount > 0">{{ accountCategoryTotalBalance(accountCategory) }}</small>
                                    <small class="text-truncate text-start smaller my-1" v-else-if="loading && allAccountCount <= 0">
                                        <v-skeleton-loader class="skeleton-no-margin"
                                                           width="100px" height="16" type="text" :loading="true"></v-skeleton-loader>
                                    </small>
                                    <span class="text-truncate text-start">{{ tt(accountCategory.name) }}</span>
                                </div>
                            </v-tab>
                        </v-tabs>
                    </v-navigation-drawer>
                    <v-main>
                        <v-window class="d-flex flex-grow-1 disable-tab-transition w-100-window-container" v-model="activeTab">
                            <v-window-item value="accountPage">
                                <v-card variant="flat" min-height="780">
                                    <template #title>
                                        <div class="title-and-toolbar d-flex align-center">
                                            <v-btn class="me-3 d-md-none" density="compact" color="default" variant="plain"
                                                   :ripple="false" :icon="true" @click="showNav = !showNav">
                                                <v-icon :icon="mdiMenu" size="24" />
                                            </v-btn>
                                            <span>{{ tt('Account List') }}</span>
                                            <v-btn class="ms-3" color="default" variant="outlined"
                                                   :disabled="loading" @click="add">{{ tt('Add') }}</v-btn>
                                            <v-btn class="ms-3" color="primary" variant="tonal"
                                                   :disabled="loading" @click="saveSortResult"
                                                   v-if="displayOrderModified">{{ tt('Save Display Order') }}</v-btn>
                                            <v-btn density="compact" color="default" variant="text" size="24"
                                                   class="ms-2" :icon="true" :loading="loading" @click="reload(true)">
                                                <template #loader>
                                                    <v-progress-circular indeterminate size="20"/>
                                                </template>
                                                <v-icon :icon="mdiRefresh" size="24" />
                                                <v-tooltip activator="parent">{{ tt('Refresh') }}</v-tooltip>
                                            </v-btn>
                                            <v-spacer/>
                                            <v-btn density="comfortable" color="default" variant="text" class="ms-2"
                                                   :disabled="loading" :icon="true">
                                                <v-icon :icon="mdiDotsVertical" />
                                                <v-menu activator="parent">
                                                    <v-list>
                                                        <v-list-item :prepend-icon="mdiEyeOutline"
                                                                     :title="tt('Show Hidden Accounts')"
                                                                     v-if="!showHidden" @click="showHidden = true"></v-list-item>
                                                        <v-list-item :prepend-icon="mdiEyeOffOutline"
                                                                     :title="tt('Hide Hidden Accounts')"
                                                                     v-if="showHidden" @click="showHidden = false"></v-list-item>
                                                        <v-divider class="my-2" v-if="hasAnyVisibleAccount"/>
                                                        <v-list-item :prepend-icon="mdiCalculatorVariantOutline"
                                                                     :title="tt('Set Accounts Included in Total')"
                                                                     v-if="hasAnyVisibleAccount" @click="showAccountsIncludedInTotalDialog = true"></v-list-item>
                                                    </v-list>
                                                </v-menu>
                                            </v-btn>
                                        </div>
                                    </template>

                                    <v-card-text class="accounts-overview-title text-truncate pt-0">
                                        <span class="accounts-overview-subtitle">{{ activeAccountCategory?.isLiability ? tt('Outstanding Balance') : tt('Balance') }}</span>
                                        <v-skeleton-loader class="skeleton-no-margin ms-3 mb-2" width="120px" type="text" :loading="true" v-if="loading && activeAccountCategory && !hasAccount(activeAccountCategory)"></v-skeleton-loader>
                                        <span class="accounts-overview-amount ms-3" v-else-if="!loading || !activeAccountCategory || hasAccount(activeAccountCategory)">{{ activeAccountCategoryTotalBalance }}</span>
                                        <v-btn class="ms-2" density="compact" color="default" variant="text"
                                               :icon="true" :disabled="loading"
                                               @click="showAccountBalance = !showAccountBalance">
                                            <v-icon :icon="showAccountBalance ? mdiEyeOffOutline : mdiEyeOutline" size="20" />
                                            <v-tooltip activator="parent">{{ showAccountBalance ? tt('Hide Account Balance') : tt('Show Account Balance') }}</v-tooltip>
                                        </v-btn>
                                    </v-card-text>

                                    <section class="account-gallery" aria-labelledby="account-gallery-title">
                                        <header class="account-gallery-heading">
                                            <div>
                                                <span class="account-gallery-kicker">ACCOUNT / ORBIT</span>
                                                <h2 id="account-gallery-title">{{ activeAccountCategory ? tt(activeAccountCategory.name) : '账户' }}</h2>
                                                <p>滑动轨道选择账户，点击卡片查看完整信息。</p>
                                            </div>
                                            <span class="account-gallery-count">{{ activeCategoryAccounts.length.toString().padStart(2, '0') }}</span>
                                        </header>

                                        <div v-if="loading && !activeCategoryAccounts.length" class="account-gallery-loading">
                                            <v-skeleton-loader type="image"/>
                                        </div>
                                        <div v-else-if="!activeCategoryAccounts.length" class="account-gallery-empty">
                                            <span aria-hidden="true">00</span>
                                            <strong>这个分类还没有账户</strong>
                                            <p>添加账户并填写当前余额，它就会进入资产轨道。</p>
                                            <v-btn color="primary" variant="flat" @click="add">添加账户</v-btn>
                                        </div>
                                        <div v-else class="account-gallery-layout">
                                            <div class="account-carousel-shell">
                                                <button type="button" class="account-carousel-arrow previous" aria-label="上一个账户"
                                                        @click="selectPreviousAccount">←</button>
                                                <div class="account-carousel-stage" aria-label="账户卡片轮播">
                                                    <button v-for="(account, index) in activeCategoryAccounts" :key="account.id"
                                                            type="button" class="account-orbit-card"
                                                            :class="[accountVisualClass(account), { active: index === activeAccountIndex }]"
                                                            :style="carouselCardStyle(index)"
                                                            :aria-label="`查看账户 ${account.name}`"
                                                            :aria-current="index === activeAccountIndex ? 'true' : undefined"
                                                            @click="selectAccount(index)">
                                                        <span class="orbit-card-grid" aria-hidden="true"></span>
                                                        <span class="orbit-card-topline">
                                                            <small>{{ accountIdentityCaption(account) }}</small>
                                                            <b>{{ (index + 1).toString().padStart(2, '0') }}</b>
                                                        </span>
                                                        <span class="orbit-card-mark" aria-hidden="true">{{ accountIdentityMark(account) }}</span>
                                                        <span class="orbit-card-name">{{ account.name }}</span>
                                                        <span class="orbit-card-balance">
                                                            <small>{{ account.isLiability ? '待还金额' : '当前余额' }}</small>
                                                            <strong>{{ accountBalance(account, '') }}</strong>
                                                        </span>
                                                        <span class="orbit-card-number">ID / {{ account.id.replace(/\D/g, '').slice(-4).padStart(4, '0') }}</span>
                                                    </button>
                                                </div>
                                                <button type="button" class="account-carousel-arrow next" aria-label="下一个账户"
                                                        @click="selectNextAccount">→</button>
                                                <div class="account-carousel-position" aria-hidden="true">
                                                    <i :style="{ width: `${(activeAccountIndex + 1) * 100 / activeCategoryAccounts.length}%` }"></i>
                                                </div>
                                            </div>

                                            <Transition name="account-detail-reveal" mode="out-in">
                                                <aside v-if="selectedAccount" :key="selectedAccount.id" class="account-focus-panel">
                                                    <header>
                                                        <div>
                                                            <span class="account-gallery-kicker">SELECTED / ACCOUNT</span>
                                                            <h3>{{ selectedAccount.name }}</h3>
                                                        </div>
                                                        <span class="account-focus-mark" aria-hidden="true">{{ accountIdentityMark(selectedAccount) }}</span>
                                                    </header>
                                                    <div class="account-focus-balance">
                                                        <small>{{ selectedAccount.isLiability ? '待还金额' : '可用余额' }}</small>
                                                        <strong>{{ accountBalance(selectedAccount, '') }}</strong>
                                                    </div>
                                                    <dl>
                                                        <div><dt>账户角色</dt><dd>{{ activeAccountCategory ? tt(activeAccountCategory.name) : '账户' }}</dd></div>
                                                        <div><dt>默认币种</dt><dd>{{ selectedAccount.currency }}</dd></div>
                                                        <div><dt>账户状态</dt><dd>{{ selectedAccount.hidden ? '已隐藏' : '正常使用' }}</dd></div>
                                                        <div><dt>说明</dt><dd>{{ selectedAccount.comment || '尚未填写' }}</dd></div>
                                                    </dl>
                                                    <footer>
                                                        <v-btn color="primary" variant="flat"
                                                               :to="`/transaction/list?pageType=1&accountIds=${selectedAccount.id}`">查看流水</v-btn>
                                                        <v-btn color="default" variant="outlined" @click="edit(selectedAccount)">编辑账户</v-btn>
                                                    </footer>
                                                </aside>
                                            </Transition>
                                        </div>
                                    </section>

                                    <v-row class="legacy-account-rows ps-6 pe-6 pe-md-8" v-if="loading && activeAccountCategory && !hasAccount(activeAccountCategory)">
                                        <v-col cols="12">
                                            <v-card border class="card-title-with-bg account-card mb-8 h-auto">
                                                <template #title>
                                                    <div class="account-title d-flex align-center">
                                                        <v-icon class="disabled me-0" size="28px" :icon="mdiSquareRounded" />
                                                        <span class="account-name text-truncate ms-2">
                                                            <v-skeleton-loader class="skeleton-no-margin my-1"
                                                                               width="120px" type="text" :loading="true"></v-skeleton-loader>
                                                        </span>
                                                        <v-spacer/>
                                                        <span class="align-self-center">
                                                            <v-icon class="disabled" :icon="mdiDrag"/>
                                                        </span>
                                                    </div>
                                                </template>
                                                <v-divider/>
                                                <v-card-text>
                                                    <div class="d-flex account-toolbar align-center">
                                                        <v-btn class="px-2" density="comfortable" color="default" variant="text"
                                                               :disabled="true" :prepend-icon="mdiListBoxOutline">
                                                            {{ tt('Transaction List') }}
                                                        </v-btn>
                                                        <v-spacer/>
                                                        <span class="account-balance ms-2">
                                                            <v-skeleton-loader class="skeleton-no-margin"
                                                                               width="100px" type="text" :loading="true"></v-skeleton-loader>
                                                        </span>
                                                    </div>
                                                </v-card-text>
                                            </v-card>
                                        </v-col>
                                    </v-row>

                                    <v-row class="ps-5 pe-2 pe-md-4" v-if="!loading && activeAccountCategory && !hasAccount(activeAccountCategory)">
                                        <v-col cols="12">
                                            {{ tt('No available account') }}
                                        </v-col>
                                    </v-row>

                                    <v-row class="legacy-account-rows ps-6 pe-6 pe-md-8">
                                        <v-col cols="12">
                                            <draggable-list
                                                class="list-group"
                                                item-key="id"
                                                handle=".drag-handle"
                                                ghost-class="dragging-item"
                                                :disabled="activeAccountCategoryVisibleAccountCount <= 1"
                                                :list="allCategorizedAccountsMap[activeAccountCategory.type]!.accounts"
                                                v-if="activeAccountCategory && allCategorizedAccountsMap[activeAccountCategory.type] && allCategorizedAccountsMap[activeAccountCategory.type]!.accounts && allCategorizedAccountsMap[activeAccountCategory.type]!.accounts.length"
                                                @change="onMove"
                                            >
                                                <template #item="{ element }">
                                                    <div class="list-group-item">
                                                        <v-card border class="card-title-with-bg account-card mb-8 h-auto" v-if="showHidden || !element.hidden">
                                                            <template #title>
                                                                <div class="account-title d-flex align-baseline">
                                                                    <ItemIcon size="1.5rem" icon-type="account" :icon-id="element.icon"
                                                                              :color="element.color" :hidden-status="element.hidden" />
                                                                    <span class="account-name text-truncate ms-2">{{ element.name }}</span>
                                                                    <small class="account-currency text-truncate ms-2">
                                                                        {{ accountCurrency(element) }}
                                                                    </small>
                                                                    <v-spacer/>
                                                                    <span class="align-self-center">
                                                                        <v-icon :class="!loading && activeAccountCategoryVisibleAccountCount > 1 ? 'drag-handle' : 'disabled'"
                                                                                :icon="mdiDrag"/>
                                                                        <v-tooltip activator="parent" v-if="!loading && activeAccountCategoryVisibleAccountCount > 1">{{ tt('Drag to Reorder') }}</v-tooltip>
                                                                    </span>
                                                                </div>

                                                                <div class="mt-4" v-if="element.type === AccountType.MultiSubAccounts.type">
                                                                    <v-btn-toggle
                                                                        class="account-subaccounts"
                                                                        variant="outlined"
                                                                        color="primary"
                                                                        density="compact"
                                                                        mandatory="force"
                                                                        divided rounded="xl"
                                                                        :disabled="loading"
                                                                        v-model="activeSubAccount[element.id]"
                                                                    >
                                                                        <v-btn :value="''">
                                                                            <span>{{ tt('All') }}</span>
                                                                        </v-btn>
                                                                        <v-btn :key="subAccount.id" :value="subAccount.id"
                                                                               v-for="subAccount in element.subAccounts"
                                                                               v-show="showHidden || !subAccount.hidden">
                                                                            <ItemIcon size="1.5rem" icon-type="account" :icon-id="subAccount.icon"
                                                                                      :color="subAccount.color" :hidden-status="subAccount.hidden" />
                                                                            <span class="ms-2">{{ subAccount.name }}</span>
                                                                        </v-btn>
                                                                    </v-btn-toggle>
                                                                </div>
                                                            </template>

                                                            <v-divider/>

                                                            <v-card-text v-if="element.getAccountOrSubAccountComment(activeSubAccount[element.id])">
                                                                {{ element.getAccountOrSubAccountComment(activeSubAccount[element.id]) }}
                                                            </v-card-text>

                                                            <v-card-text>
                                                                <div class="d-flex account-toolbar align-center">
                                                                    <v-btn class="px-2" density="comfortable" color="default" variant="text"
                                                                           :disabled="loading" :prepend-icon="mdiListBoxOutline"
                                                                           :to="`/transaction/list?accountIds=${element.getAccountOrSubAccountId(activeSubAccount[element.id])}`">
                                                                        {{ tt('Transaction List') }}
                                                                    </v-btn>
                                                                    <v-btn class="px-2 ms-1" density="comfortable" color="default" variant="text"
                                                                           :disabled="loading" :prepend-icon="mdiInvoiceListOutline"
                                                                           @click="showReconciliationStatementDialog(element.getAccountOrSubAccount(activeSubAccount[element.id]))"
                                                                           v-if="element.type === AccountType.SingleAccount.type || element.getSubAccount(activeSubAccount[element.id])">
                                                                        {{ tt('Reconciliation Statement') }}
                                                                        <v-menu activator="parent" :open-on-hover="true">
                                                                            <v-list>
                                                                                <template :key="dateRange.type"
                                                                                          v-for="dateRange in accountReconciliationStatementDateRanges(element.getAccountOrSubAccount(activeSubAccount[element.id]))">
                                                                                    <v-list-item class="text-sm" density="compact"
                                                                                                 :value="dateRange.type">
                                                                                        <v-list-item-title class="cursor-pointer"
                                                                                                           @click="showReconciliationStatementDialog(element.getAccountOrSubAccount(activeSubAccount[element.id]), dateRange.type)">
                                                                                            <div class="d-flex align-center">
                                                                                                <span class="text-sm ms-3">{{ dateRange.displayName }}</span>
                                                                                            </div>
                                                                                        </v-list-item-title>
                                                                                    </v-list-item>
                                                                                </template>
                                                                            </v-list>
                                                                        </v-menu>
                                                                    </v-btn>
                                                                    <v-btn class="px-2 ms-1" density="comfortable" color="default" variant="text"
                                                                           :class="{ 'd-none': loading, 'hover-display': !loading }"
                                                                           :disabled="loading"
                                                                           :prepend-icon="element.isAccountOrSubAccountHidden(activeSubAccount[element.id]) ? mdiEyeOutline : mdiEyeOffOutline"
                                                                           v-if="!activeSubAccount[element.id] || element.getSubAccount(activeSubAccount[element.id])"
                                                                           @click="hide(element, element.getAccountOrSubAccount(activeSubAccount[element.id]), !element.isAccountOrSubAccountHidden(activeSubAccount[element.id]))">
                                                                        {{ element.isAccountOrSubAccountHidden(activeSubAccount[element.id]) ? tt('Show') : tt('Hide') }}
                                                                    </v-btn>
                                                                    <v-btn class="px-2 ms-1" density="comfortable" color="default" variant="text"
                                                                           :class="{ 'd-none': loading, 'hover-display': !loading }"
                                                                           :disabled="loading" :prepend-icon="mdiPencilOutline"
                                                                           v-if="!activeSubAccount[element.id] || element.getSubAccount(activeSubAccount[element.id])"
                                                                           @click="edit(element)">
                                                                        {{ tt('Edit') }}
                                                                    </v-btn>
                                                                    <v-btn class="px-2 ms-1" density="comfortable" color="default" variant="text"
                                                                           :class="{ 'd-none': loading, 'hover-display': !loading }"
                                                                           :disabled="loading" :prepend-icon="mdiDotsHorizontalCircleOutline"
                                                                           v-if="element.type === AccountType.SingleAccount.type || element.getSubAccount(activeSubAccount[element.id])">
                                                                        {{ tt('More') }}
                                                                        <v-menu activator="parent" :open-on-hover="true">
                                                                            <v-list>
                                                                                <v-list-item class="text-sm" density="compact"
                                                                                             :title="tt('Mark as Reconciled')"
                                                                                             :prepend-icon="mdiReceiptTextCheckOutline"
                                                                                             @click="updateLastReconciledTime(element.getAccountOrSubAccount(activeSubAccount[element.id]))"
                                                                                             v-if="useLastReconciledTime"></v-list-item>
                                                                                <v-divider class="my-2" v-if="useLastReconciledTime" />
                                                                                <v-list-item class="text-sm" density="compact"
                                                                                             :title="tt('Move All Transactions')"
                                                                                             :prepend-icon="mdiSwapHorizontal"
                                                                                             @click="moveAllTransactions(element.getAccountOrSubAccount(activeSubAccount[element.id]))"></v-list-item>
                                                                                <v-list-item class="text-sm" density="compact"
                                                                                             :title="tt('Clear All Transactions')"
                                                                                             :prepend-icon="mdiEraser"
                                                                                             @click="clearAllTransactions(element.getAccountOrSubAccount(activeSubAccount[element.id]))"></v-list-item>
                                                                            </v-list>
                                                                        </v-menu>
                                                                    </v-btn>
                                                                    <v-btn class="px-2 ms-1" density="comfortable" color="default" variant="text"
                                                                           :class="{ 'd-none': loading, 'hover-display': !loading }"
                                                                           :disabled="loading" :prepend-icon="mdiDeleteOutline"
                                                                           v-if="!activeSubAccount[element.id] || element.getSubAccount(activeSubAccount[element.id])"
                                                                           @click="remove(element)">
                                                                        {{ tt('Delete') }}
                                                                    </v-btn>
                                                                    <v-spacer/>
                                                                    <span class="account-balance ms-2">{{ accountBalance(element, activeSubAccount[element.id]) }}</span>
                                                                </div>
                                                            </v-card-text>
                                                        </v-card>
                                                    </div>
                                                </template>
                                            </draggable-list>
                                        </v-col>
                                    </v-row>
                                </v-card>
                            </v-window-item>
                        </v-window>
                    </v-main>
                </v-layout>
            </v-card>
        </v-col>
    </v-row>

    <v-dialog width="800" v-model="showAccountsIncludedInTotalDialog">
        <account-filter-settings-card type="accountListTotalAmount" :dialog-mode="true"
                                      @settings:change="showAccountsIncludedInTotalDialog = false" />
    </v-dialog>

    <edit-dialog ref="editDialog" />
    <reconciliation-statement-dialog ref="reconciliationStatementDialog"
                                     @error="onShowDateRangeError" />
    <move-all-transactions-dialog ref="moveAllTransactionsDialog" />
    <clear-all-transactions-dialog ref="clearAllTransactionsDialog" />

    <date-range-selection-dialog :title="tt('Custom Date Range')"
                                 v-model:show="showCustomDateRangeDialog"
                                 @dateRange:change="onCustomDateRangeChanged"
                                 @error="onShowDateRangeError" />

    <confirm-dialog ref="confirmDialog"/>
    <snack-bar ref="snackbar" />
</template>

<script setup lang="ts">
import ConfirmDialog from '@/components/desktop/ConfirmDialog.vue';
import SnackBar from '@/components/desktop/SnackBar.vue';
import EditDialog from './list/dialogs/EditDialog.vue';
import ReconciliationStatementDialog from './list/dialogs/ReconciliationStatementDialog.vue';
import MoveAllTransactionsDialog from '@/views/desktop/accounts/list/dialogs/MoveAllTransactionsDialog.vue';
import ClearAllTransactionsDialog from '@/views/desktop/accounts/list/dialogs/ClearAllTransactionsDialog.vue';
import AccountFilterSettingsCard from '@/views/desktop/common/cards/AccountFilterSettingsCard.vue';

import { ref, computed, useTemplateRef, watch } from 'vue';
import { useDisplay } from 'vuetify';

import { useI18n } from '@/locales/helpers.ts';
import { useAccountListPageBase } from '@/views/base/accounts/AccountListPageBase.ts';

import { useSettingsStore } from '@/stores/setting.ts';
import { useUserStore } from '@/stores/user.ts';
import { useAccountsStore } from '@/stores/account.ts';

import { DateRange, DateRangeScene, type LocalizedDateRange, type TimeRangeAndDateType } from '@/core/datetime.ts';
import { AccountType, AccountCategory } from '@/core/account.ts';
import { DEFAULT_RECONCILIATION_STATEMENT_DATE_RANGE_IN_DESKTOP } from '@/core/statistics.ts';
import type { Account } from '@/models/account.ts';

import { isNumber } from '@/lib/common.ts';
import {
    getCurrentUnixTime,
    getDateRangeByDateType,
    getDateRangeByBillingCycleDateType,
    getDateRangeByLastReconciledTimeRangeDateType
} from '@/lib/datetime.ts';

import {
    mdiEyeOutline,
    mdiEyeOffOutline,
    mdiCalculatorVariantOutline,
    mdiRefresh,
    mdiSquareRounded,
    mdiMenu,
    mdiPencilOutline,
    mdiDotsHorizontalCircleOutline,
    mdiReceiptTextCheckOutline,
    mdiSwapHorizontal,
    mdiEraser,
    mdiDeleteOutline,
    mdiListBoxOutline,
    mdiInvoiceListOutline,
    mdiDrag,
    mdiDotsVertical
} from '@mdi/js';

type ConfirmDialogType = InstanceType<typeof ConfirmDialog>;
type SnackBarType = InstanceType<typeof SnackBar>;
type EditDialogType = InstanceType<typeof EditDialog>;
type ReconciliationStatementDialogType = InstanceType<typeof ReconciliationStatementDialog>;
type MoveAllTransactionsDialogType = InstanceType<typeof MoveAllTransactionsDialog>;
type ClearAllTransactionsDialogType = InstanceType<typeof ClearAllTransactionsDialog>;

const display = useDisplay();

const { tt, getAllDateRanges, getCurrencyName, joinMultiText } = useI18n();

const {
    loading,
    showHidden,
    displayOrderModified,
    showAccountBalance,
    customAccountCategoryOrder,
    defaultAccountCategory,
    firstDayOfWeek,
    fiscalYearStart,
    useLastReconciledTime,
    allAccounts,
    allCategorizedAccountsMap,
    allAccountCount,
    netAssets,
    totalAssets,
    totalLiabilities,
    accountCategoryTotalBalance,
    accountBalance
} = useAccountListPageBase();

const settingsStore = useSettingsStore();
const userStore = useUserStore();
const accountsStore = useAccountsStore();

const confirmDialog = useTemplateRef<ConfirmDialogType>('confirmDialog');
const snackbar = useTemplateRef<SnackBarType>('snackbar');
const editDialog = useTemplateRef<EditDialogType>('editDialog');
const reconciliationStatementDialog = useTemplateRef<ReconciliationStatementDialogType>('reconciliationStatementDialog');
const moveAllTransactionsDialog = useTemplateRef<MoveAllTransactionsDialogType>('moveAllTransactionsDialog');
const clearAllTransactionsDialog = useTemplateRef<ClearAllTransactionsDialogType>('clearAllTransactionsDialog');

const activeAccountCategoryType = ref<number>(defaultAccountCategory.value.type);
const activeTab = ref<string>('accountPage');
const activeSubAccount = ref<Record<string, string>>({});
const accountToShowReconciliationStatement = ref<Account | null>(null);
const alwaysShowNav = ref<boolean>(display.mdAndUp.value);
const showNav = ref<boolean>(display.mdAndUp.value);
const showAccountsIncludedInTotalDialog = ref<boolean>(false);
const showCustomDateRangeDialog = ref<boolean>(false);
const activeAccountIndex = ref<number>(0);

const hideAccountCategoriesWithoutAccounts = computed<boolean>(() => settingsStore.appSettings.hideCategoriesWithoutAccounts);
const hasAnyVisibleAccount = computed<boolean>(() => accountsStore.allVisibleAccountsCount > 0);
const activeAccountCategory = computed<AccountCategory | undefined>(() => AccountCategory.valueOf(activeAccountCategoryType.value));
const activeAccountCategoryTotalBalance = computed<string>(() => accountCategoryTotalBalance(activeAccountCategory.value));
const activeCategoryAccounts = computed<Account[]>(() => {
    if (!activeAccountCategory.value) {
        return [];
    }

    const categorized = allCategorizedAccountsMap.value[activeAccountCategory.value.type];
    return (categorized?.accounts || []).filter(account => showHidden.value || !account.hidden);
});
const selectedAccount = computed<Account | null>(() => activeCategoryAccounts.value[activeAccountIndex.value] || null);

const activeAccountCategoryVisibleAccountCount = computed<number>(() => {
    if (!activeAccountCategory.value) {
        return 0;
    }

    const categorizedAccounts = allCategorizedAccountsMap.value[activeAccountCategory.value.type];

    if (!categorizedAccounts || !categorizedAccounts.accounts || !categorizedAccounts.accounts.length) {
        return 0;
    }

    if (showHidden.value) {
        return categorizedAccounts.accounts.length;
    }

    let visibleCount = 0;

    for (const account of categorizedAccounts.accounts) {
        if (!account.hidden) {
            visibleCount++;
        }
    }

    return visibleCount;
});

function reload(force: boolean): void {
    loading.value = true;

    accountsStore.loadAllAccounts({
        force: force
    }).then(() => {
        loading.value = false;
        displayOrderModified.value = false;

        if (allAccounts.value) {
            for (const account of allAccounts.value) {
                if (account.type === AccountType.MultiSubAccounts.type && !activeSubAccount.value[account.id]) {
                    activeSubAccount.value[account.id] = '';
                }
            }
        }

        if (force) {
            snackbar.value?.showMessage('Account list has been updated');
        }
    }).catch(error => {
        loading.value = false;

        if (error && error.isUpToDate) {
            displayOrderModified.value = false;
        }

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function selectAccount(index: number): void {
    if (index >= 0 && index < activeCategoryAccounts.value.length) {
        activeAccountIndex.value = index;
    }
}

function selectPreviousAccount(): void {
    const count = activeCategoryAccounts.value.length;
    if (count > 0) activeAccountIndex.value = (activeAccountIndex.value - 1 + count) % count;
}

function selectNextAccount(): void {
    const count = activeCategoryAccounts.value.length;
    if (count > 0) activeAccountIndex.value = (activeAccountIndex.value + 1) % count;
}

function carouselCardStyle(index: number): Record<string, string> {
    const count = activeCategoryAccounts.value.length;
    let distance = index - activeAccountIndex.value;

    if (count > 2) {
        if (distance > count / 2) distance -= count;
        if (distance < -count / 2) distance += count;
    }

    const absoluteDistance = Math.abs(distance);
    return {
        '--orbit-x': `${distance * 48}%`,
        '--orbit-z': `${-absoluteDistance * 115}px`,
        '--orbit-rotate': `${distance * -24}deg`,
        '--orbit-scale': `${Math.max(.72, 1 - absoluteDistance * .13)}`,
        '--orbit-opacity': `${absoluteDistance > 2 ? 0 : Math.max(.34, 1 - absoluteDistance * .28)}`,
        '--orbit-pointer': absoluteDistance > 2 ? 'none' : 'auto',
        zIndex: `${20 - absoluteDistance}`
    };
}

function accountIdentity(account: Account): string {
    const name = account.name.toLowerCase();
    if (name.includes('微信') || name.includes('wechat')) return 'wechat';
    if (name.includes('支付宝') || name.includes('alipay')) return 'alipay';
    if (name.includes('花呗')) return 'huabei';
    if (name.includes('京东') || name.includes('白条')) return 'jd';
    if (account.category === AccountCategory.CreditCard.type) return 'credit';
    if (account.category === AccountCategory.Cash.type) return 'cash';
    if (account.category === AccountCategory.InvestmentAccount.type) return 'invest';
    if (account.category === AccountCategory.VirtualAccount.type) return 'digital';
    return 'reserve';
}

function accountVisualClass(account: Account): string {
    return `account-visual-${accountIdentity(account)}`;
}

function accountIdentityMark(account: Account): string {
    const marks: Record<string, string> = { wechat: '微', alipay: '支', huabei: '花', jd: '白', credit: '信', cash: '现', invest: '投', digital: '数', reserve: '储' };
    return marks[accountIdentity(account)] || '账';
}

function accountIdentityCaption(account: Account): string {
    const captions: Record<string, string> = {
        wechat: 'WECHAT / WALLET', alipay: 'ALIPAY / BALANCE', huabei: 'HUABEI / CREDIT', jd: 'JD / CREDIT',
        credit: 'CREDIT / FLOAT', cash: 'CASH / HAND', invest: 'INVEST / GROWTH', digital: 'DIGITAL / VALUE', reserve: 'RESERVE / FLOW'
    };
    return captions[accountIdentity(account)] || 'PERSONAL / LEDGER';
}

function hasAccount(accountCategory: AccountCategory): boolean {
    return accountsStore.hasAccount(accountCategory, !showHidden.value);
}

function accountCurrency(account: Account): string | null {
    if (account.type === AccountType.SingleAccount.type) {
        return getCurrencyName(account.currency);
    } else if (account.type === AccountType.MultiSubAccounts.type) {
        const subAccountCurrencies = account.getSubAccountCurrencies(showHidden.value, activeSubAccount.value[account.id])
            .map(currencyCode => getCurrencyName(currencyCode));
        return joinMultiText(subAccountCurrencies);
    } else {
        return null;
    }
}

function accountReconciliationStatementDateRanges(account: Account): LocalizedDateRange[] {
    return getAllDateRanges(DateRangeScene.Normal, {
        includeCustom: true,
        includeBillingCycle: !!accountsStore.getAccountStatementDate(account.id),
        includeLastReconciledTimeRange: userStore.currentUserUseLastReconciledTime && !!account.lastReconciledTime
    });
}

function add(): void {
    editDialog.value?.open({
        category: activeAccountCategoryType.value
    }).then(result => {
        if (result && result.message) {
            snackbar.value?.showMessage(result.message);
        }
    }).catch(error => {
        if (error) {
            snackbar.value?.showError(error);
        }
    });
}

function edit(account: Account): void {
    editDialog.value?.open({
        id: account.id,
        currentAccount: account
    }).then(result => {
        if (result && result.message) {
            snackbar.value?.showMessage(result.message);
        }

        if (accountsStore.accountListStateInvalid && !loading.value) {
            reload(false);
        }
    }).catch(error => {
        if (error) {
            snackbar.value?.showError(error);
        }
    });
}

function showReconciliationStatementDialog(account: Account, dateRangeType?: number): void {
    if (!isNumber(dateRangeType)) {
        const defualtDateRange = DateRange.valueOf(settingsStore.appSettings.reconciliationStatementButtonDefaultDateRangeTypeInDesktop);

        if (!defualtDateRange) {
            dateRangeType = DEFAULT_RECONCILIATION_STATEMENT_DATE_RANGE_IN_DESKTOP.type;
        } else if (defualtDateRange.isBillingCycle && !accountsStore.getAccountStatementDate(account.id)) {
            dateRangeType = DEFAULT_RECONCILIATION_STATEMENT_DATE_RANGE_IN_DESKTOP.type;
        } else if (defualtDateRange.isLastReconciledTimeRange && (!userStore.currentUserUseLastReconciledTime || !account.lastReconciledTime)) {
            dateRangeType = DEFAULT_RECONCILIATION_STATEMENT_DATE_RANGE_IN_DESKTOP.type;
        } else {
            dateRangeType = defualtDateRange.type;
        }
    }

    if (!isNumber(dateRangeType) || dateRangeType === DateRange.Custom.type) {
        accountToShowReconciliationStatement.value = account;
        showCustomDateRangeDialog.value = true;
        return;
    }

    let dateRange: TimeRangeAndDateType | null = null;

    if (DateRange.isBillingCycle(dateRangeType)) {
        dateRange = getDateRangeByBillingCycleDateType(dateRangeType, firstDayOfWeek.value, fiscalYearStart.value, accountsStore.getAccountStatementDate(account.id));
    } else if (DateRange.isLastReconciledTimeRange(dateRangeType)) {
        dateRange = getDateRangeByLastReconciledTimeRangeDateType(dateRangeType, account.lastReconciledTime);
    } else {
        dateRange = getDateRangeByDateType(dateRangeType, firstDayOfWeek.value, fiscalYearStart.value);
    }

    if (!dateRange) {
        return;
    }

    reconciliationStatementDialog.value?.open({
        accountId: account.id,
        startTime: dateRange.minTime,
        endTime: dateRange.maxTime
    });
}

function updateLastReconciledTime(account: Account): void {
    confirmDialog.value?.open('Are you sure you want to update the last reconciled time of this account to the current time?').then(() => {
        loading.value = true;

        accountsStore.updateAccountLastReconciledTime(account.id, getCurrentUnixTime()).then(() => {
            loading.value = false;
            snackbar.value?.showMessage('Last reconciled time have been updated');

            if (accountsStore.accountListStateInvalid && !loading.value) {
                reload(false);
            }

        }).catch(error => {
            loading.value = false;

            if (error) {
                snackbar.value?.showError(error);
            }
        });
    });
}

function moveAllTransactions(account: Account): void {
    moveAllTransactionsDialog.value?.open(account).then(() => {
        snackbar.value?.showMessage('All transactions in this account have been moved.');

        if (accountsStore.accountListStateInvalid && !loading.value) {
            reload(false);
        }
    });
}

function clearAllTransactions(account: Account): void {
    clearAllTransactionsDialog.value?.open(account).then(() => {
        snackbar.value?.showMessage('All transactions in this account have been cleared');

        if (accountsStore.accountListStateInvalid && !loading.value) {
            reload(false);
        }
    });
}

function hide(account: Account, targetAccount: Account, hidden: boolean): void {
    loading.value = true;

    accountsStore.hideAccount({
        account: targetAccount,
        hidden: hidden
    }).then(() => {
        if (hidden && !showHidden.value && activeSubAccount.value[account.id]) {
            activeSubAccount.value[account.id] = '';
        }

        loading.value = false;
    }).catch(error => {
        loading.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function remove(account: Account): void {
    if (activeSubAccount.value[account.id]) {
        const subAccount: Account | null = account.getSubAccount(activeSubAccount.value[account.id]);

        if (!subAccount) {
            snackbar.value?.showMessage('Unable to delete this sub-account');
            return;
        }

        confirmDialog.value?.open('Are you sure you want to delete this sub-account?').then(() => {
            loading.value = true;

            accountsStore.deleteSubAccount({
                subAccount: subAccount
            }).then(() => {
                activeSubAccount.value[account.id] = '';
                loading.value = false;
            }).catch(error => {
                loading.value = false;

                if (!error.processed) {
                    snackbar.value?.showError(error);
                }
            });
        });
    } else {
        confirmDialog.value?.open('Are you sure you want to delete this account?').then(() => {
            loading.value = true;

            accountsStore.deleteAccount({
                account: account
            }).then(() => {
                loading.value = false;
            }).catch(error => {
                loading.value = false;

                if (!error.processed) {
                    snackbar.value?.showError(error);
                }
            });
        });
    }
}

function saveSortResult(): void {
    if (!displayOrderModified.value) {
        return;
    }

    loading.value = true;

    accountsStore.updateAccountDisplayOrders().then(() => {
        loading.value = false;
        displayOrderModified.value = false;
    }).catch(error => {
        loading.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function onMove(event: { moved: { element: { id: string }, oldIndex: number, newIndex: number } }): void {
    if (!event || !event.moved) {
        return;
    }

    const moveEvent = event.moved;

    if (!moveEvent.element || !moveEvent.element.id) {
        snackbar.value?.showMessage('Unable to move account');
        return;
    }

    accountsStore.changeAccountDisplayOrder({
        accountId: moveEvent.element.id,
        from: moveEvent.oldIndex,
        to: moveEvent.newIndex,
        updateListOrder: false,
        updateGlobalListOrder: true
    }).then(() => {
        displayOrderModified.value = true;
    }).catch(error => {
        snackbar.value?.showError(error);
    });
}

function onCustomDateRangeChanged(minUnixTime: number, maxUnixTime: number): void {
    if (!accountToShowReconciliationStatement.value) {
        snackbar.value?.showMessage('An error occurred');
        return;
    }

    showCustomDateRangeDialog.value = false;

    reconciliationStatementDialog.value?.open({
        accountId: accountToShowReconciliationStatement.value.id,
        startTime: minUnixTime,
        endTime: maxUnixTime
    });

    accountToShowReconciliationStatement.value = null;
}

function onShowDateRangeError(message: string): void {
    snackbar.value?.showError(message);
}

watch([activeAccountCategoryType, () => activeCategoryAccounts.value.length], () => {
    activeAccountIndex.value = 0;
});

watch(() => display.mdAndUp.value, (newValue) => {
    alwaysShowNav.value = newValue;

    if (!showNav.value) {
        showNav.value = newValue;
    }
});

reload(false);
</script>

<style>
.account-statistic-item-value {
    font-size: 1rem;
}

.account-category-tabs .v-tab.v-tab.v-btn {
    height: calc(var(--v-tabs-height) * 1.5);
}

.accounts-overview-title {
    line-height: 2rem !important;
    min-height: 52px;
    display: flex;
    align-items: flex-end;
}

.accounts-overview-amount {
    font-size: 1.5rem;
    color: rgba(var(--v-theme-on-background), var(--v-high-emphasis-opacity));
    overflow: hidden;
    text-overflow: ellipsis;
}

.accounts-overview-subtitle {
    font-size: 1rem;
    line-height: 1.75rem;
}

.account-card > .v-card-item {
    padding-top: 0.875rem;
    padding-bottom: 0.875rem;
}

.account-card .account-title {
    font-size: 1rem;
    line-height: 1.5rem !important;
}

.account-card .account-title .account-name {
    color: rgba(var(--v-theme-on-background), var(--v-high-emphasis-opacity));
}

.account-card .account-currency {
    font-size: 0.8rem;
    color: rgba(var(--v-theme-on-background), var(--v-medium-emphasis-opacity));
}

.account-card .account-subaccounts {
    overflow-x: auto;
    white-space: nowrap;
}

.account-card .account-subaccounts.v-btn-toggle {
    height: auto !important;
    padding: 0;
    border: none;
}

.account-card .account-subaccounts.v-btn-toggle > .v-btn {
    border-color: rgba(var(--v-border-color), var(--v-border-opacity));
}

.account-card .account-subaccounts.v-btn-toggle > .v-btn:not(:first-child) {
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
    border-left: none;
}

.account-card .account-subaccounts.v-btn-toggle > .v-btn:not(:last-child) {
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
}

.account-card .account-subaccounts.v-btn-toggle > .v-btn {
    border: thin solid rgba(var(--v-border-color), var(--v-border-opacity));
}

.account-card .account-subaccounts.v-btn-toggle button.v-btn {
    width: auto !important;
}

.account-card .account-toolbar {
    overflow-x: auto;
    white-space: nowrap;
}

.account-card .account-toolbar .hover-display {
    display: none;
}

.account-card .account-toolbar:hover .hover-display {
    display: grid;
}

.account-card .account-balance {
    font-size: 1.25rem;
    color: rgba(var(--v-theme-on-background), var(--v-high-emphasis-opacity));
}

/* Account orbit gallery */
.legacy-account-rows { display: none; }
.account-narrative { margin: .9rem .85rem 0; padding: 1.2rem 1rem 1.3rem; color: #f8f6ff; border-radius: 2px 22px 2px 22px; background: radial-gradient(circle at 90% 10%, rgba(99,87,255,.75), transparent 8rem), #11101a; box-shadow: 7px 8px 0 rgba(99,87,255,.16); }
.account-narrative > span { color: #d8ff45; font: 900 .57rem/1 var(--quest-pixel, ui-monospace, monospace); letter-spacing: .13em; }
.account-narrative h2 { margin: .75rem 0 .65rem; font-size: 1.2rem; line-height: 1.13; letter-spacing: -.045em; }
.account-narrative p { margin: 0; color: rgba(255,255,255,.52); font-size: .72rem; line-height: 1.65; }

.account-gallery { min-height: 610px; margin: 0 1.35rem 1.5rem; padding: 1.25rem; overflow: hidden; border: 1px solid rgba(17,16,26,.1); border-radius: 3px 28px 3px 28px; background: radial-gradient(circle at 86% 0, rgba(99,87,255,.12), transparent 22rem), rgba(255,255,255,.52); box-shadow: 10px 12px 0 rgba(17,16,26,.045); }
.account-gallery-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; padding-bottom: 1rem; border-bottom: 1px solid rgba(17,16,26,.12); }
.account-gallery-heading > div { display: grid; gap: .32rem; }
.account-gallery-kicker { color: #6357ff; font: 900 .62rem/1 var(--quest-pixel, ui-monospace, monospace); letter-spacing: .14em; }
.account-gallery-heading h2 { margin: 0; color: #171522; font-size: 2rem; line-height: 1; letter-spacing: -.065em; }
.account-gallery-heading p { margin: 0; color: #727083; font-size: .78rem; }
.account-gallery-count { color: transparent; font: 900 3.4rem/.8 system-ui,sans-serif; -webkit-text-stroke: 1px rgba(99,87,255,.35); }
.account-gallery-loading { display: grid; place-items: center; min-height: 430px; }
.account-gallery-loading .v-skeleton-loader { width: min(520px,80%); border-radius: 3px 28px 3px 28px; }
.account-gallery-empty { display: grid; justify-items: start; align-content: center; min-height: 430px; padding: 2rem 8%; }
.account-gallery-empty > span { color: transparent; font: 900 7rem/.8 system-ui,sans-serif; -webkit-text-stroke: 1px rgba(99,87,255,.3); }
.account-gallery-empty strong { margin-top: 1.5rem; font-size: 1.25rem; }
.account-gallery-empty p { margin: .4rem 0 1.2rem; color: #747184; }

.account-gallery-layout { display: grid; grid-template-columns: minmax(520px,1.25fr) minmax(300px,.75fr); gap: 1.2rem; align-items: stretch; padding-top: 1rem; }
.account-carousel-shell { position: relative; min-width: 0; min-height: 462px; overflow: hidden; border-radius: 2px 24px 2px 24px; background: linear-gradient(145deg, rgba(99,87,255,.08), transparent 58%), repeating-linear-gradient(90deg, rgba(17,16,26,.055) 0 1px, transparent 1px 48px); perspective: 1300px; }
.account-carousel-shell::before { content: "ORBIT"; position: absolute; inset: auto auto -1rem 1rem; color: rgba(17,16,26,.035); font: 900 7rem/.8 system-ui,sans-serif; letter-spacing: -.08em; }
.account-carousel-stage { position: relative; height: 390px; transform-style: preserve-3d; }
.account-orbit-card { position: absolute; inset: 78px auto auto 50%; width: 350px; height: 220px; padding: 1.15rem; overflow: hidden; color: #fff; border: 1px solid rgba(17,16,26,.22); border-radius: 3px 30px 3px 30px; text-align: start; cursor: pointer; box-shadow: 12px 15px 0 rgba(17,16,26,.14), 0 30px 70px rgba(29,23,60,.23); transform: translateX(calc(-50% + var(--orbit-x))) translateZ(var(--orbit-z)) rotateY(var(--orbit-rotate)) scale(var(--orbit-scale)); opacity: var(--orbit-opacity); pointer-events: var(--orbit-pointer); transform-style: preserve-3d; transition: transform 680ms cubic-bezier(.16,1,.3,1), opacity 420ms ease, filter 420ms ease; }
.account-orbit-card:not(.active) { filter: saturate(.72) brightness(.88); }
.account-orbit-card.active { box-shadow: 14px 18px 0 rgba(99,87,255,.2), 0 36px 80px rgba(29,23,60,.28); }
.account-orbit-card:focus-visible { outline: 4px solid #d8ff45; outline-offset: 4px; }
.orbit-card-grid { position: absolute; z-index: 0; inset: 0; opacity: .23; }
.orbit-card-topline, .orbit-card-name, .orbit-card-balance, .orbit-card-number { position: relative; z-index: 3; }
.orbit-card-topline { display: flex; justify-content: space-between; font: 900 .58rem/1 var(--quest-pixel, ui-monospace, monospace); letter-spacing: .11em; }
.orbit-card-topline b { color: #d8ff45; }
.orbit-card-mark { position: absolute; z-index: 2; inset: 50% 1rem auto auto; color: #d8ff45; font: 900 6.4rem/.8 "Microsoft YaHei UI",system-ui,sans-serif; letter-spacing: -.16em; transform: translateY(-50%) rotate(-7deg); text-shadow: 7px 7px 0 rgba(17,16,26,.17); }
.orbit-card-name { position: absolute; inset: auto auto 4.7rem 1.15rem; max-width: 55%; overflow: hidden; font-size: .88rem; font-weight: 850; text-overflow: ellipsis; white-space: nowrap; }
.orbit-card-balance { position: absolute; inset: auto auto 1.15rem 1.15rem; display: grid; }
.orbit-card-balance small { font-size: .62rem; opacity: .64; }
.orbit-card-balance strong { font-size: 1.45rem; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.orbit-card-number { position: absolute; inset: auto 1.15rem 1.25rem auto; font: 800 .58rem/1 var(--quest-pixel,ui-monospace,monospace); letter-spacing: .08em; opacity: .66; }

.account-visual-wechat { color: #092c26; background: linear-gradient(125deg,#75dfb7 0 54%,#c7f66e 54%); }
.account-visual-wechat .orbit-card-grid { background: radial-gradient(circle,transparent 0 17px,rgba(9,44,38,.32) 18px 19px,transparent 20px) 0 0/43px 43px; }
.account-visual-wechat .orbit-card-mark { color: #092c26; }
.account-visual-alipay { background: linear-gradient(150deg,#096ef0 0 55%,#48c5ff 55% 76%,#edfaff 76%); }
.account-visual-alipay .orbit-card-grid { background: repeating-radial-gradient(ellipse at 90% 70%,transparent 0 12px,rgba(255,255,255,.5) 13px 15px,transparent 16px 27px); }
.account-visual-reserve { background: linear-gradient(135deg,#24155f,#6253f4 65%,#b5aaff); }
.account-visual-reserve .orbit-card-grid { background: linear-gradient(rgba(255,255,255,.2) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.2) 1px,transparent 1px); background-size: 23px 23px; }
.account-visual-credit,.account-visual-huabei,.account-visual-jd { color:#191018; background: linear-gradient(116deg,#ff765f 0 61%,#ffd3cb 61%); }
.account-visual-credit .orbit-card-grid,.account-visual-huabei .orbit-card-grid,.account-visual-jd .orbit-card-grid { background: repeating-linear-gradient(135deg,transparent 0 18px,rgba(17,16,26,.22) 19px 21px); }
.account-visual-credit .orbit-card-mark,.account-visual-huabei .orbit-card-mark,.account-visual-jd .orbit-card-mark { color:#fff; -webkit-text-stroke:2px #191018; }
.account-visual-cash { color:#281800; background: radial-gradient(circle at 78% 45%,#fff0a0 0 50px,transparent 51px),linear-gradient(135deg,#ffc22f,#ff7d43); }
.account-visual-cash .orbit-card-grid { background: repeating-conic-gradient(from 0deg at 78% 45%,rgba(40,24,0,.22) 0 4deg,transparent 4deg 15deg); }
.account-visual-invest { background: radial-gradient(circle at 88% 12%,rgba(216,255,69,.28),transparent 8rem),#11101a; }
.account-visual-invest .orbit-card-grid { background: linear-gradient(150deg,transparent 0 43%,#d8ff45 44% 46%,transparent 47% 57%,#ff765f 58% 60%,transparent 61%); }
.account-visual-digital { color:#082b37; background:linear-gradient(135deg,#78e4ea,#bdf4cc 60%,#efffc5); }
.account-visual-digital .orbit-card-mark { color:#0c6070; }

.account-carousel-arrow { position: absolute; z-index: 40; inset: 46% auto auto; display: grid; place-items: center; width: 42px; height: 42px; color: #171522; border: 1px solid #171522; border-radius: 50%; background: #d8ff45; box-shadow: 4px 4px 0 #6357ff; cursor: pointer; transform: translateY(-50%); transition: transform 180ms ease,box-shadow 180ms ease; }
.account-carousel-arrow:hover { transform: translate(-2px,calc(-50% - 2px)); box-shadow: 6px 6px 0 #6357ff; }
.account-carousel-arrow.previous { left: 1rem; }
.account-carousel-arrow.next { right: 1rem; }
.account-carousel-position { position: absolute; inset: auto 18% 1.5rem; height: 4px; overflow: hidden; background: rgba(17,16,26,.1); }
.account-carousel-position i { display:block; height:100%; background:#6357ff; transition:width 560ms cubic-bezier(.16,1,.3,1); }

.account-focus-panel { min-width:0; min-height:462px; padding:1.35rem; color:#f8f6ff; border-radius:3px 28px 3px 28px; background:radial-gradient(circle at 100% 0,rgba(99,87,255,.7),transparent 14rem),#11101a; box-shadow:10px 12px 0 rgba(99,87,255,.17); }
.account-focus-panel > header { display:flex; align-items:flex-start; justify-content:space-between; gap:1rem; padding-bottom:1rem; border-bottom:1px solid rgba(255,255,255,.14); }
.account-focus-panel header > div { display:grid; gap:.45rem; }
.account-focus-panel h3 { margin:0; color:#fff; font-size:1.65rem; line-height:1; letter-spacing:-.055em; }
.account-focus-mark { color:#d8ff45; font:900 2.5rem/.8 "Microsoft YaHei UI",system-ui,sans-serif; transform:rotate(5deg); }
.account-focus-balance { display:grid; gap:.3rem; margin:1.3rem 0; }
.account-focus-balance small { color:rgba(255,255,255,.52); }
.account-focus-balance strong { color:#fff; font-size:2rem; line-height:1; letter-spacing:-.05em; font-variant-numeric:tabular-nums; }
.account-focus-panel dl { display:grid; gap:0; margin:0; }
.account-focus-panel dl > div { display:grid; grid-template-columns:82px minmax(0,1fr); gap:.8rem; padding:.72rem 0; border-top:1px solid rgba(255,255,255,.1); }
.account-focus-panel dt { color:rgba(255,255,255,.44); font-size:.72rem; }
.account-focus-panel dd { min-width:0; margin:0; overflow:hidden; color:rgba(255,255,255,.84); font-size:.78rem; text-overflow:ellipsis; white-space:nowrap; }
.account-focus-panel footer { display:flex; flex-wrap:wrap; gap:.7rem; margin-top:1.3rem; }
.account-detail-reveal-enter-active,.account-detail-reveal-leave-active { transition:opacity 220ms ease,transform 480ms cubic-bezier(.16,1,.3,1); }
.account-detail-reveal-enter-from,.account-detail-reveal-leave-to { opacity:0; transform:translateX(24px) scale(.98); }

@media (max-width:1180px) {
    .account-gallery-layout { grid-template-columns:1fr; }
    .account-focus-panel { min-height:auto; }
}

@media (prefers-reduced-motion:reduce) {
    .account-orbit-card,.account-carousel-position i,.account-detail-reveal-enter-active,.account-detail-reveal-leave-active { transition:none !important; }
}
</style>
