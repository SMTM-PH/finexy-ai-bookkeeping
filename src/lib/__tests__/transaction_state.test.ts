import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { randomBytes } from 'node:crypto';
const flags = vi.hoisted(() => ({ locked: false }));
vi.mock('../settings.ts', () => ({ isEnableApplicationLock: () => flags.locked }));
vi.mock('../logger.ts', () => ({ default: { error: vi.fn(), warn: vi.fn() } }));
import * as state from '../userstate.ts';
import { EMPTY_USER_BASIC_INFO } from '@/models/user.ts';
import type { PendingTransactionCreate } from '@/models/transaction.ts';
import type { PendingTemplateCreate, TemplateEditorDraft } from '@/models/template_creation.ts';

class MemoryStorage {
    data = new Map<string, string>();
    failKey = '';
    get length(): number { return this.data.size; }
    key(index: number): string | null { return [...this.data.keys()][index] ?? null; }
    getItem(key: string): string | null { return this.data.get(key) ?? null; }
    setItem(key: string, value: string): void {
        if (key === this.failKey) throw new Error('Quota');
        this.data.set(key, value);
    }
    removeItem(key: string): void { this.data.delete(key); }
}
const ledger = '9223372036854775807';
const pending: PendingTransactionCreate = { clientSessionId: 'synthetic-key', request: {
    ledgerId: ledger, clientSessionId: 'synthetic-key', type: 3, categoryId: '20', time: 1700000000, utcOffset: 480,
    sourceAccountId: '10', destinationAccountId: '0', sourceAmount: 123, destinationAmount: 0,
    hideAmount: false, tagIds: [], pictureIds: [], comment: 'synthetic immutable snapshot'
} };
let local: MemoryStorage;
let location: { origin: string; pathname: string };
const login = (username = 'test-owner') => {
    state.updateCurrentUserInfo({ ...EMPTY_USER_BASIC_INFO, username });
    state.updateCurrentToken(randomBytes(16).toString('hex'));
};
beforeEach(() => {
    flags.locked = false;
    local = new MemoryStorage();
    location = { origin: 'http://test.invalid', pathname: '/mobile' };
    vi.stubGlobal('window', { location });
    vi.stubGlobal('localStorage', local);
    vi.stubGlobal('sessionStorage', new MemoryStorage());
    login();
});
afterEach(() => vi.unstubAllGlobals());

