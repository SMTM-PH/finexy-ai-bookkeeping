import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PendingAIReviewCreate, PendingAIReviewConfirm } from '@/models/ai_review_ledger.ts';

const environment = vi.hoisted(() => ({ basePath: '/', lock: false }));
vi.mock('../web.ts', () => ({ getBasePath: () => environment.basePath }));
vi.mock('../settings.ts', () => ({ isEnableApplicationLock: () => environment.lock }));
vi.mock('../logger.ts', () => ({ default: { error: vi.fn(), warn: vi.fn() } }));
vi.mock('../common.ts', () => ({ isString: (value: unknown) => typeof value === 'string', isObject: (value: unknown) => !!value && typeof value === 'object' }));
import { updateCurrentUserInfo, getTransactionStateIdentity, getUserPendingAIReviewCreate, updateUserPendingAIReviewCreate,
    getUserPendingAIReviewConfirm, updateUserPendingAIReviewConfirm, clearUserPendingAIReviewConfirm,
    clearCurrentTokenAndUserInfo, encryptToken, decryptToken,
    getUserAIReviewEditorDraft, updateUserAIReviewEditorDraft, getUserAIReviewSourceDraft, updateUserAIReviewSourceDraft } from '../userstate.ts';
import { fullReceiptCorners } from '@/models/ai_review_source.ts';

class MemoryStorage implements Storage {
    readonly values = new Map<string, string>();
    fail = false;
    get length() { return this.values.size; }
    key(index: number) { return [...this.values.keys()][index] ?? null; }
    getItem(key: string) { return this.values.get(key) ?? null; }
    setItem(key: string, value: string) { if (this.fail) throw new Error('Synthetic quota denied'); this.values.set(key, value); }
    removeItem(key: string) { this.values.delete(key); }
    clear() { this.values.clear(); }
}
let local: MemoryStorage, session: MemoryStorage;
const creation = (): PendingAIReviewCreate => ({ version: 1, identity: getTransactionStateIdentity()!, request: {
    ledgerId: '9', clientSessionId: 'synthetic-create', sourceType: 1, sourceText: 'Synthetic original content', failureReason: '' } });
const confirmation = (): PendingAIReviewConfirm => ({ version: 1, identity: getTransactionStateIdentity()!, request: { ledgerId: '9', id: '12', transaction: {
    ledgerId: '9', clientSessionId: 'synthetic-confirm', type: 3, time: 1760000000, utcOffset: 480, categoryId: '31', sourceAccountId: '21',
    destinationAccountId: '0', sourceAmount: -123, destinationAmount: 0, hideAmount: true, tagIds: [], pictureIds: [], comment: 'Synthetic refund' } } });
