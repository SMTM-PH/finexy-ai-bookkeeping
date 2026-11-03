<template>
    <f7-page class="mobile-transaction-edit-page" @page:afterin="onPageAfterIn" @page:beforeout="onPageBeforeOut">
        <f7-navbar class="mobile-transaction-edit-navbar">
            <f7-nav-left :class="{ 'disabled': loading }" :back-link="tt('Back')"></f7-nav-left>
            <f7-nav-title :title="isAIReview ? 'AI 条目复核' : tt(title)"></f7-nav-title>
            <f7-nav-right :class="{ 'navbar-compact-icons': true, 'disabled': loading || submitting }" v-if="canWriteLedger && !pendingCreate && (!editingLocked || mode === TransactionEditPageMode.View) && (mode !== TransactionEditPageMode.View || transaction.type !== TransactionType.ModifyBalance)">
                <f7-link icon-f7="ellipsis" aria-label="更多流水操作" @click="showMoreActionSheet = true"></f7-link>
                <f7-link icon-f7="checkmark_alt" :aria-label="isAIReview ? '预览 AI 入账' : isCreateTemplate ? '预览模板' : '保存流水'" :class="{ 'disabled': inputIsEmpty || submitting || recognizing }" @click="save(AfterSaveAction.GoBack)" v-if="mode !== TransactionEditPageMode.View"></f7-link>
            </f7-nav-right>
        </f7-navbar>

        <ledger-context-notice :limited="!isDefaultLedger" />
        <f7-block v-if="isAIReview && !ledgerContextChanged" class="mobile-ai-confirmation">
            <p>{{ aiReviewSourceText }} · AI 猜测，请核对实际账户、分类与币种。</p>
            <AIReviewConfirmationPanel ref="aiReviewConfirmationPanel" :active="aiPageActive && !ledgerContextChanged" :ready="!loading && !loadingError"
                :ledger-id="editLedgerId" :item-id="aiReviewId" :transaction="transaction" :can-write="canWriteLedger && aiReviewEditable"
                :raw-source-amount="templateSourceRaw" :raw-destination-amount="templateDestinationRaw" :transfer-direction="transferDirection"
                :validate="() => inputEmptyProblemMessage ? tt(inputEmptyProblemMessage) : getSelectionProblem(transaction)"
                @restore="restoreAIReviewInput" @restore-request="restoreAIReviewRequest" @locked="aiReviewLocked = $event" @complete="completeAIReview" />
            <button v-if="canWriteLedger && aiReviewEditable && !aiReviewLocked && !loading" type="button" class="mobile-ai-preview" @click="save(AfterSaveAction.GoBack)">预览并确认</button>
        </f7-block>
        <f7-block v-if="isCreateTemplate && !ledgerContextChanged">
            <template-creation-panel ref="templateCreationPanel" :active="templatePageActive && isCreateTemplate && !ledgerContextChanged" :ready="!loading && !loadingError"
                :capture-action-current="capturePageActionCurrent"
                :ledger-id="editLedgerId" :template="transaction" :can-manage="canWriteLedger" :raw-source-amount="templateSourceRaw" :raw-destination-amount="templateDestinationRaw"
                :expected-recovery-target="pendingTarget?.kind === 'template-create' ? pendingTarget : undefined"
                :validate="() => inputEmptyProblemMessage ? tt(inputEmptyProblemMessage) : getSelectionProblem(transaction)"
                @restore="restoreTemplateInput" @locked="templateCreationLocked = $event" @complete="completeTemplateCreation" />
        </f7-block>
        <f7-block v-if="ledgerContextChanged" role="alert">账本或账号已切换，请返回当前账本重新打开；原输入不会迁写。</f7-block>
        <f7-block v-else-if="loadingError" role="alert">{{ loadingError instanceof Error ? loadingError.message : '当前账本流水加载失败。' }} <f7-link @click="init(true)">重试</f7-link></f7-block>
        <f7-block v-if="saveError" role="alert">{{ saveError }}</f7-block>
        <f7-block v-if="!loading && !canWriteLedger && mode !== TransactionEditPageMode.View" role="alert">{{ pageTypeAndMode?.type === TransactionEditPageType.Template ? '只有账本所有者或管理员可以保存模板。' : '当前成员仅可查看，不能保存流水。' }}</f7-block>
        <f7-block v-if="!loading && !isDefaultLedger && canWriteLedger && !canUseOwnerResources">{{ pageTypeAndMode?.type === TransactionEditPageType.Template ? '共享模板保留原标签关联，此表单不修改标签或引入私人标签。' : '标签和附件属于账本所有者。你可以新增基础流水、修改或删除自己记录且可编辑的流水；编辑时原标签与附件保持不变。' }}</f7-block>
        <f7-block v-if="pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type">到期后待确认，确认才入账。执行时刻为模板时区午夜；停用频率暂停未来到期，隐藏不会暂停。</f7-block>
        <f7-block v-if="pendingCreate && !ledgerContextChanged" role="status">
            <p>{{ pendingCreate.receipt ? '原记账已返回创建回执，只重新读取原流水，不会再次新增。' : '这笔记账结果待确认，已保留原请求。重试会核对同一笔流水，请勿另建一笔。' }}</p>
            <p>{{ transactionDisplayDate }} · {{ transferDirection === 'in' && transaction.type === TransactionType.Transfer ? destinationAccountName : sourceAccountName }} · {{ getDisplayAmount(transaction.type === TransactionType.Transfer && transferDirection === 'in' ? transaction.destinationAmount : transaction.sourceAmount, transaction.hideAmount, transaction.type === TransactionType.Transfer && transferDirection === 'in' ? destinationAccountCurrency : sourceAccountCurrency) }}</p>
            <p v-if="transaction.type === TransactionType.Transfer && transferDirection === 'internal'">转入 {{ destinationAccountName }} · {{ getDisplayAmount(transaction.destinationAmount, transaction.hideAmount, destinationAccountCurrency) }}</p>
            <p v-if="transaction.comment">{{ transaction.comment }}</p>
            <f7-button fill :disabled="loading || submitting || (!canWriteLedger && !canReadPendingReceipt)" @click="save(AfterSaveAction.GoBack)">{{ pendingCreate.receipt ? '重新读取原结果' : '重试原请求' }}</f7-button>
        </f7-block>
        <template v-if="!ledgerContextChanged && !loadingError">
        <div :inert="(isAIReview && editingLocked) || (editingLocked && mode !== TransactionEditPageMode.View)">
        <f7-block v-if="!loading && !pendingCreate && mode !== TransactionEditPageMode.View && inputEmptyProblemMessage" role="alert">{{ tt(inputEmptyProblemMessage) }}</f7-block>
        <f7-block :class="{ 'subnav-segmented-bar': true, 'disabled': loading }">
            <f7-segmented strong round :class="{ 'readonly': pageTypeAndMode?.type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit }">
                <f7-button round :text="tt('Expense')" :active="transaction.type === TransactionType.Expense"
                           :disabled="pageTypeAndMode?.type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && transaction.type !== TransactionType.Expense"
                           v-if="transaction.type !== TransactionType.ModifyBalance"
                           @click="transaction.type = TransactionType.Expense"></f7-button>
                <f7-button round :text="tt('Income')" :active="transaction.type === TransactionType.Income"
                           :disabled="pageTypeAndMode?.type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && transaction.type !== TransactionType.Income"
                           v-if="transaction.type !== TransactionType.ModifyBalance"
                           @click="transaction.type = TransactionType.Income"></f7-button>
                <f7-button round :text="tt('Transfer')" :active="transaction.type === TransactionType.Transfer"
                           :disabled="pageTypeAndMode?.type === TransactionEditPageType.Transaction && mode !== TransactionEditPageMode.Add && mode !== TransactionEditPageMode.Edit && transaction.type !== TransactionType.Transfer"
                           v-if="transaction.type !== TransactionType.ModifyBalance"
                           @click="transaction.type = TransactionType.Transfer"></f7-button>
                <f7-button round :text="tt('Modify Balance')" :active="transaction.type === TransactionType.ModifyBalance"
                           v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction && transaction.type === TransactionType.ModifyBalance"></f7-button>
            </f7-segmented>
        </f7-block>

        <f7-block v-if="transaction.type === TransactionType.Transfer && !loading && pageTypeAndMode?.type === TransactionEditPageType.Transaction" class="margin-vertical-half">
            <f7-segmented strong class="mobile-transaction-direction" :class="{ readonly: mode === TransactionEditPageMode.View }">
                <f7-button :active="transferDirection === 'internal'" @click="setTransferDirection('internal')">账户间转账</f7-button>
                <f7-button :active="transferDirection === 'out'" @click="setTransferDirection('out')">转出</f7-button>
                <f7-button :active="transferDirection === 'in'" @click="setTransferDirection('in')">转入</f7-button>
            </f7-segmented>
            <p v-if="transferDirection === 'out'">转给系统外的人，只记录来源账户。</p>
            <p v-else-if="transferDirection === 'in'">从系统外收到资金，只记录目标账户。</p>
        </f7-block>

        <f7-list strong inset dividers class="margin-vertical-half skeleton-text" v-if="loading">
            <f7-list-input label="Template Name" placeholder="Template Name" v-if="pageTypeAndMode?.type === TransactionEditPageType.Template"></f7-list-input>
            <f7-list-item
                class="transaction-edit-amount ebk-large-amount"
                header="Expense Amount" title="0.00">
            </f7-list-item>
            <f7-list-item
                class="transaction-edit-amount ebk-large-amount"
                header="Transfer In Amount" title="0.00" v-if="transaction.type === TransactionType.Transfer">
            </f7-list-item>
            <f7-list-item class="list-item-with-header-and-title list-item-title-hide-overflow" header="Category" title="Category Names" v-if="transaction.type !== TransactionType.ModifyBalance"></f7-list-item>
            <f7-list-item class="list-item-with-header-and-title" header="Account" title="Account Name"></f7-list-item>
            <f7-list-item class="list-item-with-header-and-title" header="Destination Account" title="Account Name" v-if="transaction.type === TransactionType.Transfer"></f7-list-item>
            <f7-list-item class="list-item-with-header-and-title" header="Transaction Time" title="YYYY/MM/DD HH:mm:ss" v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction"></f7-list-item>
            <f7-list-item class="list-item-with-header-and-title" header="Scheduled Transaction Frequency" title="Every XXXXX" v-if="pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type"></f7-list-item>
            <f7-list-item class="list-item-with-header-and-title list-item-title-hide-overflow list-item-no-item-after" header="Transaction Timezone" title="(UTC XX:XX) System Default" link="#" :no-chevron="mode === TransactionEditPageMode.View" v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction || (pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type)"></f7-list-item>
            <f7-list-item class="list-item-with-header-and-title list-item-title-hide-overflow" header="Geographic Location" title="No Location" v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction"></f7-list-item>
            <f7-list-item v-if="isDefaultLedger" header="Tags">
                <template #footer>
                    <f7-block class="margin-top-half no-padding no-margin">
                        <f7-chip class="transaction-edit-tag" text="None"></f7-chip>
                    </f7-block>
                </template>
            </f7-list-item>
            <f7-list-input type="textarea" label="Description" placeholder="Your transaction description (optional)"></f7-list-input>
        </f7-list>

        <f7-list form strong inset dividers class="margin-vertical-half" v-else-if="!loading">
            <f7-list-input
                type="text"
                clear-button
                :label="tt('Template Name')"
                :placeholder="tt('Template Name')"
                v-model:value="transaction.name"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate"
            ></f7-list-input>

            <f7-list-item
                class="transaction-edit-amount"
                link="#" no-chevron
                :class="sourceAmountClass"
                :header="sourceAmountTitle"
                :title="getDisplayAmount(transaction.sourceAmount, transaction.hideAmount, sourceAccountCurrency)"
                @click="showSourceAmountSheet = true" v-if="transaction.type !== TransactionType.Transfer || transferDirection !== 'in'"
            >
                <number-pad-sheet :min-value="TRANSACTION_MIN_AMOUNT"
                                  :max-value="TRANSACTION_MAX_AMOUNT"
                                  :currency="sourceAccountCurrency"
                                  :raw-value="isCreateTemplate || isAIReview ? templateSourceRaw : undefined" @update:raw-value="(value: string) => { if (isCreateTemplate || isAIReview) templateSourceRaw = value; }"
                                  v-model:show="showSourceAmountSheet"
                                  v-model="transaction.sourceAmount"
                ></number-pad-sheet>
            </f7-list-item>

            <f7-list-item
                class="transaction-edit-amount text-color-primary"
                link="#" no-chevron
                :class="destinationAmountClass"
                :header="transferInAmountTitle"
                :title="getDisplayAmount(transaction.destinationAmount, transaction.hideAmount, destinationAccountCurrency)"
                @click="showDestinationAmountSheet = true"
                v-if="transaction.type === TransactionType.Transfer && transferDirection !== 'out'"
            >
                <number-pad-sheet :min-value="TRANSACTION_MIN_AMOUNT"
                                  :max-value="TRANSACTION_MAX_AMOUNT"
                                  :currency="destinationAccountCurrency"
                                  :raw-value="isCreateTemplate || isAIReview ? templateDestinationRaw : undefined" @update:raw-value="(value: string) => { if (isCreateTemplate || isAIReview) templateDestinationRaw = value; }"
                                  v-model:show="showDestinationAmountSheet"
                                  v-model="transaction.destinationAmount"
                ></number-pad-sheet>
            </f7-list-item>

            <f7-list-item
                class="list-item-with-header-and-title list-item-title-hide-overflow"
                key="expenseCategorySelection"
                link="#" no-chevron
                :class="{ 'disabled': !hasVisibleExpenseCategories, 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Category')"
                @click="showCategorySheet = true"
                v-if="transaction.type === TransactionType.Expense"
            >
                <template #title>
                    <div class="list-item-custom-title" v-if="hasVisibleExpenseCategories">
                        <span>{{ getTransactionPrimaryCategoryName(transaction.expenseCategoryId, allCategories[CategoryType.Expense]) }}</span>
                        <f7-icon class="category-separate-icon icon-with-direction" f7="chevron_right"></f7-icon>
                        <span>{{ getTransactionSecondaryCategoryName(transaction.expenseCategoryId, allCategories[CategoryType.Expense]) }}</span>
                    </div>
                    <div class="list-item-custom-title" v-else-if="!hasVisibleExpenseCategories">
                        <span>{{ tt('None') }}</span>
                    </div>
                </template>
                <tree-view-selection-sheet primary-key-field="id" primary-title-field="name"
                                           primary-icon-field="icon" primary-icon-type="category" primary-color-field="color"
                                           primary-hidden-field="hidden" primary-sub-items-field="subCategories"
                                           secondary-key-field="id" secondary-value-field="id" secondary-title-field="name"
                                           secondary-icon-field="icon" secondary-icon-type="category" secondary-color-field="color"
                                           secondary-hidden-field="hidden"
                                           :enable-filter="true" :filter-placeholder="tt('Find category')" :filter-no-items-text="tt('No available category')"
                                           :items="allCategories[CategoryType.Expense]"
                                           v-model:show="showCategorySheet"
                                           v-model="transaction.expenseCategoryId">
                </tree-view-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="list-item-with-header-and-title list-item-title-hide-overflow"
                key="incomeCategorySelection"
                link="#" no-chevron
                :class="{ 'disabled': !hasVisibleIncomeCategories, 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Category')"
                @click="showCategorySheet = true"
                v-if="transaction.type === TransactionType.Income"
            >
                <template #title>
                    <div class="list-item-custom-title" v-if="hasVisibleIncomeCategories">
                        <span>{{ getTransactionPrimaryCategoryName(transaction.incomeCategoryId, allCategories[CategoryType.Income]) }}</span>
                        <f7-icon class="category-separate-icon icon-with-direction" f7="chevron_right"></f7-icon>
                        <span>{{ getTransactionSecondaryCategoryName(transaction.incomeCategoryId, allCategories[CategoryType.Income]) }}</span>
                    </div>
                    <div class="list-item-custom-title" v-else-if="!hasVisibleIncomeCategories">
                        <span>{{ tt('None') }}</span>
                    </div>
                </template>
                <tree-view-selection-sheet primary-key-field="id" primary-title-field="name"
                                           primary-icon-field="icon" primary-icon-type="category" primary-color-field="color"
                                           primary-hidden-field="hidden" primary-sub-items-field="subCategories"
                                           secondary-key-field="id" secondary-value-field="id" secondary-title-field="name"
                                           secondary-icon-field="icon" secondary-icon-type="category" secondary-color-field="color"
                                           secondary-hidden-field="hidden"
                                           :enable-filter="true" :filter-placeholder="tt('Find category')" :filter-no-items-text="tt('No available category')"
                                           :items="allCategories[CategoryType.Income]"
                                           v-model:show="showCategorySheet"
                                           v-model="transaction.incomeCategoryId">
                </tree-view-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="list-item-with-header-and-title list-item-title-hide-overflow"
                key="transferCategorySelection"
                link="#" no-chevron
                :class="{ 'disabled': !hasVisibleTransferCategories, 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Category')"
                @click="showCategorySheet = true"
                v-if="transaction.type === TransactionType.Transfer"
            >
                <template #title>
                    <div class="list-item-custom-title" v-if="hasVisibleTransferCategories">
                        <span>{{ getTransactionPrimaryCategoryName(transaction.transferCategoryId, allCategories[CategoryType.Transfer]) }}</span>
                        <f7-icon class="category-separate-icon icon-with-direction" f7="chevron_right"></f7-icon>
                        <span>{{ getTransactionSecondaryCategoryName(transaction.transferCategoryId, allCategories[CategoryType.Transfer]) }}</span>
                    </div>
                    <div class="list-item-custom-title" v-else-if="!hasVisibleTransferCategories">
                        <span>{{ tt('None') }}</span>
                    </div>
                </template>
                <tree-view-selection-sheet primary-key-field="id" primary-title-field="name"
                                           primary-icon-field="icon" primary-icon-type="category" primary-color-field="color"
                                           primary-hidden-field="hidden" primary-sub-items-field="subCategories"
                                           secondary-key-field="id" secondary-value-field="id" secondary-title-field="name"
                                           secondary-icon-field="icon" secondary-icon-type="category" secondary-color-field="color"
                                           secondary-hidden-field="hidden"
                                           :enable-filter="true" :filter-placeholder="tt('Find category')" :filter-no-items-text="tt('No available category')"
                                           :items="allCategories[CategoryType.Transfer]"
                                           v-model:show="showCategorySheet"
                                           v-model="transaction.transferCategoryId">
                </tree-view-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="list-item-with-header-and-title"
                link="#" no-chevron
                :class="{ 'disabled': !allVisibleAccounts.length || (mode === TransactionEditPageMode.Edit && transaction.type === TransactionType.ModifyBalance), 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt(sourceAccountTitle)"
                :title="sourceAccountName"
                @click="showSourceAccountSheet = true" v-if="transaction.type !== TransactionType.Transfer || transferDirection !== 'in'"
            >
                <two-column-list-item-selection-sheet primary-key-field="id" primary-value-field="category"
                                                      primary-title-field="name" primary-footer-field="displayBalance"
                                                      primary-icon-field="icon" primary-icon-type="account"
                                                      primary-sub-items-field="accounts"
                                                      :primary-title-i18n="true"
                                                      secondary-key-field="id" secondary-value-field="id"
                                                      secondary-title-field="name" secondary-footer-field="displayBalance"
                                                      secondary-icon-field="icon" secondary-icon-type="account" secondary-color-field="color"
                                                      :enable-filter="true" :filter-placeholder="tt('Find account')" :filter-no-items-text="tt('No available account')"
                                                      :items="allVisibleCategorizedAccounts"
                                                      v-model:show="showSourceAccountSheet"
                                                      v-model="transaction.sourceAccountId">
                </two-column-list-item-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="list-item-with-header-and-title"
                link="#" no-chevron
                :class="{ 'disabled': !allVisibleAccounts.length, 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Destination Account')"
                :title="destinationAccountName"
                v-if="transaction.type === TransactionType.Transfer && transferDirection !== 'out'"
                @click="showDestinationAccountSheet = true"
            >
                <two-column-list-item-selection-sheet primary-key-field="id" primary-value-field="category"
                                                      primary-title-field="name" primary-footer-field="displayBalance"
                                                      primary-icon-field="icon" primary-icon-type="account"
                                                      primary-sub-items-field="accounts"
                                                      :primary-title-i18n="true"
                                                      secondary-key-field="id" secondary-value-field="id"
                                                      secondary-title-field="name" secondary-footer-field="displayBalance"
                                                      secondary-icon-field="icon" secondary-icon-type="account" secondary-color-field="color"
                                                      :enable-filter="true" :filter-placeholder="tt('Find account')" :filter-no-items-text="tt('No available account')"
                                                      :items="allVisibleCategorizedAccounts"
                                                      v-model:show="showDestinationAccountSheet"
                                                      v-model="transaction.destinationAccountId">
                </two-column-list-item-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="transaction-edit-datetime list-item-with-header-and-title"
                link="#" no-chevron
                :class="{ 'disabled': mode === TransactionEditPageMode.Edit && transaction.type === TransactionType.ModifyBalance, 'readonly': mode === TransactionEditPageMode.View && transaction.utcOffset === currentTimezoneOffsetMinutes }"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction"
            >
                <template #header>
                    <div class="transaction-edit-datetime-header" @click="showDateTimeDialog('time')">{{ tt('Transaction Time') }}</div>
                </template>
                <template #title>
                    <div class="transaction-edit-datetime-title">
                        <div @click="showDateTimeDialog('date')">{{ transactionDisplayDate }}</div>&nbsp;<div class="transaction-edit-datetime-time" @click="showDateTimeDialog('time')">{{ transactionDisplayTime }}</div>
                    </div>
                </template>
                <date-time-selection-sheet :init-mode="transactionDateTimeSheetMode"
                                           :timezone-utc-offset="transaction.utcOffset"
                                           :model-value="transaction.time"
                                           v-model:show="showTransactionDateTimeSheet"
                                           @update:model-value="updateTransactionTime">
                </date-time-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="list-item-with-header-and-title"
                link="#" no-chevron
                :class="{ 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Scheduled Transaction Frequency')"
                :title="transactionDisplayScheduledFrequency"
                @click="showTransactionScheduledFrequencySheet = true"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type"
            >
                <schedule-frequency-sheet v-model:show="showTransactionScheduledFrequencySheet"
                                          v-model:type="transaction.scheduledFrequencyType"
                                          v-model="transaction.scheduledFrequency">
                </schedule-frequency-sheet>
            </f7-list-item>

            <f7-list-item
                class="transaction-edit-datetime list-item-with-header-and-title"
                link="#" no-chevron
                :class="{ 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Start Date')"
                :title="transactionDisplayScheduledStartDate"
                @click="showScheduledStartDateSheet = true"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type"
            >
                <date-selection-sheet v-model:show="showScheduledStartDateSheet"
                                      v-model="transaction.scheduledStartDate">
                </date-selection-sheet>
            </f7-list-item>

            <f7-list-item
                class="transaction-edit-datetime list-item-with-header-and-title"
                link="#" no-chevron
                :class="{ 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('End Date')"
                :title="transactionDisplayScheduledEndDate"
                @click="showScheduledEndDateSheet = true"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type"
            >
                <date-selection-sheet v-model:show="showScheduledEndDateSheet"
                                      v-model="transaction.scheduledEndDate">
                </date-selection-sheet>
            </f7-list-item>

            <f7-list-item
                :no-chevron="mode === TransactionEditPageMode.View"
                link="#"
                class="list-item-with-header-and-title list-item-title-hide-overflow list-item-no-item-after"
                :class="{ 'disabled': mode === TransactionEditPageMode.Edit && transaction.type === TransactionType.ModifyBalance, 'readonly': mode === TransactionEditPageMode.View }"
                :header="tt('Transaction Timezone')"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction || (pageTypeAndMode?.type === TransactionEditPageType.Template && transaction instanceof TransactionTemplate && transaction.templateType === TemplateType.Schedule.type)"
                @click="showTimezonePopup = true"
            >
                <template #title>
                    <f7-block class="list-item-custom-title no-padding no-margin">
                        <span>{{ `(${transactionDisplayTimezone})` }}</span>
                        <span class="transaction-edit-timezone-name" v-if="transaction.timeZone || transaction.timeZone === ''">{{ transactionDisplayTimezoneName }}</span>
                        <span class="transaction-edit-timezone-name" v-else-if="!transaction.timeZone && transaction.timeZone !== ''">{{ transactionTimezoneTimeDifference }}</span>
                    </f7-block>
                </template>
                <list-item-selection-popup value-type="item"
                                           key-field="name" value-field="name"
                                           title-field="displayNameWithUtcOffset"
                                           :title="tt('Transaction Timezone')"
                                           :enable-filter="true"
                                           :filter-placeholder="tt('Timezone')"
                                           :filter-no-items-text="tt('No results')"
                                           :items="allTimezones"
                                           :model-value="transaction.timeZone"
                                           v-model:show="showTimezonePopup"
                                           @update:model-value="updateTransactionTimezone">
                </list-item-selection-popup>
            </f7-list-item>

            <f7-list-item
                link="#" no-chevron
                class="list-item-with-header-and-title list-item-title-hide-overflow"
                :class="{ 'readonly': mode === TransactionEditPageMode.View && !transaction.geoLocation }"
                :header="tt('Geographic Location')"
                @click="showGeoLocationActionSheet = true"
                v-if="pageTypeAndMode?.type === TransactionEditPageType.Transaction"
            >
                <template #title>
                    <f7-block class="list-item-custom-title no-padding no-margin">
                        <span v-if="transaction.geoLocation">{{ `(${formatCoordinate(transaction.geoLocation, coordinateDisplayType)})` }}</span>
                        <span v-else-if="!transaction.geoLocation">{{ geoLocationStatusInfo }}</span>
                    </f7-block>
                </template>

                <map-sheet :readonly="mode === TransactionEditPageMode.View"
                           v-model="transaction.geoLocation"
                           v-model:set-geo-location-by-click-map="setGeoLocationByClickMap"
                           v-model:show="showGeoLocationMapSheet">
                </map-sheet>
            </f7-list-item>

            <f7-list-item
                link="#" no-chevron
                :class="{ 'readonly': mode === TransactionEditPageMode.View }"
                v-if="canUseOwnerResources" :header="tt('Tags')"
                @click="showTransactionTagSheet = true"
            >
                <transaction-tag-selection-sheet :allow-add-new-tag="true" :enable-filter="true"
                                                 v-model:show="showTransactionTagSheet"
                                                 v-model="transaction.tagIds">
                </transaction-tag-selection-sheet>

                <template #footer>
                    <f7-block class="margin-top-half no-padding no-margin" v-if="transaction.tagIds && transaction.tagIds.length">
                        <f7-chip media-text-color="var(--f7-chip-text-color)" class="transaction-edit-tag"
                                 :text="allTagsMap[tagId]?.name ?? ''"
                                 :key="tagId"
                                 v-for="tagId in transaction.tagIds">
                            <template #media>
                                <f7-icon f7="number"></f7-icon>
                            </template>
                        </f7-chip>
                    </f7-block>
                    <f7-block class="margin-top-half no-padding no-margin" v-else-if="!transaction.tagIds || !transaction.tagIds.length">
                        <f7-chip class="transaction-edit-tag" :text="tt('None')">
                        </f7-chip>
                    </f7-block>
                </template>
            </f7-list-item>

            <f7-list-item
                link="#" no-chevron
                :header="tt('Pictures')"
                v-if="canUseOwnerResources && (showTransactionPictures || (transaction.pictures && transaction.pictures.length > 0))"
            >
                <template #footer>
                    <f7-block class="margin-top-half no-padding no-margin" :class="{ 'readonly': submitting || uploadingPicture || removingPictureId }">
                        <swiper-container
                            :pagination="false"
                            :space-between="10"
                            :slides-per-view="'auto'"
                            class="transaction-pictures"
                        >
                            <swiper-slide class="transaction-picture-container" :key="picIdx"
                                          v-for="(pictureInfo, picIdx) in transaction.pictures"
                                          @click="viewOrRemovePicture(pictureInfo)">
                                <div class="transaction-picture">
                                    <div class="display-flex justify-content-center align-items-center transaction-picture-control-backdrop"
                                         v-if="mode === TransactionEditPageMode.Add || mode === TransactionEditPageMode.Edit">
                                        <f7-icon class="picture-control-icon picture-remove-icon" f7="trash" v-if="pictureInfo.pictureId !== removingPictureId"></f7-icon>
                                        <f7-preloader color="white" :size="28" v-if="pictureInfo.pictureId === removingPictureId" />
                                    </div>
                                    <image-box style="height: 100%" alt="picture" :src="getTransactionPictureUrl(pictureInfo)">
                                        <template #error>
                                            {{ tt('Failed to load image, please check whether the config "domain" and "root_url" are set correctly.') }}
                                        </template>
                                    </image-box>
                                </div>
                            </swiper-slide>
                            <swiper-slide @click="showOpenPictureDialog" v-if="canAddTransactionPicture">
                                <div class="display-flex justify-content-center align-items-center transaction-picture transaction-picture-add">
                                    <f7-icon class="picture-control-icon" f7="plus" v-if="!uploadingPicture"></f7-icon>
                                    <f7-preloader :size="28" v-if="uploadingPicture" />
                                </div>
                            </swiper-slide>
                        </swiper-container>
                    </f7-block>
                </template>
            </f7-list-item>

            <f7-list-input
                type="textarea"
                class="transaction-edit-comment"
                style="height: auto"
                :class="{ 'readonly': mode === TransactionEditPageMode.View }"
                :label="transactionDescriptionTitle"
                :placeholder="mode !== TransactionEditPageMode.View ? tt('Your transaction description (optional)') : ''"
                v-textarea-auto-size
                v-model:value="transaction.comment"
            ></f7-list-input>
        </f7-list>

        <f7-actions close-by-outside-click close-on-escape :opened="showGeoLocationActionSheet" @actions:closed="showGeoLocationActionSheet = false">
            <f7-actions-group>
                <f7-actions-button v-if="mode !== TransactionEditPageMode.View" @click="updateGeoLocation(true)">{{ tt('Update Geographic Location') }}</f7-actions-button>
                <f7-actions-button v-if="mode !== TransactionEditPageMode.View" @click="clearGeoLocation">{{ tt('Clear Geographic Location') }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group v-if="!!getMapProvider()">
                <f7-actions-button :class="{ 'disabled': !transaction.geoLocation }" @click="setGeoLocationByClickMap = false; showGeoLocationMapSheet = true">{{ tt('Show on the map') }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group>
                <f7-actions-button bold close>{{ tt('Cancel') }}</f7-actions-button>
            </f7-actions-group>
        </f7-actions>

        <f7-actions close-by-outside-click close-on-escape :opened="showMoreActionSheet" @actions:closed="showMoreActionSheet = false">
            <f7-actions-group v-if="!isAIReview && mode === TransactionEditPageMode.Add && pageTypeAndMode?.type === TransactionEditPageType.Transaction && aiSourceAvailable">
                <f7-actions-button @click="openAISourcePage">AI 来源录入</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group v-if="mode !== TransactionEditPageMode.View && transaction.type === TransactionType.Transfer">
                <f7-actions-button @click="swapTransactionData(true, false)">{{ tt('Swap Account') }}</f7-actions-button>
                <f7-actions-button @click="swapTransactionData(false, true)">{{ tt('Swap Amount') }}</f7-actions-button>
                <f7-actions-button @click="swapTransactionData(true, true)">{{ tt('Swap Account and Amount') }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group v-if="mode !== TransactionEditPageMode.View">
                <f7-actions-button v-if="isSupportClipboard && !isiOS()" @click="pasteAmount('sourceAmount')">{{ tt('Paste Amount') }}</f7-actions-button>
                <f7-actions-button v-if="isSupportClipboard && !isiOS() && transaction.type === TransactionType.Transfer" @click="pasteAmount('destinationAmount')">{{ tt('Paste Destination Amount') }}</f7-actions-button>
                <f7-actions-button v-if="transaction.hideAmount" @click="transaction.hideAmount = false">{{ tt('Show Amount') }}</f7-actions-button>
                <f7-actions-button v-if="!transaction.hideAmount" @click="transaction.hideAmount = true">{{ tt('Hide Amount') }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group v-if="canUseOwnerResources && pageTypeAndMode?.type === TransactionEditPageType.Transaction && (mode === TransactionEditPageMode.Add || mode === TransactionEditPageMode.Edit) && isTransactionPicturesEnabled() && !showTransactionPictures">
                <f7-actions-button @click="showTransactionPictures = true">{{ tt('Add Picture') }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group v-if="!isAIReview && pageTypeAndMode?.type === TransactionEditPageType.Transaction && mode === TransactionEditPageMode.View && transaction.type !== TransactionType.ModifyBalance">
                <f7-actions-button @click="duplicate(false, false)">{{ tt('Duplicate') }}</f7-actions-button>
                <f7-actions-button @click="duplicate(true, false)">{{ tt('Duplicate (With Time)') }}</f7-actions-button>
                <f7-actions-button @click="duplicate(false, true)" v-if="transaction.geoLocation">{{ tt('Duplicate (With Geographic Location)') }}</f7-actions-button>
                <f7-actions-button @click="duplicate(true, true)" v-if="transaction.geoLocation">{{ tt('Duplicate (With Time and Geographic Location)') }}</f7-actions-button>
            </f7-actions-group>
            <f7-actions-group>
                <f7-actions-button bold close>{{ tt('Cancel') }}</f7-actions-button>
            </f7-actions-group>
        </f7-actions>



        <f7-toolbar id="quick-save-button" class="compact-tabbar" tabbar bottom v-if="!editingLocked && quickSaveButtonStyleType === TransactionQuickSaveButtonStyle.BottomFixed.type && mode !== TransactionEditPageMode.View">
            <f7-link :class="{ 'disabled': inputIsEmpty || submitting || recognizing }" @click="quickSave">
                <span class="tabbar-primary-link">{{ tt(quickSaveButtonTitle) }}</span>
            </f7-link>
        </f7-toolbar>

        <f7-popover class="quick-save-popover" target-el="#quick-save-button"
                    v-model:opened="showQuickSavePopover">
            <f7-list>
                <f7-list-item link="#" no-chevron popover-close
                              :title="tt(TransactionQuickAddButtonActionType.SaveAndGoBack.name)"
                              @click="save(AfterSaveAction.GoBack)"></f7-list-item>
                <f7-list-item link="#" no-chevron popover-close
                              :title="tt(TransactionQuickAddButtonActionType.SaveAndAddNewTransaction.name)"
                              @click="save(AfterSaveAction.StayWithNewTransaction)"></f7-list-item>
                <f7-list-item link="#" no-chevron popover-close
                              :title="tt(TransactionQuickAddButtonActionType.SaveAndKeepCurrentData.name)"
                              @click="save(AfterSaveAction.StayWithCurrentTransaction)"></f7-list-item>
            </f7-list>
        </f7-popover>

        <f7-photo-browser ref="pictureBrowser" type="popup" navbar-of-text="/"
                          :navbar-show-count="true" :exposition="false"
                          :photos="transactionPictures" :thumbs="transactionThumbs" />
        <input ref="pictureInput" type="file" style="display: none" :accept="`${SUPPORTED_IMAGE_EXTENSIONS};capture=camera`" @change="onUploadPicture($event)" />
        </div>
        </template>
        <template #fixed v-if="!editingLocked && !loadingError && (quickSaveButtonStyleType === TransactionQuickSaveButtonStyle.BottomLeftFloating.type || quickSaveButtonStyleType === TransactionQuickSaveButtonStyle.BottomCenterFloating.type || quickSaveButtonStyleType === TransactionQuickSaveButtonStyle.BottomRightFloating.type)">
            <f7-fab id="quick-save-button" :class="{ 'disabled': inputIsEmpty || submitting || recognizing }" :position="quickSaveButtonFloatingPosition"
                    :text="tt(quickSaveButtonTitle)"
                    @click="quickSave" v-if="mode !== TransactionEditPageMode.View">
            </f7-fab>
        </template>
    </f7-page>
</template>

<script setup lang="ts">
import { ref, computed, watch, useTemplateRef, nextTick, onUnmounted } from 'vue';
import AIReviewConfirmationPanel from '@/components/common/AIReviewConfirmationPanel.vue';
import { useAIReviewLedgerStore } from '@/stores/aiReviewLedger.ts';
import type { AIReviewEditorInput, AIReviewConfirmRequest } from '@/models/ai_review_ledger.ts';
import TemplateCreationPanel from '@/components/common/TemplateCreationPanel.vue';
import { templateFromEditorInput } from '@/lib/template_editor.ts';
import type { TemplateEditorInput } from '@/models/template_creation.ts';
import type { PhotoBrowser, Router } from 'framework7/types';

import { useI18n } from '@/locales/helpers.ts';
import { useI18nUIComponents, isiOS, showLoading, hideLoading } from '@/lib/ui/mobile.ts';
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
import { useLedgersStore } from '@/stores/ledger.ts';
import { DefaultLedgerId, LedgerMemberRole } from '@/models/ledger.ts';
import { canManageMobileLedger, canWriteMobileLedger, canUseMobileOwnerResources, isMobileAccountSelectable, isMobileCategorySelectable, mobileLedgerRole, mobileTemplateSelectionProblem, mobileTransactionSelectionProblem, mobileTagSelectionProblem, transactionDraftMatchesLedger } from '@/lib/mobile_ledger.ts';
import LedgerContextNotice from '@/components/mobile/LedgerContextNotice.vue';
import { useTransactionTemplatesStore } from '@/stores/transactionTemplate.ts';

import { CategoryType } from '@/core/category.ts';
import {
    TransactionType,
    TransactionEditScopeType,
    TransactionQuickSaveButtonStyle,
    TransactionQuickAddButtonActionType
} from '@/core/transaction.ts';
import { ScheduledTemplateFrequencyType, TemplateType } from '@/core/template.ts';
import { KnownFileType } from '@/core/file.ts';

import { TRANSACTION_MAX_AMOUNT, TRANSACTION_MIN_AMOUNT } from '@/consts/transaction.ts';
import { KnownErrorCode } from '@/consts/api.ts';
import { SUPPORTED_IMAGE_EXTENSIONS } from '@/consts/file.ts';

import { TransactionTemplate } from '@/models/transaction_template.ts';
import type { TransactionPictureInfoBasicResponse } from '@/models/transaction_picture_info.ts';
import { Transaction, type PendingTransactionCreate } from '@/models/transaction.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import { useLedgerPendingStore } from '@/stores/ledgerPending.ts';
import { mobilePendingRouteTarget } from '@/lib/mobile_pending_route.ts';
import { ledgerPendingDraftRevision } from '@/lib/ledger_pending.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, isUserLogined, inspectUserTransactionDraft, getUserPendingTemplateCreate, getUserTemplateEditorDraft } from '@/lib/userstate.ts';
import { TransactionPicture } from '@/models/transaction_picture_info.ts';

import {
    getTimezoneOffset,
    getTimezoneOffsetMinutes,
    parseDateTimeFromUnixTimeWithTimezoneOffset
} from '@/lib/datetime.ts';
import { formatCoordinate } from '@/lib/coordinate.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import { getTransactionPrimaryCategoryName, getTransactionSecondaryCategoryName, transactionTypeToCategoryType } from '@/lib/category.ts';
import { type SetTransactionOptions } from '@/lib/transaction.ts';
import {
    isTransactionFromAITextRecognitionEnabled,
    isTransactionFromAIImageRecognitionEnabled,
    isTransactionPicturesEnabled,
    getMapProvider
} from '@/lib/server_settings.ts';
import { compressJpgImageByQuality } from '@/lib/ui/common.ts';
import logger from '@/lib/logger.ts';
import services from '@/lib/services.ts';

const props = defineProps<{
    f7route: Router.Route;
    f7router: Router.Router;
}>();

const query = props.f7route.query;
const pageTypeAndMode = getPageTypeNameMode();

const {
    tt,
    getMultiMonthAndDayLongNames,
    getMultiMonthdayShortNames,
    getMultiWeekdayLongNames,
    formatDateTimeToLongDate,
    formatDateTimeToLongTime,
    formatGregorianTextualYearMonthDayToLongDate,
    parseAmountFromLocalizedNumerals,
    parseAmountFromWesternArabicNumerals
} = useI18n();
const { showAlert, showConfirm, showToast } = useI18nUIComponents();

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
    numeralSystem,
    currentTimezoneOffsetMinutes,
    defaultCurrency,
    firstDayOfWeek,
    coordinateDisplayType,
    imageUploadQualityType,
    allTimezones,
    allVisibleAccounts,
    allVisibleCategorizedAccounts,
    allCategories,
    allTagsMap,
    firstVisibleAccountId,
    hasVisibleExpenseCategories,
    hasVisibleIncomeCategories,
    hasVisibleTransferCategories,
    canAddTransactionPicture,
    title,
    quickSaveButtonTitle,
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
    setTransactionModel,
    updateTransactionModelByAfterSaveAction,
    updateTransactionTime,
    updateTransactionTimezone,
    swapTransactionData,
    getDisplayAmount,
    getTransactionPictureUrl
} = useTransactionEditPageBase(pageTypeAndMode?.type || TransactionEditPageType.Transaction, pageTypeAndMode?.mode, query['type'] ? parseInt(query['type']) : undefined, getSelectionProblem);

const isSupportClipboard = !!navigator.clipboard;

const settingsStore = useSettingsStore();
const userStore = useUserStore();
const accountsStore = useAccountsStore();
const transactionCategoriesStore = useTransactionCategoriesStore();
const transactionTagsStore = useTransactionTagsStore();
const transactionsStore = useTransactionsStore();
const ledgersStore = useLedgersStore();
const editLedgerId = ledgersStore.selectedLedgerId;
const editUsername = userStore.currentUserBasicInfo?.username;
const editIdentity = getTransactionStateIdentity();
const isDefaultLedger = editLedgerId === DefaultLedgerId;
const pageActive = ref(true);
const templatePageActive = ref(true);
let pageGeneration = 0;
const contextInvalidated = ref(false);
const ledgerContextChanged = computed(() => contextInvalidated.value || editLedgerId !== ledgersStore.selectedLedgerId || editUsername !== userStore.currentUserBasicInfo?.username || editIdentity !== getTransactionStateIdentity() || !isUserLogined());
const ledgerRole = ref<number | null>(isDefaultLedger ? LedgerMemberRole.Owner : null);
const canWriteLedger = computed(() => !ledgerContextChanged.value && (pageTypeAndMode?.type === TransactionEditPageType.Template ? canManageMobileLedger(ledgerRole.value) : canWriteMobileLedger(ledgerRole.value)));
const canUseOwnerResources = computed(() => !ledgerContextChanged.value && canUseMobileOwnerResources(ledgerRole.value) && (pageTypeAndMode?.type !== TransactionEditPageType.Template || isDefaultLedger));
const pendingCreate = ref<PendingTransactionCreate | null>(null);
let openedTransactionDraftRevision: string | undefined;
const canReadPendingReceipt = computed(() => !ledgerContextChanged.value && !!pendingCreate.value?.receipt && ledgerRole.value !== null);
const pendingStore = useLedgerPendingStore();
let pendingTarget: LedgerPendingTarget | null = null;
let pendingTargetParseFailed = false;
try { pendingTarget = mobilePendingRouteTarget(props.f7route.path, query); }
catch { pendingTargetParseFailed = true; }
const saveError = ref('');
function capturePageActionCurrent(): () => boolean {
    const serial = pageGeneration;
    return () => pageActive.value && serial === pageGeneration && !ledgerContextChanged.value;
}
const aiReviewStore = useAIReviewLedgerStore();
const aiReviewId = query['aiReviewId'] || '';
const isAIReview = pageTypeAndMode?.type === TransactionEditPageType.Transaction && !!aiReviewId;
const aiReviewEditable = ref(false), aiReviewLocked = ref(true), aiReviewSourceText = ref(''), aiPageActive = ref(true);
const aiReviewConfirmationPanel = useTemplateRef<InstanceType<typeof AIReviewConfirmationPanel>>('aiReviewConfirmationPanel');
let aiPageGeneration = 0;
function restoreAIReviewInput(input: AIReviewEditorInput): void {
    const restored = Transaction.ofDraft(input.transaction);
    if (!restored) return;
    restored.expenseCategoryId = input.categorySelections.expense;
    restored.incomeCategoryId = input.categorySelections.income;
    restored.transferCategoryId = input.categorySelections.transfer;
    const previousMode = mode.value, serial = loadGeneration;
    mode.value = TransactionEditPageMode.View;
    transaction.value = restored;
    templateSourceRaw.value = input.rawSourceAmount; templateDestinationRaw.value = input.rawDestinationAmount;
    void nextTick(() => { if (serial === loadGeneration && !ledgerContextChanged.value) mode.value = previousMode; });
}
function restoreAIReviewRequest(request: AIReviewConfirmRequest): void {
    const previousMode = mode.value, serial = loadGeneration;
    mode.value = TransactionEditPageMode.View;
    transaction.value = Transaction.ofCreateRequest(request.transaction);
    templateSourceRaw.value = undefined; templateDestinationRaw.value = undefined;
    void nextTick(() => { if (serial === loadGeneration && !ledgerContextChanged.value) mode.value = previousMode; });
}
function completeAIReview(): void {
    submitted.value = true; aiPageActive.value = false; showToast('已确认入账'); props.f7router.back();
}
/** New transactions hand the AI affordance to the durable source page instead of
 * recognizing in place; existing manual input keeps its own normal draft. */
function openAISourcePage(): void {
    if (editingLocked.value || mode.value !== TransactionEditPageMode.Add) return;
    props.f7router.navigate('/ai/source', { props: {} });
}
const aiSourceAvailable = computed(() => isTransactionFromAITextRecognitionEnabled() || isTransactionFromAIImageRecognitionEnabled());
const templateCreationPanel = useTemplateRef<InstanceType<typeof TemplateCreationPanel>>('templateCreationPanel');
const templateSourceRaw = ref<string>(), templateDestinationRaw = ref<string>(), templateCreationLocked = ref(false);
const isCreateTemplate = computed(() => pageTypeAndMode?.type === TransactionEditPageType.Template && mode.value === TransactionEditPageMode.Add);
function restoreTemplateInput(input: TemplateEditorInput): void {
    transaction.value = templateFromEditorInput(input, parseAmountFromLocalizedNumerals, parseAmountFromWesternArabicNumerals);
    templateSourceRaw.value = typeof input.sourceAmount === 'string' ? input.sourceAmount : undefined;
    templateDestinationRaw.value = typeof input.destinationAmount === 'string' ? input.destinationAmount : undefined;
}
function completeTemplateCreation(result: { deleted: boolean; id: string }): void {
    submitted.value = true; showToast(result.deleted ? '原模板已删除，未重新创建' : 'You have added a new template'); props.f7router.back();
}
const editingLocked = computed(() => ledgerContextChanged.value || submitting.value || !!pendingCreate.value || templateCreationLocked.value || (isAIReview && (aiReviewLocked.value || !aiReviewEditable.value)) || !canWriteLedger.value || (mode.value === TransactionEditPageMode.Edit && !transaction.value.editable));
const transferDirection = computed(() => !transaction.value.sourceAccountId || transaction.value.sourceAccountId === '0' ? 'in'
    : !transaction.value.destinationAccountId || transaction.value.destinationAccountId === '0' ? 'out' : 'internal');
function setTransferDirection(direction: 'internal' | 'out' | 'in'): void {
    if (editingLocked.value || mode.value === TransactionEditPageMode.View) return;
    if (direction === 'in') {
        if (!transaction.value.destinationAccountId || transaction.value.destinationAccountId === '0') transaction.value.destinationAccountId = firstVisibleAccountId.value || '';
        if (!transaction.value.destinationAmount) transaction.value.destinationAmount = transaction.value.sourceAmount;
        transaction.value.sourceAccountId = '0';
        transaction.value.sourceAmount = 0;
    } else {
        if (!transaction.value.sourceAccountId || transaction.value.sourceAccountId === '0') {
            transaction.value.sourceAccountId = firstVisibleAccountId.value || '';
            transaction.value.sourceAmount = transaction.value.destinationAmount;
        }
        if (direction === 'out') {
            transaction.value.destinationAccountId = '0';
            transaction.value.destinationAmount = 0;
        } else if (!transaction.value.destinationAccountId || transaction.value.destinationAccountId === '0') {
            transaction.value.destinationAccountId = allVisibleAccounts.value.find(account => account.id !== transaction.value.sourceAccountId)?.id || '';
        }
    }
}
let originalTagIds: string[] = [];
let originalPictureIds: string[] = [];
let loadGeneration = 0;
const transactionTemplatesStore = useTransactionTemplatesStore();

const pictureBrowser = useTemplateRef<PhotoBrowser.PhotoBrowser>('pictureBrowser');
const pictureInput = useTemplateRef<HTMLInputElement>('pictureInput');

const loadingError = ref<unknown | null>(null);
const removingPictureId = ref<string | null>(null);
const transactionDateTimeSheetMode = ref<string>('time');
const showTimeInDefaultTimezone = ref<boolean>(false);
const showQuickSavePopover = ref<boolean>(false);
const showTimezonePopup = ref<boolean>(false);
const showGeoLocationActionSheet = ref<boolean>(false);
const showMoreActionSheet = ref<boolean>(false);
const showSourceAmountSheet = ref<boolean>(false);
const showDestinationAmountSheet = ref<boolean>(false);
const showCategorySheet = ref<boolean>(false);
const showSourceAccountSheet = ref<boolean>(false);
const showDestinationAccountSheet = ref<boolean>(false);
const showTransactionDateTimeSheet = ref<boolean>(false);
const showTransactionScheduledFrequencySheet = ref<boolean>(false);
const showScheduledStartDateSheet = ref<boolean>(false);
const showScheduledEndDateSheet = ref<boolean>(false);
const showGeoLocationMapSheet = ref<boolean>(false);
const showTransactionTagSheet = ref<boolean>(false);
const showTransactionPictures = ref<boolean>(pageTypeAndMode?.type === TransactionEditPageType.Transaction
    && (pageTypeAndMode?.mode === TransactionEditPageMode.Add || pageTypeAndMode?.mode === TransactionEditPageMode.Edit)
    && settingsStore.appSettings.alwaysShowTransactionPicturesInMobileTransactionEditPage);

const quickSaveButtonStyleType = computed<number>(() => settingsStore.appSettings.quickSaveButtonStyleInMobileTransactionListPage);
const quickSaveButtonFloatingPosition = computed<string>(() => {
    switch (settingsStore.appSettings.quickSaveButtonStyleInMobileTransactionListPage) {
        case TransactionQuickSaveButtonStyle.BottomLeftFloating.type:
            return 'left-bottom';
        case TransactionQuickSaveButtonStyle.BottomCenterFloating.type:
            return 'center-bottom';
        case TransactionQuickSaveButtonStyle.BottomRightFloating.type:
            return 'right-bottom';
        default:
            return 'right-bottom';
    }
});

const sourceAmountClass = computed<Record<string, boolean>>(() => {
    const classes: Record<string, boolean> = {
        'readonly': mode.value === TransactionEditPageMode.View,
        'text-expense': transaction.value.type === TransactionType.Expense,
        'text-income': transaction.value.type === TransactionType.Income,
        'text-color-primary': transaction.value.type === TransactionType.Transfer
    };

    classes[getFontClassByAmount(transaction.value.sourceAmount)] = true;

    return classes;
});

const destinationAmountClass = computed<Record<string, boolean>>(() => {
    const classes: Record<string, boolean> = {
        'readonly': mode.value === TransactionEditPageMode.View
    };

    classes[getFontClassByAmount(transaction.value.destinationAmount)] = true;

    return classes;
});

const transactionDisplayDate = computed<string>(() => {
    if (mode.value !== TransactionEditPageMode.View || !showTimeInDefaultTimezone.value) {
        const dateTime = parseDateTimeFromUnixTimeWithTimezoneOffset(transaction.value.time, transaction.value.utcOffset);
        return formatDateTimeToLongDate(dateTime);
    }

    const dateTime = parseDateTimeFromUnixTimeWithTimezoneOffset(transaction.value.time, getTimezoneOffsetMinutes(transaction.value.time));
    return formatDateTimeToLongDate(dateTime);
});

const transactionDisplayTime = computed<string>(() => {
    if (mode.value !== TransactionEditPageMode.View || !showTimeInDefaultTimezone.value) {
        const dateTime = parseDateTimeFromUnixTimeWithTimezoneOffset(transaction.value.time, transaction.value.utcOffset);
        return formatDateTimeToLongTime(dateTime);
    }

    const dateTime = parseDateTimeFromUnixTimeWithTimezoneOffset(transaction.value.time, getTimezoneOffsetMinutes(transaction.value.time));
    const utcOffset = numeralSystem.value.replaceWesternArabicDigitsToLocalizedDigits(getTimezoneOffset(transaction.value.time));
    return `${formatDateTimeToLongTime(dateTime)} (UTC${utcOffset})`;
});

const transactionDisplayTimezoneName = computed<string>(() => {
    for (const timezone of allTimezones.value) {
        if (timezone.name === transaction.value.timeZone) {
            return timezone.displayName;
        }
    }

    return '';
});

const transactionPictures = computed<Record<string, string | undefined>[]>(() => {
    const thumbs: Record<string, string | undefined>[] = [];

    if (!transaction.value.pictures || !transaction.value.pictures.length) {
        return thumbs;
    }

    for (const picture of transaction.value.pictures) {
        thumbs.push({
            url: getTransactionPictureUrl(picture)
        });
    }

    return thumbs;
});

const transactionThumbs = computed<(string | undefined)[]>(() => {
    const thumbs: (string | undefined)[] = [];

    if (!transaction.value.pictures || !transaction.value.pictures.length) {
        return thumbs;
    }

    for (const picture of transaction.value.pictures) {
        thumbs.push(getTransactionPictureUrl(picture));
    }

    return thumbs;
});

const transactionDisplayScheduledFrequency = computed<string>(() => {
    if (pageTypeAndMode?.type !== TransactionEditPageType.Template) {
        return '';
    }

    const template = transaction.value as TransactionTemplate;

    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Disabled.type) {
        return tt('Disabled');
    }

    const items = (template.scheduledFrequency || '').split(',');
    const scheduledFrequencyValues: number[] = [];

    for (const item of items) {
        if (item) {
            scheduledFrequencyValues.push(parseInt(item));
        }
    }

    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Daily.type) {
        return tt('Daily');
    } else if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.EveryNDays.type) {
        if (scheduledFrequencyValues.length) {
            return tt('format.misc.everyNDays', {
                n: scheduledFrequencyValues[0]
            });
        } else {
            return tt('Every N Days');
        }
    } else if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Weekly.type) {
        if (scheduledFrequencyValues.length) {
            return tt('format.misc.everyMultiDaysOfWeek', {
                days: getMultiWeekdayLongNames(scheduledFrequencyValues, firstDayOfWeek.value)
            });
        } else {
            return tt('Weekly');
        }
    } else if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Monthly.type) {
        if (scheduledFrequencyValues.length) {
            return tt('format.misc.everyMultiDaysOfMonth', {
                days: getMultiMonthdayShortNames(scheduledFrequencyValues)
            });
        } else {
            return tt('Monthly');
        }
    } else if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Yearly.type) {
        if (scheduledFrequencyValues.length) {
            return tt('format.misc.everyMultiDaysOfYear', {
                days: getMultiMonthAndDayLongNames(scheduledFrequencyValues)
            });
        } else {
            return tt('Yearly');
        }
    } else {
        return '';
    }
});

