import type {
    LedgerReportDetail, LedgerReportExportPreview, LedgerReportGroup, LedgerReportIDFilter,
    LedgerReportLeg, LedgerReportMoney, LedgerReportPage, LedgerReportQuery, LedgerReportResource,
    LedgerReportTagFilter, LedgerReportType, NormalizedLedgerReportQuery
} from '@/models/ledger_report.ts';

const MIN_INT64 = -(1n << 63n);
const MAX_INT64 = (1n << 63n) - 1n;
const MONEY_FIELDS = ['income', 'expense', 'net', 'adjustment', 'transferIn', 'transferOut'] as const;
const QUERY_FIELDS = ['ledgerId', 'startDate', 'endDate', 'timezone', 'types', 'accounts', 'categories', 'tags', 'currencies', 'hidden'];
const DAY = 86400000;

export class LedgerReportContractError extends Error {
    public constructor() {
        super('报告响应或查询不完整，请重新读取');
        this.name = 'LedgerReportContractError';
    }
}

function requireContract(condition: unknown): asserts condition {
    if (!condition) throw new LedgerReportContractError();
}

function object(value: unknown): Record<string, unknown> {
    requireContract(value !== null && typeof value === 'object' && !Array.isArray(value));
    return value as Record<string, unknown>;
}

function fields(value: Record<string, unknown>, allowed: readonly string[]): void {
    requireContract(Object.keys(value).every(key => allowed.includes(key)));
}

function text(value: unknown): string {
    requireContract(typeof value === 'string');
    return value;
}

function integer(value: unknown, min = 0, max = 50000): number {
    requireContract(typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max);
    return value;
}

function boolean(value: unknown): boolean {
    requireContract(typeof value === 'boolean');
    return value;
}

function decimal(value: unknown, min = MIN_INT64, max = MAX_INT64): string {
    const result = text(value);
    requireContract(result.length <= 20 && /^(?:0|[1-9]\d*|-[1-9]\d*)$/.test(result));
    const number = BigInt(result);
    requireContract(number >= min && number <= max);
    return result;
}

function id(value: unknown, zero = false): string { return decimal(value, zero ? 0n : 1n); }
function currency(value: unknown): string {
    const result = text(value);
    requireContract(/^[A-Z]{3}$/.test(result));
    return result;
}
function array(value: unknown, max = 50000): unknown[] {
    requireContract(Array.isArray(value) && value.length <= max);
    return value;
}
function compareIDs(a: string, b: string): number { return a.length - b.length || a.localeCompare(b); }
function ids(value: unknown, zero = false): string[] {
    const result = array(value, 1000).map(item => id(item, zero));
    requireContract(result.length > 0);
    return [...new Set(result)].sort(compareIDs);
}
function date(value: unknown): string {
    const result = text(value);
    requireContract(/^\d{4}-\d{2}-\d{2}$/.test(result) && result >= '1970-01-01' && result <= '9998-12-31');
    const parsed = Date.parse(`${result}T00:00:00Z`);
    requireContract(Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 10) === result);
    return result;
}
function timezone(value: unknown): string {
    const result = text(value);
    requireContract(result.length > 0 && result.length < 256 && result.trim() === result && result !== 'Local');
    try { new Intl.DateTimeFormat('en', { timeZone: result }).format(0); } catch { throw new LedgerReportContractError(); }
    return result;
}
function freeze<T>(value: T): T {
    if (value && typeof value === 'object') {
        Object.values(value).forEach(item => freeze(item));
        Object.freeze(value);
    }
    return value;
}
function idFilter(value: unknown, zero = false): LedgerReportIDFilter | null {
    if (value === undefined || value === null) return null;
    const filter = object(value);
    fields(filter, ['ids', 'mode', 'includeDescendants']);
    requireContract(filter['mode'] === 'include' || filter['mode'] === 'exclude');
    return { ids: ids(filter['ids'], zero), mode: filter['mode'], includeDescendants: filter['includeDescendants'] === undefined ? false : boolean(filter['includeDescendants']) };
}

