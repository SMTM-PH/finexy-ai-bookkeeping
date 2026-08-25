<template>
    <div class="layout-wrapper">
        <router-link to="/">
            <div class="auth-logo d-flex align-start gap-x-3">
                <img alt="logo" class="login-page-logo" :src="APPLICATION_LOGO_PATH" />
                <h1 class="font-weight-medium leading-normal text-2xl">{{ tt('global.app.title') }}</h1>
            </div>
        </router-link>
        <v-row no-gutters class="auth-wrapper">
            <v-col cols="12" md="8" class="auth-image-background d-none d-md-flex align-center justify-center position-relative">
                <div class="auth-brand-statement">
                    <span>AIR LEDGER</span>
                    <h2>让每一笔钱<br>都有清晰去向。</h2>
                    <p>记录日常、理解支出，也看见物品随时间留下的真实价值。</p>
                </div>
                <div class="auth-hero-media" aria-hidden="true">
                    <v-img cover src="/img/desktop/finexy-login-hero.png" />
                </div>
            </v-col>
            <v-col cols="12" md="4" class="auth-card d-flex flex-column">
                <div class="d-flex align-center justify-center h-100">
                    <v-card variant="flat" class="w-100 mt-0 px-4 pt-12" max-width="500">
                        <div v-if="isSwitchingAccount" class="switch-account-notice">
                            <div>
                                <strong>切换账号</strong>
                                <span>新账号登录成功前，当前账号会保持登录。</span>
                            </div>
                            <router-link to="/">返回</router-link>
                        </div>
                        <v-card-text>
                            <h4 class="text-h4 mb-2">{{ isSwitchingAccount ? '登录其他账号' : tt('Welcome to ezBookkeeping') }}</h4>
                            <p class="mb-0" v-if="isInternalAuthEnabled()">{{ isSwitchingAccount ? '请输入要切换到的账号信息' : tt('Please log in with your ezBookkeeping account') }}</p>
                            <p class="mt-1 mb-0" v-if="tips">{{ tips }}</p>
                        </v-card-text>

                        <v-card-text class="pb-0 mb-6">
                            <v-form>
                                <v-row>
                                    <v-col cols="12" v-if="isInternalAuthEnabled()">
                                        <v-text-field
                                            persistent-placeholder
                                            type="text"
                                            autocomplete="username"
                                            autocapitalize="none"
                                            autocorrect="off"
                                            spellcheck="false"
                                            inputmode="email"
                                            :autofocus="true"
                                            :disabled="show2faInput || loggingInByPassword || loggingInByOAuth2 || verifying"
                                            :label="tt('Username')"
                                            :placeholder="tt('Your username or email')"
                                            v-model.trim="username"
                                            @input="tempToken = ''"
                                            @keyup.enter="passwordInput?.focus()"
                                        />
                                    </v-col>

                                    <v-col cols="12" v-if="isInternalAuthEnabled()">
                                        <v-text-field
                                            persistent-placeholder
                                            autocomplete="current-password"
                                            ref="passwordInput"
                                            type="password"
                                            :disabled="show2faInput || loggingInByPassword || loggingInByOAuth2 || verifying"
                                            :label="tt('Password')"
                                            :placeholder="tt('Your password')"
                                            v-model="password"
                                            @input="tempToken = ''"
                                            @keyup.enter="login"
                                        />
                                    </v-col>

                                    <v-col cols="12" v-show="show2faInput">
                                        <v-text-field
                                            persistent-placeholder
                                            type="number"
                                            autocomplete="one-time-code"
                                            ref="passcodeInput"
                                            :disabled="loggingInByPassword || loggingInByOAuth2 || verifying"
                                            :label="tt('Passcode')"
                                            :placeholder="tt('Passcode')"
                                            :append-inner-icon="mdiHelpCircleOutline"
                                            v-model="passcode"
                                            @click:append-inner="twoFAVerifyType = 'backupcode'"
                                            @keyup.enter="verify"
                                            v-if="twoFAVerifyType === 'passcode'"
                                        />
                                        <v-text-field
                                            persistent-placeholder
                                            type="text"
                                            :disabled="loggingInByPassword || loggingInByOAuth2 || verifying"
                                            :label="tt('Backup Code')"
                                            :placeholder="tt('Backup Code')"
                                            :append-inner-icon="mdiOnepassword"
                                            v-model="backupCode"
                                            @click:append-inner="twoFAVerifyType = 'passcode'"
                                            @keyup.enter="verify"
                                            v-if="twoFAVerifyType === 'backupcode'"
                                        />
                                    </v-col>

                                    <v-col cols="12" class="py-0 mt-1 mb-4">
                                        <div class="d-flex align-center justify-space-between flex-wrap">
                                            <a href="javascript:void(0);"
                                               :class="{ 'disabled': loggingInByPassword || loggingInByOAuth2 || verifying }"
                                               @click="showMobileQrCode = true">
                                                <span class="nav-item-title">{{ tt('Use on Mobile Device') }}</span>
                                            </a>
                                            <v-spacer/>
                                            <router-link class="text-primary" to="/forgetpassword"
                                                         :class="{ 'disabled': !isUserForgetPasswordEnabled() || loggingInByPassword || loggingInByOAuth2 || verifying }">
                                                {{ tt('Forget Password?') }}
                                            </router-link>
                                        </div>
                                    </v-col>

                                    <v-col cols="12">
                                        <v-btn block :disabled="inputIsEmpty || loggingInByPassword || loggingInByOAuth2 || verifying"
                                               @click="login" v-if="isInternalAuthEnabled() && !show2faInput">
                                            {{ tt('Log In') }}
                                            <v-progress-circular indeterminate size="22" class="ms-2" v-if="loggingInByPassword"></v-progress-circular>
                                        </v-btn>
                                        <v-btn block :disabled="twoFAInputIsEmpty || loggingInByPassword || loggingInByOAuth2 || verifying"
                                               @click="verify" v-else-if="isInternalAuthEnabled() && show2faInput">
                                            {{ tt('Continue') }}
                                            <v-progress-circular indeterminate size="22" class="ms-2" v-if="verifying"></v-progress-circular>
                                        </v-btn>

                                        <v-col cols="12" class="d-flex align-center px-0 text-no-wrap" v-if="isInternalAuthEnabled() && isOAuth2Enabled()">
                                            <v-divider class="me-3" />
                                            {{ tt('or') }}
                                            <v-divider class="ms-3" />
                                        </v-col>

                                        <v-btn block :disabled="show2faInput || loggingInByPassword || loggingInByOAuth2 || verifying" :href="oauth2LoginUrl"
                                               @click="loggingInByOAuth2 = true" v-if="isOAuth2Enabled()">
                                            {{ oauth2LoginDisplayName }}
                                            <v-progress-circular indeterminate size="22" class="ms-2" v-if="loggingInByOAuth2"></v-progress-circular>
                                        </v-btn>
                                    </v-col>

                                    <v-col cols="12" class="text-center text-base" v-if="isInternalAuthEnabled()">
                                        <span class="me-1">{{ tt('Don\'t have an account?') }}</span>
                                        <router-link class="text-primary" to="/signup"
                                                     :class="{ 'disabled': !isUserRegistrationEnabled() || loggingInByPassword || loggingInByOAuth2 || verifying }">
                                            {{ tt('Create an account') }}
                                        </router-link>
                                    </v-col>
                                </v-row>
                            </v-form>
                        </v-card-text>
                    </v-card>
                </div>
                <v-spacer/>
                <div class="d-flex align-center justify-center">
                    <v-card variant="flat" class="w-100 px-4 pb-4" max-width="500">
                        <v-card-text class="pt-0">
                            <v-row>
                                <v-col cols="12" class="text-center">
                                    <language-select-button :disabled="loggingInByPassword || loggingInByOAuth2 || verifying" />
                                </v-col>

                                <v-col cols="12" class="d-flex align-center pt-0">
                                    <v-divider />
                                </v-col>

                                <v-col cols="12" class="text-center text-sm">
                                    <span>Powered by </span>
                                    <a href="https://github.com/mayswind/ezbookkeeping" target="_blank">园园</a>&nbsp;<span>{{ version }}</span>
                                </v-col>
                            </v-row>
                        </v-card-text>
                    </v-card>
                </div>
            </v-col>
        </v-row>

        <switch-to-mobile-dialog v-model:show="showMobileQrCode" />
        <snack-bar ref="snackbar" />
    </div>
