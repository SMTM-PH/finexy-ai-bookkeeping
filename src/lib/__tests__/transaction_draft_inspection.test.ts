import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
const flags = vi.hoisted(() => ({ locked: false }));
vi.mock('../settings.ts', () => ({ isEnableApplicationLock: () => flags.locked }));
vi.mock('../logger.ts', () => ({ default: { error: vi.fn(), warn: vi.fn() } }));
import * as state from '../userstate.ts';
import { EMPTY_USER_BASIC_INFO } from '@/models/user.ts';

class MemoryStorage {
    readonly data = new Map<string, string>();
    getItem(key: string): string | null { return this.data.get(key) ?? null; }
    setItem(key: string, value: string): void { this.data.set(key, value); }
    removeItem(key: string): void { this.data.delete(key); }
    get length(): number { return this.data.size; }
    key(index: number): string | null { return [...this.data.keys()][index] ?? null; }
}
let local: MemoryStorage;
const location = { origin: 'http://synthetic.invalid', pathname: '/desktop' };
beforeEach(() => {
    flags.locked = false;
    local = new MemoryStorage();
    vi.stubGlobal('localStorage', local);
    vi.stubGlobal('sessionStorage', new MemoryStorage());
    vi.stubGlobal('window', { location });
    location.pathname = '/desktop';
    state.updateCurrentUserInfo({ ...EMPTY_USER_BASIC_INFO, username: 'synthetic-owner' });
    state.updateCurrentToken('synthetic-session');
});
afterEach(() => vi.unstubAllGlobals());

describe('passive draft inspection for current-ledger pending work', () => {
    it('reads only the scoped draft without changing its persisted bytes', () => {
        state.updateUserTransactionDraft({ comment: 'unfinished synthetic input', clientSessionId: 'original-key' }, '9007199254740993');
        const before = [...local.data];
        expect(state.inspectUserTransactionDraft('9007199254740993')?.clientSessionId).toBe('original-key');
        expect(state.inspectUserTransactionDraft('0')).toBeNull();
        expect([...local.data]).toEqual(before);
        state.updateCurrentUserInfo({ ...EMPTY_USER_BASIC_INFO, username: 'synthetic-outsider' });
        expect(state.inspectUserTransactionDraft('9007199254740993')).toBeNull();
    });
    it('does not migrate or attribute a legacy record while counting pending work', () => {
        local.setItem('ebk_user_draft_transaction', JSON.stringify({ comment: 'legacy synthetic input' }));
        const before = [...local.data];
        expect(() => state.inspectUserTransactionDraft('0')).toThrow('旧版未分区');
        expect(() => state.inspectUserTransactionDraft('12')).toThrow('旧版未分区');
        expect([...local.data]).toEqual(before);
    });
    it('rejects a damaged record or changed embedded ledger instead of reporting no draft', () => {
        state.updateUserTransactionDraft({ comment: 'synthetic' }, '12');
        const key = [...local.data.keys()].find(value => value.includes('draft:12'))!;
        const record = local.getItem(key)!;
        local.setItem(key, '{broken');
        expect(() => state.inspectUserTransactionDraft('12')).toThrow();
        const parsed = JSON.parse(record);
        parsed.data = JSON.stringify({ ledgerId: '13', comment: 'synthetic' });
        local.setItem(key, JSON.stringify(parsed));
        expect(() => state.inspectUserTransactionDraft('12')).toThrow('归属不匹配');
    });
    it('requires a current unlocked login even if no draft key exists', () => {
        flags.locked = true;
        expect(() => state.inspectUserTransactionDraft('0')).toThrow('解锁');
        flags.locked = false;
        local.removeItem('ebk_user_token');
        expect(() => state.inspectUserTransactionDraft('0')).toThrow('登录');
    });
});

describe('strict current-ledger preference inspection', () => {
    it('restores a string ledger ID without writing, and treats only an absent preference as default', () => {
        expect(state.inspectUserSelectedLedgerId()).toBe('0');
        state.updateUserSelectedLedgerId('9007199254740993');
        const before = [...local.data];
        expect(state.inspectUserSelectedLedgerId()).toBe('9007199254740993');
        expect([...local.data]).toEqual(before);
        state.updateCurrentUserInfo({ ...EMPTY_USER_BASIC_INFO, username: 'synthetic-other' });
        expect(state.inspectUserSelectedLedgerId()).toBe('0');
    });
    it('does not turn damaged, invalid, locked or logged-out selection into a default fallback', () => {
        state.updateUserSelectedLedgerId('12');
        const key = [...local.data.keys()].find(value => value.endsWith('selection:0'))!;
        const original = local.getItem(key)!;
        for (const bad of ['{broken', JSON.stringify({ ...JSON.parse(original), data: JSON.stringify({ ledgerId: 12 }) }),
            JSON.stringify({ ...JSON.parse(original), data: JSON.stringify({ ledgerId: '01' }) })]) {
            local.setItem(key, bad);
            expect(() => state.inspectUserSelectedLedgerId()).toThrow();
            expect(local.getItem(key)).toBe(bad);
        }
        local.setItem(key, original);
        flags.locked = true;
        expect(() => state.inspectUserSelectedLedgerId()).toThrow('解锁');
        flags.locked = false;
        local.removeItem('ebk_user_token');
        expect(() => state.inspectUserSelectedLedgerId()).toThrow('登录');
    });
});
