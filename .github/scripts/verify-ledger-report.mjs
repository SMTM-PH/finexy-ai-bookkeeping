import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { request as httpRequest } from 'node:http';
import { loadLedgerReportFixture, encodeLedgerReportAPI } from '../../testdata/ledger_report/fixture.mjs';

// Dedicated, no-volume, disposable candidates only. No credentials or raw
// financial response bodies are written into this verifier's evidence.
const docker = process.env.DOCKER_CLI || 'docker';
const container = process.env.FINEXY_REPORT_CONTAINER;
const base = process.env.FINEXY_REPORT_URL;
const imageId = process.env.FINEXY_REPORT_IMAGE_ID;
const output = process.env.FINEXY_REPORT_EVIDENCE;
const disabled = process.argv.includes('--export-disabled');
const fixture = await loadLedgerReportFixture();
const expectedCount = fixture.expected.detailIds.length;
assert(container?.startsWith('finexy-report-api-e2e-'));
assert(output?.startsWith('artifacts/ledger-report-api-'));
const url = new URL(base);
assert.equal(url.hostname, '127.0.0.1');
assert.equal(url.protocol, 'http:');
assert(['18127', '18128'].includes(url.port));
const info = JSON.parse(execFileSync(docker, ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Image, imageId);
assert.deepEqual(info.Mounts, []);
assert.equal(info.HostConfig.AutoRemove, true);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.Config.Env.includes(`EBK_DATA_ENABLE_EXPORT=${disabled ? 'false' : 'true'}`));
if (!disabled) assert(info.Config.Env.includes('EBK_SECURITY_ENABLE_API_TOKEN=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === url.port));
const password = randomBytes(24).toString('hex');
const checks = [];
let phase = 'health';
let lastHTTP;
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const headers = token => ({ 'Content-Type': 'application/json', 'X-Timezone-Offset': '0', ...(token ? { Authorization: `Bearer ${token}` } : {}) });
async function raw(path, token, body, expected = 200, extras = {}) {
    const options = {
        method: body === undefined ? 'GET' : 'POST', headers: { ...headers(token), ...extras },
        ...(body === undefined ? {} : { body: typeof body === 'string' ? body : encodeLedgerReportAPI(body) }),
    };
    // Fetch may rewrite Host. Use the native HTTP client for the wire-level
    // authority-change check, while keeping the socket on the owned loopback.
    const response = extras.Host ? await new Promise((resolve, reject) => {
        const req = httpRequest(`${base}/api/${path}`, { method: options.method, headers: options.headers }, res => {
            const chunks = []; res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers: res.headers })));
        });
        req.on('error', reject); req.end(options.body);
    }) : await fetch(`${base}/api/${path}`, options);
    lastHTTP = { path: path.split('?')[0], actualStatus: response.status, expectedStatus: expected };
    assert.equal(response.status, expected, path.split('?')[0]);
    return response;
}
async function api(path, token, body, expected = 200) {
    const response = await raw(path, token, body, expected);
    const value = await response.json();
    if (expected === 200) { assert.equal(value.success, true); assert(Object.hasOwn(value, 'result')); }
    return value.result;
}
async function register() {
    const username = `report_${randomBytes(7).toString('hex')}`;
    const value = await api('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic report QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    return { username, token: value.token };
}
const { ledgerId: fixtureLedger, ...period } = fixture.query;
const query = (actor, q, rest = {}, status = 200) => api('v1/reports/query.json', actor.token, { query: q, ...rest }, status);
const preview = (actor, q, hash, status = 200) => api('v1/reports/export/preview.json', actor.token, { query: q, expectedSnapshotHash: hash }, status);
const download = (actor, q, p, extras = {}, status = 200) => raw('v1/reports/export.csv', actor.token, { query: q, expectedSnapshotHash: p.snapshotHash, previewToken: p.previewToken, confirmed: true, ...extras }, status);

// Independent RFC-style parser: preserve quoted commas, doubled quotes and
// embedded CR/LF; physical line counts are never treated as transaction counts.
function parseCSV(text) {
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
    const header = rows.shift(); assert.equal(new Set(header).size, header.length);
    return rows.map(values => { assert.equal(values.length, header.length); return Object.fromEntries(header.map((name, i) => [name, values[i]])); });
}
function csvTotals(rows) {
    const result = new Map();
    for (const row of rows) for (const side of ['source', 'destination']) {
        const currency = row[`${side}_currency`]; if (!currency) continue;
        if (!result.has(currency)) result.set(currency, { currency, income: 0n, expense: 0n, net: 0n, adjustment: 0n, transferIn: 0n, transferOut: 0n });
        const m = result.get(currency); const amount = BigInt(row[`${side}_amount_minor`]);
        if (row.type === '1') m.adjustment += BigInt(row.adjustment_minor);
        if (row.type === '2') m.income += amount;
        if (row.type === '3') m.expense += amount;
        if (row.type === '4') m[side === 'source' ? 'transferOut' : 'transferIn'] += amount;
    }
    return [...result.values()].sort((a, b) => a.currency.localeCompare(b.currency)).map(m => {
        m.net = m.income - m.expense;
        return Object.fromEntries(Object.entries(m).map(([k, v]) => [k, typeof v === 'bigint' ? v.toString() : v]));
    });
}

try {
    await mkdir(output, { recursive: true });
    for (let i = 0; i < 60; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* startup */ }
        if (i === 59) throw new Error('Candidate health did not become ready');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    phase = 'anonymous';
    const anonymous = await raw('v1/reports/query.json', '', { query: { ...period, ledgerId: '0' } }, 400);
    assert.equal((await anonymous.json()).errorCode, 202012, 'existing empty-token authentication contract');
    pass('anonymous report requests are denied');
    const owner = await register();
    if (disabled) {
        phase = 'disabled export';
        const q = { ...period, ledgerId: '0' }; const data = await query(owner, q);
        assert.equal(data.exportAllowed, false); assert.equal(data.matchedCount, 0);
        await preview(owner, q, data.snapshotHash, 400);
        await download(owner, q, { snapshotHash: data.snapshotHash, previewToken: 'invalid' }, {}, 400);
        pass('export-disabled candidate still serves query and rejects both registered export routes');
    } else {
        const admin = await register(); const member = await register(); const viewer = await register(); const outsider = await register();
        const ledgerA = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic report A' });
        const ledgerB = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic report B' });
        const q = { ...period, ledgerId: ledgerA.id };
        const memberIDs = new Map();
        phase = 'members';
        for (const [actor, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
            const invite = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledgerA.id, inviteeName: actor.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
            await api('v1/ledger/invitation/accept.json', actor.token, { token: invite.token });
            const me = (await api(`v1/ledger/member/list.json?ledgerId=${ledgerA.id}`, actor.token)).find(row => row.isCurrentUser);
            memberIDs.set(actor, me.id);
            if (role === 2) await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledgerA.id, memberId: me.id, role: 2 });
        }
        const ledgerIDs = new Map([[fixtureLedger, ledgerA.id], ['12', ledgerB.id], ['0', '0']]);
        const categories = new Map(); const tags = new Map(); const accounts = new Map(); const transactions = new Map();
        for (const row of fixture.categories.filter(row => !row.databaseOnly)) {
            categories.set(row.id, await api('v1/transaction/categories/add.json', owner.token, { name: row.name, type: row.type, parentId: row.parentId ? categories.get(row.parentId).id : '0', icon: '1', color: '000000' }));
        }
        for (const row of fixture.tags.filter(row => !row.databaseOnly)) tags.set(row.id, await api('v1/transaction/tags/add.json', owner.token, { name: row.name }));
        const accountRequest = row => ({ ledgerId: ledgerIDs.get(row.ledgerId), name: row.name, currency: row.currency, balance: BigInt(row.balance), balanceTime: BigInt(row.balanceTime || '946684800'), category: 1, type: row.type, icon: '1', color: '000000' });
        for (const row of fixture.accounts.filter(row => !row.databaseOnly && !row.parentId)) {
            const children = fixture.accounts.filter(child => child.parentId === row.id);
            const created = await api('v1/accounts/add.json', owner.token, { ...accountRequest(row), ...(children.length ? { subAccounts: children.map(accountRequest) } : {}) });
            accounts.set(row.id, created);
            for (const child of children) {
                const actual = created.subAccounts.find(account => account.name === child.name && account.currency === child.currency);
                assert(actual); accounts.set(child.id, actual);
            }
        }
        const seedTransaction = async row => {
            const incoming = row.type === 5;
            const partner = row.relatedId ? fixture.transactions.find(other => other.id === row.relatedId) : null;
            const tagIDs = [...new Set([...(row.tagIds || []), ...(partner?.tagIds || [])])];
            return api('v1/transactions/add.json', owner.token, {
                ledgerId: ledgerIDs.get(row.ledgerId), type: incoming ? 4 : row.type,
                categoryId: row.categoryId ? categories.get(row.categoryId).id : '0',
                sourceAccountId: incoming ? '0' : accounts.get(row.accountId).id,
                sourceAmount: incoming ? 0n : BigInt(row.amount),
                ...((row.type === 4 || incoming) ? { destinationAccountId: incoming ? accounts.get(row.accountId).id : (row.relatedAccountId ? accounts.get(row.relatedAccountId).id : '0'), destinationAmount: incoming ? BigInt(row.amount) : BigInt(row.relatedAccountAmount || '0') } : {}),
                time: BigInt(row.transactionTime), utcOffset: 0, clientSessionId: randomBytes(16).toString('hex'),
                tagIds: tagIDs.map(id => tags.get(id).id), hideAmount: row.hideAmount || false, comment: row.comment || '',
            });
        };
        phase = 'transactions';
        for (const row of fixture.transactions) {
            if (row.generatedByAccount || (row.type === 5 && row.relatedId)) continue;
            transactions.set(row.id, await seedTransaction(row));
        }
        for (const row of fixture.accounts.filter(row => row.hidden && !row.databaseOnly)) await api('v1/accounts/hide.json', owner.token, { id: accounts.get(row.id).id, hidden: true });
        const cash = accounts.get('100'); const dollar = accounts.get('101'); const history = accounts.get('111'); const adjustment = accounts.get('112');
        const expenseRoot = categories.get('1001'); const expense = categories.get('1002');
        const alpha = tags.get('500'); const beta = tags.get('501'); const privateB = tags.get('502'); const unused = tags.get('503');
        const exp = transactions.get('2'); const internal = transactions.get('5'); const incoming = transactions.get('8');
        const hiddenHistory = transactions.get('4'); const memo = fixture.transactions.find(row => row.id === '2').comment;
        const add = (categoryId, sourceAccountId, sourceAmount, extra = {}) => api('v1/transactions/add.json', owner.token, { ledgerId: ledgerA.id, type: 3, categoryId, sourceAccountId, sourceAmount, time: BigInt(fixture.transactions.find(row => row.id === '10').transactionTime) + 10n, utcOffset: 0, clientSessionId: randomBytes(16).toString('hex'), ...extra });
        const beforeAccounts = await api(`v1/accounts/list.json?ledgerId=${ledgerA.id}`, owner.token);
        const full = await query(owner, q);
        assert.equal(full.matchedCount, expectedCount); assert.equal(full.details.length, expectedCount); assert.equal(full.detailsComplete, true); assert.equal(full.dataComplete, true); assert(Array.isArray(full.query.types));
        const totals = fixture.expected.totals;
        assert.deepEqual(full.totals, totals);
        assert(full.details.some(row => row.id === hiddenHistory.id));
        const adjust = full.details.find(row => row.type === 1);
        transactions.set(fixture.expected.adjustment.id, adjust);
        assert.equal(adjust.source.accountId, adjustment.id);
        assert.equal(adjust.balanceTarget, fixture.expected.adjustment.balanceTarget);
        assert.equal(adjust.adjustment, fixture.expected.adjustment.delta);
        const canonicalTransactionID = actual => {
            const match = [...transactions].find(([, value]) => value.id === actual);
            assert(match, 'every report row must map to one fixture transaction'); return match[0];
        };
        assert.deepEqual(full.details.map(row => canonicalTransactionID(row.id)), fixture.expected.detailIds);
        const tagCanonical = new Map([...tags].map(([id, actual]) => [actual.id, id]));
        assert.deepEqual(full.readableTags.map(row => tagCanonical.get(row.id)), fixture.expected.readableTagIds);
        assert.deepEqual(Object.fromEntries(full.tagGroups.map(group => [group.key === 'none' ? 'none' : tagCanonical.get(group.key), group.count])), fixture.expected.tagCounts);
        const categoryCanonical = new Map([...categories].map(([id, actual]) => [actual.id, id]));
        assert.deepEqual(Object.fromEntries(full.categories.map(group => [group.key === '0' ? '0' : categoryCanonical.get(group.key), group.count])), fixture.expected.categoryCounts);
        const transferDetail = full.details.find(row => row.id === internal.id);
        assert.equal(transferDetail.physicalIds.length, fixture.expected.transfer.physicalIds.length);
        assert.equal(transferDetail.source.amount, fixture.expected.transfer.sourceAmount);
        assert.equal(transferDetail.destination.amount, fixture.expected.transfer.destinationAmount);
        assert.deepEqual(transferDetail.tags.map(row => tagCanonical.get(row.id)), fixture.expected.transfer.tagIds);
        pass('real HTTP report preserves refund, currencies, internal/external transfers, adjustment and hidden-account history');

        phase = 'roles and isolation';
        for (const actor of [owner, admin, member, viewer]) {
            const data = await query(actor, q); assert.equal(data.matchedCount, expectedCount); assert.deepEqual(data.totals, totals);
            const p = await preview(actor, q, data.snapshotHash); const response = await download(actor, q, p);
            assert.equal(parseCSV(await response.text()).length, expectedCount);
        }
        await query(outsider, q, {}, 403); await query(member, { ...period, ledgerId: ledgerB.id }, {}, 403);
        assert.equal((await query(owner, { ...period, ledgerId: ledgerB.id })).matchedCount, 1);
        assert.equal((await query(owner, { ...period, ledgerId: '0' })).matchedCount, 1);
        assert.equal((await query(member, { ...period, ledgerId: '0' })).matchedCount, 0);
        for (const id of [privateB.id, unused.id]) await query(viewer, { ...q, tags: [{ mode: 'any', ids: [id] }] }, {}, 400);
        pass('all four roles read/export; A/B/default, nonmembers and owner-private unused tags remain isolated');

        phase = 'paging';
        const page1 = await query(owner, q, { count: 2 }); let page = page1; const seen = [];
        for (let pages = 0; ; pages++) {
            assert(pages < 10); assert.deepEqual(page.totals, totals); seen.push(...page.details.map(row => row.id));
            if (page.nextOffset === null) break;
            page = await query(owner, q, { count: 2, offset: page.nextOffset, expectedSnapshotHash: page1.snapshotHash });
        }
        assert.deepEqual(seen, full.details.map(row => row.id)); assert.equal(page1.detailsComplete, false);
        await query(owner, q, { offset: 2 }, 409); await query(owner, q, { expectedSnapshotHash: '00'.repeat(32) }, 409);
        await query(owner, q, { count: 201 }, 400); await query(owner, q, { unknown: true }, 400);
        await raw('v1/reports/query.json', owner.token, `{"query":${JSON.stringify(q)},"Query":${JSON.stringify(q)}}`, 400);
        const numericLedgerQuery = JSON.stringify(q).replace(/"ledgerId":"[^"]+"/, '"ledgerId":9007199254740993');
        await raw('v1/reports/query.json', owner.token, `{"query":${numericLedgerQuery}}`, 400);
        pass('full aggregate/hash is stable across pages; missing cursors, changed baselines and ambiguous inputs fail');

        phase = 'five dimensions';
        const mapFilter = (filter, resources) => filter ? { ...filter, ids: filter.ids?.map(id => id === '0' ? '0' : resources.get(id).id) } : undefined;
        for (const testCase of fixture.cases) {
            const patch = testCase.query;
            const actual = await query(owner, { ...q, ...patch, ...(patch.accounts ? { accounts: mapFilter(patch.accounts, accounts) } : {}), ...(patch.categories ? { categories: mapFilter(patch.categories, categories) } : {}), ...(patch.tags ? { tags: patch.tags.map(filter => mapFilter(filter, tags)) } : {}) });
            assert.deepEqual(actual.details.map(row => canonicalTransactionID(row.id)), testCase.detailIds, testCase.name);
            assert.equal(actual.matchedCount, testCase.detailIds.length, testCase.name);
            if (testCase.totals) assert.deepEqual(actual.totals, testCase.totals, testCase.name);
        }
        const uncategorizedQuery = { ...q, categories: { mode: 'include', ids: ['0'], includeDescendants: true } };
        const uncategorized = await query(owner, uncategorizedQuery);
        assert.equal(uncategorized.categoryMetadata.some(row => row.id === '0'), false);
        const uncategorizedPreview = await preview(owner, uncategorizedQuery, uncategorized.snapshotHash);
        const uncategorizedCSV = parseCSV(await (await download(owner, uncategorizedQuery, uncategorizedPreview)).text());
        assert.deepEqual(uncategorizedCSV.map(row => row.id), uncategorized.details.map(row => row.id));
        assert(uncategorizedCSV.every(row => row.category_id === '0'));
        pass('shared fixture filter cases preserve five dimensions, descendants, tag sets, hidden amounts and either transfer leg');

        phase = 'CSV content';
        const p = await preview(owner, q, full.snapshotHash); assert.deepEqual(p.totals, totals); assert.equal(p.amountUnit, 'minor');
        const response = await download(owner, q, p); assert.equal(response.headers.get('cache-control'), 'no-store'); assert.equal(response.headers.get('x-finexy-report-count'), String(expectedCount)); assert.equal(response.headers.get('x-finexy-report-snapshot'), p.snapshotHash); assert(response.headers.get('content-disposition').endsWith('.csv'));
        const csv = parseCSV(await response.text()); assert.deepEqual(csv.map(r => r.id), full.details.map(r => r.id)); assert.deepEqual(csvTotals(csv), totals);
        for (const row of csv) {
            assert.equal(row.major_unit_scale, '2');
            for (const column of ['source_amount', 'destination_amount', 'adjustment', 'balance_target']) {
                const minor = row[`${column}_minor`]; const major = row[`${column}_major`];
                if (minor === '') { assert.equal(major, ''); continue; }
                assert.match(major, /^-?\d+\.\d{2}$/);
                const negative = major.startsWith('-'); const [whole, fraction] = major.replace(/^-/, '').split('.');
                const reconstructed = (BigInt(whole) * 100n + BigInt(fraction)) * (negative ? -1n : 1n);
                assert.equal(reconstructed, BigInt(minor));
            }
        }
        const expRow = csv.find(row => row.id === exp.id); assert.equal(expRow.comment, `'${memo}`); assert.equal(expRow.source_account_name, `'${cash.name}`); assert.deepEqual(JSON.parse(expRow.tag_ids_json), [alpha.id, beta.id].sort((a, b) => BigInt(a) < BigInt(b) ? -1 : 1));
        const emptyQuery = { ...q, startDate: '2019-10-01', endDate: '2019-10-31' }; const empty = await query(owner, emptyQuery); assert.equal(empty.matchedCount, 0);
        const emptyPreview = await preview(owner, emptyQuery, empty.snapshotHash); assert.equal(parseCSV(await (await download(owner, emptyQuery, emptyPreview)).text()).length, 0);
        pass('download reparse matches exact IDs/count/currency totals, quotes/newlines/formulas and true empty output');

        phase = 'confirmation/session/hidden';
        await download(owner, q, p, { confirmed: false }, 400); await download(owner, q, p, { previewToken: 'invalid' }, 409);
        const loggedIn = await api('authorize.json', '', { loginName: owner.username, password });
        phase = 'confirmation/new-session';
        await download({ token: loggedIn.token }, q, p, {}, 409);
        phase = 'confirmation/host';
        await raw('v1/reports/export.csv', owner.token, { query: q, expectedSnapshotHash: p.snapshotHash, previewToken: p.previewToken, confirmed: true }, 409, { Host: `localhost:${url.port}` });
        phase = 'confirmation/hidden';
        const hiddenQuery = { ...q, hidden: 'include' }; const withHidden = await query(owner, hiddenQuery); assert.equal(withHidden.matchedCount, fixture.cases.find(testCase => testCase.name === 'hidden-explicit').detailIds.length);
        const hp = await preview(owner, hiddenQuery, withHidden.snapshotHash); assert.equal(hp.includesHiddenAmounts, true);
        await download(owner, hiddenQuery, hp, {}, 400); assert.equal(parseCSV(await (await download(owner, hiddenQuery, hp, { includeHiddenAmounts: true })).text()).length, withHidden.matchedCount);
        pass('explicit confirmation, session/host binding and separate hidden-amount acknowledgement are required');

        phase = 'user export restrictions';
        execFileSync(docker, ['exec', '-e', 'EBK_UUID_SERVER_ID=84', container, '/ezbookkeeping/ezbookkeeping', 'userdata', 'user-add-restrict-features', '--username', owner.username, '--features', '10'], { stdio: 'pipe' });
        assert.equal((await query(owner, q)).exportAllowed, false);
        const restriction = await raw('v1/reports/export/preview.json', owner.token, { query: q, expectedSnapshotHash: p.snapshotHash }, 400);
        assert.equal((await restriction.json()).errorCode, 201031, 'existing user-feature restriction contract');
        const restrictedFile = await download(owner, q, p, {}, 400);
        assert.equal(await restrictedFile.text(), 'not permitted to perform this action');
        assert.equal(restrictedFile.headers.get('content-disposition'), null);
        execFileSync(docker, ['exec', '-e', 'EBK_UUID_SERVER_ID=84', container, '/ezbookkeeping/ezbookkeeping', 'userdata', 'user-remove-restrict-features', '--username', owner.username, '--features', '10'], { stdio: 'pipe' });
        assert.equal((await query(owner, q)).exportAllowed, true, 'removing the last restriction must restore export');
        pass('live user export restriction rejects an already issued preview without blocking report reads');

        phase = 'read-token grants';
        const apiToken = await api('v1/tokens/generate/api.json', owner.token, { password, name: 'Synthetic report boundary', scopes: ['read'], ledgerId: ledgerA.id, expiresInSeconds: 3600 });
        await query({ token: apiToken.token }, q, {}, 403); await preview({ token: apiToken.token }, q, p.snapshotHash, 403); await download({ token: apiToken.token }, q, p, {}, 403);
        pass('existing read API tokens do not silently gain new report or private-tag export routes');

        phase = 'data change';
        await add(expense.id, cash.id, 1);
        await preview(owner, q, full.snapshotHash, 409); await download(owner, q, p, {}, 409); await query(owner, q, { offset: 2, expectedSnapshotHash: full.snapshotHash }, 409);
        pass('financial changes invalidate old pagination and download confirmation baselines');

        phase = 'demotion/revocation';
        const memberReport = await query(member, q); const memberPreview = await preview(member, q, memberReport.snapshotHash);
        await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledgerA.id, memberId: memberIDs.get(member), role: 4 });
        await download(member, q, memberPreview, {}, 409);
        const viewerReport = await query(member, q); const viewerPreview = await preview(member, q, viewerReport.snapshotHash);
        await api('v1/ledger/member/remove.json', owner.token, { ledgerId: ledgerA.id, memberId: memberIDs.get(member) });
        await query(member, q, {}, 403); await download(member, q, viewerPreview, {}, 403);
        pass('demotion invalidates receipts and membership revocation blocks further reads/downloads');

        // The only intentional financial mutation after baseline was one CNY
        // expense. Reports/exports themselves must leave balances unchanged.
        const afterAccounts = await api(`v1/accounts/list.json?ledgerId=${ledgerA.id}`, owner.token);
        const beforeCash = beforeAccounts.find(a => a.id === cash.id); const afterCash = afterAccounts.find(a => a.id === cash.id);
        assert(Number.isSafeInteger(beforeCash.balance)); assert(Number.isSafeInteger(afterCash.balance));
        assert.equal(BigInt(afterCash.balance), BigInt(beforeCash.balance) - 1n);
        pass('report and export operations have no additional account-balance effects');
    }
    await writeFile(`${output}/http-${disabled ? 'disabled' : 'enabled'}-summary.json`, JSON.stringify({ status: 'passed', checks, count: checks.length, containerId: info.Id, imageId: info.Image, mounts: 0, mode: disabled ? 'export-disabled' : 'export-enabled', fixtureVersion: fixture.schemaVersion, fixtureCases: fixture.cases.length, credentialsPersisted: false }, null, 2));
} catch (error) {
    await writeFile(`${output}/http-${disabled ? 'disabled' : 'enabled'}-failure.json`, JSON.stringify({ status: 'failed', phase, errorType: error.name, lastHTTP, checks, credentialsPersisted: false }, null, 2));
    console.error(`FAIL ${phase}: ${error.name} HTTP ${lastHTTP?.path} actual=${lastHTTP?.actualStatus} expected=${lastHTTP?.expectedStatus}`);
    process.exitCode = 1;
}
