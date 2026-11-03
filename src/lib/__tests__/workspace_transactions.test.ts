import { describe, expect, it, vi } from 'vitest';
import services from '@/lib/services.ts';
import { loadWorkspaceTransactions } from '../workspace_transactions.ts';

vi.mock('@/lib/services.ts', () => ({ default: { getTransactions: vi.fn() } }));
const list = vi.mocked(services.getTransactions);
const row = (id: string) => ({ id, timeSequenceId: '1790800000001', type: 3, categoryId: '1', time: 1790800000, utcOffset: 480, sourceAccountId: '999999999999999999', destinationAccountId: '0', sourceAmount: 100, destinationAmount: 0, hideAmount: false, tagIds: [], comment: '', editable: true });
const response = (items: unknown[], next?: number | string) => ({ data: { success: true, result: { items, nextTimeSequenceId: next } } }) as Awaited<ReturnType<typeof services.getTransactions>>;

describe('workspace history pagination', () => {
    it('keeps large IDs and same-second rows across inclusive cursor pages', async () => {
        list.mockResolvedValueOnce(response(Array.from({ length: 50 }, (_, i) => row(`9999999999999999${i}`)), '1790800000000'));
        list.mockResolvedValueOnce(response([row('9999999999999999999')]));
        const result = await loadWorkspaceTransactions('1234567890123456789', () => true);
        expect(result).toHaveLength(51);
        expect(result?.[50]?.id).toBe('9999999999999999999');
        expect(list.mock.calls[1]?.[0]).toMatchObject({ ledgerId: '1234567890123456789', maxTime: 1790800000000, page: 1, count: 50 });
    });

    it('discards an in-flight response after the ledger or page changes', async () => {
        let current = true;
        list.mockImplementationOnce(async () => { current = false; return response([row('1')], 1000); });
        expect(await loadWorkspaceTransactions('1', () => current)).toBeNull();
        expect(list).toHaveBeenCalledTimes(1);
    });

    it('does not turn missing or failed result into an empty ledger', async () => {
        list.mockResolvedValueOnce({ data: { success: true } } as Awaited<ReturnType<typeof services.getTransactions>>);
        await expect(loadWorkspaceTransactions(undefined, () => true)).rejects.toThrow('无法读取');
        list.mockResolvedValueOnce({ data: { success: false, result: { items: [] } } } as unknown as Awaited<ReturnType<typeof services.getTransactions>>);
        await expect(loadWorkspaceTransactions(undefined, () => true)).rejects.toThrow('无法读取');
    });

    it('rejects repeated cursors and overlapping pages without returning partial history', async () => {
        list.mockResolvedValueOnce(response([row('1')], 1000)).mockResolvedValueOnce(response([row('2')], 1000));
        await expect(loadWorkspaceTransactions('0', () => true)).rejects.toThrow('分页异常');
        list.mockResolvedValueOnce(response([row('1')], 1000)).mockResolvedValueOnce(response([row('1')]));
        await expect(loadWorkspaceTransactions('0', () => true)).rejects.toThrow('数据发生变化');
    });
});
