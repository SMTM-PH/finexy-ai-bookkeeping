import CryptoJS from 'crypto-js';

import type { ApplicationLockState, WebAuthnConfig } from '@/core/setting.ts';
import type { UserBasicInfo } from '@/models/user.ts';
import type { TransactionDraft, PendingTransactionCreate } from '@/models/transaction.ts';
import type { PendingTemplateCreate, TemplateCreationType, TemplateEditorDraft } from '@/models/template_creation.ts';
import type { PendingAIReviewCreate, PendingAIReviewConfirm } from '@/models/ai_review_ledger.ts';

import { isString, isObject } from './common.ts';
import { isEnableApplicationLock } from './settings.ts';
import logger from './logger.ts';
import { getBasePath } from './web.ts';

const appLockSecretBaseStringPrefix: string = 'EBK_LOCK_SECRET_';

const tokenLocalStorageKey: string = 'ebk_user_token';
const webauthnConfigLocalStorageKey: string = 'ebk_user_webauthn_config';
const userInfoLocalStorageKey: string = 'ebk_user_info';
const transactionDraftLocalStorageKey: string = 'ebk_user_draft_transaction';
const scopedTransactionStatePrefix = 'ebk_transaction_state_v1:';

const tokenSessionStorageKey: string = 'ebk_user_session_token';
const encryptedTokenSessionStorageKey: string = 'ebk_user_session_encrypted_token';
const appLockStateSessionStorageKey: string = 'ebk_user_app_lock_state'; // { 'username': '', secret: '' }

function getAppLockSecret(pinCode: string): string {
    const hashedPinCode = CryptoJS.SHA256(appLockSecretBaseStringPrefix + pinCode).toString();
    return hashedPinCode.substring(0, 24); // put secret into user id of webauthn (user id total length must less 64 bytes)
}

function getEncryptedToken(token: string, appLockState: ApplicationLockState): string {
    const key = CryptoJS.SHA256(`${appLockSecretBaseStringPrefix}|${appLockState.username}|${appLockState.secret}`).toString();
    return CryptoJS.AES.encrypt(token, key).toString();
}

function getDecryptedToken(encryptedToken: string, appLockState: ApplicationLockState): string {
    const key = CryptoJS.SHA256(`${appLockSecretBaseStringPrefix}|${appLockState.username}|${appLockState.secret}`).toString();
    const bytes = CryptoJS.AES.decrypt(encryptedToken, key);
    return bytes.toString(CryptoJS.enc.Utf8);
}

export function isUserLogined(): boolean {
    return !!localStorage.getItem(tokenLocalStorageKey);
}

export function isUserUnlocked(): boolean {
    if (!isUserLogined()) {
        return false;
    }

    if (!isEnableApplicationLock()) {
        return true;
    }

    return !!sessionStorage.getItem(appLockStateSessionStorageKey) && !!sessionStorage.getItem(tokenSessionStorageKey);
}

export function hasUserAppLockState(): boolean {
    return !!getUserAppLockState();
}

export function getUserAppLockState(): ApplicationLockState | null {
    const data = sessionStorage.getItem(appLockStateSessionStorageKey);

    if (!data) {
        return null;
    }

    const appLockState = JSON.parse(data);

    if (!appLockState || !appLockState.username || !appLockState.secret) {
        return null;
    }

    return appLockState as ApplicationLockState;
}

export function unlockTokenByWebAuthn(credentialId: string, userName: string, userSecret: string): void {
    const webauthnConfigData = localStorage.getItem(webauthnConfigLocalStorageKey);

    if (!webauthnConfigData) {
        throw new Error('WebAuthn credential is not set');
    }

    const webauthnConfig = JSON.parse(webauthnConfigData) as WebAuthnConfig;

    if (webauthnConfig.credentialId !== credentialId) {
        throw new Error('WebAuthn credential is invalid');
    }

    const encryptedToken = localStorage.getItem(tokenLocalStorageKey);

    if (!encryptedToken) {
        throw new Error('No token in local storage');
    }

    const appLockState: ApplicationLockState = {
        username: userName,
        secret: userSecret
    };
    const token = getDecryptedToken(encryptedToken, appLockState);

    sessionStorage.setItem(appLockStateSessionStorageKey, JSON.stringify(appLockState));
    sessionStorage.setItem(encryptedTokenSessionStorageKey, encryptedToken);
    sessionStorage.setItem(tokenSessionStorageKey, token);
}

