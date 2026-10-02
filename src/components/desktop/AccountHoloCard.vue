<template>
    <div class="account-holo-viewer">
        <div class="holo-stage">
            <article ref="card" class="holo-card" tabindex="0" role="group"
                     :aria-label="`${account.name}账户卡片，左右键切换账户，F键翻面，R键复位`"
                     :style="cardStyle" :data-account-id="account.id" :data-side="flipped ? 'back' : 'front'"
                     @pointerdown="startDrag" @pointermove="movePointer" @pointerup="endDrag"
                     @pointercancel="endDrag" @lostpointercapture="dragging = false"
                     @pointerleave="leavePointer" @keydown="onKeydown">
                <div class="holo-card-turn" :class="{ flipped }">
                    <div class="holo-face holo-front" :aria-hidden="flipped">
                        <div class="holo-foil" aria-hidden="true"></div>
                        <div class="holo-print">
                            <header class="holo-card-header"><span class="holo-brand">F <b>Finexy</b></span><span class="holo-currency">{{ account.currency }}</span></header>
                            <div class="holo-account-title"><span>{{ categoryName }}</span><h3>{{ account.name }}</h3></div>
                            <div class="holo-emblem" aria-hidden="true">
                                <svg viewBox="0 0 240 200" class="holo-orbits"><ellipse cx="120" cy="100" rx="104" ry="46" /><ellipse cx="120" cy="100" rx="104" ry="46" transform="rotate(60 120 100)" /><ellipse cx="120" cy="100" rx="104" ry="46" transform="rotate(120 120 100)" /><circle cx="120" cy="100" r="70" /></svg>
                                <ItemIcon icon-type="account" :icon-id="account.icon" color="ffffff" size="72px" />
                            </div>
                            <div class="holo-card-balance"><span>{{ balanceLabel }}</span><strong>{{ displayAmount }}</strong></div>
                            <footer class="holo-card-footer"><span><i aria-hidden="true" :style="accentStyle"></i>{{ hidden ? '已隐藏' : '正常' }}</span><span>{{ parentName || '当前账本' }}</span></footer>
                        </div>
                    </div>
                    <div class="holo-face holo-back" :aria-hidden="!flipped" :inert="!flipped">
                        <div class="holo-foil" aria-hidden="true"></div>
                        <div class="holo-print">
                            <header class="holo-card-header"><span class="holo-brand">F <b>Finexy</b></span><span class="holo-currency">账户资料</span></header>
                            <div class="holo-account-title"><span>{{ categoryName }} · {{ account.currency }}</span><h3>{{ account.name }}</h3></div>
                            <dl class="holo-back-facts"><div><dt>状态</dt><dd>{{ hidden ? '已隐藏' : '正常' }}</dd></div><div v-if="parentName"><dt>所属账户</dt><dd>{{ parentName }}</dd></div></dl>
                            <div class="holo-back-note"><span>账户备注</span><p>{{ account.comment || '未填写备注' }}</p></div>
                            <footer class="holo-card-footer"><span>{{ balanceLabel }}</span><b>{{ displayAmount }}</b></footer>
                        </div>
                    </div>
                </div>
            </article>
        </div>
        <p class="holo-hint">移动鼠标或拖动卡面，查看光泽</p>
        <div class="holo-actions">
            <button type="button" :aria-pressed="flipped" @click="flip"><v-icon :icon="mdiSwapHorizontal" size="18" />{{ flipped ? '回到正面' : '翻看背面' }}</button>
            <button type="button" @click="reset"><v-icon :icon="mdiRefresh" size="18" />复位卡片</button>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue';
import { mdiRefresh, mdiSwapHorizontal } from '@mdi/js';
import type { Account } from '@/models/account.ts';
import ItemIcon from '@/components/desktop/ItemIcon.vue';

