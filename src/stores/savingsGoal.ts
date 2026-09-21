import { ref } from 'vue';
import { defineStore } from 'pinia';

import { SavingsGoal, SavingsGoalFund } from '@/models/savings_goal.ts';
import type { AccountInfoResponse } from '@/models/account.ts';
import services from '@/lib/services.ts';

/**
 * Savings goals of one ledger. The saved amount always comes from the server
 * derived value; the client never keeps a local balance. A 400/403/404
 * response means the savings goal feature is unavailable on this server and
 * is presented as an empty state.
 */
export const useSavingsGoalsStore = defineStore('savingsGoals', () => {
    const goals = ref<SavingsGoal[]>([]);
    const featureDisabled = ref(false);
    const loading = ref(false);
    const loaded = ref(false);
    // goalId -> movements of that goal, newest first (only loaded on demand).
    const fundsByGoalId = ref<Record<string, SavingsGoalFund[]>>({});
    // Accounts that may fund a goal of the currently selected ledger. Family
    // ledger accounts created by other members are included by the server.
    const fundAccounts = ref<AccountInfoResponse[]>([]);

    async function loadFundAccounts(ledgerId: string): Promise<void> {
        const response = await services.listSavingsGoalAccounts({ ledgerId: ledgerId });
        if (!response.data?.success) throw { message: '无法读取可用账户' };
        fundAccounts.value = response.data.result ?? [];
    }

    async function load(ledgerId: string): Promise<void> {
        loading.value = true;
        try {
            const response = await services.listSavingsGoals({ ledgerId: ledgerId });
            if (!response.data?.success) throw { message: '无法读取存钱计划' };
            goals.value = (response.data.result ?? []).map(info => SavingsGoal.of(info));
            featureDisabled.value = false;
            loaded.value = true;
        } catch (error) {
            const status = (error as { response?: { status?: number } })?.response?.status;
            if (status === 400 || status === 403 || status === 404) {
                featureDisabled.value = true;
                goals.value = [];
                return;
            }
            throw error;
        } finally {
            loading.value = false;
        }
    }

    async function loadFunds(goalId: string): Promise<void> {
        const response = await services.listSavingsGoalFunds({ goalId: goalId });
        if (!response.data?.success) throw { message: '无法读取资金记录' };
        fundsByGoalId.value = {
            ...fundsByGoalId.value,
            [goalId]: (response.data.result ?? []).map(info => SavingsGoalFund.of(info))
        };
    }

    async function createGoal(ledgerId: string, name: string, targetAmount: number, deadlineTime: number, comment: string): Promise<void> {
        const response = await services.createSavingsGoal({
            ledgerId: ledgerId === '0' ? undefined : ledgerId,
            name: name,
            targetAmount: targetAmount,
            deadlineTime: deadlineTime > 0 ? deadlineTime : undefined,
            comment: comment
        });
        if (!response.data?.success) throw { message: '无法创建存钱目标' };
        await load(ledgerId);
    }

    async function modifyGoal(goal: SavingsGoal, name: string, targetAmount: number, deadlineTime: number, comment: string): Promise<void> {
        const response = await services.modifySavingsGoal({
            id: goal.id,
            name: name,
            targetAmount: targetAmount,
            deadlineTime: deadlineTime > 0 ? deadlineTime : undefined,
            comment: comment
        });
        if (!response.data?.success) throw { message: '无法保存存钱目标' };
        await load(goal.ledgerId);
    }

    async function deleteGoal(goal: SavingsGoal): Promise<void> {
        const response = await services.deleteSavingsGoal({ id: goal.id });
        if (!response.data?.success) throw { message: '无法删除存钱目标' };
        await load(goal.ledgerId);
    }

    async function deposit(goal: SavingsGoal, amount: number, accountId: string, comment: string): Promise<void> {
        const response = await services.depositToSavingsGoal({ id: goal.id, amount: amount, accountId: accountId, comment: comment });
        if (!response.data?.success) throw { message: '无法存入目标' };
        await load(goal.ledgerId);
		await loadFundAccounts(goal.ledgerId);
    }

    async function withdraw(goal: SavingsGoal, amount: number, accountId: string, comment: string): Promise<void> {
        const response = await services.withdrawFromSavingsGoal({ id: goal.id, amount: amount, accountId: accountId, comment: comment });
        if (!response.data?.success) throw { message: '无法从目标取出' };
        await load(goal.ledgerId);
		await loadFundAccounts(goal.ledgerId);
    }

    function reset(): void {
        goals.value = [];
        fundsByGoalId.value = {};
        featureDisabled.value = false;
    }

    return {
        goals,
        featureDisabled,
        loading,
        loaded,
        fundsByGoalId,
        fundAccounts,
        load,
        loadFunds,
        loadFundAccounts,
        createGoal,
        modifyGoal,
        deleteGoal,
        deposit,
        withdraw,
        reset
    };
});