export function unlockTokenByPinCode(userName: string, pinCode: string): void {
    const encryptedToken = localStorage.getItem(tokenLocalStorageKey);

    if (!encryptedToken) {
        throw new Error('No token in local storage');
    }

    const appLockState: ApplicationLockState = {
        username: userName,
        secret: getAppLockSecret(pinCode)
    };
    const token = getDecryptedToken(encryptedToken, appLockState);

    sessionStorage.setItem(appLockStateSessionStorageKey, JSON.stringify(appLockState));
    sessionStorage.setItem(encryptedTokenSessionStorageKey, encryptedToken);
    sessionStorage.setItem(tokenSessionStorageKey, token);
}

export function encryptToken(userName: string, pinCode: string): void {
    const token = getUserAppLockState() ? getCurrentToken() : localStorage.getItem(tokenLocalStorageKey);

    if (!token) {
        throw new Error('No token in local storage');
    }

    const appLockState: ApplicationLockState = {
        username: userName,
        secret: getAppLockSecret(pinCode)
    };
    const encryptedToken = getEncryptedToken(token, appLockState);

    const rollbackRecords = reencryptScopedTransactionState(getUserAppLockState(), appLockState);
    updateTokenLockStorage(() => {
        sessionStorage.setItem(appLockStateSessionStorageKey, JSON.stringify(appLockState));
        sessionStorage.setItem(encryptedTokenSessionStorageKey, encryptedToken);
        sessionStorage.setItem(tokenSessionStorageKey, token);
        localStorage.setItem(tokenLocalStorageKey, encryptedToken);
    }, rollbackRecords);
}

export function decryptToken(): void {
    const token = sessionStorage.getItem(tokenSessionStorageKey);

    if (!token) {
        throw new Error('No token in session storage');
    }

    const rollbackRecords = reencryptScopedTransactionState(getUserAppLockState(), null);
    updateTokenLockStorage(() => {
        localStorage.setItem(tokenLocalStorageKey, token);
        sessionStorage.removeItem(tokenSessionStorageKey);
        sessionStorage.removeItem(encryptedTokenSessionStorageKey);
        sessionStorage.removeItem(appLockStateSessionStorageKey);
    }, rollbackRecords);
}

function updateTokenLockStorage(action: () => void, rollbackRecords: () => void): void {
    const tokenBefore = localStorage.getItem(tokenLocalStorageKey);
    const sessionBefore = [appLockStateSessionStorageKey, encryptedTokenSessionStorageKey, tokenSessionStorageKey]
        .map(key => [key, sessionStorage.getItem(key)] as const);
    try {
        action();
    } catch (error) {
        rollbackRecords();
        for (const [key, value] of sessionBefore) {
            if (value === null) sessionStorage.removeItem(key);
            else sessionStorage.setItem(key, value);
        }
        if (localStorage.getItem(tokenLocalStorageKey) !== tokenBefore && tokenBefore !== null) localStorage.setItem(tokenLocalStorageKey, tokenBefore);
        throw error;
    }
}

export function isCorrectPinCode(pinCode: string): boolean {
    const secret = getAppLockSecret(pinCode);
    const appLockState = getUserAppLockState();

    if (!appLockState) {
        return false;
    }

    return appLockState && secret === appLockState.secret;
}

export function getCurrentToken(): string | null {
    if (isEnableApplicationLock()) {
        const usedEncryptedToken = sessionStorage.getItem(encryptedTokenSessionStorageKey);
        const currentEncryptedToken = localStorage.getItem(tokenLocalStorageKey);

        if (!usedEncryptedToken || !currentEncryptedToken) {
            return null;
        }

        if (usedEncryptedToken === currentEncryptedToken) {
            return sessionStorage.getItem(tokenSessionStorageKey);
        }

        // re-decrypt token
        logger.warn(`encrypted token in local storage does not equal to the one in session storage, need to re-decrypt`);

        const appLockState = getUserAppLockState();

        if (!appLockState) {
            return null;
        }

        const token = getDecryptedToken(currentEncryptedToken, appLockState);

        sessionStorage.setItem(encryptedTokenSessionStorageKey, currentEncryptedToken);
        sessionStorage.setItem(tokenSessionStorageKey, token);

        return token;
    } else {
        return localStorage.getItem(tokenLocalStorageKey);
    }
}

