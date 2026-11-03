<template>
    <dialog ref="dialog" class="ledger-pending-dialog" aria-label="当前账本通知与待处理" @cancel.prevent="emit('close')">
        <div class="pending-dialog-close"><button type="button" autofocus @click="emit('close')">关闭待处理</button></div>
        <LedgerPendingPanel @open="emit('open', $event)" />
    </dialog>
</template>
<script setup lang="ts">
import { onMounted, onUnmounted, useTemplateRef } from 'vue';
import LedgerPendingPanel from '@/components/common/LedgerPendingPanel.vue';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
const emit = defineEmits<{ close: []; open: [target: LedgerPendingTarget] }>();
const dialog = useTemplateRef<HTMLDialogElement>('dialog');
const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
onMounted(() => dialog.value?.showModal());
onUnmounted(() => { dialog.value?.close(); if (trigger?.isConnected) trigger.focus(); });
</script>
<style scoped>
.ledger-pending-dialog { box-sizing: border-box; width: min(680px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); margin: auto; padding: 16px; border: 1px solid var(--line, GrayText); border-radius: 20px; background: var(--surface, var(--card, Canvas)); color: var(--ink, CanvasText); overflow-y: auto; overscroll-behavior: contain; }
.ledger-pending-dialog::backdrop { background: color-mix(in srgb, CanvasText 50%, transparent); }
.pending-dialog-close { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.pending-dialog-close button { min-height: 48px; padding: 10px 14px; border: 1px solid var(--line, GrayText); border-radius: 11px; font: inherit; color: inherit; background: transparent; cursor: pointer; }
.pending-dialog-close button:focus-visible { outline: 2px solid var(--orange, Highlight); outline-offset: 3px; }
</style>
