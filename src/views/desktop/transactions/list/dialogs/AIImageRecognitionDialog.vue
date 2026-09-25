<template>
    <v-dialog class="finexy-dialog finexy-subdialog finexy-ai-recognition-dialog"
              width="800" max-width="calc(100vw - 24px)" max-height="calc(100dvh - 24px)"
              :z-index="4700" scrollable
              :persistent="loading || recognizing || !!imageFile" v-model="showState" @paste="onPaste">
        <v-card class="ai-recognition-card pa-sm-1 pa-md-2">
            <template #title>
                <h4 class="text-h4">{{ tt('AI Image Recognition') }}</h4>
            </template>

            <v-card-text class="ai-recognition-body d-flex flex-column flex-md-row flex-grow-1 overflow-y-auto">
                <div class="ai-recognition-canvas w-100 h-100 position-relative"
                     @dragenter.prevent="onDragEnter"
                     @dragover.prevent
                     @dragleave.prevent="onDragLeave"
                     @drop.prevent="onDrop">
                    <div class="d-flex w-100 h-100 justify-center align-center justify-content-center text-center px-4"
                         :class="{ 'dropzone': true, 'dropzone-dark': isDarkMode, 'dropzone-blurry-bg': loading || isDragOver || recognizing, 'dropzone-dragover': isDragOver }">
                        <div class="d-inline-flex flex-column" v-if="!loading && !imageFile && !isDragOver">
                            <h3 class="pa-2">{{ tt('You can drag and drop, paste or click to select a receipt or transaction image') }}</h3>
                            <span class="pa-2">{{ tt('Uploaded image and personal data will be sent to the large language model, please be aware of potential privacy risks.') }}</span>
                        </div>
                        <h3 class="pa-2" v-else-if="!loading && isDragOver">{{ tt('Release to load image') }}</h3>
                        <h3 class="pa-2" v-else-if="loading">{{ tt('Loading image...') }}</h3>
                        <h3 class="pa-2" v-else-if="recognizing">{{ tt('AI can make mistakes. Check important info.') }}</h3>
                    </div>
                    <v-img :class="{ 'cursor-pointer': !loading && !recognizing && !isDragOver, 'h-100': true }"
                           :src="imageSrc"
                           role="button" tabindex="0"
                           :aria-label="tt('Click here to select a receipt or transaction image')"
                           @click="showOpenImageDialog"
                           @keydown.enter="showOpenImageDialog"
                           @keydown.space.prevent="showOpenImageDialog">
                        <template #placeholder>
                            <div :class="{ 'w-100 h-100': true, 'bg-grey-200': !isDarkMode, 'bg-grey-100': isDarkMode }"></div>
                        </template>
                    </v-img>
                </div>
            </v-card-text>

            <v-card-text class="ai-recognition-actions">
                <div class="w-100 d-flex justify-center flex-wrap mt-sm-1 mt-md-2 gap-4">
                    <v-btn color="primary" :disabled="loading || recognizing || !imageFile" @click="recognize">
                        {{ tt('Recognize') }}
                        <v-progress-circular indeterminate size="22" class="ms-2" v-if="recognizing"></v-progress-circular>
                    </v-btn>
                    <v-btn color="secondary" variant="tonal" :disabled="loading"
                           @click="cancelRecognize" v-if="recognizing && cancelRecognizingUuid">{{ tt('Cancel Recognition') }}</v-btn>
                    <v-btn color="secondary" variant="tonal" :disabled="loading || recognizing"
                           @click="cancel" v-if="!recognizing || !cancelRecognizingUuid">{{ tt('Cancel') }}</v-btn>
                </div>
            </v-card-text>
        </v-card>
    </v-dialog>

    <snack-bar ref="snackbar" />
    <input ref="imageInput" type="file" style="display: none" :accept="SUPPORTED_IMAGE_EXTENSIONS" @change="openImage($event)" />
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';

import { ref, computed, useTemplateRef } from 'vue';
import { useTheme } from 'vuetify';

import { useI18n } from '@/locales/helpers.ts';

