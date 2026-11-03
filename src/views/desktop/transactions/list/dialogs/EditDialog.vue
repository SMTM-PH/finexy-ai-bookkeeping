<template>
    <v-dialog class="transaction-edit-dialog finexy-dialog finexy-dialog--wide" width="920" max-width="calc(100vw - 24px)"
              max-height="calc(100dvh - 24px)" scrollable
              :persistent="isTransactionModified || recognizing || isAIReview || ordinaryCreateLocked" v-model="showState">
        <v-card class="transaction-edit-card pa-sm-1 pa-md-2">
            <template #title>
                <div class="transaction-edit-title d-flex align-center justify-center">
                    <div class="d-flex align-center">
                        <h4 class="text-h4">{{ tt(title) }}</h4>
                        <v-progress-circular indeterminate size="22" class="ms-2" v-if="loading"></v-progress-circular>
                    </div>
                    <v-spacer/>
                    <small class="ms-2 text-truncate" v-if="recognizing">{{ tt('AI can make mistakes. Check important info.') }}</small>
                    <v-btn class="transaction-ai-image-action ms-2"
                           color="primary" variant="tonal"
                           :prepend-icon="mdiMagicStaff"
                           :disabled="loading || submitting || recognizing || ordinaryCreateLocked"
                           v-if="!isAIReview && mode === TransactionEditPageMode.Add && type === TransactionEditPageType.Transaction && activeTab === 'basicInfo' && isTransactionFromAIImageRecognitionEnabled()"
                           @click="recognizeFromImage">
                        {{ tt('AI Image Recognition') }}
                    </v-btn>
                    <v-btn density="comfortable" color="default" variant="text" class="ms-2" :icon="true"
                           :aria-label="tt('AI Clipboard Text Recognition')"
                           :disabled="loading || submitting || recognizing || ordinaryCreateLocked"
                           v-if="!isAIReview && mode === TransactionEditPageMode.Add && type === TransactionEditPageType.Transaction && activeTab === 'basicInfo' && isTransactionFromAITextRecognitionEnabled()"
                           @click="recognizeFromClipboard">
                        <v-icon :icon="mdiMagicStaff" size="22" v-if="!recognizing"/>
                        <v-tooltip activator="parent">{{ tt('AI Clipboard Text Recognition') }}</v-tooltip>
                        <v-progress-circular indeterminate size="22" v-if="recognizing"></v-progress-circular>
                    </v-btn>
                    <v-btn density="comfortable" color="default" variant="text" class="ms-2" :icon="true"
                           :aria-label="tt('More')"
                           :disabled="loading || submitting || recognizing || ordinaryCreateLocked || (isAIReview && aiReviewLocked)" v-if="mode !== TransactionEditPageMode.View && (activeTab === 'basicInfo' || (activeTab === 'map' && isSupportGetGeoLocationByClick()))">
                        <v-icon :icon="mdiDotsVertical" />
                        <v-tooltip activator="parent">{{ tt('More') }}</v-tooltip>
                        <v-menu activator="parent">
                            <v-list v-if="activeTab === 'basicInfo'">
                                <v-list-item :prepend-icon="mdiSwapHorizontal"
                                             :title="tt('Swap Account')"
                                             v-if="transaction.type === TransactionType.Transfer"
                                             @click="swapTransactionData(true, false)"></v-list-item>
                                <v-list-item :prepend-icon="mdiSwapHorizontal"
                                             :title="tt('Swap Amount')"
                                             v-if="transaction.type === TransactionType.Transfer"
                                             @click="swapTransactionData(false, true)"></v-list-item>
                                <v-list-item :prepend-icon="mdiSwapHorizontal"
                                             :title="tt('Swap Account and Amount')"
                                             v-if="transaction.type === TransactionType.Transfer"
                                             @click="swapTransactionData(true, true)"></v-list-item>
                                <v-divider v-if="transaction.type === TransactionType.Transfer" />
                                <v-list-item :prepend-icon="mdiEyeOutline"
                                             :title="tt('Show Amount')"
                                             v-if="transaction.hideAmount" @click="transaction.hideAmount = false"></v-list-item>
                                <v-list-item :prepend-icon="mdiEyeOffOutline"
                                             :title="tt('Hide Amount')"
                                             v-if="!transaction.hideAmount" @click="transaction.hideAmount = true"></v-list-item>
                            </v-list>
                            <v-list v-if="activeTab === 'map'">
                                <v-list-item key="setGeoLocationByClickMap" value="setGeoLocationByClickMap"
                                             :prepend-icon="mdiMapMarkerOutline"
                                             :disabled="!transaction.geoLocation" v-if="isSupportGetGeoLocationByClick()">
                                    <v-list-item-title class="cursor-pointer" @click="setGeoLocationByClickMap = !setGeoLocationByClickMap; geoMenuState = false">
                                        <div class="d-flex align-center">
                                            <span>{{ tt('Click on Map to Set Geographic Location') }}</span>
                                            <v-spacer/>
                                            <v-icon :icon="mdiCheck" v-if="setGeoLocationByClickMap" />
                                        </div>
                                    </v-list-item-title>
                                </v-list-item>
                            </v-list>
                        </v-menu>
                    </v-btn>
                </div>
            </template>
            <v-card-text v-if="isCreateTemplate">
                <template-creation-panel ref="templateCreationPanel" :active="isCreateTemplate && showState" :ready="!loading"
                    :capture-action-current="captureTemplateActionCurrent"
                    :expected-recovery-target="initOptions?.expectedRecoveryTarget"
                    :ledger-id="openedLedgerId" :template="transaction" :can-manage="transactionTemplatesStore.canManage"
                    :raw-source-amount="templateSourceRaw" :raw-destination-amount="templateDestinationRaw"
                    :validate="() => inputEmptyProblemMessage ? tt(inputEmptyProblemMessage) : null"
                    @restore="restoreTemplateInput" @locked="templateCreationLocked = $event" @complete="completeTemplateCreation" />
            </v-card-text>
            <v-card-text v-if="isAIReview" class="ai-review-confirmation-container">
                <AIReviewConfirmationPanel ref="aiReviewConfirmationPanel" :active="isAIReview && showState" :ready="!loading"
                    :ledger-id="openedLedgerId" :item-id="aiReviewItemId" :transaction="transaction" :can-write="aiReviewStore.canWrite && aiReviewEditable"
                    :raw-source-amount="templateSourceRaw" :raw-destination-amount="templateDestinationRaw" :transfer-direction="transferDirection"
                    :validate="() => inputEmptyProblemMessage ? tt(inputEmptyProblemMessage) : null"
                    @restore="restoreAIReviewInput" @restore-request="restoreAIReviewRequest" @locked="aiReviewLocked = $event" @complete="completeAIReview" />
            </v-card-text>
            <v-card-text v-if="isOrdinaryCreate && (ordinaryPending || ordinaryRecoveryError || (!loading && !ordinaryCanWrite))" class="ordinary-create-recovery" aria-live="polite">
                <p v-if="ordinaryPending"><strong>{{ ordinaryPending.receipt ? '原记账已返回创建回执' : '这笔原记账结果尚未确认' }}</strong></p>
                <p v-if="ordinaryPending">原请求固定在打开时的账本，表单保持锁定。{{ ordinaryPending.receipt ? '重新读取只核对原流水，不会再次新增。' : '明确重试时复用原内容与原请求标识。' }}</p>
                <p v-if="ordinaryRecoveryError" class="text-error" role="alert">{{ ordinaryRecoveryError }}</p>
                <p v-if="!ordinaryCanWrite && !ordinaryPending?.receipt">当前成员为只读权限，原请求和草稿已保留。</p>
                <v-btn v-if="ordinaryPending" color="primary" :disabled="loading || submitting || ordinarySessionInvalid || (!ordinaryCanWrite && !ordinaryPending.receipt)" @click="save(AfterSaveAction.GoBack)">
                    {{ ordinaryPending.receipt ? '重新读取原结果' : '重试原请求' }}
                </v-btn>
            </v-card-text>
            <v-card-text v-if="!ordinarySessionInvalid" :inert="ordinaryCreateLocked || (isCreateTemplate && (templateCreationLocked || !transactionTemplatesStore.canManage)) || (isAIReview && aiReviewLocked)" class="transaction-edit-body d-flex flex-column flex-md-row flex-grow-1 overflow-y-auto">
                <div class="mb-4">
                    <v-tabs class="v-tabs-pill" direction="vertical" :class="{ 'readonly': type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit }"
                            :disabled="loading || submitting || recognizing" v-model="transaction.type">
                        <v-tab :value="TransactionType.Expense" :disabled="type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && transaction.type !== TransactionType.Expense" v-if="transaction.type !== TransactionType.ModifyBalance">
                            <span>{{ tt('Expense') }}</span>
                        </v-tab>
                        <v-tab :value="TransactionType.Income" :disabled="type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && transaction.type !== TransactionType.Income" v-if="transaction.type !== TransactionType.ModifyBalance">
                            <span>{{ tt('Income') }}</span>
                        </v-tab>
                        <v-tab :value="TransactionType.Transfer" :disabled="type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && transaction.type !== TransactionType.Transfer" v-if="transaction.type !== TransactionType.ModifyBalance">
                            <span>{{ tt('Transfer') }}</span>
                        </v-tab>
                        <v-tab :value="TransactionType.ModifyBalance" v-if="type === TransactionEditPageType.Transaction && transaction.type === TransactionType.ModifyBalance">
                            <span>{{ tt('Modify Balance') }}</span>
                        </v-tab>
                    </v-tabs>
                    <v-divider class="my-2"/>
                    <v-tabs direction="vertical" :disabled="loading || submitting || recognizing" v-model="activeTab">
                        <v-tab value="basicInfo">
                            <span>{{ tt('Basic Information') }}</span>
                        </v-tab>
                        <v-tab value="map" :disabled="!transaction.geoLocation" v-if="type === TransactionEditPageType.Transaction && !!getMapProvider()">
                            <span>{{ tt('Location on Map') }}</span>
                        </v-tab>
                        <v-tab value="pictures" :disabled="mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && (!transaction.pictures || !transaction.pictures.length)" v-if="type === TransactionEditPageType.Transaction && isTransactionPicturesEnabled() && !props.ledgerId">
                            <span>{{ tt('Pictures') }}</span>
                        </v-tab>
                    </v-tabs>
                </div>

                <v-window class="d-flex flex-grow-1 disable-tab-transition w-100-window-container ms-md-5"
                          v-model="activeTab">
                    <v-window-item value="basicInfo">
                        <v-form class="mt-2">
                            <v-row>
                                <v-col cols="12" v-if="type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate">
                                    <v-text-field
                                        type="text"
                                        persistent-placeholder
                                        :disabled="loading || submitting || recognizing"
                                        :label="tt('Template Name')"
                                        :placeholder="tt('Template Name')"
                                        v-model="transaction.name"
                                    />
                                </v-col>
                                <v-col cols="12" v-if="transaction.type === TransactionType.Transfer">
                                    <div class="transfer-direction" role="group" aria-label="转账方向">
                                        <button v-for="option in transferDirectionOptions" :key="option.value" type="button" :class="{ active: transferDirection === option.value }" :disabled="loading || submitting || recognizing || mode === TransactionEditPageMode.View" @click="setTransferDirection(option.value)">
                                            <b>{{ option.label }}</b><small>{{ option.help }}</small>
                                        </button>
                                    </div>
                                </v-col>
                                <v-col cols="12" :md="transferDirection === 'internal' && transaction.type === TransactionType.Transfer ? 6 : 12" v-if="transaction.type !== TransactionType.Transfer || transferDirection !== 'in'">
                                    <amount-input class="transaction-edit-amount font-weight-bold"
                                                  :color="sourceAmountColor"
                                                  :currency="sourceAccountCurrency"
                                                  :show-currency="true"
                                                  :readonly="mode === TransactionEditPageMode.View"
                                                  :disabled="loading || submitting || recognizing"
                                                  :persistent-placeholder="true"
                                                  :hide="transaction.hideAmount"
                                                  :label="sourceAmountTitle"
                                                  :placeholder="tt(sourceAmountName)"
                                                  :enable-formula="mode !== TransactionEditPageMode.View"
                                                  :raw-value="isCreateTemplate || isAIReview ? templateSourceRaw : undefined" @update:raw-value="(value: string) => { if (isCreateTemplate || isAIReview) templateSourceRaw = value; }"
                                                  v-model="transaction.sourceAmount"/>
                                </v-col>
                                <v-col cols="12" :md="transferDirection === 'internal' ? 6 : 12" v-if="transaction.type === TransactionType.Transfer && transferDirection !== 'out'">
                                    <amount-input class="transaction-edit-amount font-weight-bold" color="primary"
                                                  :currency="destinationAccountCurrency"
                                                  :show-currency="true"
                                                  :readonly="mode === TransactionEditPageMode.View"
                                                  :disabled="loading || submitting || recognizing"
                                                  :persistent-placeholder="true"
                                                  :hide="transaction.hideAmount"
                                                  :label="transferInAmountTitle"
                                                  :placeholder="tt('Transfer In Amount')"
                                                  :enable-formula="mode !== TransactionEditPageMode.View"
                                                  :raw-value="isCreateTemplate || isAIReview ? templateDestinationRaw : undefined" @update:raw-value="(value: string) => { if (isCreateTemplate || isAIReview) templateDestinationRaw = value; }"
                                                  v-model="transaction.destinationAmount"/>
                                </v-col>
                                <v-col cols="12" md="12" v-if="transaction.type === TransactionType.Expense">
                                    <v-tooltip :disabled="hasVisibleExpenseCategories" :text="hasVisibleExpenseCategories ? '' : tt('No secondary expense categories are available')">
                                        <template v-slot:activator="{ props }">
                                            <div v-bind="props" class="d-block">
                                                <two-column-select key="expense-category" primary-key-field="id" primary-value-field="id" primary-title-field="name"
                                                                   primary-icon-field="icon" primary-icon-type="category" primary-color-field="color"
                                                                   primary-hidden-field="hidden" primary-sub-items-field="subCategories"
                                                                   secondary-key-field="id" secondary-value-field="id" secondary-title-field="name"
                                                                   secondary-icon-field="icon" secondary-icon-type="category" secondary-color-field="color"
                                                                   secondary-hidden-field="hidden"
                                                                   :readonly="mode === TransactionEditPageMode.View"
                                                                   :disabled="loading || submitting || recognizing || !hasVisibleExpenseCategories"
                                                                   :enable-filter="true" :filter-placeholder="tt('Find category')" :filter-no-items-text="tt('No available category')"
                                                                   :show-selection-primary-text="true"
                                                                   :custom-selection-primary-text="getTransactionPrimaryCategoryName(transaction.expenseCategoryId, allCategories[CategoryType.Expense])"
                                                                   :custom-selection-secondary-text="getTransactionSecondaryCategoryName(transaction.expenseCategoryId, allCategories[CategoryType.Expense])"
                                                                   :label="tt('Category')" :placeholder="tt('Category')"
                                                                   :items="allCategories[CategoryType.Expense] || []"
                                                                   v-model="transaction.expenseCategoryId">
                                                </two-column-select>
                                            </div>
                                        </template>
                                    </v-tooltip>
                                </v-col>
                                <v-col cols="12" md="12" v-if="transaction.type === TransactionType.Income">
                                    <v-tooltip :disabled="hasVisibleIncomeCategories" :text="hasVisibleIncomeCategories ? '' : tt('No secondary income categories are available')">
                                        <template v-slot:activator="{ props }">
                                            <div v-bind="props" class="d-block">
                                                <two-column-select key="income-category" primary-key-field="id" primary-value-field="id" primary-title-field="name"
                                                                   primary-icon-field="icon" primary-icon-type="category" primary-color-field="color"
                                                                   primary-hidden-field="hidden" primary-sub-items-field="subCategories"
                                                                   secondary-key-field="id" secondary-value-field="id" secondary-title-field="name"
                                                                   secondary-icon-field="icon" secondary-icon-type="category" secondary-color-field="color"
                                                                   secondary-hidden-field="hidden"
                                                                   :readonly="mode === TransactionEditPageMode.View"
                                                                   :disabled="loading || submitting || recognizing || !hasVisibleIncomeCategories"
                                                                   :enable-filter="true" :filter-placeholder="tt('Find category')" :filter-no-items-text="tt('No available category')"
                                                                   :show-selection-primary-text="true"
                                                                   :custom-selection-primary-text="getTransactionPrimaryCategoryName(transaction.incomeCategoryId, allCategories[CategoryType.Income])"
                                                                   :custom-selection-secondary-text="getTransactionSecondaryCategoryName(transaction.incomeCategoryId, allCategories[CategoryType.Income])"
                                                                   :label="tt('Category')" :placeholder="tt('Category')"
                                                                   :items="allCategories[CategoryType.Income] || []"
                                                                   v-model="transaction.incomeCategoryId">
                                                </two-column-select>
                                            </div>
                                        </template>
                                    </v-tooltip>
                                </v-col>
                                <v-col cols="12" md="12" v-if="transaction.type === TransactionType.Transfer">
                                    <v-tooltip :disabled="hasVisibleTransferCategories" :text="hasVisibleTransferCategories ? '' : tt('No secondary transfer categories are available')">
                                        <template v-slot:activator="{ props }">
                                            <div v-bind="props" class="d-block">
                                                <two-column-select key="transfer-category" primary-key-field="id" primary-value-field="id" primary-title-field="name"
                                                                   primary-icon-field="icon" primary-icon-type="category" primary-color-field="color"
                                                                   primary-hidden-field="hidden" primary-sub-items-field="subCategories"
                                                                   secondary-key-field="id" secondary-value-field="id" secondary-title-field="name"
                                                                   secondary-icon-field="icon" secondary-icon-type="category" secondary-color-field="color"
                                                                   secondary-hidden-field="hidden"
                                                                   :readonly="mode === TransactionEditPageMode.View"
                                                                   :disabled="loading || submitting || recognizing || !hasVisibleTransferCategories"
                                                                   :enable-filter="true" :filter-placeholder="tt('Find category')" :filter-no-items-text="tt('No available category')"
                                                                   :show-selection-primary-text="true"
                                                                   :custom-selection-primary-text="getTransactionPrimaryCategoryName(transaction.transferCategoryId, allCategories[CategoryType.Transfer])"
                                                                   :custom-selection-secondary-text="getTransactionSecondaryCategoryName(transaction.transferCategoryId, allCategories[CategoryType.Transfer])"
                                                                   :label="tt('Category')" :placeholder="tt('Category')"
                                                                   :items="allCategories[CategoryType.Transfer] || []"
                                                                   v-model="transaction.transferCategoryId">
                                                </two-column-select>
                                            </div>
                                        </template>
                                    </v-tooltip>
                                </v-col>
                                <v-col cols="12" :md="transaction.type === TransactionType.Transfer && transferDirection === 'internal' ? 6 : 12" v-if="transaction.type !== TransactionType.Transfer || transferDirection !== 'in'">
                                    <v-tooltip :disabled="!!allVisibleAccounts.length" :text="allVisibleAccounts.length ? '' : tt('No available account')">
                                        <template v-slot:activator="{ props }">
                                            <div v-bind="props" class="d-block">
                                                <two-column-select primary-key-field="id" primary-value-field="category"
                                                                   primary-title-field="name" primary-footer-field="displayBalance"
                                                                   primary-icon-field="icon" primary-icon-type="account"
                                                                   primary-sub-items-field="accounts"
                                                                   :primary-title-i18n="true"
                                                                   secondary-key-field="id" secondary-value-field="id"
                                                                   secondary-title-field="name" secondary-footer-field="displayBalance"
                                                                   secondary-icon-field="icon" secondary-icon-type="account" secondary-color-field="color"
                                                                   :readonly="mode === TransactionEditPageMode.View"
                                                                   :disabled="loading || submitting || recognizing || !allVisibleAccounts.length || (mode === TransactionEditPageMode.Edit && transaction.type === TransactionType.ModifyBalance)"
                                                                   :enable-filter="true" :filter-placeholder="tt('Find account')" :filter-no-items-text="tt('No available account')"
                                                                   :custom-selection-primary-text="sourceAccountName"
                                                                   :label="tt(sourceAccountTitle)"
                                                                   :placeholder="tt(sourceAccountTitle)"
                                                                   :items="allVisibleCategorizedAccounts"
                                                                   v-model="transaction.sourceAccountId">
                                                </two-column-select>
                                            </div>
                                        </template>
                                    </v-tooltip>
                                </v-col>
                                <v-col cols="12" :md="transferDirection === 'internal' ? 6 : 12" v-if="transaction.type === TransactionType.Transfer && transferDirection !== 'out'">
                                    <v-tooltip :disabled="!!allVisibleAccounts.length" :text="allVisibleAccounts.length ? '' : tt('No available account')">
                                        <template v-slot:activator="{ props }">
                                            <div v-bind="props" class="d-block">
                                                <two-column-select primary-key-field="id" primary-value-field="category"
                                                                   primary-title-field="name" primary-footer-field="displayBalance"
                                                                   primary-icon-field="icon" primary-icon-type="account"
                                                                   primary-sub-items-field="accounts"
                                                                   :primary-title-i18n="true"
                                                                   secondary-key-field="id" secondary-value-field="id"
                                                                   secondary-title-field="name" secondary-footer-field="displayBalance"
                                                                   secondary-icon-field="icon" secondary-icon-type="account" secondary-color-field="color"
                                                                   :readonly="mode === TransactionEditPageMode.View"
                                                                   :disabled="loading || submitting || recognizing || !allVisibleAccounts.length"
                                                                   :enable-filter="true" :filter-placeholder="tt('Find account')" :filter-no-items-text="tt('No available account')"
                                                                   :custom-selection-primary-text="destinationAccountName"
                                                                   :label="tt('Destination Account')"
                                                                   :placeholder="tt('Destination Account')"
                                                                   :items="allVisibleCategorizedAccounts"
                                                                   v-model="transaction.destinationAccountId">
                                                </two-column-select>
                                            </div>
                                        </template>
                                    </v-tooltip>
                                </v-col>
                                <v-col cols="12" md="6" v-if="type === TransactionEditPageType.Transaction">
                                    <date-time-select
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing || (mode === TransactionEditPageMode.Edit && transaction.type === TransactionType.ModifyBalance)"
                                        :label="tt('Transaction Time')"
                                        :timezone-utc-offset="transaction.utcOffset"
                                        :model-value="transaction.time"
                                        @update:model-value="updateTransactionTime"
                                        @error="onShowDateTimeError" />
                                </v-col>
                                <v-col cols="12" md="6" v-if="type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type">
                                    <schedule-frequency-select
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing"
                                        :label="tt('Scheduled Transaction Frequency')"
                                        v-model:type="transaction.scheduledFrequencyType"
                                        v-model="transaction.scheduledFrequency" />
                                </v-col>
                                <v-col cols="12" md="6" v-if="type === TransactionEditPageType.Transaction || (type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type)">
                                    <v-autocomplete
                                        class="transaction-edit-timezone"
                                        item-title="displayNameWithUtcOffset"
                                        item-value="name"
                                        auto-select-first
                                        persistent-placeholder
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing || (mode === TransactionEditPageMode.Edit && transaction.type === TransactionType.ModifyBalance)"
                                        :label="tt('Transaction Timezone')"
                                        :placeholder="!transaction.timeZone && transaction.timeZone !== '' ? `(${transactionDisplayTimezone}) ${transactionTimezoneTimeDifference}` : tt('Timezone')"
                                        :items="allTimezones"
                                        :no-data-text="tt('No results')"
                                        :model-value="transaction.timeZone"
                                        @update:model-value="updateTransactionTimezone"
                                    >
                                        <template #selection="{ item }">
                                            <span class="text-truncate" v-if="transaction.timeZone || transaction.timeZone === ''">
                                                {{ item.title }}
                                            </span>
                                        </template>
                                    </v-autocomplete>
                                </v-col>
                                <v-col cols="12" md="6" v-if="type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type">
                                    <date-select
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing"
                                        :clearable="true"
                                        :label="tt('Start Date')"
                                        :max-date="scheduledEndDateLimit"
                                        :error="scheduledDateRangeInvalid"
                                        :error-messages="scheduledDateRangeInvalid ? tt('scheduled transaction start date is later than end time') : ''"
                                        :no-data-text="tt('Select a date')"
                                        v-model="transaction.scheduledStartDate" />
                                </v-col>
                                <v-col cols="12" md="6" v-if="type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type">
                                    <date-select
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing"
                                        :clearable="true"
                                        :label="tt('End Date')"
                                        :min-date="scheduledStartDateLimit"
                                        :error="scheduledDateRangeInvalid"
                                        :error-messages="scheduledDateRangeInvalid ? tt('scheduled transaction start date is later than end time') : ''"
                                        :no-data-text="tt('No limit')"
                                        v-model="transaction.scheduledEndDate" />
                                </v-col>
                                <v-col cols="12" md="6" v-if="type === TransactionEditPageType.Transaction">
                                    <v-select
                                        persistent-placeholder
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing"
                                        :label="tt('Geographic Location')"
                                        v-model="transaction"
                                        v-model:menu="geoMenuState"
                                    >
                                        <template #selection>
                                            <span class="cursor-pointer" v-if="transaction.geoLocation">{{ `(${formatCoordinate(transaction.geoLocation, coordinateDisplayType)})` }}</span>
                                            <span class="cursor-pointer" v-else-if="!transaction.geoLocation">{{ geoLocationStatusInfo }}</span>
                                        </template>

                                        <template #no-data>
                                            <v-list class="py-0">
                                                <v-list-item v-if="mode !== TransactionEditPageMode.View" @click="updateGeoLocation(true)">{{ tt('Update Geographic Location') }}</v-list-item>
                                                <v-list-item v-if="mode !== TransactionEditPageMode.View" @click="clearGeoLocation">{{ tt('Clear Geographic Location') }}</v-list-item>
                                            </v-list>
                                        </template>
                                    </v-select>
                                </v-col>
                                <v-col cols="12" md="12" v-if="!isSharedTemplate && !(isAIReview && openedLedgerId !== '0')">
                                    <transaction-tag-auto-complete
                                        class="transaction-edit-tags"
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing"
                                        :show-label="true"
                                        :allow-add-new-tag="true"
                                        v-model="transaction.tagIds"
                                        @tag:saving="onSavingTag"
                                    />
                                </v-col>
                                <v-col cols="12" v-else>
                                    <p role="status">{{ isAIReview ? 'AI 入账保留原识别结果的标签。当前表单暂不支持修改账本所有者的标签关联。' : '共享账本模板保留原有标签。当前表单暂不支持修改账本所有者的标签关联。' }}</p>
                                </v-col>
                                <v-col cols="12" md="12">
                                    <v-textarea
                                        type="text"
                                        persistent-placeholder
                                        rows="3"
                                        :readonly="mode === TransactionEditPageMode.View"
                                        :disabled="loading || submitting || recognizing"
                                        :label="transactionDescriptionTitle"
                                        :placeholder="tt('Your transaction description (optional)')"
                                        v-model="transaction.comment"
                                    />
                                </v-col>
                            </v-row>
                        </v-form>
                    </v-window-item>
                    <v-window-item value="map">
                        <v-row>
                            <v-col cols="12" md="12">
                                <map-view ref="map" map-class="transaction-edit-map-view"
                                          :enable-zoom-control="true" :geo-location="transaction.geoLocation"
                                          @click="updateSpecifiedGeoLocation">
                                    <template #error-title="{ mapSupported, mapDependencyLoaded }">
                                        <span class="text-subtitle-1" v-if="!mapSupported"><b>{{ tt('Unsupported Map Provider') }}</b></span>
                                        <span class="text-subtitle-1" v-else-if="!mapDependencyLoaded"><b>{{ tt('Cannot Initialize Map') }}</b></span>
                                    </template>
                                    <template #error-content>
                                        <p class="text-body-1">
                                            {{ tt('Please refresh the page and try again. If the error persists, ensure that the server\'s map settings are correctly configured.') }}
                                        </p>
                                    </template>
                                </map-view>
                            </v-col>
                        </v-row>
                    </v-window-item>
                    <v-window-item value="pictures">
                        <v-row class="transaction-pictures align-content-start" :class="{ 'readonly': submitting || uploadingPicture || removingPictureId }">
                            <v-col cols="12">
                                <div class="transaction-picture-note d-flex align-center ga-3">
                                    <v-icon :icon="mdiImagePlusOutline" size="26" />
                                    <div>
                                        <div class="text-subtitle-1 font-weight-medium">{{ tt('Transaction Pictures') }}</div>
                                        <div class="text-body-2 text-medium-emphasis">{{ tt('Pictures here are transaction attachments and do not trigger AI recognition.') }}</div>
                                    </div>
                                </div>
                            </v-col>
                            <v-col :key="picIdx" cols="6" md="3" v-for="(pictureInfo, picIdx) in transaction.pictures">
                                <v-avatar rounded="lg" variant="tonal" size="160"
                                          class="cursor-pointer transaction-picture"
                                          color="rgba(0,0,0,0)" @click="viewOrRemovePicture(pictureInfo)">
                                    <v-img :src="getTransactionPictureUrl(pictureInfo)">
                                        <template #placeholder>
                                            <div class="d-flex align-center justify-center fill-height bg-light-primary">
                                                <v-progress-circular color="grey-500" indeterminate size="48"></v-progress-circular>
                                            </div>
                                        </template>
                                        <template #error>
                                            <div class="d-flex align-center justify-center fill-height bg-light-primary">
                                                <span class="text-body-1">{{ tt('Failed to load image, please check whether the config "domain" and "root_url" are set correctly.') }}</span>
                                            </div>
                                        </template>
                                    </v-img>
                                    <div class="picture-control-icon" :class="{ 'show-control-icon': pictureInfo.pictureId === removingPictureId }">
                                        <v-icon size="64" :icon="mdiTrashCanOutline" v-if="(mode === TransactionEditPageMode.Add || mode === TransactionEditPageMode.Edit) && pictureInfo.pictureId !== removingPictureId"/>
                                        <v-progress-circular color="grey-500" indeterminate size="48" v-if="(mode === TransactionEditPageMode.Add || mode === TransactionEditPageMode.Edit) && pictureInfo.pictureId === removingPictureId"></v-progress-circular>
                                        <v-icon size="64" :icon="mdiFullscreen" v-if="mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit"/>
                                    </div>
                                </v-avatar>
                            </v-col>
                            <v-col cols="6" md="3" v-if="canAddTransactionPicture">
                                <button type="button"
                                        class="transaction-picture transaction-picture-add"
                                        :disabled="submitting"
                                        :aria-label="tt('Add Picture')"
                                        @click="showOpenPictureDialog">
                                    <div class="d-flex flex-column align-center ga-2" v-if="!uploadingPicture">
                                        <v-icon class="transaction-picture-add-icon" size="44" :icon="mdiImagePlusOutline" />
                                        <span class="text-body-1 font-weight-medium">{{ tt('Add Picture') }}</span>
                                        <small class="text-medium-emphasis">{{ tt('Transaction Pictures') }}</small>
                                    </div>
                                    <v-progress-circular color="grey-500" indeterminate size="48" v-if="uploadingPicture"></v-progress-circular>
                                </button>
                            </v-col>
                        </v-row>
                    </v-window-item>
                </v-window>
            </v-card-text>
            <v-card-text class="transaction-edit-actions">
                <div class="w-100 d-flex justify-center flex-wrap mt-sm-1 mt-md-2 gap-4">
                    <v-tooltip :disabled="!inputIsEmpty" :text="inputEmptyProblemMessage ? tt(inputEmptyProblemMessage) : ''">
                        <template v-slot:activator="{ props }">
                            <div v-bind="props" class="d-inline-block">
                                <v-btn-group density="comfortable" v-if="!ordinaryPending && (mode === TransactionEditPageMode.Add || mode === TransactionEditPageMode.Edit)">
                                    <v-btn color="primary" :disabled="ordinaryCreateLocked || inputIsEmpty || loading || submitting || recognizing || (isCreateTemplate && templateCreationLocked) || (isAIReview && aiReviewLocked)" @click="save(AfterSaveAction.GoBack)">
                                        {{ isCreateTemplate || isAIReview ? '预览并确认' : tt(saveButtonTitle) }}
                                        <v-progress-circular indeterminate size="22" class="ms-2" v-if="submitting"></v-progress-circular>
                                    </v-btn>
                                    <v-btn color="primary" density="compact"
                                           :disabled="ordinaryCreateLocked || inputIsEmpty || loading || submitting || recognizing" :icon="true"
                                           v-if="!isAIReview && type === TransactionEditPageType.Transaction && mode === TransactionEditPageMode.Add">
                                        <v-icon :icon="mdiMenuDown" size="24" />
                                        <v-menu activator="parent">
                                            <v-list>
                                                <v-list-item :title="tt(TransactionQuickAddButtonActionType.SaveAndAddNewTransaction.name)"
                                                             @click="save(AfterSaveAction.StayWithNewTransaction)"></v-list-item>
                                                <v-list-item :title="tt(TransactionQuickAddButtonActionType.SaveAndKeepCurrentData.name)"
                                                             @click="save(AfterSaveAction.StayWithCurrentTransaction)"></v-list-item>
                                            </v-list>
                                        </v-menu>
                                    </v-btn>
                                </v-btn-group>
                            </div>
                        </template>
                    </v-tooltip>
                    <v-btn-group variant="tonal" density="comfortable"
                                 v-if="mode === TransactionEditPageMode.View && transaction.type !== TransactionType.ModifyBalance">
                        <v-btn :disabled="loading || submitting || recognizing"
                               @click="duplicate(false, false)">{{ tt('Duplicate') }}</v-btn>
                        <v-btn density="compact" :disabled="loading || submitting || recognizing" :icon="true">
                            <v-icon :icon="mdiMenuDown" size="24" />
                            <v-menu activator="parent">
                                <v-list>
                                    <v-list-item :title="tt('Duplicate (With Time)')"
                                                 @click="duplicate(true, false)"></v-list-item>
                                    <v-list-item :title="tt('Duplicate (With Geographic Location)')"
                                                 @click="duplicate(false, true)"
                                                 v-if="transaction.geoLocation"></v-list-item>
                                    <v-list-item :title="tt('Duplicate (With Time and Geographic Location)')"
                                                 @click="duplicate(true, true)"
                                                 v-if="transaction.geoLocation"></v-list-item>
                                </v-list>
                            </v-menu>
                        </v-btn>
                    </v-btn-group>
                    <v-btn color="warning" variant="tonal" :disabled="loading || submitting || recognizing"
                           v-if="mode === TransactionEditPageMode.View && originalTransactionEditable"
                           @click="edit">{{ tt('Edit') }}</v-btn>
                    <v-btn color="error" variant="tonal" :disabled="loading || submitting || recognizing"
                           v-if="mode === TransactionEditPageMode.View && originalTransactionEditable" @click="remove">
                        {{ tt('Delete') }}
                        <v-progress-circular indeterminate size="22" class="ms-2" v-if="submitting"></v-progress-circular>
                    </v-btn>
                    <v-btn color="secondary" variant="tonal" :disabled="loading || submitting || recognizing"
                           @click="cancel">{{ tt(cancelButtonTitle) }}</v-btn>
                </div>
            </v-card-text>
        </v-card>
    </v-dialog>

    <AIReviewSourceDialog ref="aiSourceDialog" @complete="completeAISource" />
    <confirm-dialog ref="confirmDialog"/>
    <snack-bar ref="snackbar" />
    <input ref="pictureInput" type="file" style="display: none" :accept="SUPPORTED_IMAGE_EXTENSIONS" @change="onUploadPicture($event)" />