const transactionDisplayScheduledStartDate = computed<string>(() => {
    if (pageTypeAndMode?.type !== TransactionEditPageType.Template) {
        return '';
    }

    const template = transaction.value as TransactionTemplate;

    if (template.scheduledStartDate) {
        return formatGregorianTextualYearMonthDayToLongDate(template.scheduledStartDate);
    } else {
        return tt('No limit');
    }
});

const transactionDisplayScheduledEndDate = computed<string>(() => {
    if (pageTypeAndMode?.type !== TransactionEditPageType.Template) {
        return '';
    }

    const template = transaction.value as TransactionTemplate;

    if (template.scheduledEndDate) {
        return formatGregorianTextualYearMonthDayToLongDate(template.scheduledEndDate);
    } else {
        return tt('No limit');
    }
});

function getPageTypeNameMode(): { type: TransactionEditPageType, mode: TransactionEditPageMode } | null {
    const path = props.f7route.path.replace(/\/+$/, '').toLowerCase();
    if (path === '/transaction/add') {
        return {
            type: TransactionEditPageType.Transaction,
            mode: TransactionEditPageMode.Add
        };
    } else if (path === '/transaction/edit') {
        return {
            type: TransactionEditPageType.Transaction,
            mode: TransactionEditPageMode.Edit
        };
    } else if (path === '/transaction/detail') {
        return {
            type: TransactionEditPageType.Transaction,
            mode: TransactionEditPageMode.View
        };
    } else if (path === '/template/add') {
        return {
            type: TransactionEditPageType.Template,
            mode: TransactionEditPageMode.Add
        };
    } else if (path === '/template/edit') {
        return {
            type: TransactionEditPageType.Template,
            mode: TransactionEditPageMode.Edit
        };
    } else {
        return null;
    }
}