/** Detached canonical query: safe to capture before any asynchronous request. */
export function copyLedgerReportQuery(query: LedgerReportQuery): LedgerReportQuery {
    const q = object(query);
    fields(q, [...QUERY_FIELDS, 'version', 'utcRanges']);
    if (q['version'] !== undefined) requireContract(q['version'] === 1 && Array.isArray(q['utcRanges']));
    const startDate = date(q['startDate']), endDate = date(q['endDate']);
    requireContract(endDate >= startDate && Date.parse(`${endDate}T00:00:00Z`) - Date.parse(`${startDate}T00:00:00Z`) + DAY <= 3660 * DAY);
    let types: LedgerReportType[] = [1, 2, 3, 4];
    if (q['types'] !== undefined && q['types'] !== null) {
        const input = array(q['types'], 4);
        requireContract(input.length > 0);
        types = [...new Set(input.map(item => integer(item, 1, 4) as LedgerReportType))].sort();
    }
    let tags: LedgerReportTagFilter[] | null = null;
    if (q['tags'] !== undefined && q['tags'] !== null) {
        const clauses = array(q['tags'], 20);
        requireContract(clauses.length > 0);
        const unique = new Map<string, LedgerReportTagFilter>();
        for (const value of clauses) {
            const clause = object(value);
            fields(clause, ['mode', 'ids']);
            const mode = clause['mode'];
            requireContract(mode === 'any' || mode === 'all' || mode === 'notAny' || mode === 'notAll' || mode === 'none');
            const selected = mode === 'none' ? null : ids(clause['ids']);
            if (mode === 'none') requireContract(clauses.length === 1 && (clause['ids'] === undefined || clause['ids'] === null || array(clause['ids']).length === 0));
            unique.set(`${mode}:${selected?.join(',') ?? ''}`, { mode, ids: selected });
        }
        tags = [...unique.entries()].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([, value]) => value);
    }
    let currencies: string[] | null = null;
    if (q['currencies'] !== undefined && q['currencies'] !== null) {
        const values = array(q['currencies'], 256).map(currency);
        requireContract(values.length > 0);
        currencies = [...new Set(values)].sort();
    }
    const hidden = q['hidden'] === undefined || q['hidden'] === '' ? 'exclude' : q['hidden'];
    requireContract(hidden === 'include' || hidden === 'exclude');
    return freeze({ ledgerId: id(q['ledgerId'], true), startDate, endDate, timezone: timezone(q['timezone']), types, accounts: idFilter(q['accounts']), categories: idFilter(q['categories'], true), tags, currencies, hidden });
}

export function ledgerReportQueryKey(query: LedgerReportQuery): string { return JSON.stringify(copyLedgerReportQuery(query)); }

function normalizedQuery(value: unknown, expected: LedgerReportQuery): NormalizedLedgerReportQuery {
    const q = object(value);
    fields(q, [...QUERY_FIELDS, 'version', 'utcRanges']);
    requireContract(q['version'] === 1);
    requireContract([...QUERY_FIELDS, 'version', 'utcRanges'].every(key => Object.hasOwn(q, key)));
    requireContract(Array.isArray(q['types']) && (q['hidden'] === 'include' || q['hidden'] === 'exclude'));
    const ranges = q['utcRanges'];
    const base = Object.fromEntries(QUERY_FIELDS.map(key => [key, q[key]]));
    const query = copyLedgerReportQuery(base as unknown as LedgerReportQuery);
    requireContract(ledgerReportQueryKey(query) === ledgerReportQueryKey(expected));
    // These are authoritative IANA ranges, not a client-machine offset or 24h approximation.
    const envelopeStart = BigInt(Date.parse(`${query.startDate}T00:00:00Z`) / 1000) - 172800n;
    const envelopeEnd = BigInt(Date.parse(`${query.endDate}T00:00:00Z`) / 1000) + 259200n;
    const utcRanges = array(ranges, 1000).map(value => {
        const range = object(value);
        const start = decimal(range['start'], envelopeStart, envelopeEnd);
        const endExclusive = decimal(range['endExclusive'], envelopeStart, envelopeEnd);
        requireContract(BigInt(start) < BigInt(endExclusive));
        return { start, endExclusive };
    });
    for (let i = 1; i < utcRanges.length; i++) requireContract(BigInt(utcRanges[i - 1]!.endExclusive) < BigInt(utcRanges[i]!.start));
    return { ...query, version: 1, types: query.types!, accounts: query.accounts!, categories: query.categories!, tags: query.tags!, currencies: query.currencies!, hidden: query.hidden!, utcRanges };
}