export function updateCurrentToken(token: string): void {
    if (isString(token)) {
        if (isEnableApplicationLock() && hasUserAppLockState()) {
            const appLockState = getUserAppLockState();
            const encryptedToken = getEncryptedToken(token, appLockState as ApplicationLockState);

            sessionStorage.setItem(encryptedTokenSessionStorageKey, encryptedToken);
            sessionStorage.setItem(tokenSessionStorageKey, token);
            localStorage.setItem(tokenLocalStorageKey, encryptedToken);
        } else {
            localStorage.setItem(tokenLocalStorageKey, token);
        }
    }
}

export function hasWebAuthnConfig(): boolean {
    return !!getWebAuthnCredentialId();
}

export function getWebAuthnCredentialId(): string | undefined {
    const webauthnConfigData = localStorage.getItem(webauthnConfigLocalStorageKey);

    if (!webauthnConfigData) {
        return undefined;
    }

    const webauthnConfig = JSON.parse(webauthnConfigData) as WebAuthnConfig;

    return webauthnConfig.credentialId;
}

export function saveWebAuthnConfig(credentialId: string): void {
    const webAuthnConfig: WebAuthnConfig = {
        credentialId: credentialId
    };

    localStorage.setItem(webauthnConfigLocalStorageKey, JSON.stringify(webAuthnConfig));
}

export function clearWebAuthnConfig(): void {
    localStorage.removeItem(webauthnConfigLocalStorageKey);
}

export function getCurrentUserInfo(): UserBasicInfo | null {
    const data = localStorage.getItem(userInfoLocalStorageKey);

    if (!data) {
        return null;
    }

    return JSON.parse(data) as UserBasicInfo;
}

export function updateCurrentUserInfo(user: UserBasicInfo): void {
    if (isObject(user)) {
        localStorage.setItem(userInfoLocalStorageKey, JSON.stringify(user));
    }
}

export function clearCurrentUserInfo(): void {
    localStorage.removeItem(userInfoLocalStorageKey);
}

interface StoredTransactionState {
    readonly version: 1;
    readonly scope: string;
    readonly encrypted: boolean;
    readonly data: string;
}

function currentTransactionStatePrefix(): string | null {
    if (typeof localStorage === 'undefined' || typeof window === 'undefined') return null;
    const user = getCurrentUserInfo();
    if (!user?.username) return null;
    const server = window.location.origin + getBasePath();
    return scopedTransactionStatePrefix + CryptoJS.SHA256(JSON.stringify([server, user.username])).toString() + ':';
}

export function getTransactionStateIdentity(): string | null {
    return currentTransactionStatePrefix();
}

/** IndexedDB read/write transactions serialize the brief localStorage update
 * across tabs, including non-HTTPS LAN hosts where Web Locks is unavailable. */
export async function withTransactionCreateLock<T>(action: () => T): Promise<T> {
    const identity = currentTransactionStatePrefix();
    if (!identity) throw new Error('请先登录后保存记账记录');
    return new Promise((resolve, reject) => {
        let abandoned = false;
        const opening = indexedDB.open('finexy-transaction-operations', 1);
        opening.onupgradeneeded = () => opening.result.createObjectStore('locks');
        opening.onerror = () => reject(new Error('无法打开可靠记账存储，请检查浏览器存储权限'));
        opening.onblocked = () => { abandoned = true; reject(new Error('记账存储正在升级，请关闭旧页面后重试')); };
        opening.onsuccess = () => {
            const database = opening.result;
            if (abandoned) { database.close(); return; }
            const transaction = database.transaction('locks', 'readwrite');
            const locked = transaction.objectStore('locks').get(identity);
            let result: T;
            let failure: unknown;
            locked.onsuccess = () => {
                try {
                    if (identity !== currentTransactionStatePrefix()) throw new Error('登录账号已变化，请重新打开记账页');
                    result = action();
                } catch (error) {
                    failure = error;
                    transaction.abort();
                }
            };
            transaction.oncomplete = () => { database.close(); resolve(result); };
            transaction.onabort = transaction.onerror = () => { database.close(); reject(failure || new Error('无法可靠保存记账状态，请重试')); };
        };
    });
}

