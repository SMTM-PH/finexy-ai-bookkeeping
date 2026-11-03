<template>
    <div class="card-designer" :class="{ editing }">
        <div class="card-preview">
            <AccountHoloCard v-bind="cardProps" :appearance="editing ? draft : saved" @navigate="direction => { if (!editing) emit('navigate', direction); }" />
            <div class="designer-entry">
                <button v-if="!editing" type="button" :disabled="loading || !!loadError" @click="beginEdit">{{ loading ? '正在读取卡面' : '自定义卡面' }}</button>
                <button v-if="!editing && !loading && !loadError" type="button" @click="load">刷新卡面</button>
            </div>
            <p v-if="loadError" class="designer-error" role="alert">{{ loadError }} <button type="button" :disabled="loading" @click="load">重试读取</button></p>
            <p v-if="message" class="designer-status" role="status">{{ message }}</p>
        </div>
        <form v-if="editing" class="designer-controls" aria-label="自定义账户卡面" @submit.prevent="save">
            <header><h3>把卡面换成你喜欢的样子</h3><p>调整即预览，保存后在其他设备登录同一账号可读取。图片仅保存到你的自托管服务器，其他成员有各自的卡面。</p></header>
            <fieldset :disabled="saving || imageBusy">
                <legend>卡面材质</legend>
                <div class="material-options">
                    <button v-for="theme in themes" :key="theme.id" type="button" :aria-pressed="draft.theme === theme.id" @click="draft.theme = theme.id"><i :data-theme="theme.id" aria-hidden="true"></i>{{ theme.label }}</button>
                </div>
            </fieldset>
            <fieldset :disabled="saving || imageBusy">
                <legend>背景图</legend>
                <label class="image-picker">{{ draft.background ? '更换背景图' : '选择背景图' }}<input type="file" accept="image/jpeg,image/png,image/webp" aria-label="选择背景图" @change="event => selectImage(event, 'background')" /></label>
                <small>JPG、PNG、WebP，最大 10 MB。图片会压缩后保存。</small>
                <template v-if="draft.background">
                    <label>水平位置 <output>{{ draft.imageX }}%</output><input v-model.number="draft.imageX" type="range" min="0" max="100" aria-label="背景水平位置" /></label>
                    <label>垂直位置 <output>{{ draft.imageY }}%</output><input v-model.number="draft.imageY" type="range" min="0" max="100" aria-label="背景垂直位置" /></label>
                    <label>缩放 <output>{{ draft.imageZoom }}%</output><input v-model.number="draft.imageZoom" type="range" min="100" max="200" aria-label="背景缩放" /></label>
                    <button type="button" @click="draft.background = ''">移除背景图</button>
                </template>
            </fieldset>
            <fieldset :disabled="saving || imageBusy">
                <legend>主体图（可选）</legend>
                <label class="image-picker">{{ draft.subject ? '更换主体图' : '选择主体图' }}<input type="file" accept="image/jpeg,image/png,image/webp" aria-label="选择主体图" @change="event => selectImage(event, 'subject')" /></label>
                <small>透明 PNG 效果更好。主体随视角轻微移动；仅背景图也可保存。</small>
                <template v-if="draft.subject">
                    <label>水平位置 <output>{{ draft.subjectX }}%</output><input v-model.number="draft.subjectX" type="range" min="0" max="100" aria-label="主体水平位置" /></label>
                    <label>垂直位置 <output>{{ draft.subjectY }}%</output><input v-model.number="draft.subjectY" type="range" min="0" max="100" aria-label="主体垂直位置" /></label>
                    <label>缩放 <output>{{ draft.subjectZoom }}%</output><input v-model.number="draft.subjectZoom" type="range" min="25" max="200" aria-label="主体缩放" /></label>
                    <button type="button" @click="draft.subject = ''">移除主体图</button>
                </template>
            </fieldset>
            <fieldset :disabled="saving || imageBusy">
                <legend>光泽与层次</legend>
                <label>光泽强度 <output>{{ draft.foil }}%</output><input v-model.number="draft.foil" type="range" min="0" max="100" aria-label="光泽强度" /></label>
                <label>主体景深 <output>{{ draft.depth }}%</output><input v-model.number="draft.depth" type="range" min="0" max="100" aria-label="主体景深" /></label>
                <button type="button" @click="resetDraft">恢复默认卡面</button>
                <small>恢复默认仅改变预览，保存后才替换服务器卡面。</small>
            </fieldset>
            <p v-if="imageBusy" role="status">正在处理图片…</p>
            <p v-if="error" class="designer-error" role="alert">{{ error }}</p>
            <p v-if="conflict" class="designer-conflict">其他设备已更新卡面。当前草稿已保留。<button type="button" :disabled="saving" @click="rebase">读取最新版本，保留当前草稿</button></p>
            <footer>
                <small>取消或切换账户会放弃未保存的调整。</small>
                <div><button type="button" :disabled="saving" @click="cancel">取消调整</button><button type="submit" class="designer-save" :disabled="saving || imageBusy || conflict">{{ saving ? '正在保存' : '保存卡面' }}</button></div>
            </footer>
        </form>
    </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { Account } from '@/models/account.ts';
