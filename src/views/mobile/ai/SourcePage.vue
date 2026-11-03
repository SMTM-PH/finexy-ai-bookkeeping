<template>
    <f7-page class="mobile-ai-source-page" @page:afterin="enter" @page:beforeout="leave">
        <f7-navbar title="AI 来源录入" :back-link="tt('Back')" />
        <ledger-context-notice />
        <f7-block v-if="navigationError" strong inset role="alert">
            <p>{{ navigationError }}</p>
            <p>原来源保留，不会为另一账本打开新来源。</p>
            <button type="button" @click="retryOriginal">重试原入口</button>
            <f7-button outline href="/">返回总览</f7-button>
        </f7-block>
        <f7-block v-if="visible && !navigationError">
            <p role="status" aria-live="polite">{{ status }} · {{ ledgerName }}</p>
            <p v-if="error" role="alert">{{ error }}</p>
            <p v-if="inputError" role="alert">{{ inputError }}</p>
            <p v-if="clipboardError" role="alert">{{ clipboardError }}</p>
            <button v-if="error && !busy" type="button" :disabled="busy" @click="initialize">重新读取本机状态</button>

            <template v-if="pending">
                <p>{{ pending.receipt ? '已保存待复核，只重新读取原条目。' : '保存结果待确认，原请求已保留，不能另建或丢弃。' }}</p>
                <div class="source-actions">
                    <button type="button" :disabled="busy || (!pending.receipt && !canWrite)" @click="recoverPending">{{ pending.receipt ? '重新读取原条目' : '重试原保存请求' }}</button>
                </div>
            </template>

            <template v-else-if="recovery">
                <p>本机有此账本的来源草稿。继续编辑不会发送给模型；识别结果若已保存也会恢复。</p>
                <div class="source-actions">
                    <button type="button" :disabled="busy || !canWrite" @click="resume">继续编辑来源草稿</button>
                    <button type="button" :disabled="busy || !canWrite" @click="discardRequested = true">丢弃来源草稿</button>
                </div>
                <div v-if="discardRequested" role="group" aria-label="确认丢弃来源草稿">
                    <p>确认移除这份本机文字、原图片和裁剪设置？服务端条目与账户余额保留。</p>
                    <div class="source-actions">
                        <button type="button" :disabled="busy" @click="discard">确认丢弃来源</button>
                        <button type="button" :disabled="busy" @click="discardRequested = false">继续保留</button>
                    </div>
                </div>
            </template>

            <template v-else-if="draft && ready">
                <fieldset :disabled="locked">
                    <legend>选择来源</legend>
                    <div class="source-kind">
                        <label><input type="radio" :checked="draft.input.sourceType === 1" name="ai-source-kind" @change="setKind(1)">文字</label>
                        <label><input type="radio" :checked="draft.input.sourceType === 2" name="ai-source-kind" @change="setKind(2)">票据图片</label>
                    </div>
                    <label class="source-label">{{ draft.input.sourceType === 1 ? '需要识别的文字' : '票据来源说明' }}
                        <textarea :value="draft.input.sourceText" rows="5" @input="changeText(($event.target as HTMLTextAreaElement).value)" />
                    </label>
                    <p>最多 8000 UTF-8 字节。内容先保存在当前浏览器，不会自动上传。</p>
                    <div class="source-actions">
                        <button type="button" :disabled="locked" @click="pasteFromClipboard">粘贴剪贴板文字</button>
                    </div>
                    <template v-if="draft.input.sourceType === 2">
                        <label class="source-file">选择原图片<input type="file" accept="image/jpeg,image/png,image/webp" @change="selectImage"></label>
                        <p>支持不超过 20 MB 的 JPEG、PNG、WebP。原图加密保存在本机，保存到待复核后移除本机原图。</p>
                        <div v-if="imageUrl" class="receipt-image">
                            <img :src="imageUrl" alt="本机原票据，裁剪边界见四角控件">
                            <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><polygon :points="draft.input.corners.map(p => `${p.x * 100},${p.y * 100}`).join(' ')" /></svg>
                        </div>
                        <p v-if="draft.input.image">原图：{{ draft.input.image.name }} · 顺时针旋转 {{ draft.input.rotation }}°</p>
                        <div class="crop-corners" v-if="draft.input.image">
                            <fieldset v-for="(corner, index) in draft.input.corners" :key="index">
                                <legend>{{ cornerNames[index] }}</legend>
                                <label>横向百分比<input type="number" min="0" max="100" step="1" :value="Math.round(corner.x * 10000) / 100" @change="setCorner(index, 'x', ($event.target as HTMLInputElement).value)"></label>
                                <label>纵向百分比<input type="number" min="0" max="100" step="1" :value="Math.round(corner.y * 10000) / 100" @change="setCorner(index, 'y', ($event.target as HTMLInputElement).value)"></label>
                            </fieldset>
                        </div>
                        <div v-if="draft.input.image" class="source-actions" aria-label="票据裁剪替代操作">
                            <button type="button" @click="transformCrop('up')">上移边界</button><button type="button" @click="transformCrop('down')">下移边界</button>
                            <button type="button" @click="transformCrop('left')">左移边界</button><button type="button" @click="transformCrop('right')">右移边界</button>
                            <button type="button" @click="transformCrop('expand')">扩大范围</button><button type="button" @click="transformCrop('shrink')">缩小范围</button>
                            <button type="button" @click="rotate">顺时针旋转 90°</button><button type="button" @click="resetCrop">重置裁剪与旋转</button>
                            <button type="button" @click="previewCrop">预览裁剪结果</button>
                        </div>
                        <img v-if="cropUrl" class="crop-preview" :src="cropUrl" alt="将发送给模型的裁剪和旋转结果">
                    </template>
                </fieldset>
                <p v-if="draft.recognizedData" class="recognition-result" role="status">AI 识别，需逐项复核：{{ recognitionSummary }}</p>
                <p v-if="draft.failureReason" role="status">{{ draft.failureReason }}</p>
                <template v-if="privacyRequested">
                    <p role="note">本次{{ draft.input.sourceType === 1 ? '文字' : '裁剪后的图片' }}将发送到服务器配置的模型，并使用此账本的账户、分类作为识别上下文。图片不会作为 AI 配置或流水附件保存；识别完成后仍需复核，不会自动入账。</p>
                    <div class="source-actions">
                        <button class="source-primary" type="button" :disabled="locked" @click="recognize">确认发送并识别</button>
                        <button type="button" :disabled="busy" @click="privacyRequested = false">取消发送</button>
                    </div>
                </template>
                <template v-else-if="previewRequest">
                    <p>保存到「{{ ledgerName }}」待复核，来源和识别结果保留在服务器；账户余额保持原值。</p>
                    <p>{{ draft.recognizedData ? recognitionSummary : '未识别，后续手动补全账户、金额、日期和分类。' }}</p>
                    <div class="source-actions">
                        <button class="source-primary" type="button" :disabled="busy || !canWrite" @click="createQueue">确认保存待复核</button>
                        <button type="button" :disabled="busy" @click="previewRequest = null">返回编辑来源</button>
                    </div>
                </template>
                <div v-else class="source-actions">
                    <button :class="{ 'source-primary': !draft.recognizedData }" type="button" :disabled="locked || !canRecognize" @click="privacyRequested = true">发送前核对隐私说明</button>
                    <button :class="{ 'source-primary': !!draft.recognizedData }" type="button" :disabled="locked || !!inputError || !draft.recognizedData" @click="previewQueue(false)">预览待复核条目</button>
                    <button type="button" :disabled="locked || !!inputError || !draft.input.sourceText.trim()" @click="previewQueue(true)">改为手动复核</button>
                </div>
            </template>
            <button v-if="recognizing" type="button" @click="cancelRecognition">取消识别，保留来源</button>
            <div class="source-actions">
                <button type="button" :disabled="saving || (busy && !recognizing)" @click="closeAndBack">关闭并保留本机记录</button>
            </div>
        </f7-block>
    </f7-page>
