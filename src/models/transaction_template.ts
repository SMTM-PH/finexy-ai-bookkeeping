import { type TextualYearMonthDay } from '@/core/datetime.ts';
import { TransactionType } from '@/core/transaction.ts';
import { ScheduledTemplateFrequencyType, TemplateType } from '@/core/template.ts';

import { Transaction, type TransactionInfoResponse } from './transaction.ts';

export class TransactionTemplate extends Transaction implements TransactionTemplateInfoResponse {
    public ledgerId = '0';
    public templateType: number;
    public name: string;
    public scheduledFrequencyType?: number;
    public scheduledFrequency?: string;
    public scheduledStartDate?: TextualYearMonthDay;
    public scheduledEndDate?: TextualYearMonthDay;
    public scheduledAt?: number;
    public nextScheduledTime?: number;
    public displayOrder: number;
    public hidden: boolean;

    private constructor(id: string, templateType: number, name: string, type: number, categoryId: string, timeZone: string | undefined, utcOffset: number, sourceAccountId: string, destinationAccountId: string, sourceAmount: number, destinationAmount: number, hideAmount: boolean, scheduledFrequencyType: number | undefined, scheduledFrequency: string | undefined, scheduledStartDate: TextualYearMonthDay | undefined, scheduledEndDate: TextualYearMonthDay | undefined, scheduledAt: number | undefined, nextScheduledTime: number | undefined, tagIds: string[], comment: string, editable: boolean, displayOrder: number, hidden: boolean) {
        super(id, '', type, categoryId, 0, timeZone, utcOffset, sourceAccountId, destinationAccountId, sourceAmount, destinationAmount, hideAmount, tagIds, comment, editable);
        this.templateType = templateType;
        this.name = name;
        this.scheduledFrequencyType = scheduledFrequencyType;
        this.scheduledFrequency = scheduledFrequency;
        this.scheduledStartDate = scheduledStartDate;
        this.scheduledEndDate = scheduledEndDate;
        this.scheduledAt = scheduledAt;
        this.nextScheduledTime = nextScheduledTime;
        this.displayOrder = displayOrder;
        this.hidden = hidden;
    }

    public fillFrom(other: TransactionTemplate): void {
        this.ledgerId = other.ledgerId;
        this.templateType = other.templateType;
        this.name = other.name;

        if (this.templateType === TemplateType.Schedule.type) {
            this.scheduledFrequencyType = other.scheduledFrequencyType;
            this.scheduledFrequency = other.scheduledFrequency;
            this.scheduledStartDate = other.scheduledStartDate;
            this.scheduledEndDate = other.scheduledEndDate;
            this.utcOffset = other.utcOffset;
            this.timeZone = undefined;
        }
    }

    public toTemplateCreateRequest(clientSessionId: string): TransactionTemplateCreateRequest {
        return {
            ledgerId: this.ledgerId,
            templateType: this.templateType,
            name: this.name,
            type: this.type,
            categoryId: this.getCategoryId(),
            sourceAccountId: this.type === TransactionType.Transfer ? (this.sourceAccountId || '0') : this.sourceAccountId,
            destinationAccountId: this.type === TransactionType.Transfer ? this.destinationAccountId : '0',
            sourceAmount: this.sourceAmount,
            destinationAmount: this.type === TransactionType.Transfer ? this.destinationAmount : 0,
            hideAmount: this.hideAmount,
            tagIds: [...this.tagIds],
            comment: this.comment,
            scheduledFrequencyType: this.templateType === TemplateType.Schedule.type ? this.scheduledFrequencyType : undefined,
            scheduledFrequency: this.templateType === TemplateType.Schedule.type ? this.scheduledFrequency : undefined,
            scheduledStartDate: this.templateType === TemplateType.Schedule.type && this.scheduledStartDate ? this.scheduledStartDate : undefined,
            scheduledEndDate: this.templateType === TemplateType.Schedule.type && this.scheduledEndDate ? this.scheduledEndDate : undefined,
            utcOffset: this.templateType === TemplateType.Schedule.type ? this.utcOffset : undefined,
            clientSessionId: clientSessionId
        };
    }

