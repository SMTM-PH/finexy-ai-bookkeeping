<template>
    <f7-page ptr @ptr:refresh="refresh" @page:afterin="activate" @page:beforeout="deactivate">
        <f7-navbar title="账本报告" :back-link="tt('Back')" />
        <ledger-context-notice />
        <f7-block class="ledger-report-mobile">
            <p v-if="refreshError" role="alert">{{ refreshError }}</p>
            <ledger-report-panel v-if="active" ref="panel" mobile />
            <p v-else role="status">正在打开当前账本报告…</p>
        </f7-block>
        <f7-block v-if="ledgers.selectedLedgerId === '0'" class="ledger-report-advanced">
            <p>原有账户资产趋势和高级分析使用账号账户范围，沿用原统计设置。</p>
            <f7-link href="/statistic/advanced">账户资产与高级分析</f7-link>
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { useI18n } from '@/locales/helpers.ts';
import { useLedgerReportStore } from '@/stores/ledgerReport.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import LedgerReportPanel from '@/components/common/LedgerReportPanel.vue';
import LedgerContextNotice from '@/components/mobile/LedgerContextNotice.vue';

const { tt } = useI18n();
const ledgers = useLedgersStore();
const reports = useLedgerReportStore();
const panel = ref<{ refresh(): Promise<unknown> } | null>(null);
const active = ref(false);
const refreshError = ref('');

function activate(): void { active.value = true; }
function deactivate(): void { active.value = false; refreshError.value = ''; reports.reset(); }
async function refresh(done: () => void): Promise<void> {
    refreshError.value = '';
    try { await panel.value?.refresh(); }
    catch { refreshError.value = '无法刷新当前账本报告，请重试'; }
    finally { done(); }
}
onBeforeUnmount(deactivate);
</script>

<style scoped>
.ledger-report-mobile { margin: 12px; padding: 0; }
.ledger-report-advanced { margin: 16px; font-size: 0.875rem; }
.ledger-report-advanced a { min-height: 48px; display: inline-flex; align-items: center; }
</style>
