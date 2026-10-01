import type { ApplicationCloudSetting } from '@/core/setting.ts';

import type { UserBasicInfo } from './user.ts';

export const TOKEN_TYPE_API: number = 8;
export const TOKEN_TYPE_MCP: number = 5;

export interface TokenGenerateAPIRequest {
	readonly ledgerId?: string;
    readonly expiresInSeconds: number;
    readonly password: string;
    readonly name?: string;
    readonly scopes?: string[];
}

export interface TokenGenerateMCPRequest {
	readonly ledgerId?: string;
    readonly expiresInSeconds: number;
    readonly password: string;
    readonly name?: string;
    readonly scopes?: string[];
}

export interface TokenRevokeRequest {
    readonly tokenId: string;
}

export interface TokenGenerateAPIResponse {
    readonly token: string;
    readonly apiBaseUrl: string;
}

export interface TokenGenerateMCPResponse {
    readonly token: string;
    readonly mcpUrl: string;
}

export interface TokenRefreshResponse {
    readonly newToken?: string;
    readonly oldTokenId?: string;
    readonly user: UserBasicInfo;
    readonly applicationCloudSettings?: ApplicationCloudSetting[];
    readonly notificationContent?: string;
}

export interface TokenInfoResponse {
	readonly ledgerId?: string;
    readonly name?: string;
    readonly scopes?: string[];
    readonly expiresAt?: number;
    readonly tokenId: string;
    readonly tokenType: number;
    readonly userAgent: string;
    readonly lastSeen: number;
    readonly isCurrent: boolean;
}

export enum SessionDeviceType {
    Api = 'api',
    MCP = 'mcp',
    Phone = 'phone',
    Tablet = 'tablet',
    TV = 'tv',
    Wearable = 'wearable',
    Default = 'default'
}

export class SessionInfo {
    public readonly tokenId: string;
    public readonly isCurrent: boolean;
    public readonly deviceType: SessionDeviceType;
    public readonly deviceInfo: string;
    public readonly deviceName: string;
    public readonly lastSeen: number;

    protected constructor(tokenId: string, isCurrent: boolean, deviceType: SessionDeviceType, deviceInfo: string, deviceName: string, lastSeen: number) {
        this.tokenId = tokenId;
        this.isCurrent = isCurrent;
        this.deviceType = deviceType;
        this.deviceInfo = deviceInfo;
        this.deviceName = deviceName;
        this.lastSeen = lastSeen;
    }

    public static of(tokenId: string, isCurrent: boolean, deviceType: SessionDeviceType, deviceInfo: string, deviceName: string, lastSeen: number): SessionInfo {
        return new SessionInfo(tokenId, isCurrent, deviceType, deviceInfo, deviceName, lastSeen);
    }
}
