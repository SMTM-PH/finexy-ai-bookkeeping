import { ScheduledTemplateFrequencyType } from '@/core/template.ts';
import { assertPeriodicSnapshot, isPeriodicStringId, type TransactionTemplateCreateRequest } from './transaction_template.ts';

export type TemplateCreationType = 1 | 2;
export type TemplateEditorInput = Omit<TransactionTemplateCreateRequest, 'clientSessionId' | 'ledgerId' | 'sourceAmount' | 'destinationAmount'> & {
    readonly ledgerId: string;
    readonly sourceAmount: number | string;
    readonly destinationAmount: number | string;
    readonly categorySelections?: { readonly expense: string; readonly income: string; readonly transfer: string };
};
export interface TemplateEditorDraft {
    readonly version: 1;
    readonly revision: string;
    readonly input: TemplateEditorInput;
}
export interface PendingTemplateCreate {
    readonly version: 1;
    readonly identity: string;
    readonly request: TransactionTemplateCreateRequest & { readonly ledgerId: string };
    readonly draftRevision: string | null;
    readonly receipt?: { readonly id: string; readonly deleted: boolean };
}
export interface TemplateCreationCapabilities {
    readonly creationContractVersion: number;
    readonly durableCreation: boolean;
    readonly payloadBinding: boolean;
    readonly deletedReceipts: boolean;
    readonly maxClientSessionIdBytes: number;
    readonly submissionScope: string;
    readonly durableCreatePath: string;
    readonly templateTypes: number[];
}

export function assertTemplateEditorDraft(value: TemplateEditorDraft, ledgerId: string, kind: TemplateCreationType): void {
    const input = value?.input;
    if (![1, 2].includes(kind) || value?.version !== 1 || typeof value.revision !== 'string' || !value.revision
        || !input || input.ledgerId !== ledgerId || input.templateType !== kind || ![2, 3, 4].includes(input.type)
        || !isPeriodicStringId(ledgerId) || typeof input.name !== 'string' || typeof input.comment !== 'string'
        || typeof input.hideAmount !== 'boolean' || !Array.isArray(input.tagIds) || input.tagIds.some(id => typeof id !== 'string')
        || !['categoryId', 'sourceAccountId', 'destinationAccountId'].every(key => typeof input[key as keyof TemplateEditorInput] === 'string')
        || ![input.sourceAmount, input.destinationAmount].every(amount => typeof amount === 'string' || (typeof amount === 'number' && Number.isFinite(amount)))
        || (input.categorySelections !== undefined && !['expense', 'income', 'transfer'].every(key => typeof input.categorySelections?.[key as keyof NonNullable<TemplateEditorInput['categorySelections']>] === 'string'))) {
        throw new Error('模板草稿损坏或不属于当前账本，请保留原记录');
    }
    // Drafts intentionally accept unfinished/invalid text; only the confirmed
    // integer API snapshot must satisfy the write contract.
}

export function assertTemplateCreateRequest(request: TransactionTemplateCreateRequest, ledgerId: string): void {
    if (!request || request.ledgerId !== ledgerId || !isPeriodicStringId(ledgerId)
        || ![1, 2].includes(request.templateType) || typeof request.name !== 'string' || !request.name.trim()
        || [...request.name].length > 64 || typeof request.comment !== 'string' || [...request.comment].length > 255
        || typeof request.clientSessionId !== 'string' || !request.clientSessionId.trim()
        || new TextEncoder().encode(request.clientSessionId).length > 128) throw new Error('模板确认内容不完整，请核对原输入');
    assertPeriodicSnapshot({ ...request, utcOffset: request.utcOffset ?? 0 });
    if (request.templateType === 2 && (!ScheduledTemplateFrequencyType.valueOf(request.scheduledFrequencyType!)
        || typeof request.scheduledFrequency !== 'string' || request.scheduledFrequency.length > 100
        || !Number.isInteger(request.utcOffset)
        || !validFrequency(request.scheduledFrequencyType!, request.scheduledFrequency)
        || [request.scheduledStartDate, request.scheduledEndDate].some(date => date !== undefined && !validDate(date))
        || (request.scheduledFrequencyType !== 0 && !request.scheduledStartDate)
        || (request.scheduledStartDate && request.scheduledEndDate && request.scheduledStartDate > request.scheduledEndDate))) {
        throw new Error('周期确认内容不完整，请核对原输入');
    }
}

function validDate(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const [year, month, day] = value.split('-').map(Number) as [number, number, number];
    const date = new Date(Date.UTC(year, month - 1, day));
    return year >= 1000 && year <= 9999 && date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

// Match the server's strict rule parser before freezing a confirmed request.
// Do not turn malformed text into a different schedule through normalization.
function validFrequency(kind: number, raw: string): boolean {
    if (kind === 0) return raw === '';
    const parts = raw.split(',');
    return parts.every(part => {
        if (!/^(0|[1-9]\d*|-[1-9]\d*)$/.test(part)) return false;
        const value = BigInt(part);
        if (value < -9223372036854775808n || value > 9223372036854775807n) return false;
        if (kind === 3) return parts.length === 1 && (value === 0n || value === 1n);
        if (kind === 1) return value >= 0n && value <= 6n;
        if (kind === 2) return value !== 0n && value >= -31n && value <= 31n;
        if (kind === 4) return value >= 101n && value <= 1231n && validDate(`2000-${String(Number(value / 100n)).padStart(2, '0')}-${String(Number(value % 100n)).padStart(2, '0')}`);
        return kind === 5 && parts.length === 1 && value > 0n;
    });
}

export function assertPendingTemplateCreate(value: PendingTemplateCreate, ledgerId: string, identity: string): void {
    if (value?.version !== 1 || value.identity !== identity
        || (value.draftRevision !== null && (typeof value.draftRevision !== 'string' || !value.draftRevision))
        || (value.receipt !== undefined && (!isPeriodicStringId(value.receipt?.id, false) || typeof value.receipt.deleted !== 'boolean'))) {
        throw new Error('模板待确认记录损坏或账号不匹配，请保留原记录');
    }
    assertTemplateCreateRequest(value.request, ledgerId);
}

export function assertTemplateCreationCapabilities(value: TemplateCreationCapabilities, kind: number): void {
    if (value?.creationContractVersion !== 1 || value.durableCreation !== true || value.payloadBinding !== true
        || value.deletedReceipts !== true || value.maxClientSessionIdBytes !== 128
        || value.submissionScope !== 'owner-creator-ledger' || value.durableCreatePath !== 'v1/transaction/templates/add_durable.json'
        || !Array.isArray(value.templateTypes) || value.templateTypes.some(type => ![1, 2].includes(type))
        || !value.templateTypes.includes(kind)) throw new Error('当前服务不支持可靠模板恢复，请升级服务；原确认请求已保留');
}
