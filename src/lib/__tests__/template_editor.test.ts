import { describe, expect, it } from 'vitest';
import { templateEditorAmount, templateFromEditorInput } from '../template_editor.ts';
import type { TemplateEditorInput } from '@/models/template_creation.ts';

const parse = (text: string) => Number(text) * 100;
const input: TemplateEditorInput = { ledgerId: '9007199254740997', templateType: 2, name: '', type: 4,
    sourceAccountId: '21', destinationAccountId: '22', sourceAmount: '1.', destinationAmount: '', categoryId: '31',
    hideAmount: true, tagIds: ['41'], comment: 'Synthetic input', utcOffset: 480, scheduledFrequencyType: 2,
    scheduledFrequency: '29,30', scheduledStartDate: '2028-02-29', scheduledEndDate: '2028-12-31',
    categorySelections: { expense: '32', income: '33', transfer: '31' } };

describe('template editor raw input boundary', () => {
    it('restores dates, ledger, dual accounts and inactive category choices without rounding IDs', () => {
        const restored = templateFromEditorInput(input, parse, parse);
        expect(restored.ledgerId).toBe(input.ledgerId);
        expect(restored.sourceAmount).toBe(100);
        expect(restored.destinationAmount).toBe(0);
        expect(restored.expenseCategoryId).toBe('32');
        expect(restored.incomeCategoryId).toBe('33');
        expect(restored.transferCategoryId).toBe('31');
        expect(restored.toTemplateCreateRequest('stable-key')).toMatchObject({ ledgerId: input.ledgerId,
            scheduledFrequency: '29,30', scheduledStartDate: '2028-02-29', scheduledEndDate: '2028-12-31', utcOffset: 480,
            sourceAccountId: '21', destinationAccountId: '22', hideAmount: true, tagIds: ['41'] });
        expect(input.sourceAmount).toBe('1.'); expect(input.destinationAmount).toBe('');
    });
    it.each(['', ' ', '=1+2', 'numpad:{"value":"2","previous":"1","symbol":"+","confirmed":false}',
        'numpad:{"value":"2","previous":"","symbol":"","confirmed":false}', 'numpad:{broken', 'invalid', '0.001'])
    ('rejects unfinished raw input %s before creating the request', raw => {
        expect(() => templateEditorAmount(raw, parse, parse)).toThrow();
        expect(templateFromEditorInput({ ...input, sourceAmount: raw }, parse, parse).sourceAmount).toBe(0);
    });
    it('uses the western parser only for a confirmed mobile calculator result', () => {
        expect(templateEditorAmount('numpad:{"value":"-1.23","previous":"","symbol":"","confirmed":true}',
            () => { throw new Error('wrong parser'); }, parse)).toBe(-123);
        expect(templateEditorAmount('-1.23', parse, () => { throw new Error('wrong parser'); })).toBe(-123);
        expect(templateEditorAmount(123, parse, parse)).toBe(123);
    });
});
