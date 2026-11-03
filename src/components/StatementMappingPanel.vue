<template>
    <details class="statement-mapping" open v-if="rows.length">
        <summary>按原账户与分类分组映射 · {{ groups.length }} 组</summary>
        <p>先核对建议，再一键关联。微信的宽泛消费类型会按商品或备注拆分；没有明确用途的仍需选择。关联只修改预览，不会提交流水。</p>
        <div class="mapping-actions">
            <button type="button" :disabled="locked || !suggestedCount" @click="applySuggestions">关联建议分类（{{ suggestedCount }} 笔）</button>
            <label class="mapping-overwrite"><input type="checkbox" v-model="remember" :disabled="locked" />记住本次确认的分类关联（仅此浏览器）</label>
            <button type="button" :disabled="locked || !Object.keys(remembered).length" @click="forgetMappings">清除已记住的关联</button>
        </div>
        <label class="mapping-overwrite"><input type="checkbox" v-model="overwrite" :disabled="locked" />覆盖该组已映射项</label>
        <div v-for="group in groups" :key="group.key" class="mapping-row">
            <div><b>{{ group.label }}</b><small>{{ group.rows.length }} 笔 · 待补全 {{ missing(group).length }} 笔</small><small v-if="group.suggestion">{{ group.suggestion.reason }}<span v-if="group.suggestion.targetId"> · {{ group.targets.find(target => target.id === group.suggestion?.targetId)?.name }}</span></small></div>
            <label>目标{{ group.field === 'categoryId' ? '分类' : '账户' }}<select :value="targetId(group)" @change="targets[group.key] = ($event.target as HTMLSelectElement).value" :aria-label="`分组映射：${group.label}`" :disabled="locked"><option value="">请选择</option><option v-for="target in group.targets" :key="target.id" :value="target.id">{{ target.name }}</option></select></label>
            <div class="mapping-actions">
                <button type="button" :disabled="locked || !targetId(group) || !eligible(group).length" :aria-label="`应用映射：${group.label}`" @click="apply(group)">应用 {{ eligible(group).length }} 笔</button>
                <button v-if="group.field === 'categoryId'" type="button" :disabled="locked" :aria-label="`新增分类：${group.label}`" @click="openCreate(group)">新增分类</button>
            </div>
            <form class="mapping-create" v-if="creatingKey === group.key" @submit.prevent="createCategory(group)">
                <p>新增分类会保存到系统；取消导入不会删除分类。分类由账本所有者共用。</p>
                <label>所属一级分类<select v-model="newParentId" aria-label="新增分类的一级分类" :disabled="locked"><option value="">请选择</option><option v-for="parent in createParents(group)" :key="parent.id" :value="parent.id">{{ parent.name }}</option></select></label>
                <label>新分类名称<input v-model="newName" aria-label="新分类名称" maxlength="64" :disabled="locked" required /></label>
                <div class="mapping-actions"><button type="submit" :disabled="locked || !newParentId || !newName.trim()">创建并关联 {{ eligible(group).length }} 笔</button><button type="button" :disabled="locked" @click="creatingKey = ''">取消新增</button></div>
                <p v-if="!createParents(group).length">当前没有可管理的一级分类，请先由账本所有者在分类管理中添加。</p>
            </form>
        </div>
        <p v-if="message" :role="failed ? 'alert' : 'status'" aria-live="polite">{{ message }}</p>
    </details>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { TransactionType } from '@/core/transaction.ts';
import type { ImportTransaction } from '@/models/imported_transaction.ts';
import type { Account } from '@/models/account.ts';
import { TransactionCategory } from '@/models/transaction_category.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { getCurrentUserInfo } from '@/lib/userstate.ts';
import { getBasePath } from '@/lib/web.ts';
import { generateRandomUUID } from '@/lib/misc.ts';
import services from '@/lib/services.ts';
import { suggestStatementCategory, statementMappingStorageKey, readStatementCategoryMappings, type CategorySuggestion } from '@/lib/statement_category_mapping.ts';