function resource(value: unknown, kind: 'account' | 'category' | 'tag'): LedgerReportResource {
    const r = object(value);
    const result: LedgerReportResource = { id: id(r['id']), name: text(r['name']), hidden: boolean(r['hidden']) };
    if (kind === 'tag') return result;
    const parentId = id(r['parentId'], true);
    const type = integer(r['type'], 1, kind === 'account' ? 2 : 3);
    if (kind === 'account') return { ...result, parentId, type, currency: type === 1 ? currency(r['currency']) : r['currency'] === undefined ? '' : text(r['currency']) };
    return { ...result, parentId, type };
}
function resources(value: unknown, kind: 'account' | 'category' | 'tag'): Map<string, LedgerReportResource> {
    const result = new Map<string, LedgerReportResource>();
    for (const item of array(value, 20000)) {
        const row = resource(item, kind);
        requireContract(!result.has(row.id));
        result.set(row.id, row);
    }
    if (kind !== 'tag') for (const row of result.values()) {
        if (row.parentId === '0') continue;
        const parent = result.get(row.parentId!);
        requireContract(parent && parent.parentId === '0' && (kind === 'account' ? row.type === 1 && parent.type === 2 : row.type === parent.type));
    }
    return result;
}
function money(value: unknown): LedgerReportMoney[] {
    const seen = new Set<string>();
    return array(value, 20000).map(value => {
        const row = object(value);
        const code = currency(row['currency']);
        requireContract(!seen.has(code));
        seen.add(code);
        const result = { currency: code, income: decimal(row['income']), expense: decimal(row['expense']), net: decimal(row['net']), adjustment: decimal(row['adjustment']), transferIn: decimal(row['transferIn']), transferOut: decimal(row['transferOut']) };
        requireContract(BigInt(result.net) === BigInt(result.income) - BigInt(result.expense));
        return result;
    });
}
function moneyKey(value: readonly LedgerReportMoney[]): string {
    return JSON.stringify([...value].sort((a, b) => a.currency.localeCompare(b.currency)));
}
type MoneyMap = Map<string, Record<typeof MONEY_FIELDS[number], bigint>>;
function addMoney(target: MoneyMap, values: readonly LedgerReportMoney[]): void {
    for (const row of values) {
        let bucket = target.get(row.currency);
        if (!bucket) {
            bucket = { income: 0n, expense: 0n, net: 0n, adjustment: 0n, transferIn: 0n, transferOut: 0n };
            target.set(row.currency, bucket);
        }
        for (const key of MONEY_FIELDS) bucket[key] += BigInt(row[key]);
    }
}
function sameMoney(actual: MoneyMap, expected: readonly LedgerReportMoney[]): void {
    requireContract(actual.size === expected.length);
    for (const row of expected) {
        const bucket = actual.get(row.currency);
        requireContract(bucket);
        for (const key of MONEY_FIELDS) requireContract(bucket[key] === BigInt(row[key]));
    }
}
function localDateFormatter(timezone: string): (seconds: string) => string {
    const formatter = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit', calendar: 'gregory', numberingSystem: 'latn' });
    return seconds => {
        const parts = formatter.formatToParts(Number(seconds) * 1000);
        return `${parts.find(part => part.type === 'year')?.value.padStart(4, '0')}-${parts.find(part => part.type === 'month')?.value}-${parts.find(part => part.type === 'day')?.value}`;
    };
}
function leg(value: unknown, accounts: Map<string, LedgerReportResource>): LedgerReportLeg | null {
    if (value === null) return null;
    const row = object(value), accountId = id(row['accountId']);
    const account = accounts.get(accountId);
    const result = { accountId, accountName: text(row['accountName']), currency: currency(row['currency']), amount: decimal(row['amount']) };
    requireContract(account && account.type === 1 && account.name === result.accountName && account.currency === result.currency);
    return result;
}
function filterMatches(filter: LedgerReportIDFilter | null | undefined, ids: readonly string[], metadata: Map<string, LedgerReportResource>): boolean {
    if (!filter) return true;
    const selected = new Set(filter.ids);
    const matches = ids.some(value => selected.has(value) || filter.includeDescendants && selected.has(metadata.get(value)?.parentId ?? ''));
    return filter.mode === 'exclude' ? !matches : matches;
}
function tagsMatch(filters: readonly LedgerReportTagFilter[] | null | undefined, tags: readonly LedgerReportResource[]): boolean {
    const selected = new Set(tags.map(tag => tag.id));
    return !filters || filters.every(filter => {
        const count = filter.ids?.filter(id => selected.has(id)).length ?? 0;
        switch (filter.mode) {
            case 'none': return tags.length === 0;
            case 'any': return count > 0;
            case 'all': return count === filter.ids!.length;
            case 'notAny': return count === 0;
            case 'notAll': return count < filter.ids!.length;
        }
    });
}

