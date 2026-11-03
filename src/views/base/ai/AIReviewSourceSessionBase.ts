import { computed, onUnmounted, ref, watch } from 'vue';
import { useAIReviewLedgerStore } from '@/stores/aiReviewLedger.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useAccountsStore } from '@/stores/account.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useUserStore } from '@/stores/user.ts';
import { useI18n } from '@/locales/helpers.ts';
import { getTransactionStateIdentity } from '@/lib/userstate.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import { saveAIReviewSourceImage, readAIReviewSourceImage, removeAIReviewSourceImage } from '@/lib/ai_source_image.ts';
import { cropReceiptForRecognition } from '@/lib/receipt_crop.ts';
import services from '@/lib/services.ts';
import { assertReceiptCorners, fullReceiptCorners, type AIReviewSourceDraft, type AIReviewSourceInput, type ReceiptCorners } from '@/models/ai_review_source.ts';
import { assertAIReviewCreate, type LedgerAIReviewCreateRequest, type PendingAIReviewCreate } from '@/models/ai_review_ledger.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';

/** Shared durable AI source session used by the desktop dialog and the mobile
 * source page. Views own only their markup; recovery, persistence, cancellation
 * and the queue hand-off live here so both platforms cannot drift apart. */
export default function useAIReviewSourceSession(options: {
    complete: (value: { ledgerId: string; id: string; deleted: boolean }) => void;
    closed: () => void;
}) {
    const store = useAIReviewLedgerStore(), ledgers = useLedgersStore(), accounts = useAccountsStore(), settings = useSettingsStore();
    const categories = useTransactionCategoriesStore();
    const user = useUserStore();
    const { formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
    const visible = ref(false), ledgerId = ref('0'), draft = ref<AIReviewSourceDraft | null>(null), recovery = ref<AIReviewSourceDraft | null>(null);
    const pending = ref<PendingAIReviewCreate | null>(null), previewRequest = ref<LedgerAIReviewCreateRequest | null>(null);
    const busy = ref(false), recognizing = ref(false), saving = ref(false), ready = ref(false), error = ref(''), privacyRequested = ref(false), discardRequested = ref(false);
    const imageUrl = ref(''), cropUrl = ref('');
    const inputError = ref('');
    const cornerNames = ['左上角', '右上角', '右下角', '左下角'];
    let identity: string | null = null, generation = 0, recognitionSerial = 0, saveSerial = 0, revision: string | null = null, cancelUuid = '';
    let jobs: Promise<void> = Promise.resolve(), sourceFile: File | null = null;
    let expectedRecoveryTarget: Extract<LedgerPendingTarget, { kind: 'ai-source' }> | undefined;
    const current = () => visible.value && identity === getTransactionStateIdentity() && ledgerId.value === ledgers.selectedLedgerId;
    const canWrite = computed(() => ready.value && store.canWrite && current());
    const locked = computed(() => !canWrite.value || busy.value || !!error.value || !!pending.value || !!recovery.value || !!previewRequest.value);
    const ledgerName = computed(() => ledgerId.value === ledgers.selectedLedgerId ? ledgers.selectedLedger.name : '原账本');
    const status = computed(() => pending.value?.receipt ? '待复核已保存，只读取原结果' : pending.value ? '保存结果待确认' : recovery.value ? '可恢复来源草稿' : saving.value ? '正在保存本机来源' : recognizing.value ? '正在识别，可取消' : revision ? '来源已保存到本机' : '开始编辑后保存到本机');
    const canRecognize = computed(() => !inputError.value && !!draft.value && (draft.value.input.sourceType === 1 ? !!draft.value.input.sourceText.trim() : !!sourceFile));
    const recognitionSummary = computed(() => {
        const data = draft.value?.recognizedData; if (!data) return '';
        if (!settings.appSettings.showAmountInHomePage) return '金额已隐藏；账户、分类和日期在入账预览中逐项核对';
        const side = (id: string | undefined, amount: number | undefined) => {
            const account = accounts.accountListLedgerId === ledgerId.value && id ? accounts.allAccountsMap[id] : undefined;
            return account && amount !== undefined ? `${account.name} · ${formatAmountToLocalizedNumeralsWithCurrency(amount, account.currency)}` : '账户及币种待选择';
        };
        const category = categories.loadedLedgerId === ledgerId.value && data.categoryId ? categories.allTransactionCategoriesMap[data.categoryId] : undefined;
        const date = data.time ? new Date(data.time * 1000) : null;
        const dateLabel = date && !Number.isNaN(date.getTime()) ? `${date.toISOString().slice(0, 19).replace('T', ' ')} UTC` : '日期待选择';
        return `${data.type === 4 ? '转账' : data.type === 2 ? '收入' : '支出（负金额为退款）'} · ${side(data.sourceAccountId, data.sourceAmount)}${data.type === 4 ? ` → ${side(data.destinationAccountId, data.destinationAmount)}` : ''} · ${category?.name || '分类待选择'} · ${dateLabel}，均需复核`;
    });
    const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
    function fail(reason: unknown) { error.value = reason instanceof Error ? reason.message : '操作未完成，原记录已保留，请重试'; }
    function replaceUrl(target: typeof imageUrl, file: File | null) { if (target.value) URL.revokeObjectURL(target.value); target.value = file ? URL.createObjectURL(file) : ''; }
    function empty(type: 1 | 2 = 1, text = ''): AIReviewSourceDraft { return { version: 1, identity: identity!, revision: generateRandomUUID(), input: { ledgerId: ledgerId.value, sourceType: type, sourceText: text, corners: fullReceiptCorners(), rotation: 0 }, failureReason: '' }; }
    function assertOriginalSource(originalPending: PendingAIReviewCreate | null, originalDraft: AIReviewSourceDraft | null): void {
        const target = expectedRecoveryTarget;
        if (!target) return;
        const matches = target.ledgerId === ledgerId.value && (target.recovery === 'pending'
            ? originalPending?.request.clientSessionId === target.clientSessionId && (originalPending.draftRevision ?? null) === target.draftRevision
            : !originalPending && originalDraft?.revision === target.draftRevision);
        if (!matches) { pending.value = null; recovery.value = null; draft.value = null; ready.value = false; throw new Error('原来源记录已变化，请返回原账本刷新待处理；不会打开替代来源'); }
    }
    async function open(id: string, text = '', type: 1 | 2 = 1, recoveryTarget?: Extract<LedgerPendingTarget, { kind: 'ai-source' }>) {
        expectedRecoveryTarget = recoveryTarget;
        const run = ++generation; cancelRecognition(); ledgerId.value = id; identity = getTransactionStateIdentity(); visible.value = true;
        revision = null; saveSerial++; saving.value = false; jobs = Promise.resolve(); draft.value = empty(type, text); sourceFile = null; replaceUrl(imageUrl, null); replaceUrl(cropUrl, null);
        await initialize();
        if (current() && run === generation && ready.value && !recovery.value && !pending.value) { draft.value = empty(type, text); if (text) await persist(); }
    }
    async function initialize() {
        const run = generation; busy.value = true; ready.value = false; error.value = ''; inputError.value = ''; privacyRequested.value = false; previewRequest.value = null; discardRequested.value = false;
        try {
            await jobs; if (!current() || run !== generation) return;
            pending.value = store.readPendingCreate(ledgerId.value); recovery.value = store.readSourceDraft(ledgerId.value);
            assertOriginalSource(pending.value, recovery.value);
            if (!pending.value && !recovery.value) { revision = null; draft.value = empty(); sourceFile = null; replaceUrl(imageUrl, null); replaceUrl(cropUrl, null); }
            await store.verifyCapabilities(ledgerId.value, true); if (!current() || run !== generation) return;
            // The recognition summary and preview resolve this ledger's accounts and
            // categories, so load them here instead of relying on the calling page.
            const ignoreUnchanged = (reason: unknown) => {
                const failure = reason as { isUpToDate?: boolean; stale?: boolean };
                if (!failure?.isUpToDate && !failure?.stale) throw reason;
            };
            await Promise.all([
                accounts.loadAllAccounts({ force: false, ledgerId: ledgerId.value }).catch(ignoreUnchanged),
                categories.loadAllCategories({ force: false, ledgerId: ledgerId.value }).catch(ignoreUnchanged)
            ]);
            if (!current() || run !== generation) return;
            assertOriginalSource(store.readPendingCreate(ledgerId.value), store.readSourceDraft(ledgerId.value));
            ready.value = true;
        } catch (reason) { if (current() && run === generation) fail(reason); }
        finally { if (run === generation) busy.value = false; }
    }
    async function persist() {
        if (!draft.value || !current()) return;
        const frozen = clone({ ...draft.value, revision: generateRandomUUID() }), run = generation, serial = ++saveSerial;
        saving.value = true;
        jobs = jobs.then(async () => {
            if (!current() || run !== generation || error.value) return;
            try { await store.saveSourceDraft(frozen, revision); if (current() && run === generation) { revision = frozen.revision; if (draft.value) draft.value = { ...draft.value, revision }; } }
            catch (reason) { if (current() && run === generation) fail(reason); }
        });
        await jobs; if (run === generation && serial === saveSerial) saving.value = false;
    }
    function change(input: AIReviewSourceInput) {
        if (locked.value || !draft.value) return;
        privacyRequested.value = false; replaceUrl(cropUrl, null); inputError.value = '';
        draft.value = { ...draft.value, input, recognizedData: undefined, failureReason: '' };
        if (new TextEncoder().encode(input.sourceText).length > 8000) { inputError.value = '文字超过 8000 UTF-8 字节，当前超长输入尚未保存，请缩短后继续'; return; }
        void persist();
    }
    function changeText(text: string) { if (draft.value) change({ ...draft.value.input, sourceText: text }); }
    async function setKind(type: 1 | 2) {
        if (!draft.value || locked.value || draft.value.input.sourceType === type) return;
        const old = draft.value.input.image, run = generation, id = ledgerId.value; sourceFile = null; replaceUrl(imageUrl, null);
        change({ ...draft.value.input, sourceType: type, image: undefined, corners: fullReceiptCorners(), rotation: 0 }); await jobs;
        if (run === generation && current() && !error.value && old) await removeAIReviewSourceImage(id, old).catch(() => {});
    }
    async function selectImage(event: Event) {
        const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''; await selectImageFile(file);
    }
    /** Shared by the desktop file input and mobile share/gallery entries. */
    async function selectImageFile(file: File | null | undefined) {
        if (!file || locked.value || !draft.value) return;
        const run = generation, id = ledgerId.value, old = draft.value.input.image; busy.value = true;
        try {
            const image = await saveAIReviewSourceImage(id, file);
            if (!current() || run !== generation) { await removeAIReviewSourceImage(id, image).catch(() => {}); return; }
            draft.value = { ...draft.value, input: { ...draft.value.input, image, sourceText: draft.value.input.sourceText.trim() ? draft.value.input.sourceText : `票据：${file.name}`, corners: fullReceiptCorners(), rotation: 0 }, recognizedData: undefined, failureReason: '' };
            await persist();
            if (!current() || run !== generation) return;
            if (!error.value) { sourceFile = file; replaceUrl(imageUrl, file); replaceUrl(cropUrl, null); if (old) await removeAIReviewSourceImage(id, old).catch(() => {}); }
            else await removeAIReviewSourceImage(id, image).catch(() => {});
        } catch (reason) { if (current() && run === generation) fail(reason); }
        finally { if (run === generation) busy.value = false; }
    }
    function applyCorners(corners: ReceiptCorners) { try { assertReceiptCorners(corners); if (draft.value) change({ ...draft.value.input, corners }); } catch (reason) { inputError.value = reason instanceof Error ? reason.message : '裁剪边界无效'; } }
    function setCorner(index: number, axis: 'x' | 'y', raw: string) {
        if (!draft.value) return;
        const value = raw.trim() ? Number(raw) / 100 : NaN;
        applyCorners(draft.value.input.corners.map((p, i) => i === index ? { ...p, [axis]: value } : p) as unknown as ReceiptCorners);
    }
    function transformCrop(action: 'up' | 'down' | 'left' | 'right' | 'expand' | 'shrink') {
        if (!draft.value) return;
        const points = draft.value.input.corners, cx = points.reduce((sum, p) => sum + p.x, 0) / 4, cy = points.reduce((sum, p) => sum + p.y, 0) / 4;
        const factor = action === 'expand' ? 1.1 : action === 'shrink' ? 0.9 : 1;
        applyCorners(points.map(p => ({ x: Math.max(0, Math.min(1, cx + (p.x - cx) * factor + (action === 'left' ? -0.02 : action === 'right' ? 0.02 : 0))),
            y: Math.max(0, Math.min(1, cy + (p.y - cy) * factor + (action === 'up' ? -0.02 : action === 'down' ? 0.02 : 0))) })) as unknown as ReceiptCorners);
    }
    function rotate() { if (draft.value) change({ ...draft.value.input, rotation: ((draft.value.input.rotation + 90) % 360) as AIReviewSourceInput['rotation'] }); }
    function resetCrop() { if (draft.value) change({ ...draft.value.input, corners: fullReceiptCorners(), rotation: 0 }); }
    async function previewCrop() {
        if (locked.value || !draft.value || !sourceFile) return;
        const run = generation; busy.value = true;
        try { const result = await cropReceiptForRecognition(sourceFile, draft.value.input.corners, draft.value.input.rotation); if (current() && run === generation) replaceUrl(cropUrl, result); }
        catch (reason) { if (current() && run === generation) fail(reason); } finally { if (run === generation) busy.value = false; }
    }
    async function resume() {
        if (!recovery.value || !canWrite.value) return;
        const saved = clone(recovery.value), run = generation; busy.value = true; error.value = '';
        try {
            const file = saved.input.image ? await readAIReviewSourceImage(ledgerId.value, saved.input.image) : null;
            if (!current() || run !== generation) return;
            assertOriginalSource(store.readPendingCreate(ledgerId.value), store.readSourceDraft(ledgerId.value));
            expectedRecoveryTarget = undefined;
            draft.value = saved; revision = saved.revision; recovery.value = null; sourceFile = file; replaceUrl(imageUrl, file);
        } catch (reason) { if (current() && run === generation) fail(reason); } finally { if (run === generation) busy.value = false; }
    }
    async function discard() {
        if (!recovery.value || !canWrite.value) return;
        const run = generation; busy.value = true;
        try { await store.discardSourceDraft(ledgerId.value, recovery.value.revision); if (current() && run === generation) { expectedRecoveryTarget = undefined; await initialize(); } }
        catch (reason) { if (current() && run === generation) fail(reason); } finally { if (run === generation) busy.value = false; }
    }
    function cancelRecognition() { recognitionSerial++; if (cancelUuid) services.cancelRequest(cancelUuid); cancelUuid = ''; recognizing.value = false; busy.value = false; privacyRequested.value = false; }
    async function recognize() {
        if (locked.value || !privacyRequested.value || !draft.value || !canRecognize.value) return;
        const run = generation;
        await jobs; if (error.value || !current() || run !== generation || !privacyRequested.value || !draft.value) return;
        const serial = ++recognitionSerial, frozen = clone(draft.value); busy.value = true; recognizing.value = true; privacyRequested.value = false;
        try {
            cancelUuid = generateRandomUUID();
            const cropped = frozen.input.sourceType === 2 ? await cropReceiptForRecognition(sourceFile!, frozen.input.corners, frozen.input.rotation) : null;
            if (!current() || run !== generation || serial !== recognitionSerial) return;
            const result = cropped ? await store.recognizeImage(ledgerId.value, cropped, cancelUuid) : await store.recognizeText(ledgerId.value, frozen.input.sourceText);
            if (!current() || run !== generation || serial !== recognitionSerial) return;
            draft.value = { ...frozen, recognizedData: result, failureReason: '' }; await persist();
        } catch (reason) {
            if (current() && run === generation && serial === recognitionSerial) { draft.value = { ...frozen, recognizedData: undefined, failureReason: reason instanceof Error ? reason.message : '识别未完成，可重试或改为手动复核' }; await persist(); }
        } finally { if (run === generation && serial === recognitionSerial) { recognizing.value = false; busy.value = false; cancelUuid = ''; } }
    }
    async function previewQueue(manual: boolean) {
        if (locked.value || inputError.value || !draft.value) return;
        const run = generation; await jobs; if (error.value || !current() || run !== generation || !draft.value) return;
        if (manual) { draft.value = { ...draft.value, recognizedData: undefined, failureReason: '用户选择手动复核，未进行自动入账' }; await persist(); }
        if (error.value || !current() || run !== generation || !draft.value || !revision) return;
        try {
            const request: LedgerAIReviewCreateRequest = { ledgerId: ledgerId.value, clientSessionId: generateRandomUUID(), sourceType: draft.value.input.sourceType,
                sourceText: draft.value.input.sourceText, recognizedData: draft.value.recognizedData, failureReason: draft.value.failureReason };
            assertAIReviewCreate(request, ledgerId.value); previewRequest.value = request;
        } catch (reason) { fail(reason); }
    }
    async function readReceipt(value: PendingAIReviewCreate, run: number) {
        if (!value.receipt) return;
        const id = value.request.ledgerId, result = await store.get(id, value.receipt.id);
        if (!current() || run !== generation) return;
        await store.acknowledgeCreate(id, value.request.clientSessionId, result.id);
        if (!current() || run !== generation) return;
        visible.value = false; replaceUrl(imageUrl, null); replaceUrl(cropUrl, null);
        options.complete({ ledgerId: id, id: result.id, deleted: result.deleted }); options.closed();
    }
    async function createQueue() {
        if (!previewRequest.value || busy.value || !canWrite.value) return;
        const run = generation, request = clone(previewRequest.value); busy.value = true; error.value = '';
        try { await jobs; if (error.value || !current() || run !== generation) return; const result = await store.create(request, revision); if (current() && run === generation) { pending.value = result; await readReceipt(result, run); } }
        catch (reason) { if (current() && run === generation) { pending.value = store.readPendingCreate(ledgerId.value); fail(reason); } }
        finally { if (run === generation) busy.value = false; }
    }
    async function recoverPending() {
        if (!pending.value || busy.value || !current()) return;
        const run = generation; busy.value = true; error.value = '';
        try { const result = await store.retryCreate(ledgerId.value, pending.value.request.clientSessionId); if (current() && run === generation) { pending.value = result; await readReceipt(result, run); } }
        catch (reason) { if (current() && run === generation) { pending.value = store.readPendingCreate(ledgerId.value); fail(reason); } } finally { if (run === generation) busy.value = false; }
    }
    async function close() { const run = generation; cancelRecognition(); await jobs; if (run !== generation) return; visible.value = false; generation++; replaceUrl(imageUrl, null); replaceUrl(cropUrl, null); sourceFile = null; options.closed(); }
    function storageChanged(event: StorageEvent) {
        if (!current() || (event.key && !event.key.includes('ai-review-'))) return;
        try {
            const otherPending = store.readPendingCreate(ledgerId.value), otherDraft = store.readSourceDraft(ledgerId.value);
            assertOriginalSource(otherPending, otherDraft);
            if (otherPending || (otherDraft?.revision ?? null) !== revision) { cancelRecognition(); pending.value = otherPending; recovery.value = otherDraft; previewRequest.value = null; ready.value = false; error.value = '另一页面已更新或处理来源，请重新读取本机状态；不会替代保存。'; }
        } catch (reason) { fail(reason); }
    }
    window.addEventListener('storage', storageChanged);
    watch(() => ledgers.selectedLedgerId, () => { if (visible.value && ledgerId.value !== ledgers.selectedLedgerId) void close(); });
    watch(() => user.currentUserBasicInfo?.username, () => { if (visible.value) void close(); }, { flush: 'sync' });
    onUnmounted(() => { generation++; cancelRecognition(); window.removeEventListener('storage', storageChanged); replaceUrl(imageUrl, null); replaceUrl(cropUrl, null); });

    return {
        // state
        visible,
        draft,
        recovery,
        pending,
        previewRequest,
        busy,
        recognizing,
        saving,
        ready,
        error,
        privacyRequested,
        discardRequested,
        imageUrl,
        cropUrl,
        inputError,
        cornerNames,
        // computed
        current,
        canWrite,
        locked,
        ledgerName,
        status,
        canRecognize,
        recognitionSummary,
        // actions
        open,
        initialize,
        changeText,
        setKind,
        selectImage,
        selectImageFile,
        setCorner,
        transformCrop,
        rotate,
        resetCrop,
        previewCrop,
        resume,
        discard,
        recognize,
        cancelRecognition,
        previewQueue,
        createQueue,
        recoverPending,
        close
    };
}