function getFontClassByAmount(amount: number): string {
    if (amount >= 10000000000 || amount <= -10000000000) {
        return 'ebk-extra-small-amount';
    } if (amount >= 100000000 || amount <= -100000000) {
        return 'ebk-small-amount';
    } else if (amount >= 1000000 || amount <= -1000000) {
        return 'ebk-normal-amount';
    } else {
        return 'ebk-large-amount';
    }
}

function getSelectionProblem(value: Transaction): string | null {
    if (mode.value === TransactionEditPageMode.View) return null;
    if (ledgerContextChanged.value || accountsStore.accountListLedgerId !== editLedgerId || accountsStore.accountListStateInvalid ||
        transactionCategoriesStore.loadedLedgerId !== editLedgerId || transactionCategoriesStore.transactionCategoryListStateInvalid) {
        return '当前账本账户或分类尚未有效加载，请重试';
    }
    if (pageTypeAndMode?.type === TransactionEditPageType.Template) {
        const templateProblem = mobileTemplateSelectionProblem(value, editLedgerId, ledgerRole.value, accountsStore.allAccountsMap, transactionCategoriesStore.allTransactionCategoriesMap, originalTagIds);
        if (templateProblem) return templateProblem;
        return isDefaultLedger ? mobileTagSelectionProblem(value.tagIds, transactionTagsStore.allTransactionTagsMap) : null;
    }
    const selectionProblem = mobileTransactionSelectionProblem(value, editLedgerId, accountsStore.allAccountsMap, transactionCategoriesStore.allTransactionCategoriesMap);
    if (selectionProblem) return selectionProblem;
    if (canUseOwnerResources.value) return mobileTagSelectionProblem(value.tagIds, transactionTagsStore.allTransactionTagsMap);
    const sameIds = (left: string[], right: string[]) => JSON.stringify([...left].sort()) === JSON.stringify([...right].sort());
    if (mode.value === TransactionEditPageMode.Edit && sameIds(value.tagIds, originalTagIds) && sameIds(value.getPictureIds(), originalPictureIds)) return null;
    return value.tagIds.length || value.getPictureIds().length ? '标签与附件仅账本所有者可管理；新流水不能带入他人的标签或附件' : null;
}