beforeEach(() => {
    local = new MemoryStorage(); session = new MemoryStorage(); environment.basePath = '/'; environment.lock = false;
    vi.stubGlobal('localStorage', local); vi.stubGlobal('sessionStorage', session);
    vi.stubGlobal('window', { location: { origin: 'http://127.0.0.1:18133' } });
    updateCurrentUserInfo({ username: 'synthetic-owner' } as never);
});
describe('real scoped AI storage encoding', () => {
    it('protects the image key with app lock while preserving source, crop, rotation and recognition after logout', () => {
        const identity = getTransactionStateIdentity()!, draft = { version: 1 as const, identity, revision: 'source-revision', input: {
            ledgerId: '9', sourceType: 2 as const, sourceText: 'Synthetic receipt source', rotation: 270 as const, corners: fullReceiptCorners(),
            image: { blobId: `${identity}ai-source-image:9:synthetic`, secret: 'a'.repeat(64), sha256: 'b'.repeat(64), mime: 'image/png', name: 'synthetic.png', bytes: 123 },
        }, recognizedData: { ledgerId: '9', type: 3, sourceAmount: -123 }, failureReason: '' };
        updateUserAIReviewSourceDraft('9', draft); expect(getUserAIReviewSourceDraft('10')).toBeNull();
        local.setItem('ebk_user_token', 'synthetic-session-value'); encryptToken('synthetic-owner', 'synthetic-pin'); environment.lock = true;
        const stored = [...local.values.entries()].find(([key]) => key.includes('ai-review-source'))![1];
        expect(JSON.parse(stored).encrypted).toBe(true); expect(stored).not.toContain(draft.input.image.secret); expect(stored).not.toContain(draft.input.sourceText);
        expect(getUserAIReviewSourceDraft('9')).toEqual(draft); decryptToken(); environment.lock = false;
        clearCurrentTokenAndUserInfo(true); updateCurrentUserInfo({ username: 'synthetic-other' } as never); expect(getUserAIReviewSourceDraft('9')).toBeNull();
        updateCurrentUserInfo({ username: 'synthetic-owner' } as never); expect(getUserAIReviewSourceDraft('9')).toEqual(draft);
    });
    it('raw AI draft survives logout and app-lock encryption, isolated by account, path and ledger', () => {
        const draft = { version: 1 as const, identity: getTransactionStateIdentity()!, revision: 'synthetic-revision', input: {
            ledgerId: '9', id: '12', transaction: { ...confirmation().request.transaction, pictures: [], timeZone: 'Asia/Shanghai' },
            categorySelections: { expense: '31', income: '', transfer: '' }, transferDirection: 'internal' as const, rawSourceAmount: '=12+', rawDestinationAmount: '1.',
        } };
        updateUserAIReviewEditorDraft('9', draft); expect(getUserAIReviewEditorDraft('10')).toBeNull();
        environment.basePath = '/other/'; expect(getUserAIReviewEditorDraft('9')).toBeNull(); environment.basePath = '/';
        clearCurrentTokenAndUserInfo(true); updateCurrentUserInfo({ username: 'synthetic-other' } as never); expect(getUserAIReviewEditorDraft('9')).toBeNull();
        updateCurrentUserInfo({ username: 'synthetic-owner' } as never); expect(getUserAIReviewEditorDraft('9')).toEqual(draft);
        local.setItem('ebk_user_token', 'synthetic-session-value'); encryptToken('synthetic-owner', 'synthetic-pin'); environment.lock = true;
        const stored = [...local.values.entries()].find(([key]) => key.includes('ai-review-draft'))![1];
        expect(JSON.parse(stored).encrypted).toBe(true); expect(stored).not.toContain('=12+');
        expect(getUserAIReviewEditorDraft('9')).toEqual(draft); decryptToken(); environment.lock = false;
        expect(getUserAIReviewEditorDraft('9')).toEqual(draft);
    });
    it('separates journals by server path, account and ledger and keeps exact content after reads', () => {
        const initialIdentity = getTransactionStateIdentity();
        updateUserPendingAIReviewCreate('9', creation()); updateUserPendingAIReviewConfirm('9', confirmation());
        expect(getUserPendingAIReviewCreate('9')).toEqual(creation()); expect(getUserPendingAIReviewConfirm('9')).toEqual(confirmation());
        expect(getUserPendingAIReviewConfirm('10')).toBeNull();
        environment.basePath = '/other/'; expect(getTransactionStateIdentity()).not.toBe(initialIdentity); expect(getUserPendingAIReviewConfirm('9')).toBeNull();
        environment.basePath = '/'; updateCurrentUserInfo({ username: 'synthetic-other' } as never); expect(getUserPendingAIReviewConfirm('9')).toBeNull();
        updateCurrentUserInfo({ username: 'synthetic-owner' } as never); expect(getUserPendingAIReviewConfirm('9')).toEqual(confirmation());
    });
    it('logout retains unknown submissions for original user, then restores after normal login identity', () => {
        const original = confirmation(); updateUserPendingAIReviewConfirm('9', original); clearCurrentTokenAndUserInfo(true);
        expect(getTransactionStateIdentity()).toBeNull(); expect(getUserPendingAIReviewConfirm('9')).toBeNull();
        updateCurrentUserInfo({ username: 'synthetic-owner' } as never); expect(getUserPendingAIReviewConfirm('9')).toEqual(original);
    });
    it.each(['', 'null', '[]', '7', '"synthetic scalar"', '{broken'])('corrupt envelope/payload cannot masquerade as no pending record: %s', payload => {
        updateUserPendingAIReviewConfirm('9', confirmation());
        const key = [...local.values.keys()].find(value => value.includes('ai-review-confirm'))!;
        if (payload === '') local.values.set(key, '');
        else { const envelope = JSON.parse(local.getItem(key)!); envelope.data = payload; local.values.set(key, JSON.stringify(envelope)); }
        expect(() => getUserPendingAIReviewConfirm('9')).toThrow('无法读取');
    });
    it('rejects scope transplantation and preserves the stored original', () => {
        updateUserPendingAIReviewConfirm('9', confirmation());
        const key = [...local.values.keys()].find(value => value.includes('ai-review-confirm'))!;
        local.values.set(key.replace(/:9$/, ':10'), local.getItem(key)!);
        expect(() => getUserPendingAIReviewConfirm('10')).toThrow(); expect(getUserPendingAIReviewConfirm('9')).toEqual(confirmation());
    });
    it('encrypting/decrypting the app lock updates both AI journals without losing their identities or contents', () => {
        const create = creation(), confirm = confirmation();
        updateUserPendingAIReviewCreate('9', create); updateUserPendingAIReviewConfirm('9', confirm);
        local.setItem('ebk_user_token', 'synthetic-session-value'); encryptToken('synthetic-owner', 'synthetic-pin'); environment.lock = true;
        for (const [key, value] of local.values) if (key.includes('ai-review')) {
            expect(JSON.parse(value).encrypted).toBe(true); expect(value).not.toContain('Synthetic original content'); expect(value).not.toContain('Synthetic refund');
        }
        expect(getUserPendingAIReviewCreate('9')).toEqual(create); expect(getUserPendingAIReviewConfirm('9')).toEqual(confirm);
        session.clear(); expect(() => getUserPendingAIReviewConfirm('9')).toThrow('无法读取');
    });
    it('decrypt preserves creation and confirmation after lock is disabled', () => {
        const create = creation(), confirm = confirmation(); updateUserPendingAIReviewCreate('9', create); updateUserPendingAIReviewConfirm('9', confirm);
        local.setItem('ebk_user_token', 'synthetic-session-value'); encryptToken('synthetic-owner', 'synthetic-pin'); environment.lock = true;
        decryptToken(); environment.lock = false;
        expect(getUserPendingAIReviewCreate('9')).toEqual(create); expect(getUserPendingAIReviewConfirm('9')).toEqual(confirm);
    });
    it('storage denial propagates and clear removes only the original confirmation journal', () => {
        updateUserPendingAIReviewCreate('9', creation()); updateUserPendingAIReviewConfirm('9', confirmation());
        local.fail = true; expect(() => updateUserPendingAIReviewConfirm('9', confirmation())).toThrow('quota'); local.fail = false;
        clearUserPendingAIReviewConfirm('9'); expect(getUserPendingAIReviewConfirm('9')).toBeNull(); expect(getUserPendingAIReviewCreate('9')).not.toBeNull();
    });
});
