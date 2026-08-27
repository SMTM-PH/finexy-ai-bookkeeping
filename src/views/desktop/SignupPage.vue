<template>
    <div class="layout-wrapper signup-page">
        <router-link to="/">
            <div class="auth-logo d-flex align-start gap-x-3">
                <img alt="logo" class="login-page-logo" :src="APPLICATION_LOGO_PATH" />
                <h1 class="font-weight-medium leading-normal text-2xl">{{ tt('global.app.title') }}</h1>
            </div>
        </router-link>
        <v-row no-gutters class="auth-wrapper signup-wrapper">
            <v-col cols="12" md="5" class="auth-image-background signup-visual d-none d-md-flex position-relative">
                <div class="signup-hero-media" aria-hidden="true">
                    <v-img cover src="/img/desktop/finexy-login-hero.png" />
                </div>
                <div class="signup-brand-statement">
                    <span>YUANYUAN FINANCE</span>
                    <h2>从第一笔开始，<br>建立清晰的财务秩序。</h2>
                    <p>一个账号，一本属于你的个人账本。数据由你的自托管服务安全保存。</p>
                </div>
            </v-col>
            <v-col cols="12" md="7" class="auth-card signup-card d-flex align-center justify-center">
                <v-card variant="flat" class="signup-panel">
                    <div class="signup-toolbar">
                        <v-btn variant="text"
                               :disabled="submitting || navigateToHomePage"
                               :prepend-icon="mdiArrowLeft"
                               @click="navigateToLogin">返回登录</v-btn>
                    </div>
                    <steps-bar min-width="0" :steps="allSteps" :current-step="currentStep" @step:change="switchToTab" />

                    <v-window class="signup-window disable-tab-transition" v-model="currentStep">
                        <v-form>
                            <v-window-item value="basicSetting">
                                <h4 class="text-h4 mb-1">{{ tt('Basic Information') }}</h4>
                                <p class="text-sm mt-2 mb-5">
                                    <span>{{ tt('Already have an account?') }}</span>
                                    <router-link class="ms-1" to="/login">{{ tt('Click here to log in') }}</router-link>
                                </p>
                                <v-row>
                                    <v-col cols="12" md="6">
                                        <v-text-field
                                            type="text"
                                            autocomplete="username"
                                            autocapitalize="none"
                                            autocorrect="off"
                                            spellcheck="false"
                                            inputmode="email"
                                            :disabled="submitting || navigateToHomePage"
                                            :label="tt('Username')"
                                            :placeholder="tt('Your username')"
                                            v-model="user.username"
                                        />
                                    </v-col>

                                    <v-col cols="12" md="6">
                                        <v-text-field
                                            type="text"
                                            autocomplete="nickname"
                                            :disabled="submitting || navigateToHomePage"
                                            :label="tt('Nickname')"
                                            :placeholder="tt('Your nickname')"
                                            v-model="user.nickname"
                                        />
                                    </v-col>
                                </v-row>
                                <v-row>
                                    <v-col cols="12" md="12">
                                        <v-text-field
                                            type="email"
                                            autocomplete="email"
                                            :disabled="submitting || navigateToHomePage"
                                            :label="tt('E-mail')"
                                            :placeholder="tt('Your email address')"
                                            v-model="user.email"
                                        />
                                    </v-col>
                                </v-row>
                                <v-row>
                                    <v-col cols="12" md="6">
                                        <v-text-field
                                            autocomplete="new-password"
                                            type="password"
                                            :disabled="submitting || navigateToHomePage"
                                            :label="tt('Password')"
                                            :placeholder="tt('Your password, at least 6 characters')"
                                            v-model="user.password"
                                        />
                                    </v-col>
                                    <v-col cols="12" md="6">
                                        <v-text-field
                                            autocomplete="new-password"
                                            type="password"
                                            :disabled="submitting || navigateToHomePage"
                                            :label="tt('Confirm Password')"
                                            :placeholder="tt('Re-enter the password')"
                                            v-model="user.confirmPassword"
                                        />
                                    </v-col>
                                </v-row>

                                <v-row>
                                    <v-col cols="12" md="12">
                                        <language-select :disabled="submitting || navigateToHomePage"
                                                         :label="languageTitle"
                                                         :placeholder="languageTitle"
                                                         :use-model-value="true" v-model="currentLocale" />
                                    </v-col>
                                </v-row>

                                <v-row>
                                    <v-col cols="12" md="6">
                                        <currency-select :disabled="submitting || navigateToHomePage"
                                                         :label="tt('Default Currency')"
                                                         :placeholder="tt('Default Currency')"
                                                         v-model="user.defaultCurrency" />
                                    </v-col>

                                    <v-col cols="12" md="6">
                                        <v-select
                                            item-title="displayName"
                                            item-value="type"
                                            :disabled="submitting || navigateToHomePage"
                                            :label="tt('First Day of Week')"
                                            :placeholder="tt('First Day of Week')"
                                            :items="allWeekDays"
                                            v-model="user.firstDayOfWeek"
                                        />
                                    </v-col>
                                </v-row>
                            </v-window-item>

                            <v-window-item value="presetCategories" class="signup-preset-categories">
                                <h4 class="text-h4 mb-1">{{ tt('Preset Categories') }}</h4>
                                <p class="text-sm mt-2 mb-2">{{ tt('Set whether to use preset transaction categories') }}</p>

                                <v-row>
                                    <v-col cols="12" sm="6">
                                        <v-switch class="mb-2" :disabled="submitting || navigateToHomePage"
                                                  :label="tt('Use Preset Transaction Categories')"
                                                  v-model="usePresetCategories"/>
                                    </v-col>
                                    <v-col cols="12" sm="6" class="text-end-sm">
                                        <language-select-button :disabled="submitting || navigateToHomePage"
                                                                :use-model-value="true" v-model="currentLocale" />
                                    </v-col>
                                </v-row>

                                <div class="overflow-y-auto px-3" :class="{ 'disabled': !usePresetCategories || submitting || navigateToHomePage }" style="max-height: 323px">
                                    <v-row :key="categoryType" v-for="(categories, categoryType) in allPresetCategories">
                                        <v-col cols="12" md="12">
                                            <h4 class="mb-3">{{ getCategoryTypeName(parseInt(categoryType)) }}</h4>

                                            <v-expansion-panels class="border rounded" variant="accordion" multiple>
                                                <v-expansion-panel :key="idx" v-for="(category, idx) in categories">
                                                    <v-expansion-panel-title class="py-0">
                                                        <ItemIcon icon-type="category" :icon-id="category.icon" :color="category.color"></ItemIcon>
                                                        <span class="ms-3">{{ category.name }}</span>
                                                    </v-expansion-panel-title>
                                                    <v-expansion-panel-text v-if="category.subCategories.length">
                                                        <v-list rounded density="comfortable" class="pa-0">
                                                            <template :key="subIdx"
                                                                      v-for="(subCategory, subIdx) in category.subCategories">
                                                                <v-list-item>
                                                                    <template #prepend>
                                                                        <ItemIcon icon-type="category" :icon-id="subCategory.icon" :color="subCategory.color"></ItemIcon>
                                                                    </template>
                                                                    <span class="ms-3">{{ subCategory.name }}</span>
                                                                </v-list-item>
                                                                <v-divider v-if="subIdx !== category.subCategories.length - 1"/>
                                                            </template>
                                                        </v-list>
                                                    </v-expansion-panel-text>
                                                </v-expansion-panel>
                                            </v-expansion-panels>
                                        </v-col>
                                    </v-row>
                                </div>
                            </v-window-item>

                            <v-window-item value="finalResult" v-if="finalResultMessage">
                                <h4 class="text-h4 mb-1">{{ tt('Registration Completed') }}</h4>
                                <p class="my-5">{{ finalResultMessage }}</p>
                            </v-window-item>
                        </v-form>
                    </v-window>

                    <div class="signup-actions">
                        <v-btn class="button-icon-with-direction"
                               color="default"
                               :disabled="submitting || navigateToHomePage"
                               :prepend-icon="mdiArrowLeft"
                               v-if="currentStep === 'presetCategories'"
                               @click="switchToPreviousTab">{{ tt('Previous') }}</v-btn>
                        <v-btn class="button-icon-with-direction" color="primary"
                               :disabled="submitting || navigateToHomePage"
                               :append-icon="mdiArrowRight"
                               @click="switchToNextTab"
                               v-if="currentStep === 'basicSetting'">{{ tt('Next') }}</v-btn>
                        <v-btn color="teal"
                               :disabled="submitting || navigateToHomePage"
                               :append-icon="!submitting ? mdiCheck : undefined"
                               @click="submit"
                               v-if="currentStep === 'presetCategories'">
                            {{ tt('Submit') }}
                            <v-progress-circular indeterminate size="22" class="ms-2" v-if="submitting"></v-progress-circular>
                        </v-btn>
                        <v-btn class="button-icon-with-direction"
                               :append-icon="mdiArrowRight"
                               @click="navigateToLogin"
                               v-if="currentStep === 'finalResult'">{{ tt('Continue') }}</v-btn>
                    </div>
                </v-card>
            </v-col>
        </v-row>

        <snack-bar ref="snackbar" @update:show="onSnackbarShowStateChanged" />
    </div>
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';
import type { StepBarItem } from '@/components/desktop/StepsBar.vue';

