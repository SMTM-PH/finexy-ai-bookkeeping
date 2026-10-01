import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearRememberedLogin, readRememberedLogin, saveRememberedLogin } from '@/lib/remembered_login.ts';

describe('device remembered login', () => {
    const storage = new Map<string, string>();
    const login = { username: 'test-user', password: 'test-password-测试' };

    beforeEach(() => {
        storage.clear();
        vi.stubGlobal('localStorage', {
            getItem: (key: string) => storage.get(key) ?? null,
            setItem: (key: string, value: string) => { storage.set(key, value); },
            removeItem: (key: string) => { storage.delete(key); }
        });
    });

    it('defaults to no credentials, restores encrypted values and replaces the remembered account', () => {
        expect(readRememberedLogin()).toBeNull();
        expect(saveRememberedLogin(login)).toBe(true);
        const raw = [...storage.values()][0];
        expect(raw).not.toContain(login.username);
        expect(raw).not.toContain(login.password);
        expect(readRememberedLogin()).toEqual(login);
        const next = { username: 'other-user', password: 'other-password' };
        expect(saveRememberedLogin(next)).toBe(true);
        expect(readRememberedLogin()).toEqual(next);
        expect(clearRememberedLogin()).toBe(true);
        expect(storage.size).toBe(0);
    });

    it('removes malformed or tampered records without breaking the login page', () => {
        saveRememberedLogin(login);
        const key = [...storage.keys()][0]!;
        const record = JSON.parse(storage.get(key)!);
        record.ciphertext += 'invalid';
        storage.set(key, JSON.stringify(record));
        expect(readRememberedLogin()).toBeNull();
        expect(storage.size).toBe(0);
        storage.set(key, 'not-json');
        expect(readRememberedLogin()).toBeNull();
        expect(storage.size).toBe(0);
    });

    it('handles browsers that block storage', () => {
        const blocked = () => { throw new Error('Storage blocked'); };
        vi.stubGlobal('localStorage', { getItem: blocked, setItem: blocked, removeItem: blocked });
        expect(readRememberedLogin()).toBeNull();
        expect(saveRememberedLogin(login)).toBe(false);
        expect(clearRememberedLogin()).toBe(false);
    });
});
