<template>
    <f7-page>
        <f7-navbar title="待处理入口不可用" />
        <f7-block strong inset role="alert">
            <p>{{ message || '无法核验原待处理条目，请回总览重新核对。' }}</p>
            <p>原记录仍保留。此入口不会切换账本、重建表单或重新提交。</p>
            <f7-button fill @click="retry">重试原入口</f7-button>
            <f7-button outline href="/">返回总览</f7-button>
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import type { Router } from 'framework7/types';

const props = defineProps<{ f7router: Router.Router; retryUrl: string; message?: string }>();
function retry(): void {
    props.f7router.navigate(props.retryUrl, { reloadCurrent: true, animate: false });
}
</script>

<style scoped>
.button { min-height: 44px; height: auto; margin-top: 12px; white-space: normal; }
</style>
