import { describe, expect, it } from 'vitest';

import { CategoryType } from '@/core/category.ts';
import { getAllLocalizedTransactionDefaultCategories } from '@/lib/category.ts';

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
});
