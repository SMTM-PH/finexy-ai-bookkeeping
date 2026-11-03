import { AccountType } from '@/core/account.ts';
import { CategoryType } from '@/core/category.ts';
import { TransactionType } from '@/core/transaction.ts';
import { DefaultLedgerId, LedgerMemberRole, type LedgerMemberInfoResponse } from '@/models/ledger.ts';
import type { Account } from '@/models/account.ts';
import type { TransactionCategory } from '@/models/transaction_category.ts';
import type { TransactionTag } from '@/models/transaction_tag.ts';
import type { Transaction } from '@/models/transaction.ts';
import type { TransactionDraft } from '@/models/transaction.ts';

/** Keep unsupported account-wide flows guarded. Templates and schedule review
 * now use explicit ledger contracts, including their add/edit deep links. */
export function supportsMobileLedgerRoute(path: string, ledgerId: string): boolean {
    if (ledgerId === DefaultLedgerId) return true;
    const pathname = (path.split(/[?#]/)[0] || '/').replace(/\/+$/, '').toLowerCase() || '/';
    if (pathname === '/statistic/transaction') return true;
    return !(/^\/account\/(add|edit|reconciliation_statements|move_all_transactions)$/.test(pathname) ||
        /^\/(statistic|category|tag)(\/|$)/.test(pathname) ||
        /^\/settings\/filter\/(account|category|tag)$/.test(pathname) ||
        /^\/user\/data(\/|$)/.test(pathname));
}

/** Older drafts belong to the implicit personal ledger, never the new choice. */
export function transactionDraftMatchesLedger(draft: TransactionDraft | null, ledgerId: string): boolean {
    return !!draft && (draft.ledgerId ?? DefaultLedgerId) === ledgerId;
}

export function mobileLedgerRole(ledgerId: string, members?: LedgerMemberInfoResponse[]): number {
    if (ledgerId === DefaultLedgerId) return LedgerMemberRole.Owner;
    if (!Array.isArray(members) || members.some(member => member.ledgerId !== ledgerId)) {
        throw new Error('账本权限响应归属不匹配，请重试');
    }
    const current = members.filter(member => member.isCurrentUser === true && member.status === 1);
    if (current.length !== 1 || !Object.values(LedgerMemberRole).some(role => role === current[0]!.role)) {
        throw new Error('无法确认当前账本成员权限，请重试');
    }
    return current[0]!.role;
}

export function canWriteMobileLedger(role: number | null): boolean {
    return role === LedgerMemberRole.Owner || role === LedgerMemberRole.Admin || role === LedgerMemberRole.Member;
}

export function canUseMobileOwnerResources(role: number | null): boolean {
    return role === LedgerMemberRole.Owner;
}

export function canManageMobileLedger(role: number | null): boolean {
    return role === LedgerMemberRole.Owner || role === LedgerMemberRole.Admin;
}

/** Shared template tags are owner references. Editing may retain the exact
 * captured association, without borrowing the current member's private tags. */
export function mobileTemplateSelectionProblem(
    template: Transaction & { ledgerId?: string }, ledgerId: string, role: number | null,
    accounts: Record<string, Account>, categories: Record<string, TransactionCategory>,
    originalTagIds: readonly string[] = []
): string | null {
    if (!canManageMobileLedger(role)) return '只有账本所有者或管理员可以管理模板';
    if ((template.ledgerId ?? '0') !== ledgerId) return '模板不属于打开时的账本，请重新打开';
    if (template.type === TransactionType.ModifyBalance) return '模板不支持余额调整';
    const problem = mobileTransactionSelectionProblem(template, ledgerId, accounts, categories);
    if (problem) return problem;
    if (template.type === TransactionType.Transfer && (template.sourceAccountId === '0' || template.destinationAccountId === '0'
        || template.sourceAmount < 0 || template.destinationAmount < 0)) return '转账模板必须保留两个本账本账户和两个非负金额';
    if (ledgerId !== '0' && JSON.stringify([...template.tagIds].sort()) !== JSON.stringify([...originalTagIds].sort())) {
        return '共享模板的原标签须保持不变，不能带入私人标签';
    }
    return null;
}

export function isMobileAccountSelectable(id: string, ledgerId: string, accounts: Record<string, Account>): boolean {
    const account = accounts[id];
    const parent = account?.parentId && account.parentId !== '0' ? accounts[account.parentId] : null;
    return !!account && account.ledgerId === ledgerId && !account.hidden && account.type === AccountType.SingleAccount.type &&
        (account.subAccounts?.length ?? 0) === 0 && /^[A-Z]{3}$/.test(account.currency) &&
        (!account.parentId || account.parentId === '0' || (!!parent && !parent.hidden && parent.ledgerId === ledgerId && parent.type === AccountType.MultiSubAccounts.type));
}

export function isMobileCategorySelectable(id: string, type: CategoryType, categories: Record<string, TransactionCategory>): boolean {
    const category = categories[id];
    const parent = category?.parentId && category.parentId !== '0' ? categories[category.parentId] : null;
    return !!category && !category.hidden && category.type === type && !!parent && !parent.hidden && parent.type === type &&
        (category.subCategories?.length ?? 0) === 0 && !!parent.subCategories?.some(child => child.id === category.id && child.parentId === parent.id);
}

/** Validate selected IDs against a complete snapshot. Never repair an explicit
 * stale selection by silently choosing another account/category. */
export function mobileTransactionSelectionProblem(
    transaction: Pick<Transaction, 'type' | 'categoryId' | 'sourceAccountId' | 'destinationAccountId' | 'sourceAmount' | 'destinationAmount'>,
    ledgerId: string,
    accounts: Record<string, Account>,
    categories: Record<string, TransactionCategory>
): string | null {
    if (![TransactionType.Expense, TransactionType.Income, TransactionType.Transfer, TransactionType.ModifyBalance].includes(transaction.type)) {
        return '流水类型无效，请重新选择';
    }
    if (ledgerId !== DefaultLedgerId && transaction.type === TransactionType.ModifyBalance) {
        return '当前移动版本暂不支持此账本的余额调整';
    }
    const selectedAccounts = transaction.type === TransactionType.Transfer
        ? [transaction.sourceAccountId, transaction.destinationAccountId].filter(id => !!id && id !== '0')
        : [transaction.sourceAccountId];
    if (!selectedAccounts.length) return '请选择当前账本的可用账户';
    if (transaction.type === TransactionType.Transfer && selectedAccounts.length === 2 && selectedAccounts[0] === selectedAccounts[1]) {
        return '转出和转入账户不能相同';
    }
    for (const id of selectedAccounts) {
        if (!isMobileAccountSelectable(id, ledgerId, accounts)) {
            return '所选账户不属于当前账本或已不可用，请重新选择';
        }
    }
    if (!Number.isSafeInteger(transaction.sourceAmount) ||
        (transaction.type === TransactionType.Transfer && !Number.isSafeInteger(transaction.destinationAmount))) {
        return '金额必须为有效的整数最小货币单位';
    }
    if (transaction.type === TransactionType.ModifyBalance) return null;
    const expectedType = transaction.type === TransactionType.Expense ? CategoryType.Expense
        : transaction.type === TransactionType.Income ? CategoryType.Income : CategoryType.Transfer;
    if (!isMobileCategorySelectable(transaction.categoryId, expectedType, categories)) {
        return '所选分类不是当前类型的可用末级分类，请重新选择';
    }
    return null;
}

export function mobileTagSelectionProblem(tagIds: readonly string[], tags: Record<string, TransactionTag>): string | null {
    return tagIds.some(id => !tags[id] || tags[id].hidden) ? '所选标签已不可用，请重新选择' : null;
}

export const mobileLedgerUnsupportedMessage = '当前移动版本支持此账本的收支、转账、流水管理、月度预算、模板、周期待确认和账本报告；账户管理和高级账户分析暂不支持。标签与附件遵守账本所有者边界。';
