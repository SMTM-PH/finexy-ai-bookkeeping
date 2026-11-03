<template>
    <nav class="account-wallet-stack" aria-label="账户钱包">
        <p class="stack-heading">账户钱包</p>
        <div ref="list" class="stack-list" role="listbox" aria-label="钱包中的账户">
            <button v-for="(account, index) in accounts" :key="account.id" type="button" class="stack-card"
                    role="option" :aria-selected="account.id === selectedId" :tabindex="account.id === selectedId ? 0 : -1"
                    :aria-label="`${account.name} · ${account.currency}${account.hidden ? ' · 已隐藏' : ''}`" :title="account.name"
                    :data-account-id="account.id" :data-brand="accountArt(account.name, account.category).brand"
                    :style="{ zIndex: index + 1 }" @click="emit('select', account.id)" @keydown="event => onKeydown(event, index)">
                <img class="stack-landscape" :src="accountArt(account.name, account.category).background" alt="" aria-hidden="true" draggable="false" />
                <img class="stack-illustration" :src="accountArt(account.name, account.category).subject" alt="" aria-hidden="true" draggable="false" />
                <span class="stack-mark" aria-hidden="true"><AccountBrandMark :brand="accountArt(account.name, account.category).brand" :icon="account.icon" /></span>
                <span v-if="account.id === selectedId" class="stack-selection" aria-hidden="true"><v-icon :icon="mdiCheck" size="14" /></span>
            </button>
        </div>
        <p class="stack-help">选择卡片查看<br />↑ ↓ 切换账户</p>
    </nav>
</template>
<script setup lang="ts">
import { nextTick, useTemplateRef, watch } from 'vue';
import { mdiCheck } from '@mdi/js';
import type { Account } from '@/models/account.ts';
import { accountArt } from '@/lib/ui/account_art.ts';
import AccountBrandMark from './AccountBrandMark.vue';
const props = defineProps<{ accounts: Account[]; selectedId: string }>();
const emit = defineEmits<{ select: [id: string] }>();
const list = useTemplateRef<HTMLElement>('list');
function onKeydown(event: KeyboardEvent, index: number) {
    const target = event.key === 'ArrowDown' ? index + 1 : event.key === 'ArrowUp' ? index - 1
        : event.key === 'Home' ? 0 : event.key === 'End' ? props.accounts.length - 1 : -1;
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const account = props.accounts[target];
    if (!account) return;
    emit('select', account.id);
    list.value?.querySelectorAll<HTMLButtonElement>('.stack-card')[target]?.focus({ preventScroll: true });
}
watch(() => `${props.selectedId}:${props.accounts.map(account => account.id).join(',')}`, async () => {
    await nextTick();
    const selected = list.value?.querySelector<HTMLElement>('[aria-selected="true"]');
    if (!selected || !list.value) return;
    // Scroll only this wallet, keeping the page and current editing position stable.
    const top = selected.offsetTop, bottom = top + selected.offsetHeight;
    if (top < list.value.scrollTop) list.value.scrollTop = top;
    else if (bottom > list.value.scrollTop + list.value.clientHeight) list.value.scrollTop = bottom - list.value.clientHeight;
}, { immediate: true });
</script>
<style scoped>
.account-wallet-stack { min-width: 0; margin-top: 24px; }
.stack-heading { margin: 0 0 12px; color: var(--muted, #475467); font-size: 12px; }
.stack-list { position: relative; max-height: 424px; overflow-y: auto; padding: 3px 5px 15px; scrollbar-width: thin; overscroll-behavior: contain; }
.stack-card { display: block; position: relative; width: 100%; height: 88px; margin-bottom: -32px; padding: 0; border: 1px solid rgb(228 196 138 / 65%); border-radius: 12px; overflow: hidden; background: #12141a; cursor: pointer; box-shadow: 0 -3px 8px rgb(18 20 26 / 12%); }
.stack-card:last-child { margin-bottom: 0; }
.stack-landscape, .stack-illustration { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; pointer-events: none; }
.stack-landscape { opacity: .34; }
.stack-illustration { left: 42%; width: 65%; object-fit: contain; opacity: .75; }
.stack-mark { position: absolute; top: 8px; left: 8px; width: 32px; height: 32px; padding: 5px; border-radius: 50%; background: rgb(18 20 26 / 88%); color: #e4c48a; --mark-ink: #12141a; }
.stack-card[aria-selected=true] { border-color: var(--orange, #f05537); box-shadow: inset 0 0 0 1px var(--orange, #f05537); }
.stack-selection { position: absolute; right: 7px; top: 8px; display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; background: var(--ink, #12141a); color: #fff; }
.stack-card:focus-visible { outline: 2px solid var(--ink, #12141a); outline-offset: 2px; }
.stack-help { margin: 10px 0 0; color: var(--muted, #475467); font-size: 11px; line-height: 1.6; }
</style>