</template>

<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';
import type { Router } from 'framework7/types';
import LedgerContextNotice from '@/components/mobile/LedgerContextNotice.vue';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useUserStore } from '@/stores/user.ts';
import { useLedgerPendingStore } from '@/stores/ledgerPending.ts';
import { getTransactionStateIdentity, isUserLogined } from '@/lib/userstate.ts';
import { mobilePendingRouteTarget } from '@/lib/mobile_pending_route.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import { useI18n } from '@/locales/helpers.ts';
import useAIReviewSourceSession from '@/views/base/ai/AIReviewSourceSessionBase.ts';

// Share-sheet text and images arrive as in-memory route props, never as URL fields.
const props = defineProps<{ f7route: Router.Route; f7router: Router.Router; sourceText?: string; sourceImage?: File; sourceType?: 1 | 2 }>();
const { tt } = useI18n();
const ledgers = useLedgersStore();
const user = useUserStore();
const pendingStore = useLedgerPendingStore();
const navigationError = ref('');
const openedIdentity = getTransactionStateIdentity();
const openedUsername = user.currentUserBasicInfo?.username;
let navigationTarget: Extract<LedgerPendingTarget, { kind: 'ai-source' }> | null = null;
let navigationInvalidated = false;
try {
    const target = mobilePendingRouteTarget(props.f7route.path, props.f7route.query);
    if (target && target.kind !== 'ai-source') throw new Error('原来源入口无效');
    navigationTarget = target;
} catch { navigationError.value = '原来源定位无效，请返回总览重新核对。'; }
const {
    visible, draft, recovery, pending, previewRequest, busy, recognizing, saving, ready, error, privacyRequested, discardRequested,
    imageUrl, cropUrl, inputError, cornerNames, current, canWrite, locked, ledgerName, status, canRecognize, recognitionSummary,
    open, initialize: initializeSession, changeText, setKind, selectImage, selectImageFile, setCorner, transformCrop, rotate, resetCrop, previewCrop,
    resume, discard, recognize, cancelRecognition, previewQueue, createQueue, recoverPending, close
} = useAIReviewSourceSession({ complete: () => { props.f7router.back(); }, closed: () => { /* The page keeps its own state until the user leaves. */ } });
const clipboardError = ref('');
let started = false;
let entering = false;
let entryGeneration = 0;

