export const LedgerType = {
    Personal: 1,
    Family: 2
} as const;

export const DefaultLedgerId = '0';

export interface LedgerInfoResponse {
    readonly id: string;
    readonly ownerUid: string;
    readonly type: number;
    readonly familyId?: string;
    readonly name: string;
    readonly comment: string;
    readonly createdTime: number;
}

export interface LedgerCreateRequest {
    readonly type: number;
    readonly familyId?: string;
    readonly name: string;
    readonly comment?: string;
}

export interface LedgerModifyRequest {
    readonly id: string;
    readonly name: string;
    readonly comment?: string;
}

export const LedgerMemberRole = {
    Owner: 1,
    Admin: 2,
    Member: 3,
    Viewer: 4
} as const;

export interface LedgerMemberInfoResponse {
    readonly id: string;
    readonly ledgerId: string;
    readonly uid: string;
    readonly role: number;
    readonly status: number;
    readonly nickname?: string;
    readonly joinedTime: number;
    readonly isCurrentUser?: boolean;
}

export interface LedgerInvitationInfoResponse {
    readonly id: string;
    readonly ledgerId: string;
    readonly inviteeName: string;
    readonly role: number;
    readonly status: number;
    readonly token: string;
    readonly createdTime: number;
    readonly expiredTime: number;
}

export interface LedgerInvitationPreviewResponse {
    readonly ledger: LedgerInfoResponse;
    readonly role: number;
    readonly inviteeName: string;
    readonly inviterNickname?: string;
    readonly expiredTime: number;
}

export interface LedgerDeletePreviewResponse {
    readonly ledgerId: string;
    readonly ledgerName: string;
    readonly activeMemberCount: number;
    readonly pendingInvitationCount: number;
    readonly accountCount: number;
    readonly transactionCount: number;
    readonly savingsGoalCount: number;
    readonly savingsGoalFundCount: number;
    readonly canDelete: boolean;
    readonly blockingReasons: string[];
}

export interface LedgerOverviewResponse {
    readonly ledgerId: string;
    readonly ledgerName: string;
    readonly activeMemberCount: number;
    readonly accountCount: number;
    readonly transactionCount: number;
    readonly savingsGoalCount: number;
    readonly balances: Array<{ readonly currency: string; readonly balance: number }>;
}

export class LedgerOverview {
    readonly ledgerId: string;
    readonly ledgerName: string;
    readonly activeMemberCount: number;
    readonly accountCount: number;
    readonly transactionCount: number;
    readonly savingsGoalCount: number;
    readonly balances: ReadonlyArray<{ currency: string; balance: number }>;

    private constructor(info: LedgerOverviewResponse) {
        if (typeof info.ledgerId !== 'string' || !/^\d+$/.test(info.ledgerId)) throw new Error('invalid overview ledger id');
        if (typeof info.ledgerName !== 'string' || !info.ledgerName.trim()) throw new Error('invalid overview ledger name');
        const counts = [info.activeMemberCount, info.accountCount, info.transactionCount, info.savingsGoalCount];
        if (counts.some(value => !Number.isSafeInteger(value) || value < 0)) throw new Error('invalid overview counts');
        if (!Array.isArray(info.balances)) throw new Error('invalid overview balances');
        const seen = new Set<string>();
        this.balances = info.balances.map(item => {
            if (!/^[A-Z]{3}$/.test(item.currency) || seen.has(item.currency) || !Number.isSafeInteger(item.balance)) throw new Error('invalid overview balance');
            seen.add(item.currency); return { currency: item.currency, balance: item.balance };
        });
        this.ledgerId = info.ledgerId; this.ledgerName = info.ledgerName.trim();
        this.activeMemberCount = info.activeMemberCount; this.accountCount = info.accountCount;
        this.transactionCount = info.transactionCount; this.savingsGoalCount = info.savingsGoalCount;
    }

    static of(info: LedgerOverviewResponse): LedgerOverview { return new LedgerOverview(info); }
}

export class LedgerDeletePreview {
    private static readonly blockerNames = new Set(['accounts', 'transactions', 'savingsGoals']);
    readonly ledgerId: string;
    readonly ledgerName: string;
    readonly activeMemberCount: number;
    readonly pendingInvitationCount: number;
    readonly accountCount: number;
    readonly transactionCount: number;
    readonly savingsGoalCount: number;
    readonly savingsGoalFundCount: number;
    readonly canDelete: boolean;
    readonly blockingReasons: string[];