</template>

<script setup lang="ts">
import MapView from '@/components/common/MapView.vue';
import ConfirmDialog from '@/components/desktop/ConfirmDialog.vue';
import SnackBar from '@/components/desktop/SnackBar.vue';
import AIReviewSourceDialog from '@/views/desktop/transactions/list/dialogs/AIReviewSourceDialog.vue';
import TemplateCreationPanel from '@/components/common/TemplateCreationPanel.vue';
import AIReviewConfirmationPanel from '@/components/common/AIReviewConfirmationPanel.vue';
import { useAIReviewLedgerStore } from '@/stores/aiReviewLedger.ts';
import { getTransactionStateIdentity, getCurrentUserInfo, inspectUserTransactionDraft, isUserLogined } from '@/lib/userstate.ts';
import { ledgerPendingDraftRevision } from '@/lib/ledger_pending.ts';
import { mobileLedgerRole, canWriteMobileLedger } from '@/lib/mobile_ledger.ts';
import services from '@/lib/services.ts';
import type { TemplateRecoveryTarget } from '@/lib/template_recovery_locator.ts';
import type { AIReviewEditorInput, AIReviewConfirmRequest } from '@/models/ai_review_ledger.ts';
import { templateFromEditorInput } from '@/lib/template_editor.ts';
import type { TemplateEditorInput } from '@/models/template_creation.ts';