function assertNavigationCurrent(): void {
    if (navigationTarget && (navigationInvalidated || navigationTarget.ledgerId !== ledgers.selectedLedgerId ||
        openedIdentity !== getTransactionStateIdentity() || openedUsername !== user.currentUserBasicInfo?.username || !isUserLogined())) {
        throw new Error('账号或账本已变化，请回总览核对原来源后重新打开。');
    }
}
function assertOriginalSource(): void {
    if (!navigationTarget) return;
    assertNavigationCurrent();
    const matches = navigationTarget.recovery === 'pending'
        ? pending.value?.request.clientSessionId === navigationTarget.clientSessionId && pending.value?.draftRevision === navigationTarget.draftRevision
        : recovery.value?.revision === navigationTarget.draftRevision;
    if (!matches) throw new Error('原来源已变化或已处理，请回总览重新读取；不会打开替代来源。');
}
async function verifyNavigation(): Promise<void> {
    if (!navigationTarget) return;
    assertNavigationCurrent();
    await pendingStore.refresh(navigationTarget.ledgerId);
    await pendingStore.verifyTarget(navigationTarget);
    assertNavigationCurrent();
}
function rejectNavigation(reason: unknown): void {
    navigationError.value = reason instanceof Error ? reason.message : '原来源无法核验，请回总览重新读取。';
    void close();
}
function retryOriginal(): void { props.f7router.navigate(props.f7route.url, { reloadCurrent: true, animate: false }); }
async function initialize(): Promise<void> {
    if (entering) return;
    const serial = ++entryGeneration;
    entering = true;
    try {
        await verifyNavigation();
        if (serial !== entryGeneration) return;
        await initializeSession();
        if (serial === entryGeneration) assertOriginalSource();
    }
    catch (reason) { if (serial === entryGeneration) rejectNavigation(reason); }
    finally { if (serial === entryGeneration) entering = false; }
}

// Re-open whenever the session was closed (ledger or account switch) and apply the
// share/clipboard prefill only once; afterwards the persisted draft is the source of truth.
async function enter(): Promise<void> {
    if (navigationError.value || visible.value || entering) return;
    const serial = ++entryGeneration;
    entering = true;
    try {
        await verifyNavigation();
        if (serial !== entryGeneration) return;
        const first = !started;
        started = true;
        await open(navigationTarget?.ledgerId ?? ledgers.selectedLedgerId, first && !navigationTarget ? (props.sourceText ?? '') : '', first && !navigationTarget ? (props.sourceType ?? (props.sourceImage ? 2 : 1)) : 1, navigationTarget ?? undefined);
        if (serial !== entryGeneration) return;
        assertOriginalSource();
        if (first && !navigationTarget && props.sourceImage && current() && !recovery.value && !pending.value && !error.value) await selectImageFile(props.sourceImage);
    } catch (reason) { if (serial === entryGeneration) rejectNavigation(reason); }
    finally { if (serial === entryGeneration) entering = false; }
}
function leave(): void { ++entryGeneration; entering = false; void close(); }

