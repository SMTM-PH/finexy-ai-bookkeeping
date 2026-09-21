export const FamilyMemberRole = {
    Owner: 1,
    Admin: 2,
    Member: 3,
    Viewer: 4
} as const;

export const FamilyMemberStatus = {
    Active: 1,
    Left: 2,
    Removed: 3
} as const;

export const FamilyInvitationStatus = {
    Pending: 1,
    Accepted: 2,
    Revoked: 3,
    Expired: 4,
    Rejected: 5
} as const;

export interface FamilyGroupInfoResponse {
    readonly id: string;
    readonly ownerUid: string;
    readonly name: string;
    readonly comment: string;
    readonly memberCount: number;
    readonly createdTime: number;
}

export interface FamilyGroupCreateRequest {
    readonly name: string;
    readonly comment?: string;
}

export interface FamilyGroupModifyRequest {
    readonly id: string;
    readonly name: string;
    readonly comment?: string;
}

export interface FamilyMemberInfoResponse {
    readonly id: string;
    readonly familyId: string;
    readonly uid: string;
    readonly role: number;
    readonly status: number;
    readonly nickname?: string;
    readonly joinedTime: number;
}

export interface FamilyInvitationInfoResponse {
    readonly id: string;
    readonly familyId: string;
    readonly inviteeName: string;
    readonly role: number;
    readonly status: number;
    readonly token: string;
    readonly createdTime: number;
    readonly expiredTime: number;
    readonly usedTime?: number;
    readonly usedByUid?: string;
}

export interface FamilyInvitationCreateRequest {
    readonly familyId: string;
    readonly inviteeName: string;
    readonly role: number;
    readonly expiresInSeconds?: number;
}

export interface FamilyMemberListRequest {
    readonly familyId: string;
}

export interface FamilyMemberRoleChangeRequest {
    readonly familyId: string;
    readonly memberId: string;
    readonly role: number;
}

export interface FamilyMemberRemoveRequest {
    readonly familyId: string;
    readonly memberId: string;
}

export interface FamilyMemberLeaveRequest {
    readonly familyId: string;
}

export interface FamilyInvitationRevokeRequest {
    readonly familyId: string;
    readonly invitationId: string;
}

export interface FamilyInvitationAcceptRequest {
    readonly token: string;
}

function parseId(value: unknown, label: string): string {
    if (typeof value !== 'string' || !value || value === '0') {
        throw new Error(`invalid ${label}`);
    }
    return value;
}

/**
 * One family sharing family ledgers and savings goals. Membership rows are
 * the only bridge between a user and the family store on the server.
 */
export class FamilyGroup {
    readonly id: string;
    readonly ownerUid: string;
    readonly name: string;
    readonly comment: string;
    readonly memberCount: number;
    readonly createdTime: number;

    private constructor(info: FamilyGroupInfoResponse) {
        this.id = parseId(info.id, 'family id');
        this.ownerUid = parseId(info.ownerUid, 'family owner');
        this.name = typeof info.name === 'string' && info.name ? info.name : '';
        this.comment = typeof info.comment === 'string' ? info.comment : '';
        this.memberCount = typeof info.memberCount === 'number' && info.memberCount >= 0 ? info.memberCount : 0;
        this.createdTime = typeof info.createdTime === 'number' && info.createdTime > 0 ? info.createdTime : 0;
    }

    static of(info: FamilyGroupInfoResponse): FamilyGroup {
        return new FamilyGroup(info);
    }
}

/**
 * One membership of one user in one family. Roles decide capabilities:
 * owner and admin manage, member records, viewer only reads.
 */
export class FamilyMember {
    readonly id: string;
    readonly familyId: string;
    readonly uid: string;
    readonly role: number;
    readonly status: number;
    readonly nickname: string;
    readonly joinedTime: number;

    private constructor(info: FamilyMemberInfoResponse) {
        this.id = parseId(info.id, 'family member id');
        this.familyId = parseId(info.familyId, 'family member family id');
        this.uid = parseId(info.uid, 'family member uid');
        this.role = info.role;
        this.status = info.status;
        this.nickname = typeof info.nickname === 'string' ? info.nickname : '';
        this.joinedTime = typeof info.joinedTime === 'number' && info.joinedTime > 0 ? info.joinedTime : 0;

        if (this.role < FamilyMemberRole.Owner || this.role > FamilyMemberRole.Viewer) {
            throw new Error('invalid family member role');
        }
        if (this.status < FamilyMemberStatus.Active || this.status > FamilyMemberStatus.Removed) {
            throw new Error('invalid family member status');
        }
    }

    static of(info: FamilyMemberInfoResponse): FamilyMember {
        return new FamilyMember(info);
    }

    get isOwner(): boolean {
        return this.role === FamilyMemberRole.Owner;
    }

    get canManage(): boolean {
        return this.role === FamilyMemberRole.Owner || this.role === FamilyMemberRole.Admin;
    }

    get canWrite(): boolean {
        return this.role !== FamilyMemberRole.Viewer;
    }

    get isActive(): boolean {
        return this.status === FamilyMemberStatus.Active;
    }
}

/**
 * One one-shot invitation. The token is the only joining secret; every state
 * other than pending is terminal and the token can no longer be used.
 */
export class FamilyInvitation {
    readonly id: string;
    readonly familyId: string;
    readonly inviteeName: string;
    readonly role: number;
    readonly status: number;
    readonly token: string;
    readonly createdTime: number;
    readonly expiredTime: number;
    readonly usedTime: number;
    readonly usedByUid: string;

    private constructor(info: FamilyInvitationInfoResponse) {
        this.id = parseId(info.id, 'family invitation id');
        this.familyId = parseId(info.familyId, 'family invitation family id');
        this.inviteeName = typeof info.inviteeName === 'string' && info.inviteeName ? info.inviteeName : '';
        this.role = info.role;
        this.status = info.status;
        this.token = typeof info.token === 'string' && info.token ? info.token : '';
        this.createdTime = typeof info.createdTime === 'number' && info.createdTime > 0 ? info.createdTime : 0;
        this.expiredTime = typeof info.expiredTime === 'number' && info.expiredTime > 0 ? info.expiredTime : 0;
        this.usedTime = typeof info.usedTime === 'number' && info.usedTime > 0 ? info.usedTime : 0;
        this.usedByUid = typeof info.usedByUid === 'string' ? info.usedByUid : '';

        if (this.role !== FamilyMemberRole.Member && this.role !== FamilyMemberRole.Viewer) {
            throw new Error('invalid family invitation role');
        }
        if (this.status < FamilyInvitationStatus.Pending || this.status > FamilyInvitationStatus.Rejected) {
            throw new Error('invalid family invitation status');
        }
    }

    static of(info: FamilyInvitationInfoResponse): FamilyInvitation {
        return new FamilyInvitation(info);
    }

    get isPending(): boolean {
        return this.status === FamilyInvitationStatus.Pending;
    }

    get isAccepted(): boolean {
        return this.status === FamilyInvitationStatus.Accepted;
    }

    get isRevoked(): boolean {
        return this.status === FamilyInvitationStatus.Revoked;
    }
}
