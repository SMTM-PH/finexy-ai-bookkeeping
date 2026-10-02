export interface AccountBalancePresentation {
    currency: string;
    balance: number;
    hidden: boolean;
    isLiability: boolean;
}

/** Keep each currency separate; hidden accounts remain browsable but are not totaled. */
export function summarizeVisibleAccountBalances(accounts: readonly AccountBalancePresentation[]) {
    const summaries = new Map<string, { currency: string; assets: number; liabilities: number; net: number }>();
    for (const account of accounts) {
        if (account.hidden) continue;
        const summary = summaries.get(account.currency) || { currency: account.currency, assets: 0, liabilities: 0, net: 0 };
        if (account.isLiability) summary.liabilities -= account.balance;
        else summary.assets += account.balance;
        summary.net += account.balance;
        summaries.set(account.currency, summary);
    }
    return [...summaries.values()].sort((a, b) => a.currency.localeCompare(b.currency));
}

export function getAccountBalanceDisplay(balance: number, isLiability: boolean) {
    if (!isLiability) return { label: "账户余额", amount: balance };
    return balance > 0
        ? { label: "溢缴款", amount: balance }
        : { label: "待还款", amount: Math.abs(balance) };
}
