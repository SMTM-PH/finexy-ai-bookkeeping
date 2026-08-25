export enum AIReviewSourceType {
    Text = 1,
    Image = 2,
    Import = 3
}

export interface AIReviewItemInfoResponse {
    readonly id: string;
    readonly sourceType: AIReviewSourceType;
    readonly status: number;
    readonly sourceText: string;
    readonly recognizedData?: RecognizedTransactionResponse;
    readonly failureReason: string;
    readonly createdUnixTime: number;
}

export interface AIReviewItemCreateRequest {
    readonly sourceType: AIReviewSourceType;
    readonly sourceText: string;
    readonly recognizedData?: RecognizedTransactionResponse;
    readonly failureReason: string;
}
import type { RecognizedTransactionResponse } from '@/models/large_language_model.ts';
