<template>
    <details class="statement-mapping" open v-if="rows.length">
        <summary>按原账户与分类分组映射 · {{ groups.length }} 组</summary>
        <p>先选择目标，再点击应用。默认只补全未映射项，不改变勾选状态，也不会提交流水。</p>
        <label class="mapping-overwrite"><input type="checkbox" v-model="overwrite" :disabled="disabled" />覆盖该组已映射项</label>
        <div v-for="group in groups" :key="group.key" class="mapping-row">
            <div><b>{{ group.label }}</b><small>{{ group.rows.length }} 笔 · 可应用 {{ eligible(group).length }} 笔</small></div>
            <label>目标{{ group.field === 'categoryId' ? '分类' : '账户' }}<select v-model="targets[group.key]" :aria-label="`分组映射：${group.label}`" :disabled="disabled"><option value="">请选择</option><option v-for="target in group.targets" :key="target.id" :value="target.id">{{ target.name }}</option></select></label>
            <button type="button" :disabled="disabled || !targets[group.key] || !eligible(group).length" :aria-label="`应用映射：${group.label}`" @click="apply(group)">应用 {{ eligible(group).length }} 笔</button>
        </div>
        <p v-if="message" role="status" aria-live="polite">{{ message }}</p>
    </details>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { TransactionType } from '@/core/transaction.ts';
import type { ImportTransaction } from '@/models/imported_transaction.ts';
import type { Account } from '@/models/account.ts';
import type { TransactionCategory } from '@/models/transaction_category.ts';

const props = defineProps<{ rows: ImportTransaction[], accounts: Record<string, Account>, categories: Record<string, TransactionCategory>, disabled?: boolean }>();
const emit = defineEmits<{ mapped: [] }>();
type Field = 'sourceAccountId' | 'destinationAccountId' | 'categoryId';
interface MappingGroup { key: string; label: string; field: Field; rows: ImportTransaction[]; targets: { id: string, name: string }[] }
const targets = ref<Record<string, string>>({});
const overwrite = ref(false);
const message = ref('');
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
            const key = JSON.stringify([field, name || '', currency || '', category ? categoryType : 0]);
            if (!result.has(key)) {
                const options = category ? Object.values(props.categories).filter(c => !c.hidden && c.parentId !== '0' && props.categories[c.parentId] && !props.categories[c.parentId]!.hidden && c.type === categoryType).map(c => ({ id: c.id, name: `${props.categories[c.parentId]!.name} / ${c.name}` }))
                    : Object.values(props.accounts).filter(a => !a.hidden && a.type === 1 && (a.parentId === '0' || (props.accounts[a.parentId] && !props.accounts[a.parentId]!.hidden)) && (!currency || a.currency === currency)).map(a => ({ id: a.id, name: `${a.name} · ${a.currency}` }));
                const typeLabel = categoryType === 1 ? '收入分类' : categoryType === 2 ? '支出分类' : '转账分类';
                result.set(key, { key, field, label: `${category ? typeLabel : field === 'sourceAccountId' ? '来源账户' : '转入账户'}：${name || '未标注'}${currency ? ` · ${currency}` : ''}`, rows: [], targets: options });
            }
            result.get(key)!.rows.push(row);
        }
    }
    return [...result.values()];
});
function eligible(group: MappingGroup): ImportTransaction[] {
    return group.rows.filter(row => overwrite.value || !group.targets.some(target => target.id === row[group.field]));
}
function apply(group: MappingGroup): void {
    if (props.disabled) return;
    const id = targets.value[group.key];
    if (!id || !group.targets.some(target => target.id === id)) return;
    const affected = eligible(group);
    for (const row of affected) row[group.field] = id;
    emit('mapped');
    message.value = `已将「${group.label}」的 ${affected.length} 笔映射到所选目标，请继续逐笔核对。`;
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
.statement-mapping select, .statement-mapping button { box-sizing: border-box; min-height: 44px; padding: 8px 12px; border: 1px solid var(--line, #dfe3e9); border-radius: 12px; background: var(--surface, #fff); color: inherit; font: inherit; }
.statement-mapping select { display: block; width: 100%; margin-top: 4px; }
.statement-mapping button { width: auto; cursor: pointer; }
.statement-mapping button:disabled { opacity: .5; cursor: default; }
.statement-mapping :is(button, select, input, summary):focus-visible { outline: 2px solid var(--orange, #f05537); outline-offset: 3px; }
@media (max-width: 768px) { .mapping-row { grid-template-columns: minmax(0, 1fr); gap: 8px; } .statement-mapping button { justify-self: start; } }
</style>
