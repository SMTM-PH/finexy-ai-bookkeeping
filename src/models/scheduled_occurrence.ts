export const ScheduledOccurrenceStatus = {
    Pending: 1,
    Confirmed: 2,
    Dismissed: 3
} as const;

export interface ScheduledOccurrenceSnapshotResponse {
    name: string;
    type: number;
    categoryId: string;
    sourceAccountId: string;
    destinationAccountId: string;
    sourceAmount: number;
    destinationAmount: number;
    utcOffset: number;
    hideAmount: boolean;
    tagIds: string[];
    comment: string;
}

export interface ScheduledOccurrenceInfoResponse {
    templateId: string;
    scheduledUnixTime: number;
    status: number;
    transactionId: string;
    snapshot: ScheduledOccurrenceSnapshotResponse;
}

export interface ScheduledOccurrenceActionRequest {
    templateId: string;
    scheduledUnixTime: number;
}

/**
 * One due schedule waiting for user confirmation. Confirming posts the
 * captured snapshot exactly once on the server; the occurrence itself never
 * changes an account balance.
 */
export class ScheduledOccurrence {
    readonly templateId: string;
    readonly scheduledUnixTime: number;
    readonly status: number;
    readonly transactionId: string;
    readonly name: string;
    readonly type: number;
    readonly categoryId: string;
    readonly sourceAccountId: string;
    readonly destinationAccountId: string;
    readonly sourceAmount: number;
    readonly destinationAmount: number;
    readonly utcOffset: number;
    readonly hideAmount: boolean;
    readonly comment: string;

    private constructor(info: ScheduledOccurrenceInfoResponse) {
        this.templateId = info.templateId;
        this.scheduledUnixTime = info.scheduledUnixTime;
        this.status = info.status;
        this.transactionId = info.transactionId;
        this.name = info.snapshot.name;
        this.type = info.snapshot.type;
        this.categoryId = info.snapshot.categoryId;
        this.sourceAccountId = info.snapshot.sourceAccountId;
        this.destinationAccountId = info.snapshot.destinationAccountId;
        this.sourceAmount = info.snapshot.sourceAmount;
        this.destinationAmount = info.snapshot.destinationAmount;
        this.utcOffset = info.snapshot.utcOffset;
        this.hideAmount = info.snapshot.hideAmount;
        this.comment = info.snapshot.comment;
    }

    static of(info: ScheduledOccurrenceInfoResponse): ScheduledOccurrence {
        if (!info || !info.templateId || info.templateId === '0') {
            throw new Error('invalid scheduled occurrence');
        }
        if (!info.scheduledUnixTime || info.scheduledUnixTime <= 0) {
            throw new Error('invalid scheduled occurrence time');
        }
        if (info.status !== ScheduledOccurrenceStatus.Pending &&
            info.status !== ScheduledOccurrenceStatus.Confirmed &&
            info.status !== ScheduledOccurrenceStatus.Dismissed) {
            throw new Error('invalid scheduled occurrence status');
        }
        const snapshot = info.snapshot;
        if (!snapshot || !snapshot.categoryId || snapshot.categoryId === '0' ||
            !snapshot.sourceAccountId || snapshot.sourceAccountId === '0') {
            throw new Error('incomplete scheduled occurrence snapshot');
        }
        return new ScheduledOccurrence(info);
    }

    get isPending(): boolean {
        return this.status === ScheduledOccurrenceStatus.Pending;
    }

    get isDismissed(): boolean {
        return this.status === ScheduledOccurrenceStatus.Dismissed;
    }

    get actionRequest(): ScheduledOccurrenceActionRequest {
        return {
            templateId: this.templateId,
            scheduledUnixTime: this.scheduledUnixTime
        };
    }
}