async function pasteFromClipboard(): Promise<void> {
    clipboardError.value = '';
    try {
        const text = await navigator.clipboard.readText();
        if (!current()) return;
        if (!text || !text.trim()) { clipboardError.value = '剪贴板没有可用文字，请手动输入需要识别的文字'; return; }
        changeText(text.trim());
    } catch { clipboardError.value = '无法读取剪贴板，请手动输入需要识别的文字；不会自动发送给模型'; }
}

async function closeAndBack(): Promise<void> {
    if (saving.value || (busy.value && !recognizing.value)) return;
    await close();
    props.f7router.back();
}

watch(() => [ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], () => {
    if (!navigationTarget) return;
    navigationInvalidated = true;
    rejectNavigation(new Error('账号或账本已变化，请回总览核对原来源后重新打开。'));
}, { flush: 'sync' });
function checkNavigationSession(): void {
    if (!navigationTarget) return;
    try { assertNavigationCurrent(); }
    catch (reason) { navigationInvalidated = true; rejectNavigation(reason); }
}
window.addEventListener('storage', checkNavigationSession);
window.addEventListener('focus', checkNavigationSession);
document.addEventListener('visibilitychange', checkNavigationSession);
onUnmounted(() => {
    window.removeEventListener('storage', checkNavigationSession);
    window.removeEventListener('focus', checkNavigationSession);
    document.removeEventListener('visibilitychange', checkNavigationSession);
});
void enter();
</script>

<style scoped>
.mobile-ai-source-page { overflow-wrap: anywhere; }
.source-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.mobile-ai-source-page button { min-height: 44px; padding: 8px 12px; border: 1px solid currentColor; border-radius: 12px; color: var(--f7-text-color); background: var(--f7-block-strong-bg-color); font: inherit; }
.mobile-ai-source-page button:disabled { opacity: .55; }
.mobile-ai-source-page button.source-primary { font-weight: 700; }
.mobile-ai-source-page button:focus-visible { outline: 2px solid var(--f7-theme-color); outline-offset: 2px; }
.mobile-ai-source-page fieldset { border: 1px solid var(--f7-block-strong-border-color, currentColor); border-radius: 12px; padding: 12px; margin: 0 0 12px; }
.mobile-ai-source-page fieldset:disabled { opacity: .7; }
.mobile-ai-source-page .source-kind { display: flex; gap: 16px; flex-wrap: wrap; }
.mobile-ai-source-page label { display: inline-flex; align-items: center; min-height: 44px; gap: 8px; }
.mobile-ai-source-page .source-label { display: flex; flex-direction: column; align-items: stretch; }
.mobile-ai-source-page .source-file { display: flex; flex-direction: column; align-items: stretch; gap: 8px; }
.mobile-ai-source-page textarea, .mobile-ai-source-page input[type="number"] { border: 1px solid currentColor; border-radius: 8px; padding: 8px; min-height: 44px; color: var(--f7-text-color); background: var(--f7-block-strong-bg-color); font: inherit; width: 100%; }
.mobile-ai-source-page .crop-corners > fieldset { display: flex; flex-direction: column; gap: 8px; }
.mobile-ai-source-page .crop-corners label { justify-content: space-between; }
.mobile-ai-source-page .receipt-image { position: relative; width: fit-content; max-width: 100%; margin: 12px auto; }
.mobile-ai-source-page .receipt-image img { display: block; max-width: 100%; }
.mobile-ai-source-page .receipt-image svg { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.mobile-ai-source-page .receipt-image polygon { fill: transparent; stroke: var(--f7-theme-color); stroke-width: 1; vector-effect: non-scaling-stroke; }
.mobile-ai-source-page .crop-preview { display: block; max-width: 100%; margin: 12px auto; }
</style>