import { ref, computed, useTemplateRef } from 'vue';
import { useRouter } from 'vue-router';

import { useI18n } from '@/locales/helpers.ts';
import { useSignupPageBase } from '@/views/base/SignupPageBase.ts';

import { useRootStore } from '@/stores/index.ts';

import type { TypeAndDisplayName } from '@/core/base.ts';
import { type LocalizedPresetCategory } from '@/core/category.ts';
import { APPLICATION_LOGO_PATH } from '@/consts/asset.ts';

import { categorizedArrayToPlainArray } from '@/lib/common.ts';
import { isUserLogined } from '@/lib/userstate.ts';

import {
    mdiArrowLeft,
    mdiArrowRight,
    mdiCheck
} from '@mdi/js';

type SnackBarType = InstanceType<typeof SnackBar>;

const router = useRouter();

const { tt, getAllWeekDays, getAllTransactionDefaultCategories } = useI18n();

const {
    user,
    submitting,
    languageTitle,
    currentLocale,
    inputEmptyProblemMessage,
    inputInvalidProblemMessage,
    getCategoryTypeName,
    doAfterSignupSuccess
} = useSignupPageBase();

const rootStore = useRootStore();

const snackbar = useTemplateRef<SnackBarType>('snackbar');

const currentStep = ref<string>('basicSetting');
const usePresetCategories = ref<boolean>(true);
const finalResultMessage = ref<string | null>(null);
const navigateToHomePage = ref<boolean>(false);