function details(value: unknown, query: NormalizedLedgerReportQuery, accounts: Map<string, LedgerReportResource>, categories: Map<string, LedgerReportResource>, readableTags: Map<string, LedgerReportResource>, maximum = 200): LedgerReportDetail[] {
    const seen = new Set<string>(), physicalSeen = new Set<string>(), dateAt = localDateFormatter(query.timezone);
    return array(value, maximum).map(value => {
        const row = object(value), logicalID = id(row['id']);
        requireContract(!seen.has(logicalID));
        seen.add(logicalID);
        const physicalIds = array(row['physicalIds'], 2).map(value => id(value));
        requireContract(physicalIds.length >= 1 && physicalIds[0] === logicalID);
        for (const physicalID of physicalIds) {
            requireContract(!physicalSeen.has(physicalID));
            physicalSeen.add(physicalID);
        }
        const type = integer(row['type'], 1, 4) as LedgerReportType;
        const time = decimal(row['time'], 0n), sequence = decimal(row['sequence'], 0n);
        requireContract(BigInt(sequence) / 1000n === BigInt(time));
        requireContract(query.utcRanges.some(range => BigInt(range.start) <= BigInt(time) && BigInt(time) < BigInt(range.endExclusive)));
        const day = date(row['date']);
        requireContract(day >= query.startDate && day <= query.endDate && day === dateAt(time));
        const categoryId = id(row['categoryId'], true), categoryName = text(row['categoryName']);
        const category = categories.get(categoryId);
        if (categoryId === '0') requireContract(categoryName === '' && (type === 1 || type === 4));
        else requireContract(category && category.parentId !== '0' && category.name === categoryName && category.type === (type === 2 ? 1 : type === 3 ? 2 : 3));
        const source = leg(row['source'], accounts), destination = leg(row['destination'], accounts);
        const adjustment = decimal(row['adjustment']);
        const balanceTarget = row['balanceTarget'] === undefined ? undefined : decimal(row['balanceTarget']);
        if (type === 1) requireContract(source && !destination && physicalIds.length === 1 && categoryId === '0' && balanceTarget === source.amount);
        else {
            requireContract(adjustment === '0' && balanceTarget === undefined);
            if (type === 4) requireContract((source || destination) && physicalIds.length === (source && destination ? 2 : 1) && (!source || !destination || source.accountId !== destination.accountId));
            else requireContract(source && !destination && physicalIds.length === 1 && categoryId !== '0');
        }
        const tags = array(row['tags'], 20000).map(value => resource(value, 'tag'));
        const tagIDs = new Set<string>();
        for (const tag of tags) {
            const known = readableTags.get(tag.id);
            requireContract(known && known.name === tag.name && known.hidden === tag.hidden && !tagIDs.has(tag.id));
            tagIDs.add(tag.id);
        }
        const hidden = boolean(row['hidden']);
        requireContract(query.types.includes(type) && (query.hidden === 'include' || !hidden));
        const accountIDs = [source?.accountId, destination?.accountId].filter((value): value is string => !!value);
        requireContract(filterMatches(query.accounts, accountIDs, accounts) && filterMatches(query.categories, [categoryId], categories) && tagsMatch(query.tags, tags));
        requireContract(!query.currencies || [source?.currency, destination?.currency].some(value => value !== undefined && query.currencies!.includes(value)));
        return { id: logicalID, physicalIds, type, time, sequence, date: day, categoryId, categoryName, source, destination, adjustment, ...(balanceTarget === undefined ? {} : { balanceTarget }), tags, hidden, comment: text(row['comment']), recorderUid: id(row['recorderUid']), payerUid: id(row['payerUid']) };
    });
}

