import { describe, expect, it } from 'vitest';

import {
    FamilyGroup,
    FamilyInvitation,
    FamilyInvitationStatus,
    FamilyMember,
    FamilyMemberRole
} from '../family.ts';
import { Ledger } from '../ledger.ts';
import { SavingsGoal, SavingsGoalFund } from '../savings_goal.ts';

describe('family models', () => {
    it('parses a family group with ids and member count', () => {
        const group = FamilyGroup.of({
            id: '7',
            ownerUid: '100',
            name: '温暖小家',
            comment: '',
            memberCount: 3,
            createdTime: 1700000000
        });
        expect(group.id).toBe('7');
        expect(group.ownerUid).toBe('100');
        expect(group.memberCount).toBe(3);
    });

    it('rejects a family group without an id or owner', () => {
        expect(() =>
            FamilyGroup.of({
                id: '0',
                ownerUid: '100',
                name: 'x',
                comment: '',
                memberCount: 0,
                createdTime: 0
            })
        ).toThrow();
        expect(() =>
            FamilyGroup.of({
                id: '7',
                ownerUid: '',
                name: 'x',
                comment: '',
                memberCount: 0,
                createdTime: 0
            })
        ).toThrow();
    });

    it('exposes role capabilities on members', () => {
        const owner = FamilyMember.of({
            id: '1',
            familyId: '7',
            uid: '100',
            role: FamilyMemberRole.Owner,
            status: 1,
            nickname: '林悦',
            joinedTime: 1700000000
        });
        const member = FamilyMember.of({
            id: '2',
            familyId: '7',
            uid: '200',
            role: FamilyMemberRole.Member,
            status: 1,
            joinedTime: 1700000001
        });
        const viewer = FamilyMember.of({
            id: '3',
            familyId: '7',
            uid: '300',
            role: FamilyMemberRole.Viewer,
            status: 1,
            joinedTime: 1700000002
        });

        expect(owner.canManage).toBe(true);
        expect(member.canManage).toBe(false);
        expect(member.canWrite).toBe(true);
        expect(viewer.canWrite).toBe(false);
        expect(owner.isOwner).toBe(true);
        expect(owner.nickname).toBe('林悦');
        expect(member.nickname).toBe('');
    });

    it('rejects members with unknown role or status', () => {
        expect(() =>
            FamilyMember.of({
                id: '2',
                familyId: '7',
                uid: '200',
                role: 9,
                status: 1,
                joinedTime: 0
            })
        ).toThrow();
        expect(() =>
            FamilyMember.of({
                id: '2',
                familyId: '7',
                uid: '200',
                role: 3,
                status: 9,
                joinedTime: 0
            })
        ).toThrow();
    });

    it('treats only pending invitations as actionable', () => {
        const pending = FamilyInvitation.of({
            id: '5',
            familyId: '7',
            inviteeName: '爸爸',
            role: FamilyMemberRole.Member,
            status: FamilyInvitationStatus.Pending,
            token: 'token-1',
            createdTime: 1700000000,
            expiredTime: 1700086400
        });
        const revoked = FamilyInvitation.of({
            ...pending,
            status: FamilyInvitationStatus.Revoked
        });

        expect(pending.isPending).toBe(true);
        expect(revoked.isPending).toBe(false);
        expect(revoked.isRevoked).toBe(true);
    });

    it('rejects invitations granting the owner or admin role', () => {
        expect(() =>
            FamilyInvitation.of({
                id: '5',
                familyId: '7',
                inviteeName: 'x',
                role: FamilyMemberRole.Admin,
                status: 1,
                token: 't',
                createdTime: 1,
                expiredTime: 2
            })
        ).toThrow();
    });
});

describe('ledger model', () => {
    it('accepts the implicit default personal ledger and family ledgers', () => {
        const personal = Ledger.of({
            id: '0',
            ownerUid: '100',
            type: 1,
            name: '默认个人账本',
            comment: '',
            createdTime: 0
        });
        const family = Ledger.of({
            id: '9',
            ownerUid: '100',
            type: 2,
            familyId: '7',
            name: '家庭账本',
            comment: '',
            createdTime: 1700000000
        });

        expect(personal.id).toBe('0');
        expect(personal.isFamilyLedger).toBe(false);
        expect(family.isFamilyLedger).toBe(true);
        expect(family.familyId).toBe('7');
    });

    it('rejects ledgers with mismatched family scope', () => {
        expect(() =>
            Ledger.of({
                id: '9',
                ownerUid: '100',
                type: 1,
                familyId: '7',
                name: '个人账本',
                comment: '',
                createdTime: 0
            })
        ).toThrow();
        expect(() =>
            Ledger.of({
                id: '9',
                ownerUid: '100',
                type: 2,
                name: '家庭账本',
                comment: '',
                createdTime: 0
            })
        ).toThrow();
    });
});

describe('savings goal models', () => {
    it('derives progress from saved and target amounts', () => {
        const goal = SavingsGoal.of({
            id: '5',
            ledgerId: '0',
            uid: '100',
            name: '全家旅行基金',
            targetAmount: 200000,
            savedAmount: 105000,
            achieved: false,
            comment: ''
        });

        expect(goal.progressPercent).toBeCloseTo(52.5);
        expect(goal.achieved).toBe(false);
    });

    it('rejects goals with invalid amounts or names', () => {
        expect(() =>
            SavingsGoal.of({
                id: '5',
                ledgerId: '0',
                uid: '100',
                name: '',
                targetAmount: 100,
                savedAmount: 0,
                achieved: false,
                comment: ''
            })
        ).toThrow();
        expect(() =>
            SavingsGoal.of({
                id: '5',
                ledgerId: '0',
                uid: '100',
                name: 'x',
                targetAmount: -1,
                savedAmount: 0,
                achieved: false,
                comment: ''
            })
        ).toThrow();
        expect(() =>
            SavingsGoal.of({
                id: '5',
                ledgerId: '0',
                uid: '100',
                name: 'x',
                targetAmount: 100,
                savedAmount: NaN,
                achieved: false,
                comment: ''
            })
        ).toThrow();
    });

    it('parses fund movements and rejects unknown directions', () => {
        const fund = SavingsGoalFund.of({
            id: '11',
            goalId: '5',
            uid: '100',
            direction: 1,
            amount: 50000,
            accountId: '2',
            comment: '九月存入',
            createdTime: 1700000000
        });
        expect(fund.isDeposit).toBe(true);

        expect(() =>
            SavingsGoalFund.of({
                id: '12',
                goalId: '5',
                uid: '100',
                direction: 3,
                amount: 1,
                accountId: '2',
                comment: '',
                createdTime: 0
            })
        ).toThrow();
    });
});