const props = defineProps<{
    account: Account;
    categoryName: string;
    balanceLabel: string;
    displayAmount: string;
    hidden: boolean;
    parentName: string;
}>();
const emit = defineEmits<{ navigate: [direction: number] }>();
const card = useTemplateRef<HTMLElement>('card');
const flipped = ref(false);
const dragging = ref(false);
const tiltX = ref(0);
const tiltY = ref(0);
const lightX = ref(50);
const lightY = ref(35);
const reducedMotion = ref(false);
let motionQuery: MediaQueryList | undefined;
const cardStyle = computed(() => ({
    '--tilt-x': `${reducedMotion.value ? 0 : tiltX.value}deg`,
    '--tilt-y': `${reducedMotion.value ? 0 : tiltY.value}deg`,
    '--light-x': `${reducedMotion.value ? 50 : lightX.value}%`,
    '--light-y': `${reducedMotion.value ? 35 : lightY.value}%`,
}));
const accentStyle = computed(() => ({ backgroundColor: /^[0-9a-f]{6}$/i.test(props.account.color) ? `#${props.account.color}` : 'var(--holo-coral)' }));
function reset() {
    tiltX.value = 0;
    tiltY.value = 0;
    lightX.value = 50;
    lightY.value = 35;
    flipped.value = false;
    dragging.value = false;
}
function flip() {
    flipped.value = !flipped.value;
    tiltX.value = 0;
    tiltY.value = 0;
}
function startDrag(event: PointerEvent) {
    if (event.button !== 0 || reducedMotion.value) return;
    if (event.target instanceof Element && event.target.closest('.holo-back-note')) return;
    dragging.value = true;
    card.value?.setPointerCapture(event.pointerId);
}
function movePointer(event: PointerEvent) {
    if (reducedMotion.value || (!dragging.value && event.pointerType !== 'mouse')) return;
    if (!dragging.value && event.target instanceof Element && event.target.closest('.holo-back-note')) return;
    const bounds = card.value?.getBoundingClientRect();
    if (!bounds?.width || !bounds.height) return;
    const x = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width));
    const y = Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height));
    tiltX.value = (0.5 - y) * 14;
    tiltY.value = (x - 0.5) * 18;
    lightX.value = x * 100;
    lightY.value = y * 100;
}
function endDrag(event: PointerEvent) {
    dragging.value = false;
    if (card.value?.hasPointerCapture(event.pointerId)) card.value.releasePointerCapture(event.pointerId);
}
function leavePointer() {
    if (!dragging.value) {
        tiltX.value = 0;
        tiltY.value = 0;
        lightX.value = 50;
        lightY.value = 35;
    }
}
function onKeydown(event: KeyboardEvent) {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const key = event.key.toLowerCase();
    if (key === 'arrowleft' || key === 'arrowright') {
        event.preventDefault();
        emit('navigate', key === 'arrowleft' ? -1 : 1);
    } else if (key === 'f') {
        event.preventDefault();
        flip();
    } else if (key === 'r') {
        event.preventDefault();
        reset();
    }
}
function updateMotion() {
    reducedMotion.value = !!motionQuery?.matches;
    leavePointer();
}
watch(() => props.account.id, reset);
onMounted(() => {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    updateMotion();
    motionQuery.addEventListener('change', updateMotion);
});
onBeforeUnmount(() => motionQuery?.removeEventListener('change', updateMotion));
</script>

