import { ref } from 'vue';
import { defineStore } from 'pinia';

import type { AIReviewItemCreateRequest, AIReviewItemInfoResponse } from '@/models/ai_review_item.ts';
import services from '@/lib/services.ts';

export const useAIReviewItemsStore = defineStore('aiReviewItems', () => {
    const items = ref<AIReviewItemInfoResponse[]>([]);

    async function load(): Promise<void> {
        const response = await services.listAIReviewItems();
        if (!response.data?.success) throw { message: '无法读取待处理队列' };
        items.value = response.data.result ?? [];
    }

    async function create(request: AIReviewItemCreateRequest): Promise<AIReviewItemInfoResponse> {
        const response = await services.createAIReviewItem(request);
        if (!response.data?.success || !response.data.result) throw { message: '无法保存待处理记录' };
        items.value.unshift(response.data.result);
        return response.data.result;
    }

    async function resolve(id: string): Promise<void> {
        const response = await services.resolveAIReviewItem({ id });
        if (!response.data?.success) throw { message: '无法完成待处理记录' };
        items.value = items.value.filter(item => item.id !== id);
    }

    async function dismiss(id: string): Promise<void> {
        const response = await services.dismissAIReviewItem({ id });
        if (!response.data?.success) throw { message: '无法忽略待处理记录' };
        items.value = items.value.filter(item => item.id !== id);
    }

    async function remove(id: string): Promise<void> {
        const response = await services.deleteAIReviewItem({ id });
        if (!response.data?.success) throw { message: '无法删除待处理记录' };
        items.value = items.value.filter(item => item.id !== id);
    }

    function reset(): void { items.value = []; }

    return { items, load, create, resolve, dismiss, remove, reset };
});
