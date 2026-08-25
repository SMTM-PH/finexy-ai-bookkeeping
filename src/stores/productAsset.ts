import { ref } from 'vue';
import { defineStore } from 'pinia';

import type {
    ProductAssetCreateRequest,
    ProductAssetInfoResponse,
    ProductAssetModifyRequest,
    ProductAssetSellRequest
} from '@/models/product_asset.ts';
import services from '@/lib/services.ts';
import logger from '@/lib/logger.ts';

export const useProductAssetsStore = defineStore('productAssets', () => {
    const assets = ref<ProductAssetInfoResponse[]>([]);
    const loaded = ref<boolean>(false);

    function apiResult<T>(response: { data?: { success?: boolean, result?: T } }, message: string): T {
        if (!response.data?.success || typeof response.data.result === 'undefined') {
            throw { message };
        }

        return response.data.result;
    }

    function normalizeError(error: unknown, message: string): never {
        logger.error(message, error);
        const apiError = error as {
            response?: { data?: { errorMessage?: string } };
            processed?: boolean;
        };

        if (apiError?.response?.data?.errorMessage) {
            throw { error: apiError.response.data };
        }

        if (apiError?.processed) {
            throw error;
        }

        throw { message };
    }

    async function loadAll(force = false): Promise<ProductAssetInfoResponse[]> {
        if (loaded.value && !force) {
            return assets.value;
        }

        try {
            const response = await services.getAllProductAssets();
            assets.value = apiResult(response, 'Unable to retrieve product assets');
            loaded.value = true;
            return assets.value;
        } catch (error) {
            return normalizeError(error, 'Unable to retrieve product assets');
        }
    }

    async function create(request: ProductAssetCreateRequest): Promise<ProductAssetInfoResponse> {
        try {
            const response = await services.addProductAsset(request);
            const asset = apiResult(response, 'Unable to add product asset');
            assets.value.unshift(asset);
            return asset;
        } catch (error) {
            return normalizeError(error, 'Unable to add product asset');
        }
    }

    async function modify(request: ProductAssetModifyRequest): Promise<ProductAssetInfoResponse> {
        try {
            const response = await services.modifyProductAsset(request);
            const asset = apiResult(response, 'Unable to save product asset');
            replace(asset);
            return asset;
        } catch (error) {
            return normalizeError(error, 'Unable to save product asset');
        }
    }

    async function sell(request: ProductAssetSellRequest): Promise<ProductAssetInfoResponse> {
        try {
            const response = await services.sellProductAsset(request);
            const asset = apiResult(response, 'Unable to mark product asset as sold');
            replace(asset);
            return asset;
        } catch (error) {
            return normalizeError(error, 'Unable to mark product asset as sold');
        }
    }

    async function remove(id: string): Promise<void> {
        try {
            const response = await services.deleteProductAsset({ id });
            apiResult(response, 'Unable to delete product asset');
            assets.value = assets.value.filter(asset => asset.id !== id);
        } catch (error) {
            normalizeError(error, 'Unable to delete product asset');
        }
    }

    function replace(asset: ProductAssetInfoResponse): void {
        const index = assets.value.findIndex(item => item.id === asset.id);

        if (index >= 0) {
            assets.value.splice(index, 1, asset);
        }
    }

    function reset(): void {
        assets.value = [];
        loaded.value = false;
    }

    return { assets, loaded, loadAll, create, modify, sell, remove, reset };
});
