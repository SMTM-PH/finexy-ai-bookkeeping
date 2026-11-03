/** Read-only report transport. IDs, integer money and timestamps never become JS numbers. */
export type LedgerReportType = 1 | 2 | 3 | 4;
export type LedgerReportHiddenPolicy = 'include' | 'exclude';

export interface LedgerReportIDFilter {
    readonly ids: readonly string[];
    readonly mode: 'include' | 'exclude';
    readonly includeDescendants?: boolean;
}

export interface LedgerReportTagFilter {
    readonly ids?: readonly string[] | null;
    readonly mode: 'any' | 'all' | 'notAny' | 'notAll' | 'none';
}

export interface LedgerReportQuery {
    readonly ledgerId: string;
    readonly startDate: string;
    readonly endDate: string;
    readonly timezone: string;
    readonly types?: readonly LedgerReportType[] | null;
    readonly accounts?: LedgerReportIDFilter | null;
    readonly categories?: LedgerReportIDFilter | null;
    readonly tags?: readonly LedgerReportTagFilter[] | null;
    readonly currencies?: readonly string[] | null;
    readonly hidden?: LedgerReportHiddenPolicy;
}

export interface LedgerReportTimeRange {
    readonly start: string;
    readonly endExclusive: string;
}

export interface NormalizedLedgerReportQuery extends LedgerReportQuery {
    readonly version: 1;
    readonly types: readonly LedgerReportType[];
    readonly accounts: LedgerReportIDFilter | null;
    readonly categories: LedgerReportIDFilter | null;
    readonly tags: readonly LedgerReportTagFilter[] | null;
    readonly currencies: readonly string[] | null;
    readonly hidden: LedgerReportHiddenPolicy;
    readonly utcRanges: readonly LedgerReportTimeRange[];
}

export interface LedgerReportMoney {
    readonly currency: string;
    readonly income: string;
    readonly expense: string;
    readonly net: string;
    readonly adjustment: string;
    readonly transferIn: string;
    readonly transferOut: string;
}

export interface LedgerReportLeg {
    readonly accountId: string;
    readonly accountName: string;
    readonly currency: string;
    readonly amount: string;
}

export interface LedgerReportResource {
    readonly id: string;
    readonly name: string;
    readonly parentId?: string;
    readonly hidden: boolean;
    readonly type?: number;
    readonly currency?: string;
}

export interface LedgerReportDetail {
    readonly id: string;
    readonly physicalIds: readonly string[];
    readonly type: LedgerReportType;
    readonly time: string;
    readonly sequence: string;
    readonly date: string;
    readonly categoryId: string;
    readonly categoryName: string;
    readonly source: LedgerReportLeg | null;
    readonly destination: LedgerReportLeg | null;
    readonly adjustment: string;
    readonly balanceTarget?: string;
    readonly tags: readonly LedgerReportResource[];
    readonly hidden: boolean;
    readonly comment: string;
    readonly recorderUid: string;
    readonly payerUid: string;
}

export interface LedgerReportGroup {
    readonly key: string;
    readonly name?: string;
    readonly count: number;
    readonly money: readonly LedgerReportMoney[];
}

export interface LedgerReportSnapshot {
    readonly memberRole: 1 | 2 | 3 | 4;
    readonly query: NormalizedLedgerReportQuery;
    readonly snapshotHash: string;
    readonly dataComplete: true;
    readonly matchedCount: number;
    readonly details: readonly LedgerReportDetail[];
    readonly totals: readonly LedgerReportMoney[];
    readonly trend: readonly LedgerReportGroup[];
    readonly categories: readonly LedgerReportGroup[];
    readonly tagGroups: readonly LedgerReportGroup[];
    readonly tagGroupsAdditive: false;
    readonly readableTags: readonly LedgerReportResource[];
    readonly accounts: readonly LedgerReportResource[];
    readonly categoryMetadata: readonly LedgerReportResource[];
}

export interface LedgerReportPage extends LedgerReportSnapshot {
    readonly offset: number;
    readonly returnedCount: number;
    readonly nextOffset: number | null;
    readonly detailsComplete: boolean;
    readonly exportAllowed: boolean;
}

export interface LedgerReportQueryRequest {
    readonly query: LedgerReportQuery;
    readonly offset?: number;
    readonly count?: number;
    readonly expectedSnapshotHash?: string;
}

export interface LedgerReportExportPreviewRequest {
    readonly query: LedgerReportQuery;
    readonly expectedSnapshotHash: string;
}

export interface LedgerReportExportRequest extends LedgerReportExportPreviewRequest {
    readonly previewToken: string;
    readonly confirmed: boolean;
    readonly includeHiddenAmounts?: boolean;
}

export interface LedgerReportExportPreview {
    readonly query: NormalizedLedgerReportQuery;
    readonly snapshotHash: string;
    readonly matchedCount: number;
    readonly totals: readonly LedgerReportMoney[];
    readonly amountUnit: 'minor';
    readonly includesHiddenAmounts: boolean;
    readonly expiresAt: string;
    readonly previewToken: string;
}