describe('persistent transaction scope', () => {
    it('isolates template raw drafts by type, ledger, user and server path and retains them across logout', () => {
        const input = { ...pending.request, ledgerId: ledger, templateType: 1, name: '', sourceAmount: '1.', destinationAmount: '' };
        const draft: TemplateEditorDraft = { version: 1, revision: 'first', input };
        state.updateUserTemplateEditorDraft(ledger, 1, draft);
        expect(state.getUserTemplateEditorDraft(ledger, 2)).toBeNull();
        expect(state.getUserTemplateEditorDraft('0', 1)).toBeNull();
        state.clearCurrentTokenAndUserInfo(true); login('another-user'); expect(state.getUserTemplateEditorDraft(ledger, 1)).toBeNull();
        login(); location.pathname = '/other/mobile'; expect(state.getUserTemplateEditorDraft(ledger, 1)).toBeNull();
        location.pathname = '/mobile'; expect(state.getUserTemplateEditorDraft(ledger, 1)).toEqual(draft);
    });
    it('includes template journals in PIN re-encryption and preserves exact confirmation text', () => {
        const value: PendingTemplateCreate = { version: 1, identity: state.getTransactionStateIdentity()!, draftRevision: null,
            request: { ...pending.request, ledgerId: ledger, name: 'Synthetic template', templateType: 1 } };
        state.updateUserPendingTemplateCreate(ledger, value);
        state.encryptToken('test-owner', randomBytes(8).toString('hex')); flags.locked = true;
        const stored = [...local.data.entries()].find(([key]) => key.includes('template-create:'))!;
        expect(JSON.parse(stored[1]).encrypted).toBe(true); expect(stored[1]).not.toContain('Synthetic template');
        expect(state.getUserPendingTemplateCreate(ledger)).toEqual(value);
        state.decryptToken(); flags.locked = false; expect(state.getUserPendingTemplateCreate(ledger)).toEqual(value);
    });
    it('refuses a copied template journal or unavailable storage instead of reading an empty queue', () => {
        const value: PendingTemplateCreate = { version: 1, identity: state.getTransactionStateIdentity()!, draftRevision: null,
            request: { ...pending.request, ledgerId: ledger, name: 'Synthetic template', templateType: 1 } };
        state.updateUserPendingTemplateCreate(ledger, value);
        const stored = [...local.data.entries()].find(([key]) => key.includes('template-create:'))!;
        local.setItem(stored[0].replace(ledger, '9'), stored[1]);
        expect(() => state.getUserPendingTemplateCreate('9')).toThrow();
        local.failKey = stored[0]; expect(() => state.updateUserPendingTemplateCreate(ledger, value)).toThrow('Quota');
        expect(state.getUserPendingTemplateCreate(ledger)).toEqual(value);
    });
    it('isolates server path, user and large string ledger IDs', () => {
        state.updateUserTransactionDraft({ comment: 'synthetic draft', time: 1700000000 }, ledger);
        state.updateUserSelectedLedgerId(ledger);
        expect(state.getUserTransactionDraft('0')).toBeNull();
        login('another-user'); expect(state.getUserTransactionDraft(ledger)).toBeNull();
        expect(state.getUserSelectedLedgerId()).toBe('0');
        login(); location.pathname = '/other/mobile'; expect(state.getUserTransactionDraft(ledger)).toBeNull();
        location.pathname = '/mobile'; location.origin = 'http://another.invalid'; expect(state.getUserTransactionDraft(ledger)).toBeNull();
        location.origin = 'http://test.invalid';
        expect(state.getUserTransactionDraft(ledger)?.time).toBe(1700000000);
        expect(state.getUserSelectedLedgerId()).toBe(ledger);
    });

    it('migrates a legacy draft only into its original ledger', () => {
        local.setItem('ebk_user_draft_transaction', JSON.stringify({ ledgerId: ledger, comment: 'legacy synthetic' }));
        expect(state.getUserTransactionDraft('0')).toBeNull();
        expect(local.getItem('ebk_user_draft_transaction')).not.toBeNull();
        expect(state.getUserTransactionDraft(ledger)?.ledgerId).toBe(ledger);
        expect(local.getItem('ebk_user_draft_transaction')).toBeNull();
    });

    it('preserves unknown submissions across logout without sharing them with another user', () => {
        state.updateUserPendingTransactionCreate(pending, ledger);
        state.updateUserTransactionDraft({ comment: 'discard on logout' }, ledger);
        state.clearCurrentTokenAndUserInfo(true);
        login('another-user'); expect(state.getUserPendingTransactionCreate(ledger)).toBeNull();
        login(); expect(state.getUserPendingTransactionCreate(ledger)).toEqual(pending);
        expect(state.getUserTransactionDraft(ledger)).toBeNull();
    });

    it('fails closed for corrupt or copied pending records', () => {
        state.updateUserPendingTransactionCreate(pending, ledger);
        const key = [...local.data.keys()].find(key => key.includes('pending:'))!;
        local.setItem(key.replace(ledger, '9'), local.getItem(key)!);
        expect(() => state.getUserPendingTransactionCreate('9')).toThrow();
        local.setItem(key, '{invalid');
        expect(() => state.getUserPendingTransactionCreate(ledger)).toThrow();
    });

    it('reencrypts every ledger and the selection when enabling, changing and disabling the PIN', () => {
        const tokenBefore = state.getCurrentToken();
        state.updateUserPendingTransactionCreate(pending, ledger);
        state.updateUserTransactionDraft({ comment: 'synthetic private draft' }, '0');
        state.updateUserSelectedLedgerId(ledger);
        state.encryptToken('test-owner', randomBytes(8).toString('hex')); flags.locked = true;
        const records = [...local.data.entries()].filter(([key]) => key.startsWith('ebk_transaction_state_v1:'));
        expect(records.every(([, value]) => JSON.parse(value).encrypted && !value.includes('synthetic'))).toBe(true);
        expect(state.getUserPendingTransactionCreate(ledger)).toEqual(pending);
        state.encryptToken('test-owner', randomBytes(8).toString('hex'));
        expect(state.getCurrentToken()).toBe(tokenBefore);
        expect(state.getUserTransactionDraft('0')?.comment).toBe('synthetic private draft');
        expect(state.getUserSelectedLedgerId()).toBe(ledger);
        state.decryptToken(); flags.locked = false;
        expect(state.getUserPendingTransactionCreate(ledger)).toEqual(pending);
        expect(state.getUserSelectedLedgerId()).toBe(ledger);
    });

    it('restores journal encryption and session state when the final token write is refused', () => {
        state.updateUserPendingTransactionCreate(pending, ledger);
        const original = [...local.data.entries()];
        local.failKey = 'ebk_user_token';
        expect(() => state.encryptToken('test-owner', randomBytes(8).toString('hex'))).toThrow();
        expect([...local.data.entries()]).toEqual(original);
        expect(state.hasUserAppLockState()).toBe(false);
        expect(state.getUserPendingTransactionCreate(ledger)).toEqual(pending);
    });

    it('rolls back earlier record rewrites if storage refuses the lock change', () => {
        state.updateUserTransactionDraft({ comment: 'synthetic first' }, '0');
        state.updateUserPendingTransactionCreate(pending, ledger);
        const original = [...local.data.entries()];
        local.failKey = original.find(([key]) => key.includes('pending:'))![0];
        expect(() => state.encryptToken('test-owner', randomBytes(8).toString('hex'))).toThrow();
        expect([...local.data.entries()]).toEqual(original);
        expect(state.hasUserAppLockState()).toBe(false);
    });
});
