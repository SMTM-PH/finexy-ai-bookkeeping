<template>
    <svg v-if="brand !== 'generic'" viewBox="0 0 64 64" fill="none" class="account-brand-mark" aria-hidden="true">
        <g stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
            <template v-if="brand === 'icbc'"><circle cx="32" cy="32" r="23"/><path d="M19 20h26M19 44h26M24 20v24m16-24v24M24 32h16"/></template>
            <template v-else-if="brand === 'boc'"><circle cx="32" cy="30" r="23"/><path d="M24 22h16v16H24zM32 7v15m0 16v19"/></template>
            <template v-else-if="brand === 'bocom'"><path d="m13 48 10-32h8L21 48zm20 0 10-32h8L41 48z" fill="currentColor" stroke="none"/></template>
            <template v-else-if="brand === 'psbc'"><path d="M10 18h31l-9 10H10m0 8h29l-9 10H10m34-28h11L44 46H33"/></template>
            <template v-else-if="brand === 'wechat'"><path d="M37 34c2-3 3-6 3-9 0-8-8-14-17-14S6 17 6 25c0 4 2 8 6 11l-2 8 9-5h4" fill="currentColor" stroke="none"/><path d="M58 39c0-8-8-14-17-14S24 31 24 39s8 14 17 14h4l9 5-2-9c4-2 6-6 6-10Z"/><circle cx="16" cy="23" r="1.7" fill="var(--mark-ink)" stroke="none"/><circle cx="29" cy="23" r="1.7" fill="var(--mark-ink)" stroke="none"/><circle cx="35" cy="38" r="1.5" fill="currentColor"/><circle cx="47" cy="38" r="1.5" fill="currentColor"/></template>
            <template v-else-if="brand === 'alipay'"><path d="M12 21h33M26 12v10M18 30h25c-4 16-21 26-30 17-8-8 3-15 17-10l25 12"/></template>
            <template v-else-if="brand === 'jd'"><path d="M16 26 10 12l17 8h10l17-8-6 14v17c0 10-32 10-32 0z"/><circle cx="24" cy="33" r="2" fill="currentColor"/><circle cx="40" cy="33" r="2" fill="currentColor"/><path d="m27 43 5 4 5-4"/></template>
            <template v-else-if="brand === 'douyin'"><path d="M35 12v29c0 13-22 16-22 4 0-9 10-13 16-9m6-24c2 10 7 16 17 16" stroke-width="7"/></template>
            <template v-else-if="brand === 'rural'"><path d="m8 40 24-24 24 24M16 36v14h32V36M27 50V36h10v14"/></template>
            <template v-else><path d="m8 46 16-22 10 13 10-24 12 33H8zM24 24l4-9"/></template>
        </g>
    </svg>
    <ItemIcon v-else icon-type="account" :icon-id="icon" color="ffffff" size="28px" />
</template>
<script setup lang="ts">
import ItemIcon from '@/components/desktop/ItemIcon.vue';
defineProps<{ brand: string; icon: string }>();
</script>
<style scoped>
.account-brand-mark { width: 100%; height: 100%; }
</style>
