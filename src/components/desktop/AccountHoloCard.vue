<template>
    <div class="account-holo-viewer" :class="{ compact }">
        <div class="holo-stage">
            <article ref="card" class="holo-card" tabindex="0" role="group"
                     :aria-label="`${account.name}账户卡片，左右键切换账户，F键翻面，R键复位`"
                     :style="cardStyle" :data-theme="appearance.theme" :data-custom="!!appearance.background" :data-brand="art.brand" :data-artwork="art.artwork" :data-account-id="account.id" :data-side="flipped ? 'back' : 'front'"
                     @pointerdown="startDrag" @pointermove="movePointer" @pointerup="endDrag"
                     @pointercancel="endDrag" @lostpointercapture="dragging = false"
                     @pointerleave="leavePointer" @keydown="onKeydown">
                <div class="holo-card-turn" :class="{ flipped }">
                    <div class="holo-face holo-front" :aria-hidden="flipped">
                        <img v-if="appearance.background" class="holo-background" :src="appearance.background" alt="" aria-hidden="true" :style="backgroundStyle" draggable="false" />
                        <div v-if="!appearance.background" class="account-card-artwork" :data-pattern="art.pattern" aria-hidden="true">
                            <img class="holo-garden" :src="art.background" alt="" draggable="false" />
                            <img v-if="!appearance.subject" class="holo-illustration" :src="art.subject" alt="" draggable="false" />
                            <span class="holo-watermark"><AccountBrandMark :brand="art.brand" :icon="account.icon" /></span>
                            <span class="holo-engraving"></span>
                        </div>
                        <img v-if="appearance.subject" class="holo-subject" :src="appearance.subject" alt="" aria-hidden="true" :style="subjectStyle" draggable="false" />
                        <div class="holo-foil" aria-hidden="true"></div>
                        <div class="holo-stars" aria-hidden="true"></div>
                        <div class="holo-glare" aria-hidden="true"></div>
                        <div v-if="!appearance.background" class="holo-brand-seal" aria-hidden="true"><AccountBrandMark :brand="art.brand" :icon="account.icon" /></div>
                    </div>
                    <div class="holo-face holo-back" :aria-hidden="!flipped" :inert="!flipped">
                        <div class="holo-foil" aria-hidden="true"></div>
                        <div class="holo-glare" aria-hidden="true"></div>
                        <img class="holo-reverse-garden" :src="art.background" alt="" aria-hidden="true" draggable="false" />
                        <div class="holo-reverse-seal" aria-hidden="true"><AccountBrandMark :brand="art.brand" :icon="account.icon" /></div>
                    </div>
                </div>
            </article>
        </div>
        <div class="holo-caption">
            <div class="holo-caption-identity"><h3>{{ account.name }}</h3><span>{{ categoryName }} · {{ account.currency }}</span></div>
            <div class="holo-caption-balance"><span>{{ balanceLabel }}</span><strong>{{ displayAmount }}</strong></div>
            <span class="holo-caption-status">{{ hidden ? '已隐藏' : '正常' }}<template v-if="parentName"> · {{ parentName }}</template></span>
        </div>
        <p v-if="flipped" class="holo-outside-note">{{ account.comment || '未填写备注' }}</p>
        <p v-if="!compact" class="holo-hint">移动鼠标或拖动卡面，查看光泽</p>
        <div class="holo-actions">
            <button type="button" :aria-pressed="flipped" :aria-label="flipped ? '回到正面' : '翻看背面'" @click="flip"><v-icon :icon="mdiSwapHorizontal" size="18" />{{ compact ? (flipped ? '正面' : '翻面') : (flipped ? '回到正面' : '翻看背面') }}</button>
            <button type="button" aria-label="复位卡片" @click="reset"><v-icon :icon="mdiRefresh" size="18" />{{ compact ? '复位' : '复位卡片' }}</button>
            <button v-if="compact" type="button" @click="emit('details')">查看详情</button>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue';
import { mdiRefresh, mdiSwapHorizontal } from '@mdi/js';
import type { Account } from '@/models/account.ts';
import AccountBrandMark from '@/components/desktop/AccountBrandMark.vue';
import { accountArt } from '@/lib/ui/account_art.ts';
import { defaultAccountCardStyle, type AccountCardStyle } from '@/models/account_card.ts';

