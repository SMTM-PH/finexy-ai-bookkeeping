import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';

const base = 'http://127.0.0.1:18131', mock = 'http://127.0.0.1:18132';
const output = 'artifacts/ai-recognition-ledger-20261005';
for (const [name, image, port] of [['finexy-ai-recognition-e2e', 'finexy-ai-recognition-candidate:20261005-root1', '18131'], ['finexy-ai-recognition-mock-e2e', 'finexy-ai-recognition-mock:20261005-root1', '18132']]) {
    const info = JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0];
    assert.equal(info.Name, `/${name}`); assert.equal(info.Config.Image, image); assert.deepEqual(info.Mounts, []);
    assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === port));
    if (port === '18131') assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=253'));
}
const password = randomBytes(18).toString('hex'), checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, token, body, status = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, status, path.split('?')[0]); const data = await response.json();
    if (status === 200) { assert(data.success); assert(Object.hasOwn(data, 'result')); } return data.result;
}
async function register() {
    const username = `recognition_${randomBytes(6).toString('hex')}`;
    return { username, ...(await api('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic recognition QA', language: 'zh-Hans', defaultCurrency: 'USD', categories: [] })) };
}
const audit = () => fetch(`${mock}/audit`).then(r => r.json());
const imageData = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8nEAAAAASUVORK5CYII=', 'base64');
async function image(user, values, status = 200, query = '') {
    const form = new FormData(); form.append('image', new Blob([imageData], { type: 'image/png' }), 'synthetic.png');
    for (const value of values) form.append('ledgerId', value);
    const response = await fetch(`${base}/api/v1/llm/transactions/recognize_receipt_image.json${query}`, { method: 'POST', headers: { Authorization: `Bearer ${user.token}`, 'X-Timezone-Offset': '480' }, body: form });
    assert.equal(response.status, status, 'image recognition HTTP'); const data = await response.json(); if (status === 200) assert(data.success); return data.result;
}
async function mcp(user, args, status = 200) {
    const response = await fetch(`${base}/mcp`, { method: 'POST', headers: { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-03-26' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'recognize_receipt_image', arguments: { image_base64: imageData.toString('base64'), mime_type: 'image/png', ...args } } }) });
    assert.equal(response.status, status); return response.json();
}
await mkdir(output, { recursive: true });
for (let i = 0; i < 60; i++) { try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* Starting. */ } if (i === 59) throw new Error('Disposable startup'); await new Promise(r => setTimeout(r, 250)); }
const owner = await register(), member = await register(), viewer = await register(), outsider = await register();
const a = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic recognition A' });
const b = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic recognition B' });
for (const [user, role] of [[member, 3], [viewer, 4]]) {
    const invitation = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: a.id, inviteeName: user.username, role, expiresInSeconds: 86400 });
    await api('v1/ledger/invitation/accept.json', user.token, { token: invitation.token });
}
const names = { a: 'SyntheticSelectedAccount', b: 'SyntheticForeignLedgerAccount', zero: 'SyntheticDefaultAccount', member: 'SyntheticMemberPrivateAccount', tag: 'SyntheticOwnerPrivateTag', category: 'SyntheticOwnerExpense', privateCategory: 'SyntheticMemberPrivateCategory' };
const accountBody = { category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 };
const account = await api('v1/accounts/add.json', owner.token, { ...accountBody, ledgerId: a.id, name: names.a });
await api('v1/accounts/add.json', owner.token, { ...accountBody, ledgerId: b.id, name: names.b });
const personal = await api('v1/accounts/add.json', owner.token, { ...accountBody, name: names.zero });
await api('v1/accounts/add.json', member.token, { ...accountBody, name: names.member });
const root = await api('v1/transaction/categories/add.json', owner.token, { type: 2, name: 'Synthetic root', icon: '1', color: '000000' });
const leaf = await api('v1/transaction/categories/add.json', owner.token, { type: 2, name: names.category, parentId: root.id, icon: '1', color: '000000' });
await api('v1/transaction/categories/add.json', member.token, { type: 2, name: names.privateCategory, icon: '1', color: '000000' });
const tag = await api('v1/transaction/tags/add.json', owner.token, { name: names.tag, color: '000000' });
const raw = { type: 'expense', account: names.a, category: names.category, amount: '-5.00', tags: [names.tag] };
async function control(body = {}) {
    const response = await fetch(`${mock}/control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ result: raw, forbidden: [names.b, names.zero, names.member, names.privateCategory, names.tag], required: [names.a, names.category], ...body }) }); assert.equal(response.status, 204);
}
async function assertAudit(count) { const data = await audit(); assert.equal(data.requests, count); assert.equal(data.violations, 0); }
const text = (user, ledgerId = a.id, status = 200) => api('v1/llm/transactions/recognize_text.json', user.token, { ledgerId, text: 'Synthetic private prompt marker' }, status);
const usage = user => api('v1/llm/usage/get.json', user.token);

await control({ forbidden: [names.b, names.zero, names.member, names.privateCategory], required: [names.a, names.category, names.tag] });
const ownerResult = await text(owner); assert.equal(ownerResult.ledgerId, a.id); assert.equal(ownerResult.sourceAccountId, account.id); assert.deepEqual(ownerResult.tagIds, [tag.id]); await assertAudit(1);
await control();
const memberResult = await text(member); assert.equal(memberResult.ledgerId, a.id); assert.equal(memberResult.sourceAccountId, account.id); assert.equal(memberResult.categoryId, leaf.id); assert.equal(memberResult.sourceAmount, -500); assert(!memberResult.tagIds?.length); await assertAudit(1);
assert.equal((await usage(owner)).all.requests, 1); assert.equal((await usage(member)).all.requests, 1);
pass('real text requests use selected owner resources, exclude other-ledger and private names, retain refunds and bill actual requester');

await control(); const result = await image(viewer, [a.id]); assert.equal(result.sourceAccountId, account.id); assert.equal(result.ledgerId, a.id); assert(!result.tagIds?.length); await assertAudit(1);
await image(owner, [a.id, a.id], 400); await image(owner, ['-1'], 400); await image(owner, [a.id], 400, `?ledgerId=${b.id}`); await image(outsider, [a.id], 403); await text(outsider, a.id, 403); await assertAudit(1);
pass('multipart ledger is explicit and unique; viewers recognize without writes; outsider and mismatched query rejected before model call');

await control({ result: { ...raw, account: names.zero }, forbidden: [names.a, names.b, names.member], required: [names.zero] });
const legacy = await api('v1/llm/transactions/recognize_text.json', owner.token, { text: 'Synthetic private prompt marker' }); assert.equal(legacy.sourceAccountId, personal.id); assert(!legacy.ledgerId || legacy.ledgerId === '0'); await assertAudit(1);
pass('legacy text omission remains default personal and does not send other owned ledger accounts');

const token = await api('v1/tokens/generate/mcp.json', member.token, { password, name: 'Synthetic recognition read', scopes: ['read'], ledgerId: a.id, expiresInSeconds: 86400 });
await control(); const rpc = await mcp(token, { ledger_id: b.id }); assert(!rpc.error); assert(!rpc.result.isError);
const draft = rpc.result.structuredContent || JSON.parse(rpc.result.content[0].text); assert.equal(draft.ledger_id, a.id); assert.equal(draft.account_id, account.id); assert.equal(draft.amount, '-5.00'); assert(!draft.tag_ids?.length); await assertAudit(1);
pass('normally generated MCP read token fixes recognition ledger, ignores caller escape argument and reports negative refund amount');

await control({ hold: true }); const pending = text(member, a.id, 403);
for (let i = 0; i < 60 && !(await audit()).requests; i++) await new Promise(r => setTimeout(r, 50));
assert.equal((await audit()).requests, 1);
const me = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, member.token)).find(row => row.isCurrentUser);
await api('v1/ledger/member/remove.json', owner.token, { ledgerId: a.id, memberId: me.id });
await fetch(`${mock}/release`, { method: 'POST' }); await pending;
await mcp(token, {}, 403); await assertAudit(1);
pass('membership revoked during real provider request prevents returning draft; existing MCP token is immediately denied without another provider request');

await control({ malformed: true, forbidden: [names.b, names.zero, names.member], required: [names.a] });
await text(owner, a.id, 500); await assertAudit(1);
const logs = execFileSync('docker', ['logs', 'finexy-ai-recognition-e2e'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
for (const marker of ['Synthetic private prompt marker', 'synthetic_private_response', names.a, names.tag]) assert(!logs.includes(marker), 'private synthetic content leaked to service log');
pass('malformed model JSON fails visibly; service logs contain no prompt, response, account or private tag markers');

assert.equal((await api(`v1/accounts/get.json?ledgerId=${a.id}&id=${account.id}`, owner.token)).balance, 10000);
assert.deepEqual(await api(`v1/ai/review/list.json?ledgerId=${a.id}`, owner.token), []);
pass('all recognition routes leave balances and review queue unchanged until explicit user action');
await writeFile(`${output}/http-summary.json`, JSON.stringify({ passed: checks.length, checks, externalProviderCalled: false, syntheticOnly: true, logsRedacted: true }, null, 2));
console.log(`AI recognition HTTP: ${checks.length} groups passed`);
