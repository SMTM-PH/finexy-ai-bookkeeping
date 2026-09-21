import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
vi.mock('@/lib/services.ts', () => ({ default: {
    listLedgerMembers: vi.fn(), listLedgerInvitations: vi.fn(), deleteLedger: vi.fn(), listLedgers: vi.fn(), previewLedgerInvitation: vi.fn(), previewLedgerDelete: vi.fn(), getLedgerOverview: vi.fn()
} }));
import services from '@/lib/services.ts';
import { useLedgersStore } from '../ledger.ts';

describe('ledger access loading', () => {
    beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); });
    const response = (ledgerId: string, role: number) => ({ data: { success: true, result: [{
        id: ledgerId, ledgerId, uid: '42', role, status: 1, joinedTime: 1, isCurrentUser: true
    }] } });

    it('loads a reader without requesting manager-only invitations', async () => {
        vi.mocked(services.listLedgerMembers).mockResolvedValue(response('1', 4) as never);
        const store = useLedgersStore();
        await store.loadAccess('1');
        expect(store.members[0]?.role).toBe(4);
        expect(services.listLedgerInvitations).not.toHaveBeenCalled();
    });

    it('ignores a late member response after viewing a different ledger', async () => {
        let resolveFirst!: (value: never) => void;
        vi.mocked(services.listLedgerMembers).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }));
        vi.mocked(services.listLedgerMembers).mockResolvedValueOnce(response('2', 3) as never);
        const store = useLedgersStore();
        const first = store.loadAccess('1');
        await store.loadAccess('2');
        resolveFirst(response('1', 1) as never);
        await first;
        expect(store.members[0]?.ledgerId).toBe('2');
        expect(services.listLedgerInvitations).not.toHaveBeenCalled();
    });

    it('does not restore old members after signing out', async () => {
        let resolveRequest!: (value: never) => void;
        vi.mocked(services.listLedgerMembers).mockImplementationOnce(() => new Promise(resolve => { resolveRequest = resolve; }));
        const store = useLedgersStore();
        const pending = store.loadAccess('1');
        store.reset();
        resolveRequest(response('1', 1) as never);
        await pending;
        expect(store.members).toEqual([]);
    });

    it('protects the default ledger from deletion before calling the API', async () => {
        const store = useLedgersStore();
        await expect(store.deleteLedger('0')).rejects.toThrow('默认账本不能删除');
        expect(services.deleteLedger).not.toHaveBeenCalled();
    });

    it('validates invitation details before accepting', async () => {
        vi.mocked(services.previewLedgerInvitation).mockResolvedValue({ data: { success: true, result: {
            ledger: { id: '9', ownerUid: '1', type: 1, name: 'Shared', comment: 'Household', createdTime: 1 },
            role: 4, inviteeName: 'Alex', inviterNickname: 'Owner', expiredTime: 2_000_000_000
        } } } as never);
        const preview = await useLedgersStore().previewInvitation(' token ');
        expect(services.previewLedgerInvitation).toHaveBeenCalledWith({ token: 'token' });
        expect(preview.ledger.name).toBe('Shared');
        expect(preview.role).toBe(4);
    });

    it('validates the server deletion impact', async () => {
        vi.mocked(services.previewLedgerDelete).mockResolvedValue({ data: { success: true, result: {
            ledgerId: '9', ledgerName: 'Shared', activeMemberCount: 2, pendingInvitationCount: 1,
            accountCount: 1, transactionCount: 3, savingsGoalCount: 0, savingsGoalFundCount: 0,
            canDelete: false, blockingReasons: ['accounts', 'transactions']
        } } } as never);
        const preview = await useLedgersStore().previewDelete('9');
        expect(preview.canDelete).toBe(false);
        expect(preview.transactionCount).toBe(3);
        expect(services.previewLedgerDelete).toHaveBeenCalledWith({ id: '9' });
    });

    it('loads and validates a ledger overview without switching ledgers', async () => {
        vi.mocked(services.getLedgerOverview).mockResolvedValue({ data: { success: true, result: {
            ledgerId: '9', ledgerName: 'Shared', activeMemberCount: 2, accountCount: 3,
            transactionCount: 8, savingsGoalCount: 1,
            balances: [{ currency: 'CNY', balance: 12345 }, { currency: 'USD', balance: -200 }]
        } } } as never);
        const overview = await useLedgersStore().loadOverview('9');
        expect(overview.balances[0]?.balance).toBe(12345);
        expect(overview.transactionCount).toBe(8);
        expect(services.getLedgerOverview).toHaveBeenCalledWith({ ledgerId: '9' });
    });
});
