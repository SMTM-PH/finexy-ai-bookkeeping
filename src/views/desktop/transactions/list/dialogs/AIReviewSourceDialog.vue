<template>
    <v-dialog v-model="visible" class="finexy-dialog" width="860" persistent :z-index="4700">
        <v-card class="source-dialog" title="AI 文字与票据录入" :subtitle="ledgerName">
            <v-card-text v-if="current()" class="source-body">
                <p role="status" aria-live="polite">{{ status }} · {{ ledgerName }}</p>
                <p v-if="error" role="alert">{{ error }}</p>
                <p v-if="inputError" role="alert">{{ inputError }}</p>
                <button v-if="error && !busy" type="button" @click="initialize">重新读取本机状态</button>
                <template v-if="pending">
                    <p>{{ pending.receipt ? '已保存待复核，只重新读取原条目。' : '保存结果待确认，原请求已保留，不能另建或丢弃。' }}</p>
                    <button type="button" :disabled="busy || (!pending.receipt && !canWrite)" @click="recoverPending">{{ pending.receipt ? '重新读取原条目' : '重试原保存请求' }}</button>
                </template>
                <template v-else-if="recovery">
                    <p>本机有此账本的来源草稿。继续编辑不会发送给模型；识别结果若已保存也会恢复。</p>
                    <button type="button" :disabled="busy || !canWrite" @click="resume">继续编辑来源草稿</button>
                    <button type="button" :disabled="busy || !canWrite" @click="discardRequested = true">丢弃来源草稿</button>
                    <div v-if="discardRequested" role="group" aria-label="确认丢弃来源草稿">
                        <p>确认移除这份本机文字、原图片和裁剪设置？服务端条目与账户余额保留。</p>
                        <button type="button" :disabled="busy" @click="discard">确认丢弃来源</button>
                        <button type="button" :disabled="busy" @click="discardRequested = false">继续保留</button>
                    </div>
                </template>
                <template v-else-if="draft && ready">
                    <fieldset :disabled="locked">
                        <legend>选择来源</legend>
                        <label><input type="radio" :checked="draft.input.sourceType === 1" name="ai-source-kind" @change="setKind(1)">文字</label>
                        <label><input type="radio" :checked="draft.input.sourceType === 2" name="ai-source-kind" @change="setKind(2)">票据图片</label>
                        <label class="source-label">{{ draft.input.sourceType === 1 ? '需要识别的文字' : '票据来源说明' }}
                            <textarea :value="draft.input.sourceText" rows="5" @input="changeText(($event.target as HTMLTextAreaElement).value)" />
                        </label>
                        <p>最多 8000 UTF-8 字节。内容先保存在当前浏览器，不会自动上传。</p>
                        <template v-if="draft.input.sourceType === 2">
                            <label class="source-label">选择原图片<input type="file" accept="image/jpeg,image/png,image/webp" @change="selectImage"></label>
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
                    <p v-if="draft.recognizedData" class="recognition-result">AI 识别，需逐项复核：{{ recognitionSummary }}</p>
                    <p v-if="draft.failureReason" role="status">{{ draft.failureReason }}</p>
                    <template v-if="privacyRequested">
                        <p role="note">本次{{ draft.input.sourceType === 1 ? '文字' : '裁剪后的图片' }}将发送到服务器配置的模型，并使用此账本的账户、分类作为识别上下文。图片不会作为 AI 配置或流水附件保存；识别完成后仍需复核，不会自动入账。</p>
                        <button class="source-primary" type="button" :disabled="locked" @click="recognize">确认发送并识别</button>
                        <button type="button" :disabled="busy" @click="privacyRequested = false">取消发送</button>
                    </template>
                    <template v-else-if="previewRequest">
                        <p>保存到「{{ ledgerName }}」待复核，来源和识别结果保留在服务器；账户余额保持原值。</p>
                        <p>{{ draft.recognizedData ? recognitionSummary : '未识别，后续手动补全账户、金额、日期和分类。' }}</p>
                        <button class="source-primary" type="button" :disabled="busy || !canWrite" @click="createQueue">确认保存待复核</button>
                        <button type="button" :disabled="busy" @click="previewRequest = null">返回编辑来源</button>
                    </template>
                    <div v-else class="source-actions">
                        <button :class="{ 'source-primary': !draft.recognizedData }" type="button" :disabled="locked || !canRecognize" @click="privacyRequested = true">发送前核对隐私说明</button>
                        <button :class="{ 'source-primary': !!draft.recognizedData }" type="button" :disabled="locked || !!inputError || !draft.recognizedData" @click="previewQueue(false)">预览待复核条目</button>
                        <button type="button" :disabled="locked || !!inputError || !draft.input.sourceText.trim()" @click="previewQueue(true)">改为手动复核</button>
                    </div>
                </template>
                <button v-if="recognizing" type="button" @click="cancelRecognition">取消识别，保留来源</button>
            </v-card-text>
            <v-card-actions><v-spacer /><v-btn color="secondary" variant="tonal" :disabled="saving || (busy && !recognizing)" @click="close">关闭并保留本机记录</v-btn></v-card-actions>
        </v-card>
    </v-dialog>