</template>

<script setup lang="ts">
import { VTextField } from 'vuetify/components/VTextField';
import SnackBar from '@/components/desktop/SnackBar.vue';

import { ref, computed, useTemplateRef, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useI18n } from '@/locales/helpers.ts';
import { useLoginPageBase } from '@/views/base/LoginPageBase.ts';

import { useRootStore } from '@/stores/index.ts';

import { APPLICATION_LOGO_PATH } from '@/consts/asset.ts';
import { KnownErrorCode } from '@/consts/api.ts';

import { generateRandomUUID } from '@/lib/misc.ts';
import {
    isUserRegistrationEnabled,
    isUserForgetPasswordEnabled,
    isUserVerifyEmailEnabled,
    isInternalAuthEnabled,
    isOAuth2Enabled
} from '@/lib/server_settings.ts';

import {
    mdiOnepassword,
    mdiHelpCircleOutline
} from '@mdi/js';

type SnackBarType = InstanceType<typeof SnackBar>;

const router = useRouter();
const route = useRoute();

const { tt } = useI18n();

const rootStore = useRootStore();

const {
    version,
    username,
    password,
    passcode,
    backupCode,
    tempToken,
    twoFAVerifyType,
    oauth2ClientSessionId,
    loggingInByPassword,
    loggingInByOAuth2,
    verifying,
    inputIsEmpty,
    twoFAInputIsEmpty,
    oauth2LoginUrl,
    oauth2LoginDisplayName,
    tips,
    doAfterLogin
} = useLoginPageBase('desktop');

