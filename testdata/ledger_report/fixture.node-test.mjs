import assert from 'node:assert/strict';
import test from 'node:test';
import { encodeLedgerReportAPI, loadLedgerReportFixture } from './fixture.mjs';

test('versioned report fixture has complete source references and shared query oracles', async () => {
    const fixture = await loadLedgerReportFixture();
    const accountIDs = new Set(fixture.accounts.map(row => row.id));
    const categoryIDs = new Set(fixture.categories.map(row => row.id));
    const tagIDs = new Set(fixture.tags.map(row => row.id));
    const transactionIDs = new Set(fixture.transactions.map(row => row.id));
    for (const row of fixture.transactions) {
        assert(accountIDs.has(row.accountId));
        if (row.categoryId) assert(categoryIDs.has(row.categoryId));
        if (row.relatedId) assert(transactionIDs.has(row.relatedId));
        if (row.relatedAccountId) assert(accountIDs.has(row.relatedAccountId));
        for (const id of row.tagIds || []) assert(tagIDs.has(id));
    }
    for (const queryCase of fixture.cases) {
        for (const id of queryCase.detailIds) assert(transactionIDs.has(id));
    }
    assert.equal(new Set(fixture.cases.map(queryCase => queryCase.name)).size, fixture.cases.length);
    assert.notEqual(fixture.variants.historicalAdjustment.balanceTarget, fixture.variants.historicalAdjustment.delta);
});

test('API request encoder preserves integer money beyond JavaScript precision and string IDs', () => {
    const encoded = encodeLedgerReportAPI({ id: '9007199254740993', amount: 9007199254740993n, legs: [-9223372036854775808n], utcOffset: 0, memo: '=1\n"quoted"', omitted: undefined });
    assert.equal(encoded, '{"id":"9007199254740993","amount":9007199254740993,"legs":[-9223372036854775808],"utcOffset":0,"memo":"=1\\n\\"quoted\\""}');
});

test('API request encoder rejects already-rounded numbers and noninteger values', () => {
    for (const amount of [Number.MAX_SAFE_INTEGER + 1, 1.5, NaN, Infinity]) {
        assert.throws(() => encodeLedgerReportAPI({ amount }), assert.AssertionError);
    }
});
