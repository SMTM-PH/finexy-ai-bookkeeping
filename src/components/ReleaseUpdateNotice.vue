<template>
    <aside v-if="availableRelease" class="release-update" role="status" aria-live="polite" aria-label="软件更新提醒">
        <div class="release-update-copy">
            <strong>发现新版本 v{{ availableRelease.version }}</strong>
            <span>当前{{ availableRelease.currentSource === 'server' ? '服务' : '页面' }}版本 v{{ availableRelease.currentVersion }}。可查看发布说明并更新自托管服务。</span>
        </div>
        <div class="release-update-actions">
            <a :href="availableRelease.url" target="_blank" rel="noopener noreferrer">查看更新</a>
            <button type="button" aria-label="忽略此版本更新" @click="dismissWebUpdate">忽略此版本</button>
        </div>
    </aside>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { availableRelease, checkForWebUpdate, dismissWebUpdate } from '@/lib/release_update.ts';

onMounted(() => { void checkForWebUpdate().catch(() => {}); });
</script>

<style scoped>
.release-update {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin: 12px 0 18px;
    padding: 14px 16px;
    border: 1px solid rgba(224, 119, 91, .3);
    border-radius: 14px;
    background: #fff4ed;
    color: #3e332f;
}
.release-update-copy { display: grid; gap: 3px; }
.release-update-copy strong { font-size: 15px; }
.release-update-copy span { font-size: 13px; line-height: 1.5; }
.release-update-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.release-update-actions a, .release-update-actions button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-height: 44px;
    padding: 0 12px;
    border-radius: 9px;
    font: inherit;
    font-size: 13px;
    font-weight: 600;
    text-decoration: none;
    cursor: pointer;
}
.release-update-actions a { background: #bd553d; color: white; }
.release-update-actions button { border: 1px solid #c9b7ad; background: transparent; color: inherit; }
.release-update-actions a:focus-visible, .release-update-actions button:focus-visible { outline: 2px solid #9f422e; outline-offset: 2px; }
@media (max-width: 640px) {
    .release-update { flex-direction: column; align-items: stretch; margin: 12px 16px; }
    .release-update-actions { flex-wrap: wrap; }
    .release-update-actions a, .release-update-actions button { min-height: 48px; }
}
@media (prefers-color-scheme: dark) {
    .release-update { background: #382c29; border-color: #785044; color: #f6e9e2; }
    .release-update-actions button { border-color: #a9887d; }
}
</style>
