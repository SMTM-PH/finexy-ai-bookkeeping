export const ProductAssetStatus = {
    Active: 1,
    Sold: 2,
    Disposed: 3
} as const;

export const ProductAssetCategory = {
    Other: 1,
    Phone: 2,
    Computer: 3,
    Tablet: 4,
    Camera: 5,
    GameConsole: 6,
    Appliance: 7
} as const;

export interface ProductAssetValuation {
    readonly heldDays: number;
    readonly dailyDepreciation: number;
    readonly accumulatedDepreciation: number;
    readonly bookValue: number;
    readonly marketValue?: number;
    readonly averageDailyCost: number;
}

export interface ProductAssetInfoResponse {
    readonly id: string;
    readonly sourceTransactionId?: string;
    readonly saleTransactionId?: string;
    readonly category: number;
    readonly status: number;
    readonly name: string;
    readonly brand: string;
    readonly model: string;
    readonly purchaseAmount: number;
    readonly purchaseTime: number;
    readonly utcOffset: number;
    readonly usefulLifeDays: number;
    readonly residualAmount: number;
    readonly manualMarketValue?: number;
    readonly manualMarketValueTime?: number;
    readonly soldAmount?: number;
    readonly soldTime?: number;
    readonly comment: string;
    readonly valuation: ProductAssetValuation;
}

export interface ProductAssetCreateRequest {
    readonly sourceTransactionId: string;
    readonly category: number;
    readonly name: string;
    readonly brand: string;
    readonly model: string;
    readonly purchaseAmount: number;
    readonly purchaseTime: number;
    readonly utcOffset: number;
    readonly usefulLifeDays?: number;
    readonly residualAmount?: number;
    readonly manualMarketValue?: number;
    readonly comment: string;
}

export interface ProductAssetModifyRequest extends Omit<ProductAssetCreateRequest, 'usefulLifeDays' | 'residualAmount'> {
    readonly id: string;
    readonly usefulLifeDays: number;
    readonly residualAmount: number;
    readonly clearManualMarketValue: boolean;
}

export interface ProductAssetSellRequest {
    readonly id: string;
    readonly saleTransactionId: string;
    readonly soldAmount: number;
    readonly soldTime: number;
}

export interface ProductAssetDeleteRequest {
    readonly id: string;
}
