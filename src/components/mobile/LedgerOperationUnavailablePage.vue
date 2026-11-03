<template>
    <f7-page>
        <f7-navbar title="此账本的操作范围" back-link="返回" />
        <LedgerContextNotice />
        <f7-block strong inset role="status">
            <p>{{ mobileLedgerUnsupportedMessage }}</p>
            <p>这是当前移动端的功能限制。账本成员权限仍由服务器核验。</p>
            <f7-button outline href="/transaction/list">查看此账本流水</f7-button>
            <f7-button class="margin-top" outline href="/ledger/manage">管理与切换账本</f7-button>
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import LedgerContextNotice from './LedgerContextNotice.vue';
import { mobileLedgerUnsupportedMessage } from '@/lib/mobile_ledger.ts';
</script>
