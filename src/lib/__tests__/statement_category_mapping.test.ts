import { describe, expect, it } from 'vitest';
import { TransactionCategory } from '@/models/transaction_category.ts';
import { ImportTransaction } from '@/models/imported_transaction.ts';
import { TransactionType } from '@/core/transaction.ts';
import { suggestStatementCategory, readStatementCategoryMappings, statementMappingStorageKey } from '@/lib/statement_category_mapping.ts';

const category = (id: string, name: string, type = 2, parentId = '1', hidden = false) => TransactionCategory.of({ id, name, type, parentId, hidden, icon: '1', color: '000000', comment: '', displayOrder: 0 });
const row = (name: string, comment = '', type = TransactionType.Expense) => ImportTransaction.of({ type, originalCategoryName: name, comment, categoryId: '0', time: 1, utcOffset: 480, sourceAccountId: '0', originalSourceAccountName: '', originalSourceAccountCurrency: 'CNY', sourceAmount: 100, tagIds: [], originalTagNames: [] }, 0);
const categories = { '1': category('1', '生活', 2, '0'), '2': category('2', '餐饮'), '3': category('3', '交通'), '4': category('4', '饮料') };

describe('statement category suggestions', () => {
    it('splits broad WeChat consumption by clear purpose without changing rows', () => {
        const food = row('商户消费', '午餐'); const travel = row('商户消费', '地铁乘车');
        expect(suggestStatementCategory(food, categories).targetId).toBe('2');
        expect(suggestStatementCategory(travel, categories).targetId).toBe('3');
        expect(suggestStatementCategory(food, categories).key).not.toBe(suggestStatementCategory(travel, categories).key);
        expect(food.categoryId).toBe('0'); expect(food.selected).toBe(false);
    });
    it('retains unknown and conflicting purpose for explicit selection', () => {
        expect(suggestStatementCategory(row('商户消费', '无用途'), categories).targetId).toBe('');
        expect(suggestStatementCategory(row('商户消费', '午餐和地铁'), categories).targetId).toBe('');
        expect(suggestStatementCategory(row('商户消费'), { ...categories, '7': category('7', '商户消费') }).targetId).toBe('');
    });
    it('proposes unique names and aliases within type; rejects ambiguity', () => {
        expect(suggestStatementCategory(row('餐饮美食'), categories).targetId).toBe('2');
        expect(suggestStatementCategory(row('餐饮'), { ...categories, '7': category('7', '餐饮') }).targetId).toBe('');
        expect(suggestStatementCategory(row('餐饮', '', TransactionType.Income), categories).targetId).toBe('');
    });
    it('validates remembered IDs against type and visible leaf and parent', () => {
        const food = row('餐饮美食'); const key = suggestStatementCategory(food, categories).key;
        expect(suggestStatementCategory(food, categories, { [key]: '3' }).targetId).toBe('3');
        expect(suggestStatementCategory(food, categories, { [key]: '1' }).targetId).toBe('2');
        expect(suggestStatementCategory(food, { ...categories, '1': category('1', '生活', 2, '0', true) }, { [key]: '3' }).targetId).toBe('');
        expect(suggestStatementCategory(food, categories, { [key]: '999' }).targetId).toBe('2');
    });
    it('isolates rules by server, user, ledger and provider while sharing CSV/XLSX', () => {
        const key = statementMappingStorageKey('http://nas', 'user', '0', 'wechat_pay_app_csv');
        expect(statementMappingStorageKey('http://nas', 'user', '0', 'wechat_pay_app_xlsx')).toBe(key);
        for (const args of [['http://other', 'user', '0', 'wechat_pay_app_csv'], ['http://nas', 'other', '0', 'wechat_pay_app_csv'], ['http://nas', 'user', '1', 'wechat_pay_app_csv'], ['http://nas', 'user', '0', 'alipay_app_csv']]) expect(statementMappingStorageKey(...args as [string, string, string, string])).not.toBe(key);
    });
    it('handles corrupt or unavailable browser storage and preserves string IDs', () => {
        expect(readStatementCategoryMappings({ getItem: () => '{invalid' }, 'key')).toEqual({});
        expect(readStatementCategoryMappings({ getItem: () => '{"ok":"9007199254740993","zero":"0","number":123}' }, 'key')).toEqual({ ok: '9007199254740993' });
        expect(readStatementCategoryMappings({ getItem: () => { throw new Error(); } }, 'key')).toEqual({});
    });
});
