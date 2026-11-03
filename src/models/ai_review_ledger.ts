import type { AIReviewItemCreateRequest, AIReviewItemInfoResponse } from './ai_review_item.ts';
import type { RecognizedTransactionResponse } from './large_language_model.ts';
import type { TransactionCreateRequest, TransactionDraft } from './transaction.ts';
import { TransactionType } from '@/core/transaction.ts';
import { isPeriodicStringId } from './transaction_template.ts';

export interface AIReviewLedgerItem extends AIReviewItemInfoResponse {
    readonly ledgerId: string;
    readonly creatorUid: string;
    readonly transactionId: string;
    readonly confirmedUid: string;
    readonly editable: boolean;
    readonly deleted: boolean;
}
export interface AIReviewCapabilities {
    readonly ledgerId: string;
    readonly contractVersion: number;
    readonly durableCreation: boolean;
    readonly atomicConfirmation: boolean;
    readonly payloadBinding: boolean;
    readonly deletedReceipts: boolean;
    readonly maxClientSessionIdBytes: number;
    readonly maxPageSize: number;
    readonly pagination: string;
    readonly reviewerScope: string;
    readonly confirmationPath: string;
    readonly recognitionLedgerContext?: boolean;
}
export interface LedgerAIReviewCreateRequest extends AIReviewItemCreateRequest {
    readonly ledgerId: string;
    readonly clientSessionId: string;
}
export interface AIReviewConfirmRequest {
    readonly ledgerId: string;
    readonly id: string;
    readonly transaction: TransactionCreateRequest & { readonly ledgerId: string };
}
export interface AIReviewConfirmationReceipt {
    readonly ledgerId: string;
    readonly id: string;
    readonly transactionId: string;
}
export interface PendingAIReviewCreate {
    readonly version: 1;
    readonly identity: string;
    readonly request: LedgerAIReviewCreateRequest;
    readonly draftRevision?: string | null;
    readonly receipt?: { readonly id: string; readonly deleted: boolean };
}
export interface PendingAIReviewConfirm {
    readonly version: 1;
    readonly identity: string;
    readonly request: AIReviewConfirmRequest;
    readonly draftRevision?: string | null;
    readonly receipt?: AIReviewConfirmationReceipt;
}

export interface AIReviewEditorInput {
    readonly ledgerId: string;
    readonly id: string;
    readonly transaction: TransactionDraft;
    readonly rawSourceAmount?: string;
    readonly rawDestinationAmount?: string;
    readonly categorySelections: { readonly expense: string; readonly income: string; readonly transfer: string };
    readonly transferDirection: 'internal' | 'out' | 'in';
}
export interface AIReviewEditorDraft {
    readonly version: 1;
    readonly identity: string;
    readonly revision: string;
    readonly input: AIReviewEditorInput;
}

/** Unfinished amount expressions are preserved verbatim. Only confirmation
 * validates a completed integer financial request. */
export function assertAIReviewEditorDraft(value: AIReviewEditorDraft, ledgerId: string, identity: string): void {
    const input = value?.input, tx = input?.transaction;
    const editableId = (id: unknown) => id === '' || isPeriodicStringId(id);
    if (value?.version !== 1 || value.identity !== identity || typeof value.revision !== 'string' || !value.revision
        || !input || input.ledgerId !== ledgerId || !isPeriodicStringId(ledgerId) || !isPeriodicStringId(input.id, false)
        || !tx || tx.ledgerId !== ledgerId || ![2, 3, 4].includes(tx.type!)
        || !Number.isSafeInteger(tx.time) || tx.time! <= 0 || (tx.timeZone !== undefined && typeof tx.timeZone !== 'string')
        || !Number.isInteger(tx.utcOffset) || tx.utcOffset! < -720 || tx.utcOffset! > 840
        || ![tx.categoryId, tx.sourceAccountId, tx.destinationAccountId].every(editableId)
        || ![tx.sourceAmount, tx.destinationAmount].every(amountValid)
        || typeof tx.comment !== 'string' || typeof tx.hideAmount !== 'boolean' || !idsValid(tx.tagIds, 10)
        || !Array.isArray(tx.pictures) || tx.pictures.length > 10 || tx.pictures.some(picture => !picture || !isPeriodicStringId(picture.pictureId, false) || typeof picture.originalUrl !== 'string')
        || !['internal', 'out', 'in'].includes(input.transferDirection)
        || !input.categorySelections || !Object.values(input.categorySelections).every(editableId)
        || !['expense', 'income', 'transfer'].every(key => editableId(input.categorySelections[key as keyof typeof input.categorySelections]))
        || [input.rawSourceAmount, input.rawDestinationAmount].some(raw => raw !== undefined && typeof raw !== 'string')
        || (tx.geoLocation !== undefined && (!Number.isFinite(tx.geoLocation.latitude) || !Number.isFinite(tx.geoLocation.longitude)
            || Math.abs(tx.geoLocation.latitude) > 90 || Math.abs(tx.geoLocation.longitude) > 180))) {
        throw new Error('AI 编辑草稿损坏或不属于原账本、账号，请保留原记录');
    }
}