<style scoped>
.account-holo-viewer {
    --holo-ink: #12141a;
    --holo-paper: #ffffff;
    --holo-soft: #dfe3e9;
    --holo-coral: #f05537;
    --holo-gold: #d4b77c;
    --holo-ice: #a8cffc;
    --holo-mint: #8fcfc2;
}
.holo-stage { display: flex; justify-content: center; padding: 30px 24px 20px; perspective: 1200px; }
.holo-card {
    --holo-foil: conic-gradient(from 35deg at var(--light-x) var(--light-y), var(--holo-ice), var(--holo-mint), var(--holo-gold), var(--holo-coral), var(--holo-ice));
    width: min(100%, 340px); margin: 0; outline: none; transform: rotateX(var(--tilt-x)) rotateY(var(--tilt-y)); transform-style: preserve-3d; transition: transform 160ms ease-out; touch-action: pan-y; cursor: grab; user-select: none;
}
.holo-card:active { cursor: grabbing; }
.holo-card:focus-visible { outline: 2px solid var(--holo-ink); outline-offset: 10px; border-radius: 22px; }
.holo-card-turn { display: grid; transform-style: preserve-3d; transition: transform 280ms ease; }
.holo-card-turn.flipped { transform: rotateY(180deg); }
.holo-face { grid-area: 1 / 1; position: relative; display: flex; min-height: 450px; padding: 1px; border-radius: 22px; background: var(--holo-foil); backface-visibility: hidden; box-shadow: 0 20px 36px rgb(18 20 26 / 18%); overflow: hidden; }
.holo-back { transform: rotateY(180deg); }
.holo-face::before { content: ''; position: absolute; inset: 1px; border-radius: 21px; background: var(--holo-ink); }
.holo-face::after { content: ''; position: absolute; inset: 10px; border: 1px solid rgb(255 255 255 / 22%); border-radius: 14px; pointer-events: none; }
.holo-foil { position: absolute; inset: 1px; border-radius: 21px; background: repeating-linear-gradient(130deg, transparent 0 12px, rgb(255 255 255 / 12%) 12px 13px, transparent 13px 25px), var(--holo-foil); opacity: 0.22; pointer-events: none; }
.holo-print { position: relative; z-index: 1; display: flex; flex-direction: column; width: 100%; min-width: 0; padding: 25px; color: var(--holo-paper); }
.holo-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.holo-brand { display: flex; align-items: center; gap: 7px; color: var(--holo-paper); font-size: 18px; font-weight: 800; }
.holo-brand b { font-size: 13px; font-weight: 600; letter-spacing: 0.03em; }
.holo-currency { padding: 5px 8px; border: 1px solid rgb(255 255 255 / 35%); border-radius: 6px; font-size: 12px; }
.holo-account-title { margin-top: 25px; }
.holo-account-title > span { color: var(--holo-soft); font-size: 13px; }
.holo-account-title h3 { margin: 8px 0 0; color: var(--holo-paper); font-size: 24px; font-weight: 700; line-height: 1.45; overflow-wrap: anywhere; }
.holo-emblem { position: relative; display: grid; min-height: 170px; margin: 10px -10px; place-items: center; }
.holo-orbits { position: absolute; width: 100%; height: 100%; fill: none; stroke: var(--holo-paper); stroke-width: 0.7; opacity: 0.25; }
.holo-emblem :deep(.item-icon) { transform: translateZ(18px); }
.holo-card-balance { display: grid; gap: 7px; margin-top: auto; padding: 14px; border: 1px solid rgb(255 255 255 / 20%); border-radius: 12px; background: rgb(18 20 26 / 86%); }
.holo-card-balance span { color: var(--holo-soft); font-size: 13px; }
.holo-card-balance strong { color: var(--holo-paper); font-size: 32px; font-weight: 750; line-height: 1.3; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.holo-card-footer { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin-top: 20px; color: var(--holo-soft); font-size: 12px; }
.holo-card-footer > span { display: flex; align-items: center; gap: 6px; overflow-wrap: anywhere; }
.holo-card-footer i { width: 8px; height: 8px; border: 1px solid rgb(255 255 255 / 65%); border-radius: 50%; flex: none; }
.holo-back-facts { margin: 25px 0 0; }
.holo-back-facts > div { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid rgb(255 255 255 / 20%); font-size: 14px; }
.holo-back-facts dt { color: var(--holo-soft); }
.holo-back-facts dd { margin: 0; text-align: right; overflow-wrap: anywhere; }
.holo-back-note { margin: 24px 0; }
.holo-back-note > span { color: var(--holo-soft); font-size: 13px; }
.holo-back-note p { max-height: 180px; margin: 9px 0 0; overflow: auto; color: var(--holo-paper); font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; user-select: text; cursor: text; }
.holo-back .holo-card-footer { margin-top: auto; padding-top: 20px; }
.holo-card-footer b { color: var(--holo-paper); font-size: 17px; font-variant-numeric: tabular-nums; }
.holo-hint { margin: 0; color: var(--muted); font-size: 12px; text-align: center; }
.holo-actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 10px; margin-top: 12px; }
.holo-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 44px; padding: 0 16px; border: 1px solid var(--line); border-radius: 999px; background: var(--panel); color: var(--ink); font-size: 14px; }
.holo-actions button:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) {
    .holo-card, .holo-card-turn { transition: none; }
}
@media (max-width: 1300px) {
    .holo-stage { padding: 28px 8px 18px; }
    .holo-print { padding: 22px; }
    .holo-card-balance strong { font-size: 28px; }
}
</style>