import { useTransactionsStore } from '@/stores/transaction.ts';

import { ImageUploadQualityType } from '@/core/image.ts';
import { KnownFileType } from '@/core/file.ts';
import { ThemeType } from '@/core/theme.ts';
import { SUPPORTED_IMAGE_EXTENSIONS } from '@/consts/file.ts';

import type { RecognizedTransactionResponse } from '@/models/large_language_model.ts';

export interface AIImageRecognitionResult {
    response: RecognizedTransactionResponse;
    imageFile: File;
}

import { generateRandomUUID } from '@/lib/misc.ts';
import { compressJpgImageByQuality } from '@/lib/ui/common.ts';
import logger from '@/lib/logger.ts';

type SnackBarType = InstanceType<typeof SnackBar>;

const theme = useTheme();

const { tt } = useI18n();

const transactionsStore = useTransactionsStore();

const snackbar = useTemplateRef<SnackBarType>('snackbar');
const imageInput = useTemplateRef<HTMLInputElement>('imageInput');

let resolveFunc: ((result: AIImageRecognitionResult) => void) | null = null;
let rejectFunc: ((reason?: unknown) => void) | null = null;

const showState = ref<boolean>(false);
const loading = ref<boolean>(false);
const recognizing = ref<boolean>(false);
const cancelRecognizingUuid = ref<string | undefined>(undefined);
const imageFile = ref<File | null>(null);
const imageSrc = ref<string | undefined>(undefined);
const isDragOver = ref<boolean>(false);

const isDarkMode = computed<boolean>(() => theme.global.name.value === ThemeType.Dark);

function loadImage(file: File): void {
    loading.value = true;
    imageFile.value = null;
    imageSrc.value = undefined;

    compressJpgImageByQuality(file, ImageUploadQualityType.HD720P).then(blob => {
        imageFile.value = KnownFileType.JPG.createFileFromBlob(blob, "image");
        imageSrc.value = URL.createObjectURL(blob);
        loading.value = false;
    }).catch(error => {
        imageFile.value = null;
        imageSrc.value = undefined;
        loading.value = false;
        logger.error('failed to compress image', error);
        snackbar.value?.showError('Unable to load image');
    });
}

function open(): Promise<AIImageRecognitionResult> {
    showState.value = true;
    loading.value = false;
    recognizing.value = false;
    cancelRecognizingUuid.value = undefined;
    imageFile.value = null;
    imageSrc.value = undefined;

    return new Promise((resolve, reject) => {
        resolveFunc = resolve;
        rejectFunc = reject;
    });
}

function showOpenImageDialog(): void {
    if (loading.value || recognizing.value || isDragOver.value) {
        return;
    }

    imageInput.value?.click();
}

function openImage(event: Event): void {
    if (!event || !event.target) {
        return;
    }

    const el = event.target as HTMLInputElement;

    if (!el.files || !el.files.length || !el.files[0]) {
        return;
    }

    const image = el.files[0] as File;

    el.value = '';

    loadImage(image);
}