const props = withDefaults(defineProps<{ rows: ImportTransaction[], accounts: Record<string, Account>, categories: Record<string, TransactionCategory>, disabled?: boolean, ledgerId?: string, fileType?: string }>(), { ledgerId: '0', fileType: '' });
const emit = defineEmits<{ mapped: [], busy: [value: boolean] }>();
const categoriesStore = useTransactionCategoriesStore();
const ledgersStore = useLedgersStore();
type Field = 'sourceAccountId' | 'destinationAccountId' | 'categoryId';
interface MappingGroup { key: string; label: string; field: Field; rows: ImportTransaction[]; targets: { id: string, name: string }[]; suggestion?: CategorySuggestion }
const targets = ref<Record<string, string>>({});
const overwrite = ref(false);
const message = ref('');
const failed = ref(false);
const busy = ref(false);
const locked = computed(() => props.disabled || busy.value || props.ledgerId !== ledgersStore.selectedLedgerId);
const remember = ref(false);
const remembered = ref<Record<string, string>>({});
const creatingKey = ref('');
const newName = ref('');
const newParentId = ref('');
const ownParentIds = ref<string[]>([]);
const createSessionIds = new Map<string, string>();
const storageKey = computed(() => statementMappingStorageKey(window.location.origin + getBasePath(), getCurrentUserInfo()?.username || '', props.ledgerId, props.fileType));
watch(storageKey, key => { remembered.value = readStatementCategoryMappings(localStorage, key); targets.value = {}; creatingKey.value = ''; message.value = ''; }, { immediate: true });
watch(() => props.rows, () => { targets.value = {}; creatingKey.value = ''; message.value = ''; createSessionIds.clear(); });
const groups = computed(() => {
    const result = new Map<string, MappingGroup>();
    for (const row of props.rows) {
        const fields: Field[] = ['sourceAccountId'];
        if (row.type === TransactionType.Transfer) fields.push('destinationAccountId');
        if (row.type !== TransactionType.ModifyBalance) fields.push('categoryId');
        for (const field of fields) {
            const category = field === 'categoryId';
            const name = category ? row.originalCategoryName : field === 'sourceAccountId' ? row.originalSourceAccountName : row.originalDestinationAccountName;
            const currency = category ? '' : field === 'sourceAccountId' ? row.originalSourceAccountCurrency : row.originalDestinationAccountCurrency;
            const categoryType = row.type === TransactionType.Income ? 1 : row.type === TransactionType.Expense ? 2 : 3;
            const suggestion = category ? suggestStatementCategory(row, props.categories, remembered.value) : undefined;
            const key = JSON.stringify([field, category ? suggestion!.key : name || '', currency || '', category ? categoryType : 0]);
            if (!result.has(key)) {
                const options = category ? Object.values(props.categories).filter(c => !c.hidden && c.parentId !== '0' && props.categories[c.parentId] && !props.categories[c.parentId]!.hidden && c.type === categoryType).map(c => ({ id: c.id, name: `${props.categories[c.parentId]!.name} / ${c.name}` }))
                    : Object.values(props.accounts).filter(a => !a.hidden && a.type === 1 && (a.parentId === '0' || (props.accounts[a.parentId] && !props.accounts[a.parentId]!.hidden)) && (!currency || a.currency === currency)).map(a => ({ id: a.id, name: `${a.name} · ${a.currency}` }));
                const typeLabel = categoryType === 1 ? '收入分类' : categoryType === 2 ? '支出分类' : '转账分类';
                const displayName = suggestion && suggestion.name !== name ? `${name || '未标注'} → ${suggestion.name}` : name || '未标注';
                result.set(key, { key, field, label: `${category ? typeLabel : field === 'sourceAccountId' ? '来源账户' : '转入账户'}：${displayName}${currency ? ` · ${currency}` : ''}`, rows: [], targets: options, suggestion });
            }
            result.get(key)!.rows.push(row);
        }
    }
    return [...result.values()];
});
function eligible(group: MappingGroup): ImportTransaction[] {
    return overwrite.value ? group.rows : missing(group);
}
function missing(group: MappingGroup): ImportTransaction[] { return group.rows.filter(row => !group.targets.some(target => target.id === row[group.field])); }
function targetId(group: MappingGroup): string { return targets.value[group.key] ?? group.suggestion?.targetId ?? ''; }
const suggestedCount = computed(() => groups.value.filter(group => group.suggestion?.targetId && targets.value[group.key] === undefined).reduce((count, group) => count + missing(group).length, 0));
function saveMapping(group: MappingGroup, id: string): void {
    if (!remember.value || !group.suggestion) return;
    remembered.value = { ...remembered.value, [group.suggestion.key]: id };
    try { localStorage.setItem(storageKey.value, JSON.stringify(remembered.value)); }
    catch { failed.value = true; message.value = '预览关联已应用，但浏览器未能保存关联规则；下次需重新确认。'; }
}
function forgetMappings(): void {
    if (locked.value) return;
    try { localStorage.removeItem(storageKey.value); remembered.value = {}; message.value = '已清除当前账本、当前账单来源的分类关联。'; failed.value = false; }
    catch { message.value = '无法清除浏览器中的关联，请检查浏览器存储权限。'; failed.value = true; }
}
function applySuggestions(): void {
    if (locked.value) return;
    let count = 0; failed.value = false;
    for (const group of groups.value) {
        if (!group.suggestion?.targetId || targets.value[group.key] !== undefined) continue;
        const rows = missing(group);
        for (const row of rows) row.categoryId = group.suggestion.targetId;
        count += rows.length;
        if (rows.length) saveMapping(group, group.suggestion.targetId);
    }
    emit('mapped');
    if (!failed.value) message.value = `已关联 ${count} 笔建议分类。已有分类保持不变，请核对后确认导入。`;
}
function apply(group: MappingGroup): void {
    if (locked.value) return;
    const id = targetId(group);
    if (!id || !group.targets.some(target => target.id === id)) return;
    const affected = eligible(group);
    for (const row of affected) row[group.field] = id;
    emit('mapped');
    failed.value = false; saveMapping(group, id);
    if (!failed.value) message.value = `已将「${group.label}」的 ${affected.length} 笔映射到所选目标，请继续逐笔核对。`;
}
function createParents(group: MappingGroup): TransactionCategory[] {
    return Object.values(props.categories).filter(c => c.parentId === '0' && !c.hidden && c.type === group.suggestion?.type && ownParentIds.value.includes(c.id));
}
async function openCreate(group: MappingGroup): Promise<void> {
    if (locked.value || !group.suggestion) return;
    setBusy(true); failed.value = false; message.value = '';
    const ledgerId = props.ledgerId;
    try {
        // Category creation API manages the caller's taxonomy. Never create into another owner's cached taxonomy.
        const response = await services.getAllTransactionCategories('0');
        if (!response.data?.success || !response.data.result) throw new Error();
        if (props.ledgerId !== ledgerId || ledgersStore.selectedLedgerId !== ledgerId) return;
        ownParentIds.value = Object.values(response.data.result).flat().map(c => c.id);
        creatingKey.value = group.key; newName.value = group.suggestion.name === '未标注' ? '' : group.suggestion.name; newParentId.value = '';
    } catch { failed.value = true; message.value = '无法加载可管理的一级分类，请重试。'; }
    finally { setBusy(false); }
}
function setBusy(value: boolean): void { busy.value = value; emit('busy', value); }
async function createCategory(group: MappingGroup): Promise<void> {
    if (locked.value || !group.suggestion || !newName.value.trim() || !createParents(group).some(c => c.id === newParentId.value)) return;
    const name = newName.value.trim(); const parentId = newParentId.value;
    const existing = Object.values(props.categories).find(c => c.parentId === parentId && c.name.trim() === name);
    if (existing?.hidden) { failed.value = true; message.value = '同名分类已隐藏，请先在分类管理中恢复。'; return; }
    setBusy(true); failed.value = false;
    const ledgerId = props.ledgerId; const rows = props.rows;
    try {
        const category = TransactionCategory.createNewCategory(group.suggestion.type, parentId); category.name = name;
        const requestKey = JSON.stringify([parentId, name]);
        if (!createSessionIds.has(requestKey)) createSessionIds.set(requestKey, generateRandomUUID());
        const saved = existing || await categoriesStore.saveCategory({ category, isEdit: false, clientSessionId: createSessionIds.get(requestKey)! });
        if (props.ledgerId !== ledgerId || ledgersStore.selectedLedgerId !== ledgerId || props.rows !== rows) { message.value = '分类已保存，但账本或文件已切换，请重新预览。'; return; }
        targets.value[group.key] = saved.id;
        const affected = group.rows.filter(row => overwrite.value || missing(group).includes(row));
        for (const row of affected) row.categoryId = saved.id;
        saveMapping(group, saved.id); emit('mapped'); creatingKey.value = '';
        if (!failed.value) message.value = `已${existing ? '复用' : '创建'}分类「${name}」并关联 ${affected.length} 笔；流水尚未入账。`;
    } catch { failed.value = true; message.value = '新增失败，名称和所属分类已保留，可重试。'; }
    finally { setBusy(false); }
}
</script>

