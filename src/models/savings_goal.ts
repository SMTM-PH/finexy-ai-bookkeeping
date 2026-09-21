export const SavingsGoalFundDirection = {
    Deposit: 1,
    Withdraw: 2
} as const;

export const MaximumGoalAmount = 9999999999999;

export interface SavingsGoalInfoResponse {
    readonly id: string;
    readonly ledgerId: string;
    readonly uid: string;
    readonly name: string;
    readonly targetAmount: number;
    readonly savedAmount: number;
    readonly achieved: boolean;
    readonly deadlineTime?: number;
    readonly comment: string;
}

export interface SavingsGoalCreateRequest {
    readonly ledgerId?: string;
    readonly name: string;
    readonly targetAmount: number;
    readonly deadlineTime?: number;
    readonly comment?: string;
}

export interface SavingsGoalModifyRequest {
    readonly id: string;
    readonly name: string;
    readonly targetAmount: number;
    readonly deadlineTime?: number;
    readonly comment?: string;
}

export interface SavingsGoalFundRequest {
    readonly id: string;
    readonly amount: number;
    readonly accountId: string;
    readonly comment?: string;
}

export interface SavingsGoalFundInfoResponse {
    readonly id: string;
    readonly goalId: string;
    readonly uid: string;
    readonly direction: number;
    readonly amount: number;
    readonly accountId: string;
    readonly transactionId?: string;
    readonly comment: string;
    readonly createdTime: number;
}

function parsePositiveId(value: unknown, label: string): string {
    if (typeof value !== 'string' || !value || value === '0') {
        throw new Error(`invalid ${label}`);
    }
    return value;
}

function parseAmount(value: unknown, label: string): number {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > MaximumGoalAmount) {
        throw new Error(`invalid ${label}`);
    }
    return value;
}

/**
 * One savings goal of one ledger. The goal never stores a balance: the saved
 * amount is derived from the immutable fund movements referencing it, and
 * deposits and withdrawals are never income or expense.
 */
export class SavingsGoal {
    readonly id: string;
    readonly ledgerId: string;
    readonly uid: string;
    readonly name: string;
    readonly targetAmount: number;
    readonly savedAmount: number;
    readonly achieved: boolean;
    readonly deadlineTime: number;
    readonly comment: string;

    private constructor(info: SavingsGoalInfoResponse) {
        this.id = parsePositiveId(info.id, 'savings goal id');
        if (typeof info.ledgerId !== 'string' || !/^\d+$/.test(info.ledgerId)) {
            throw new Error('invalid savings goal ledger id');
        }
        this.ledgerId = info.ledgerId;
        this.uid = parsePositiveId(info.uid, 'savings goal owner');
        this.name = typeof info.name === 'string' && info.name ? info.name : '';
        this.targetAmount = parseAmount(info.targetAmount, 'savings goal target amount');
        this.savedAmount = parseAmount(info.savedAmount, 'savings goal saved amount');
        this.achieved = info.achieved === true;
        this.deadlineTime = typeof info.deadlineTime === 'number' && info.deadlineTime > 0 ? info.deadlineTime : 0;
        this.comment = typeof info.comment === 'string' ? info.comment : '';

        if (!this.name) {
            throw new Error('invalid savings goal name');
        }
    }

    static of(info: SavingsGoalInfoResponse): SavingsGoal {
        return new SavingsGoal(info);
    }

    get progressPercent(): number {
        if (this.targetAmount <= 0) {
            return 0;
        }
        return (this.savedAmount * 100) / this.targetAmount;
    }
}

/**
 * One immutable movement into or out of one goal. A deposit moves ledger
 * funds into the goal, and a withdraw returns them to a ledger account. Each
 * current movement links to the transfer transaction that updates the account.
 */
export class SavingsGoalFund {
    readonly id: string;
    readonly goalId: string;
    readonly uid: string;
    readonly direction: number;
    readonly amount: number;
    readonly accountId: string;
    readonly transactionId: string;
    readonly comment: string;
    readonly createdTime: number;

    private constructor(info: SavingsGoalFundInfoResponse) {
        this.id = parsePositiveId(info.id, 'savings goal fund id');
        this.goalId = parsePositiveId(info.goalId, 'savings goal fund goal id');
        this.uid = parsePositiveId(info.uid, 'savings goal fund owner');
        this.direction = info.direction;
        this.amount = parseAmount(info.amount, 'savings goal fund amount');
        this.accountId = parsePositiveId(info.accountId, 'savings goal fund account');
        this.transactionId = typeof info.transactionId === 'string' && info.transactionId
            ? parsePositiveId(info.transactionId, 'savings goal fund transaction')
            : '';
        this.comment = typeof info.comment === 'string' ? info.comment : '';
        this.createdTime = typeof info.createdTime === 'number' && info.createdTime > 0 ? info.createdTime : 0;

        if (this.direction !== SavingsGoalFundDirection.Deposit && this.direction !== SavingsGoalFundDirection.Withdraw) {
            throw new Error('invalid savings goal fund direction');
        }
    }

    static of(info: SavingsGoalFundInfoResponse): SavingsGoalFund {
        return new SavingsGoalFund(info);
    }

    get isDeposit(): boolean {
        return this.direction === SavingsGoalFundDirection.Deposit;
    }
}