import { defaultAccountCardStyle, parseAccountCardStyle, prepareAccountCardImage, type AccountCardStyle } from '@/models/account_card.ts';
import { useUserStore } from '@/stores/user.ts';
import services from '@/lib/services.ts';
import AccountHoloCard from './AccountHoloCard.vue';

const props = defineProps<{ account: Account; ledgerId: string; categoryName: string; balanceLabel: string; displayAmount: string; hidden: boolean; parentName: string }>();
const emit = defineEmits<{ navigate: [direction: number] }>();
const userStore = useUserStore();
const cardProps = computed(() => ({ account: props.account, categoryName: props.categoryName, balanceLabel: props.balanceLabel, displayAmount: props.displayAmount, hidden: props.hidden, parentName: props.parentName }));
const scope = computed(() => `${userStore.currentUserBasicInfo?.username || ''}:${props.ledgerId}:${props.account.id}`);
const saved = ref<AccountCardStyle>(defaultAccountCardStyle());
const draft = ref<AccountCardStyle>(defaultAccountCardStyle());
const editing = ref(false);
const loading = ref(false);
const saving = ref(false);
const imageBusy = ref(false);
const loadError = ref('');
const error = ref('');
const message = ref('');
const conflict = ref(false);
let generation = 0;
let imageGeneration = 0;
const themes: { id: AccountCardStyle['theme']; label: string }[] = [{ id: 'classic', label: '经典镭射' }, { id: 'midnight', label: '午夜蓝' }, { id: 'mint', label: '松石绿' }, { id: 'copper', label: '暖铜色' }];

async function fetchStyle(): Promise<AccountCardStyle> {
    const response = await services.getAccountCard(props.ledgerId, props.account.id);
    if (!response.data.success) throw new Error();
    return parseAccountCardStyle(response.data.result);
}
async function load(): Promise<void> {
    const own = ++generation;
    loading.value = true; loadError.value = ''; saved.value = defaultAccountCardStyle();
    try {
        const style = await fetchStyle();
        if (own === generation) saved.value = style;
    } catch {
        if (own === generation) loadError.value = '未能读取服务器卡面，请重试。';
    } finally { if (own === generation) loading.value = false; }
}
function beginEdit(): void { draft.value = { ...saved.value }; editing.value = true; error.value = ''; message.value = ''; conflict.value = false; }
function cancel(): void { imageGeneration++; imageBusy.value = false; editing.value = false; error.value = ''; conflict.value = false; }
function resetDraft(): void { draft.value = { ...defaultAccountCardStyle(), revision: draft.value.revision }; error.value = ''; }
async function selectImage(event: Event, layer: 'background' | 'subject'): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0]; input.value = '';
    if (!file) return;
    const ownScope = scope.value;
    const own = ++imageGeneration;
    imageBusy.value = true; error.value = '';
    try {
        const image = await prepareAccountCardImage(file, layer === 'subject');
        if (ownScope === scope.value && own === imageGeneration && editing.value) draft.value[layer] = image;
    } catch (reason) {
        if (ownScope === scope.value && own === imageGeneration && editing.value) error.value = reason instanceof Error ? reason.message : '图片处理失败，请重试。';
    } finally { if (own === imageGeneration) imageBusy.value = false; }
}
async function rebase(): Promise<void> {
    const own = generation;
    saving.value = true;
    try {
        const current = await fetchStyle();
        if (own !== generation) return;
        saved.value = current; draft.value.revision = current.revision; conflict.value = false; error.value = '';
        message.value = '已读取最新版本。再次保存会以当前预览替换服务器卡面。';
    } catch { if (own === generation) error.value = '读取最新版本失败，草稿已保留。'; }
    finally { if (own === generation) saving.value = false; }
}
async function save(): Promise<void> {
    if (saving.value || imageBusy.value || conflict.value) return;
    const own = generation;
    saving.value = true; error.value = ''; message.value = '';
    try {
        const response = await services.updateAccountCard(props.ledgerId, props.account.id, { ...draft.value });
        if (own !== generation) return;
        if (!response.data.success) throw new Error();
        saved.value = parseAccountCardStyle(response.data.result); editing.value = false;
        message.value = '卡面已保存到服务器，其他设备可刷新读取。';
    } catch (reason) {
        if (own !== generation) return;
        const status = (reason as { response?: { status?: number } })?.response?.status;
        conflict.value = status === 409;
        error.value = conflict.value ? '保存未完成：服务器卡面已有新版本。' : '保存失败，请检查网络与账户访问权限后重试。当前草稿已保留。';
    } finally { if (own === generation) saving.value = false; }
}
watch(scope, () => { cancel(); saving.value = false; message.value = ''; void load(); }, { immediate: true });
onBeforeUnmount(() => { generation++; imageGeneration++; });
</script>