async function refreshLedgerRole(generation = loadGeneration): Promise<void> {
    const role = isDefaultLedger ? LedgerMemberRole.Owner : await services.listLedgerMembers({ ledgerId: editLedgerId, expectedUsername: editUsername, ignoreError: true }).then(response => {
        if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取当前账本权限，请重试');
        return mobileLedgerRole(editLedgerId, response.data.result);
    });
    if (generation !== loadGeneration || ledgerContextChanged.value || editIdentity !== getTransactionStateIdentity()) throw { stale: true };
    ledgerRole.value = role;
}

/** Keep an already observed receipt even when its disk persistence failed.
 * A different pending key must be reopened explicitly, never adopted here. */
function retainOriginalPending(next: PendingTransactionCreate | null, previous: PendingTransactionCreate | null = pendingCreate.value): PendingTransactionCreate | null {
    if (!next) return previous;
    if (previous) {
        if (previous.clientSessionId !== next.clientSessionId || JSON.stringify(previous.request) !== JSON.stringify(next.request) ||
            previous.receipt && next.receipt && previous.receipt.id !== next.receipt.id) {
            throw new Error('原记账请求或创建回执已变化，请保留原记录后回总览核对');
        }
        if (previous.receipt && !next.receipt) return { ...next, receipt: previous.receipt };
    }
    return next;
}
function assertPendingTargetLocal(): void {
    if (!pendingTarget) return;
    if (pendingTarget.ledgerId !== editLedgerId || ledgerContextChanged.value) throw new Error('原待处理入口的账号或账本已变化');
    if (pendingTarget.kind === 'transaction-create') {
        const original = transactionsStore.getPendingTransactionCreate(editLedgerId);
        if (pendingTarget.recovery === 'pending') {
            if (!original || original.clientSessionId !== pendingTarget.clientSessionId) throw new Error('原待确认请求已变化或已处理，请回总览重新读取');
        } else {
            const draft = inspectUserTransactionDraft(editLedgerId);
            if (!draft || ledgerPendingDraftRevision(draft) !== pendingTarget.draftRevision ||
                draft.clientSessionId !== pendingTarget.clientSessionId || original && original.clientSessionId !== draft.clientSessionId) {
                throw new Error('原草稿已变化或已有另一原请求，请回总览重新读取');
            }
        }
    } else if (pendingTarget.kind === 'template-create') {
        const original = getUserPendingTemplateCreate(editLedgerId);
        if (pendingTarget.recovery === 'pending') {
            if (!original || original.request.templateType !== pendingTarget.templateType || original.request.clientSessionId !== pendingTarget.clientSessionId || original.draftRevision !== pendingTarget.draftRevision) {
                throw new Error('原模板确认请求已变化或已处理，请回总览重新读取');
            }
        } else {
            const draft = getUserTemplateEditorDraft(editLedgerId, pendingTarget.templateType);
            if (!draft || draft.revision !== pendingTarget.draftRevision || original &&
                (original.request.templateType !== pendingTarget.templateType || original.draftRevision !== draft.revision)) {
                throw new Error('原模板草稿已变化或已有另一原请求，请回总览重新读取');
            }
        }
    }
}

