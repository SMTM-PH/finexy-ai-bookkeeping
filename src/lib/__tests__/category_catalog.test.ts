import { describe, expect, it } from 'vitest';

import type { TransactionCategory } from '@/models/transaction_category.ts';
import {
    buildCategoryCatalog,
    categoryEditPayload,
    filterCatalogItems,
    flattenTransactionCategories
} from '@/lib/category_catalog.ts';

function primaryCategory(index: number): TransactionCategory {
    return {
        id: String(index),
        name: `一级分类${index}`,
        parentId: '0',
        comment: '',
        color: '#101828',
        visible: true,
        subCategories: []
    } as unknown as TransactionCategory;
}

describe('分类结构目录（issue #48）', () => {
    it('第 9 个及之后的一级分类仍然进入目录，并且可以被搜索到', () => {
        const primaries = Array.from({ length: 12 }, (_, index) => primaryCategory(index + 1));
        const catalog = buildCategoryCatalog(flattenTransactionCategories({ 0: primaries }));

        expect(catalog).toHaveLength(12);
        expect(catalog[8]?.title).toBe('一级分类9');
        expect(filterCatalogItems(catalog, '一级分类12')).toHaveLength(1);
    });

    it('查询为空时返回全部一级分类，而不是固定截断前 8 个', () => {
        const primaries = Array.from({ length: 12 }, (_, index) => primaryCategory(index + 1));
        const catalog = buildCategoryCatalog(flattenTransactionCategories({ 0: primaries }));

        expect(filterCatalogItems(catalog, '')).toHaveLength(catalog.length);
        expect(filterCatalogItems(catalog, '   ')).toHaveLength(catalog.length);
    });

    it('子分类编辑使用子分类自身的 id，而不是所属一级分类', () => {
        const child = {
            id: '101',
            name: '子分类A',
            parentId: '9'
        } as unknown as TransactionCategory;

        expect(categoryEditPayload(child)).toEqual({ id: '101', currentCategory: child });
    });
});