    private constructor(info: LedgerDeletePreviewResponse) {
        if (typeof info.ledgerId !== 'string' || !/^\d+$/.test(info.ledgerId) || info.ledgerId === '0') throw new Error('invalid ledger id');
        if (typeof info.ledgerName !== 'string' || !info.ledgerName.trim()) throw new Error('invalid ledger name');
        const counts = [info.activeMemberCount, info.pendingInvitationCount, info.accountCount, info.transactionCount, info.savingsGoalCount, info.savingsGoalFundCount];
        if (counts.some(value => !Number.isSafeInteger(value) || value < 0)) throw new Error('invalid ledger delete counts');
        if (!Array.isArray(info.blockingReasons) || info.blockingReasons.some(value => !LedgerDeletePreview.blockerNames.has(value))) throw new Error('invalid ledger delete blockers');
        const blocked = info.accountCount > 0 || info.transactionCount > 0 || info.savingsGoalCount > 0;
        if (info.canDelete === blocked) throw new Error('inconsistent ledger delete state');
        this.ledgerId = info.ledgerId; this.ledgerName = info.ledgerName;
        this.activeMemberCount = info.activeMemberCount; this.pendingInvitationCount = info.pendingInvitationCount;
        this.accountCount = info.accountCount; this.transactionCount = info.transactionCount;
        this.savingsGoalCount = info.savingsGoalCount; this.savingsGoalFundCount = info.savingsGoalFundCount;
        this.canDelete = info.canDelete; this.blockingReasons = [...info.blockingReasons];
    }

    static of(info: LedgerDeletePreviewResponse): LedgerDeletePreview { return new LedgerDeletePreview(info); }
}

export class LedgerInvitationPreview {
    readonly ledger: Ledger;
    readonly role: number;
    readonly inviteeName: string;
    readonly inviterNickname: string;
    readonly expiredTime: number;

    private constructor(info: LedgerInvitationPreviewResponse) {
        this.ledger = Ledger.of(info.ledger);
        if (info.role !== LedgerMemberRole.Member && info.role !== LedgerMemberRole.Viewer) throw new Error('invalid invitation role');
        if (typeof info.inviteeName !== 'string' || !info.inviteeName.trim()) throw new Error('invalid invitation label');
        if (!Number.isSafeInteger(info.expiredTime) || info.expiredTime <= 0) throw new Error('invalid invitation expiry');
        this.role = info.role;
        this.inviteeName = info.inviteeName.trim();
        this.inviterNickname = typeof info.inviterNickname === 'string' ? info.inviterNickname.trim() : '';
        this.expiredTime = info.expiredTime;
    }

    static of(info: LedgerInvitationPreviewResponse): LedgerInvitationPreview {
        return new LedgerInvitationPreview(info);
    }
}

/**
 * One book. Id zero is the user's implicit default personal ledger, which
 * never has a server row; every user always has at least that one.
 */
export class Ledger {
    readonly id: string;
    readonly ownerUid: string;
    readonly type: number;
    readonly familyId: string;
    readonly name: string;
    readonly comment: string;
    readonly createdTime: number;

    private constructor(info: LedgerInfoResponse) {
        if (typeof info.id !== 'string' || !info) {
            throw new Error('invalid ledger id');
        }
        this.id = info.id;
        this.ownerUid = typeof info.ownerUid === 'string' && info.ownerUid && info.ownerUid !== '0' ? info.ownerUid : '';
        this.type = info.type;
        this.familyId = typeof info.familyId === 'string' && info.familyId && info.familyId !== '0' ? info.familyId : '';
        this.name = typeof info.name === 'string' && info.name ? info.name : '';
        this.comment = typeof info.comment === 'string' ? info.comment : '';
        this.createdTime = typeof info.createdTime === 'number' && info.createdTime > 0 ? info.createdTime : 0;

        if (this.type !== LedgerType.Personal && this.type !== LedgerType.Family) {
            throw new Error('invalid ledger type');
        }
        if (!this.name) {
            throw new Error('invalid ledger name');
        }
        if (this.type === LedgerType.Personal && this.familyId) {
            throw new Error('personal ledger must not reference a family');
        }
        if (this.type === LedgerType.Family && !this.familyId) {
            throw new Error('family ledger requires a family');
        }
    }

    static of(info: LedgerInfoResponse): Ledger {
        return new Ledger(info);
    }

    get isFamilyLedger(): boolean {
        return this.type === LedgerType.Family;
    }
}
