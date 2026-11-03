import assert from 'node:assert/strict';
import { randomBytes, createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile } from 'node:fs/promises';

// Only disposable containers with exact image/port/environment guards may be
// written. Credentials and synthetic financial contents stay in memory.
const run = promisify(execFile);
const evidence = 'artifacts/sqlite-concurrency-20261005';
const definitions = [
    { name: 'finexy-sqlite-concurrency-e2e', image: 'finexy-sqlite-concurrency-candidate:20261005-root1',
        hash: (await readFile(`${evidence}/runtime-image-id.txt`, 'utf8')).trim(), port: 18125, uuid: 246 },
    { name: 'finexy-sqlite-concurrency-baseline-e2e', image: 'finexy-template-creation-worker-candidate:20261005-root2',
        hash: 'sha256:56e53787747c76e5fce56030a0ff078b03128354d0071d9d5ac77190f0a045bd', port: 18126, uuid: 247 },
];
const docker = async args => (await run('docker', args, { encoding: 'utf8', timeout: 40000, maxBuffer: 4 * 1024 * 1024 })).stdout.trim();
const guards = [];
async function guard(definition) {
    const info = JSON.parse(await docker(['inspect', definition.name]))[0];
    assert.equal(info.Name, `/${definition.name}`);
    assert.equal(info.Config.Image, definition.image); assert.equal(info.Image, definition.hash);
    assert.equal(info.State.Running, true); assert.deepEqual(info.Mounts, []);
    if (definition.id) assert.equal(info.Id, definition.id);
    for (const entry of [`EBK_UUID_SERVER_ID=${definition.uuid}`, 'EBK_AUTH_ENABLE_REGISTER=true',
        'EBK_USER_ENABLE_SCHEDULED_TRANSACTION=true', 'EBK_DUPLICATE_CHECKER_DUPLICATE_SUBMISSIONS_INTERVAL=0',
        'EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false']) assert(info.Config.Env.includes(entry));
    assert.deepEqual(info.NetworkSettings.Ports['8080/tcp'], [{ HostIp: '127.0.0.1', HostPort: String(definition.port) }]);
    if (definition.port === 18125) assert.deepEqual(info.NetworkSettings.Ports['8081/tcp'], [{ HostIp: '127.0.0.1', HostPort: '18127' }]);
    definition.id = info.Id;
    return info;
}
async function healthy(base) {
    for (let i = 0; i < 120; i++) {
        try {
            const response = await fetch(`${base}/healthz.json`, { signal: AbortSignal.timeout(1500) });
            if (response.status === 200 && (await response.json()).success === true) return;
        } catch { /* startup only; business requests are never retried */ }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('Isolated service startup failed');
}
function client(base) {
    const stats = { requests: 0, success: 0, expectedDenials: 0, unexpected: [] };
    async function request(path, token = '', body, expected = 200, tolerate = false) {
        stats.requests++;
        const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
            ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000) });
        const data = await response.json();
        if (response.status !== expected || (expected === 200 && (data.success !== true || !Object.hasOwn(data, 'result')))) {
            stats.unexpected.push({ path: path.split('?')[0], status: response.status, errorCode: data.errorCode });
            if (tolerate) return undefined;
            throw new Error(`Unexpected HTTP result at ${path.split('?')[0]}: ${response.status}`);
        }
        if (expected === 200) stats.success++; else { assert.equal(data.success, false); stats.expectedDenials++; }
        return data.result;
    }
    return { request, stats };
}
const addPath = 'v1/transaction/templates/add_durable.json';
async function fixture(c) {
    const register = async label => {
        const username = `sql_${randomBytes(8).toString('hex')}`;
        return { username, ...(await c.request('register.json', '', { username, password: randomBytes(20).toString('hex'),
            email: `${username}@example.invalid`, nickname: `Synthetic ${label}`, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] })) };
    };
    const owner = await register('owner'), admin = await register('admin'), member = await register('member'), viewer = await register('viewer');
    const a = await c.request('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic A' });
    const b = await c.request('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic B' });
    let adminMemberId;
    for (const [user, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invitation = await c.request('v1/ledger/invitation/create.json', owner.token, { ledgerId: a.id, inviteeName: user.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await c.request('v1/ledger/invitation/accept.json', user.token, { token: invitation.token });
        if (role === 2) {
            adminMemberId = (await c.request(`v1/ledger/member/list.json?ledgerId=${a.id}`, admin.token)).find(row => row.isCurrentUser).id;
            await c.request('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: adminMemberId, role: 2 });
        }
    }
    const parent = await c.request('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic parent', type: 2, icon: '1', color: '000000' });
    const category = await c.request('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic leaf', type: 2, parentId: parent.id, icon: '1', color: '000000' });
    const accounts = new Map();
    for (const ledgerId of ['0', a.id, b.id]) accounts.set(ledgerId, await c.request('v1/accounts/add.json', owner.token, {
        ledgerId, name: 'Synthetic sentinel', currency: 'CNY', type: 1, category: 2, icon: '1', color: '000000', balance: 1000, balanceTime: 946684800 }));
    const draft = { ledgerId: a.id, templateType: 1, name: 'Synthetic durable template', type: 3, categoryId: category.id,
        sourceAccountId: accounts.get(a.id).id, destinationAccountId: '0', sourceAmount: 123, destinationAmount: 0, hideAmount: false, tagIds: [], comment: 'Synthetic only' };
    const periodic = { ...draft, templateType: 2, name: 'Synthetic periodic', utcOffset: 480, scheduledFrequencyType: 3, scheduledFrequency: '1', scheduledStartDate: '2020-01-01' };
    await c.request(addPath, owner.token, { ...periodic, clientSessionId: 'periodic' });
    const financial = async () => {
        const result = [];
        for (const id of ['0', a.id, b.id]) {
            const accounts = await c.request(`v1/accounts/list.json?ledgerId=${id}&with_balance=true`, owner.token);
            const transactions = await c.request(`v1/transactions/list.json?ledgerId=${id}&count=50&with_count=true`, owner.token);
            assert(transactions.items.length < 50);
            result.push({ accounts, transactions });
        }
        return createHash('sha256').update(JSON.stringify(result)).digest('hex');
    };
    const financialBefore = await financial();
    return { owner, admin, member, viewer, a, b, accounts, draft, periodic, adminMemberId, financial, financialBefore };
}
async function stress(c, f, tolerate = false) {
    const actors = [f.owner, f.admin, f.member, f.viewer];
    const lanes = Array.from({ length: 12 }, (_, lane) => (async () => {
        for (let round = 0; round < 40; round++) {
            if (lane < 4) {
                await c.request('v1/ledger/modify.json', actors[lane % 2].token, { id: f.a.id, name: `Synthetic A ${lane} ${round}`, comment: '' }, 200, tolerate);
            } else {
                const kind = lane % 2 + 1;
                const rows = await c.request(`v1/transaction/templates/list.json?ledgerId=${f.a.id}&templateType=${kind}`, actors[lane % 4].token, undefined, 200, tolerate);
                if (rows) for (const row of rows) assert.equal(row.ledgerId, f.a.id);
            }
        }
    })());
    const results = await Promise.allSettled(lanes);
    assert(results.every(result => result.status === 'fulfilled'), 'A concurrent request lane failed');
    assert.equal(await f.financial(), f.financialBefore, 'Concurrent template and ledger operations changed finances');
}
const summary = { passed: false, checks: [], baseline: {}, candidate: {} };
try {
    // Guard both targets before the first registration or any other write.
    for (const definition of definitions) {
        const info = await guard(definition);
        guards.push({ name: definition.name, containerId: info.Id, imageId: info.Image, port: definition.port, uuid: definition.uuid, mounts: [] });
        await healthy(`http://127.0.0.1:${definition.port}`);
    }
    await writeFile(`${evidence}/containers-guard.json`, JSON.stringify(guards, null, 2));
    const baselineClient = client('http://127.0.0.1:18126');
    const old = await fixture(baselineClient);
    await stress(baselineClient, old, true);
    summary.baseline = { ...baselineClient.stats, stressRequests: 480, financialUnchanged: true };
    await writeFile(`${evidence}/http-summary.json`, JSON.stringify(summary, null, 2));

    const candidate = client('http://127.0.0.1:18125');
    const f = await fixture(candidate);
    await stress(candidate, f);
    summary.checks.push('480 concurrent ledger writes and four-role normal/periodic template reads, without retries');
    await guard(definitions[0]);
    await docker(['exec', '-d', '-e', 'EBK_SERVER_HTTP_PORT=8081', '-e', 'EBK_UUID_SERVER_ID=248', definitions[0].id, '/ezbookkeeping/ezbookkeeping', 'server', 'run']);
    await healthy('http://127.0.0.1:18127');
    const processIds = (await docker(['exec', definitions[0].id, 'pidof', 'ezbookkeeping'])).split(/\s+/);
    assert.equal(new Set(processIds).size, 2);
    const second = client('http://127.0.0.1:18127');
    for (let round = 0; round < 10; round++) {
        const body = { ...f.draft, clientSessionId: `concurrent-${round}` };
        const responses = await Promise.all(Array.from({ length: 12 }, (_, index) => (index % 2 ? second : candidate).request(addPath, f.admin.token, body)));
        assert.equal(new Set(responses.map(row => row.id)).size, 1);
        await candidate.request(addPath, f.admin.token, { ...body, sourceAmount: 124 }, 400);
    }
    const list = (user, ledgerId = f.a.id, kind = 1, expected = 200) => candidate.request(`v1/transaction/templates/list.json?ledgerId=${ledgerId}&templateType=${kind}`, user.token, undefined, expected);
    assert.equal((await list(f.owner)).length, 10);
    summary.checks.push('120 same-key creates across two live service processes return ten unique templates, with changed payloads rejected');
    const stable = { ...f.draft, clientSessionId: 'concurrent-0' };
    for (const user of [f.member, f.viewer]) await candidate.request(addPath, user.token, stable, 403);
    for (const [user, editable] of [[f.owner, true], [f.admin, true], [f.member, false], [f.viewer, false]]) {
        const rows = await list(user); assert.equal(rows.length, 10); assert(rows.every(row => row.editable === editable));
    }
    const ownerResult = await candidate.request(addPath, f.owner.token, stable);
    assert.notEqual(ownerResult.id, (await candidate.request(addPath, f.admin.token, stable)).id);
    for (const ledgerId of ['0', f.b.id]) {
        await candidate.request(addPath, f.owner.token, { ...stable, ledgerId, sourceAccountId: f.accounts.get(ledgerId).id });
        assert.equal((await list(f.owner, ledgerId)).length, 1);
    }
    await candidate.request('v1/ledger/member/change_role.json', f.owner.token, { ledgerId: f.a.id, memberId: f.adminMemberId, role: 4 });
    await second.request(addPath, f.admin.token, stable, 403);
    assert((await list(f.admin)).every(row => row.editable === false));
    await list(f.member, f.b.id, 1, 403);
    assert.equal(await f.financial(), f.financialBefore);
    assert.equal(candidate.stats.unexpected.length + second.stats.unexpected.length, 0);
    summary.checks.push('live role revocation, owner/creator/default/A/B isolation and unchanged financial snapshot');
    summary.candidate = { primary: candidate.stats, second: second.stats, stressRequests: 480, concurrentCreateRequests: 120,
        processIds, uuidIds: [246, 248], financialBeforeSha256: f.financialBefore, financialAfterSha256: await f.financial() };
    summary.passed = true;
    await writeFile(`${evidence}/http-summary.json`, JSON.stringify(summary, null, 2));
    console.log(`PASS SQLite HTTP concurrency: baseline unexpected=${summary.baseline.unexpected.length}, candidate unexpected=0, concurrent creates=120`);
} catch (error) {
    summary.error = { name: error.name, message: error.message.split('\n')[0] };
    await writeFile(`${evidence}/http-summary.json`, JSON.stringify(summary, null, 2));
    console.error(`FAIL SQLite HTTP concurrency: ${summary.error.message}`);
    process.exitCode = 1;
} finally {
    // Never remove an unrelated or drifted container; the first guard authorizes
    // only these exact no-volume disposable IDs.
    const cleanup = [];
    for (const definition of definitions) {
        if (!definition.id) continue;
        try {
            await guard(definition);
            await docker(['rm', '-f', definition.id]);
            cleanup.push({ name: definition.name, containerId: definition.id, removed: true });
        } catch {
            cleanup.push({ name: definition.name, removed: false });
            process.exitCode = 1;
        }
    }
    await writeFile(`${evidence}/containers-cleanup.json`, JSON.stringify(cleanup, null, 2));
}
