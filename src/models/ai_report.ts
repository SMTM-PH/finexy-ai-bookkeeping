export enum AIReportStatus {
    Pending = 1,
    Completed = 2,
    Failed = 3
}

export interface AIReportInfoResponse {
    readonly id: string;
    readonly yearMonth: number;
    readonly comparedYearMonth: number;
    readonly status: AIReportStatus;
    readonly provider: string;
    readonly modelId: string;
    readonly content: string;
    readonly errorMessage: string;
    readonly generatedUnixTime: number;
}
