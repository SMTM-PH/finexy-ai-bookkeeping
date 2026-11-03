import { Transaction } from '@/models/transaction.ts';
import { TransactionTemplate } from '@/models/transaction_template.ts';
import type { TemplateEditorInput } from '@/models/template_creation.ts';
import type { TextualYearMonthDay } from '@/core/datetime.ts';

export function templateEditorAmount(raw: number | string, parseLocalized: (text: string) => number, parseWestern: (text: string) => number): number {
    if (typeof raw === 'number') return raw;
    if (raw.startsWith('=')) throw new Error('请先计算金额表达式，再确认模板');
    if (raw.startsWith('numpad:')) {
        const state = JSON.parse(raw.slice(7)) as { value: string; previous: string; symbol: string; confirmed: boolean };
        if (!state.confirmed || state.symbol) throw new Error('请先完成并确认金额输入');
        raw = state.value;
        if (!raw.trim()) throw new Error('金额尚未填写');
        const amount = parseWestern(raw);
        if (!Number.isSafeInteger(amount)) throw new Error('金额尚未填写完整');
        return amount;
    }
    if (!raw.trim()) throw new Error('金额尚未填写');
    const amount = parseLocalized(raw);
    if (!Number.isSafeInteger(amount)) throw new Error('金额尚未填写完整');
    return amount;
}

export function templateFromEditorInput(input: TemplateEditorInput, parseLocalized: (text: string) => number, parseWestern: (text: string) => number): TransactionTemplate {
    const restoreAmount = (raw: number | string) => {
        try { return templateEditorAmount(raw, parseLocalized, parseWestern); }
        catch { return 0; } // Raw text remains in the amount control, not a write.
    };
    const template = TransactionTemplate.createNewTransactionTemplate(Transaction.ofCreateRequest({
        ...input, time: 0, utcOffset: input.utcOffset ?? 0, clientSessionId: 'local-draft', pictureIds: [],
        sourceAmount: restoreAmount(input.sourceAmount), destinationAmount: restoreAmount(input.destinationAmount)
    }), input.ledgerId);
    template.name = input.name; template.templateType = input.templateType;
    template.scheduledFrequencyType = input.scheduledFrequencyType;
    template.scheduledFrequency = input.scheduledFrequency;
    template.scheduledStartDate = input.scheduledStartDate as TextualYearMonthDay | undefined;
    template.scheduledEndDate = input.scheduledEndDate as TextualYearMonthDay | undefined;
    if (input.categorySelections) {
        template.expenseCategoryId = input.categorySelections.expense;
        template.incomeCategoryId = input.categorySelections.income;
        template.transferCategoryId = input.categorySelections.transfer;
    }
    return template;
}
