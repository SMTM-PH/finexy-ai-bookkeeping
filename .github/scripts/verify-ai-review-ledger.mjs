import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';

// Fixed disposable target. Tokens and synthetic request bodies stay in memory.
const container = 'finexy-ai-ledger-e2e';
const base = 'http://127.0.0.1:18130';
const output = 'artifacts/ai-ledger-20261005';
const inspect = () => JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
const info = inspect();
assert.equal(info.Name, `/${container}`); assert.deepEqual(info.Mounts, []);
assert.equal(info.Config.Image, 'finexy-ai-ledger-candidate:20261005-root2');
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=251'));
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18130'));
const password = randomBytes(18).toString('hex');
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function healthy() {
    for (let i = 0; i < 60; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) return; } catch { /* Starting disposable service. */ }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('Disposable service failed to start');
}
async function api(path, token, body, status = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const data = await response.json();
    assert.equal(response.status, status, `HTTP ${path.split('?')[0]}`);
    if (status === 200) { assert.equal(data.success, true); assert(Object.hasOwn(data, 'result')); }
    return data.result;
}
async function register() {
    const username = `ai_queue_${randomBytes(6).toString('hex')}`;
    return { username, ...(await api('register.json', '', { username, password, email: `${username}@example.invalid`,
        nickname: 'Synthetic AI QA', language: 'zh-Hans', defaultCurrency: 'USD', categories: [] })) };
}
let proxy;
try {
    await mkdir(output, { recursive: true }); await healthy();
    const owner = await register(), admin = await register(), member = await register(), viewer = await register(), outsider = await register();
    const a = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic AI A' });
    const b = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic AI B' });
    for (const [user, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: a.id, inviteeName: user.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', user.token, { token: invitation.token });
        if (role === 2) {
            const me = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, user.token)).find(row => row.isCurrentUser);
            await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: me.id, role: 2 });
        }
    }
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 };
    const account = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: a.id, name: 'Synthetic A USD' });
    const bAccount = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: b.id, name: 'Synthetic B USD' });
    const personal = await api('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Synthetic personal USD' });
    const root = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic AI root', type: 2, icon: '1', color: '000000' });
    const leaf = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic AI expense', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const source = { ledgerId: a.id, sourceType: 1, sourceText: 'Synthetic uncertain receipt', clientSessionId: randomBytes(16).toString('hex') };
    const create = (user, body = {}, status = 200) => api('v1/ai/review/create.json', user.token, { ...source, clientSessionId: randomBytes(16).toString('hex'), ...body }, status);
    const queue = (user, ledger = a.id, status = 1, before = '0', limit = 100, http = 200) => api(`v1/ai/review/list.json?ledgerId=${ledger}&status=${status}&beforeId=${before}&limit=${limit}`, user.token, undefined, http);
    const get = (user, row) => api(`v1/ai/review/get.json?ledgerId=${row.ledgerId}&id=${row.id}`, user.token);
    const action = (verb, user, row, status = 200) => api(`v1/ai/review/${verb}.json`, user.token, { ledgerId: row.ledgerId, id: row.id }, status);
    const balance = acc => api(`v1/accounts/get.json?ledgerId=${acc.ledgerId || '0'}&id=${acc.id}`, owner.token).then(row => row.balance);
    const transaction = row => ({ ledgerId: row.ledgerId, type: 3, time: Math.floor(Date.now() / 1000), utcOffset: 480,
        categoryId: leaf.id, sourceAccountId: account.id, destinationAccountId: '0', sourceAmount: 500, destinationAmount: 0,
        tagIds: [], pictureIds: [], comment: 'Synthetic explicit confirmation', clientSessionId: randomBytes(16).toString('hex') });
    const confirmation = row => ({ ledgerId: row.ledgerId, id: row.id, transaction: transaction(row) });
    const confirm = (user, body, status = 200) => api('v1/ai/review/confirm.json', user.token, body, status);

    for (const user of [owner, admin, member, viewer]) {
        const capResponse = await fetch(`${base}/api/v1/ai/review/capabilities.json?ledgerId=${a.id}`, { headers: { Authorization: `Bearer ${user.token}` } });
        assert.equal(capResponse.status, 200); assert.equal(capResponse.headers.get('cache-control'), 'no-store');
        const cap = (await capResponse.json()).result; assert.equal(cap.ledgerId, a.id); assert.equal(cap.contractVersion, 1); assert(cap.atomicConfirmation && cap.payloadBinding);
    }
    await api(`v1/ai/review/capabilities.json?ledgerId=${a.id}`, outsider.token, undefined, 403);
    pass('authenticated no-store capability checks current ledger membership without granting write access');

    const ownerRow = await create(owner), memberRow = await create(member);
    for (const user of [owner, admin, member, viewer]) {
        const rows = await queue(user); assert.equal(rows.length, 2);
        for (const row of rows) {
            assert.equal(row.ledgerId, a.id); assert.equal(typeof row.id, 'string'); assert.equal(typeof row.creatorUid, 'string');
            assert.equal(row.editable, user === owner || user === admin || (user === member && row.id === memberRow.id));
        }
    }
    assert.deepEqual(await queue(owner, b.id), []); assert.deepEqual(await queue(owner, '0'), []);
    await queue(outsider, a.id, 1, '0', 100, 403);
    await create(viewer, {}, 403); await create(outsider, {}, 403);
    await action('dismiss', member, ownerRow, 403); await action('delete', viewer, memberRow, 403);
    assert.equal(await balance(account), 10000);
    pass('owner shard shared queue, manager-or-creator writes, read-only member and default/A/B isolation');

    const stable = { ...source, clientSessionId: randomBytes(16).toString('hex') };
    const created = await Promise.all(Array.from({ length: 12 }, () => api('v1/ai/review/create.json', member.token, stable)));
    assert(created.every(row => row.id === created[0].id));
    await api('v1/ai/review/create.json', member.token, { ...stable, sourceText: 'Synthetic different content' }, 409);
    await action('delete', member, created[0]);
    const replay = await api('v1/ai/review/create.json', member.token, stable); assert.equal(replay.id, created[0].id); assert(replay.deleted);
    await create(member, { clientSessionId: '' }, 400);
    await action('resolve', member, memberRow, 409);
    pass('12 concurrent creations share one content-bound receipt; tombstone replay and no legacy shared resolve');

    await action('dismiss', member, memberRow); await action('dismiss', member, memberRow);
    assert((await queue(viewer, a.id, 3)).some(row => row.id === memberRow.id));
    await action('restore', admin, memberRow);
    const body = confirmation(memberRow);
    await confirm(member, { ...body, transaction: { ...body.transaction, sourceAccountId: bAccount.id } }, 400);
    await confirm(member, { ...body, transaction: { ...body.transaction, ledgerId: b.id } }, 400);
    await confirm(member, { ...body, transaction: { ...body.transaction, categoryId: root.id } }, 400);
    await confirm(member, { ...body, transaction: { ...body.transaction, pictureIds: ['999999'] } }, 400);
    await confirm(owner, { ledgerId: a.id, id: memberRow.id }, 400);
    await confirm(owner, { ...body, id: Number(memberRow.id) }, 400);
    assert.equal((await get(owner, memberRow)).status, 1); assert.equal(await balance(account), 10000); assert.equal(await balance(bAccount), 10000);
    pass('dismiss/restore and invalid nested payload, numeric ID, foreign account, root category and private attachment leave balances unchanged');

    const confirmations = await Promise.all(Array.from({ length: 12 }, () => confirm(member, body)));
    assert(confirmations.every(row => row.transactionId === confirmations[0].transactionId));
    assert.equal(typeof confirmations[0].transactionId, 'string'); assert.equal(await balance(account), 9500);
    const posted = await api(`v1/transactions/get.json?ledgerId=${a.id}&id=${confirmations[0].transactionId}&trim_tag=true`, owner.token);
    assert.equal(posted.recorderUid, memberRow.creatorUid); assert.equal(posted.payerUid, memberRow.creatorUid);
    assert.equal((await get(owner, memberRow)).transactionId, confirmations[0].transactionId);
    await confirm(member, { ...body, transaction: { ...body.transaction, sourceAmount: 501 } }, 409);
    await confirm(member, { ...body, transaction: { ...body.transaction, clientSessionId: 'different-key' } }, 409);
    await action('restore', owner, memberRow, 409);
    pass('12 atomic confirmations post once and preserve actor plus original string transaction ID; modified retry conflicts');

    const lostRow = await create(member), lostBody = confirmation(lostRow);
    let lost = false, upstreamID;
    proxy = createServer(async (req, res) => {
        try {
            const chunks = []; for await (const chunk of req) chunks.push(chunk);
            const response = await fetch(`${base}/api/v1/ai/review/confirm.json`, { method: 'POST', headers: {
                'Content-Type': 'application/json', 'X-Timezone-Offset': '480', Authorization: `Bearer ${member.token}`,
            }, body: Buffer.concat(chunks), redirect: 'error' });
            assert.equal(response.status, 200); const result = await response.json(); assert(result.success); upstreamID = result.result.transactionId;
            lost = true; res.destroy(); // Only abort after the service has committed and replied.
        } catch { res.statusCode = 500; res.end(); }
    });
    await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
    try { await fetch(`http://127.0.0.1:${proxy.address().port}/`, { method: 'POST', body: JSON.stringify(lostBody) }); } catch { /* Expected committed response loss. */ }
    assert(lost); assert.equal(await balance(account), 9000);
    const started = inspect().State.StartedAt;
    execFileSync('docker', ['restart', info.Id], { stdio: 'pipe', timeout: 30000 });
    await healthy(); assert.notEqual(inspect().State.StartedAt, started);
    const recovered = await confirm(member, lostBody); assert.equal(recovered.transactionId, upstreamID); assert.equal(await balance(account), 9000);
    await new Promise(resolve => proxy.close(resolve)); proxy = undefined;
    pass('real committed HTTP response loss followed by service process restart returns persisted original receipt without another financial write');

    const me = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, member.token)).find(row => row.isCurrentUser);
    await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: me.id, role: 4 });
    await confirm(member, lostBody, 403); await action('delete', member, ownerRow, 403); await create(member, {}, 403);
    assert((await queue(member)).every(row => !row.editable));
    await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: me.id, role: 3 });
    assert.equal((await confirm(member, lostBody)).transactionId, upstreamID);
    pass('live downgrade denies creation, mutation and even receipt retries; regrant permits original request recovery');

    await api('v1/transactions/delete.json', owner.token, { ledgerId: a.id, id: recovered.transactionId });
    await action('delete', owner, lostRow);
    const tombstone = await get(viewer, lostRow); assert(tombstone.deleted); assert(!tombstone.editable); assert.equal(tombstone.transactionId, recovered.transactionId);
    assert.equal((await confirm(admin, lostBody)).transactionId, recovered.transactionId); assert.equal(await balance(account), 9500);
    pass('deleting transaction and queue retains confirmation receipt and never recreates deleted financial data');

    const legacy = await create(owner, { ledgerId: '0', clientSessionId: '' });
    await action('resolve', owner, legacy);
    const legacyBody = confirmation(legacy); legacyBody.transaction.sourceAccountId = personal.id;
    await confirm(owner, legacyBody, 409);
    assert.equal((await get(owner, legacy)).transactionId, '0'); assert.equal(await balance(personal), 10000);
    pass('old default create/resolve stays compatible; historical resolved rows are never inferred as atomic confirmations');

    const pages = await Promise.all(Array.from({ length: 105 }, (_, i) => create(owner, { sourceText: `Synthetic paginated queue ${i}` })));
    const first = await queue(owner), second = await queue(owner, a.id, 1, first.at(-1).id);
    assert.equal(first.length, 100); const ids = [...first, ...second].map(row => row.id);
    assert.equal(new Set(ids).size, ids.length); assert(pages.every(row => ids.includes(row.id)));
    await queue(owner, a.id, 1, '0', 101, 400);
    pass('keyset pagination reads beyond 100 records without omissions or duplicates and rejects invalid limits');

    const empty = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic AI reference only' });
    const reference = await create(owner, { ledgerId: empty.id });
    const preview = await api(`v1/ledger/delete/preview.json?id=${empty.id}`, owner.token);
    assert.equal(preview.aiReviewItemCount, 1); assert(!preview.canDelete); assert(preview.blockingReasons.includes('aiReviewItems'));
    await api('v1/ledger/delete.json', owner.token, { id: empty.id }, 409);
    await action('delete', owner, reference); await api('v1/ledger/delete.json', owner.token, { id: empty.id });
    pass('live AI queue blocks ledger deletion in preview and transaction; deleting the reference permits an otherwise empty ledger');

    const agent = await api('v1/tokens/generate/api.json', owner.token, { password, name: 'Synthetic AI boundary', scopes: ['read', 'transactions:write', 'accounts:manage', 'bills:import'], ledgerId: a.id, expiresInSeconds: 86400 });
    for (const path of ['list', 'get', 'capabilities']) await api(`v1/ai/review/${path}.json?ledgerId=${a.id}&id=${ownerRow.id}`, agent.token, undefined, 403);
    await api('v1/ai/review/confirm.json', agent.token, confirmation(ownerRow), 403);
    pass('API Agent tokens cannot enter session-only AI source, capability or confirmation routes even with all requested scopes');

    await writeFile(`${output}/http-summary.json`, JSON.stringify({ passed: checks.length, checks, restarted: true, syntheticOnly: true, productionTargets: [] }, null, 2));
    console.log(`AI queue HTTP acceptance: ${checks.length} groups passed`);
} finally {
    if (proxy) { proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)); }
}