const allWeekDays = computed<TypeAndDisplayName[]>(() => getAllWeekDays());
const allPresetCategories = computed<Record<string, LocalizedPresetCategory[]>>(() => getAllTransactionDefaultCategories(0, currentLocale.value));
const allSteps = computed<StepBarItem[]>(() => {
    const allSteps = [
        {
            name: 'basicSetting',
            title: tt('User Information'),
            subTitle: tt('Basic Information')
        },
        {
            name: 'presetCategories',
            title: tt('Transaction Categories'),
            subTitle: tt('Preset Categories')
        }
    ];

    if (finalResultMessage.value) {
        allSteps.push({
            name: 'finalResult',
            title: tt('Complete'),
            subTitle: tt('Registration Completed')
        });
    }

    return allSteps;
});

function switchToTab(tabName: string): void {
    if (submitting.value || currentStep.value === 'finalResult' || navigateToHomePage.value) {
        return;
    }

    if (tabName === 'basicSetting') {
        currentStep.value = 'basicSetting';
    } else if (tabName === 'presetCategories') {
        const problemMessage = inputEmptyProblemMessage.value || inputInvalidProblemMessage.value;

        if (problemMessage) {
            snackbar.value?.showMessage(problemMessage);
            return;
        }

        currentStep.value = 'presetCategories';
    }
}

function switchToPreviousTab(): void {
    switchToTab('basicSetting');
}

function switchToNextTab(): void {
    switchToTab('presetCategories');
}