const amountValid = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && Math.abs(value) <= 9999999999999;
const keyValid = (value: unknown, max = 128): value is string => typeof value === 'string' && !!value.trim() && new TextEncoder().encode(value).length <= max;
const idsValid = (value: unknown, max: number): value is string[] => Array.isArray(value) && value.length <= max
    && value.every(id => isPeriodicStringId(id, false)) && new Set(value).size === value.length;

// strings.TrimSpace on the server uses Unicode White_Space. JS trim differs
// for U+0085 and BOM; compare receipts against the actual server normalization.
export function trimAIReviewSource(value: string): string {
    return value.replace(/^[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/g, '');
}

export function assertAIRecognition(value: RecognizedTransactionResponse, ledgerId: string): void {
    if (!value || !isPeriodicStringId(ledgerId) || (value.ledgerId ?? '0') !== ledgerId || ![2, 3, 4].includes(value.type)
        || [value.sourceAccountId, value.destinationAccountId, value.categoryId].some(id => id !== undefined && !isPeriodicStringId(id))
        || [value.sourceAmount, value.destinationAmount].some(amount => amount !== undefined && !amountValid(amount))
        || (value.time !== undefined && (!Number.isSafeInteger(value.time) || value.time < 0))
        || (value.tagIds !== undefined && !idsValid(value.tagIds, 10))
        || (value.comment !== undefined && typeof value.comment !== 'string')) {
        throw new Error('识别结果不完整或不属于原账本，请重新核对');
    }
}
export function assertAIReviewItem(value: AIReviewLedgerItem, ledgerId: string): void {
    if (!value || value.ledgerId !== ledgerId || !isPeriodicStringId(ledgerId) || !isPeriodicStringId(value.id, false)
        || !isPeriodicStringId(value.creatorUid, false) || !isPeriodicStringId(value.confirmedUid) || !isPeriodicStringId(value.transactionId)
        || ![1, 2, 3].includes(value.sourceType) || ![1, 2, 3].includes(value.status)
        || typeof value.editable !== 'boolean' || typeof value.deleted !== 'boolean' || (value.deleted && value.editable)
        || typeof value.sourceText !== 'string' || typeof value.failureReason !== 'string'
        || !Number.isSafeInteger(value.createdUnixTime) || value.createdUnixTime < 0
        || (value.status !== 2 && value.transactionId !== '0')) {
        throw new Error('待复核回包不完整或账本不匹配，请重新读取');
    }
    // Legacy recognized data may omit its ledger. The queue itself remains the
    // authoritative scope; the server checks resource IDs again on confirmation.
    if (value.recognizedData) assertAIRecognition({ ...value.recognizedData, ledgerId: value.recognizedData.ledgerId ?? ledgerId }, ledgerId);
}
export function assertAIReviewCapabilities(value: AIReviewCapabilities, ledgerId: string, recognition = false): void {
    if (!value || value.ledgerId !== ledgerId || value.contractVersion !== 1 || value.durableCreation !== true
        || value.atomicConfirmation !== true || value.payloadBinding !== true || value.deletedReceipts !== true
        || value.maxClientSessionIdBytes !== 128 || value.maxPageSize !== 100 || value.pagination !== 'beforeId'
        || value.reviewerScope !== 'manager-or-creator' || value.confirmationPath !== 'v1/ai/review/confirm.json'
        || (recognition && value.recognitionLedgerContext !== true)) {
        throw new Error('当前服务不支持可靠的账本 AI 流程，请升级服务；本机内容已保留');
    }
}
export function assertAIReviewCreate(request: LedgerAIReviewCreateRequest, ledgerId: string): void {
    if (!request || request.ledgerId !== ledgerId || !isPeriodicStringId(ledgerId) || !keyValid(request.clientSessionId)
        || ![1, 2, 3].includes(request.sourceType) || typeof request.sourceText !== 'string' || !trimAIReviewSource(request.sourceText)
        || new TextEncoder().encode(trimAIReviewSource(request.sourceText)).length > 8000 || typeof request.failureReason !== 'string' || [...trimAIReviewSource(request.failureReason)].length > 500) {
        throw new Error('待复核创建内容不完整，请核对原输入');
    }
    if (request.recognizedData) assertAIRecognition({ ...request.recognizedData, ledgerId: request.recognizedData.ledgerId ?? ledgerId }, ledgerId);
}
export function assertAIReviewConfirm(request: AIReviewConfirmRequest, ledgerId: string): void {
    const tx = request?.transaction;
    if (!request || request.ledgerId !== ledgerId || !isPeriodicStringId(ledgerId) || !isPeriodicStringId(request.id, false)
        || !tx || tx.ledgerId !== ledgerId || !keyValid(tx.clientSessionId, 256) || ![2, 3, 4].includes(tx.type)
        || !isPeriodicStringId(tx.categoryId, false) || !isPeriodicStringId(tx.sourceAccountId) || !isPeriodicStringId(tx.destinationAccountId)
        || !amountValid(tx.sourceAmount) || !amountValid(tx.destinationAmount) || !Number.isSafeInteger(tx.time) || tx.time <= 0
        || !Number.isInteger(tx.utcOffset) || tx.utcOffset < -720 || tx.utcOffset > 840 || typeof tx.hideAmount !== 'boolean'
        || typeof tx.comment !== 'string' || [...tx.comment].length > 255 || !idsValid(tx.tagIds, 10) || !idsValid(tx.pictureIds, 10)
        || (tx.geoLocation !== undefined && (!Number.isFinite(tx.geoLocation.latitude) || !Number.isFinite(tx.geoLocation.longitude)
            || Math.abs(tx.geoLocation.latitude) > 90 || Math.abs(tx.geoLocation.longitude) > 180))) {
        throw new Error('AI 确认内容不完整，请核对原账本、账户及金额');
    }
    if (tx.type === TransactionType.Transfer) {
        if ((tx.sourceAccountId === '0' && tx.destinationAccountId === '0') || tx.sourceAccountId === tx.destinationAccountId
            || tx.sourceAmount < 0 || tx.destinationAmount < 0 || (tx.sourceAccountId === '0' && tx.sourceAmount !== 0)
            || (tx.destinationAccountId === '0' && tx.destinationAmount !== 0)) throw new Error('请保留转账资金方向及双金额');
    } else if (tx.sourceAccountId === '0' || tx.destinationAccountId !== '0' || tx.destinationAmount !== 0
        || (tx.type === TransactionType.Income && tx.sourceAmount < 0)) throw new Error('收支账户或金额不完整，请核对');
}
export function assertAIReviewReceipt(value: AIReviewConfirmationReceipt, request: AIReviewConfirmRequest): void {
    if (!value || value.ledgerId !== request.ledgerId || value.id !== request.id || !isPeriodicStringId(value.transactionId, false)) {
        throw new Error('AI 确认回执不匹配，请保留原请求并重试');
    }
}
export function assertPendingAIReviewCreate(value: PendingAIReviewCreate, ledgerId: string, identity: string): void {
    if (!value || value.version !== 1 || value.identity !== identity
        || (value.draftRevision !== undefined && value.draftRevision !== null && (typeof value.draftRevision !== 'string' || !value.draftRevision))
        || (value.receipt !== undefined && (!value.receipt || !isPeriodicStringId(value.receipt.id, false) || typeof value.receipt.deleted !== 'boolean'))) {
        throw new Error('本机待复核创建请求损坏或账号不匹配，请保留原记录');
    }
    assertAIReviewCreate(value.request, ledgerId);
}
export function assertPendingAIReviewConfirm(value: PendingAIReviewConfirm, ledgerId: string, identity: string): void {
    if (!value || value.version !== 1 || value.identity !== identity
        || (value.draftRevision !== undefined && value.draftRevision !== null && (typeof value.draftRevision !== 'string' || !value.draftRevision))) throw new Error('本机 AI 确认请求损坏或账号不匹配，请保留原记录');
    assertAIReviewConfirm(value.request, ledgerId);
    if (value.receipt !== undefined) assertAIReviewReceipt(value.receipt, value.request);
}
