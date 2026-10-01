import { ref, computed, watch } from 'vue';

import { useI18n } from '@/locales/helpers.ts';

import { useRootStore } from '@/stores/index.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useExchangeRatesStore } from '@/stores/exchangeRates.ts';

import type { AuthResponse } from '@/models/auth_response.ts';

import { updateMapCacheExpiration } from '@/lib/cache.ts';
import { getOAuth2Provider, getOIDCCustomDisplayNames, getLoginPageTips } from '@/lib/server_settings.ts';
import { getClientDisplayVersion } from '@/lib/version.ts';
import { setExpenseAndIncomeAmountColor } from '@/lib/ui/common.ts';
import { clearRememberedLogin, readRememberedLogin, saveRememberedLogin } from '@/lib/remembered_login.ts';

export function useLoginPageBase(platform: 'mobile' | 'desktop', restoreSavedLogin = true) {
    const { getServerMultiLanguageConfigContent, getLocalizedOAuth2LoginText, setLanguage } = useI18n();

    const rootStore = useRootStore();
    const settingsStore = useSettingsStore();
    const exchangeRatesStore = useExchangeRatesStore();

    const version = `${getClientDisplayVersion()}`;

    const savedLogin = restoreSavedLogin ? readRememberedLogin() : null;
    const username = ref<string>(savedLogin?.username ?? '');
    const password = ref<string>(savedLogin?.password ?? '');
    const rememberLogin = ref<boolean>(!!savedLogin);
    const rememberLoginError = ref<string>('');

    watch(rememberLogin, enabled => {
        rememberLoginError.value = '';
        if (!enabled && !clearRememberedLogin()) {
            rememberLoginError.value = '无法清除保存的登录信息，请在浏览器设置中清除此网站的数据。';
        }
    }, { flush: 'sync' });
    const passcode = ref<string>('');
    const backupCode = ref<string>('');
    const tempToken = ref<string>('');
    const twoFAVerifyType = ref<string>('passcode');
    const oauth2ClientSessionId = ref<string>('');

    const loggingInByPassword = ref<boolean>(false);
    const loggingInByOAuth2 = ref<boolean>(false);
    const verifying = ref<boolean>(false);

    const inputIsEmpty = computed<boolean>(() => !username.value || !password.value);
    const twoFAInputIsEmpty = computed<boolean>(() => {
        if (twoFAVerifyType.value === 'backupcode') {
            return !backupCode.value;
        } else {
            return !passcode.value;
        }
    });

    const oauth2LoginUrl = computed<string>(() => rootStore.generateOAuth2LoginUrl(platform, oauth2ClientSessionId.value));
    const oauth2LoginDisplayName = computed<string>(() => getLocalizedOAuth2LoginText(getOAuth2Provider(), getOIDCCustomDisplayNames()));
    const tips = computed<string>(() => getServerMultiLanguageConfigContent(getLoginPageTips()));

    function doAfterLogin(authResponse: AuthResponse): void {
        if (!authResponse.need2FA && username.value && password.value) {
            const saved = rememberLogin.value
                ? saveRememberedLogin({ username: username.value, password: password.value })
                : clearRememberedLogin();
            if (!saved) rememberLoginError.value = '浏览器不允许保存登录信息，本次登录仍然有效。';
        }
        if (authResponse.user) {
            const localeDefaultSettings = setLanguage(authResponse.user.language);
            settingsStore.updateLocalizedDefaultSettings(localeDefaultSettings);

            setExpenseAndIncomeAmountColor(authResponse.user.expenseAmountColor, authResponse.user.incomeAmountColor);
        }

        updateMapCacheExpiration(settingsStore.appSettings.mapCacheExpiration);
        exchangeRatesStore.removeExpiredExchangeRates(true);
        exchangeRatesStore.autoUpdateExchangeRatesData();

        if (authResponse.notificationContent) {
            rootStore.setNotificationContent(authResponse.notificationContent);
        }
        if (rememberLoginError.value) {
            rootStore.setNotificationContent(rememberLoginError.value);
        }
    }

    return {
        // constants
        version,
        // states
        username,
        password,
        rememberLogin,
        rememberLoginError,
        passcode,
        backupCode,
        tempToken,
        twoFAVerifyType,
        oauth2ClientSessionId,
        loggingInByPassword,
        loggingInByOAuth2,
        verifying,
        // computed states
        inputIsEmpty,
        twoFAInputIsEmpty,
        oauth2LoginUrl,
        oauth2LoginDisplayName,
        tips,
        // functions
        doAfterLogin
    }
}
