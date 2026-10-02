export interface AIUsageSummary {
    cacheMeteredRequests: number;
    reasoningMeteredRequests: number;
    requests: number;
    failedRequests: number;
    unmeteredRequests: number;
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
    cachedTokens: number;
    reasoningTokens: number;
    durationMs: number;
    firstRequestTime: number;
}
export interface AIUsageRecord {
    time: number;
    provider: string;
    modelId: string;
    success: boolean;
    usageKnown: boolean;
    cacheUsageKnown: boolean;
    reasoningUsageKnown: boolean;
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
    cachedTokens: number;
    reasoningTokens: number;
    durationMs: number;
}
export interface AIUsageResponse {
    today: AIUsageSummary;
    month: AIUsageSummary;
    all: AIUsageSummary;
    recent: AIUsageRecord[];
    updatedAt: number;
    timezone: string;
}
export interface AIBalanceResponse {
    status: 'available' | 'unsupported' | 'not_configured' | 'unavailable';
    available: boolean;
    balances: { currency: string; totalBalance: string; grantedBalance: string; toppedUpBalance: string }[];
    updatedAt: number;
}
