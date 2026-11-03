import { ref } from 'vue';
import { defineStore } from 'pinia';

import type { MonthlyBudgetInfoResponse } from '@/models/monthly_budget.ts';
import { ALL_CURRENCIES } from '@/consts/currency.ts';
import { getCurrentUserInfo, getTransactionStateIdentity } from '@/lib/userstate.ts';
import services from '@/lib/services.ts';
import logger from '@/lib/logger.ts';

const maximumAmount = 9999999999999;
const stringId = /^(0|[1-9]\d*)$/;

function validMonth(value: number): boolean {
    return Number.isInteger(value) && value >= 200001 && value <= 999912 && value % 100 >= 1 && value % 100 <= 12;
}

function validAmount(value: unknown): value is number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= maximumAmount;
}

function validateBudget(value: unknown, yearMonth: number, ledgerId: string): MonthlyBudgetInfoResponse {
    const budget = value as MonthlyBudgetInfoResponse | null;
    if (!budget || typeof budget !== 'object' || typeof budget.id !== 'string' || !/^[1-9]\d*$/.test(budget.id)
        || budget.ledgerId !== ledgerId || budget.yearMonth !== yearMonth || !validAmount(budget.amount)
        || !Number.isSafeInteger(budget.revision) || budget.revision < 1
        || typeof budget.currency !== 'string' || !ALL_CURRENCIES[budget.currency]
        || !budget.categoryAmounts || typeof budget.categoryAmounts !== 'object' || Array.isArray(budget.categoryAmounts)
        || Object.entries(budget.categoryAmounts).some(([id, amount]) => !/^[1-9]\d*$/.test(id) || !validAmount(amount))) {
        throw new Error('预算响应不完整或账本归属不匹配，请重试');
    }
    return { ...budget, categoryAmounts: { ...budget.categoryAmounts } };
}

export const useMonthlyBudgetStore = defineStore('monthlyBudget', () => {
    const budgets = ref<Record<string, MonthlyBudgetInfoResponse | null>>({});
    const versions = new Map<string, number>();
    let generation = 0;

    function currentContext(yearMonth: number, ledgerId: string) {
        const identity = getTransactionStateIdentity();
        const username = getCurrentUserInfo()?.username;
        if (!identity || !username || typeof ledgerId !== 'string' || !stringId.test(ledgerId) || !validMonth(yearMonth)) {
            throw new Error('无法确认当前账号、账本或预算月份');
        }
        return { identity, username, key: JSON.stringify([identity, ledgerId, yearMonth]), generation };
    }

    function budgetFor(yearMonth: number, ledgerId = '0'): MonthlyBudgetInfoResponse | null | undefined {
        const identity = getTransactionStateIdentity();
        return identity ? budgets.value[JSON.stringify([identity, ledgerId, yearMonth])] : undefined;
    }

    function begin(context: ReturnType<typeof currentContext>): number {
        const version = (versions.get(context.key) ?? 0) + 1;
        versions.set(context.key, version);
        return version;
    }

    function assertCurrent(context: ReturnType<typeof currentContext>, version: number): void {
        if (context.generation !== generation || context.identity !== getTransactionStateIdentity()
            || context.username !== getCurrentUserInfo()?.username || versions.get(context.key) !== version) {
            throw new Error('账号或预算已更新，请重新读取当前账本');
        }
    }

    function normalizeError(error: unknown, message: string): never {
        // Axios errors can carry the full request and credentials.
        logger.warn(message);
        const apiError = error as { response?: { status?: number; data?: { errorMessage?: string } }, processed?: boolean };

        if (apiError.response?.data?.errorMessage) throw { error: apiError.response.data, status: apiError.response.status };
        if (error instanceof Error || apiError.processed) throw error;
        throw { message };
    }

    async function load(yearMonth: number, force = false, ledgerId = '0'): Promise<MonthlyBudgetInfoResponse | null> {
        const context = currentContext(yearMonth, ledgerId);
        if (!force && Object.prototype.hasOwnProperty.call(budgets.value, context.key)) return budgets.value[context.key] ?? null;
        const version = begin(context);

        try {
            const response = await services.getMonthlyBudget({ yearMonth, ledgerId, expectedUsername: context.username });
            assertCurrent(context, version);
            if (!response.data?.success || !Object.prototype.hasOwnProperty.call(response.data, 'result')) throw new Error('预算读取失败，请重试');
            const budget = response.data.result === null ? null : validateBudget(response.data.result, yearMonth, ledgerId);
            budgets.value[context.key] = budget;
            return budget;
        } catch (error) {
            return normalizeError(error, '预算读取失败，请重试');
        }
    }

    async function save(yearMonth: number, amount: number, categoryAmounts: Record<string, number> = {}, ledgerId = '0', currency?: string, expectedRevision?: number): Promise<MonthlyBudgetInfoResponse> {
        const context = currentContext(yearMonth, ledgerId);
        const revision = expectedRevision ?? budgetFor(yearMonth, ledgerId)?.revision ?? 0;
        if (!validAmount(amount) || (currency !== undefined && !ALL_CURRENCIES[currency])
            || !Number.isSafeInteger(revision) || revision < 0
            || Object.entries(categoryAmounts).some(([id, value]) => !/^[1-9]\d*$/.test(id) || !validAmount(value))) {
            throw new Error('预算金额、币种或分类额度无效');
        }
        const version = begin(context);
        try {
            const response = await services.setMonthlyBudget({ ledgerId, yearMonth, amount, currency, expectedRevision: revision, categoryAmounts: { ...categoryAmounts } }, context.username);
            assertCurrent(context, version);
            if (!response.data?.success) throw new Error('预算保存失败，请重试');
            const budget = validateBudget(response.data.result, yearMonth, ledgerId);
            budgets.value[context.key] = budget;
            return budget;
        } catch (error) {
            return normalizeError(error, '预算保存失败，请重试');
        }
    }

    async function remove(yearMonth: number, ledgerId = '0', expectedRevision?: number): Promise<void> {
        const context = currentContext(yearMonth, ledgerId);
        const revision = expectedRevision ?? budgetFor(yearMonth, ledgerId)?.revision ?? 0;
        if (!Number.isSafeInteger(revision) || revision < 0) throw new Error('预算基线版本无效，请重新读取');
        const version = begin(context);
        try {
            const response = await services.deleteMonthlyBudget({ ledgerId, yearMonth, expectedRevision: revision }, context.username);
            assertCurrent(context, version);
            if (!response.data?.success || response.data.result !== true) throw new Error('预算清除失败，请重试');
            budgets.value[context.key] = null;
        } catch (error) {
            normalizeError(error, '预算清除失败，请重试');
        }
    }

    function reset(): void {
        generation++;
        versions.clear();
        budgets.value = {};
    }

    return { budgets, budgetFor, load, save, remove, reset };
});