function groups(value: unknown, kind: 'trend' | 'category' | 'tag', matched: number, query: NormalizedLedgerReportQuery, categories: Map<string, LedgerReportResource>, tags: Map<string, LedgerReportResource>): LedgerReportGroup[] {
    const seen = new Set<string>();
    return array(value).map(value => {
        const row = object(value), key = text(row['key']);
        requireContract(!seen.has(key));
        seen.add(key);
        const name = row['name'] === undefined ? undefined : text(row['name']);
        if (kind === 'trend') requireContract(date(key) >= query.startDate && key <= query.endDate && !name);
        else if (kind === 'category') requireContract(key === '0' ? !name : categories.has(id(key)) && categories.get(key)!.name === (name ?? ''));
        else requireContract(key === 'none' ? !name : tags.has(id(key)) && tags.get(key)!.name === (name ?? ''));
        return { key, ...(name === undefined ? {} : { name }), count: integer(row['count'], 1, matched), money: money(row['money']) };
    });
}
function partitionGroups(values: readonly LedgerReportGroup[], matched: number, totals: readonly LedgerReportMoney[]): void {
    requireContract(values.reduce((sum, group) => sum + group.count, 0) === matched);
    const sum: MoneyMap = new Map();
    for (const group of values) addMoney(sum, group.money);
    sameMoney(sum, totals);
}
function detailMoney(row: LedgerReportDetail): LedgerReportMoney[] {
    const result: MoneyMap = new Map();
    for (const [side, leg] of [['source', row.source], ['destination', row.destination]] as const) {
        if (!leg) continue;
        const bucket: LedgerReportMoney = { currency: leg.currency, income: row.type === 2 ? leg.amount : '0', expense: row.type === 3 ? leg.amount : '0', net: (row.type === 2 ? BigInt(leg.amount) : row.type === 3 ? -BigInt(leg.amount) : 0n).toString(), adjustment: row.type === 1 ? row.adjustment : '0', transferIn: row.type === 4 && side === 'destination' ? leg.amount : '0', transferOut: row.type === 4 && side === 'source' ? leg.amount : '0' };
        addMoney(result, [bucket]);
    }
    return [...result.entries()].map(([currency, bucket]) => ({ currency, income: bucket.income.toString(), expense: bucket.expense.toString(), net: bucket.net.toString(), adjustment: bucket.adjustment.toString(), transferIn: bucket.transferIn.toString(), transferOut: bucket.transferOut.toString() }));
}
function verifyDetailGroups(rows: readonly LedgerReportDetail[], values: readonly LedgerReportGroup[], kind: 'trend' | 'category' | 'tag', complete: boolean): void {
    const collected = new Map<string, { count: number; money: MoneyMap }>();
    const actualGroups = new Map(values.map(value => [value.key, value]));
    for (const row of rows) {
        const keys = kind === 'trend' ? [row.date] : kind === 'category' ? [row.categoryId] : row.tags.length ? row.tags.map(tag => tag.id) : ['none'];
        for (const key of keys) {
            const group = collected.get(key) ?? { count: 0, money: new Map() };
            group.count++;
            addMoney(group.money, detailMoney(row));
            collected.set(key, group);
        }
    }
    if (complete) requireContract(collected.size === values.length);
    for (const [key, group] of collected) {
        const actual = actualGroups.get(key);
        requireContract(actual && (complete ? actual.count === group.count : actual.count >= group.count));
        if (complete) sameMoney(group.money, actual.money);
    }
}

