import { TransactionType } from '@/core/transaction.ts';
import type { PendingTransactionCreate, TransactionCreateRequest, TransactionInfoResponse } from '@/models/transaction.ts';

const id = (value: unknown): value is string => typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value);
const positiveId = (value: unknown): value is string => id(value) && value !== '0';
const ids = (value: unknown): value is string[] => Array.isArray(value) && value.every(positiveId) && new Set(value).size === value.length;
const sameIds = (left: readonly string[], right: readonly string[]) => left.length === right.length && left.every(value => right.includes(value));

export function assertPendingTransactionCreate(pending: PendingTransactionCreate, ledgerId: string): void {
    const request = pending?.request;
    if (!request || !id(ledgerId) || typeof pending.clientSessionId !== 'string' || !pending.clientSessionId || pending.clientSessionId.length > 256 ||
        request.clientSessionId !== pending.clientSessionId || (request.ledgerId ?? '0') !== ledgerId ||
        ![TransactionType.Expense, TransactionType.Income, TransactionType.Transfer].includes(request.type) ||
        !id(request.sourceAccountId) || !id(request.destinationAccountId) || !id(request.categoryId) ||
        !Number.isSafeInteger(request.time) || !Number.isSafeInteger(request.utcOffset) ||
        !Number.isSafeInteger(request.sourceAmount) || !Number.isSafeInteger(request.destinationAmount) ||
        typeof request.comment !== 'string' || typeof request.hideAmount !== 'boolean' || !ids(request.tagIds) || !ids(request.pictureIds) ||
        (pending.receipt !== undefined && !positiveId(pending.receipt?.id))) {
        throw new Error('待确认记账记录无效，请保留原记录后核对，不能生成替代请求');
    }
}

/** Validate the financial receipt separately from attachment readback: an
 * incomplete picture response cannot turn a known created row into a new add. */
export function assertTransactionSubmissionResponse(value: TransactionInfoResponse, ledgerId: string, expectedId?: string, request?: TransactionCreateRequest): void {
    if (!value || !positiveId(value.id) || (expectedId !== undefined && value.id !== expectedId) ||
        (value.ledgerId ?? '0') !== ledgerId || !positiveId(value.timeSequenceId) || !Number.isSafeInteger(value.time) ||
        !Number.isSafeInteger(value.utcOffset) || ![TransactionType.Expense, TransactionType.Income, TransactionType.Transfer].includes(value.type) ||
        !id(value.categoryId) || !id(value.sourceAccountId) || !id(value.destinationAccountId ?? '0') ||
        !Number.isSafeInteger(value.sourceAmount) || !Number.isSafeInteger(value.destinationAmount ?? 0) ||
        typeof value.hideAmount !== 'boolean' || typeof value.editable !== 'boolean' || typeof value.comment !== 'string' || !ids(value.tagIds)) {
        throw new Error('保存结果不完整或原账本、流水标识不匹配，请保留原请求后核对');
    }
    if (!request) return;
    // The API mirrors the actual amount on both sides of an external inbound
    // transfer, while its absent source account remains the explicit zero ID.
    const inbound = request.type === TransactionType.Transfer && request.sourceAccountId === '0';
    const sourceAmount = inbound ? (request.destinationAmount || request.sourceAmount) : request.sourceAmount;
    const destinationAmount = inbound ? sourceAmount : request.destinationAmount;
    if (value.type !== request.type || value.time !== request.time || value.utcOffset !== request.utcOffset || value.categoryId !== request.categoryId ||
        value.sourceAccountId !== request.sourceAccountId || (value.destinationAccountId ?? '0') !== request.destinationAccountId ||
        value.sourceAmount !== sourceAmount || (value.destinationAmount ?? 0) !== destinationAmount || value.hideAmount !== request.hideAmount ||
        value.comment !== request.comment || !sameIds(value.tagIds, request.tagIds)) {
        throw new Error('保存回包与原确认内容不匹配，请保留原请求后核对');
    }
}

export function assertTransactionSubmissionPictures(value: TransactionInfoResponse, request: TransactionCreateRequest): void {
    const pictures = value.pictures ?? [];
    if (!Array.isArray(pictures) || pictures.some(picture => !positiveId(picture?.pictureId) || typeof picture.originalUrl !== 'string') ||
        !sameIds(pictures.map(picture => picture.pictureId), request.pictureIds)) {
        throw new Error('流水已确认创建，附件尚未完整核对；请重新读取原结果，不要再次新增');
    }
}