function getQueryTransactionOptions(): SetTransactionOptions {
    const integer = (key: string): number | undefined => {
        if (query[key] === undefined) return undefined;
        if (!/^-?\d+$/.test(query[key]!) || !Number.isSafeInteger(Number(query[key]))) throw new Error('流水链接的金额、日期或类型无效');
        return Number(query[key]);
    };
    return {
        time: integer('time'),
        type: integer('type') ?? 0,
        categoryId: query['categoryId'],
        accountId: query['accountId'],
        destinationAccountId: query['destinationAccountId'],
        amount: integer('amount'),
        destinationAmount: integer('destinationAmount'),
        tagIds: query['tagIds'],
        comment: query['comment']
    };
}

async function init(forceReload = false): Promise<void> {
    if (!pageTypeAndMode) {
        showToast('Parameter Invalid');
        loadingError.value = 'Parameter Invalid';
        return;
    }

    if (ledgerContextChanged.value) return;
    const generation = ++loadGeneration;
    loading.value = true;
    loadingError.value = null;
    saveError.value = '';
    ledgerRole.value = isDefaultLedger ? LedgerMemberRole.Owner : null;

    try {
    if (pendingTargetParseFailed) throw new Error('原待处理入口无效，请返回总览重新读取');
    if (pendingTarget) {
        await pendingStore.refresh(pendingTarget.ledgerId);
        await pendingStore.verifyTarget(pendingTarget);
        if (generation !== loadGeneration || ledgerContextChanged.value) return;
        assertPendingTargetLocal();
    }
    if (query['ledgerId'] && query['ledgerId'] !== editLedgerId) throw new Error('此流水链接属于另一账本，请先切换到账本后重新打开');
    if (isAIReview) {
        if (!/^[1-9]\d*$/.test(aiReviewId) || props.f7route.path !== '/transaction/add') throw new Error('AI 条目链接无效，请返回队列重新打开');
        aiReviewEditable.value = false; aiReviewLocked.value = true;
        await refreshLedgerRole(generation);
        await Promise.all([
            aiReviewStore.verifyCapabilities(editLedgerId),
            accountsStore.loadAllAccounts({ force: forceReload, ledgerId: editLedgerId }),
            transactionCategoriesStore.loadAllCategories({ force: forceReload, ledgerId: editLedgerId }),
            canUseOwnerResources.value ? transactionTagsStore.loadAllTags({ force: forceReload }) : Promise.resolve([])
        ]);
        const pending = aiReviewStore.readPendingConfirm(editLedgerId);
        let item;
        try { item = await aiReviewStore.get(editLedgerId, aiReviewId); }
        catch (error) { if (pending?.request.id !== aiReviewId) throw error; }
        if (generation !== loadGeneration || ledgerContextChanged.value) return;
        aiReviewSourceText.value = item?.sourceText.slice(0, 160) || '原入账请求已保留，请核对原结果';
        aiReviewEditable.value = !!pending && pending.request.id === aiReviewId || !!item && item.editable && item.status === 1 && !item.deleted;
        mode.value = TransactionEditPageMode.View;
        if (pending?.request.id === aiReviewId) transaction.value = Transaction.ofCreateRequest(pending.request.transaction);
        else {
            const data = item?.recognizedData;
            const type = data?.type ?? TransactionType.Expense;
            const categoryType = type === TransactionType.Expense ? CategoryType.Expense : type === TransactionType.Income ? CategoryType.Income : CategoryType.Transfer;
            transaction.value = Transaction.ofDraft({ ...transaction.value.toTransactionDraft()!, type,
                time: data?.time || item!.createdUnixTime, categoryId: data?.categoryId && isMobileCategorySelectable(data.categoryId, categoryType, transactionCategoriesStore.allTransactionCategoriesMap) ? data.categoryId : '0',
                sourceAccountId: data?.sourceAccountId && isMobileAccountSelectable(data.sourceAccountId, editLedgerId, accountsStore.allAccountsMap) ? data.sourceAccountId : '0',
                destinationAccountId: type === TransactionType.Transfer && data?.destinationAccountId && isMobileAccountSelectable(data.destinationAccountId, editLedgerId, accountsStore.allAccountsMap) ? data.destinationAccountId : '0',
                sourceAmount: data?.sourceAmount ?? 0, destinationAmount: type === TransactionType.Transfer ? data?.destinationAmount ?? 0 : data?.sourceAmount ?? 0,
                tagIds: data?.tagIds ?? [], comment: data?.comment ?? '', pictures: [] })!;
        }
        originalTagIds = [...transaction.value.tagIds]; originalPictureIds = transaction.value.getPictureIds();
        await nextTick();
        if (generation !== loadGeneration || ledgerContextChanged.value) return;
        mode.value = aiReviewEditable.value && canWriteLedger.value ? TransactionEditPageMode.Add : TransactionEditPageMode.View;
        return;
    }
    if (pageTypeAndMode.type === TransactionEditPageType.Transaction && mode.value !== TransactionEditPageMode.Add && !query['id']) {
        throw new Error('流水链接缺少流水 ID');
    }
    if (pageTypeAndMode.type === TransactionEditPageType.Transaction) transactionsStore.initTransactionDraft(editLedgerId);
    pendingCreate.value = pageTypeAndMode.type === TransactionEditPageType.Transaction && mode.value === TransactionEditPageMode.Add ? retainOriginalPending(transactionsStore.getPendingTransactionCreate(editLedgerId)) : null;
    if (pendingCreate.value) {
        transaction.value = Transaction.ofCreateRequest(pendingCreate.value.request);
        if (pendingCreate.value.pictures) transaction.value.setPictures(TransactionPicture.ofMulti([...pendingCreate.value.pictures]));
        clientSessionId.value = pendingCreate.value.clientSessionId;
    }
    await refreshLedgerRole(generation);

    const promises: Promise<unknown>[] = [
        accountsStore.loadAllAccounts({ force: forceReload, ledgerId: editLedgerId }),
        transactionCategoriesStore.loadAllCategories({ force: forceReload, ledgerId: editLedgerId }),
        canUseOwnerResources.value ? transactionTagsStore.loadAllTags({ force: forceReload }) : Promise.resolve([]),
        pageTypeAndMode.type === TransactionEditPageType.Transaction ? transactionTemplatesStore.loadAllTemplates({ force: false, templateType: TemplateType.Normal.type, ledgerId: editLedgerId }) : Promise.resolve([])
    ];

    if (!pendingCreate.value && pageTypeAndMode.type === TransactionEditPageType.Transaction) {
        if (query['id']) {
            if (mode.value === TransactionEditPageMode.Edit) {
                editId.value = query['id'];
            } else if (mode.value === TransactionEditPageMode.Add) {
                duplicateFromId.value = query['id'];
            }

            promises.push(transactionsStore.getTransaction({ ledgerId: editLedgerId, transactionId: query['id'], withPictures: true }));
        }
    } else if (!pendingCreate.value && pageTypeAndMode.type === TransactionEditPageType.Template) {
        const template = TransactionTemplate.createNewTransactionTemplate(transaction.value, editLedgerId);
        template.name = '';
        template.templateType = TemplateType.Normal.type;

        if (query['templateType']) {
            template.templateType = parseInt(query['templateType']);
        }
        if (!TemplateType.valueOf(template.templateType)) throw new Error('模板类型无效');

        if (template.templateType === TemplateType.Schedule.type) {
            template.scheduledFrequencyType = ScheduledTemplateFrequencyType.Disabled.type;
            template.scheduledFrequency = '';
        }

        transaction.value = template;

        if (query['id']) {
            if (mode.value === TransactionEditPageMode.Edit) {
                editId.value = query['id'];
            }

            promises.push(transactionTemplatesStore.getTemplate({ templateId: query['id'], ledgerId: editLedgerId }));
        }
    }

    const initOptions = pendingCreate.value ? {} : getQueryTransactionOptions();

    if (initOptions.type &&
        initOptions.type >= TransactionType.Income &&
        initOptions.type <= TransactionType.Transfer) {
        transaction.value.type = initOptions.type;
    } else if (initOptions.type === TransactionType.ModifyBalance &&
        pageTypeAndMode.type === TransactionEditPageType.Transaction &&
        mode.value === TransactionEditPageMode.View) {
        transaction.value.type = initOptions.type;
    }

    if (mode.value === TransactionEditPageMode.Add && !pendingCreate.value) {
        clientSessionId.value = generateRandomUUID();
    }

    const responses = await Promise.all(promises);
        if (generation !== loadGeneration || ledgerContextChanged.value) return;
        assertPendingTargetLocal();
        if (pendingCreate.value) { loading.value = false; return; }
        for (const id of [initOptions.accountId, initOptions.destinationAccountId].filter(id => !!id && id !== '0')) {
            if (!isMobileAccountSelectable(id!, editLedgerId, accountsStore.allAccountsMap)) throw new Error('流水链接所选账户不属于当前账本或已不可用，请返回重新选择');
        }
        if (initOptions.categoryId && initOptions.categoryId !== '0') {
            const category = transactionCategoriesStore.allTransactionCategoriesMap[initOptions.categoryId];
            const categoryType = initOptions.type ? transactionTypeToCategoryType(initOptions.type) : category?.type;
            if (!categoryType || !isMobileCategorySelectable(initOptions.categoryId, categoryType, transactionCategoriesStore.allTransactionCategoriesMap)) {
                throw new Error('流水链接所选分类已不可用或类型不匹配，请返回重新选择');
            }
        }
        if (initOptions.tagIds) {
            if (!canUseOwnerResources.value) throw new Error('标签属于账本所有者，此链接不能带入标签');
            const tagProblem = mobileTagSelectionProblem(initOptions.tagIds.split(','), transactionTagsStore.allTransactionTagsMap);
            if (tagProblem) throw new Error(tagProblem);
        }
        if (query['id'] && !responses[4]) {
            if (pageTypeAndMode.type === TransactionEditPageType.Transaction) {
                showToast('Unable to retrieve transaction');
                loadingError.value = 'Unable to retrieve transaction';
            } else if (pageTypeAndMode.type === TransactionEditPageType.Template) {
                showToast('Unable to retrieve template');
                loadingError.value = 'Unable to retrieve template';
            }

            return;
        }

        let fromTransaction: Transaction | TransactionTemplate | null = null;

        if (pageTypeAndMode.type === TransactionEditPageType.Transaction) {
            if (query['id'] && responses[4] instanceof Transaction) {
                fromTransaction = responses[4];
            } else if (query['templateId'] && transactionTemplatesStore.allTransactionTemplatesMap && transactionTemplatesStore.allTransactionTemplatesMap[TemplateType.Normal.type]) {
                fromTransaction = (transactionTemplatesStore.allTransactionTemplatesMap[TemplateType.Normal.type] as Record<string, TransactionTemplate>)[query['templateId']] ?? null;

                if (fromTransaction) {
                    addByTemplateId.value = fromTransaction.id;
                } else throw new Error('当前账本没有此模板，请返回模板列表重新读取');
            } else if (pendingTarget?.kind === 'transaction-create' && pendingTarget.recovery === 'draft') {
                // An explicit recovery entry restores the exact saved draft
                // even when automatic draft saving is currently disabled.
                const draft = inspectUserTransactionDraft(editLedgerId)!;
                openedTransactionDraftRevision = ledgerPendingDraftRevision(draft);
                fromTransaction = Transaction.ofDraft(draft);
                if (draft.clientSessionId) clientSessionId.value = draft.clientSessionId;
            } else if (query['noTransactionDraft'] !== 'true' && (settingsStore.appSettings.autoSaveTransactionDraft === 'enabled' || settingsStore.appSettings.autoSaveTransactionDraft === 'confirmation') && transactionDraftMatchesLedger(transactionsStore.transactionDraft, editLedgerId)) {
                const draft = inspectUserTransactionDraft(editLedgerId);
                if (!draft) throw new Error('原草稿已变化，请返回总览重新读取');
                openedTransactionDraftRevision = ledgerPendingDraftRevision(draft);
                fromTransaction = Transaction.ofDraft(draft);
                if (draft.clientSessionId) clientSessionId.value = draft.clientSessionId;
            }
        } else if (pageTypeAndMode.type === TransactionEditPageType.Template && responses[4] instanceof TransactionTemplate) {
            if (query['id']) {
                fromTransaction = responses[4];
            }
        }

        setTransactionModel(
            fromTransaction,
            initOptions,
            pageTypeAndMode.type === TransactionEditPageType.Transaction && (mode.value === TransactionEditPageMode.Edit || mode.value === TransactionEditPageMode.View)
        );
        transaction.value.tagIds = [...transaction.value.tagIds];
        if (pageTypeAndMode.type === TransactionEditPageType.Transaction && query['templateId'] && !canUseOwnerResources.value && transaction.value.tagIds.length) {
            const accepted = await new Promise<boolean>(resolve => showConfirm('此模板含账本所有者的标签。作为新流水应用时须去除这些标签；原模板保持不变。去除标签并继续？', () => resolve(true), () => resolve(false)));
            if (generation !== loadGeneration || ledgerContextChanged.value) return;
            if (!accepted) { props.f7router.back(); return; }
            transaction.value.tagIds = [];
        }
        if (!fromTransaction && transaction.value.type === TransactionType.Transfer) {
            if (initOptions.accountId === '0') transaction.value.sourceAccountId = '0';
            if (initOptions.destinationAccountId === '0') transaction.value.destinationAccountId = '0';
        }
        if (fromTransaction && !query['id'] && !query['templateId'] && (openedTransactionDraftRevision !== undefined || transactionDraftMatchesLedger(transactionsStore.transactionDraft, editLedgerId))) {
            if (openedTransactionDraftRevision !== undefined || transactionsStore.transactionDraft?.time !== undefined) {
                transaction.value.time = fromTransaction.time;
                transaction.value.timeZone = fromTransaction.timeZone;
                transaction.value.utcOffset = fromTransaction.utcOffset;
            }
            transaction.value.setGeoLocation(fromTransaction.geoLocation);
        }
        if (fromTransaction && mode.value === TransactionEditPageMode.Edit) {
            originalTagIds = [...fromTransaction.tagIds];
            originalPictureIds = fromTransaction.getPictureIds();
            if (!canWriteLedger.value || !fromTransaction.editable) mode.value = TransactionEditPageMode.View;
        }
        if (openedTransactionDraftRevision === undefined && query['noTransactionDraft'] !== 'true' && !query['id'] && !query['templateId'] && transactionDraftMatchesLedger(transactionsStore.transactionDraft, editLedgerId)) {
            clientSessionId.value = transactionsStore.transactionDraft?.clientSessionId || clientSessionId.value;
        }
        if (!canUseOwnerResources.value && duplicateFromId.value && (transaction.value.tagIds.length || transaction.value.getPictureIds().length)) {
            const accepted = await new Promise<boolean>(resolve => showConfirm('复制为新流水时不能带入账本所有者的标签或附件。去除这些字段并继续复制？', () => resolve(true), () => resolve(false)));
            if (generation !== loadGeneration || ledgerContextChanged.value) return;
            if (!accepted) { props.f7router.back(); return; }
            transaction.value.tagIds = [];
            transaction.value.clearPictures();
        }
        if (canUseOwnerResources.value && duplicateFromId.value && transaction.value.getPictureIds().length) {
            const accepted = await new Promise<boolean>(resolve => showConfirm('原附件不能直接用于新流水。去除附件并继续复制？你可以为新流水重新上传附件。', () => resolve(true), () => resolve(false)));
            if (generation !== loadGeneration || ledgerContextChanged.value) return;
            if (!accepted) { props.f7router.back(); return; }
            transaction.value.clearPictures();
        }

        if (pageTypeAndMode.type === TransactionEditPageType.Transaction && query['id'] && responses[4] instanceof Transaction) {
            if (fromTransaction && query['withTime'] && query['withTime'] === 'true') {
                transaction.value.time = fromTransaction.time;
                transaction.value.timeZone = fromTransaction.timeZone;
                transaction.value.utcOffset = fromTransaction.utcOffset;
            }

            if (fromTransaction && query['withGeoLocation'] && query['withGeoLocation'] === 'true') {
                transaction.value.setGeoLocation(fromTransaction.geoLocation);
            }
        } else if (pageTypeAndMode.type === TransactionEditPageType.Template && query['id'] && responses[4] instanceof TransactionTemplate) {
            const template = responses[4];
            transaction.value.id = template.id;

            if (!(transaction.value instanceof TransactionTemplate)) {
                transaction.value = TransactionTemplate.createNewTransactionTemplate(transaction.value, editLedgerId);
            }

            (transaction.value as TransactionTemplate).fillFrom(template);
            transaction.value.editable = template.editable;
        }

        loading.value = false;
    } catch (error) {
        const failure = error as { stale?: boolean; processed?: boolean; message?: string };
        if (generation !== loadGeneration || ledgerContextChanged.value || failure.stale) return;
        loading.value = false;
        logger.error('failed to load essential data for editing transaction');
        loadingError.value = error instanceof Error ? error : new Error(failure.message || '无法读取当前账本流水，请重试');
        if (!failure.processed) showToast(failure.message || '无法读取当前账本流水，请重试');
    } finally {
        if (generation === loadGeneration) loading.value = false;
    }
}