function submit(): void {
    const problemMessage = inputEmptyProblemMessage.value || inputInvalidProblemMessage.value;

    if (problemMessage) {
        snackbar.value?.showMessage(problemMessage);
        return;
    }

    navigateToHomePage.value = false;
    submitting.value = true;

    let presetCategories: LocalizedPresetCategory[] = [];

    if (usePresetCategories.value) {
        presetCategories = categorizedArrayToPlainArray(allPresetCategories.value);
    }

    rootStore.register({
        user: user.value,
        presetCategories: presetCategories
    }).then(response => {
        if (!isUserLogined()) {
            submitting.value = false;

            if (usePresetCategories.value && !response.presetCategoriesSaved) {
                finalResultMessage.value = tt('You have been successfully registered, but there was an failure when adding preset categories. You can re-add preset categories in settings page anytime.');
                currentStep.value = 'finalResult';
            } else if (response.needVerifyEmail) {
                finalResultMessage.value = tt('You have been successfully registered. An account activation link has been sent to your email address, please activate your account first.');
                currentStep.value = 'finalResult';
            } else {
                snackbar.value?.showMessage('You have been successfully registered');
                navigateToHomePage.value = true;
            }

            return;
        }

        doAfterSignupSuccess(response);
        submitting.value = false;

        if (usePresetCategories.value && !response.presetCategoriesSaved) {
            snackbar.value?.showMessage('You have been successfully registered, but there was an failure when adding preset categories. You can re-add preset categories in settings page anytime.');
        } else {
            snackbar.value?.showMessage('You have been successfully registered');
            router.replace('/');
        }

        navigateToHomePage.value = true;
    }).catch(error => {
        submitting.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function navigateToLogin(): void {
    router.push('/login');
}

function onSnackbarShowStateChanged(newValue: boolean): void {
    if (!newValue && navigateToHomePage.value) {
        router.replace('/');
    }
}
</script>

<style scoped>
.signup-page {
    min-height: 100dvh;
    color: #12141a;
    background: #f2f4f7;
    font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Segoe UI", system-ui, sans-serif;
    font-synthesis: none;
}

.signup-page .auth-logo {
    z-index: 5;
}

.signup-page .auth-logo h1 {
    color: #fff !important;
    font-weight: 750 !important;
    text-shadow: 0 2px 16px rgba(0, 0, 0, .45);
    opacity: 1 !important;
}

.signup-wrapper {
    min-height: 100dvh;
}

.signup-visual {
    isolation: isolate;
    align-items: flex-start;
    overflow: hidden;
    background: #101216 !important;
}

.signup-visual::before,
.signup-visual::after {
    display: none !important;
}

.signup-hero-media {
    position: absolute;
    z-index: 0;
    inset: 0;
    overflow: hidden;
}

.signup-hero-media::after {
    content: "";
    position: absolute;
    inset: 0;
    background:
        linear-gradient(180deg, rgba(8, 10, 13, .76) 0%, rgba(8, 10, 13, .14) 42%, rgba(8, 10, 13, .34) 100%),
        linear-gradient(90deg, rgba(8, 10, 13, .64) 0%, transparent 72%);
}

.signup-hero-media :deep(.v-img) {
    width: 100%;
    height: 100%;
}

.signup-hero-media :deep(.v-img__img) {
    object-position: 69% center;
}

.signup-brand-statement {
    position: relative;
    z-index: 2;
    max-width: 520px;
    margin: 154px 52px 0;
    color: #fff;
}

.signup-brand-statement > span {
    color: #ff8b72;
    font-size: 12px;
    font-weight: 850;
    letter-spacing: .18em;
}

.signup-brand-statement h2 {
    margin: 18px 0 13px;
    color: #fff !important;
    font-size: clamp(32px, 2.8vw, 52px);
    font-weight: 850;
    line-height: 1.16;
    letter-spacing: -.045em;
    text-shadow: 0 3px 28px rgba(0, 0, 0, .42);
}

.signup-brand-statement p {
    max-width: 480px;
    margin: 0;
    color: #e2e7ef !important;
    font-size: 15px;
    font-weight: 550;
    line-height: 1.75;
    text-shadow: 0 2px 14px rgba(0, 0, 0, .4);
}

.signup-card {
    flex: 0 0 58.333333%;
    width: 58.333333%;
    max-width: 58.333333%;
    min-width: 0;
    min-height: 100dvh;
    align-self: stretch;
    margin: 0;
    padding: 24px 34px;
    color: #12141a !important;
    border: 0;
    border-radius: 0 !important;
    background: #f2f4f7 !important;
    box-shadow: none !important;
    backdrop-filter: none;
    animation: none;
}

.signup-panel {
    width: min(100%, 800px);
    max-height: calc(100dvh - 48px);
    padding: 26px 38px 30px;
    overflow-y: auto;
    color: #12141a !important;
    border: 1px solid #e4e7ec;
    border-radius: 26px !important;
    background: #fff !important;
    box-shadow: 0 22px 65px rgba(16, 24, 40, .10) !important;
}

.signup-toolbar {
    display: flex;
    margin-bottom: 20px;
}

.signup-toolbar :deep(.v-btn) {
    min-height: 42px;
    padding-inline: 12px;
    color: #344054 !important;
    border-radius: 11px;
    background: #f2f4f7 !important;
    font-size: 14px;
    font-weight: 700;
}

.signup-window {
    margin-top: 20px;
}

.signup-panel :deep(.slide-group-with-stepper) {
    margin-bottom: 0 !important;
}

.signup-panel :deep(.slide-group-stepper-indicator) {
    background: #fff !important;
    border-color: #f05537 !important;
    opacity: .38;
}

.signup-panel :deep(.slide-group-stepper-link-line) {
    background: #f05537 !important;
    opacity: .26;
}

.signup-panel :deep(.slide-group-step-active .slide-group-stepper-indicator),
.signup-panel :deep(.slide-group-step-completed .slide-group-stepper-indicator),
.signup-panel :deep(.slide-group-step-completed .slide-group-stepper-link-line) {
    opacity: 1;
}

.signup-panel :deep(.step-number) {
    color: #344054 !important;
    font-size: 24px !important;
    font-weight: 800 !important;
    opacity: 1 !important;
}

.signup-panel :deep(.step-title) {
    color: #101828 !important;
    font-size: 13px !important;
    font-weight: 750 !important;
    line-height: 1.35;
    opacity: 1 !important;
}

.signup-panel :deep(.step-subtitle) {
    color: #667085 !important;
    font-size: 12px !important;
    line-height: 1.4;
    opacity: 1 !important;
}

.signup-panel :deep(h4.text-h4) {
    color: #12141a !important;
    font-size: 27px !important;
    font-weight: 850 !important;
    line-height: 1.25;
    letter-spacing: -.035em !important;
    opacity: 1 !important;
}

.signup-panel :deep(p),
.signup-panel :deep(p span) {
    color: #475467 !important;
    font-size: 14px;
    font-weight: 520;
    line-height: 1.6;
    opacity: 1 !important;
}

.signup-panel :deep(a) {
    color: #c83f28 !important;
    font-weight: 750;
    opacity: 1 !important;
}

.signup-panel :deep(.v-row) {
    margin-top: -5px;
    margin-bottom: -5px;
}

.signup-panel :deep(.v-col) {
    padding-top: 8px;
    padding-bottom: 8px;
}

.signup-panel :deep(.v-field) {
    min-height: 54px;
    color: #101828 !important;
    border-radius: 13px !important;
    background: #f8fafc !important;
    box-shadow: none !important;
}

.signup-panel :deep(.v-field__outline) {
    --v-field-border-opacity: 1;
    color: #d0d5dd !important;
}

.signup-panel :deep(.v-field--focused) {
    background: #fff !important;
    box-shadow: 0 0 0 4px rgba(240, 85, 55, .1) !important;
}

.signup-panel :deep(.v-field--focused .v-field__outline) {
    color: #f05537 !important;
}

.signup-panel :deep(.v-label),
.signup-panel :deep(.v-field-label) {
    color: #344054 !important;
    font-size: 14px !important;
    font-weight: 700 !important;
    opacity: 1 !important;
}

.signup-panel :deep(.v-field__input),
.signup-panel :deep(input),
.signup-panel :deep(.v-select__selection-text) {
    color: #101828 !important;
    font-size: 15px !important;
    font-weight: 550;
    opacity: 1 !important;
}

.signup-panel :deep(input::placeholder) {
    color: #667085 !important;
    opacity: 0 !important;
}

.signup-panel :deep(.v-field--focused input::placeholder) {
    opacity: 1 !important;
}

.signup-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    margin-top: 22px;
}

.signup-actions > :only-child {
    margin-left: auto;
}

.signup-actions :deep(.v-btn) {
    min-width: 130px;
    min-height: 48px;
    padding-inline: 20px;
    color: #fff !important;
    border-radius: 13px !important;
    background: #12141a !important;
    font-size: 14px;
    font-weight: 750;
    opacity: 1 !important;
}

.signup-actions :deep(.v-btn:hover:not(:disabled)) {
    background: #f05537 !important;
}

.signup-actions :deep(.v-btn:disabled) {
    color: #667085 !important;
    background: #e4e7ec !important;
}

.signup-preset-categories :deep(.v-expansion-panel-text__wrapper) {
    padding: 0 0 0 20px;
}

.signup-preset-categories :deep(.v-switch .v-label),
.signup-preset-categories :deep(.v-expansion-panel-title),
.signup-preset-categories :deep(.v-list-item) {
    color: #344054 !important;
    font-size: 14px !important;
    opacity: 1 !important;
}

@media (max-width: 1100px) {
    .signup-card { padding: 20px; }
    .signup-panel { padding: 24px 26px 28px; }
    .signup-brand-statement { margin-inline: 34px; }
}

@media (max-width: 959px) {
    .signup-page .auth-logo h1 { color: #12141a !important; text-shadow: none; }
    .signup-card { flex-basis: 100%; width: 100%; max-width: 100%; min-height: 100dvh; padding: 78px 18px 18px; }
    .signup-panel { max-height: none; padding: 22px 20px 26px; border-radius: 20px !important; }
}

@media (prefers-reduced-motion: reduce) {
    .signup-page * { transition-duration: .01ms !important; animation-duration: .01ms !important; }
}
</style>
