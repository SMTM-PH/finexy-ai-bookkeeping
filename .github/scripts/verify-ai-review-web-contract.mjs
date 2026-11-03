import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';

const base = 'http://127.0.0.1:18133', host = 'http://127.0.0.1:18134';
const container = 'finexy-ai-review-web-contract-e2e';
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Name, `/${container}`); assert.equal(info.Config.Image, 'finexy-ai-review-web-contract:20261006-root1');
assert.deepEqual(info.Mounts, []); assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=255'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18133'));
const output = 'artifacts/ai-review-web-contract-20261006', checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const password = randomBytes(18).toString('hex');
let browser, vite;
async function api(route, auth, body, status = 200) {
    const response = await fetch(`${base}/api/${route}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(auth ? { Authorization: `Bearer ${auth.token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, status, route.split('?')[0]); const data = await response.json();
    if (status === 200) { assert(data.success); assert(Object.hasOwn(data, 'result')); } return data.result;
}
async function register() {
    const username = `aiweb_${randomBytes(6).toString('hex')}`;
    const auth = await api('register.json', null, { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic contract QA',
        language: 'zh-Hans', defaultCurrency: 'USD', categories: [] });
    assert(auth.user?.username); return auth;
}
const attempt = async (page, method, args) => page.evaluate(async ({ method, args }) => {
    try { return { ok: true, result: await window.contract.store[method](...args) }; }
    catch (error) { return { ok: false, message: error.message }; }
}, { method, args });
async function boot(context, auth, ledgerId) {
    const page = await context.newPage(); await page.goto(`${host}/contract-host.html`);
    await page.evaluate(async ({ auth, ledgerId }) => {
        const module = await import('/.github/scripts/fixtures/ai-review-web-host.ts'); window.contract = module.boot(auth, ledgerId);
    }, { auth, ledgerId }); return page;
}
try {
    await mkdir(output, { recursive: true });
    for (let i = 0; i < 60; i++) { try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* Starting. */ }
        if (i === 59) throw new Error('Isolated candidate unavailable'); await new Promise(resolve => setTimeout(resolve, 250)); }
    const owner = await register(), member = await register(), viewer = await register();
    const a = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic AI contract A' });
    const b = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic AI contract B' });
    for (const [auth, role] of [[member, 3], [viewer, 4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner, { ledgerId: a.id, inviteeName: auth.user.username, role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', auth, { token: invitation.token });
    }
    const account = await api('v1/accounts/add.json', owner, { ledgerId: a.id, name: 'Synthetic AI account', category: 2, type: 1,
        icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 });
    const root = await api('v1/transaction/categories/add.json', owner, { type: 2, name: 'Synthetic root', icon: '1', color: '000000' });
    const category = await api('v1/transaction/categories/add.json', owner, { type: 2, name: 'Synthetic expense', parentId: root.id, icon: '1', color: '000000' });
    const capabilityResponse = await fetch(`${base}/api/v1/ai/review/capabilities.json?ledgerId=${a.id}`, { headers: { Authorization: `Bearer ${owner.token}` } });
    assert.equal(capabilityResponse.headers.get('cache-control'), 'no-store'); assert.equal((await capabilityResponse.json()).result.recognitionLedgerContext, true);
    vite = await createServer({ configFile: false, root: process.cwd(), publicDir: false, plugins: [vue(), {
        name: 'isolated-ai-contract-host', configureServer(server) { server.middlewares.use('/contract-host.html', (_request, response) => {
            response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><html lang="zh-CN"><title>Isolated store contract host</title><body>Isolated store test host</body></html>');
        }); },
    }], resolve: { alias: { '@': path.resolve('src') } },
    define: { __EZBOOKKEEPING_IS_PRODUCTION__: false, __EZBOOKKEEPING_VERSION__: JSON.stringify('1.9.3'),
        __EZBOOKKEEPING_BUILD_UNIX_TIME__: JSON.stringify(''), __EZBOOKKEEPING_BUILD_COMMIT_HASH__: JSON.stringify('contract-host'),
        __EZBOOKKEEPING_CONTRIBUTORS__: '[]', __EZBOOKKEEPING_LICENSE__: JSON.stringify(''), __EZBOOKKEEPING_THIRD_PARTY_LICENSES__: '[]' },
    server: { host: '127.0.0.1', port: 18134, strictPort: true, proxy: { '/api': { target: base, changeOrigin: true } } },
    optimizeDeps: { include: ['vue', 'pinia', 'axios', 'crypto-js', 'moment-timezone'] }, logLevel: 'error' });
    await vite.listen();
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext(), page = await boot(context, owner, a.id);
    const createRequest = { ledgerId: a.id, clientSessionId: 'synthetic-create-lost', sourceType: 1, sourceText: 'Synthetic original source',
        failureReason: '', recognizedData: { ledgerId: a.id, type: 3, sourceAmount: -123, sourceAccountId: account.id, categoryId: category.id } };
    let createWrites = 0, confirmWrites = 0;
    context.on('request', request => { if (request.url().endsWith('/ai/review/create.json')) createWrites++;
        if (request.url().endsWith('/ai/review/confirm.json')) confirmWrites++; });
    await page.route('**/api/v1/ai/review/create.json', async route => {
        const response = await route.fetch(); assert.equal(response.status(), 200); await route.abort('failed');
    });
    assert.equal((await attempt(page, 'create', [createRequest])).ok, false); assert.equal(createWrites, 1);
    await page.unroute('**/api/v1/ai/review/create.json'); await page.reload();
    await page.evaluate(async ({ owner, ledgerId }) => { const module = await import('/.github/scripts/fixtures/ai-review-web-host.ts'); window.contract = module.boot(owner, ledgerId); }, { owner, ledgerId: a.id });
    const pendingCreate = await attempt(page, 'readPendingCreate', [a.id]); assert.deepEqual(pendingCreate.result.request, createRequest);
    const created = await attempt(page, 'retryCreate', [a.id, createRequest.clientSessionId]); assert(created.ok); assert.equal(createWrites, 2);
    const id = created.result.receipt.id;
    assert((await attempt(page, 'get', [a.id, id])).ok);
    assert((await attempt(page, 'acknowledgeCreate', [a.id, createRequest.clientSessionId, id])).ok);
    pass('real browser storage persists source creation before HTTP and restores same key after committed response loss and reload');

    const confirmRequest = { ledgerId: a.id, id, transaction: { ledgerId: a.id, clientSessionId: 'synthetic-confirm-lost', type: 3,
        categoryId: category.id, time: Math.floor(Date.now() / 1000) - 60, utcOffset: 480, sourceAccountId: account.id, destinationAccountId: '0',
        sourceAmount: -123, destinationAmount: 0, hideAmount: true, tagIds: [], pictureIds: [], comment: 'Synthetic refund' } };
    await page.route('**/api/v1/ai/review/confirm.json', async route => {
        const response = await route.fetch(); assert.equal(response.status(), 200); await route.abort('failed');
    });
    assert.equal((await attempt(page, 'confirm', [confirmRequest])).ok, false); assert.equal(confirmWrites, 1);
    await page.unroute('**/api/v1/ai/review/confirm.json'); await page.reload();
    await page.evaluate(async ({ owner, ledgerId }) => { const module = await import('/.github/scripts/fixtures/ai-review-web-host.ts'); window.contract = module.boot(owner, ledgerId); }, { owner, ledgerId: a.id });
    assert.deepEqual((await attempt(page, 'readPendingConfirm', [a.id])).result.request, confirmRequest);
    assert.equal((await attempt(page, 'confirm', [{ ...confirmRequest, transaction: { ...confirmRequest.transaction, clientSessionId: 'replacement' } }])).ok, false);
    const second = await boot(context, owner, a.id);
    const retries = await Promise.all([attempt(page, 'retryConfirm', [a.id, id, confirmRequest.transaction.clientSessionId]),
        attempt(second, 'retryConfirm', [a.id, id, confirmRequest.transaction.clientSessionId])]);
    assert(retries.every(result => result.ok)); assert.equal(retries[0].result.receipt.transactionId, retries[1].result.receipt.transactionId);
    const accountRows = await api(`v1/accounts/list.json?ledgerId=${a.id}`, owner); assert.equal(accountRows.find(row => row.id === account.id).balance, 10123);
    pass('atomic confirmation survives committed response loss, reload and two real IndexedDB tabs; refund affects balance once');

    const knownWrites = confirmWrites;
    await page.route('**/api/v1/ai/review/get.json?*', route => route.abort('failed'));
    assert.equal((await attempt(page, 'get', [a.id, id])).ok, false);
    assert((await attempt(page, 'retryConfirm', [a.id, id, confirmRequest.transaction.clientSessionId])).ok); assert.equal(confirmWrites, knownWrites);
    await page.unroute('**/api/v1/ai/review/get.json?*');
    assert((await attempt(page, 'get', [a.id, id])).ok);
    assert((await attempt(page, 'acknowledgeConfirm', [a.id, id, confirmRequest.transaction.clientSessionId, retries[0].result.receipt.transactionId])).ok);
    assert.equal((await attempt(second, 'readPendingConfirm', [a.id])).result, null);
    pass('known receipt survives read failure with no additional financial HTTP; acknowledgement is visible to other tab');

    const seeded = await Promise.all(Array.from({ length: 101 }, (_, index) => api('v1/ai/review/create.json', owner, {
        ...createRequest, clientSessionId: `synthetic-page-${index}`, sourceText: `Synthetic page ${index}`,
    })));
    assert((await attempt(page, 'load', [a.id])).ok);
    const rows = await page.evaluate(() => window.contract.store.items.map(row => row.id)); assert.equal(rows.length, 101); assert.equal(new Set(rows).size, 101);
    assert(rows.includes(seeded[100].id));
    await page.route('**/api/v1/ai/review/list.json?*', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{"success":true}' }));
    assert.equal((await attempt(page, 'load', [a.id])).ok, false); assert.equal(await page.evaluate(() => window.contract.store.loaded), false);
    assert.equal(await page.evaluate(() => window.contract.store.items.length), 0); await page.unroute('**/api/v1/ai/review/list.json?*');
    pass('101 actual queued rows fully paginate with exact IDs; missing result is a failure and never an empty valid snapshot');

    let release, entered; const held = new Promise(resolve => { entered = resolve; }), gate = new Promise(resolve => { release = resolve; });
    await page.route('**/api/v1/ai/review/list.json?*', async route => { const response = await route.fetch(); entered(); await gate; await route.fulfill({ response }); });
    const lateLoad = attempt(page, 'load', [a.id]); await held;
    await page.evaluate(ledgerId => window.contract.select(ledgerId), b.id); release(); assert.equal((await lateLoad).ok, false);
    assert.equal(await page.evaluate(() => window.contract.store.items.length), 0); await page.unroute('**/api/v1/ai/review/list.json?*');
    await page.evaluate(ledgerId => window.contract.select(ledgerId), a.id);
    pass('actual late queue response cannot populate a different selected ledger');

    const viewerContext = await browser.newContext(), viewerPage = await boot(viewerContext, viewer, a.id);
    assert((await attempt(viewerPage, 'load', [a.id])).ok); assert.equal((await attempt(viewerPage, 'create', [createRequest])).ok, false);
    assert.equal((await attempt(viewerPage, 'confirm', [{ ...confirmRequest, id: seeded[0].id }])).ok, false);
    const memberContext = await browser.newContext(), memberPage = await boot(memberContext, member, a.id);
    assert.equal((await attempt(memberPage, 'confirm', [{ ...confirmRequest, id: seeded[0].id }])).ok, false);
    const memberCreate = { ...createRequest, clientSessionId: 'synthetic-member-create' }; const memberCreated = await attempt(memberPage, 'create', [memberCreate]); assert(memberCreated.ok);
    const memberId = memberCreated.result.receipt.id;
    await memberPage.route('**/api/v1/ai/review/confirm.json', async route => { const response = await route.fetch(); assert.equal(response.status(), 200); await route.abort('failed'); });
    const memberConfirm = { ...confirmRequest, id: memberId, transaction: { ...confirmRequest.transaction, clientSessionId: 'synthetic-member-confirm' } };
    assert.equal((await attempt(memberPage, 'confirm', [memberConfirm])).ok, false); await memberPage.unroute('**/api/v1/ai/review/confirm.json');
    const memberRow = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, member)).find(row => row.isCurrentUser);
    await api('v1/ledger/member/remove.json', owner, { ledgerId: a.id, memberId: memberRow.id });
    assert.equal((await attempt(memberPage, 'retryConfirm', [a.id, memberId, memberConfirm.transaction.clientSessionId])).ok, false);
    assert.deepEqual((await attempt(memberPage, 'readPendingConfirm', [a.id])).result.request, memberConfirm);
    pass('viewer remains read-only, member cannot confirm another creator and revoked unknown result retains original journal');

    await page.route('**/api/v1/ai/review/capabilities.json?*', route => route.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
    const oldRequest = { ...createRequest, clientSessionId: 'synthetic-old-service' }, oldWrites = createWrites;
    assert.equal((await attempt(page, 'create', [oldRequest])).ok, false); assert.equal(createWrites, oldWrites);
    assert.deepEqual((await attempt(page, 'readPendingCreate', [a.id])).result.request, oldRequest);
    await page.unroute('**/api/v1/ai/review/capabilities.json?*');
    const unknownId = seeded[1].id;
    assert((await attempt(page, 'change', ['dismiss', a.id, unknownId])).ok); assert((await attempt(page, 'load', [a.id, 3])).ok);
    assert.equal(await page.evaluate(() => window.contract.store.items.length), 1);
    assert((await attempt(page, 'change', ['restore', a.id, unknownId])).ok); assert((await attempt(page, 'get', [a.id, unknownId])).ok);
    pass('old service blocks writes and retains original create; ignored entries can be explicitly restored through real API');

    // Hold both detail reads before either tab obtains the real IDB lock. This
    // verifies local serialization rather than relying only on server dedupe.
    const competitionId = seeded[3].id;
    let detailReads = 0, releaseDetails, enteredDetails;
    const bothDetails = new Promise(resolve => { enteredDetails = resolve; });
    const detailsGate = new Promise(resolve => { releaseDetails = resolve; });
    await context.route('**/api/v1/ai/review/get.json?*', async route => {
        const response = await route.fetch(); detailReads++; if (detailReads === 2) enteredDetails();
        await detailsGate; await route.fulfill({ response });
    });
    const priorConfirmWrites = confirmWrites;
    const competing = ['synthetic-tab-one', 'synthetic-tab-two'].map((key, index) => attempt(index ? second : page, 'confirm', [{
        ...confirmRequest, id: competitionId, transaction: { ...confirmRequest.transaction, clientSessionId: key },
    }]));
    await Promise.race([bothDetails, new Promise((_resolve, reject) => setTimeout(() => reject(new Error('Both original detail reads did not arrive')), 15000))]);
    releaseDetails(); const competingResults = await Promise.all(competing); await context.unroute('**/api/v1/ai/review/get.json?*');
    assert.equal(competingResults.filter(result => result.ok).length, 1); assert.equal(confirmWrites - priorConfirmWrites, 1);
    const winner = competingResults.find(result => result.ok).result;
    assert.deepEqual((await attempt(page, 'readPendingConfirm', [a.id])).result, (await attempt(second, 'readPendingConfirm', [a.id])).result);
    await attempt(page, 'acknowledgeConfirm', [a.id, competitionId, winner.request.transaction.clientSessionId, winner.receipt.transactionId]);
    pass('two tabs preview different confirmation keys concurrently; actual IndexedDB lock permits only one financial HTTP and retains winning snapshot');

    await page.evaluate(ledgerId => window.contract.select(ledgerId), b.id);
    await page.evaluate(async ledgerId => {
        const state = await import('/src/lib/userstate.ts'); const key = `${state.getTransactionStateIdentity()}ai-review-confirm:${ledgerId}`;
        localStorage.setItem(key, JSON.stringify({ version: 1, scope: key, encrypted: false, data: 'null' }));
    }, b.id);
    const priorCorruptWrites = confirmWrites;
    assert.equal((await attempt(page, 'confirm', [{ ...confirmRequest, ledgerId: b.id, transaction: { ...confirmRequest.transaction, ledgerId: b.id } }])).ok, false);
    assert.equal(confirmWrites, priorCorruptWrites);
    await page.evaluate(async ledgerId => {
        const state = await import('/src/lib/userstate.ts'); localStorage.removeItem(`${state.getTransactionStateIdentity()}ai-review-confirm:${ledgerId}`);
    }, b.id);
    pass('corrupt real localStorage payload fails closed before confirmation HTTP instead of masquerading as no journal');
    await writeFile(`${output}/browser-contract-summary.json`, JSON.stringify({ passed: checks.length, checks, productionStore: true,
        realBrowserStorage: true, isolatedBackend: true, productionUIAccepted: false, externalProviderCalled: false }, null, 2));
    console.log(`AI Web contract acceptance: ${checks.length} checks passed`);
} finally { await browser?.close(); await vite?.close(); }
