import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { loadLedgerReportFixture } from '../../testdata/ledger_report/fixture.mjs';

// The caller owns HTTP serialization, target isolation and credentials. Actors
// and financial fixture responses stay in memory; this helper writes no files.
export async function seedLedgerReportWebFixture(api, password) {
    assert.equal(typeof password, 'string');
    assert(password.length >= 16);
    const fixture = await loadLedgerReportFixture();
    const register = async () => {
        const username = `web_report_${randomBytes(7).toString('hex')}`;
        const registered = await api('register.json', null, { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic report web QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
        assert.equal(typeof registered.token, 'string');
        return { username, token: registered.token };
    };
    const owner = await register();
    const admin = await register();
    const member = await register();
    const viewer = await register();
    const outsider = await register();
    const suffix = randomBytes(5).toString('hex');
    const ledgerA = await api('v1/ledger/create.json', owner, { type: 1, name: `Synthetic web report A ${suffix}` });
    const ledgerB = await api('v1/ledger/create.json', owner, { type: 1, name: `Synthetic web report B ${suffix}` });
    const memberIDs = new Map();
    for (const [actor, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner, { ledgerId: ledgerA.id, inviteeName: actor.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', actor, { token: invitation.token });
        const me = (await api(`v1/ledger/member/list.json?ledgerId=${ledgerA.id}`, actor)).find(row => row.isCurrentUser);
        assert(me);
        memberIDs.set(actor, me.id);
        if (role === 2) await api('v1/ledger/member/change_role.json', owner, { ledgerId: ledgerA.id, memberId: me.id, role });
    }
    const { ledgerId: fixtureLedger, ...period } = fixture.query;
    const ledgerIDs = new Map([[fixtureLedger, ledgerA.id], ['12', ledgerB.id], ['0', '0']]);
    const categories = new Map();
    const tags = new Map();
    const accounts = new Map();
    const transactions = new Map();
    for (const row of fixture.categories.filter(row => !row.databaseOnly)) {
        categories.set(row.id, await api('v1/transaction/categories/add.json', owner, { name: row.name, type: row.type, parentId: row.parentId ? categories.get(row.parentId).id : '0', icon: '1', color: '000000' }));
    }
    for (const row of fixture.tags.filter(row => !row.databaseOnly)) {
        tags.set(row.id, await api('v1/transaction/tags/add.json', owner, { name: row.name }));
    }
    const accountRequest = row => ({ ledgerId: ledgerIDs.get(row.ledgerId), name: row.name, currency: row.currency, balance: BigInt(row.balance), balanceTime: BigInt(row.balanceTime || '946684800'), category: 1, type: row.type, icon: '1', color: '000000' });
    for (const row of fixture.accounts.filter(row => !row.databaseOnly && !row.parentId)) {
        const children = fixture.accounts.filter(child => child.parentId === row.id);
        const created = await api('v1/accounts/add.json', owner, { ...accountRequest(row), ...(children.length ? { subAccounts: children.map(accountRequest) } : {}) });
        accounts.set(row.id, created);
        for (const child of children) {
            const actual = created.subAccounts.find(account => account.name === child.name && account.currency === child.currency);
            assert(actual);
            accounts.set(child.id, actual);
        }
    }
    for (const row of fixture.transactions) {
        if (row.generatedByAccount || (row.type === 5 && row.relatedId)) continue;
        const incoming = row.type === 5;
        const partner = row.relatedId ? fixture.transactions.find(other => other.id === row.relatedId) : null;
        const tagIDs = [...new Set([...(row.tagIds || []), ...(partner?.tagIds || [])])];
        const created = await api('v1/transactions/add.json', owner, {
            ledgerId: ledgerIDs.get(row.ledgerId), type: incoming ? 4 : row.type,
            categoryId: row.categoryId ? categories.get(row.categoryId).id : '0',
            sourceAccountId: incoming ? '0' : accounts.get(row.accountId).id,
            sourceAmount: incoming ? 0n : BigInt(row.amount),
            ...((row.type === 4 || incoming) ? { destinationAccountId: incoming ? accounts.get(row.accountId).id : (row.relatedAccountId ? accounts.get(row.relatedAccountId).id : '0'), destinationAmount: incoming ? BigInt(row.amount) : BigInt(row.relatedAccountAmount || '0') } : {}),
            time: BigInt(row.transactionTime), utcOffset: 0, clientSessionId: randomBytes(16).toString('hex'),
            tagIds: tagIDs.map(id => tags.get(id).id), hideAmount: row.hideAmount || false, comment: row.comment || '',
        });
        transactions.set(row.id, created);
    }
    for (const row of fixture.accounts.filter(row => row.hidden && !row.databaseOnly)) {
        await api('v1/accounts/hide.json', owner, { id: accounts.get(row.id).id, hidden: true });
    }
    // Recover server-allocated adjustment and paired physical IDs from the real
    // production report, keeping the JSON fixture's stable IDs as lookup keys.
    const initialReport = await api('v1/reports/query.json', owner, { query: { ...period, ledgerId: ledgerA.id } });
    assert.equal(initialReport.dataComplete, true);
    for (const row of fixture.transactions) {
        if (row.generatedByAccount) {
            const detail = initialReport.details.find(detail => detail.type === row.type && detail.source?.accountId === accounts.get(row.accountId).id);
            assert(detail);
            transactions.set(row.id, detail);
        } else if (row.type === 5 && row.relatedId) {
            const source = transactions.get(row.relatedId);
            assert(source);
            const detail = initialReport.details.find(detail => detail.id === source.id);
            const partnerID = detail?.physicalIds.find(id => id !== source.id);
            assert(partnerID);
            transactions.set(row.id, { id: partnerID });
        }
    }
    return { fixture, owner, admin, member, viewer, outsider, ledgerA, ledgerB, memberIDs, categories, tags, accounts, transactions, ledgerIDs, period, initialReport };
}

export function parseReportCSV(text) {
    const rows = []; let row = []; let cell = ''; let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (quoted) {
            if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; }
            else if (c === '"') quoted = false;
            else cell += c;
        } else if (c === '"' && cell === '') quoted = true;
        else if (c === ',') { row.push(cell); cell = ''; }
        else if (c === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
        else if (c !== '\r') cell += c;
    }
    assert.equal(quoted, false);
    if (cell || row.length) { row.push(cell); rows.push(row); }
    const header = rows.shift();
    assert(Array.isArray(header) && header.length > 0);
    assert.equal(new Set(header).size, header.length);
    return rows.map(values => { assert.equal(values.length, header.length); return Object.fromEntries(header.map((name, i) => [name, values[i]])); });
}

export function totalReportCSV(rows) {
    const result = new Map();
    for (const row of rows) for (const side of ['source', 'destination']) {
        const currency = row[`${side}_currency`]; if (!currency) continue;
        if (!result.has(currency)) result.set(currency, { currency, income: 0n, expense: 0n, net: 0n, adjustment: 0n, transferIn: 0n, transferOut: 0n });
        const money = result.get(currency); const amount = BigInt(row[`${side}_amount_minor`]);
        if (row.type === '1') money.adjustment += BigInt(row.adjustment_minor);
        if (row.type === '2') money.income += amount;
        if (row.type === '3') money.expense += amount;
        if (row.type === '4') money[side === 'source' ? 'transferOut' : 'transferIn'] += amount;
    }
    return [...result.values()].sort((a, b) => a.currency.localeCompare(b.currency)).map(money => {
        money.net = money.income - money.expense;
        return Object.fromEntries(Object.entries(money).map(([key, value]) => [key, typeof value === 'bigint' ? value.toString() : value]));
    });
}
