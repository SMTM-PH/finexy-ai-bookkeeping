<template>
    <f7-page>
        <f7-navbar title="月度预算" :back-link="tt('Back')" />
        <ledger-context-notice />
        <f7-block class="monthly-budget-mobile">
            <monthly-budget-panel :ledger-id="ledgers.selectedLedgerId" :ledger-name="ledgers.selectedLedger.name" />
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import { useI18n } from '@/locales/helpers.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import MonthlyBudgetPanel from '@/components/MonthlyBudgetPanel.vue';
import LedgerContextNotice from '@/components/mobile/LedgerContextNotice.vue';

const { tt } = useI18n();
const ledgers = useLedgersStore();
</script>

<style scoped>
.monthly-budget-mobile { margin: 16px; padding: 0; }
</style>