function save(afterAction: AfterSaveAction): void {
    checkEditingSession();
    if (!pageActive.value) return;
    const actionCurrent = capturePageActionCurrent();
    if (isAIReview) { void aiReviewConfirmationPanel.value?.preview(); return; }
    if (isCreateTemplate.value) { void templateCreationPanel.value?.preview(); return; }
    if (loading.value || submitting.value || recognizing.value) return;
    if (ledgerContextChanged.value) { showToast('账本已切换，请返回原账本后重新打开'); return; }
    if (!canWriteLedger.value && !canReadPendingReceipt.value) { showToast('当前账本没有记账权限，请重新核对成员权限'); return; }
    if (loadingError.value && !pendingCreate.value) return;
    const router = props.f7router;

    if (mode.value === TransactionEditPageMode.View) {
        return;
    }

    const problemMessage = pendingCreate.value ? null : inputEmptyProblemMessage.value;

    if (problemMessage) {
        showAlert(problemMessage);
        return;
    }

    if (pageTypeAndMode?.type === TransactionEditPageType.Transaction && (mode.value === TransactionEditPageMode.Add || mode.value === TransactionEditPageMode.Edit)) {
        const doSubmit = async function () {
            if (submitting.value || !actionCurrent()) return;
            submitting.value = true;
            saveError.value = '';
            showLoading(() => submitting.value);
            try {
                await refreshLedgerRole();
                if (!actionCurrent()) return;
                if (mode.value === TransactionEditPageMode.Edit && !transaction.value.editable) throw new Error('此流水不可编辑');
                if (mode.value === TransactionEditPageMode.Add) {
                    const appearedPending = transactionsStore.getPendingTransactionCreate(editLedgerId);
                    const newlyObserved = !pendingCreate.value && !!appearedPending;
                    pendingCreate.value = retainOriginalPending(appearedPending);
                    if (pendingCreate.value) {
                        transaction.value = Transaction.ofCreateRequest(pendingCreate.value.request);
                        if (pendingCreate.value.pictures) transaction.value.setPictures(TransactionPicture.ofMulti([...pendingCreate.value.pictures]));
                        clientSessionId.value = pendingCreate.value.clientSessionId;
                    }
                    if (newlyObserved) {
                        saveError.value = '另一页面已有原请求，已显示原内容；请核对后明确重试。';
                        return;
                    }
                }
                if (!canWriteLedger.value && !canReadPendingReceipt.value) throw new Error('当前成员已失去记账权限，请保留草稿并核对成员权限');
                const selectedProblem = pendingCreate.value ? null : getSelectionProblem(transaction.value);
                if (selectedProblem) throw new Error(selectedProblem);
                const submittedKey = clientSessionId.value;
                const savedTransaction = await transactionsStore.saveTransaction({
                ledgerId: editLedgerId,
                transaction: transaction.value as Transaction,
                defaultCurrency: defaultCurrency.value,
                isEdit: mode.value === TransactionEditPageMode.Edit,
                clientSessionId: submittedKey,
                persistCreate: true,
                retrySubmission: pendingCreate.value ?? undefined,
                expectedDraftRevision: mode.value === TransactionEditPageMode.Add ? openedTransactionDraftRevision : undefined,
                isActionCurrent: actionCurrent
                });
                if (!actionCurrent()) return;
                if (mode.value === TransactionEditPageMode.Add) {
                    await transactionsStore.acknowledgePendingTransactionCreate(editLedgerId, savedTransaction.submissionClientSessionId ?? submittedKey, savedTransaction.id);
                    pendingCreate.value = null;
                    openedTransactionDraftRevision = undefined;
                }
                submitted.value = true;

                if (ledgerContextChanged.value) {
                    showToast('流水已保存至打开时的账本，请返回当前账本');
                    return;
                }
                if (mode.value === TransactionEditPageMode.Add && (afterAction === AfterSaveAction.StayWithNewTransaction || afterAction === AfterSaveAction.StayWithCurrentTransaction)) {
                    showToast('You have added a new transaction');
                    updateTransactionModelByAfterSaveAction(afterAction, getQueryTransactionOptions());
                    clientSessionId.value = generateRandomUUID();
                    submitted.value = false;
                } else {
                    if (mode.value === TransactionEditPageMode.Add) {
                        showToast('You have added a new transaction');
                    } else if (mode.value === TransactionEditPageMode.Edit) {
                        showToast('You have saved this transaction');
                    }

                    router.back();
                }
            } catch (error) {
                if (!actionCurrent()) return;
                const submission = (error as { submission?: PendingTransactionCreate }).submission;
                if (mode.value === TransactionEditPageMode.Add) {
                    try {
                        if (submission) pendingCreate.value = retainOriginalPending(submission);
                        pendingCreate.value = retainOriginalPending(transactionsStore.getPendingTransactionCreate(editLedgerId));
                        if (pendingCreate.value) {
                            transaction.value = Transaction.ofCreateRequest(pendingCreate.value.request);
                            if (pendingCreate.value.pictures) transaction.value.setPictures(TransactionPicture.ofMulti([...pendingCreate.value.pictures]));
                            clientSessionId.value = pendingCreate.value.clientSessionId;
                        }
                    } catch {
                        loadingError.value = new Error('本机待确认记录无法读取，请保留原记录后核对；不能另建替代请求');
                    }
                }
                const failure = error as { error?: { errorCode?: number }; processed?: boolean; message?: string; stale?: boolean };
                if (failure.stale || ledgerContextChanged.value) return;
                saveError.value = pendingCreate.value?.receipt ? '原记账已有创建回执，请重新读取原结果；不会再次新增。' : pendingCreate.value ? '这笔记账结果尚未确认，请重试原请求。' : failure.message || '流水保存失败，请保留输入后重试。';
                if (isDefaultLedger && failure.error && (failure.error.errorCode === KnownErrorCode.TransactionCannotCreateInThisTime || failure.error.errorCode === KnownErrorCode.TransactionCannotModifyInThisTime)) {
                    showConfirm('You have set this time range to prevent editing transactions. Would you like to change the editable transaction range to All?', () => {
                        submitting.value = true;
                        showLoading(() => submitting.value);

                        userStore.updateUserTransactionEditScope({
                            transactionEditScope: TransactionEditScopeType.All.type
                        }).then(() => {
                            submitting.value = false;
                            hideLoading();

                            showToast('Your editable transaction range has been set to All');
                        }).catch(error => {
                            submitting.value = false;
                            hideLoading();

                            if (!error.processed) {
                                showToast(error.message || error);
                            }
                        });
                    });
                } else if (!failure.processed) {
                    showToast(saveError.value);
                }
            } finally {
                submitting.value = false;
                hideLoading();
            }
        };

        const hasZeroAmount = transaction.value.type === TransactionType.Transfer && transferDirection.value === 'in'
            ? transaction.value.destinationAmount === 0 : transaction.value.sourceAmount === 0;
        if (!pendingCreate.value && hasZeroAmount) {
            showConfirm('Are you sure you want to save this transaction with a zero amount?', () => {
                doSubmit();
            });
        } else {
            doSubmit();
        }
    } else if (pageTypeAndMode?.type === TransactionEditPageType.Template && (mode.value === TransactionEditPageMode.Add || mode.value === TransactionEditPageMode.Edit)) {
        const original = transaction.value as TransactionTemplate;
        // Capture the entire form before the live membership request. New
        // drafts have no server ID yet, so do not parse them as response rows.
        const captured = TransactionTemplate.createNewTransactionTemplate(original, editLedgerId);
        captured.fillFrom(original);
        captured.id = original.id;
        const isEdit = mode.value === TransactionEditPageMode.Edit;
        const submittedKey = clientSessionId.value;
        submitting.value = true;
        saveError.value = '';
        showLoading(() => submitting.value);
        void (async () => {
            try {
                await refreshLedgerRole();
                if (!canWriteLedger.value) throw new Error('当前成员已失去模板管理权限，请保留输入后核对');
                const selectionProblem = getSelectionProblem(captured);
                if (selectionProblem) throw new Error(selectionProblem);
                await transactionTemplatesStore.saveTemplateContent({ template: captured, isEdit, clientSessionId: submittedKey });
                if (ledgerContextChanged.value) return;
                submitted.value = true;
                showToast(isEdit ? 'You have saved this template' : 'You have added a new template');
                router.back();
            } catch (error) {
                const failure = error as { stale?: boolean; processed?: boolean; message?: string; error?: { errorMessage?: string } };
                if (failure.stale || ledgerContextChanged.value) return;
                saveError.value = error instanceof Error ? error.message : failure.error?.errorMessage || failure.message || '模板保存失败，请保留输入并重新读取原模板核对结果。';
                if (!failure.processed) showToast(saveError.value);
            } finally {
                submitting.value = false;
                hideLoading();
            }
        })();
    }
}

