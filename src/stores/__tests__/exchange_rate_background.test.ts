import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const settings = vi.hoisted(() => ({ appSettings: { autoUpdateExchangeRatesData: true, exchangeRatesDataCacheExpiration: 3600 }, now: 10000 }));
vi.mock('@/stores/setting.ts', () => ({ useSettingsStore: () => settings }));
vi.mock('@/lib/services.ts', () => ({ default: { getLatestExchangeRates: vi.fn() } }));
vi.mock('@/lib/logger.ts', () => ({ default: { error: vi.fn() } }));
vi.mock('@/lib/datetime.ts', () => ({
    getCurrentUnixTime: () => settings.now,
    isUnixTimeYearMonthDayEquals: () => false,
    isUnixTimeYearMonthDayHourEquals: () => false
}));

import services from '@/lib/services.ts';
import { useExchangeRatesStore } from '../exchangeRates.ts';

const cacheKey = 'ebk_app_exchange_rates';
const verified = {
    time: 100,
    data: { dataSource: 'euro_central_bank', referenceUrl: 'https://example.invalid/verified', updateTime: 80,
        baseCurrency: 'EUR', exchangeRates: [{ currency: 'CNY', rate: '7.8' }, { currency: 'USD', rate: '1.1' }] }
};
const unavailable = { response: { status: 400, data: { success: false, errorCode: 200005, errorMessage: 'Synthetic provider unavailable' } } };
const flushBackground = () => new Promise<void>(done => setTimeout(done, 0));
let storage: Map<string, string>;

describe('optional background exchange-rate refresh', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        settings.appSettings.autoUpdateExchangeRatesData = true;
        settings.appSettings.exchangeRatesDataCacheExpiration = 3600;
        settings.now = 10000;
        storage = new Map([[cacheKey, JSON.stringify(verified)]]);
        vi.stubGlobal('localStorage', {
            getItem: (key: string) => storage.get(key) ?? null,
            setItem: (key: string, value: string) => { storage.set(key, value); },
            removeItem: (key: string) => { storage.delete(key); }
        });
    });
    afterEach(() => { vi.unstubAllGlobals(); });

    it('handles an automatic HTTP failure without an unhandled rejection or a false cache refresh', async () => {
        const unhandled: unknown[] = [];
        const observe = (reason: unknown) => { unhandled.push(reason); };
        process.on('unhandledRejection', observe);
        try {
            const store = useExchangeRatesStore();
            vi.mocked(services.getLatestExchangeRates).mockRejectedValueOnce(unavailable);
            store.autoUpdateExchangeRatesData();
            await flushBackground();
            expect(unhandled).toEqual([]);
            expect(services.getLatestExchangeRates).toHaveBeenCalledWith({ ignoreError: true });
            expect(store.latestExchangeRates).toEqual(verified);
            expect(storage.get(cacheKey)).toBe(JSON.stringify(verified));
        } finally { process.off('unhandledRejection', observe); }
    });

    it('does not replace a verified snapshot with zero or a new timestamp when an automatic response is incomplete', async () => {
        const store = useExchangeRatesStore();
        vi.mocked(services.getLatestExchangeRates).mockResolvedValueOnce({ data: { success: true, result: null } } as never);
        store.autoUpdateExchangeRatesData(); await flushBackground();
        expect(store.latestExchangeRates).toEqual(verified);
        expect(storage.get(cacheKey)).toBe(JSON.stringify(verified));
    });

    it('keeps the foreground refresh failure observable to its caller', async () => {
        const store = useExchangeRatesStore();
        vi.mocked(services.getLatestExchangeRates).mockRejectedValueOnce(unavailable);
        await expect(store.getLatestExchangeRates({ silent: false, force: true })).rejects.toEqual({ error: unavailable.response.data });
        expect(services.getLatestExchangeRates).toHaveBeenCalledWith({ ignoreError: false });
        expect(store.latestExchangeRates).toEqual(verified);
        expect(storage.get(cacheKey)).toBe(JSON.stringify(verified));
    });

    it('adopts only a successful real response when a later automatic retry succeeds', async () => {
        const store = useExchangeRatesStore();
        const refreshed = { ...verified.data, updateTime: 9900, exchangeRates: [{ currency: 'CNY', rate: '8.0' }, { currency: 'USD', rate: '1.2' }] };
        vi.mocked(services.getLatestExchangeRates).mockRejectedValueOnce(unavailable)
            .mockResolvedValueOnce({ data: { success: true, result: refreshed } } as never);
        store.autoUpdateExchangeRatesData(); await flushBackground();
        expect(store.latestExchangeRates).toEqual(verified);
        store.autoUpdateExchangeRatesData(); await flushBackground();
        expect(store.latestExchangeRates).toEqual({ time: settings.now, data: refreshed });
        expect(JSON.parse(storage.get(cacheKey)!)).toEqual({ time: settings.now, data: refreshed });
    });

    it('does not request rates when automatic refresh is disabled', async () => {
        const store = useExchangeRatesStore(); settings.appSettings.autoUpdateExchangeRatesData = false;
        store.autoUpdateExchangeRatesData(); await flushBackground();
        expect(services.getLatestExchangeRates).not.toHaveBeenCalled();
        expect(store.latestExchangeRates).toEqual(verified);
    });
});
