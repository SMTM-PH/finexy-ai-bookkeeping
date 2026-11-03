import { describe, expect, it } from 'vitest';
import { projectReceiptPoint, receiptProjection } from '../receipt_crop.ts';
import { assertAIReviewSourceDraft, assertAIReviewSourceImage, assertReceiptCorners, fullReceiptCorners, type ReceiptCorners } from '@/models/ai_review_source.ts';

describe('receipt perspective and source boundaries', () => {
    it('maps all four destination corners to a skewed original quadrilateral', () => {
        const corners: ReceiptCorners = [{ x: .12, y: .09 }, { x: .84, y: .2 }, { x: .93, y: .92 }, { x: .07, y: .81 }];
        const projection = receiptProjection(corners);
        [[0, 0], [1, 0], [1, 1], [0, 1]].forEach(([x, y], index) => {
            const point = projectReceiptPoint(projection, x!, y!);
            expect(point.x).toBeCloseTo(corners[index]!.x, 10); expect(point.y).toBeCloseTo(corners[index]!.y, 10);
        });
        const center = projectReceiptPoint(projection, .5, .5);
        expect(center.x).toBeGreaterThan(.1); expect(center.x).toBeLessThan(.9);
        expect(center.y).toBeGreaterThan(.1); expect(center.y).toBeLessThan(.9);
    });
    it('preserves an uncropped original and rejects invalid homogeneous transforms', () => {
        const point = projectReceiptPoint(receiptProjection(fullReceiptCorners()), .37, .62);
        expect(point.x).toBeCloseTo(.37, 10); expect(point.y).toBeCloseTo(.62, 10);
        expect(() => projectReceiptPoint([1, 0, 0, 0, 1, 0, -1, 0], 1, 0)).toThrow('变换无效');
    });
    it.each([
        [{ x: 0, y: 0 }, { x: 1, y: 1 }, { x: 1, y: 0 }, { x: 0, y: 1 }],
        [{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 }],
        [{ x: -1, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 }],
        [{ x: NaN, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 }],
    ].map(points => ({ points })))('rejects crossing, degenerate, out of bounds and nonfinite corners %#', ({ points }) => {
        expect(() => assertReceiptCorners(points as unknown as ReceiptCorners)).toThrow();
    });
    it('rejects image metadata moved to another user or ledger, including noncanonical ledger IDs', () => {
        const ref = { blobId: 'owner/ai-source-image:9:uuid', secret: 'a'.repeat(64), sha256: 'b'.repeat(64), mime: 'image/png', name: 'synthetic.png', bytes: 100 };
        expect(() => assertAIReviewSourceImage(ref, '9', 'owner/')).not.toThrow();
        for (const [ledger, identity] of [['10', 'owner/'], ['9', 'other/'], ['09', 'owner/']]) expect(() => assertAIReviewSourceImage(ref, ledger!, identity!)).toThrow();
    });
    it('preserves incomplete source input but refuses recognized data from another ledger', () => {
        const draft = { version: 1 as const, identity: 'owner/', revision: 'revision', input: { ledgerId: '9', sourceType: 2 as const, sourceText: '', corners: fullReceiptCorners(), rotation: 90 as const }, failureReason: '' };
        expect(() => assertAIReviewSourceDraft(draft, '9', 'owner/')).not.toThrow();
        expect(() => assertAIReviewSourceDraft({ ...draft, recognizedData: { ledgerId: '10', type: 3 } }, '9', 'owner/')).toThrow();
        expect(() => assertAIReviewSourceDraft({ ...draft, input: { ...draft.input, sourceText: '中'.repeat(2667) } }, '9', 'owner/')).toThrow();
    });
});
