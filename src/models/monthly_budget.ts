export interface MonthlyBudgetInfoResponse {
    readonly id: string;
    readonly yearMonth: number;
    readonly amount: number;
    readonly categoryAmounts: Record<string, number>;
}

export interface MonthlyBudgetSetRequest {
    readonly yearMonth: number;
    readonly amount: number;
    readonly categoryAmounts?: Record<string, number>;
}

export interface MonthlyBudgetDeleteRequest {
    readonly yearMonth: number;
}
