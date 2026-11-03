<template>
    <f7-page>
        <f7-navbar title="读取账本失败" />
        <f7-block strong inset role="alert">
            <p>{{ ledgersStore.loadError || '无法核验当前账本，请重试。' }}</p>
            <p>恢复账本前暂停记账，原草稿与待确认记录仍保留在本机。</p>
            <f7-button fill :disabled="retrying" @click="retry">重新读取账本</f7-button>
            <p v-if="error">{{ error }}</p>
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { f7 } from 'framework7-vue';
import { useLedgersStore } from '@/stores/ledger.ts';

const ledgersStore = useLedgersStore();
const retrying = ref(false);
const error = ref('');
async function retry(): Promise<void> {
    retrying.value = true;
    error.value = '';
    try {
        await ledgersStore.ensureSelection();
        const router = f7.views.main.router;
        router.navigate(router.currentRoute.url, { reloadCurrent: true, animate: false });
    } catch {
        error.value = '账本仍无法读取，请检查网络后重试。';
    } finally {
        retrying.value = false;
    }
}
</script>

<style scoped>
.button { min-height: 44px; }
</style>