import { ref, computed, useTemplateRef, watch, nextTick, onMounted, onUnmounted } from 'vue';

import { useI18n } from '@/locales/helpers.ts';
import {
    TransactionEditPageMode,
    TransactionEditPageType,
    GeoLocationStatus,
    AfterSaveAction,
    useTransactionEditPageBase
} from '@/views/base/transactions/TransactionEditPageBase.ts';

import { useSettingsStore } from '@/stores/setting.ts';
import { useUserStore } from '@/stores/user.ts';
import { useAccountsStore } from '@/stores/account.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useTransactionTagsStore } from '@/stores/transactionTag.ts';
import { useTransactionsStore } from '@/stores/transaction.ts';
import { useTransactionTemplatesStore } from '@/stores/transactionTemplate.ts';

import type { Coordinate } from '@/core/coordinate.ts';
import { CategoryType } from '@/core/category.ts';
import { TransactionType, TransactionEditScopeType, TransactionQuickAddButtonActionType } from '@/core/transaction.ts';
import { TemplateType, ScheduledTemplateFrequencyType } from '@/core/template.ts';
import { KnownFileType } from '@/core/file.ts';

import { KnownErrorCode } from '@/consts/api.ts';
import { SUPPORTED_IMAGE_EXTENSIONS } from '@/consts/file.ts';

