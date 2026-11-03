import { assertAIRecognition } from './ai_review_ledger.ts';
import type { RecognizedTransactionResponse } from './large_language_model.ts';
import { isPeriodicStringId } from './transaction_template.ts';

export interface AIReviewSourceImage {
    readonly blobId: string;
    readonly secret: string;
    readonly sha256: string;
    readonly mime: string;
    readonly name: string;
    readonly bytes: number;
}
export interface ReceiptPoint { readonly x: number; readonly y: number }
export type ReceiptCorners = readonly [ReceiptPoint, ReceiptPoint, ReceiptPoint, ReceiptPoint];
export interface AIReviewSourceInput {
    readonly ledgerId: string;
    readonly sourceType: 1 | 2;
    readonly sourceText: string;
    readonly image?: AIReviewSourceImage;
    readonly corners: ReceiptCorners;
    readonly rotation: 0 | 90 | 180 | 270;
}
export interface AIReviewSourceDraft {
    readonly version: 1;
    readonly identity: string;
    readonly revision: string;
    readonly input: AIReviewSourceInput;
    readonly recognizedData?: RecognizedTransactionResponse;
    readonly failureReason: string;
}
export const fullReceiptCorners = (): ReceiptCorners => [{ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 }];
export function assertReceiptCorners(points: ReceiptCorners): void {
    if (!Array.isArray(points) || points.length !== 4 || points.some(point => !point || !Number.isFinite(point.x) || !Number.isFinite(point.y) || point.x < 0 || point.x > 1 || point.y < 0 || point.y > 1)) throw new Error('裁剪边界无效，请重置并重新核对');
    // Clockwise screen coordinates, convex and nondegenerate. Self-intersecting
    // corners must never silently become a different crop.
    for (let i = 0; i < 4; i++) {
        const a = points[i]!, b = points[(i + 1) % 4]!, c = points[(i + 2) % 4]!;
        if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) <= 0.000001) throw new Error('四角必须依次围住票据，请重置或调整边界');
    }
}
export function assertAIReviewSourceImage(value: AIReviewSourceImage, ledgerId: string, identity: string): void {
    if (!identity || !isPeriodicStringId(ledgerId) || !value || !value.blobId?.startsWith(`${identity}ai-source-image:${ledgerId}:`) || !/^[a-f0-9]{64}$/.test(value.secret)
        || !/^[a-f0-9]{64}$/.test(value.sha256) || !['image/jpeg', 'image/png', 'image/webp'].includes(value.mime)
        || typeof value.name !== 'string' || !Number.isSafeInteger(value.bytes) || value.bytes <= 0 || value.bytes > 20 * 1024 * 1024) throw new Error('原图片记录损坏或不属于原账本、账号，请保留本机记录');
}
export function assertAIReviewSourceDraft(value: AIReviewSourceDraft, ledgerId: string, identity: string): void {
    const input = value?.input;
    if (value?.version !== 1 || value.identity !== identity || typeof value.revision !== 'string' || !value.revision
        || !input || input.ledgerId !== ledgerId || !isPeriodicStringId(ledgerId) || ![1, 2].includes(input.sourceType)
        || typeof input.sourceText !== 'string' || new TextEncoder().encode(input.sourceText).length > 8000
        || ![0, 90, 180, 270].includes(input.rotation) || typeof value.failureReason !== 'string' || [...value.failureReason].length > 500) throw new Error('AI 来源草稿损坏或不属于原账本、账号，请保留原记录');
    assertReceiptCorners(input.corners);
    if (input.image) assertAIReviewSourceImage(input.image, ledgerId, identity);
    if (input.sourceType === 1 && input.image) throw new Error('文字草稿不能带另一份图片来源');
    if (value.recognizedData) assertAIRecognition(value.recognizedData, ledgerId);
}
