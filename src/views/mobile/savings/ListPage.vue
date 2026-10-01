<template>
    <f7-page ptr @ptr:refresh="refresh">
        <f7-navbar title="存钱计划" back-link="返回"></f7-navbar>
        <f7-block v-if="error" class="text-color-red" role="alert">{{ error }}</f7-block>
        <f7-list strong inset>
            <f7-list-item title="账本" smart-select :smart-select-params="{ openIn: 'sheet' }">
                <select v-model="ledgerId" @change="load"><option v-for="ledger in ledgers.allLedgers" :key="ledger.id" :value="ledger.id">{{ ledger.name }}</option></select>
            </f7-list-item>
        </f7-list>
        <f7-card v-for="goal in goals.goals" :key="goal.id" class="goal-card">
            <f7-card-header><span>{{ goal.name }}</span><small>{{ Math.min(100, goal.progressPercent).toFixed(0) }}%</small></f7-card-header>
            <f7-card-content>
                <div class="progress"><i :style="{ width: `${Math.min(100, goal.progressPercent)}%` }"></i></div>
                <p>已存 {{ money(goal.savedAmount) }} / 目标 {{ money(goal.targetAmount) }}</p>
                <p v-if="goal.comment" class="text-color-gray">{{ goal.comment }}</p>
            </f7-card-content>
            <f7-card-footer><f7-button @click="openMove(goal, 'deposit')">存入</f7-button><f7-button @click="openMove(goal, 'withdraw')">取出</f7-button></f7-card-footer>
        </f7-card>
        <f7-block v-if="!goals.loading && !goals.goals.length" class="text-align-center text-color-gray">此账本还没有存钱目标。</f7-block>

        <f7-block-title>新建目标</f7-block-title>
        <f7-list strong inset>
            <f7-list-input label="目标名称" type="text" maxlength="64" v-model:value="name"></f7-list-input>
            <f7-list-input label="目标金额" type="number" min="0.01" step="0.01" v-model:value="target"></f7-list-input>
            <f7-list-input label="备注" type="text" maxlength="255" v-model:value="comment"></f7-list-input>
            <f7-list-button :class="{ disabled: busy || !name.trim() || !(Number(target) > 0) }" @click="createGoal">创建目标</f7-list-button>
        </f7-list>

        <f7-sheet v-model:opened="moveOpen" swipe-to-close backdrop>
            <f7-page-content>
                <f7-block-title>{{ moveMode === 'deposit' ? '存入目标' : '从目标取出' }}</f7-block-title>
                <f7-list strong inset>
                    <f7-list-input label="金额" type="number" min="0.01" step="0.01" v-model:value="moveAmount"></f7-list-input>
                    <f7-list-item title="资金账户" smart-select :smart-select-params="{ openIn: 'sheet' }">
                        <select v-model="accountId"><option v-for="account in goals.fundAccounts" :key="account.id" :value="account.id">{{ account.name }}</option></select>
                    </f7-list-item>
                    <f7-list-input label="备注" type="text" maxlength="255" v-model:value="moveComment"></f7-list-input>
                    <f7-list-button :class="{ disabled: busy || !(Number(moveAmount) > 0) || !accountId }" @click="submitMove">确认{{ moveMode === 'deposit' ? '存入' : '取出' }}</f7-list-button>
                </f7-list>
            </f7-page-content>
        </f7-sheet>
    </f7-page>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { SavingsGoal } from '@/models/savings_goal.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useSavingsGoalsStore } from '@/stores/savingsGoal.ts';
import { useI18nUIComponents } from '@/lib/ui/mobile.ts';
const ledgers = useLedgersStore(); const goals = useSavingsGoalsStore(); const { showToast } = useI18nUIComponents();
const ledgerId = ref('0'); const busy = ref(false); const error = ref('');
const name = ref(''); const target = ref(''); const comment = ref('');
const moveOpen = ref(false); const moveMode = ref<'deposit'|'withdraw'>('deposit'); const moveGoal = ref<SavingsGoal>();
const moveAmount = ref(''); const moveComment = ref(''); const accountId = ref('');
function money(value: number): string { return (value / 100).toFixed(2); }
function messageOf(reason: unknown): string { return reason instanceof Error ? reason.message : String((reason as { message?: unknown })?.message || reason); }
async function run(action: () => Promise<void>): Promise<void> { if (busy.value) return; busy.value = true; error.value = ''; try { await action(); } catch (reason) { error.value = messageOf(reason); showToast(error.value); } finally { busy.value = false; } }
async function load(): Promise<void> { await run(async () => { await goals.load(ledgerId.value); }); }
async function refresh(done?: () => void): Promise<void> { await ledgers.load(); await load(); done?.(); }
async function createGoal(): Promise<void> { await run(async () => { await goals.createGoal(ledgerId.value, name.value.trim(), Math.round(Number(target.value) * 100), 0, comment.value.trim()); name.value = ''; target.value = ''; comment.value = ''; }); }
async function openMove(goal: SavingsGoal, mode: 'deposit'|'withdraw'): Promise<void> { moveGoal.value = goal; moveMode.value = mode; moveAmount.value = ''; moveComment.value = ''; await run(async () => { await goals.loadFundAccounts(ledgerId.value); accountId.value = goals.fundAccounts[0]?.id || ''; moveOpen.value = true; }); }
async function submitMove(): Promise<void> { const goal = moveGoal.value; if (!goal) return; await run(async () => { const amount = Math.round(Number(moveAmount.value) * 100); if (moveMode.value === 'deposit') await goals.deposit(goal, amount, accountId.value, moveComment.value.trim()); else await goals.withdraw(goal, amount, accountId.value, moveComment.value.trim()); moveOpen.value = false; }); }
onMounted(async () => { await ledgers.load(); ledgerId.value = ledgers.selectedLedgerId; await load(); });
</script>

<style scoped>
.goal-card p { margin: 8px 0 0; }.progress { height: 8px; overflow: hidden; border-radius: 8px; background: var(--f7-list-border-color); }.progress i { display: block; height: 100%; border-radius: inherit; background: var(--f7-theme-color); }
</style>