import { TransactionTemplate } from '@/models/transaction_template.ts';
import type { TransactionPictureInfoBasicResponse } from '@/models/transaction_picture_info.ts';
import { Transaction, type PendingTransactionCreate, type TransactionDraft } from '@/models/transaction.ts';
import { TransactionPicture } from '@/models/transaction_picture_info.ts';
import type { RecognizedTransactionResponse } from '@/models/large_language_model.ts';

import { isDefined } from '@/lib/common.ts';
import {
    getTimezoneOffsetMinutes,
    getCurrentUnixTime,
    getLocalDateFromYearDashMonthDashDay
} from '@/lib/datetime.ts';
import { formatCoordinate } from '@/lib/coordinate.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import {
    getTransactionPrimaryCategoryName,
    getTransactionSecondaryCategoryName
} from '@/lib/category.ts';
import { type SetTransactionOptions } from '@/lib/transaction.ts';
import {
    isTransactionFromAITextRecognitionEnabled,
    isTransactionFromAIImageRecognitionEnabled,
    isTransactionPicturesEnabled,
    getMapProvider
} from '@/lib/server_settings.ts';
import {
    isSupportGetGeoLocationByClick
} from '@/lib/map/index.ts';
import { compressJpgImageByQuality } from '@/lib/ui/common.ts';
import logger from '@/lib/logger.ts';

import {
    mdiMagicStaff,
    mdiDotsVertical,
    mdiEyeOffOutline,
    mdiEyeOutline,
    mdiSwapHorizontal,
    mdiMapMarkerOutline,
    mdiCheck,
    mdiMenuDown,
    mdiImagePlusOutline,
    mdiTrashCanOutline,
    mdiFullscreen
} from '@mdi/js';

export interface TransactionEditOptions extends SetTransactionOptions {
    aiReviewItemId?: string;
    aiReviewEditable?: boolean;
    id?: string;
    name?: string;
    templateType?: number;
    scheduledFrequencyType?: number;
    scheduledFrequency?: string;
    template?: TransactionTemplate;
    currentTransaction?: Transaction;
    currentTemplate?: TransactionTemplate;
    autoUploadPicture?: File;
    autoRecognizeClipboardText?: string;
    autoRecognizeText?: string;
    autoSaveAfterRecognition?: boolean;
    noTransactionDraft?: boolean;
    recoverPendingTransaction?: boolean;
    recoverTransactionDraft?: boolean;
    expectedRecoveryClientSessionId?: string;
    expectedRecoveryDraftRevision?: string;
    expectedRecoveryTarget?: TemplateRecoveryTarget;
}

interface TransactionEditResponse {
    message: string;
    transactionId?: string;
    aiReviewItemId?: string;
}

export interface TransactionEditFailure {
    readonly canceled: boolean;
    readonly recognizedTransaction?: RecognizedTransactionResponse;
}

type MapViewType = InstanceType<typeof MapView>;
type ConfirmDialogType = InstanceType<typeof ConfirmDialog>;
type SnackBarType = InstanceType<typeof SnackBar>;

const props = defineProps<{
    type: TransactionEditPageType;
    persistent?: boolean;
    show?: boolean;
    ledgerId?: string;
}>();

const { tt, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals } = useI18n();

const {
    mode,
    isSupportGeoLocation,
    editId,
    addByTemplateId,
    duplicateFromId,
    clientSessionId,
    loading,
    recognizing,
    submitting,
    submitted,
    uploadingPicture,
    geoLocationStatus,
    setGeoLocationByClickMap,
    transaction,
    defaultCurrency,
    coordinateDisplayType,
    imageUploadQualityType,
    allTimezones,
    allVisibleAccounts,
    allVisibleCategorizedAccounts,
    allCategories,
    firstVisibleAccountId,
    hasVisibleExpenseCategories,
    hasVisibleIncomeCategories,
    hasVisibleTransferCategories,
    canAddTransactionPicture,
    title,
    saveButtonTitle,
    cancelButtonTitle,
    sourceAmountName,
    sourceAmountTitle,
    sourceAccountTitle,
    transferInAmountTitle,
    sourceAccountName,
    destinationAccountName,
    sourceAccountCurrency,
    destinationAccountCurrency,
    transactionDisplayTimezone,
    transactionTimezoneTimeDifference,
    geoLocationStatusInfo,
    transactionDescriptionTitle,
    inputEmptyProblemMessage,
    inputIsEmpty,
    createNewTransactionModel,
    setTransactionModel,
    updateTransactionModelByAfterSaveAction,
    updateTransactionTime,
    updateTransactionTimezone,
    swapTransactionData,
    getTransactionPictureUrl
} = useTransactionEditPageBase(props.type);

const settingsStore = useSettingsStore();
const userStore = useUserStore();
const accountsStore = useAccountsStore();
const transactionCategoriesStore = useTransactionCategoriesStore();
const transactionTagsStore = useTransactionTagsStore();
const transactionsStore = useTransactionsStore();
const transactionTemplatesStore = useTransactionTemplatesStore();

const scheduledStartDateLimit = computed<Date | undefined>(() => {
    if (!(transaction.value instanceof TransactionTemplate) || !transaction.value.scheduledStartDate) return undefined;
    return getLocalDateFromYearDashMonthDashDay(transaction.value.scheduledStartDate) || undefined;
});
const scheduledEndDateLimit = computed<Date | undefined>(() => {
    if (!(transaction.value instanceof TransactionTemplate) || !transaction.value.scheduledEndDate) return undefined;
    return getLocalDateFromYearDashMonthDashDay(transaction.value.scheduledEndDate) || undefined;
});
const scheduledDateRangeInvalid = computed<boolean>(() =>
    transaction.value instanceof TransactionTemplate &&
    !!transaction.value.scheduledStartDate &&
    !!transaction.value.scheduledEndDate &&
    transaction.value.scheduledStartDate > transaction.value.scheduledEndDate
);

const map = useTemplateRef<MapViewType>('map');
const confirmDialog = useTemplateRef<ConfirmDialogType>('confirmDialog');
const snackbar = useTemplateRef<SnackBarType>('snackbar');
const pictureInput = useTemplateRef<HTMLInputElement>('pictureInput');
const aiSourceDialog = useTemplateRef<InstanceType<typeof AIReviewSourceDialog>>('aiSourceDialog');

const showState = ref<boolean>(false);
const openedLedgerId = ref('0');
let openedIdentity: string | null = null;
let openedUsername: string | undefined;
let openedOrdinaryDraftRevision: string | undefined;
const ordinaryPending = ref<PendingTransactionCreate | null>(null);
const ordinaryRecoveryError = ref('');
const ordinarySessionInvalid = ref(false);
const ordinaryCanWrite = ref(false);
const isOrdinaryCreate = computed(() => props.type === TransactionEditPageType.Transaction && mode.value === TransactionEditPageMode.Add && !isAIReview.value);
const ordinaryCreateLocked = computed(() => isOrdinaryCreate.value && (!!ordinaryPending.value || ordinarySessionInvalid.value || !ordinaryCanWrite.value));

async function refreshOrdinaryRole(): Promise<void> {
    assertOpenedIdentity();
    if (openedLedgerId.value === '0') { ordinaryCanWrite.value = true; return; }
    const response = await services.listLedgerMembers({ ledgerId: openedLedgerId.value, expectedUsername: openedUsername, ignoreError: true });
    assertOpenedIdentity();
    if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('未能读取原账本权限，请保留原内容后重试');
    ordinaryCanWrite.value = canWriteMobileLedger(mobileLedgerRole(openedLedgerId.value, response.data.result));
}

