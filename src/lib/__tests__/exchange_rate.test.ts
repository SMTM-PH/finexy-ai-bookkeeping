import { describe, expect, test } from 'vitest';
import { getCnyValueForCurrency } from '@/lib/exchange_rate.ts';

describe('CNY exchange-rate display', () => {
    const ecbRates = [
        { currency: 'EUR', rate: '1' },
        { currency: 'CNY', rate: '7.7936' },
        { currency: 'USD', rate: '1.1614' },
        { currency: 'GBP', rate: '0.85740' },
        { currency: 'HKD', rate: '9.1068' },
        { currency: 'JPY', rate: '179.20' }
    ];

    test('converts ECB EUR-base rates into CNY per one foreign unit', () => {
        expect(getCnyValueForCurrency(ecbRates, 'EUR')).toBeCloseTo(7.7936, 6);
        expect(getCnyValueForCurrency(ecbRates, 'USD')).toBeCloseTo(6.7105, 4);
        expect(getCnyValueForCurrency(ecbRates, 'GBP')).toBeCloseTo(9.0898, 4);
        expect(getCnyValueForCurrency(ecbRates, 'HKD')).toBeCloseTo(0.8558, 4);
        expect(getCnyValueForCurrency(ecbRates, 'JPY')).toBeCloseTo(0.04349, 5);
    });

    test('returns null when CNY or the requested currency is absent', () => {
        expect(getCnyValueForCurrency([{ currency: 'EUR', rate: '1' }], 'EUR')).toBeNull();
        expect(getCnyValueForCurrency(ecbRates, 'AUD')).toBeNull();
    });
});
