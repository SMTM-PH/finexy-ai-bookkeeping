import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

export async function loadLedgerReportFixture() {
    const fixture = JSON.parse(await readFile(new URL('./v1.json', import.meta.url), 'utf8'));
    assert.equal(fixture.schemaVersion, 1);
    assert.equal(fixture.synthetic, true);
    assert.equal(fixture.amountUnit, 'minor');
    assert.equal(fixture.timeUnit, 'unix_seconds');
    assert.equal(fixture.timezone, fixture.query.timezone);
    const decimalKeys = new Set(['id', 'uid', 'ledgerId', 'parentId', 'categoryId', 'accountId', 'amount', 'balance', 'balanceTime', 'transactionTime', 'relatedId', 'relatedAccountId', 'relatedAccountAmount', 'balanceTarget', 'delta', 'income', 'expense', 'net', 'adjustment', 'transferIn', 'transferOut', 'sourceAmount', 'destinationAmount']);
    const decimalLists = new Set(['ids', 'tagIds', 'detailIds', 'physicalIds', 'readableTagIds']);
    const decimal = value => {
        assert.equal(typeof value, 'string', 'fixture IDs, amounts and timestamps must remain strings');
        assert.match(value, /^-?(0|[1-9]\d*)$/);
        assert.equal(BigInt(value).toString(), value);
        assert(BigInt(value) >= -(1n << 63n) && BigInt(value) < (1n << 63n));
    };
    const walk = object => {
        for (const [key, value] of Object.entries(object)) {
            if (decimalKeys.has(key) && typeof value !== 'object') decimal(value);
            else if (decimalLists.has(key)) value.forEach(decimal);
            else if (value !== null && typeof value === 'object') walk(value);
        }
    };
    walk(fixture);
    for (const name of ['accounts', 'categories', 'tags', 'transactions']) {
        assert.equal(new Set(fixture[name].map(row => row.id)).size, fixture[name].length);
    }
    assert(fixture.cases.length >= 16);
    return fixture;
}

// JSON request DTOs still use numeric money fields. Emit validated BigInt
// decimals directly rather than converting them through IEEE-754 numbers.
export function encodeLedgerReportAPI(value) {
    if (typeof value === 'bigint') return value.toString();
    if (Array.isArray(value)) return `[${value.map(encodeLedgerReportAPI).join(',')}]`;
    if (value && typeof value === 'object') return `{${Object.entries(value).filter(([, v]) => v !== undefined).map(([k, v]) => `${JSON.stringify(k)}:${encodeLedgerReportAPI(v)}`).join(',')}}`;
    if (typeof value === 'number') assert(Number.isSafeInteger(value));
    return JSON.stringify(value);
}