function quickSave(): void {
    if (mode.value === TransactionEditPageMode.View) {
        return;
    }

    if (pageTypeAndMode?.type === TransactionEditPageType.Transaction && mode.value === TransactionEditPageMode.Add) {
        const quickAddActionType = settingsStore.appSettings.quickAddButtonActionInMobileTransactionEditPage;

        if (quickAddActionType === TransactionQuickAddButtonActionType.OpenMenu.type) {
            showQuickSavePopover.value = true;
            return;
        } else if (quickAddActionType === TransactionQuickAddButtonActionType.SaveAndAddNewTransaction.type) {
            save(AfterSaveAction.StayWithNewTransaction);
            return;
        } else if (quickAddActionType === TransactionQuickAddButtonActionType.SaveAndKeepCurrentData.type) {
            save(AfterSaveAction.StayWithCurrentTransaction);
            return;
        }
    }

    save(AfterSaveAction.GoBack);
}

function pasteAmount(type: 'sourceAmount' | 'destinationAmount'): void {
    if (mode.value === TransactionEditPageMode.View || !isSupportClipboard) {
        return;
    }

    navigator.clipboard.readText().then(text => {
        if (!text) {
            return;
        }

        const parsedAmount = parseAmountFromLocalizedNumerals(text);

        if (Number.isNaN(parsedAmount) || !Number.isFinite(parsedAmount)) {
            showToast('Cannot parse amount from clipboard');
            return;
        }

        if (parsedAmount < TRANSACTION_MIN_AMOUNT || parsedAmount > TRANSACTION_MAX_AMOUNT) {
            showToast('Numeric Overflow');
            return;
        }

        if (type === 'sourceAmount') {
            transaction.value.sourceAmount = parsedAmount;
        } else if (type === 'destinationAmount') {
            transaction.value.destinationAmount = parsedAmount;
        }
    }).catch(error => {
        logger.error('failed to read clipboard text', error);
        showToast('Unable to read clipboard text');
    });
}

