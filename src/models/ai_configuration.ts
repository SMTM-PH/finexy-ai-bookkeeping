export interface AIConfigurationResponse {
    readonly enabled: boolean;
    readonly imageEnabled: boolean;
    readonly provider: string;
    readonly baseUrl: string;
    readonly modelId: string;
    readonly enableThinking: string;
    readonly requestTimeout: number;
    readonly apiKeyConfigured: boolean;
    readonly editable: boolean;
}

export interface AIConfigurationUpdateRequest {
    readonly enabled: boolean;
    readonly imageEnabled: boolean;
    readonly baseUrl: string;
    readonly apiKey: string;
    readonly modelId: string;
    readonly enableThinking: string;
    readonly requestTimeout: number;
}
