import { ref } from 'vue';
import { defineStore } from 'pinia';

import { SavingsGoal, SavingsGoalFund } from '@/models/savings_goal.ts';
import type { AccountInfoResponse } from '@/models/account.ts';
import services from '@/lib/services.ts';

/**
 * Savings goals of one ledger. The saved amount always comes from the server
 * derived value; the client never keeps a local balance. A 400/403/404
 * response stays a visible error and preserves valid data of the same ledger.
 */
export const useSavingsGoalsStore = defineStore('savingsGoals', () => {
    const goals = ref<SavingsGoal[]>([]);
    const featureDisabled = ref(false);
    const loading = ref(false);
    const loaded = ref(false);
    const loadedLedgerId = ref('0');
    let loadGeneration = 0;
    let accountsGeneration = 0;
    // goalId -> movements of that goal, newest first (only loaded on demand).
    const fundsByGoalId = ref<Record<string, SavingsGoalFund[]>>({});
    // Accounts that may fund a goal of the currently selected ledger. Family
    // ledger accounts created by other members are included by the server.
    const fundAccounts = ref<AccountInfoResponse[]>([]);

    async function loadFundAccounts(ledgerId: string): Promise<void> {
        if (loadedLedgerId.value !== ledgerId) return;
        const generation = ++accountsGeneration;
        fundAccounts.value = [];
        const response = await services.listSavingsGoalAccounts({ ledgerId });
        if (generation !== accountsGeneration) return;
        if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取有效资金账户，请重试');
        fundAccounts.value = response.data.result;
    }

    async function load(ledgerId: string): Promise<void> {
        if (loadedLedgerId.value !== ledgerId) {
            reset();
            loadedLedgerId.value = ledgerId;
        }
        const generation = ++loadGeneration;
        loading.value = true;
        try {
            const response = await services.listSavingsGoals({ ledgerId });
            if (generation !== loadGeneration) return;
            if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取有效存钱计划，请重试');
            const next = response.data.result.map(info => SavingsGoal.of(info));
            if (next.some(goal => goal.ledgerId !== ledgerId)) throw new Error('存钱计划账本归属不匹配，请重试');
            goals.value = next;
            featureDisabled.value = false;
            loaded.value = true;
        } catch (error) {
            if (generation !== loadGeneration) return;
            featureDisabled.value = (error as { response?: { status?: number } })?.response?.status === 404;
            throw error;
        } finally {
            if (generation === loadGeneration) loading.value = false;
        }
    }

    async function loadFunds(goalId: string): Promise<void> {
        const generation = loadGeneration;
        const response = await services.listSavingsGoalFunds({ goalId });
        if (generation !== loadGeneration) return;
        if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取有效资金记录，请重试');
        const funds = response.data.result.map(info => SavingsGoalFund.of(info));
        if (funds.some(fund => fund.goalId !== goalId)) throw new Error('资金记录目标归属不匹配，请重试');
        fundsByGoalId.value = { ...fundsByGoalId.value, [goalId]: funds };
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
        if (loadedLedgerId.value !== ledgerId) return;
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
        if (loadedLedgerId.value !== goal.ledgerId) return;
        await load(goal.ledgerId);
    }

    async function deleteGoal(goal: SavingsGoal): Promise<void> {
        const response = await services.deleteSavingsGoal({ id: goal.id });
        if (!response.data?.success) throw { message: '无法删除存钱目标' };
        if (loadedLedgerId.value !== goal.ledgerId) return;
        await load(goal.ledgerId);
    }

    async function deposit(goal: SavingsGoal, amount: number, accountId: string, comment: string): Promise<void> {
        const response = await services.depositToSavingsGoal({ id: goal.id, amount: amount, accountId: accountId, comment: comment });
        if (!response.data?.success) throw { message: '无法存入目标' };
        if (loadedLedgerId.value !== goal.ledgerId) return;
        await load(goal.ledgerId);
		await loadFundAccounts(goal.ledgerId);
    }

    async function withdraw(goal: SavingsGoal, amount: number, accountId: string, comment: string): Promise<void> {
        const response = await services.withdrawFromSavingsGoal({ id: goal.id, amount: amount, accountId: accountId, comment: comment });
        if (!response.data?.success) throw { message: '无法从目标取出' };
        if (loadedLedgerId.value !== goal.ledgerId) return;
        await load(goal.ledgerId);
		await loadFundAccounts(goal.ledgerId);
    }

    function reset(): void {
        ++loadGeneration;
        ++accountsGeneration;
        loading.value = false;
        loaded.value = false;
        loadedLedgerId.value = '0';
        fundAccounts.value = [];
        goals.value = [];
        fundsByGoalId.value = {};
        featureDisabled.value = false;
    }

    return {
        goals,
        featureDisabled,
        loading,
        loaded,
        loadedLedgerId,
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
