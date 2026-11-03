export interface MonthlyBudgetInfoResponse {
    readonly id: string;
    readonly ledgerId: string;
    readonly yearMonth: number;
    readonly amount: number;
    readonly revision: number;
    readonly currency: string;
    readonly categoryAmounts: Record<string, number>;
}

export interface MonthlyBudgetSetRequest {
    readonly ledgerId?: string;
    readonly yearMonth: number;
    readonly amount: number;
    readonly categoryAmounts?: Record<string, number>;
    readonly currency?: string;
    readonly expectedRevision?: number;
}

export interface MonthlyBudgetDeleteRequest {
    readonly ledgerId?: string;
    readonly yearMonth: number;
    readonly expectedRevision?: number;
}