type ScopedStateKind = 'draft' | 'pending' | 'selection' | 'template-create' | 'ai-review-create' | 'ai-review-confirm' | 'ai-review-draft' | 'ai-review-source' | `template-draft-${TemplateCreationType}`;

function transactionStateKey(kind: ScopedStateKind, ledgerId: string): string | null {
    if (!/^(0|[1-9]\d*)$/.test(ledgerId)) throw new Error('账本标识无效');
    const prefix = currentTransactionStatePrefix();
    return prefix ? `${prefix}${kind}:${ledgerId}` : null;
}

function decodeTransactionState(key: string, data: string, lock: ApplicationLockState | null): string {
    const record = JSON.parse(data) as StoredTransactionState;
    if (record.version !== 1 || record.scope !== key || typeof record.encrypted !== 'boolean' || typeof record.data !== 'string') {
        throw new Error('本地记账状态无效，请保留原记录后重试');
    }
    if (record.encrypted && !lock) throw new Error('请先解锁应用，再恢复待确认记录');
    return record.encrypted ? getDecryptedToken(record.data, lock!) : record.data;
}

function encodeTransactionState(key: string, data: string, lock: ApplicationLockState | null): string {
    const record: StoredTransactionState = { version: 1, scope: key, encrypted: !!lock, data: lock ? getEncryptedToken(data, lock) : data };
    return JSON.stringify(record);
}

function readTransactionState<T>(kind: ScopedStateKind, ledgerId: string): T | null {
    const key = transactionStateKey(kind, ledgerId);
    const data = key ? localStorage.getItem(key) : null;
    if (!key || data === null) return null;
    try {
        const parsed: unknown = JSON.parse(decodeTransactionState(key, data, getUserAppLockState()));
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('本地记账状态不是有效记录');
        return parsed as T;
    } catch {
        // Never replace an unreadable pending request with a newly generated key.
        throw new Error('无法读取本地记账状态，请先解锁或恢复原记录');
    }
}

function writeTransactionState(kind: ScopedStateKind, ledgerId: string, state: unknown): void {
    const key = transactionStateKey(kind, ledgerId);
    if (!key) throw new Error('请先登录后保存记账记录');
    const lock = getUserAppLockState();
    if (isEnableApplicationLock() && !lock) throw new Error('请先解锁应用后保存记账记录');
    // A quota/storage error must stop the HTTP request rather than silently losing its key.
    localStorage.setItem(key, encodeTransactionState(key, JSON.stringify(state), isEnableApplicationLock() ? lock : null));
}

function removeTransactionState(kind: ScopedStateKind, ledgerId: string): void {
    const key = transactionStateKey(kind, ledgerId);
    if (key) localStorage.removeItem(key);
}

function reencryptScopedTransactionState(oldLock: ApplicationLockState | null, newLock: ApplicationLockState | null): () => void {
    const prefix = currentTransactionStatePrefix();
    if (!prefix) return () => {};
    const rewrites: [string, string, string][] = [];
    for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index);
        if (!key?.startsWith(prefix)) continue;
        const data = localStorage.getItem(key)!;
        const decoded = decodeTransactionState(key, data, oldLock);
        JSON.parse(decoded); // Validate all records before changing any lock state.
        rewrites.push([key, data, encodeTransactionState(key, decoded, newLock)]);
    }
    const legacy = localStorage.getItem(transactionDraftLocalStorageKey);
    if (legacy) {
        const decoded = oldLock ? getDecryptedToken(legacy, oldLock) : legacy;
        JSON.parse(decoded);
        rewrites.push([transactionDraftLocalStorageKey, legacy, newLock ? getEncryptedToken(decoded, newLock) : decoded]);
    }
    let changed = 0;
    try {
        for (const [key, , data] of rewrites) {
            localStorage.setItem(key, data);
            changed++;
        }
    } catch (error) {
        for (const [key, original] of rewrites.slice(0, changed)) localStorage.setItem(key, original);
        throw error;
    }
    return () => { for (const [key, original] of rewrites) localStorage.setItem(key, original); };
}