    public toTemplateModifyRequest(): TransactionTemplateModifyRequest {
        return {
            ledgerId: this.ledgerId,
            id: this.id,
            name: this.name,
            type: this.type,
            categoryId: this.getCategoryId(),
            sourceAccountId: this.type === TransactionType.Transfer ? (this.sourceAccountId || '0') : this.sourceAccountId,
            destinationAccountId: this.type === TransactionType.Transfer ? this.destinationAccountId : '0',
            sourceAmount: this.sourceAmount,
            destinationAmount: this.type === TransactionType.Transfer ? this.destinationAmount : 0,
            hideAmount: this.hideAmount,
            tagIds: [...this.tagIds],
            comment: this.comment,
            scheduledFrequencyType: this.templateType === TemplateType.Schedule.type ? this.scheduledFrequencyType : undefined,
            scheduledFrequency: this.templateType === TemplateType.Schedule.type ? this.scheduledFrequency : undefined,
            scheduledStartDate: this.templateType === TemplateType.Schedule.type && this.scheduledStartDate ? this.scheduledStartDate : undefined,
            scheduledEndDate: this.templateType === TemplateType.Schedule.type && this.scheduledEndDate ? this.scheduledEndDate : undefined,
            utcOffset: this.templateType === TemplateType.Schedule.type ? this.utcOffset : undefined
        };
    }

    public static createNewTransactionTemplate(transaction: Transaction, ledgerId = '0'): TransactionTemplate {
        if (!isPeriodicStringId(ledgerId)) throw new Error('模板账本标识无效');
        const template = new TransactionTemplate(
            transaction.id,
            0, // templateType
            '', // name
            transaction.type,
            transaction.categoryId,
            transaction.timeZone,
            transaction.utcOffset,
            transaction.sourceAccountId,
            transaction.destinationAccountId,
            transaction.sourceAmount,
            transaction.destinationAmount,
            transaction.hideAmount,
            undefined, // scheduledFrequencyType
            undefined, // scheduledFrequency
            undefined, // scheduledStartDate
            undefined, // scheduledEndDate
            undefined, // scheduledAt
            undefined, // nextScheduledTime
            [...transaction.tagIds],
            transaction.comment,
            true,
            0,
            false
        );
        template.ledgerId = ledgerId;
        return template;
    }

    public static ofTemplate(templateResponse: TransactionTemplateInfoResponse, expectedLedgerId = templateResponse?.ledgerId ?? '0'): TransactionTemplate {
        if (!templateResponse || !isPeriodicStringId(expectedLedgerId)
            || !isPeriodicStringId(templateResponse.id, false)
            || (templateResponse.ledgerId ?? '0') !== expectedLedgerId
            || !TemplateType.valueOf(templateResponse.templateType)
            || typeof templateResponse.name !== 'string' || !templateResponse.name.trim()
            || typeof templateResponse.editable !== 'boolean' || typeof templateResponse.hidden !== 'boolean'
            || !Number.isSafeInteger(templateResponse.displayOrder)) {
            throw new Error('模板响应不完整或账本归属不匹配，请重试');
        }
        // The transaction response omits zero-valued destination fields for
        // ordinary income/expense templates. Transfers still require both sides.
        const destinationAccountId = templateResponse.type !== TransactionType.Transfer && templateResponse.destinationAccountId === undefined ? '0' : templateResponse.destinationAccountId;
        const destinationAmount = templateResponse.type !== TransactionType.Transfer && templateResponse.destinationAmount === undefined ? 0 : templateResponse.destinationAmount;
        assertPeriodicSnapshot({ ...templateResponse, categoryId: templateResponse.categoryId, destinationAccountId, destinationAmount });
        if (templateResponse.templateType === TemplateType.Schedule.type
            && (typeof templateResponse.scheduledFrequencyType !== 'number'
                || !ScheduledTemplateFrequencyType.valueOf(templateResponse.scheduledFrequencyType)
                || typeof templateResponse.scheduledFrequency !== 'string'
                || [templateResponse.scheduledAt, templateResponse.nextScheduledTime].some(value => value !== undefined && !Number.isSafeInteger(value))
                || [templateResponse.scheduledStartDate, templateResponse.scheduledEndDate].some(value => value != null && !/^\d{4}-\d{2}-\d{2}$/.test(value)))) {
            throw new Error('周期模板响应不完整，请重试');
        }
        const template = new TransactionTemplate(
            templateResponse.id,
            templateResponse.templateType,
            templateResponse.name,
            templateResponse.type,
            templateResponse.categoryId,
            undefined, // only in new transaction template
            templateResponse.utcOffset ?? 0,
            templateResponse.sourceAccountId,
            destinationAccountId,
            templateResponse.sourceAmount,
            destinationAmount,
            templateResponse.hideAmount,
            templateResponse.scheduledFrequencyType,
            templateResponse.scheduledFrequency,
            templateResponse.scheduledStartDate ?? undefined,
            templateResponse.scheduledEndDate ?? undefined,
            templateResponse.scheduledAt,
            templateResponse.nextScheduledTime,
            [...templateResponse.tagIds],
            templateResponse.comment,
            templateResponse.editable,
            templateResponse.displayOrder,
            templateResponse.hidden
        );
        template.ledgerId = expectedLedgerId;
        return template;
    }

