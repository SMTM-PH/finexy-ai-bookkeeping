import CryptoJS from 'crypto-js';

// Origin-local storage keeps different servers separate. This is device convenience,
// not protection against someone who can access this browser profile.
const storageKey = 'finexy_remembered_login_v1';

export interface RememberedLogin {
    username: string;
    password: string;
}

export function clearRememberedLogin(): boolean {
    try {
        localStorage.removeItem(storageKey);
        return true;
    } catch {
        return false;
    }
}

export function readRememberedLogin(): RememberedLogin | null {
    try {
        const raw = localStorage.getItem(storageKey);
        if (!raw) return null;
        const record = JSON.parse(raw);
        if (record.version !== 1 || typeof record.key !== 'string' || typeof record.ciphertext !== 'string' || typeof record.mac !== 'string') {
            throw new Error('Invalid saved login');
        }
        if (CryptoJS.HmacSHA256(record.ciphertext, record.key).toString() !== record.mac) {
            throw new Error('Invalid saved login');
        }
        const login = JSON.parse(CryptoJS.AES.decrypt(record.ciphertext, record.key).toString(CryptoJS.enc.Utf8));
        if (typeof login.username !== 'string' || !login.username || typeof login.password !== 'string' || !login.password) {
            throw new Error('Invalid saved login');
        }
        return { username: login.username, password: login.password };
    } catch {
        clearRememberedLogin();
        return null;
    }
}

export function saveRememberedLogin(login: RememberedLogin): boolean {
    try {
        const key = CryptoJS.lib.WordArray.random(32).toString();
        const ciphertext = CryptoJS.AES.encrypt(JSON.stringify(login), key).toString();
        localStorage.setItem(storageKey, JSON.stringify({ version: 1, key, ciphertext, mac: CryptoJS.HmacSHA256(ciphertext, key).toString() }));
        return true;
    } catch {
        clearRememberedLogin();
        return false;
    }
}