const passwordInput = useTemplateRef<VTextField>('passwordInput');
const passcodeInput = useTemplateRef<VTextField>('passcodeInput');
const snackbar = useTemplateRef<SnackBarType>('snackbar');

const show2faInput = ref<boolean>(false);
const showMobileQrCode = ref<boolean>(false);

const isSwitchingAccount = computed<boolean>(() => route.query['switch'] === '1');

function goToHomeAfterLogin(): void {
    const reloadAfterNavigation = isSwitchingAccount.value;

    router.replace('/').then(() => {
        if (reloadAfterNavigation) {
            window.location.reload();
        }
    });
}

function login(): void {
    if (!username.value) {
        snackbar.value?.showMessage('Username cannot be blank');
        return;
    }

    if (!password.value) {
        snackbar.value?.showMessage('Password cannot be blank');
        return;
    }

    if (tempToken.value) {
        show2faInput.value = true;
        return;
    }

    if (loggingInByPassword.value) {
        return;
    }

    loggingInByPassword.value = true;

    rootStore.authorize({
        loginName: username.value,
        password: password.value
    }).then(authResponse => {
        loggingInByPassword.value = false;

        if (authResponse.need2FA) {
            tempToken.value = authResponse.token;
            show2faInput.value = true;

            nextTick(() => {
                if (passcodeInput.value) {
                    passcodeInput.value.focus();
                    passcodeInput.value.select();
                }
            });

            return;
        }

        doAfterLogin(authResponse);
        goToHomeAfterLogin();
    }).catch(error => {
        loggingInByPassword.value = false;

        if (isUserVerifyEmailEnabled() && error.error && error.error.errorCode === KnownErrorCode.UserEmailNotVerified && error.error.context && error.error.context.email) {
            router.push(`/verify_email?email=${encodeURIComponent(error.error.context.email)}&emailSent=${error.error.context.hasValidEmailVerifyToken || false}`);
            return;
        }

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function verify(): void {
    if (twoFAInputIsEmpty.value || verifying.value) {
        return;
    }

    if (twoFAVerifyType.value === 'passcode' && !passcode.value) {
        snackbar.value?.showMessage('Passcode cannot be blank');
        return;
    } else if (twoFAVerifyType.value === 'backupcode' && !backupCode.value) {
        snackbar.value?.showMessage('Backup code cannot be blank');
        return;
    }

    verifying.value = true;

    rootStore.authorize2FA({
        token: tempToken.value,
        passcode: twoFAVerifyType.value === 'passcode' ? passcode.value : null,
        recoveryCode: twoFAVerifyType.value === 'backupcode' ? backupCode.value : null
    }).then(authResponse => {
        verifying.value = false;

        doAfterLogin(authResponse);
        goToHomeAfterLogin();
    }).catch(error => {
        verifying.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

oauth2ClientSessionId.value = generateRandomUUID();
</script>

<style scoped>
.auth-logo h1 {
    color: #f7f8fb !important;
    font-weight: 700 !important;
    text-shadow: 0 1px 18px rgba(0, 0, 0, .35);
    opacity: 1 !important;
}

.auth-image-background {
    isolation: isolate;
    background: #111317 !important;
}

.auth-image-background::before,
.auth-image-background::after {
    display: none !important;
}

.auth-hero-media {
    position: absolute;
    z-index: 0;
    inset: 0;
    overflow: hidden;
    pointer-events: none;
}

.auth-hero-media::after {
    content: "";
    position: absolute;
    inset: 0;
    background: linear-gradient(90deg, rgba(12, 14, 18, .94) 0%, rgba(12, 14, 18, .66) 42%, rgba(12, 14, 18, .08) 78%, transparent 100%);
}

.auth-hero-media :deep(.v-img) {
    width: 100%;
    height: 100%;
}

.auth-hero-media :deep(.v-img__img) {
    object-position: center center;
}

.auth-brand-statement {
    z-index: 2;
    color: #fff;
}

.auth-brand-statement span {
    color: #ff8268;
}

.auth-brand-statement h2 {
    color: #fff !important;
    text-shadow: 0 2px 24px rgba(0, 0, 0, .3);
}

.auth-brand-statement p {
    color: #d3d8e2 !important;
    font-size: 17px;
    font-weight: 500;
    line-height: 1.7;
}

.auth-card {
    color: #12141a !important;
    background: rgba(255, 255, 255, .97) !important;
}

.auth-card :deep(.v-card),
.auth-card :deep(.v-card-text) {
    color: #12141a !important;
}

.auth-card :deep(h4) {
    color: #12141a !important;
    font-size: 30px !important;
    font-weight: 800 !important;
    line-height: 1.25;
    letter-spacing: -.035em !important;
    opacity: 1 !important;
}

.auth-card :deep(.v-card-text > p) {
    color: #475467 !important;
    font-size: 14px;
    font-weight: 500;
    line-height: 1.65;
    opacity: 1 !important;
}

.auth-card :deep(.v-field) {
    min-height: 56px;
    color: #12141a !important;
    border-radius: 14px !important;
    background: #fff !important;
    box-shadow: 0 1px 2px rgba(16, 24, 40, .04) !important;
}

.auth-card :deep(.v-field__outline) {
    --v-field-border-opacity: 1;
    color: #cfd5df !important;
}

.auth-card :deep(.v-field--focused .v-field__outline) {
    color: #f05537 !important;
}

.auth-card :deep(.v-field--focused) {
    transform: none;
    box-shadow: 0 0 0 4px rgba(240, 85, 55, .11) !important;
}

.auth-card :deep(.v-label),
.auth-card :deep(.v-field-label) {
    color: #344054 !important;
    font-size: 14px !important;
    font-weight: 700 !important;
    opacity: 1 !important;
}

.auth-card :deep(.v-field__input),
.auth-card :deep(input) {
    color: #101828 !important;
    font-size: 15px !important;
    font-weight: 550;
    opacity: 1 !important;
}

.auth-card :deep(input::placeholder) {
    color: #667085 !important;
    opacity: 1 !important;
}

.auth-card :deep(a) {
    color: #c83f28 !important;
    font-size: 14px;
    font-weight: 650;
    opacity: 1 !important;
}

.auth-card :deep(.v-btn) {
    min-height: 52px;
    color: #fff !important;
    border-radius: 14px !important;
    background: #12141a !important;
    font-size: 15px;
    font-weight: 750;
    opacity: 1 !important;
}

.auth-card :deep(.v-btn:hover:not(:disabled)) {
    background: #f05537 !important;
}

.auth-card :deep(.v-btn:disabled) {
    color: #667085 !important;
    background: #e4e7ec !important;
}

.auth-card :deep(.v-divider) {
    border-color: #d0d5dd !important;
    opacity: 1;
}

.auth-card :deep(.text-sm),
.auth-card :deep(.text-base),
.auth-card :deep(.text-center > span) {
    color: #667085 !important;
    opacity: 1 !important;
}

.switch-account-notice {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin: 0 16px 8px;
    padding: 14px 16px;
    border: 1px solid #eceef2;
    border-radius: 14px;
    background: #f7f8fa;
}
.switch-account-notice div { display: grid; gap: 3px; }
.switch-account-notice strong { color: #12141a; font-size: 14px; }
.switch-account-notice span { color: #475467; font-size: 13px; }
.switch-account-notice a {
    flex: none;
    padding: 7px 13px;
    border-radius: 999px;
    color: #12141a;
    background: #fff;
    font-size: 12px;
    font-weight: 700;
}
</style>