function recognize(): void {
    if (loading.value || recognizing.value || !imageFile.value) {
        return;
    }

    const currentImageFile = imageFile.value;
    cancelRecognizingUuid.value = generateRandomUUID();
    recognizing.value = true;

    transactionsStore.recognizeReceiptImage({
        imageFile: imageFile.value,
        cancelableUuid: cancelRecognizingUuid.value
    }).then(response => {
        resolveFunc?.({ response: response, imageFile: currentImageFile });
        showState.value = false;
        recognizing.value = false;
        cancelRecognizingUuid.value = undefined;
    }).catch(error => {
        if (error.canceled) {
            return;
        }

        recognizing.value = false;
        cancelRecognizingUuid.value = undefined;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function cancelRecognize(): void {
    if (!cancelRecognizingUuid.value) {
        return;
    }

    transactionsStore.cancelRecognizeReceiptImage(cancelRecognizingUuid.value);
    recognizing.value = false;
    cancelRecognizingUuid.value = undefined;

    snackbar.value?.showMessage('User Canceled');
}

function cancel(): void {
    rejectFunc?.();
    showState.value = false;
    loading.value = false;
    recognizing.value = false;
    cancelRecognizingUuid.value = undefined;
    imageFile.value = null;
    imageSrc.value = undefined;
}

function onDragEnter(): void {
    if (loading.value || recognizing.value) {
        return;
    }

    isDragOver.value = true;
}

function onDragLeave(): void {
    isDragOver.value = false;
}

function onDrop(event: DragEvent): void {
    if (loading.value || recognizing.value) {
        return;
    }

    isDragOver.value = false;

    if (event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files.length && event.dataTransfer.files[0]) {
        loadImage(event.dataTransfer.files[0] as File);
    }
}

function onPaste(event: ClipboardEvent) {
    if (!event.clipboardData) {
        event.preventDefault();
        return;
    }

    for (let i = 0; i < event.clipboardData.items.length; i++) {
        const item = event.clipboardData.items[i];

        if (item && item.type.startsWith('image/')) {
            const file = item.getAsFile();

            if (file) {
                loadImage(file);
                event.preventDefault();
                return;
            }
        }
    }
}

defineExpose({
    open
});
</script>

<style>
.v-overlay.finexy-dialog.finexy-ai-recognition-dialog.v-overlay--active {
    z-index: 4700 !important;
}

.v-overlay.finexy-dialog.finexy-ai-recognition-dialog .v-card-title::before {
    content: "FINEXY / AI";
}

.ai-recognition-card {
    max-height: min(680px, calc(100dvh - 24px));
}

.ai-recognition-body {
    min-height: 360px;
    height: min(480px, calc(100dvh - 190px));
    padding-top: 18px !important;
    padding-bottom: 18px !important;
    background: linear-gradient(180deg, #fff 0, #fbfbfc 100%);
}

.ai-recognition-canvas {
    min-height: 320px;
    overflow: hidden;
    border: 1px dashed var(--finexy-dialog-line);
    border-radius: 16px;
    background: var(--finexy-dialog-panel);
}

.ai-recognition-canvas:focus-within {
    border-color: rgba(240, 85, 55, .72);
    box-shadow: 0 0 0 3px rgba(240, 85, 55, .1);
}

.v-overlay.finexy-ai-recognition-dialog .ai-recognition-canvas .v-img__placeholder > div {
    background: var(--finexy-dialog-panel) !important;
}

.v-overlay.finexy-ai-recognition-dialog .dropzone h3,
.v-overlay.finexy-ai-recognition-dialog .dropzone span,
.v-overlay.finexy-ai-recognition-dialog .dropzone.dropzone-dark h3,
.v-overlay.finexy-ai-recognition-dialog .dropzone.dropzone-dark span {
    color: var(--finexy-dialog-ink) !important;
    text-shadow: none !important;
}

.ai-recognition-actions {
    flex: 0 0 auto;
}

.dropzone {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    pointer-events: none;
    border-radius: 16px;
    z-index: 10;

    h3, span {
        color: rgb(var(--v-theme-on-grey-200)) !important;
        text-shadow: -1px -1px 0 #fff, 1px -1px 0 #fff, -1px 1px 0 #fff, 1px 1px 0 #fff;
    }

    &.dropzone-dark {
        h3, span {
            color: rgb(var(--v-theme-on-grey-100)) !important;
            text-shadow: -1px -1px 0 #000, 1px -1px 0 #000, -1px 1px 0 #000, 1px 1px 0 #000;
        }
    }
}

.dropzone-blurry-bg {
    /* stylelint-disable property-no-vendor-prefix */
    -webkit-backdrop-filter: blur(6px);
    backdrop-filter: blur(6px);
}

.dropzone-dragover {
    border: 3px dashed var(--finexy-dialog-accent);
    background: rgba(240, 85, 55, .06);
}

@media (max-width: 700px) {
    .ai-recognition-card {
        max-height: calc(100dvh - 16px);
    }

    .ai-recognition-body {
        min-height: 300px;
        height: calc(100dvh - 180px);
    }

    .ai-recognition-canvas {
        min-height: 260px;
    }
}
</style>