const props = defineProps<{
    account: Account;
    categoryName: string;
    balanceLabel: string;
    displayAmount: string;
    hidden: boolean;
    parentName: string;
    appearance?: AccountCardStyle;
    compact?: boolean;
}>();
const appearance = computed(() => props.appearance || defaultAccountCardStyle());
const art = computed(() => accountArt(props.account.name, props.account.category));
const emit = defineEmits<{ navigate: [direction: number]; details: [] }>();
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
    '--foil-opacity': appearance.value.foil / 100,
    '--foil-shift-x': `${reducedMotion.value ? 0 : (lightX.value - 50) * 0.22}px`,
    '--foil-shift-y': `${reducedMotion.value ? 0 : (lightY.value - 35) * 0.18}px`,
    '--subject-shift-x': `${reducedMotion.value ? 0 : tiltY.value * appearance.value.depth / 100}px`,
    '--subject-shift-y': `${reducedMotion.value ? 0 : -tiltX.value * appearance.value.depth / 100}px`,
}));
const backgroundStyle = computed(() => ({ objectPosition: `${appearance.value.imageX}% ${appearance.value.imageY}%`, transform: `scale(${appearance.value.imageZoom / 100})`, transformOrigin: `${appearance.value.imageX}% ${appearance.value.imageY}%` }));
const subjectStyle = computed(() => ({ left: `${appearance.value.subjectX}%`, top: `${appearance.value.subjectY}%`, width: `${appearance.value.subjectZoom}%`, transform: 'translate(calc(-50% + var(--subject-shift-x)), calc(-50% + var(--subject-shift-y)))' }));
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
    dragging.value = true;
    card.value?.setPointerCapture(event.pointerId);
}
function movePointer(event: PointerEvent) {
    if (reducedMotion.value || (!dragging.value && event.pointerType !== 'mouse')) return;
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
.account-holo-viewer { --holo-ink: #12141a; --holo-gold: #e4c48a; --holo-ice: #a8cffc; --holo-mint: #8fcfc2; --holo-coral: #f05537; --mark-ink: #12141a; }
.holo-stage { display: flex; justify-content: center; padding: 24px 24px 18px; perspective: 1200px; }
.holo-card { width: min(100%, 380px, calc(100vw - 48px)); margin: 0; outline: none; transform: rotateX(var(--tilt-x)) rotateY(var(--tilt-y)); transform-style: preserve-3d; transition: transform 160ms ease-out; touch-action: pan-y; cursor: grab; user-select: none; }
.holo-card:active { cursor: grabbing; }
.holo-card:focus-visible { outline: 2px solid var(--ink); outline-offset: 8px; border-radius: 17px; }
.holo-card-turn { display: grid; transform-style: preserve-3d; transition: transform 280ms ease; }
.holo-card-turn.flipped { transform: rotateY(180deg); }
.holo-face { grid-area: 1 / 1; position: relative; aspect-ratio: 1.586 / 1; width: 100%; border-radius: 17px; border: 2px solid var(--holo-gold); background: var(--holo-ink); backface-visibility: hidden; box-shadow: 0 24px 34px -15px rgb(18 20 26 / 42%), 0 3px 8px rgb(18 20 26 / 14%); overflow: hidden; }
.holo-face::after { content: ''; position: absolute; z-index: 6; inset: 5px; border: 1px solid rgb(228 196 138 / 70%); border-radius: 11px; pointer-events: none; }
.holo-back { transform: rotateY(180deg); }
.holo-background, .account-card-artwork, .holo-garden, .holo-illustration, .holo-reverse-garden { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.holo-background, .holo-garden, .holo-reverse-garden { object-fit: cover; }
.holo-garden { filter: saturate(.8) brightness(.7); }
.holo-illustration { inset: -10% -4% auto auto; width: 78%; height: 125%; object-fit: contain; transform: translate(var(--subject-shift-x), var(--subject-shift-y)) scale(.96); transition: transform 160ms ease-out; }
.holo-card[data-brand=wechat] .holo-garden, .holo-card[data-brand=psbc] .holo-garden, .holo-card[data-brand=rural] .holo-garden { filter: hue-rotate(55deg) saturate(.8) brightness(.65); }
.holo-card[data-brand=bocom] .holo-garden, .holo-card[data-brand=alipay] .holo-garden { filter: hue-rotate(330deg) saturate(.7) brightness(.65); }
.holo-card[data-theme=midnight] .holo-garden { filter: saturate(.6) brightness(.5); }
.holo-card[data-theme=mint] .holo-garden { filter: hue-rotate(55deg) saturate(.6) brightness(.65); }
.holo-card[data-theme=copper] .holo-garden { filter: sepia(.35) saturate(.9) brightness(.8); }
.holo-card[data-artwork=moon] .holo-illustration { width: 60%; right: 3%; top: -7%; height: 120%; }
.holo-watermark { position: absolute; left: 8%; bottom: 18%; width: 20%; aspect-ratio: 1; color: var(--holo-gold); opacity: .18; transform: rotate(-12deg); }
.holo-engraving { position: absolute; left: 8%; bottom: 12%; width: 22%; height: 8%; border-top: 1px solid var(--holo-gold); border-bottom: 1px solid var(--holo-gold); opacity: .5; background: repeating-linear-gradient(90deg, transparent 0 7px, var(--holo-gold) 7px 8px, transparent 8px 14px); }
.holo-subject { position: absolute; z-index: 1; max-width: none; height: auto; pointer-events: none; }
.holo-foil { position: absolute; z-index: 2; inset: -15%; background: repeating-linear-gradient(118deg, transparent 0 28px, rgb(255 255 255 / 18%) 29px 30px, transparent 31px 58px), conic-gradient(from 35deg at var(--light-x) var(--light-y), var(--holo-ice), var(--holo-mint), var(--holo-gold), var(--holo-coral), var(--holo-ice)); opacity: calc(var(--foil-opacity) * .34); mix-blend-mode: screen; transform: translate(var(--foil-shift-x), var(--foil-shift-y)); transition: transform 160ms ease-out; pointer-events: none; }
.holo-stars { position: absolute; z-index: 3; inset: 0; background-image: radial-gradient(circle, rgb(255 255 255 / 80%) 0 .6px, transparent 1.5px), radial-gradient(circle, var(--holo-gold) 0 .8px, transparent 1.8px); background-size: 47px 61px, 83px 97px; background-position: var(--light-x) var(--light-y), 13px 31px; opacity: calc(var(--foil-opacity) * .55); mix-blend-mode: screen; pointer-events: none; }
.holo-glare { position: absolute; z-index: 4; inset: -45%; background: linear-gradient(118deg, transparent 34%, rgb(255 255 255 / 8%) 43%, rgb(255 255 255 / 65%) 47%, transparent 52%); opacity: calc(var(--foil-opacity) * .45); transform: translate(calc(var(--foil-shift-x) * 3), calc(var(--foil-shift-y) * 3)); transition: transform 160ms ease-out; pointer-events: none; }
.holo-brand-seal { position: absolute; z-index: 5; left: 14px; top: 14px; width: 46px; height: 46px; padding: 7px; border: 1px solid var(--holo-gold); border-radius: 50%; color: var(--holo-gold); background: rgb(18 20 26 / 82%); box-shadow: 0 2px 8px rgb(18 20 26 / 25%); }
.holo-reverse-garden { opacity: .32; filter: saturate(.45); transform: scale(1.04); }
.holo-reverse-seal { position: absolute; z-index: 5; width: 30%; height: auto; aspect-ratio: 1; left: 35%; top: 25%; padding: 18px; border: 1px solid var(--holo-gold); border-radius: 50%; color: var(--holo-gold); background: var(--holo-ink); box-shadow: 0 0 0 10px rgb(228 196 138 / 10%), 0 0 0 11px rgb(228 196 138 / 35%); display: grid; place-items: center; }
.holo-caption { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 14px; max-width: 360px; margin: 0 auto 12px; color: var(--ink); }
.holo-caption h3 { margin: 0 0 3px; color: var(--ink, #12141a); font-size: 16px; font-weight: 650; line-height: 1.4; overflow-wrap: anywhere; }
.holo-caption-identity > span, .holo-caption-balance > span, .holo-caption-status { font-size: 12px; color: var(--muted); }
.holo-caption-balance { display: grid; min-width: 0; gap: 3px; align-content: start; text-align: right; }
.holo-caption-balance strong { font-size: 18px; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.holo-caption-status { grid-column: 1 / -1; }
.holo-outside-note { max-width: 360px; max-height: 140px; overflow: auto; margin: 8px auto 12px; color: var(--ink); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.7; }
.holo-hint { margin: 0; color: var(--muted); font-size: 12px; text-align: center; }
.holo-actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.holo-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 44px; padding: 0 14px; border: 1px solid var(--line); border-radius: 999px; background: var(--panel); color: var(--ink); font-size: 13px; }
.holo-actions button:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
.compact .holo-stage { padding: 8px 0 16px; }
.compact .holo-card { width: min(100%, 260px); }
.compact .holo-face { border-radius: 11px; }
.compact .holo-face::after { inset: 3px; border-radius: 7px; }
.compact .holo-brand-seal { width: 30px; height: 30px; padding: 5px; left: 8px; top: 8px; }
.compact .holo-reverse-seal { padding: 8px; }
.compact .holo-caption { grid-template-columns: minmax(0, 1fr); gap: 4px; margin-bottom: 4px; }
.compact .holo-caption h3 { font-size: 14px; }
.compact .holo-caption-balance { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; text-align: left; }
.compact .holo-caption-balance strong { font-size: 19px; }
.compact .holo-caption-status { display: none; }
.compact .holo-actions { gap: 5px; margin-top: 4px; }
.compact .holo-actions button { padding: 0 8px; font-size: 12px; gap: 4px; }
@media (prefers-reduced-motion: reduce) { .holo-card, .holo-card-turn, .holo-illustration, .holo-foil, .holo-glare { transition: none; } }
@media (max-width: 1300px) { .holo-stage { padding: 24px 8px 18px; } .compact .holo-stage { padding: 8px 0 16px; } }
</style>