export const getUserTemplateEditorDraft = (ledgerId: string, kind: TemplateCreationType): TemplateEditorDraft | null => readTransactionState(`template-draft-${kind}`, ledgerId);
export const updateUserTemplateEditorDraft = (ledgerId: string, kind: TemplateCreationType, draft: TemplateEditorDraft): void => writeTransactionState(`template-draft-${kind}`, ledgerId, draft);
export const clearUserTemplateEditorDraft = (ledgerId: string, kind: TemplateCreationType): void => removeTransactionState(`template-draft-${kind}`, ledgerId);
export const getUserPendingTemplateCreate = (ledgerId: string): PendingTemplateCreate | null => readTransactionState('template-create', ledgerId);
export const updateUserPendingTemplateCreate = (ledgerId: string, pending: PendingTemplateCreate): void => writeTransactionState('template-create', ledgerId, pending);
export const clearUserPendingTemplateCreate = (ledgerId: string): void => removeTransactionState('template-create', ledgerId);

export const getUserPendingAIReviewCreate = (ledgerId: string): PendingAIReviewCreate | null => readTransactionState('ai-review-create', ledgerId);
export const updateUserPendingAIReviewCreate = (ledgerId: string, pending: PendingAIReviewCreate): void => writeTransactionState('ai-review-create', ledgerId, pending);
export const clearUserPendingAIReviewCreate = (ledgerId: string): void => removeTransactionState('ai-review-create', ledgerId);
export const getUserPendingAIReviewConfirm = (ledgerId: string): PendingAIReviewConfirm | null => readTransactionState('ai-review-confirm', ledgerId);
export const updateUserPendingAIReviewConfirm = (ledgerId: string, pending: PendingAIReviewConfirm): void => writeTransactionState('ai-review-confirm', ledgerId, pending);
export const clearUserPendingAIReviewConfirm = (ledgerId: string): void => removeTransactionState('ai-review-confirm', ledgerId);

export const getUserAIReviewEditorDraft = (ledgerId: string): import('@/models/ai_review_ledger.ts').AIReviewEditorDraft | null => readTransactionState('ai-review-draft', ledgerId);
export const updateUserAIReviewEditorDraft = (ledgerId: string, draft: import('@/models/ai_review_ledger.ts').AIReviewEditorDraft): void => writeTransactionState('ai-review-draft', ledgerId, draft);
export const clearUserAIReviewEditorDraft = (ledgerId: string): void => removeTransactionState('ai-review-draft', ledgerId);
export const getUserAIReviewSourceDraft = (ledgerId: string): import('@/models/ai_review_source.ts').AIReviewSourceDraft | null => readTransactionState('ai-review-source', ledgerId);
export const updateUserAIReviewSourceDraft = (ledgerId: string, draft: import('@/models/ai_review_source.ts').AIReviewSourceDraft): void => writeTransactionState('ai-review-source', ledgerId, draft);
export const clearUserAIReviewSourceDraft = (ledgerId: string): void => removeTransactionState('ai-review-source', ledgerId);

/** Passive inspection never migrates or clears a draft. An unscoped legacy
 * record cannot prove its current account and is therefore not a zero count. */
export function inspectUserTransactionDraft(ledgerId = '0'): TransactionDraft | null {
    if (!currentTransactionStatePrefix() || !isUserLogined()) throw new Error('请先登录后核验本机草稿');
    if (isEnableApplicationLock() && !getUserAppLockState()) throw new Error('请先解锁后核验本机草稿');
    const draft = readTransactionState<TransactionDraft>('draft', ledgerId);
    if (draft !== null) {
        if (draft.ledgerId !== ledgerId) throw new Error('本机草稿归属不匹配，请保留原记录后核对');
        return draft;
    }
    if (localStorage.getItem(transactionDraftLocalStorageKey) !== null) {
        throw new Error('发现旧版未分区草稿，请先打开原记账页核验；不会自动迁移');
    }
    return null;
}

