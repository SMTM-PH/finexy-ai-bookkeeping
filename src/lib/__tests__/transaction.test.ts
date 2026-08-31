import { describe, expect, it } from 'vitest';

import { CategoryType } from '@/core/category.ts';
import { TransactionType } from '@/core/transaction.ts';
import { Transaction } from '@/models/transaction.ts';
import { TransactionCategory } from '@/models/transaction_category.ts';
import { setTransactionModelByTransaction } from '@/lib/transaction.ts';

function categoryTree(type: CategoryType, primaryId: string, secondaryId: string): TransactionCategory {
    const primary = TransactionCategory.createNewCategory(type);
    primary.id = primaryId;
    primary.name = `${type}-primary`;

    const secondary = TransactionCategory.createNewCategory(type, primaryId);
    secondary.id = secondaryId;
    secondary.name = `${type}-secondary`;
    primary.subCategories = [secondary];

    return primary;
}

describe('transaction editing contract', () => {
    it('keeps expense amounts positive in create requests', () => {
        const transaction = Transaction.createNewTransaction(TransactionType.Expense, 1, 'UTC', 0);
        transaction.expenseCategoryId = 'expense-secondary';
        transaction.sourceAccountId = 'account';
        transaction.sourceAmount = 500;

        const request = transaction.toCreateRequest('session');

        expect(request.type).toBe(TransactionType.Expense);
        expect(request.sourceAmount).toBe(500);
        expect(request.categoryId).toBe('expense-secondary');
    });

    it('selects a category from the active transaction type only', () => {
        const expense = categoryTree(CategoryType.Expense, 'expense-primary', 'expense-secondary');
        const income = categoryTree(CategoryType.Income, 'income-primary', 'income-secondary');
        const transfer = categoryTree(CategoryType.Transfer, 'transfer-primary', 'transfer-secondary');
        const categories = {
            [CategoryType.Expense]: [expense],
            [CategoryType.Income]: [income],
            [CategoryType.Transfer]: [transfer]
        };
        const categoryMap = {
            'expense-primary': expense,
            'expense-secondary': expense.subCategories![0]!,
            'income-primary': income,
            'income-secondary': income.subCategories![0]!,
            'transfer-primary': transfer,
            'transfer-secondary': transfer.subCategories![0]!
        };
        const transaction = Transaction.createNewTransaction(TransactionType.Expense, 1, 'UTC', 0);

        setTransactionModelByTransaction(
            transaction,
            null,
            categories,
            categoryMap,
            [],
            {},
            {},
            '',
            { type: TransactionType.Expense, categoryId: 'income-secondary' },
            true
        );

        expect(transaction.type).toBe(TransactionType.Expense);
        expect(transaction.categoryId).toBe('expense-secondary');
    });
});
