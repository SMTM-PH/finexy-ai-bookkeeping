import { ref } from 'vue';
import { defineStore } from 'pinia';

import type { AIReportInfoResponse } from '@/models/ai_report.ts';
import services from '@/lib/services.ts';

export const useAIReportsStore = defineStore('aiReports', () => {
    const reports = ref<AIReportInfoResponse[]>([]);

    async function load(): Promise<void> {
        const response = await services.listAIReports();
        if (!response.data?.success) throw { message: '无法读取 AI 分析报告' };
        reports.value = response.data.result ?? [];
    }

    async function generate(yearMonth: number): Promise<AIReportInfoResponse> {
        const response = await services.generateAIReport({ yearMonth });
        if (!response.data?.success || !response.data.result) throw { message: '无法生成 AI 分析报告' };
        reports.value.unshift(response.data.result);
        return response.data.result;
    }

    function reset(): void { reports.value = []; }

    return { reports, load, generate, reset };
});
