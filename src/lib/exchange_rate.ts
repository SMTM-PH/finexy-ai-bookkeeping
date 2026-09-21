import type { LatestExchangeRate } from '@/models/exchange_rate.ts';
import { getExchangedAmountByRate } from '@/lib/numeral.ts';

// Returns how many CNY one unit of the selected foreign currency is worth.
export function getCnyValueForCurrency(
    rates: readonly LatestExchangeRate[],
    currency: string
): number | null {
    const cnyRate = rates.find(item => item.currency === 'CNY');
    const currencyRate = rates.find(item => item.currency === currency);

    if (!cnyRate || !currencyRate) {
        return null;
    }

    return getExchangedAmountByRate(1, currencyRate.rate, cnyRate.rate);
}