function assertOpenedIdentity(): void {
    if (!openedIdentity || !isUserLogined() || openedIdentity !== getTransactionStateIdentity() || openedUsername !== getCurrentUserInfo()?.username || openedUsername !== userStore.currentUserBasicInfo?.username) {
        throw new Error('登录账号已变化，原内容已保留，请刷新页面后回原账号核对');
    }
    if (openedLedgerId.value !== (props.ledgerId || '0')) throw new Error('账本已切换，原内容已保留，请回原账本核对');
}
function captureTemplateActionCurrent(): () => boolean {
    const serial = editorGeneration, actionSerial = editorActionGeneration, ledgerId = openedLedgerId.value, identity = openedIdentity;
    return () => serial === editorGeneration && actionSerial === editorActionGeneration && showState.value && ledgerId === (props.ledgerId || '0') &&
        identity === getTransactionStateIdentity() && isUserLogined();
}
function restoreOrdinaryPending(pending: PendingTransactionCreate): void {
    if (ordinaryPending.value?.clientSessionId === pending.clientSessionId) {
        if (JSON.stringify(ordinaryPending.value.request) !== JSON.stringify(pending.request) || (ordinaryPending.value.receipt && pending.receipt && ordinaryPending.value.receipt.id !== pending.receipt.id)) throw new Error('原请求或创建回执已变化，请保留原记录后核对');
        if (ordinaryPending.value.receipt && !pending.receipt) pending = { ...pending, receipt: ordinaryPending.value.receipt };
    }
    ordinaryPending.value = pending;
    clientSessionId.value = pending.clientSessionId;
    transaction.value = Transaction.ofCreateRequest(pending.request);
    if (pending.pictures) transaction.value.setPictures(TransactionPicture.ofMulti([...pending.pictures]));
    transferDirection.value = pending.request.type === TransactionType.Transfer ? pending.request.sourceAccountId === '0' ? 'in' : pending.request.destinationAccountId === '0' ? 'out' : 'internal' : 'internal';
}
function inspectOrdinarySession(): void {
    if (!showState.value || !isOrdinaryCreate.value) return;
    try {
        assertOpenedIdentity();
        const current = transactionsStore.getPendingTransactionCreate(openedLedgerId.value);
        if (ordinaryPending.value && (!current || current.clientSessionId !== ordinaryPending.value.clientSessionId)) {
            ordinaryRecoveryError.value = '原请求已由另一页面处理或改变，请关闭后重新核验；不会打开替代请求';
            ordinarySessionInvalid.value = true;
        } else if (current) restoreOrdinaryPending(current);
    } catch (error) {
        ordinarySessionInvalid.value = true;
        ordinaryRecoveryError.value = error instanceof Error ? error.message : '无法核验原请求，请保留原记录后重新打开';
    }
}
const aiReviewStore = useAIReviewLedgerStore();
const aiReviewItemId = ref(''), aiReviewEditable = ref(false), aiReviewLocked = ref(true);
const isAIReview = computed(() => props.type === TransactionEditPageType.Transaction && !!aiReviewItemId.value);
const aiReviewConfirmationPanel = useTemplateRef<InstanceType<typeof AIReviewConfirmationPanel>>('aiReviewConfirmationPanel');
let editorGeneration = 0;
let editorActionGeneration = 0;
function restoreAIReviewInput(input: AIReviewEditorInput): void {
    const restored = Transaction.ofDraft(input.transaction);
    if (!restored) return;
    restored.expenseCategoryId = input.categorySelections.expense;
    restored.incomeCategoryId = input.categorySelections.income;
    restored.transferCategoryId = input.categorySelections.transfer;
    const previousMode = mode.value, serial = editorGeneration;
    mode.value = TransactionEditPageMode.View;
    transaction.value = restored;
    templateSourceRaw.value = input.rawSourceAmount; templateDestinationRaw.value = input.rawDestinationAmount;
    transferDirection.value = input.transferDirection;
    void nextTick(() => { if (serial === editorGeneration) mode.value = previousMode; });
}
function restoreAIReviewRequest(request: AIReviewConfirmRequest): void {
    const previousMode = mode.value, serial = editorGeneration;
    mode.value = TransactionEditPageMode.View;
    transaction.value = Transaction.ofCreateRequest(request.transaction);
    templateSourceRaw.value = undefined; templateDestinationRaw.value = undefined;
    transferDirection.value = request.transaction.sourceAccountId === '0' ? 'in' : request.transaction.destinationAccountId === '0' && request.transaction.type === TransactionType.Transfer ? 'out' : 'internal';
    void nextTick(() => { if (serial === editorGeneration) mode.value = previousMode; });
}
function completeAIReview(result: { transactionId: string }): void {
    resolveFunc?.({ message: '已确认入账', transactionId: result.transactionId });
    showState.value = false;
}
const templateCreationPanel = useTemplateRef<InstanceType<typeof TemplateCreationPanel>>('templateCreationPanel');
const templateSourceRaw = ref<string>(), templateDestinationRaw = ref<string>(), templateCreationLocked = ref(false);
const isCreateTemplate = computed(() => props.type === TransactionEditPageType.Template && mode.value === TransactionEditPageMode.Add);
function restoreTemplateInput(input: TemplateEditorInput): void {
    transaction.value = templateFromEditorInput(input, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals);
    templateSourceRaw.value = typeof input.sourceAmount === 'string' ? input.sourceAmount : undefined;
    templateDestinationRaw.value = typeof input.destinationAmount === 'string' ? input.destinationAmount : undefined;
}
function completeTemplateCreation(result: { deleted: boolean; id: string }): void {
    resolveFunc?.({ message: result.deleted ? '原模板已删除，未重新创建' : 'You have added a new template' });
    showState.value = false;
}
const isSharedTemplate = computed(() => props.type === TransactionEditPageType.Template && openedLedgerId.value !== '0');
type TransferDirection = 'internal' | 'out' | 'in';
const transferDirection = ref<TransferDirection>('internal');
const transferDirectionOptions: Array<{ value: TransferDirection; label: string; help: string }> = [
    { value: 'internal', label: '账户间转账', help: '我的两个账户之间' },
    { value: 'out', label: '转出', help: '我转给其他人' },
    { value: 'in', label: '转入', help: '其他人转给我' },
];

function setTransferDirection(direction: TransferDirection): void {
    transferDirection.value = direction;
    if (direction === 'out') {
        transaction.value.destinationAccountId = '';
        transaction.value.destinationAmount = 0;
    } else if (direction === 'in') {
        transaction.value.sourceAccountId = '';
        if (!transaction.value.destinationAmount) transaction.value.destinationAmount = transaction.value.sourceAmount;
        transaction.value.sourceAmount = 0;
    }
}
const activeTab = ref<string>('basicInfo');
const originalTransactionEditable = ref<boolean>(false);
const noTransactionDraft = ref<boolean>(false);
const geoMenuState = ref<boolean>(false);
const removingPictureId = ref<string>('');
const lastRecognizedTransaction = ref<RecognizedTransactionResponse | null>(null);

const initOptions = ref<TransactionEditOptions | undefined>(undefined);

let resolveFunc: ((response?: TransactionEditResponse) => void) | null = null;
let rejectFunc: ((reason?: unknown) => void) | null = null;

const sourceAmountColor = computed<string | undefined>(() => {
    if (transaction.value.type === TransactionType.Expense) {
        return 'expense';
    } else if (transaction.value.type === TransactionType.Income) {
        return 'income';
    } else if (transaction.value.type === TransactionType.Transfer) {
        return 'primary';
    }

    return undefined;
});

const isTransactionModified = computed<boolean>(() => {
    if (mode.value === TransactionEditPageMode.Add) {
        return transactionsStore.isTransactionDraftModified(transaction.value, initOptions.value?.amount, initOptions.value?.categoryId, initOptions.value?.accountId, initOptions.value?.tagIds, firstVisibleAccountId.value);
    } else if (mode.value === TransactionEditPageMode.Edit) {
        return true;
    } else {
        return false;
    }
});