<style scoped>
.card-designer { container-type: inline-size; }
.designer-entry { display: flex; gap: 10px; justify-content: center; margin-top: 12px; }
.card-designer button { min-height: 44px; padding: 8px 14px; color: var(--ink, #12141a); background: var(--panel, #f7f8fa); border: 1px solid var(--line, #dfe3e9); border-radius: 12px; font: inherit; cursor: pointer; }
.card-designer button:disabled { opacity: .55; cursor: default; }
.card-designer :is(button, input):focus-visible { outline: 2px solid var(--orange, #f05537); outline-offset: 3px; }
.designer-controls { margin-top: 24px; padding: 20px; border: 1px solid var(--line, #dfe3e9); border-radius: 18px; background: var(--surface, #fff); color: var(--ink, #12141a); font-family: var(--ledger-font-body, "Microsoft YaHei UI", "Microsoft YaHei", system-ui, sans-serif); font-size: 14px; line-height: 1.6; }
.designer-controls h3 { font-size: 18px; margin: 0 0 8px; }
.designer-controls p, .designer-controls small { color: var(--muted, #475467); }
.designer-controls small { display: block; font-size: 12px; }
.designer-controls fieldset { min-width: 0; padding: 16px 0; margin: 0; border: 0; border-bottom: 1px solid var(--line, #dfe3e9); }
.designer-controls legend { padding: 12px 0 0; font-weight: 700; }
.designer-controls label { display: grid; grid-template-columns: 1fr auto; margin: 8px 0; gap: 6px; }
.designer-controls input[type=range] { grid-column: 1 / -1; width: 100%; height: 44px; margin: 0; accent-color: var(--ink, #12141a); }
.designer-controls .image-picker { display: block; padding: 10px 12px; border: 1px dashed var(--line, #dfe3e9); border-radius: 12px; background: var(--panel, #f7f8fa); }
.image-picker input { display: block; width: 100%; min-height: 44px; padding-top: 10px; font: inherit; }
.material-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.material-options button { display: flex; gap: 8px; align-items: center; }
.material-options button[aria-pressed=true] { border-color: var(--ink, #12141a); box-shadow: inset 0 0 0 1px var(--ink, #12141a); }
.material-options i { width: 18px; height: 18px; border-radius: 50%; background: #12141a; flex: none; }
.material-options i[data-theme=midnight] { background: #14253d; }
.material-options i[data-theme=mint] { background: #123d36; }
.material-options i[data-theme=copper] { background: #462b20; }
.designer-controls footer { position: sticky; bottom: 12px; padding: 12px 0 0; background: var(--surface, #fff); }
.designer-controls footer > div { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
.designer-controls .designer-save { background: var(--ink, #12141a); color: var(--surface, #fff); }
.designer-error, .designer-controls .designer-error { color: var(--error, #b42318); margin: 12px 0; font-size: 14px; }
.designer-status { margin: 12px 0; color: var(--muted, #475467); font-size: 14px; text-align: center; }
.designer-conflict button { margin-top: 8px; }
@container (min-width: 660px) {
    .card-designer.editing > .card-preview { float: left; width: 48%; position: sticky; top: 110px; }
    .card-designer.editing > .designer-controls { margin-left: 50%; }
}
</style>
