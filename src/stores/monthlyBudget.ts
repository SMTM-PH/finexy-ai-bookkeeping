import { ref } from 'vue';
import { defineStore } from 'pinia';

import type { MonthlyBudgetInfoResponse } from '@/models/monthly_budget.ts';
import services from '@/lib/services.ts';
import logger from '@/lib/logger.ts';

export const useMonthlyBudgetStore = defineStore('monthlyBudget', () => {
    const budgets = ref<Record<number, MonthlyBudgetInfoResponse | null>>({});

    function normalizeError(error: unknown, message: string): never {
        logger.error(message, error);
        const apiError = error as { response?: { data?: { errorMessage?: string } }, processed?: boolean };

        if (apiError.response?.data?.errorMessage) throw { error: apiError.response.data };
        if (apiError.processed) throw error;
        throw { message };
    }

    async function load(yearMonth: number, force = false): Promise<MonthlyBudgetInfoResponse | null> {
        if (!force && Object.prototype.hasOwnProperty.call(budgets.value, yearMonth)) return budgets.value[yearMonth] ?? null;

        try {
            const response = await services.getMonthlyBudget({ yearMonth });
            if (!response.data?.success) throw { message: 'Unable to retrieve monthly budget' };
            budgets.value[yearMonth] = response.data.result ?? null;
            return budgets.value[yearMonth];
        } catch (error) {
            return normalizeError(error, 'Unable to retrieve monthly budget');
        }
    }

    async function save(yearMonth: number, amount: number): Promise<MonthlyBudgetInfoResponse> {
        try {
            const response = await services.setMonthlyBudget({ yearMonth, amount });
            if (!response.data?.success || !response.data.result) throw { message: 'Unable to save monthly budget' };
            budgets.value[yearMonth] = response.data.result;
            return response.data.result;
        } catch (error) {
            return normalizeError(error, 'Unable to save monthly budget');
        }
    }

    async function remove(yearMonth: number): Promise<void> {
        try {
            const response = await services.deleteMonthlyBudget({ yearMonth });
            if (!response.data?.success) throw { message: 'Unable to delete monthly budget' };
            budgets.value[yearMonth] = null;
        } catch (error) {
            normalizeError(error, 'Unable to delete monthly budget');
        }
    }

    function reset(): void {
        budgets.value = {};
    }

    return { budgets, load, save, remove, reset };
});
