import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const state = vi.hoisted(() => ({ identity: 'synthetic-server:user-a', username: 'user-a', loggedIn: true, preference: '9007199254740993', damaged: false }));
vi.mock('@/lib/userstate.ts', () => ({
    getTransactionStateIdentity: () => state.identity,
    getCurrentUserInfo: () => ({ username: state.username }),
    isUserLogined: () => state.loggedIn,
    getUserSelectedLedgerId: () => state.preference,
    inspectUserSelectedLedgerId: () => { if (state.damaged) throw new Error('Synthetic damaged preference'); return state.preference; },
    updateUserSelectedLedgerId: vi.fn((id: string) => { state.preference = id; })
}));
vi.mock('@/lib/services.ts', () => ({ default: { listLedgers: vi.fn(), listLedgerMembers: vi.fn(), getProfile: vi.fn() } }));
import services from '@/lib/services.ts';
import { updateUserSelectedLedgerId } from '@/lib/userstate.ts';
import { useLedgersStore } from '../ledger.ts';

const ledger = (id: string) => ({ id, ownerUid: '1', type: 1, name: `Synthetic ${id}`, comment: '', createdTime: 1 });
const response = (result: unknown) => ({ data: { success: true, result } }) as never;
const members = (id = '9007199254740993') => [{ id: '10', ledgerId: id, uid: '1', role: 1, status: 1, isCurrentUser: true }];
function deferred() {
    let resolve!: (value: never) => void;
    const promise = new Promise<never>(done => { resolve = done; });
    return { promise, resolve };
}
beforeEach(() => {
    setActivePinia(createPinia()); vi.resetAllMocks();
    state.identity = 'synthetic-server:user-a'; state.username = 'user-a'; state.loggedIn = true;
    state.preference = '9007199254740993'; state.damaged = false;
    vi.mocked(services.listLedgers).mockResolvedValue(response([ledger(state.preference), ledger('8')]));
    vi.mocked(services.listLedgerMembers).mockImplementation(async ({ ledgerId }) => response(members(ledgerId)));
    vi.mocked(services.getProfile).mockResolvedValue(response({ username: state.username }));
});