</template>

<script setup lang="ts">
import useAIReviewSourceSession from '@/views/base/ai/AIReviewSourceSessionBase.ts';

const emit = defineEmits<{ (e: 'complete', value: { ledgerId: string; id: string; deleted: boolean }): void; (e: 'closed'): void }>();
const {
    visible, draft, recovery, pending, previewRequest, busy, recognizing, saving, ready, error, privacyRequested, discardRequested,
    imageUrl, cropUrl, inputError, cornerNames, current, canWrite, locked, ledgerName, status, canRecognize, recognitionSummary,
    open, initialize, changeText, setKind, selectImage, setCorner, transformCrop, rotate, resetCrop, previewCrop, resume, discard,
    recognize, cancelRecognition, previewQueue, createQueue, recoverPending, close
} = useAIReviewSourceSession({ complete: value => emit('complete', value), closed: () => { emit('closed'); } });
defineExpose({ open });
</script>

<style scoped>
.source-dialog { color: var(--finexy-dialog-ink); }
.source-body { overflow-y: auto; max-height: 72dvh; }
.source-body p { margin: 12px 0; }
.source-body [role="alert"] { color: rgb(var(--v-theme-error)); }
.source-body button { min-height: 44px; padding: 8px 16px; margin: 4px; border: 1px solid var(--finexy-dialog-line); border-radius: 12px; background: var(--finexy-dialog-panel); color: inherit; }
.source-body button:disabled { opacity: .5; cursor: default; }
.source-body button.source-primary { background: var(--finexy-dialog-ink); color: var(--finexy-dialog-surface, rgb(var(--v-theme-on-primary))); }
.source-body fieldset { border: 1px solid var(--finexy-dialog-line); border-radius: 12px; padding: 12px; }
.source-body label { display: inline-flex; align-items: center; min-height: 44px; gap: 8px; margin-right: 16px; }
.source-body .source-label { display: flex; flex-direction: column; align-items: stretch; margin: 12px 0; }
.source-body textarea, .source-body input[type="number"] { border: 1px solid var(--finexy-dialog-line); border-radius: 8px; padding: 8px; color: inherit; background: var(--finexy-dialog-panel); min-height: 44px; }
.source-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.receipt-image { position: relative; width: fit-content; max-width: 100%; margin: 16px auto; }
.receipt-image img { display: block; max-width: 100%; max-height: 400px; }
.receipt-image svg { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.receipt-image polygon { fill: transparent; stroke: var(--finexy-dialog-accent); stroke-width: 1; vector-effect: non-scaling-stroke; }
.crop-corners { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.crop-corners label { display: flex; justify-content: space-between; margin: 0; }
.crop-corners input { width: 6em; }
.crop-preview { display: block; max-width: 100%; max-height: 320px; margin: 16px auto; }
@media (max-width: 600px) { .crop-corners { grid-template-columns: 1fr; } }
</style>