function open(options: TransactionEditOptions): Promise<TransactionEditResponse | undefined> {
    editorActionGeneration++;
    const serial = ++editorGeneration, identity = getTransactionStateIdentity();
    const originalLedgerId = props.ledgerId || '0';
    openedIdentity = identity;
    openedUsername = getCurrentUserInfo()?.username;
    ordinaryPending.value = null;
    ordinaryRecoveryError.value = '';
    ordinarySessionInvalid.value = false;
    ordinaryCanWrite.value = false;
    openedOrdinaryDraftRevision = undefined;
    let ordinaryDraft: TransactionDraft | null = null;
    aiReviewItemId.value = options.aiReviewItemId || ''; aiReviewEditable.value = options.aiReviewEditable === true; aiReviewLocked.value = true;
    templateSourceRaw.value = undefined; templateDestinationRaw.value = undefined; templateCreationLocked.value = false;
    openedLedgerId.value = props.ledgerId || '0';
    addByTemplateId.value = null;
    duplicateFromId.value = null;
    showState.value = true;
    activeTab.value = 'basicInfo';
    loading.value = true;
    submitting.value = false;
    submitted.value = false;
    geoLocationStatus.value = null;
    setGeoLocationByClickMap.value = false;
    originalTransactionEditable.value = false;
    noTransactionDraft.value = !!options.aiReviewItemId || options.noTransactionDraft || false;
    lastRecognizedTransaction.value = null;
    transferDirection.value = 'internal';

    initOptions.value = options;

    if (props.type === TransactionEditPageType.Transaction && !options.id && !options.aiReviewItemId) {
        try {
            assertOpenedIdentity();
            ordinaryPending.value = transactionsStore.getPendingTransactionCreate(originalLedgerId);
            if (options.recoverPendingTransaction && !ordinaryPending.value) throw new Error('原待确认请求已不存在，请刷新待处理列表；不会创建替代请求');
            if (options.recoverPendingTransaction && options.expectedRecoveryClientSessionId && ordinaryPending.value?.clientSessionId !== options.expectedRecoveryClientSessionId) throw new Error('原待确认请求已变化，请刷新待处理列表；不会打开替代请求');
            if (options.recoverTransactionDraft && ordinaryPending.value) throw new Error('本账本已有待确认原请求，请先核验原请求；草稿已保留');
            if (!ordinaryPending.value && (!noTransactionDraft.value || options.recoverTransactionDraft)) {
                ordinaryDraft = inspectUserTransactionDraft(originalLedgerId);
                if (options.recoverTransactionDraft && !ordinaryDraft) throw new Error('原草稿已不存在，请刷新待处理列表；不会创建替代草稿');
                if (options.recoverTransactionDraft && options.expectedRecoveryClientSessionId && ordinaryDraft?.clientSessionId !== options.expectedRecoveryClientSessionId) throw new Error('原草稿已变化，请刷新待处理列表');
                if (options.recoverTransactionDraft && options.expectedRecoveryDraftRevision && (!ordinaryDraft || ledgerPendingDraftRevision(ordinaryDraft) !== options.expectedRecoveryDraftRevision)) throw new Error('原草稿已变化，请刷新待处理列表');
                if (ordinaryDraft && !Transaction.ofDraft(ordinaryDraft)) throw new Error('原草稿内容无法恢复，请保留原记录后核对');
            }
        } catch (error) {
            showState.value = false;
            loading.value = false;
            return Promise.reject(error);
        }
    }

    const newTransaction = createNewTransactionModel(options.type);
    setTransactionModel(newTransaction, options, true);

    const promises: Promise<unknown>[] = [
        Promise.all([accountsStore.loadAllAccounts({ force: false, ledgerId: props.ledgerId }),
            props.type === TransactionEditPageType.Template && !options.id ? transactionTemplatesStore.loadAllTemplates({ force: false, ledgerId: props.ledgerId || '0', templateType: options.templateType || 1 }) : Promise.resolve()]),
        transactionCategoriesStore.loadAllCategories({ force: false, ledgerId: props.ledgerId }),
        isSharedTemplate.value || (isAIReview.value && openedLedgerId.value !== '0') ? Promise.resolve([]) : transactionTagsStore.loadAllTags({ force: false })
    ];
    if (props.type === TransactionEditPageType.Transaction && !options.id && !options.aiReviewItemId) promises.push(refreshOrdinaryRole());

    if (props.type === TransactionEditPageType.Transaction) {
        if (options && options.id) {
            if (options.currentTransaction) {
                setTransactionModel(options.currentTransaction, options, true);
            }

            mode.value = TransactionEditPageMode.View;
            editId.value = options.id;

            promises.push(transactionsStore.getTransaction({ transactionId: editId.value, ledgerId: props.ledgerId }));
        } else {
            mode.value = TransactionEditPageMode.Add;
            editId.value = null;

            if (options.template) {
                setTransactionModel(options.template, options, false);
                addByTemplateId.value = options.template.id;
            } else if (!options.template && ordinaryDraft && (options.recoverTransactionDraft || settingsStore.appSettings.autoSaveTransactionDraft === 'enabled' || settingsStore.appSettings.autoSaveTransactionDraft === 'confirmation')) {
                setTransactionModel(Transaction.ofDraft(ordinaryDraft), options, false);
            }

            if (!isAIReview.value && !ordinaryPending.value && !ordinaryDraft && settingsStore.appSettings.autoGetCurrentGeoLocation
                && !geoLocationStatus.value && !transaction.value.geoLocation) {
                updateGeoLocation(false);
            }
        }
    } else if (props.type === TransactionEditPageType.Template) {
        const template = TransactionTemplate.createNewTransactionTemplate(transaction.value);
        template.name = '';

        if (options && options.templateType) {
            template.templateType = options.templateType;
        }

        if (template.templateType === TemplateType.Schedule.type) {
            template.scheduledFrequencyType = options?.scheduledFrequencyType ?? ScheduledTemplateFrequencyType.Disabled.type;
            template.scheduledFrequency = options?.scheduledFrequency ?? '';
        }

        transaction.value = template;
        template.ledgerId = openedLedgerId.value;

        if (options?.name) {
            template.name = options.name;
        }

        if (options && options.id) {
            if (options.currentTemplate) {
                setTransactionModel(options.currentTemplate, options, false);
                (transaction.value as TransactionTemplate).fillFrom(options.currentTemplate);
            }

            mode.value = TransactionEditPageMode.Edit;
            editId.value = options.id;
            transaction.value.id = options.id;

            promises.push(transactionTemplatesStore.getTemplate({ templateId: editId.value, ledgerId: openedLedgerId.value }));
        } else {
            mode.value = TransactionEditPageMode.Add;
            editId.value = null;
            transaction.value.id = '';
        }
    }

    if (options.type &&
        options.type >= TransactionType.Income &&
        options.type <= TransactionType.Transfer) {
        transaction.value.type = options.type;
    }

    if (mode.value === TransactionEditPageMode.Add) {
        const restoringDraft = !options.template && ordinaryDraft && (options.recoverTransactionDraft || settingsStore.appSettings.autoSaveTransactionDraft === 'enabled' || settingsStore.appSettings.autoSaveTransactionDraft === 'confirmation');
        clientSessionId.value = ordinaryPending.value?.clientSessionId || (restoringDraft ? ordinaryDraft?.clientSessionId : undefined) || generateRandomUUID();
    }

    Promise.all(promises).then(function (responses) {
        if (serial !== editorGeneration || !showState.value || identity !== getTransactionStateIdentity()) return;
        if (openedLedgerId.value !== (props.ledgerId || '0')) {
            loading.value = false;
            rejectFunc?.({ message: '账本已切换，请重新打开表单。' });
            showState.value = false;
            return;
        }
        if (editId.value && !responses[3]) {
            if (rejectFunc) {
                if (props.type === TransactionEditPageType.Transaction) {
                    rejectFunc('Unable to retrieve transaction');
                } else if (props.type === TransactionEditPageType.Template) {
                    rejectFunc('Unable to retrieve template');
                }
            }

            return;
        }

        if (props.type === TransactionEditPageType.Transaction && options && options.id && responses[3] && responses[3] instanceof Transaction) {
            const transaction: Transaction = responses[3];
            setTransactionModel(transaction, options, true);
            originalTransactionEditable.value = transaction.editable;
        } else if (props.type === TransactionEditPageType.Template && options && options.id && responses[3] && responses[3] instanceof TransactionTemplate) {
            const template: TransactionTemplate = responses[3];
            setTransactionModel(template, options, false);

            if (!(transaction.value instanceof TransactionTemplate)) {
                transaction.value = TransactionTemplate.createNewTransactionTemplate(transaction.value);
            }

            (transaction.value as TransactionTemplate).fillFrom(template);
            (transaction.value as TransactionTemplate).ledgerId = openedLedgerId.value;
        } else {
            setTransactionModel(null, options, true);
        }

        if (isAIReview.value) {
            // Unmatched AI resources cannot inherit the editor's defaults.
            transaction.value.sourceAccountId = options.accountId === '0' ? '' : options.accountId || '';
            transaction.value.destinationAccountId = options.destinationAccountId === '0' ? '' : options.destinationAccountId || '';
            transaction.value.setCategoryId(options.categoryId || '');
            transferDirection.value = transaction.value.type === TransactionType.Transfer
                ? (options.accountId === '0' ? 'in' : options.destinationAccountId === '0' ? 'out' : 'internal') : 'internal';
        }

        if (isOrdinaryCreate.value) {
            assertOpenedIdentity();
            if (ordinaryPending.value) {
                const current = transactionsStore.getPendingTransactionCreate(originalLedgerId);
                if (!current || current.clientSessionId !== ordinaryPending.value.clientSessionId) throw new Error('原请求已变化，请关闭后刷新待处理列表');
                restoreOrdinaryPending(current);
            } else if (!options.template && ordinaryDraft && (options.recoverTransactionDraft || settingsStore.appSettings.autoSaveTransactionDraft === 'enabled' || settingsStore.appSettings.autoSaveTransactionDraft === 'confirmation')) {
                const current = inspectUserTransactionDraft(originalLedgerId);
                if (!current || ledgerPendingDraftRevision(current) !== ledgerPendingDraftRevision(ordinaryDraft)) throw new Error('原草稿已变化，请关闭后刷新待处理列表');
                transaction.value = Transaction.ofDraft(current)!;
                openedOrdinaryDraftRevision = ledgerPendingDraftRevision(current);
                transferDirection.value = transaction.value.type === TransactionType.Transfer ? transaction.value.sourceAccountId === '0' ? 'in' : transaction.value.destinationAccountId === '0' ? 'out' : 'internal' : 'internal';
            }
        }

        if (options.autoUploadPicture && !ordinaryCreateLocked.value) {
            uploadPicture(options.autoUploadPicture);
        }

        loading.value = false;

        if (!isAIReview.value && !ordinaryCreateLocked.value && mode.value === TransactionEditPageMode.Add && (isDefined(options.autoRecognizeClipboardText) || isDefined(options.autoRecognizeText))) {
            void aiSourceDialog.value?.open(openedLedgerId.value, options.autoRecognizeClipboardText ?? options.autoRecognizeText ?? '');
        }
    }).catch(error => {
        if (serial !== editorGeneration || !showState.value || originalLedgerId !== (props.ledgerId || '0') || identity !== getTransactionStateIdentity()) return;
        if (isAIReview.value) {
            loading.value = false; showState.value = false;
            rejectFunc?.(new Error('未能读取原账本的账户或分类，请重试；本机内容已保留'));
            return;
        }
        logger.error('failed to load essential data for editing transaction', error);

        loading.value = false;
        showState.value = false;

        if (!error.processed) {
            if (rejectFunc) {
                rejectFunc(error);
            }
        }
    });

    return new Promise((resolve, reject) => {
        resolveFunc = resolve;
        rejectFunc = reject;
    });
}

function save(afterAction: AfterSaveAction): void {
    if (isAIReview.value) { void aiReviewConfirmationPanel.value?.preview(); return; }
    if (openedLedgerId.value !== (props.ledgerId || '0')) {
        snackbar.value?.showError({ message: '账本已切换，当前内容不会写入另一个账本。请返回原账本或重新打开表单。' });
        return;
    }
    if (isCreateTemplate.value) { void templateCreationPanel.value?.preview(); return; }
    if (isOrdinaryCreate.value) { void saveOrdinary(afterAction); return; }
    const problemMessage = inputEmptyProblemMessage.value;

    if (problemMessage) {
        snackbar.value?.showMessage(problemMessage);
        return;
    }

    if (props.type === TransactionEditPageType.Transaction && (mode.value === TransactionEditPageMode.Add || mode.value === TransactionEditPageMode.Edit)) {
        const doSubmit = function () {
            submitting.value = true;

            transactionsStore.saveTransaction({
                transaction: transaction.value as Transaction,
                defaultCurrency: defaultCurrency.value,
                isEdit: mode.value === TransactionEditPageMode.Edit,
                clientSessionId: clientSessionId.value,
                ledgerId: props.ledgerId
            }).then(savedTransaction => {
                submitting.value = false;
                submitted.value = true;

                if (mode.value === TransactionEditPageMode.Add && !noTransactionDraft.value && !addByTemplateId.value && !duplicateFromId.value) {
                    transactionsStore.clearTransactionDraft();
                }

                if (mode.value === TransactionEditPageMode.Add && (afterAction === AfterSaveAction.StayWithNewTransaction || afterAction === AfterSaveAction.StayWithCurrentTransaction)) {
                    snackbar.value?.showMessage('You have added a new transaction');
                    updateTransactionModelByAfterSaveAction(afterAction, initOptions.value);
                    clientSessionId.value = generateRandomUUID();
                } else {
                    if (resolveFunc) {
                        if (mode.value === TransactionEditPageMode.Add) {
                            resolveFunc({
                                message: 'You have added a new transaction',
                                transactionId: savedTransaction.id
                            });
                        } else if (mode.value === TransactionEditPageMode.Edit) {
                            resolveFunc({
                                message: 'You have saved this transaction',
                                transactionId: savedTransaction.id
                            });
                        }
                    }

                    showState.value = false;
                }
            }).catch(error => {
                submitting.value = false;

                if (error.error && (error.error.errorCode === KnownErrorCode.TransactionCannotCreateInThisTime || error.error.errorCode === KnownErrorCode.TransactionCannotModifyInThisTime)) {
                    confirmDialog.value?.open('You have set this time range to prevent editing transactions. Would you like to change the editable transaction range to All?').then(() => {
                        submitting.value = true;

                        userStore.updateUserTransactionEditScope({
                            transactionEditScope: TransactionEditScopeType.All.type
                        }).then(() => {
                            submitting.value = false;

                            snackbar.value?.showMessage('Your editable transaction range has been set to All');
                        }).catch(error => {
                            submitting.value = false;

                            if (!error.processed) {
                                snackbar.value?.showError(error);
                            }
                        });
                    });
                } else if (!error.processed) {
                    snackbar.value?.showError(error);
                }
            });
        };

        if (transaction.value.sourceAmount === 0) {
            confirmDialog.value?.open('Are you sure you want to save this transaction with a zero amount?').then(() => {
                doSubmit();
            });
        } else {
            doSubmit();
        }
    } else if (props.type === TransactionEditPageType.Template && (mode.value === TransactionEditPageMode.Add || mode.value === TransactionEditPageMode.Edit)) {
        (transaction.value as TransactionTemplate).ledgerId = openedLedgerId.value;
        submitting.value = true;

        transactionTemplatesStore.saveTemplateContent({
            template: transaction.value as TransactionTemplate,
            isEdit: mode.value === TransactionEditPageMode.Edit,
            clientSessionId: clientSessionId.value
        }).then(() => {
            submitting.value = false;

            if (resolveFunc) {
                if (mode.value === TransactionEditPageMode.Add) {
                    resolveFunc({
                        message: 'You have added a new template'
                    });
                } else if (mode.value === TransactionEditPageMode.Edit) {
                    resolveFunc({
                        message: 'You have saved this template'
                    });
                }
            }

            showState.value = false;
        }).catch(error => {
            submitting.value = false;

            if (!error.processed) {
                snackbar.value?.showError(error);
            }
        });
    }
}

