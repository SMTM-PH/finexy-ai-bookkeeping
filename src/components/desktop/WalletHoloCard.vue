<template>
    <div class="wallet-holo-card" :aria-busy="loading">
        <AccountHoloCard :account="account" :category-name="categoryName" balance-label="账户余额" :display-amount="displayAmount" :hidden="account.hidden" parent-name="" :appearance="appearance" compact @navigate="direction => emit('navigate', direction)" @details="emit('details')" />
        <p v-if="loading" class="wallet-artwork-status" role="status">正在读取卡面…</p>
        <p v-if="error" class="wallet-artwork-error" role="alert">{{ error }} <button type="button" @click="load">重试读取</button></p>
    </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { Account } from '@/models/account.ts';
import { defaultAccountCardStyle, parseAccountCardStyle } from '@/models/account_card.ts';
import { useUserStore } from '@/stores/user.ts';
import services from '@/lib/services.ts';
import AccountHoloCard from './AccountHoloCard.vue';

const props = defineProps<{ account: Account; ledgerId: string; categoryName: string; displayAmount: string; refreshKey: number }>();
const emit = defineEmits<{ navigate: [direction: number]; details: [] }>();
const userStore = useUserStore();
const scope = computed(() => `${userStore.currentUserBasicInfo?.username || ''}:${props.ledgerId}:${props.account.id}:${props.refreshKey}`);
const appearance = ref(defaultAccountCardStyle());
const loading = ref(false);
const error = ref('');
let generation = 0;
async function load(): Promise<void> {
    const own = ++generation;
    loading.value = true; error.value = ''; appearance.value = defaultAccountCardStyle();
    try {
        const response = await services.getAccountCard(props.ledgerId, props.account.id);
        if (own !== generation) return;
        if (!response.data.success) throw new Error();
        appearance.value = parseAccountCardStyle(response.data.result);
    } catch {
        if (own === generation) error.value = '卡面读取失败，暂显示默认样式。';
    } finally { if (own === generation) loading.value = false; }
}
watch(scope, () => void load(), { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { generation++; });
</script>

<style scoped>
.wallet-artwork-status, .wallet-artwork-error { margin: 8px 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.wallet-artwork-error { color: var(--error, #b42318); }
.wallet-artwork-error button { min-height: 44px; padding: 0 10px; border: 1px solid var(--line); border-radius: 10px; color: var(--ink); background: var(--panel); }
.wallet-artwork-error button:focus-visible { outline: 2px solid var(--orange); outline-offset: 2px; }
</style>
