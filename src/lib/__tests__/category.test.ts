import { describe, expect, it } from 'vitest';

import { CategoryType } from '@/core/category.ts';
import type { TransactionCategory } from '@/models/transaction_category.ts';
import {
    getAllLocalizedTransactionDefaultCategories,
    getCategoryTypesWithoutSelectableCategories
} from '@/lib/category.ts';

describe('getAllLocalizedTransactionDefaultCategories', () => {
    it('returns the complete simplified Chinese preset set', () => {
        const categories = getAllLocalizedTransactionDefaultCategories(0, 'zh-Hans');

        expect(categories[CategoryType.Income]).toHaveLength(3);
        expect(categories[CategoryType.Expense]).toHaveLength(11);
        expect(categories[CategoryType.Transfer]).toHaveLength(3);
        expect(categories[CategoryType.Expense]?.[0]?.name).toBe('食品饮料');
        expect(categories[CategoryType.Expense]?.[0]?.subCategories[0]?.name).toBe('食品');
    });

    it('falls back to English for an unknown locale', () => {
        const categories = getAllLocalizedTransactionDefaultCategories(CategoryType.Expense, 'unknown');

        expect(categories[CategoryType.Expense]?.[0]?.name).toBe('Food & Drink');
        expect(categories[CategoryType.Expense]?.[0]?.subCategories[0]?.name).toBe('Food');
    });

    it('finds only category types without a selectable subcategory', () => {
        const selectableCategory = {
            hidden: false,
            subCategories: [{ id: '2', hidden: false }]
        } as TransactionCategory;
        const categories = {
            [CategoryType.Income]: [selectableCategory],
            [CategoryType.Expense]: [],
            [CategoryType.Transfer]: [selectableCategory]
        };

        expect(getCategoryTypesWithoutSelectableCategories(categories)).toEqual([CategoryType.Expense]);
    });
});
