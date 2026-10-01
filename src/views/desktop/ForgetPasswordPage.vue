<template>
    <v-theme-provider theme="light" with-background class="password-recovery-page">
        <div class="layout-wrapper">
            <router-link to="/">
                <div class="auth-logo d-flex align-start gap-x-3">
                    <img alt="logo" class="login-page-logo" :src="APPLICATION_LOGO_PATH" />
                    <h1 class="font-weight-medium leading-normal text-2xl">{{ tt('global.app.title') }}</h1>
                </div>
            </router-link>
            <v-row no-gutters class="auth-wrapper">
                <v-col cols="12" md="8" class="auth-image-background d-none d-md-flex align-center justify-center position-relative">
                    <div class="d-flex auth-img-footer" v-if="!isDarkMode">
                        <v-img class="img-with-direction" src="img/desktop/background.svg"/>
                    </div>
                    <div class="d-flex auth-img-footer" v-if="isDarkMode">
                        <v-img class="img-with-direction" src="img/desktop/background-dark.svg"/>
                    </div>
                    <div class="d-flex align-center justify-center w-100 pt-10">
                        <v-img class="img-with-direction" max-width="600px" src="img/desktop/people4.svg" v-if="!isDarkMode"/>
                        <v-img class="img-with-direction" max-width="600px" src="img/desktop/people4-dark.svg" v-else-if="isDarkMode"/>
                    </div>
                </v-col>
                <v-col cols="12" md="4" class="auth-card d-flex flex-column">
                    <div class="d-flex align-center justify-center h-100">
                        <v-card variant="flat" class="w-100 mt-0 px-4 pt-12" max-width="500">
                            <v-card-text>
                                <h4 class="text-h4 mb-2">{{ tt('Forget Password?') }}</h4>
                                <p class="mb-0">{{ tt('Please enter your email address used for registration and we\'ll send you an email with a reset password link') }}</p>
                            </v-card-text>

                            <v-card-text class="pb-0 mb-6">
                                <v-form>
                                    <v-row>
                                        <v-col cols="12">
                                            <v-text-field
                                                type="email"
                                                autocomplete="email"
                                                :autofocus="true"
                                                :disabled="requesting"
                                                :label="tt('E-mail')"
                                                :placeholder="tt('Your email address')"
                                                v-model="email"
                                                @keyup.enter="requestResetPassword"
                                            />
                                        </v-col>

                                        <v-col cols="12">
                                            <v-btn block type="submit" :disabled="!email || requesting" @click="requestResetPassword">
                                                {{ tt('Send Reset Link') }}
                                                <v-progress-circular indeterminate size="22" class="ms-2" v-if="requesting"></v-progress-circular>
                                            </v-btn>
                                        </v-col>

                                        <v-col cols="12">
                                            <router-link class="d-flex align-center justify-center" to="/login"
                                                         :class="{ 'disabled': requesting }">
                                                <v-icon class="icon-with-direction" :icon="mdiChevronLeft"/>
                                                <span>{{ tt('Back to login page') }}</span>
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
                                        <language-select-button :disabled="requesting" />
                                    </v-col>

                                    <v-col cols="12" class="d-flex align-center pt-0">
                                        <v-divider />
                                    </v-col>

                                    <v-col cols="12" class="text-center text-sm">
                                        <span>Powered by </span>
                                        <a href="https://github.com/SMTM-PH/finexy-ai-bookkeeping" target="_blank">Finexy</a>&nbsp;<span>{{ version }}</span>
                                    </v-col>
                                </v-row>
                            </v-card-text>
                        </v-card>
                    </div>
                </v-col>
            </v-row>

            <snack-bar ref="snackbar" />
        </div>
    </v-theme-provider>
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';

import { ref, computed, useTemplateRef } from 'vue';
import { useTheme } from 'vuetify';

import { useI18n } from '@/locales/helpers.ts';

import { useRootStore } from '@/stores/index.ts';

import { ThemeType } from '@/core/theme.ts';
import { APPLICATION_LOGO_PATH } from '@/consts/asset.ts';

import { getClientDisplayVersion } from '@/lib/version.ts';

import {
    mdiChevronLeft
} from '@mdi/js';

type SnackBarType = InstanceType<typeof SnackBar>;

const theme = useTheme();

const { tt } = useI18n();

const rootStore = useRootStore();

const version = `${getClientDisplayVersion()}`;

const snackbar = useTemplateRef<SnackBarType>('snackbar');

const email = ref<string>('');
const requesting = ref<boolean>(false);

const isDarkMode = computed<boolean>(() => theme.global.name.value === ThemeType.Dark);

function requestResetPassword(): void {
    if (!email.value) {
        snackbar.value?.showMessage('Email address cannot be blank');
        return;
    }

    requesting.value = true;

    rootStore.requestResetPassword({
        email: email.value
    }).then(() => {
        requesting.value = false;
        snackbar.value?.showMessage('Password reset email has been sent');
    }).catch(error => {
        requesting.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}
</script>

<style scoped>
/* The authentication form has a light surface even when the device uses dark mode. */
.auth-card :deep(.v-card) {
    color: rgb(var(--v-theme-on-surface)) !important;
    background: rgb(var(--v-theme-surface)) !important;
}

.auth-card :deep(h4) {
    font-family: var(--ledger-font-body) !important;
    font-size: 28px !important;
    font-weight: 700 !important;
    line-height: 1.4;
    letter-spacing: normal !important;
    color: rgb(var(--v-theme-on-surface));
}

.auth-card :deep(p) {
    font-size: 15px;
    line-height: 1.7;
    color: rgba(var(--v-theme-on-surface), 0.85);
}

.auth-card :deep(.v-label),
.auth-card :deep(input) {
    font-family: var(--ledger-font-body);
    color: rgb(var(--v-theme-on-surface));
}

.auth-card :deep(input) {
    opacity: 1;
}

.auth-card :deep(.v-label) {
    font-weight: 600;
}

.auth-card :deep(input::placeholder) {
    color: rgba(var(--v-theme-on-surface), 0.75);
    opacity: 1;
}

.auth-card :deep(a) {
    color: rgb(var(--v-theme-primary-darken-1));
    font-weight: 600;
}

.auth-logo h1 {
    color: rgb(var(--v-theme-surface));
}

@media (max-width: 959px) {
    .auth-logo h1 {
        color: rgb(var(--v-theme-on-surface));
    }
}
</style>
