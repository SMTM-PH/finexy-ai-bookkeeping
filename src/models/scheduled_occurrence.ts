import { assertPeriodicSnapshot, isPeriodicStringId } from './transaction_template.ts';

export const ScheduledOccurrenceStatus = {
    Pending: 1,
    Confirmed: 2,
    Dismissed: 3
} as const;

export interface ScheduledOccurrenceSnapshotResponse {
    ledgerId?: string;
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
    ledgerId?: string;
    templateId: string;
    scheduledUnixTime: number;
    status: number;
    transactionId: string;
    snapshot: ScheduledOccurrenceSnapshotResponse;
}

export interface ScheduledOccurrenceActionRequest {
    ledgerId?: string;
    templateId: string;
    scheduledUnixTime: number;
}

/**
 * One due schedule waiting for user confirmation. Confirming posts the
 * captured snapshot exactly once on the server; the occurrence itself never
 * changes an account balance.
 */
export class ScheduledOccurrence {
    readonly ledgerId: string;
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
    readonly tagIds: readonly string[];

    private constructor(info: ScheduledOccurrenceInfoResponse) {
        this.ledgerId = info.ledgerId ?? '0';
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
        this.tagIds = Object.freeze([...info.snapshot.tagIds]);
        Object.freeze(this);
    }

    static of(info: ScheduledOccurrenceInfoResponse, expectedLedgerId = info?.ledgerId ?? '0'): ScheduledOccurrence {
        if (!info || !isPeriodicStringId(info.templateId, false) || !isPeriodicStringId(expectedLedgerId)
            || (info.ledgerId ?? '0') !== expectedLedgerId || !isPeriodicStringId(info.transactionId)) {
            throw new Error('invalid scheduled occurrence');
        }
        if (!Number.isSafeInteger(info.scheduledUnixTime) || info.scheduledUnixTime <= 0) {
            throw new Error('invalid scheduled occurrence time');
        }
        if (info.status !== ScheduledOccurrenceStatus.Pending &&
            info.status !== ScheduledOccurrenceStatus.Confirmed &&
            info.status !== ScheduledOccurrenceStatus.Dismissed) {
            throw new Error('invalid scheduled occurrence status');
        }
        const snapshot = info.snapshot;
        if (!snapshot || (snapshot.ledgerId ?? '0') !== expectedLedgerId
            || typeof snapshot.name !== 'string' || !snapshot.name.trim()
            || (info.status === ScheduledOccurrenceStatus.Confirmed ? info.transactionId === '0' : info.transactionId !== '0')) {
            throw new Error('incomplete scheduled occurrence snapshot');
        }
        assertPeriodicSnapshot(snapshot);
        return new ScheduledOccurrence({ ...info, ledgerId: expectedLedgerId });
    }

    get isPending(): boolean {
        return this.status === ScheduledOccurrenceStatus.Pending;
    }

    get isDismissed(): boolean {
        return this.status === ScheduledOccurrenceStatus.Dismissed;
    }

    get actionRequest(): ScheduledOccurrenceActionRequest {
        return {
            ledgerId: this.ledgerId,
            templateId: this.templateId,
            scheduledUnixTime: this.scheduledUnixTime
        };
    }
}