/** Revalidate accumulated pages against their common immutable baseline. A complete
 * list can exceed the HTTP page limit; its original-currency totals must still agree. */
export function validateLedgerReportAccumulatedDetails(value: unknown, page: LedgerReportPage, complete: boolean): readonly LedgerReportDetail[] {
    const rows = details(value, page.query, resources(page.accounts, 'account'), resources(page.categoryMetadata, 'category'), resources(page.readableTags, 'tag'), 50000);
    requireContract(complete ? rows.length === page.matchedCount : rows.length < page.matchedCount);
    for (let i = 1; i < rows.length; i++) {
        const before = rows[i - 1]!, after = rows[i]!;
        requireContract(BigInt(before.sequence) > BigInt(after.sequence) || before.sequence === after.sequence && BigInt(before.id) > BigInt(after.id));
    }
    verifyDetailGroups(rows, page.trend, 'trend', complete);
    verifyDetailGroups(rows, page.categories, 'category', complete);
    verifyDetailGroups(rows, page.tagGroups, 'tag', complete);
    if (complete) {
        const calculated: MoneyMap = new Map();
        rows.forEach(row => addMoney(calculated, detailMoney(row)));
        sameMoney(calculated, page.totals);
    }
    return freeze(rows);
}

/** Validate the full baseline and this transport page; never recompute a total from a partial page. */
export function validateLedgerReportPage(value: unknown, requestQuery: LedgerReportQuery, offset: number, count = 50, expectedHash?: string): LedgerReportPage {
    integer(offset); integer(count, 1, 200);
    if (expectedHash !== undefined && expectedHash !== '') requireContract(/^[0-9a-f]{64}$/.test(expectedHash));
    requireContract(offset === 0 || !!expectedHash);
    const page = object(value), query = normalizedQuery(page['query'], requestQuery);
    const snapshotHash = text(page['snapshotHash']);
    requireContract(/^[0-9a-f]{64}$/.test(snapshotHash) && (!expectedHash || expectedHash === snapshotHash));
    requireContract(page['dataComplete'] === true && page['tagGroupsAdditive'] === false);
    const matchedCount = integer(page['matchedCount']);
    requireContract(offset <= matchedCount && integer(page['offset']) === offset);
    const accounts = resources(page['accounts'], 'account'), categories = resources(page['categoryMetadata'], 'category'), readableTags = resources(page['readableTags'], 'tag');
    for (const selected of query.accounts?.ids ?? []) requireContract(accounts.has(selected));
    for (const selected of query.categories?.ids ?? []) requireContract(selected === '0' || categories.has(selected));
    for (const filter of query.tags ?? []) for (const selected of filter.ids ?? []) requireContract(readableTags.has(selected));
    const rows = details(page['details'], query, accounts, categories, readableTags);
    requireContract(integer(page['returnedCount'], 0, 200) === rows.length && rows.length === Math.min(count, matchedCount - offset));
    const next = offset + rows.length < matchedCount ? offset + rows.length : null;
    requireContract(page['nextOffset'] === next);
    const complete = offset === 0 && rows.length === matchedCount;
    requireContract(page['detailsComplete'] === complete);
    for (let i = 1; i < rows.length; i++) {
        const before = rows[i - 1]!, after = rows[i]!;
        requireContract(BigInt(before.sequence) > BigInt(after.sequence) || before.sequence === after.sequence && BigInt(before.id) > BigInt(after.id));
    }
    const totals = money(page['totals']);
    requireContract((matchedCount === 0) === (totals.length === 0));
    const trend = groups(page['trend'], 'trend', matchedCount, query, categories, readableTags);
    const categoryGroups = groups(page['categories'], 'category', matchedCount, query, categories, readableTags);
    const tagGroups = groups(page['tagGroups'], 'tag', matchedCount, query, categories, readableTags);
    partitionGroups(trend, matchedCount, totals); partitionGroups(categoryGroups, matchedCount, totals);
    requireContract((matchedCount === 0) === (tagGroups.length === 0));
    requireContract(tagGroups.reduce((sum, group) => sum + group.count, 0) >= matchedCount);
    for (const group of tagGroups) requireContract(group.money.every(row => totals.some(total => total.currency === row.currency)));
    verifyDetailGroups(rows, trend, 'trend', complete); verifyDetailGroups(rows, categoryGroups, 'category', complete); verifyDetailGroups(rows, tagGroups, 'tag', complete);
    if (complete) {
        const calculated: MoneyMap = new Map();
        rows.forEach(row => addMoney(calculated, detailMoney(row)));
        sameMoney(calculated, totals);
    }
    return freeze({ memberRole: integer(page['memberRole'], 1, 4) as LedgerReportPage['memberRole'], query, snapshotHash, dataComplete: true, matchedCount, details: rows, totals, trend, categories: categoryGroups, tagGroups, tagGroupsAdditive: false, readableTags: [...readableTags.values()], accounts: [...accounts.values()], categoryMetadata: [...categories.values()], offset, returnedCount: rows.length, nextOffset: next, detailsComplete: complete, exportAllowed: boolean(page['exportAllowed']) });
}