export function getUserTransactionDraft(ledgerId = '0'): TransactionDraft | null {
    let scoped: TransactionDraft | null;
    try {
        scoped = readTransactionState<TransactionDraft>('draft', ledgerId);
    } catch {
        // A locked or damaged optional draft must not prevent the lock screen.
        return null;
    }
    if (scoped) return scoped.ledgerId === ledgerId ? scoped : null;
    // The previous app clears this legacy key on logout. Migrate it once, only
    // for the logged-in user and its originally recorded/default ledger.
    if (!currentTransactionStatePrefix()) return null;
    let data = localStorage.getItem(transactionDraftLocalStorageKey);
    if (!data) return null;
    const lock = getUserAppLockState();
    if (isEnableApplicationLock()) {
        if (!lock) return null;
        data = getDecryptedToken(data, lock);
    }
    let legacy: TransactionDraft;
    try {
        legacy = JSON.parse(data) as TransactionDraft;
        if (!isObject(legacy)) return null;
    } catch {
        return null;
    }
    if ((legacy.ledgerId ?? '0') !== ledgerId) return null;
    const migrated = { ...legacy, ledgerId };
    writeTransactionState('draft', ledgerId, migrated);
    localStorage.removeItem(transactionDraftLocalStorageKey);
    return migrated;
}

export function updateUserTransactionDraft(transaction?: TransactionDraft | null, ledgerId = '0'): void {
    if (isObject(transaction)) writeTransactionState('draft', ledgerId, { ...transaction, ledgerId });
}

export function clearUserTransactionDraft(ledgerId = '0'): void {
    removeTransactionState('draft', ledgerId);
    if (ledgerId === '0') localStorage.removeItem(transactionDraftLocalStorageKey);
}

export function getUserPendingTransactionCreate(ledgerId: string): PendingTransactionCreate | null {
    return readTransactionState<PendingTransactionCreate>('pending', ledgerId);
}

export function updateUserPendingTransactionCreate(pending: PendingTransactionCreate, ledgerId: string): void {
    writeTransactionState('pending', ledgerId, pending);
}

export function clearUserPendingTransactionCreate(ledgerId: string): void {
    removeTransactionState('pending', ledgerId);
}

export function getUserSelectedLedgerId(): string {
    try {
        const selection = readTransactionState<{ ledgerId: string }>('selection', '0');
        return selection && /^(0|[1-9]\d*)$/.test(selection.ledgerId) ? selection.ledgerId : '0';
    } catch {
        return '0';
    }
}

/** Deep-link recovery must distinguish a missing preference from an unreadable
 * one. This read neither repairs the record nor selects a replacement ledger. */
export function inspectUserSelectedLedgerId(): string {
    if (!currentTransactionStatePrefix() || !isUserLogined()) throw new Error('请先登录后核验原账本');
    if (isEnableApplicationLock() && !getUserAppLockState()) throw new Error('请先解锁后核验原账本');
    const selection = readTransactionState<{ ledgerId: unknown }>('selection', '0');
    if (selection === null) return '0';
    if (typeof selection.ledgerId !== 'string' || !/^(0|[1-9]\d*)$/.test(selection.ledgerId)) {
        throw new Error('本机账本选择无效，请保留原记录后核对');
    }
    return selection.ledgerId;
}

export function updateUserSelectedLedgerId(ledgerId: string): void {
    if (!currentTransactionStatePrefix()) return;
    if (!/^(0|[1-9]\d*)$/.test(ledgerId)) throw new Error('账本标识无效');
    writeTransactionState('selection', '0', { ledgerId });
}

export function clearCurrentSessionToken(): void {
    sessionStorage.removeItem(tokenSessionStorageKey);
    sessionStorage.removeItem(encryptedTokenSessionStorageKey);
    sessionStorage.removeItem(appLockStateSessionStorageKey);
}

export function clearCurrentTokenAndUserInfo(clearAppLockState: boolean): void {
    const prefix = currentTransactionStatePrefix();
    if (prefix) {
        const draftKeys: string[] = [];
        for (let index = 0; index < localStorage.length; index++) {
            const key = localStorage.key(index);
            if (key?.startsWith(prefix + 'draft:')) draftKeys.push(key);
        }
        for (const key of draftKeys) localStorage.removeItem(key);
    }
    // Unknown submissions survive logout and can only be resumed by this user.
    if (clearAppLockState) {
        sessionStorage.removeItem(appLockStateSessionStorageKey);
    }

    sessionStorage.removeItem(tokenSessionStorageKey);
    sessionStorage.removeItem(encryptedTokenSessionStorageKey);
    localStorage.removeItem(tokenLocalStorageKey);
    clearUserTransactionDraft();
    clearCurrentUserInfo();
}