function updateGeoLocation(forceUpdate: boolean): void {
    if (!isSupportGeoLocation) {
        logger.warn('this browser does not support geo location');

        if (forceUpdate) {
            showToast('Unable to retrieve current position');
        }
        return;
    }

    navigator.geolocation.getCurrentPosition(function (position) {
        if (!position || !position.coords) {
            logger.error('current position is null');
            geoLocationStatus.value = GeoLocationStatus.Error;

            if (forceUpdate) {
                showToast('Unable to retrieve current position');
            }

            return;
        }

        geoLocationStatus.value = GeoLocationStatus.Success;

        transaction.value.setLatitudeAndLongitude(position.coords.latitude, position.coords.longitude);
    }, function (err) {
        logger.error('cannot retrieve current position', err);
        geoLocationStatus.value = GeoLocationStatus.Error;

        if (forceUpdate) {
            showToast('Unable to retrieve current position');
        }
    });

    geoLocationStatus.value = GeoLocationStatus.Getting;
}

function clearGeoLocation(): void {
    geoLocationStatus.value = null;
    transaction.value.removeGeoLocation();
}

function showDateTimeDialog(sheetMode: string): void {
    if (mode.value === TransactionEditPageMode.View) {
        showTimeInDefaultTimezone.value = !showTimeInDefaultTimezone.value;
    } else {
        transactionDateTimeSheetMode.value = sheetMode;
        showTransactionDateTimeSheet.value = true;
    }
}

function showOpenPictureDialog(): void {
    if (!canAddTransactionPicture.value || submitting.value) {
        return;
    }

    pictureInput.value?.click();
}

function uploadPicture(file: File): void {
    if (!file || !canUseOwnerResources.value || editingLocked.value || mode.value === TransactionEditPageMode.View) {
        return;
    }

    uploadingPicture.value = true;
    submitting.value = true;

    compressJpgImageByQuality(file, imageUploadQualityType.value).then(blob => {
        if (ledgerContextChanged.value || !canUseOwnerResources.value || pendingCreate.value) throw new Error('账本已切换，附件上传已停止');
        return transactionsStore.uploadTransactionPicture({
            pictureFile: KnownFileType.JPG.createFileFromBlob(blob, "image")
        });
    }).then(response => {
        if (!ledgerContextChanged.value) transaction.value.addPicture(response);
        uploadingPicture.value = false;
        submitting.value = false;
    }).catch(error => {
        uploadingPicture.value = false;
        submitting.value = false;

        if (!error.processed) {
            showToast(error.message || error);
        }
    });
}

function viewOrRemovePicture(pictureInfo: TransactionPictureInfoBasicResponse): void {
    if (!canUseOwnerResources.value || ledgerContextChanged.value || pendingCreate.value) return;
    if (mode.value !== TransactionEditPageMode.Add && mode.value !== TransactionEditPageMode.Edit && transaction.value.pictures && transaction.value.pictures.length) {
        pictureBrowser.value?.open();
        return;
    }

    showConfirm('Are you sure you want to remove this transaction picture?', () => {
        if (!canUseOwnerResources.value || editingLocked.value) return;
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
                showToast(error.message || error);
            }

            removingPictureId.value = '';
            submitting.value = false;
        });
    });
}

function duplicate(withTime?: boolean, withGeoLocation?: boolean): void {
    if (!canWriteLedger.value || ledgerContextChanged.value || pendingCreate.value) return;
    props.f7router.navigate(`/transaction/add?ledgerId=${editLedgerId}&id=${transaction.value.id}&type=${transaction.value.type}&withTime=${withTime ?? false}&withGeoLocation=${withGeoLocation ?? false}`);
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

function onPageAfterIn(): void {
    checkEditingSession();
    const returning = !pageActive.value;
    if (returning) { pageGeneration++; pageActive.value = true; templatePageActive.value = true; }
    if (isAIReview) { aiPageGeneration++; aiPageActive.value = true; }
    if (ledgerContextChanged.value) return;
    if (returning) {
        // Revalidate the returned page without replacing unsaved manual input.
        // An interrupted initial load needs a new load ticket instead.
        if (loading.value) void init(false);
        else void revalidateReturnedPage();
        return;
    }
    void refreshLedgerRole().catch(() => { ledgerRole.value = null; });

    if (!isAIReview && !editingLocked.value && settingsStore.appSettings.autoGetCurrentGeoLocation && mode.value === TransactionEditPageMode.Add
        && !geoLocationStatus.value && !transaction.value.geoLocation) {
        updateGeoLocation(false);
    }
}
async function revalidateReturnedPage(): Promise<void> {
    const actionCurrent = capturePageActionCurrent();
    try {
        await refreshLedgerRole();
        if (!actionCurrent() || isAIReview || pageTypeAndMode?.type !== TransactionEditPageType.Transaction || mode.value !== TransactionEditPageMode.Add) return;
        const appearedPending = transactionsStore.getPendingTransactionCreate(editLedgerId);
        const newlyObserved = !pendingCreate.value && !!appearedPending;
        pendingCreate.value = retainOriginalPending(appearedPending);
        if (pendingCreate.value) {
            transaction.value = Transaction.ofCreateRequest(pendingCreate.value.request);
            if (pendingCreate.value.pictures) transaction.value.setPictures(TransactionPicture.ofMulti([...pendingCreate.value.pictures]));
            clientSessionId.value = pendingCreate.value.clientSessionId;
        }
        if (newlyObserved) saveError.value = '另一页面已有原请求，已显示原内容；请核对后明确重试。';
    } catch (reason) {
        if (!actionCurrent()) return;
        ledgerRole.value = null;
        loadingError.value = reason instanceof Error ? reason : new Error('无法核验返回页面的原账本状态，请重试');
    }
}

function onPageBeforeOut(): void {
    checkEditingSession();
    pageGeneration++; pageActive.value = false; ++loadGeneration;
    if (isAIReview) {
        const serial = ++aiPageGeneration;
        void aiReviewConfirmationPanel.value?.flush(false).catch(() => {}).finally(() => { if (serial === aiPageGeneration) aiPageActive.value = false; });
        return;
    }
    if (isCreateTemplate.value) {
        const serial = pageGeneration;
        // Keep local draft flushing active while the captured action ticket has
        // already revoked any permission/capability wait before its HTTP write.
        void templateCreationPanel.value?.flush().catch(() => {}).finally(() => { if (serial === pageGeneration) templatePageActive.value = false; });
        return;
    }
    if (pendingCreate.value || ledgerContextChanged.value || loading.value || loadingError.value || submitting.value) return;
    if (submitted.value || pageTypeAndMode?.type !== TransactionEditPageType.Transaction || mode.value !== TransactionEditPageMode.Add || query['noTransactionDraft'] === 'true' || addByTemplateId.value || duplicateFromId.value) {
        return;
    }

    const initAmount: number | undefined = query['amount'] ? parseInt(query['amount']) : undefined;

    if (settingsStore.appSettings.autoSaveTransactionDraft === 'confirmation') {
        if (transactionsStore.isTransactionDraftModified(transaction.value, initAmount, query['categoryId'], query['accountId'], query['tagIds'], firstVisibleAccountId.value)) {
            showConfirm('Do you want to save this transaction draft?', () => {
                checkEditingSession();
                if (ledgerContextChanged.value || transactionsStore.getPendingTransactionCreate(editLedgerId)) return;
                transactionsStore.saveTransactionDraft(transaction.value, initAmount, query['categoryId'], query['accountId'], query['tagIds'], firstVisibleAccountId.value, editLedgerId, clientSessionId.value);
            }, () => {
                checkEditingSession();
                if (ledgerContextChanged.value || transactionsStore.getPendingTransactionCreate(editLedgerId)) return;
                transactionsStore.clearTransactionDraft(editLedgerId);
            });
        } else {
            transactionsStore.clearTransactionDraft(editLedgerId);
        }
    } else if (settingsStore.appSettings.autoSaveTransactionDraft === 'enabled') {
        transactionsStore.saveTransactionDraft(transaction.value, initAmount, query['categoryId'], query['accountId'], query['tagIds'], firstVisibleAccountId.value, editLedgerId, clientSessionId.value);
    }
}

watch(() => [ledgersStore.selectedLedgerId, userStore.currentUserBasicInfo?.username], () => {
    ++loadGeneration;
    contextInvalidated.value = true;
    ledgerRole.value = null;
    showMoreActionSheet.value = false;
    showQuickSavePopover.value = false;
    showTransactionTagSheet.value = false;
}, { flush: 'sync' });

function checkEditingSession(): void {
    if (editIdentity === getTransactionStateIdentity() && editUsername === getCurrentUserInfo()?.username && isUserLogined()) return;
    contextInvalidated.value = true;
    ++loadGeneration;
    ledgerRole.value = null;
    showMoreActionSheet.value = false;
    showQuickSavePopover.value = false;
    showTransactionTagSheet.value = false;
}
window.addEventListener('storage', checkEditingSession);
window.addEventListener('focus', checkEditingSession);
document.addEventListener('visibilitychange', checkEditingSession);
onUnmounted(() => {
    pageGeneration++; pageActive.value = false; templatePageActive.value = false; ++loadGeneration;
    window.removeEventListener('storage', checkEditingSession);
    window.removeEventListener('focus', checkEditingSession);
    document.removeEventListener('visibilitychange', checkEditingSession);
});

init();
</script>

<style>
.mobile-ai-confirmation { --finexy-dialog-panel: var(--f7-page-bg-color); overflow-wrap: anywhere; }
.mobile-ai-preview { min-height: 44px; padding: 8px 16px; border: 1px solid currentColor; border-radius: 12px; color: var(--f7-text-color); background: var(--f7-block-strong-bg-color); font: inherit; }
.mobile-transaction-edit-navbar .navbar-inner {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    gap: 8px;
}

.mobile-transaction-edit-navbar .title {
    min-width: 0;
    width: 100%;
    margin: 0;
    left: 0 !important;
    text-align: center;
}

.mobile-transaction-edit-navbar .left,
.mobile-transaction-edit-navbar .right {
    margin-inline: 0;
}

.mobile-transaction-edit-navbar .link {
    min-width: 44px;
    min-height: 44px;
}

.mobile-transaction-direction .button {
    min-height: 44px;
}

.mobile-transaction-edit-page .button {
    min-height: 44px;
}

.mobile-transaction-edit-page .block > .link {
    display: inline-flex;
    align-items: center;
    min-width: 44px;
    min-height: 44px;
}

.category-separate-icon.icon {
    margin-inline-start: 5px;
    margin-inline-end: 5px;
    font-size: var(--ebk-category-separate-icon-font-size);
    line-height: 16px;
    color: var(--f7-color-gray-tint);
}

.transaction-edit-amount {
    line-height: 53px;
}

.transaction-edit-amount .item-title {
    font-weight: bolder;
}

.transaction-edit-amount .item-header {
    padding-top: calc(var(--f7-typography-padding) / 2);
}

.transaction-edit-datetime .item-title {
    width: 100%;
}

.transaction-edit-datetime .item-title > .item-header > .transaction-edit-datetime-header {
    display: block;
    width: 100%;
}

.transaction-edit-datetime .item-title > .transaction-edit-datetime-title {
    display: flex;
    width: 100%;
}

.transaction-edit-datetime .item-title > .transaction-edit-datetime-title > .transaction-edit-datetime-time {
    flex-grow: 1;
    overflow: hidden;
    text-overflow: ellipsis;
}

.transaction-edit-timezone-name {
    padding-inline-start: 4px;
}

.transaction-edit-tag {
    --f7-chip-bg-color: var(--ebk-transaction-tag-chip-bg-color);
    margin-inline-end: 4px;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
}

.chip.transaction-edit-tag .chip-media+.chip-label {
    margin-inline-start: 0;
}

.chip.transaction-edit-tag .chip-media i.icon {
    font-size: calc(var(--f7-chip-media-size) - 12px);
    height: calc(var(--f7-chip-media-size) - 12px);
}

.transaction-pictures {
    height: var(--ebk-transaction-picture-size);
}

.transaction-pictures .transaction-picture-container {
    width: var(--ebk-transaction-picture-size);
    height: var(--ebk-transaction-picture-size);
}

.transaction-pictures .transaction-picture-container .transaction-picture {
    width: 100%;
    height: 100%;
}

.transaction-pictures .transaction-picture-container .transaction-picture .transaction-picture-control-backdrop {
    width: 100%;
    height: 100%;
    position: absolute;
    z-index: 10;
    background-color: rgba(0, 0, 0, 0.4);
    border-radius: 8px;
}

.transaction-pictures .transaction-picture-container .transaction-picture .picture-control-icon {
    z-index: 15;
    font-size: var(--ebk-transaction-picture-add-icon-size);
}

.transaction-pictures .transaction-picture-container .transaction-picture .picture-remove-icon {
    background-color: transparent;
    color: rgba(255, 255, 255, 0.8);
    font-size: var(--ebk-transaction-picture-remove-icon-size);
}

.transaction-pictures .transaction-picture-container .transaction-picture img {
    object-fit: cover;
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    border-radius: 8px;
}

.transaction-pictures .transaction-picture-add {
    width: calc(var(--ebk-transaction-picture-size) - 2px);
    height: calc(var(--ebk-transaction-picture-size) - 4px);
    border: 2px dashed #ccc;
    border-radius: 8px;
}
</style>