async function saveOrdinary(afterAction: AfterSaveAction): Promise<void> {
    if (loading.value || submitting.value || ordinarySessionInvalid.value) return;
    const serial = editorGeneration, actionSerial = editorActionGeneration, ledgerId = openedLedgerId.value;
    const actionCurrent = () => serial === editorGeneration && actionSerial === editorActionGeneration && showState.value && ledgerId === (props.ledgerId || '0');
    try {
        assertOpenedIdentity();
        const current = transactionsStore.getPendingTransactionCreate(ledgerId);
        if (current && !ordinaryPending.value) {
            restoreOrdinaryPending(current);
            ordinaryRecoveryError.value = '另一页面已有原请求，已显示原内容；请核对后明确重试';
            return;
        }
        if (ordinaryPending.value && (!current || current.clientSessionId !== ordinaryPending.value.clientSessionId)) throw new Error('原请求已变化，请关闭后刷新待处理列表');
        if (!ordinaryPending.value && inputEmptyProblemMessage.value) { snackbar.value?.showMessage(inputEmptyProblemMessage.value); return; }
        const approvedPending = ordinaryPending.value ? JSON.parse(JSON.stringify(ordinaryPending.value)) as PendingTransactionCreate : undefined;
        submitting.value = true;
        await refreshOrdinaryRole();
        if (!actionCurrent()) return;
        const pendingAfterPermission = transactionsStore.getPendingTransactionCreate(ledgerId);
        if (!approvedPending && pendingAfterPermission) {
            restoreOrdinaryPending(pendingAfterPermission);
            ordinaryRecoveryError.value = '另一页面已有原请求，已显示原内容；请核对后明确重试';
            return;
        }
        if (!ordinaryCanWrite.value && !ordinaryPending.value?.receipt) throw new Error('当前成员已失去记账权限，原请求和草稿已保留');
        if (!ordinaryPending.value && transaction.value.sourceAmount === 0) {
            const confirmed = await confirmDialog.value?.open('Are you sure you want to save this transaction with a zero amount?').then(() => true, () => false);
            if (!confirmed) return;
            assertOpenedIdentity();
            if (!actionCurrent()) return;
        }
        ordinaryRecoveryError.value = '';
        const saved = await transactionsStore.saveTransaction({ transaction: transaction.value as Transaction, defaultCurrency: defaultCurrency.value,
            isEdit: false, clientSessionId: clientSessionId.value, ledgerId, persistCreate: true, retrySubmission: approvedPending, expectedDraftRevision: openedOrdinaryDraftRevision,
            isActionCurrent: actionCurrent });
        // Save the acknowledgement in the original partition before allowing
        // another form. A late reply must not close a newly opened editor.
        assertOpenedIdentity();
        if (!actionCurrent()) return;
        await transactionsStore.acknowledgePendingTransactionCreate(ledgerId, saved.submissionClientSessionId ?? clientSessionId.value, saved.id);
        if (!actionCurrent()) return;
        ordinaryPending.value = null;
        openedOrdinaryDraftRevision = undefined;
        submitted.value = true;
        if (afterAction === AfterSaveAction.StayWithNewTransaction || afterAction === AfterSaveAction.StayWithCurrentTransaction) {
            snackbar.value?.showMessage('You have added a new transaction');
            updateTransactionModelByAfterSaveAction(afterAction, initOptions.value);
            clientSessionId.value = generateRandomUUID();
            submitted.value = false;
        } else {
            resolveFunc?.({ message: 'You have added a new transaction', transactionId: saved.id });
            showState.value = false;
        }
    } catch (error) {
        if (!actionCurrent()) return;
        const failure = error as { submission?: PendingTransactionCreate; message?: string; canceled?: boolean };
        try {
            assertOpenedIdentity();
            const current = transactionsStore.getPendingTransactionCreate(ledgerId);
            const pending = failure.submission?.receipt && (!current || current.clientSessionId === failure.submission.clientSessionId) ? failure.submission : current ?? failure.submission;
            if (pending) restoreOrdinaryPending(pending);
            ordinaryRecoveryError.value = failure.message || '未能核对原记账结果，请保留原请求后重试';
        } catch (reason) {
            ordinarySessionInvalid.value = true;
            ordinaryRecoveryError.value = reason instanceof Error ? reason.message : '无法读取原请求，请保留原记录后重新打开';
        }
    } finally {
        if (serial === editorGeneration) submitting.value = false;
    }
}

function recognizeFromClipboard(): void {
    if (recognizing.value || loading.value || submitting.value) {
        return;
    }
    if (isTransactionModified.value) { snackbar.value?.showMessage('当前手动表单有输入，请先保存或取消，再使用 AI 来源录入'); return; }

    const serial = editorGeneration, id = openedLedgerId.value;
    const clipboardText = navigator.clipboard?.readText ? navigator.clipboard.readText().catch(() => '') : Promise.resolve('');
    clipboardText.then(text => {
        if (serial === editorGeneration && showState.value && id === openedLedgerId.value) void aiSourceDialog.value?.open(id, text);
    });
}

function recognizeFromImage(): void {
    if (recognizing.value || loading.value || submitting.value) {
        return;
    }
    if (isTransactionModified.value) { snackbar.value?.showMessage('当前手动表单有输入，请先保存或取消，再使用 AI 来源录入'); return; }

    void aiSourceDialog.value?.open(openedLedgerId.value, '', 2);
}

function completeAISource(value: { ledgerId: string; id: string; deleted: boolean }): void {
    if (!showState.value || value.ledgerId !== openedLedgerId.value) return;
    showState.value = false;
    resolveFunc?.({ message: '已保存到待复核，复核确认后才入账', aiReviewItemId: value.deleted ? undefined : value.id });
}

function duplicate(withTime?: boolean, withGeoLocation?: boolean): void {
    if (props.type !== TransactionEditPageType.Transaction || mode.value !== TransactionEditPageMode.View) {
        return;
    }

    editId.value = null;
    duplicateFromId.value = transaction.value.id;
    clientSessionId.value = generateRandomUUID();
    submitted.value = false;
    activeTab.value = 'basicInfo';
    transaction.value.id = '';

    if (!withTime) {
        transaction.value.time = getCurrentUnixTime();
        transaction.value.timeZone = settingsStore.appSettings.timeZone;
        transaction.value.utcOffset = getTimezoneOffsetMinutes(transaction.value.time, transaction.value.timeZone);
    }

    if (!withGeoLocation) {
        transaction.value.removeGeoLocation();
    }

    transaction.value.clearPictures();
    mode.value = TransactionEditPageMode.Add;
}

function edit(): void {
    if (props.type !== TransactionEditPageType.Transaction || mode.value !== TransactionEditPageMode.View) {
        return;
    }

    mode.value = TransactionEditPageMode.Edit;
}

function remove(): void {
    if (props.type !== TransactionEditPageType.Transaction || mode.value !== TransactionEditPageMode.View) {
        return;
    }

    confirmDialog.value?.open('Are you sure you want to delete this transaction?').then(() => {
        submitting.value = true;

        transactionsStore.deleteTransaction({
            transaction: transaction.value as Transaction,
            defaultCurrency: defaultCurrency.value,
            ledgerId: props.ledgerId
        }).then(() => {
            if (resolveFunc) {
                resolveFunc();
            }

            submitting.value = false;
            showState.value = false;
        }).catch(error => {
            submitting.value = false;

            if (!error.processed) {
                snackbar.value?.showError(error);
            }
        });
    });
}

function cancel(): void {
    // Cancel revokes pending write authorization immediately; local draft
    // flushing can finish before the dialog itself closes.
    editorActionGeneration++;
    const serial = editorGeneration, ledgerId = openedLedgerId.value;
    const doClose = function () {
        if (props.type === TransactionEditPageType.Transaction && mode.value === TransactionEditPageMode.Add && submitted.value && resolveFunc) {
            resolveFunc({
                message: 'You have added a new transaction'
            });
        } else if (rejectFunc) {
            rejectFunc({
                canceled: true,
                recognizedTransaction: lastRecognizedTransaction.value || undefined
            } satisfies TransactionEditFailure);
        }

        showState.value = false;
    };
    if (isAIReview.value) {
        void aiReviewConfirmationPanel.value?.flush().then(doClose).catch(reason => snackbar.value?.showError({ message: reason instanceof Error ? reason.message : '本机草稿尚未保存，请重试' }));
        return;
    }
    if (isCreateTemplate.value) {
        void templateCreationPanel.value?.flush().then(doClose).catch(reason => snackbar.value?.showError({ message: reason instanceof Error ? reason.message : '本机草稿尚未保存，请重试' }));
        return;
    }

    if (isOrdinaryCreate.value) {
        if (ordinaryPending.value || ordinarySessionInvalid.value || !ordinaryCanWrite.value) { doClose(); return; }
        try { assertOpenedIdentity(); } catch { doClose(); return; }
    }

    if (props.type !== TransactionEditPageType.Transaction || mode.value !== TransactionEditPageMode.Add || noTransactionDraft.value || addByTemplateId.value || duplicateFromId.value) {
        doClose();
        return;
    }

    if (settingsStore.appSettings.autoSaveTransactionDraft === 'confirmation') {
        if (transactionsStore.isTransactionDraftModified(transaction.value, initOptions.value?.amount, initOptions.value?.categoryId, initOptions.value?.accountId, initOptions.value?.tagIds, firstVisibleAccountId.value)) {
            confirmDialog.value?.open('Do you want to save this transaction draft?').then(() => {
                if (serial !== editorGeneration || !showState.value) return;
                assertOpenedIdentity();
                if (transactionsStore.getPendingTransactionCreate(ledgerId)) { doClose(); return; }
                transactionsStore.saveTransactionDraft(transaction.value, initOptions.value?.amount, initOptions.value?.categoryId, initOptions.value?.accountId, initOptions.value?.tagIds, firstVisibleAccountId.value, ledgerId, clientSessionId.value);
                doClose();
            }, () => {
                if (serial !== editorGeneration || !showState.value) return;
                assertOpenedIdentity();
                if (transactionsStore.getPendingTransactionCreate(ledgerId)) { doClose(); return; }
                transactionsStore.clearTransactionDraft(ledgerId);
                doClose();
            }).catch(reason => snackbar.value?.showError({ message: reason instanceof Error ? reason.message : '本机草稿未能保存，原内容已保留' }));
        } else {
            try {
                assertOpenedIdentity();
                if (!transactionsStore.getPendingTransactionCreate(ledgerId)) transactionsStore.clearTransactionDraft(ledgerId);
                doClose();
            } catch (reason) { snackbar.value?.showError({ message: reason instanceof Error ? reason.message : '原内容已保留，请重试' }); }
        }
    } else if (settingsStore.appSettings.autoSaveTransactionDraft === 'enabled') {
        try {
            assertOpenedIdentity();
            if (!transactionsStore.getPendingTransactionCreate(ledgerId)) transactionsStore.saveTransactionDraft(transaction.value, initOptions.value?.amount, initOptions.value?.categoryId, initOptions.value?.accountId, initOptions.value?.tagIds, firstVisibleAccountId.value, ledgerId, clientSessionId.value);
            doClose();
        } catch (reason) { snackbar.value?.showError({ message: reason instanceof Error ? reason.message : '本机草稿未能保存，原内容已保留' }); }
    } else {
        doClose();
    }
}