    public static ofMultiTemplates(templateResponses: TransactionTemplateInfoResponse[], expectedLedgerId = '0'): TransactionTemplate[] {
        const templates: TransactionTemplate[] = [];

        for (const templateResponse of templateResponses) {
            templates.push(TransactionTemplate.ofTemplate(templateResponse, expectedLedgerId));
        }

        return templates;
    }
}

export interface TransactionTemplateCreateRequest {
    readonly ledgerId?: string;
    readonly templateType: number;
    readonly name: string;
    readonly type: number;
    readonly categoryId: string;
    readonly sourceAccountId: string;
    readonly destinationAccountId: string;
    readonly sourceAmount: number;
    readonly destinationAmount: number;
    readonly hideAmount: boolean;
    readonly tagIds: string[];
    readonly comment: string;
    readonly scheduledFrequencyType?: number;
    readonly scheduledFrequency?: string;
    readonly scheduledStartDate?: string;
    readonly scheduledEndDate?: string;
    readonly utcOffset?: number;
    readonly clientSessionId: string;
}

export interface TransactionTemplateModifyRequest {
    readonly ledgerId?: string;
    readonly id: string;
    readonly name: string;
    readonly type: number;
    readonly categoryId: string;
    readonly sourceAccountId: string;
    readonly destinationAccountId: string;
    readonly sourceAmount: number;
    readonly destinationAmount: number;
    readonly hideAmount: boolean;
    readonly tagIds: string[];
    readonly comment: string;
    readonly scheduledFrequencyType?: number;
    readonly scheduledFrequency?: string;
    readonly scheduledStartDate?: string;
    readonly scheduledEndDate?: string;
    readonly utcOffset?: number;
}

export interface TransactionTemplateHideRequest {
    readonly ledgerId?: string;
    readonly id: string;
    readonly hidden: boolean;
}

export interface TransactionTemplateMoveRequest {
    readonly ledgerId?: string;
    readonly newDisplayOrders: TransactionTemplateNewDisplayOrderRequest[];
}

export interface TransactionTemplateNewDisplayOrderRequest {
    readonly id: string;
    readonly displayOrder: number;
}

export interface TransactionTemplateDeleteRequest {
    readonly ledgerId?: string;
    readonly id: string;
}

/** IDs retain their exact decimal representation at every API boundary. */
export function isPeriodicStringId(value: unknown, allowZero = true): value is string {
    return typeof value === 'string' && (allowZero ? /^(0|[1-9]\d*)$/ : /^[1-9]\d*$/).test(value)
        && value.length <= 19 && BigInt(value) <= 9223372036854775807n;
}

export function assertPeriodicSnapshot(snapshot: Pick<TransactionTemplateInfoResponse, 'type' | 'categoryId' | 'sourceAccountId' | 'destinationAccountId' | 'sourceAmount' | 'destinationAmount' | 'utcOffset' | 'hideAmount' | 'tagIds' | 'comment'>): void {
    const amountValid = (amount: unknown) => typeof amount === 'number' && Number.isSafeInteger(amount) && Math.abs(amount) <= 9999999999999;
    if (!snapshot || ![TransactionType.Income, TransactionType.Expense, TransactionType.Transfer].includes(snapshot.type)
        || !isPeriodicStringId(snapshot.categoryId, false) || !isPeriodicStringId(snapshot.sourceAccountId, false)
        || !isPeriodicStringId(snapshot.destinationAccountId) || !amountValid(snapshot.sourceAmount) || !amountValid(snapshot.destinationAmount)
        || !Number.isInteger(snapshot.utcOffset) || snapshot.utcOffset < -720 || snapshot.utcOffset > 840
        || typeof snapshot.hideAmount !== 'boolean' || typeof snapshot.comment !== 'string'
        || !Array.isArray(snapshot.tagIds) || snapshot.tagIds.length > 10
        || snapshot.tagIds.some(id => !isPeriodicStringId(id, false)) || new Set(snapshot.tagIds).size !== snapshot.tagIds.length
        || (snapshot.type === TransactionType.Transfer
            ? snapshot.destinationAccountId === '0' || snapshot.destinationAccountId === snapshot.sourceAccountId || snapshot.sourceAmount < 0 || snapshot.destinationAmount < 0
            : snapshot.destinationAccountId !== '0' || snapshot.destinationAmount !== 0)) {
        throw new Error('周期记录的账户、金额或分类快照不完整，请重试');
    }
}

export interface TransactionTemplateInfoResponse extends TransactionInfoResponse {
    readonly templateType: number;
    readonly name: string;
    readonly scheduledFrequencyType?: number;
    readonly scheduledFrequency?: string;
    readonly scheduledStartDate?: TextualYearMonthDay;
    readonly scheduledEndDate?: TextualYearMonthDay;
    readonly scheduledAt?: number;
    readonly nextScheduledTime?: number;
    readonly displayOrder: number;
    readonly hidden: boolean;
}
