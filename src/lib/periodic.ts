import { TransactionType } from '@/core/transaction.ts';

interface PeriodicAmounts {
    type: number;
    sourceAccountId: string;
    destinationAccountId: string;
    sourceAmount: number;
    destinationAmount: number;
    hideAmount: boolean;
}

/** Preserve each funding side's own currency, including external transfers. */
export function formatPeriodicAmounts(
    item: PeriodicAmounts,
    accounts: Readonly<Record<string, { currency: string }>>,
    format: (amount: number, currency: string) => string,
    showAmount = true,
): string {
    if (!showAmount || item.hideAmount) return '••••';
    const side = (id: string, amount: number): string => {
        const currency = accounts[id]?.currency;
        return currency && /^[A-Z]{3}$/.test(currency) && Number.isSafeInteger(amount)
            ? format(amount, currency) : '金额或币种待核对';
    };
    if (item.type !== TransactionType.Transfer) return side(item.sourceAccountId, item.sourceAmount);
    const source = item.sourceAccountId && item.sourceAccountId !== '0';
    const destination = item.destinationAccountId && item.destinationAccountId !== '0';
    if (source && destination) return `${side(item.sourceAccountId, item.sourceAmount)} → ${side(item.destinationAccountId, item.destinationAmount)}`;
    if (source) return side(item.sourceAccountId, item.sourceAmount);
    if (destination) return side(item.destinationAccountId, item.destinationAmount);
    return '金额或币种待核对';
}
