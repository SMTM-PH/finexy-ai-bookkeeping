<template>
    <f7-block class="ledger-context-notice">
        <f7-link href="/ledger/manage" :aria-label="`当前账本：${ledgers.selectedLedger.name}，管理与切换账本`">
            <f7-icon f7="book" aria-hidden="true" />
            <span>当前账本：{{ ledgers.selectedLedger.name }}</span>
            <f7-icon f7="chevron_down" aria-hidden="true" />
        </f7-link>
        <p v-if="limited && ledgers.selectedLedgerId !== '0'">{{ mobileLedgerUnsupportedMessage }}</p>
    </f7-block>
</template>

<script setup lang="ts">
import { useLedgersStore } from '@/stores/ledger.ts';
import { mobileLedgerUnsupportedMessage } from '@/lib/mobile_ledger.ts';
defineProps<{ limited?: boolean }>();
const ledgers = useLedgersStore();
</script>

<style scoped>
.ledger-context-notice { margin: 12px 16px; }
.ledger-context-notice a { min-height: 44px; gap: 8px; max-width: 100%; color: var(--f7-text-color); }
.ledger-context-notice span { overflow-wrap: anywhere; }
.ledger-context-notice p { margin: 8px 0; line-height: 1.5; }
</style>