function updateGeoLocation(forceUpdate: boolean): void {
    geoMenuState.value = false;

    if (!isSupportGeoLocation) {
        logger.warn('this browser does not support geo location');

        if (forceUpdate) {
            snackbar.value?.showMessage('Unable to retrieve current position');
        }
        return;
    }

    navigator.geolocation.getCurrentPosition(function (position) {
        if (!position || !position.coords) {
            logger.error('current position is null');
            geoLocationStatus.value = GeoLocationStatus.Error;

            if (forceUpdate) {
                snackbar.value?.showMessage('Unable to retrieve current position');
            }

            return;
        }

        geoLocationStatus.value = GeoLocationStatus.Success;

        transaction.value.setLatitudeAndLongitude(position.coords.latitude, position.coords.longitude);
    }, function (err) {
        logger.error('cannot retrieve current position', err);
        geoLocationStatus.value = GeoLocationStatus.Error;

        if (forceUpdate) {
            snackbar.value?.showMessage('Unable to retrieve current position');
        }
    });

    geoLocationStatus.value = GeoLocationStatus.Getting;
}

function updateSpecifiedGeoLocation(coordinate: Coordinate): void {
    if (isSupportGetGeoLocationByClick() && setGeoLocationByClickMap.value) {
        transaction.value.setLatitudeAndLongitude(coordinate.latitude, coordinate.longitude);
        map.value?.setMarkerPosition(transaction.value.geoLocation);
    }
}

function clearGeoLocation(): void {
    geoMenuState.value = false;
    geoLocationStatus.value = null;
    transaction.value.removeGeoLocation();
}

function showOpenPictureDialog(): void {
    if (!canAddTransactionPicture.value || submitting.value) {
        return;
    }

    pictureInput.value?.click();
}

function uploadPicture(file: File): void {
    if (!file) {
        return;
    }

    uploadingPicture.value = true;
    submitting.value = true;

    compressJpgImageByQuality(file, imageUploadQualityType.value).then(blob => {
        return transactionsStore.uploadTransactionPicture({
            pictureFile: KnownFileType.JPG.createFileFromBlob(blob, "image")
        });
    }).then(response => {
        transaction.value.addPicture(response);
        uploadingPicture.value = false;
        submitting.value = false;
    }).catch(error => {
        uploadingPicture.value = false;
        submitting.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function viewOrRemovePicture(pictureInfo: TransactionPictureInfoBasicResponse): void {
    if (mode.value !== TransactionEditPageMode.Add && mode.value !== TransactionEditPageMode.Edit) {
        window.open(getTransactionPictureUrl(pictureInfo), '_blank');
        return;
    }

    confirmDialog.value?.open('Are you sure you want to remove this transaction picture?').then(() => {
        removingPictureId.value = pictureInfo.pictureId;
        submitting.value = true;

        transactionsStore.removeUnusedTransactionPicture({ pictureInfo }).then(response => {
            if (response) {
                transaction.value.removePicture(pictureInfo);
            }

            removingPictureId.value = '';
            submitting.value = false;
        }).catch(error => {
            if (error.error && error.error.errorCode === KnownErrorCode.TransactionPictureNotFound) {
                transaction.value.removePicture(pictureInfo);
            } else if (!error.processed) {
                snackbar.value?.showError(error);
            }

            removingPictureId.value = '';
            submitting.value = false;
        });
    });
}

function onSavingTag(state: boolean): void {
    submitting.value = state;
}

function onUploadPicture(event: Event): void {
    if (!event || !event.target) {
        return;
    }

    const el = event.target as HTMLInputElement;

    if (!el.files || !el.files.length || !el.files[0]) {
        return;
    }

    const pictureFile = el.files[0] as File;

    el.value = '';
    uploadPicture(pictureFile);
}

function onShowDateTimeError(error: string): void {
    snackbar.value?.showError(error);
}

watch(activeTab, (newValue) => {
    if (newValue === 'map') {
        nextTick(() => {
            map.value?.initMapView();
        });
    }
});

watch(() => props.ledgerId, inspectOrdinarySession);
onMounted(() => {
    window.addEventListener('storage', inspectOrdinarySession);
    window.addEventListener('focus', inspectOrdinarySession);
    document.addEventListener('visibilitychange', inspectOrdinarySession);
});
onUnmounted(() => {
    editorGeneration++;
    editorActionGeneration++;
    window.removeEventListener('storage', inspectOrdinarySession);
    window.removeEventListener('focus', inspectOrdinarySession);
    document.removeEventListener('visibilitychange', inspectOrdinarySession);
});

defineExpose({
    open
});
</script>

<style>
.transaction-edit-dialog .ordinary-create-recovery {
    flex: 0 0 auto;
    max-height: 40dvh;
    overflow-y: auto;
    border-bottom: 1px solid rgba(var(--v-border-color), .14);
}
.ordinary-create-recovery p { margin-bottom: 10px; }
.ordinary-create-recovery .v-btn { min-height: 44px; }
.transaction-edit-dialog .ai-review-confirmation-container {
    flex: 0 0 auto !important;
    max-height: 45dvh;
    overflow-y: auto;
}

.transfer-direction {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    padding: 5px;
    border-radius: 14px;
    background: rgb(var(--v-theme-surface-variant));
}

.transfer-direction button {
    min-height: 52px;
    padding: 7px 10px;
    border: 1px solid transparent;
    border-radius: 10px;
    color: rgb(var(--v-theme-on-surface));
    background: transparent;
}

.transfer-direction button b,
.transfer-direction button small {
    display: block;
}

.transfer-direction button small {
    margin-top: 2px;
    opacity: 0.72;
}

.transfer-direction button.active {
    border-color: rgba(var(--v-theme-primary), 0.35);
    background: rgb(var(--v-theme-surface));
    box-shadow: 0 2px 8px rgba(18, 20, 26, 0.08);
}

.transaction-edit-dialog .v-overlay__content {
    max-height: min(760px, calc(100dvh - 24px)) !important;
    margin: 12px;
}

.transaction-edit-card {
    display: flex;
    max-height: min(760px, calc(100dvh - 24px));
    overflow: hidden;
    border: 1px solid rgba(var(--v-border-color), .14);
}

.transaction-edit-card > .v-card-item {
    flex: 0 0 auto;
    padding-block: 16px 12px;
    border-bottom: 1px solid rgba(var(--v-border-color), .1);
}

.transaction-ai-image-action {
    min-height: 44px;
    white-space: nowrap;
}

.transaction-picture-note {
    min-height: 64px;
    padding: 12px 14px;
    border: 1px solid rgba(var(--v-border-color), .14);
    border-radius: 12px;
    background: rgba(var(--v-theme-surface-variant), .42);
}

.transaction-edit-body {
    min-height: 0;
    padding-top: 14px !important;
    padding-bottom: 10px !important;
    overscroll-behavior: contain;
    scrollbar-gutter: stable;
}

.transaction-edit-body .v-row {
    margin: -6px;
}

.transaction-edit-body .v-col-12 {
    padding: 6px;
}

.transaction-edit-actions {
    position: relative;
    z-index: 2;
    flex: 0 0 auto;
    padding-block: 12px !important;
    border-top: 1px solid rgba(var(--v-border-color), .12);
    background: rgba(var(--v-theme-surface), .94);
    box-shadow: 0 -12px 28px rgba(31, 27, 61, .06);
    backdrop-filter: blur(18px);
}

.transaction-edit-amount .v-field__prepend-inner,
.transaction-edit-amount .v-field__append-inner,
.transaction-edit-amount .v-field__field > input {
    font-size: 1.25rem;
}

.transaction-edit-timezone.v-input input::placeholder {
    color: rgba(var(--v-theme-on-background), var(--v-high-emphasis-opacity)) !important;
    opacity: unset;
}

.transaction-edit-map-view {
    height: 220px;
}

@media (min-height: 630px) {
    .transaction-edit-map-view {
        height: 390px;
    }

    @media (min-width: 960px) {
        .transaction-pictures {
            min-height: 414px;
        }
    }
}

@media (min-height: 700px) {
    .transaction-edit-map-view {
        height: 460px;
    }

    @media (min-width: 960px) {
        .transaction-pictures {
            min-height: 484px;
        }
    }
}

@media (min-height: 780px) {
    .transaction-edit-map-view {
        height: 537px;
    }

    @media (min-width: 960px) {
        .transaction-pictures {
            min-height: 561px;
        }
    }
}

.transaction-picture .picture-control-icon {
    display: none;
    position: absolute;
    width: 100% !important;
    height: 100% !important;
    background-color: rgba(0, 0, 0, 0.4);
}

.transaction-picture .picture-control-icon > i.v-icon {
    background-color: transparent;
    color: rgba(255, 255, 255, 0.8);
}

.transaction-picture:hover .picture-control-icon,
.transaction-picture .picture-control-icon.show-control-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    vertical-align: middle;
}

.transaction-picture:hover .transaction-picture-placeholder {
    display: none;
}

.transaction-picture-add {
    display: inline-flex;
    width: 160px;
    height: 160px;
    align-items: center;
    justify-content: center;
    padding: 16px;
    border: 2px dashed rgba(var(--v-theme-grey-500));
    border-radius: 12px;
    color: rgb(var(--v-theme-on-surface));
    background: transparent;
    cursor: pointer;

    .transaction-picture-add-icon {
        color: rgba(var(--v-theme-grey-500));
    }
}

.transaction-picture-add:hover:not(:disabled),
.transaction-picture-add:focus-visible {
    border: 2px dashed rgba(var(--v-theme-grey-700));
    background: rgba(var(--v-theme-primary), .05);
    outline: 3px solid rgba(var(--v-theme-primary), .18);
    outline-offset: 2px;

    .transaction-picture-add-icon {
        color: rgba(var(--v-theme-grey-700));
    }
}

.transaction-picture-add:disabled {
    cursor: default;
    opacity: .55;
}

@media (max-width: 959px) {
    .transaction-edit-title {
        flex-wrap: wrap;
        row-gap: 8px;
    }

    .transaction-ai-image-action {
        order: 3;
    }

    .transaction-edit-dialog .v-overlay__content,
    .transaction-edit-card {
        max-height: calc(100dvh - 16px) !important;
    }

    .transaction-edit-body {
        padding-inline: 14px !important;
    }
}
</style>