export function validateLedgerReportPreview(value: unknown, page: LedgerReportPage): LedgerReportExportPreview {
    const preview = object(value), query = normalizedQuery(preview['query'], copyLedgerReportQuery({ ledgerId: page.query.ledgerId, startDate: page.query.startDate, endDate: page.query.endDate, timezone: page.query.timezone, types: page.query.types, accounts: page.query.accounts, categories: page.query.categories, tags: page.query.tags, currencies: page.query.currencies, hidden: page.query.hidden }));
    requireContract(page.exportAllowed && preview['snapshotHash'] === page.snapshotHash && integer(preview['matchedCount']) === page.matchedCount && preview['amountUnit'] === 'minor' && preview['includesHiddenAmounts'] === (page.query.hidden === 'include'));
    requireContract(JSON.stringify(query.utcRanges) === JSON.stringify(page.query.utcRanges));
    const totals = money(preview['totals']);
    requireContract(moneyKey(totals) === moneyKey(page.totals));
    const previewToken = text(preview['previewToken']);
    requireContract(previewToken.length > 0 && previewToken.length <= 4096 && /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(previewToken));
    return freeze({ query, snapshotHash: page.snapshotHash, matchedCount: page.matchedCount, totals, amountUnit: 'minor', includesHiddenAmounts: query.hidden === 'include', expiresAt: decimal(preview['expiresAt'], 1n), previewToken });
}

/** Finexy's two-decimal major-unit contract, including both signed int64 endpoints. */
export function formatLedgerReportAmount(minor: string): string {
    const amount = BigInt(decimal(minor));
    const absolute = amount < 0n ? -amount : amount;
    return `${amount < 0n ? '-' : ''}${absolute / 100n}.${(absolute % 100n).toString().padStart(2, '0')}`;
}
