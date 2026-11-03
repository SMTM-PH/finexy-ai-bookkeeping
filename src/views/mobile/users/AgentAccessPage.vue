<template>
    <f7-page>
        <f7-navbar title="AI 设置" back-link="返回" />
        <f7-block class="agent-page"><AISettingsPanel /></f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import AISettingsPanel from '@/components/AISettingsPanel.vue';
</script>

<style scoped>
.agent-page { padding: 20px; border-radius: 18px; background: #fff; color: #12141a; }
</style>