<style scoped>
.statement-mapping { margin: 0 0 20px; padding: 16px; border: 1px solid var(--line, #dfe3e9); border-radius: 14px; color: var(--ink, #12141a); background: var(--surface, #fff); }
.statement-mapping summary { min-height: 44px; cursor: pointer; font-size: 16px; font-weight: 600; }
.statement-mapping p { margin: 8px 0 16px; font-size: 14px; line-height: 1.6; }
.statement-mapping .mapping-overwrite { display: flex; align-items: center; gap: 8px; min-height: 44px; }
.mapping-overwrite input { width: 18px; height: 18px; min-height: 18px; margin: 0; }
.mapping-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(180px, 1fr) auto; gap: 16px; align-items: center; padding: 12px 0; border-top: 1px solid var(--line, #dfe3e9); overflow-wrap: anywhere; }
.mapping-row small { display: block; font-size: 12px; margin-top: 4px; }
.mapping-row label { font-size: 14px; }
.mapping-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.mapping-create { grid-column: 1 / -1; display: grid; gap: 12px; padding: 12px; background: var(--surface-muted, #f5f6f8); border-radius: 12px; }
.mapping-create input { box-sizing: border-box; display: block; width: 100%; min-height: 44px; border: 1px solid var(--line, #dfe3e9); border-radius: 12px; padding: 8px 12px; color: inherit; background: var(--surface, #fff); font: inherit; }
.statement-mapping select, .statement-mapping button { box-sizing: border-box; min-height: 44px; padding: 8px 12px; border: 1px solid var(--line, #dfe3e9); border-radius: 12px; background: var(--surface, #fff); color: inherit; font: inherit; }
.statement-mapping select { display: block; width: 100%; margin-top: 4px; }
.statement-mapping button { width: auto; cursor: pointer; }
.statement-mapping button:disabled { opacity: .5; cursor: default; }
.statement-mapping :is(button, select, input, summary):focus-visible { outline: 2px solid var(--orange, #f05537); outline-offset: 3px; }
@media (max-width: 768px) { .mapping-row { grid-template-columns: minmax(0, 1fr); gap: 8px; } .statement-mapping button { justify-self: start; } }
</style>
