import { ref, computed } from 'vue';
import { defineStore } from 'pinia';
import type { BeforeResolveFunction } from '@/core/base.ts';
import { TransactionType } from '@/core/transaction.ts';
import { TransactionTemplate } from '@/models/transaction_template.ts';
import { isEquals } from '@/lib/common.ts';
import services from '@/lib/services.ts';
import { periodicError, usePeriodicScope } from './periodicScope.ts';

export const useTransactionTemplatesStore = defineStore('transactionTemplates', () => {
    const allTransactionTemplates = ref<Record<number, TransactionTemplate[]>>({});
    const allTransactionTemplatesMap = ref<Record<number, Record<string, TransactionTemplate>>>({});
    const transactionTemplateListStatesInvalid = ref<Record<number, boolean>>({});
    const versions = new Map<number, number>();
    const scope = usePeriodicScope(() => {
        allTransactionTemplates.value = {};
        allTransactionTemplatesMap.value = {};
        transactionTemplateListStatesInvalid.value = {};
        versions.clear();
    });
    const allVisibleTemplates = computed(() => Object.fromEntries(Object.entries(allTransactionTemplates.value).map(([type, rows]) => [type, rows.filter(row => !row.hidden)])) as Record<number, TransactionTemplate[]>);
    const allAvailableTemplatesCount = computed(() => Object.fromEntries(Object.entries(allTransactionTemplates.value).map(([type, rows]) => [type, rows.length])) as Record<number, number>);
    const allVisibleTemplatesCount = computed(() => Object.fromEntries(Object.entries(allVisibleTemplates.value).map(([type, rows]) => [type, rows.length])) as Record<number, number>);

    function setRows(type: number, rows: TransactionTemplate[]): void {
        allTransactionTemplates.value[type] = rows;
        allTransactionTemplatesMap.value[type] = Object.fromEntries(rows.map(row => [row.id, row]));
    }
    function invalidate(type: number): void {
        versions.set(type, (versions.get(type) ?? 0) + 1);
        transactionTemplateListStatesInvalid.value[type] = true;
    }
    function upsert(template: TransactionTemplate): void {
        invalidate(template.templateType);
        const rows = [...(allTransactionTemplates.value[template.templateType] ?? [])];
        const index = rows.findIndex(row => row.id === template.id);
        if (index < 0) rows.push(template); else rows[index] = template;
        setRows(template.templateType, rows);
    }
    async function loadAllTemplates({ templateType, force, ledgerId = '0' }: { templateType: number; force?: boolean; ledgerId?: string }): Promise<TransactionTemplate[]> {
        const context = scope.capture(ledgerId);
        const version = (versions.get(templateType) ?? 0) + 1;
        versions.set(templateType, version);
        try {
            await scope.authorize(context, 'read');
            if (!force && transactionTemplateListStatesInvalid.value[templateType] === false) return allTransactionTemplates.value[templateType] ?? [];
            const response = await services.getAllTransactionTemplates({ templateType, ledgerId, expectedUsername: context.username });
            scope.assertCurrent(context);
            if (versions.get(templateType) !== version) throw Object.assign(new Error('模板读取已被后续请求替代'), { stale: true });
            if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('模板响应不完整，请重试');
            const rows = TransactionTemplate.ofMultiTemplates(response.data.result, ledgerId);
            if (rows.some(row => row.templateType !== templateType) || new Set(rows.map(row => row.id)).size !== rows.length) throw new Error('模板响应类型错误或重复，请重试');
            const unchanged = force && isEquals(allTransactionTemplates.value[templateType], rows);
            setRows(templateType, rows);
            transactionTemplateListStatesInvalid.value[templateType] = false;
            if (unchanged) throw { message: 'Template list is up to date', isUpToDate: true };
            return rows;
        } catch (error) {
            if ((error as { isUpToDate?: boolean })?.isUpToDate) throw error;
            return periodicError(error, '无法读取当前账本模板');
        }
    }
    async function getTemplate({ templateId, ledgerId = '0' }: { templateId: string; ledgerId?: string }): Promise<TransactionTemplate> {
        const context = scope.capture(ledgerId);
        try {
            await scope.authorize(context, 'read');
            const response = await services.getTransactionTemplate({ id: templateId, ledgerId, expectedUsername: context.username });
            scope.assertCurrent(context);
            if (!response.data?.success || !response.data.result) throw new Error('模板响应不完整');
            const template = TransactionTemplate.ofTemplate(response.data.result, ledgerId);
            if (template.id !== templateId) throw new Error('模板标识不匹配');
            return template;
        } catch (error) { return periodicError(error, '无法读取当前账本模板'); }
    }
    async function saveTemplateContent({ template, isEdit, clientSessionId }: { template: TransactionTemplate; isEdit: boolean; clientSessionId: string }): Promise<TransactionTemplate> {
        const context = scope.capture(template.ledgerId);
        const request = isEdit ? template.toTemplateModifyRequest() : template.toTemplateCreateRequest(clientSessionId);
        const originalId = template.id;
        const originalType = template.templateType;
        if (![TransactionType.Expense, TransactionType.Income, TransactionType.Transfer].includes(template.type)) throw new Error('模板交易类型无效');
        try {
            await scope.authorize(context, 'manage');
            const response = isEdit ? await services.modifyTransactionTemplate(request as ReturnType<TransactionTemplate['toTemplateModifyRequest']>, context.username)
                : await services.addTransactionTemplate(request as ReturnType<TransactionTemplate['toTemplateCreateRequest']>, context.username);
            scope.assertCurrent(context);
            if (!response.data?.success || !response.data.result) throw new Error('模板保存回包不完整，请读取原模板核对结果');
            const saved = TransactionTemplate.ofTemplate(response.data.result, context.ledgerId);
            if (saved.templateType !== originalType || (isEdit && saved.id !== originalId)) throw new Error('模板保存回包不匹配');
            upsert(saved);
            return saved;
        } catch (error) { return periodicError(error, '无法保存当前账本模板'); }
    }
    async function changeTemplateDisplayOrder({ templateType, templateId, from, to }: { templateType: number; templateId: string; from: number; to: number }): Promise<void> {
        const context = scope.capture(scope.loadedLedgerId.value);
        await scope.authorize(context, 'manage');
        const rows = [...(allTransactionTemplates.value[templateType] ?? [])];
        if (!Number.isInteger(from) || !Number.isInteger(to) || rows[from]?.id !== templateId || !rows[to]) throw new Error('模板排序目标无效');
        rows.splice(to, 0, rows.splice(from, 1)[0]!);
        invalidate(templateType);
        setRows(templateType, rows);
    }
    async function updateTemplateDisplayOrders({ templateType }: { templateType: number }): Promise<boolean> {
        const context = scope.capture(scope.loadedLedgerId.value);
        const newDisplayOrders = (allTransactionTemplates.value[templateType] ?? []).map((row, index) => ({ id: row.id, displayOrder: index + 1 }));
        try {
            await scope.authorize(context, 'manage');
            const response = await services.moveTransactionTemplate({ ledgerId: context.ledgerId, newDisplayOrders }, context.username);
            scope.assertCurrent(context);
            if (!response.data?.success || response.data.result !== true) throw new Error('模板排序结果尚未确认');
            invalidate(templateType);
            await loadAllTemplates({ templateType, ledgerId: context.ledgerId });
            return true;
        } catch (error) { return periodicError(error, '无法保存当前账本模板排序'); }
    }
    async function hideTemplate({ template, hidden }: { template: TransactionTemplate; hidden: boolean }): Promise<boolean> {
        const context = scope.capture(template.ledgerId);
        const id = template.id;
        try {
            await scope.authorize(context, 'manage');
            const response = await services.hideTransactionTemplate({ ledgerId: context.ledgerId, id, hidden }, context.username);
            scope.assertCurrent(context);
            if (!response.data?.success || response.data.result !== true) throw new Error('模板隐藏结果尚未确认');
            const current = allTransactionTemplatesMap.value[template.templateType]?.[id];
            if (current) current.hidden = hidden;
            invalidate(template.templateType);
            return true;
        } catch (error) { return periodicError(error, '无法修改当前账本模板可见性'); }
    }
    async function deleteTemplate({ template, beforeResolve }: { template: TransactionTemplate; beforeResolve?: BeforeResolveFunction }): Promise<boolean> {
        const context = scope.capture(template.ledgerId);
        const id = template.id;
        const type = template.templateType;
        try {
            await scope.authorize(context, 'manage');
            const response = await services.deleteTransactionTemplate({ ledgerId: context.ledgerId, id }, context.username);
            scope.assertCurrent(context);
            if (!response.data?.success || response.data.result !== true) throw new Error('模板删除结果尚未确认');
            invalidate(type);
            const remove = () => {
                try { scope.assertCurrent(context); } catch { return; }
                setRows(type, (allTransactionTemplates.value[type] ?? []).filter(row => row.id !== id));
            };
            if (beforeResolve) beforeResolve(remove); else remove();
            return true;
        } catch (error) { return periodicError(error, '无法删除当前账本模板'); }
    }
    return {
        allTransactionTemplates, allTransactionTemplatesMap, transactionTemplateListStatesInvalid,
        allVisibleTemplates, allAvailableTemplatesCount, allVisibleTemplatesCount,
        loadedLedgerId: scope.loadedLedgerId, canManage: scope.canManage, selectLedger: scope.selectLedger,
        updateTransactionTemplateListInvalidState: (type: number, invalid: boolean) => { transactionTemplateListStatesInvalid.value[type] = invalid; },
        resetTransactionTemplates: () => scope.reset(), loadAllTemplates, getTemplate, saveTemplateContent,
        changeTemplateDisplayOrder, updateTemplateDisplayOrders, hideTemplate, deleteTemplate,
    };
});
