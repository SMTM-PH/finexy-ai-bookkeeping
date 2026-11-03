import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';

// Only this disposable, unmounted service can be restarted or written by the gate.
const container = 'finexy-agent-e2e';
const base = 'http://127.0.0.1:18110';
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostPort === '18110'));
const password = randomBytes(18).toString('hex');
const evidence = [];
const pass = name => { evidence.push(name); console.log(`PASS ${name}`); };
async function request(path, token, body, expected = 200) {
    const response = await fetch(`${base}/api/${path}`, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    assert.equal(response.status, expected, path);
    const data = await response.json();
    if (expected === 200) assert.equal(data.success, true, path);
    return data.result;
}
async function register() {
    const username = `retry_${randomBytes(6).toString('hex')}`;
    const result = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: '隔离重试测试', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    return { username, token: result.token };
}
async function restart() {
    execFileSync('docker', ['restart', container], { stdio: 'ignore' });
    for (let i = 0; i < 80; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) return; } catch { /* starting */ }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert.fail('isolated service did not recover');
}
const owner = await register();
const member = await register();
const root = await request('v1/transaction/categories/add.json', owner.token, { name: 'Retry root', type: 2, icon: '1', color: '000000' });
const category = await request('v1/transaction/categories/add.json', owner.token, { name: 'Retry leaf', parentId: root.id, type: 2, icon: '1', color: '000000' });
const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 };
const defaultAccount = await request('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Default retry account' });
const ledger = await request('v1/ledger/create.json', owner.token, { type: 1, name: 'Retry selected ledger' });
const account = await request('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: ledger.id, name: 'Selected retry account' });
const invitation = await request('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledger.id, inviteeName: member.username, role: 3, expiresInSeconds: 86400 });
await request('v1/ledger/invitation/accept.json', member.token, { token: invitation.token });
const draft = { ledgerId: ledger.id, type: 3, time: Math.floor(Date.now() / 1000), utcOffset: 480, sourceAccountId: account.id, categoryId: category.id, sourceAmount: 123, comment: 'Synthetic persistent retry', clientSessionId: randomBytes(16).toString('hex') };
const first = await request('v1/transactions/add.json', member.token, draft);
await restart();
const retry = await request('v1/transactions/add.json', member.token, draft);
assert.equal(retry.id, first.id);
assert.equal(retry.recorderUid, first.recorderUid);
assert.equal((await request(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, 9877);
pass('same snapshot and key survive a server restart without a second balance change');

await request('v1/transactions/add.json', member.token, { ...draft, sourceAmount: 124 }, 400);
await request('v1/transactions/add.json', member.token, { ...draft, comment: 'Different snapshot' }, 400);
assert.equal((await request(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, 9877);
pass('key reuse with changed business fields is rejected without writing');

const concurrent = { ...draft, clientSessionId: randomBytes(16).toString('hex'), sourceAmount: 10 };
let transientFailures = 0;
async function stableRetry() {
    // SQLite may reject concurrent preflight reads while another transaction
    // holds a table lock. Retain the exact confirmed snapshot on every retry.
    for (let attempt = 0; attempt < 12; attempt++) {
        try { return await request('v1/transactions/add.json', member.token, concurrent); }
        catch (error) {
            if (error.actual !== 500 || attempt === 11) throw error;
            transientFailures++;
            await new Promise(resolve => setTimeout(resolve, 30 * (attempt + 1)));
        }
    }
}
const results = await Promise.all(Array.from({ length: 8 }, stableRetry));
assert(results.every(row => row.id === results[0].id));
assert.equal((await request(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, 9867);
pass(`eight concurrent requests with stable retries share one committed transaction (${transientFailures} transient 500 responses)`);

const own = await request('v1/transactions/add.json', owner.token, draft);
assert.notEqual(own.id, first.id);
const personal = await request('v1/transactions/add.json', owner.token, { ...draft, ledgerId: '0', sourceAccountId: defaultAccount.id });
assert.notEqual(personal.id, own.id);
assert.equal((await request(`v1/accounts/get.json?id=${defaultAccount.id}`, owner.token)).balance, 9877);
pass('idempotency keys are isolated by actor and ledger');
await request('v1/transactions/add.json', owner.token, { ...draft, ledgerId: '0', clientSessionId: randomBytes(16).toString('hex') }, 400);
pass('default-ledger requests cannot change an explicit-ledger account');

const form = new FormData();
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXfoAAAAASUVORK5CYII=', 'base64');
form.set('picture', new Blob([png], { type: 'image/png' }), 'synthetic.png');
const upload = await fetch(`${base}/api/v1/transaction/pictures/upload.json`, { method: 'POST', headers: { Authorization: `Bearer ${owner.token}`, 'X-Timezone-Offset': '480' }, body: form });
assert.equal(upload.status, 200);
const picture = (await upload.json()).result;
assert(picture?.pictureId);
const tag = await request('v1/transaction/tags/add.json', owner.token, { name: 'Persistent retry tag', icon: '1', color: '000000' });
const attachedDraft = { ...draft, ledgerId: '0', sourceAccountId: defaultAccount.id, sourceAmount: 1, clientSessionId: randomBytes(16).toString('hex'), pictureIds: [picture.pictureId], tagIds: [tag.id] };
const attached = await request('v1/transactions/add.json', owner.token, attachedDraft);
await restart();
const attachedRetry = await request('v1/transactions/add.json', owner.token, attachedDraft);
assert.equal(attachedRetry.id, attached.id);
assert.deepEqual(attachedRetry.tagIds, attached.tagIds);
assert.deepEqual(attachedRetry.pictures.map(p => p.pictureId), attached.pictures.map(p => p.pictureId));
assert.equal((await request(`v1/accounts/get.json?id=${defaultAccount.id}`, owner.token)).balance, 9876);
pass('attached pictures and tags replay after restart using persisted associations');

const members = await request(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, owner.token);
const invited = members.find(m => !m.isCurrentUser && m.role === 3);
assert(invited);
await request('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledger.id, memberId: invited.id, role: 4 });
await request('v1/transactions/add.json', member.token, draft, 403);
pass('live membership is rechecked for prior submissions');

await request('v1/transactions/delete.json', owner.token, { id: own.id, ledgerId: ledger.id });
await request('v1/transactions/add.json', owner.token, draft, 400);
assert.equal((await request(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, 9867);
pass('retry cannot recreate a deleted transaction');
await mkdir('artifacts/first-iteration-20261003', { recursive: true });
await writeFile('artifacts/first-iteration-20261003/transaction-retries.json', JSON.stringify({ passed: evidence }, null, 2));