describe('strict selection before opening an original route', () => {
    it('waits for live membership before restoring the existing preference and never writes it', async () => {
        const store = useLedgersStore(), access = deferred();
        vi.mocked(services.listLedgerMembers).mockReturnValueOnce(access.promise);
        const restoring = store.ensureStrictSelection();
        await vi.waitFor(() => expect(services.listLedgerMembers).toHaveBeenCalledOnce());
        expect(store.selectedLedgerId).toBe('0'); expect(store.ledgers).toEqual([]);
        access.resolve(response(members()));
        await expect(restoring).resolves.toBe('9007199254740993');
        expect(store.selectedLedgerId).toBe('9007199254740993');
        expect(services.listLedgers).toHaveBeenCalledWith({ expectedUsername: 'user-a', ignoreError: true });
        expect(services.listLedgerMembers).toHaveBeenCalledWith({ ledgerId: '9007199254740993', expectedUsername: 'user-a', ignoreError: true });
        expect(updateUserSelectedLedgerId).not.toHaveBeenCalled();
    });
    it('verifies the live profile for the implicit default ledger without fabricating membership', async () => {
        state.preference = '0'; const store = useLedgersStore();
        await expect(store.ensureStrictSelection()).resolves.toBe('0');
        expect(services.getProfile).toHaveBeenCalledWith({ expectedUsername: 'user-a', ignoreError: true });
        expect(services.listLedgerMembers).not.toHaveBeenCalled();
        expect(updateUserSelectedLedgerId).not.toHaveBeenCalled();
    });
    it('rejects an inaccessible persisted choice and preserves its bytes instead of saving zero', async () => {
        vi.mocked(services.listLedgers).mockResolvedValueOnce(response([ledger('8')]));
        const store = useLedgersStore();
        await expect(store.ensureStrictSelection()).rejects.toThrow('原账本不可访问');
        expect(state.preference).toBe('9007199254740993'); expect(store.ledgers).toEqual([]);
        expect(services.listLedgerMembers).not.toHaveBeenCalled(); expect(updateUserSelectedLedgerId).not.toHaveBeenCalled();
    });
    it('rejects missing, duplicated or default-row list responses before restoring anything', async () => {
        const store = useLedgersStore();
        for (const bad of [{ data: { success: true } }, response([ledger(state.preference), ledger(state.preference)]), response([ledger('0')])]) {
            vi.mocked(services.listLedgers).mockResolvedValueOnce(bad as never);
            await expect(store.ensureStrictSelection()).rejects.toBeDefined();
            expect(store.selectedLedgerId).toBe('0'); expect(store.ledgers).toEqual([]);
        }
        expect(services.listLedgerMembers).not.toHaveBeenCalled(); expect(updateUserSelectedLedgerId).not.toHaveBeenCalled();
    });
    it('rejects unreadable preferences or missing login before any HTTP', async () => {
        const store = useLedgersStore(); state.damaged = true;
        await expect(store.ensureStrictSelection()).rejects.toThrow('damaged');
        state.damaged = false; state.loggedIn = false;
        await expect(store.ensureStrictSelection()).rejects.toThrow('登录');
        expect(services.listLedgers).not.toHaveBeenCalled();
    });
    it('rejects permission failure and mismatched or absent active membership without changing choice', async () => {
        const store = useLedgersStore();
        for (const bad of [[], members('8'), members().map(row => ({ ...row, status: 2 }))]) {
            vi.mocked(services.listLedgerMembers).mockResolvedValueOnce(response(bad));
            await expect(store.ensureStrictSelection()).rejects.toBeDefined();
            expect(store.ledgers).toEqual([]); expect(store.selectedLedgerId).toBe('0');
        }
        vi.mocked(services.listLedgerMembers).mockRejectedValueOnce({ response: { status: 403 } });
        await expect(store.ensureStrictSelection()).rejects.toBeDefined();
        expect(updateUserSelectedLedgerId).not.toHaveBeenCalled();
    });
    it('does not accept a profile for another account as default-ledger verification', async () => {
        state.preference = '0'; const store = useLedgersStore();
        vi.mocked(services.getProfile).mockResolvedValueOnce(response({ username: 'user-b' }));
        await expect(store.ensureStrictSelection()).rejects.toThrow('当前登录');
        expect(store.ledgers).toEqual([]);
    });
    it('ignores a delayed list after account, persisted preference, route or reset changes', async () => {
        for (const invalidation of ['identity', 'username', 'preference', 'route', 'reset']) {
            state.identity = 'synthetic-server:user-a'; state.username = 'user-a'; state.preference = '9007199254740993'; current = true;
            const store = useLedgersStore(), list = deferred();
            vi.mocked(services.listLedgers).mockReturnValueOnce(list.promise);
            const pending = store.ensureStrictSelection(() => current);
            const rejected = expect(pending).rejects.toThrow();
            if (invalidation === 'identity') state.identity = 'other:user';
            else if (invalidation === 'username') state.username = 'user-b';
            else if (invalidation === 'preference') state.preference = '8';
            else if (invalidation === 'route') current = false;
            else store.reset();
            list.resolve(response([ledger('9007199254740993'), ledger('8')]));
            await rejected; expect(store.ledgers).toEqual([]); expect(store.selectedLedgerId).toBe('0');
            store.reset();
        }
        expect(updateUserSelectedLedgerId).not.toHaveBeenCalled();
    });
    it('uses the already selected ledger in a warm session and rejects a selected A to B to A race', async () => {
        const store = useLedgersStore(); await store.load();
        state.preference = '8'; // Another tab preference does not authorize switching this page.
        await expect(store.ensureStrictSelection()).resolves.toBe('9007199254740993');
        const list = deferred(); vi.mocked(services.listLedgers).mockReturnValueOnce(list.promise);
        const pending = store.ensureStrictSelection(); const rejected = expect(pending).rejects.toThrow();
        store.select('8'); store.select('9007199254740993');
        list.resolve(response([ledger('9007199254740993'), ledger('8')])); await rejected;
        expect(store.selectedLedgerId).toBe('9007199254740993');
    });
});
let current = true;
