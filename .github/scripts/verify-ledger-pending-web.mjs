import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, access } from 'node:fs/promises';
import { resolve, relative, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { seedLedgerPendingWebFixture } from './ledger-pending-web-fixture.mjs';

// Real normal UI login, real queue APIs and production-created local journals.
// No store injection, response mocks, saved storageState or credential files.
const docker = process.env.DOCKER_CLI || 'C:/Users/abc123/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe';
assert(process.env.FINEXY_PENDING_RUNTIME && process.env.PLAYWRIGHT_MODULE);
const runtime = JSON.parse((await readFile(process.env.FINEXY_PENDING_RUNTIME, 'utf8')).replace(/^\uFEFF/, ''));
const base = runtime.url, target = new URL(base);
assert.equal(target.hostname, '127.0.0.1'); assert.equal(target.protocol, 'http:'); assert.equal(target.pathname, '/');
assert(Number(target.port) >= 18130 && Number(target.port) <= 18199); assert.equal(Number(target.port), runtime.port);
assert(!target.username && !target.password && !target.search && !target.hash);
const output = resolve(process.env.FINEXY_PENDING_EVIDENCE || 'artifacts/ledger-pending-20261023/browser/attempt1');
const artifactRelative = relative(resolve('artifacts/ledger-pending-20261023/browser'), output);
assert(artifactRelative && !artifactRelative.startsWith('..') && !isAbsolute(artifactRelative));
try { await access(resolve(output, 'summary.json')); throw new Error('Preserve earlier evidence; choose another attempt'); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const info = JSON.parse(execFileSync(docker, ['inspect', runtime.containerId], { encoding: 'utf8' }))[0];
assert.equal(info.Id, runtime.containerId); assert.equal(info.Name, `/${runtime.containerName}`);
assert(info.Name.startsWith('/finexy-pending-web-e2e-')); assert.equal(info.Image, runtime.imageId);
assert.equal(info.Config.Labels['finexy.pending.run'], runtime.runId); assert.equal(info.State.Running, true);
assert.equal(info.HostConfig.AutoRemove, true); assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes(`EBK_UUID_SERVER_ID=${runtime.uuidServerId}`));
assert.equal(runtime.cronUuidServerId, 239); assert.notEqual(runtime.cronUuidServerId, runtime.uuidServerId);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert.deepEqual(info.NetworkSettings.Ports['8080/tcp'], [{ HostIp: '127.0.0.1', HostPort: String(runtime.port) }]);
const password = randomBytes(24).toString('hex');
const checks = [], pages = [], jsErrors = [], consoleErrors = [], failedResources = [], geometries = [], readonlyWindows = [], networkFaults = [];
let browser, activePage, seed, lastHTTP, ordinaryAcceptance, paginationAcceptance, coldAcceptance, phase = 'health', currentStep = 'owned candidate health';
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds));
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const current = page => page.locator('.view-main > .page.page-current');
const homePanel = page => page.pendingPlatform === 'mobile' ? current(page).getByTestId('ledger-pending') : page.getByTestId('ledger-pending').first();
const escapeRegex = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
function step(page, name) { if (page) activePage = page; currentStep = `${page?.pendingPlatform || 'runner'}: ${name}`; }
function redact(value) {
    let text = String(value ?? '');
    for (const secret of [password, ...[seed?.owner, seed?.admin, seed?.member, seed?.viewer].map(actor => actor?.token)]) if (secret) text = text.replaceAll(secret, '[REDACTED]');
    return text;
}
async function until(fn, message) {
    for (let index = 0; index < 100; index++) { if (await fn()) return; await pause(200); }
    throw new Error(message);
}
async function api(path, actor, body, expected = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '0', ...(actor ? { Authorization: `Bearer ${actor.token}` } : {})
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    lastHTTP = { path: path.split('?')[0], status: response.status, expected }; assert.equal(response.status, expected, lastHTTP.path);
    const value = await response.json(); if (expected === 200) { assert.equal(value.success, true); assert(Object.hasOwn(value, 'result')); }
    return value.result;
}
function observe(page, platform) {
    page.pendingPlatform = platform; page.apiRequests = []; page.pendingTargets = []; page.createPosts = []; page.pendingNavigationEvents = [];
    page.on('pageerror', error => jsErrors.push({ platform, phase, step: currentStep, path: new URL(page.url()).pathname,
        route: new URL(page.url()).hash.split('?')[0], name: error.name, message: redact(error.message), stack: redact(error.stack).slice(0, 3000) }));
    page.on('console', message => { if (message.type() === 'error') consoleErrors.push({ platform, phase, message: redact(message.text()).slice(0, 1000), location: redact(message.location().url).split('?')[0] }); });
    page.on('response', response => { if (response.status() >= 400) failedResources.push({ platform, phase, status: response.status(), path: new URL(response.url()).pathname }); });
    page.on('requestfailed', request => failedResources.push({ platform, phase, path: new URL(request.url()).pathname, failure: request.failure()?.errorText }));
    page.on('request', request => {
        const url = new URL(request.url()); if (!url.pathname.startsWith('/api/')) return;
        page.apiRequests.push({ method: request.method(), path: url.pathname, ledgerId: url.searchParams.get('ledgerId'),
            ...(url.pathname.endsWith('/ai/review/list.json') ? { beforeId: url.searchParams.get('beforeId'), limit: url.searchParams.get('limit') } : {}), phase });
        if (url.pathname.endsWith('/templates/add_durable.json')) page.createPosts.push(request.postDataJSON());
    });
    page.on('framenavigated', frame => {
        if (frame !== page.mainFrame()) return;
        page.pendingNavigationEvents.push({ phase, step: currentStep, url: redact(frame.url()) });
        const hash = new URL(frame.url()).hash.replace(/^#/, '');
        if (!hash.startsWith('/')) return;
        const location = new URL(hash, 'https://route.invalid'); const value = location.searchParams.get('pendingTarget');
        if (value) { try { page.pendingTargets.push(JSON.parse(value)); } catch { /* malformed routes are rejected by the application */ } }
    });
}
async function settled(panel) {
    await panel.waitFor(); await panel.locator('.pending-updated').waitFor();
    await until(async () => await panel.getAttribute('aria-busy') === 'false', 'pending reads did not settle');
}
async function counts(panel, expected, exact = true) {
    await settled(panel);
    for (const [group, count] of Object.entries(expected)) await until(async () => (await panel.getByTestId(`pending-${group}`).locator('summary > strong').textContent())?.trim() === `${count} 项`, `wrong ${group} count`);
    if (exact) assert.match(await panel.locator('.pending-header p').textContent(), new RegExp(` · ${Object.values(expected).reduce((sum, value) => sum + value, 0)} 项$`));
}
async function expand(panel, group) {
    const detail = panel.getByTestId(`pending-${group}`); if (await detail.getAttribute('open') === null) await detail.locator('summary').click(); return detail;
}
async function refresh(page) {
    step(page, 'refresh current-ledger pending');
    const panel = homePanel(page); await settled(panel);
    await panel.getByRole('button', { name: '刷新待处理', exact: true }).click(); await settled(panel);
}
async function mobileNavigate(page, path) {
    step(page, `navigate ${path.split('?')[0]}`);
    await page.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
    await page.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
    await page.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
    await page.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange && !document.querySelector('.view-main.router-transition'));
    await current(page).waitFor();
}
async function home(page) {
    step(page, 'open current-ledger home');
    if (page.pendingPlatform === 'desktop') await page.goto(`${base}/desktop#/`, { waitUntil: 'networkidle' });
    else await mobileNavigate(page, '/');
    await settled(homePanel(page));
}
async function login(actor, platform) {
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'UTC', reducedMotion: 'reduce', viewport: platform === 'desktop' ? { width: 1440, height: 1080 } : { width: 375, height: 812 } });
    const page = await context.newPage(); page.setDefaultTimeout(20000); observe(page, platform); pages.push(page); step(page, 'normal UI login');
    await page.goto(`${base}/${platform}${platform === 'desktop' ? '#/login' : ''}`, { waitUntil: 'networkidle' });
    const form = platform === 'desktop' ? page : current(page);
    await form.locator('input[autocomplete="username"]:visible').fill(actor.username);
    const passwordInput = platform === 'mobile' ? form.getByPlaceholder('你的密码', { exact: true }) : form.locator('input[autocomplete="current-password"]:visible');
    await passwordInput.fill(password);
    if (platform === 'desktop') { await form.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(/#\/$/); }
    else { await form.getByText('登录', { exact: true }).click(); await current(page).getByText('当前账本：默认个人账本', { exact: true }).waitFor(); }
    await settled(homePanel(page)); return page;
}
async function changeLedger(page, ledger) {
    step(page, `select original ledger ${ledger.id}`);
    if (page.pendingPlatform === 'desktop') {
        await page.getByRole('button', { name: '切换当前账本', exact: true }).and(page.locator(':enabled')).waitFor();
        await page.getByRole('button', { name: '切换当前账本', exact: true }).click();
        await page.getByRole('listbox', { name: '可用账本', exact: true }).getByRole('option').filter({ hasText: ledger.name }).click();
        await page.getByRole('button', { name: '切换当前账本', exact: true }).filter({ hasText: ledger.name }).waitFor();
    } else {
        await mobileNavigate(page, '/ledger/manage'); await current(page).locator('.list .item-link').filter({ hasText: ledger.name }).click();
        const select = current(page).getByText('切换到此账本', { exact: true }); if (await select.isVisible()) await select.click();
        await current(page).getByText(new RegExp(`正在使用：${escapeRegex(ledger.name)}`)).waitFor(); await mobileNavigate(page, '/');
    }
    await settled(homePanel(page));
}
function readOnlyStart(page) { return page.apiRequests.length; }
function readOnlyEnd(page, index, label) {
    const requests = page.apiRequests.slice(index); assert.deepEqual(requests.filter(request => request.method !== 'GET'), [], `${label} must be read-only`);
    readonlyWindows.push({ label, platform: page.pendingPlatform, requests });
}
async function notifications(page, expected) {
    step(page, 'open current-ledger notification');
    const before = readOnlyStart(page); await page.getByRole('button', { name: '通知', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '当前账本通知与待处理', exact: true }); await dialog.waitFor();
    await counts(dialog.getByTestId('ledger-pending'), expected); readOnlyEnd(page, before, 'open notification and read original queues'); return dialog;
}
async function closeNotifications(page, dialog) {
    await dialog.getByRole('button', { name: '关闭待处理', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('button', { name: '通知', exact: true }).evaluate(element => element === document.activeElement), true, 'restore trigger keyboard focus');
}
async function verifyQueueNavigation(page) {
    const panel = homePanel(page); await expand(panel, 'ai');
    step(page, 'open exact original AI item without confirmation'); let before = readOnlyStart(page);
    await panel.getByTestId('pending-ai').getByRole('button', { name: '打开原复核条目', exact: true }).first().click();
    if (page.pendingPlatform === 'desktop') {
        const editor = page.getByRole('dialog').filter({ has: page.locator('.ai-review-confirmation-panel') });
        await editor.locator('.ai-review-confirmation-panel').getByRole('status').waitFor();
        await until(async () => !(await editor.locator('.ai-review-confirmation-panel').getByRole('status').textContent()).includes('正在核验'), 'original AI editor not ready');
        readOnlyEnd(page, before, 'desktop notification target opens original AI without confirming');
        await closeEditor(editor);
    } else {
        await current(page).locator('.ai-review-confirmation-panel').getByRole('status').waitFor();
        const route = await page.evaluate(() => document.querySelector('.view-main').f7View.router.currentRoute.url);
        const target = JSON.parse(new URL(route, base).searchParams.get('pendingTarget'));
        assert.equal(target.kind, 'ai-review'); assert.equal(target.ledgerId, seed.ledgerA.id); assert(seed.aiA.some(item => item.id === target.id));
        readOnlyEnd(page, before, 'mobile pending opens exact original AI without confirming');
    }
    await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 0 });
    await expand(homePanel(page), 'periodic'); before = readOnlyStart(page);
    step(page, 'locate exact original occurrence without confirmation');
    await homePanel(page).getByTestId('pending-periodic').getByRole('button', { name: '定位原到期记录', exact: true }).first().click();
    if (page.pendingPlatform === 'desktop') {
        await page.getByText('已定位原到期记录，核对后再确认入账', { exact: true }).waitFor();
        const focused = await page.evaluate(() => document.activeElement?.getAttribute('data-pending-occurrence'));
        assert(seed.occurrences.some(row => `${row.templateId}:${row.scheduledUnixTime}` === focused));
        readOnlyEnd(page, before, 'desktop pending focuses original occurrence without confirming');
    } else {
        await current(page).getByText('这里只定位原待确认记录。请核对内容，再明确选择确认或忽略。', { exact: true }).waitFor();
        await until(async () => await current(page).locator('.periodic-card').count() === 1, 'original occurrence not isolated');
        const route = await page.evaluate(() => document.querySelector('.view-main').f7View.router.currentRoute.url);
        const target = JSON.parse(new URL(route, base).searchParams.get('pendingTarget'));
        assert.equal(target.kind, 'periodic-review'); assert.equal(target.ledgerId, seed.ledgerA.id);
        assert(seed.occurrences.some(row => row.templateId === target.templateId && row.scheduledUnixTime === target.scheduledUnixTime));
        readOnlyEnd(page, before, 'mobile pending isolates original occurrence without confirming');
    }
    await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 0 });
    if (page.pendingPlatform === 'desktop') {
        const target = page.pendingTargets.findLast(target => target.kind === 'periodic-review'); assert(target);
        await changeLedger(page, seed.ledgerB); before = readOnlyStart(page);
        step(page, 'navigate an original A locator while B remains selected');
        await page.evaluate(path => { window.location.hash = path; }, `/schedule/list?pendingTarget=${encodeURIComponent(JSON.stringify(target))}`);
        await page.getByText(/无法打开原待处理/).waitFor();
        assert((await page.getByRole('button', { name: '切换当前账本', exact: true }).textContent()).includes(seed.ledgerB.name));
        assert.equal(await page.locator('.transaction-edit-dialog:visible').count(), 0);
        readOnlyEnd(page, before, 'foreign ledger target rejects without changing selected ledger');
        await home(page); await changeLedger(page, seed.ledgerA);
    }
    pass(`${page.pendingPlatform} original AI and occurrence navigation only reads, preserves queue counts and requires later explicit confirmation`);
}
async function verifyLateQueueRead(page) {
    step(page, 'delay real queue response while selecting another ledger');
    const cdp = await page.context().newCDPSession(page); await cdp.send('Network.enable');
    try {
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 900, downloadThroughput: -1, uploadThroughput: -1 });
        const requested = page.waitForRequest(request => { const url = new URL(request.url()); return url.pathname.endsWith('/ai/review/list.json') && url.searchParams.get('ledgerId') === seed.ledgerA.id; });
        await homePanel(page).getByRole('button', { name: '刷新待处理', exact: true }).click(); await requested;
        await changeLedger(page, seed.ledgerB);
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
        await counts(homePanel(page), { ai: 1, periodic: 0, local: 0 }); await pause(1500);
        await counts(homePanel(page), { ai: 1, periodic: 0, local: 0 });
    } finally { await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 }); await cdp.detach(); }
    await changeLedger(page, seed.ledgerA);
    pass('a real delayed A queue response cannot replace the current B summary');
}

async function verifyColdDocumentEntries() {
    const results = [];
    const originalAI = { kind: 'ai-review', ledgerId: seed.ledgerA.id, id: seed.aiA[0].id };
    const originalOccurrence = { kind: 'periodic-review', ledgerId: seed.ledgerA.id,
        templateId: seed.occurrences[0].templateId, scheduledUnixTime: seed.occurrences[0].scheduledUnixTime };
    const pathFor = target => `${target.kind === 'periodic-review' ? '/schedule/list' : '/transaction/list'}?pendingTarget=${encodeURIComponent(JSON.stringify(target))}`;
    const authWrites = new Set(['/api/v1/tokens/refresh.json', '/api/v1/tokens/revoke.json']);
    async function prepare(selected = seed.ledgerA, beforeClose) {
        const warm = await login(seed.owner, 'desktop');
        await changeLedger(warm, selected);
        if (beforeClose) await beforeClose(warm);
        const context = warm.context();
        // An idle old page must not compete for the same refreshed token. Its
        // ordinary UI selection remains in the browser context's native storage.
        await warm.close();
        const page = await context.newPage(); page.setDefaultTimeout(20000); observe(page, 'desktop'); pages.push(page);
        page.coldDocuments = []; page.coldOriginalReads = [];
        page.on('request', request => {
            const url = new URL(request.url());
            if (request.isNavigationRequest() && request.resourceType() === 'document') page.coldDocuments.push(url.pathname);
            if (url.pathname === '/api/v1/ai/review/get.json') page.coldOriginalReads.push({ id: url.searchParams.get('id'), ledgerId: url.searchParams.get('ledgerId') });
        });
        return page;
    }
    async function navigate(page, path, label) {
        step(page, label); assert.equal(page.url(), 'about:blank');
        await page.goto(`${base}/desktop#${path}`, { waitUntil: 'networkidle' });
        assert(page.coldDocuments.includes('/desktop'), 'the locator is initialized in a new document, not a hash-only navigation');
    }
    async function originalAIReady(page, target = originalAI) {
        const editor = page.getByRole('dialog').filter({ has: page.locator('.ai-review-confirmation-panel') });
        await editor.locator('.ai-review-confirmation-panel').getByRole('status').waitFor();
        await until(async () => !(await editor.locator('.ai-review-confirmation-panel').getByRole('status').textContent()).includes('正在核验'), 'cold original AI editor did not finish its current-ledger check');
        assert(page.coldOriginalReads.some(read => read.id === target.id && read.ledgerId === target.ledgerId), 'GET the exact original AI record in its original ledger');
        assert((await page.getByRole('button', { name: '切换当前账本', exact: true }).textContent()).includes(seed.ledgerA.name));
        assert.deepEqual(JSON.parse(new URL(page.url().split('#')[1], base).searchParams.get('pendingTarget')), target, 'keep the original locator in the URL');
        assert.equal(await page.getByTestId('ledger-route-error').count(), 0);
        return editor;
    }
    function finish(page, label) {
        const writes = page.apiRequests.filter(request => request.method !== 'GET');
        assert.deepEqual(writes.filter(request => !authWrites.has(request.path)), [], 'cold startup may refresh its login, but never performs a financial action');
        readonlyWindows.push({ label, platform: 'desktop', normalAuthenticationWrites: writes, requests: page.apiRequests });
        return { label, documents: page.coldDocuments, originalReads: page.coldOriginalReads, requests: page.apiRequests };
    }

    let page = await prepare();
    await navigate(page, pathFor(originalAI), 'cold document opens the original A AI entry after restoring the real saved A choice');
    await originalAIReady(page);
    results.push(finish(page, 'cold original A AI target')); await page.context().close();
    pass('a genuine cold document restores saved A before opening the exact original AI entry, preserving its URL without financial POST');

    page = await prepare();
    await navigate(page, pathFor(originalOccurrence), 'cold document locates the exact original A occurrence');
    await page.getByText('已定位原到期记录，核对后再确认入账', { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => document.activeElement?.getAttribute('data-pending-occurrence')),
        `${originalOccurrence.templateId}:${originalOccurrence.scheduledUnixTime}`);
    assert((await page.getByRole('button', { name: '切换当前账本', exact: true }).textContent()).includes(seed.ledgerA.name));
    assert.deepEqual(JSON.parse(new URL(page.url().split('#')[1], base).searchParams.get('pendingTarget')), originalOccurrence);
    results.push(finish(page, 'cold original A occurrence target')); await page.context().close();
    pass('a genuine cold document restores A then focuses only the original occurrence without confirming or discarding it');

    page = await prepare();
    await navigate(page, '/transaction/list?action=expense', 'cold ordinary quick action waits for saved A and its live permission');
    const form = page.getByRole('dialog').filter({ has: page.locator('.transaction-edit-card') }); await form.waitFor();
    await form.getByRole('button', { name: '添加', exact: true }).waitFor();
    assert((await page.getByRole('button', { name: '切换当前账本', exact: true }).textContent()).includes(seed.ledgerA.name));
    assert((await form.getByRole('combobox', { name: '来源账户', exact: true }).locator('..').textContent()).includes(seed.accounts.get(seed.ledgerA.id).name));
    assert.equal(new URL(page.url().split('#')[1], base).searchParams.has('action'), false, 'consume quick action only after original-ledger readiness');
    assert(page.apiRequests.some(request => request.path === '/api/v1/ledger/member/list.json' && request.ledgerId === seed.ledgerA.id));
    results.push(finish(page, 'cold quick expense belongs to A')); await page.context().close();
    pass('a genuine cold expense quick action opens a form using A account only after live A selection and permission checks, with no automatic write');

    page = await prepare();
    const foreign = { kind: 'ai-review', ledgerId: seed.ledgerB.id, id: seed.aiB[0].id }, foreignPath = pathFor(foreign);
    await navigate(page, foreignPath, 'cold B target cannot replace the real saved A choice');
    const foreignError = page.getByTestId('ledger-route-error'); await foreignError.waitFor();
    assert.match(await foreignError.textContent(), /无法打开原待处理/);
    await pause(1800); assert.equal(await foreignError.isVisible(), true, 'failure stays visible rather than expiring as a toast');
    assert((await page.getByRole('button', { name: '切换当前账本', exact: true }).textContent()).includes(seed.ledgerA.name));
    assert.equal(await page.getByRole('dialog').filter({ has: page.locator('.transaction-edit-card') }).count(), 0);
    assert.equal(page.coldOriginalReads.length, 0, 'a foreign target must not read an alternative original entry');
    assert.equal(page.url().split('#')[1], foreignPath, 'failure retains the exact original URL');
    await foreignError.getByRole('button', { name: '重试打开原入口', exact: true }).click();
    await foreignError.waitFor(); assert.equal(page.url().split('#')[1], foreignPath);
    results.push(finish(page, 'foreign cold B target remains an explicit A-scoped error')); await page.context().close();
    pass('a cold foreign B locator keeps actual A and its original URL, shows a persistent retryable error and never falls back to personal ledger or opens a form');

    for (const endpoint of ['/api/v1/ledger/list.json', '/api/v1/ledger/member/list.json']) {
        page = await prepare(); let upstreamStatus = null, failed = 0, interceptFailure;
        const pattern = `${base}${endpoint}*`;
        await page.route(pattern, async route => {
            try {
                const response = await route.fetch(); upstreamStatus = response.status(); assert.equal(upstreamStatus, 200);
                await response.body(); failed++; await route.abort('failed');
            } catch (error) { interceptFailure = error; await route.abort('failed').catch(() => {}); }
        });
        await navigate(page, pathFor(originalAI), `cold original A entry with one real ${endpoint} reply lost`);
        const failure = page.getByTestId('ledger-route-error'); await failure.waitFor();
        if (interceptFailure) throw interceptFailure;
        assert.equal(failed, 1); assert.equal(upstreamStatus, 200);
        assert.equal(page.url().split('#')[1], pathFor(originalAI));
        assert.equal(page.coldOriginalReads.length, 0); assert.equal(await page.locator('.transaction-edit-card:visible').count(), 0);
        await pause(1800); assert.equal(await failure.isVisible(), true);
        await page.screenshot({ path: resolve(output, `cold-${endpoint.includes('member') ? 'membership' : 'selection'}-read-failure.png`), fullPage: true });
        await page.unroute(pattern);
        await failure.getByRole('button', { name: '重试打开原入口', exact: true }).click();
        await originalAIReady(page);
        networkFaults.push({ kind: 'lose one unchanged real read response', path: endpoint, upstreamStatus, failedReads: failed,
            businessResponseModified: false, recovery: 'explicit retry reads original A target after normal transport is restored' });
        results.push(finish(page, `cold ${endpoint} failure and explicit original-target retry`)); await page.context().close();
        pass(`a cold ${endpoint} failure preserves the original URL and fixed error; restoring transport plus explicit retry opens only the same A entry without financial POST`);
    }

    const removed = await api('v1/ledger/create.json', seed.owner, { type: 1, name: `Synthetic removed preference ${randomBytes(5).toString('hex')}` });
    page = await prepare(removed, async () => {
        assert.equal((await api(`v1/ledger/delete/preview.json?id=${removed.id}`, seed.owner)).canDelete, true);
        await api('v1/ledger/delete.json', seed.owner, { id: removed.id });
    });
    await navigate(page, pathFor(originalAI), 'cold original locator with a genuinely removed saved ledger preference');
    const staleError = page.getByTestId('ledger-route-error'); await staleError.waitFor();
    assert.match(await staleError.textContent(), /无法核验原入口/);
    assert.match(await staleError.textContent(), /不会改用默认账本/);
    assert.equal(page.url().split('#')[1], pathFor(originalAI));
    assert.equal(await page.getByRole('button', { name: '切换当前账本', exact: true }).count(), 0);
    assert.equal(page.coldOriginalReads.length, 0); assert.equal(await page.locator('.transaction-edit-card:visible').count(), 0);
    assert(!page.apiRequests.some(request => /\/(?:ai|schedule)\/review\//.test(request.path) && request.ledgerId === '0'), 'a stale saved preference cannot trigger a personal-ledger fallback');
    await staleError.getByRole('button', { name: '重试打开原入口', exact: true }).click(); await staleError.waitFor();
    assert.equal(page.url().split('#')[1], pathFor(originalAI));
    results.push(finish(page, 'removed actual saved ledger preference stays unresolved')); await page.context().close();
    pass('a genuinely deleted UI-selected ledger preference stays unavailable with its original locator and retry, never silently switching to A or personal ledger');
    await writeFile(resolve(output, 'cold-document-summary.json'), JSON.stringify({ status: 'passed', cases: results,
        preferencesSetByProductionUI: true, navigation: 'new page from about:blank in the normally authenticated browser context',
        financialWritesDuringEntry: 0, journalsInjected: false, businessResponsesMocked: false }, null, 2));
    return { cases: results.length, status: 'passed' };
}
async function verifyLateTargetABA(page) {
    step(page, 'hold the actual original AI GET reply while selecting A to B to A');
    const routePattern = '**/api/v1/ai/review/get.json?*'; let held = false, routeFailure, release;
    const gate = new Promise(done => { release = done; });
    let finished; const delivered = new Promise(done => { finished = done; });
    const before = readOnlyStart(page);
    await page.route(routePattern, async route => {
        const url = new URL(route.request().url());
        if (url.searchParams.get('ledgerId') !== seed.ledgerA.id) { await route.continue(); return; }
        try {
            const response = await route.fetch(); assert.equal(response.status(), 200);
            await response.body(); held = true; await gate; await route.fulfill({ response });
        } catch (error) { routeFailure = error; await route.abort().catch(() => {}); }
        finally { finished(); }
    });
    try {
        await expand(homePanel(page), 'ai');
        await homePanel(page).getByTestId('pending-ai').getByRole('button', { name: '打开原复核条目', exact: true }).first().click();
        await until(async () => held, 'original AI GET was not forwarded and held');
        const select = async ledger => {
            const trigger = page.getByRole('button', { name: '切换当前账本', exact: true });
            await trigger.and(page.locator(':enabled')).waitFor(); await trigger.click();
            await page.getByRole('listbox', { name: '可用账本', exact: true }).getByRole('option').filter({ hasText: ledger.name }).click();
            await trigger.filter({ hasText: ledger.name }).waitFor();
        };
        await select(seed.ledgerB); await select(seed.ledgerA);
        release(); await delivered; if (routeFailure) throw routeFailure; await pause(1500);
        assert.equal(await page.locator('.ai-review-confirmation-panel:visible').count(), 0, 'an A/B/A round trip must invalidate the old open action');
        readOnlyEnd(page, before, 'A/B/A navigation invalidates the delayed original AI open without financial operations');
        networkFaults.push({ kind: 'delay unchanged real response', path: '/api/v1/ai/review/get.json', upstreamStatus: 200,
            releasedAfter: 'selected ledger A/B/A', businessResponseModified: false, staleEditorOpened: false });
    } finally { release(); await page.unroute(routePattern); }
    await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 0 });
    pass('an unchanged real AI GET delivered after A/B/A cannot open a stale editor');
}
async function switchDesktopAccount(page, actor) {
    step(page, 'switch account through the normal login page');
    await page.locator('button.profile:visible, .finance-home button.user:visible').click();
    await page.getByText('切换账号', { exact: true }).click(); await page.waitForURL(/#\/login\?switch=1$/);
    await page.locator('input[autocomplete="username"]').fill(actor.username);
    await page.locator('input[autocomplete="current-password"]').fill(password);
    const reloaded = page.waitForEvent('domcontentloaded');
    await page.getByRole('button', { name: '登录', exact: true }).click(); await reloaded; await page.waitForURL(/#\/$/);
    await settled(homePanel(page));
}
async function verifyCrossTabSession(page) {
    step(page, 'real second-tab login while an original owner read is delayed');
    const peer = await page.context().newPage(); peer.setDefaultTimeout(20000); observe(peer, 'desktop'); pages.push(peer);
    await peer.goto(`${base}/desktop#/`, { waitUntil: 'networkidle' }); await settled(homePanel(peer));
    const cdp = await page.context().newCDPSession(page); await cdp.send('Network.enable');
    try {
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 1100, downloadThroughput: -1, uploadThroughput: -1 });
        const requested = page.waitForRequest(request => { const url = new URL(request.url()); return url.pathname.endsWith('/ai/review/list.json') && url.searchParams.get('ledgerId') === seed.ledgerA.id; });
        await homePanel(page).getByRole('button', { name: '刷新待处理', exact: true }).click(); await requested;
        const readonlyStart = readOnlyStart(page);
        await switchDesktopAccount(peer, seed.viewer); await counts(homePanel(peer), { ai: 0, periodic: 0, local: 0 });
        step(page, 'old tab suspends the previous account instead of refilling its old summary');
        await homePanel(page).getByRole('alert').filter({ hasText: '账号状态已变化' }).waitFor();
        assert.equal(await homePanel(page).locator('.pending-entries').count(), 0);
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
        await pause(1500); assert.equal(await homePanel(page).locator('.pending-group').count(), 0);
        assert.equal(await homePanel(page).getByRole('button', { name: '刷新待处理', exact: true }).isDisabled(), true);
        readOnlyEnd(page, readonlyStart, 'old tab suspends after a real other-account login');
        await page.reload({ waitUntil: 'networkidle' }); await counts(homePanel(page), { ai: 0, periodic: 0, local: 0 });
        await peer.close(); await switchDesktopAccount(page, seed.owner); await changeLedger(page, seed.ledgerA);
        await counts(homePanel(page), { ai: 2, periodic: 2, local: 0 });
    } finally {
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 }); await cdp.detach();
        if (!peer.isClosed()) await peer.close();
    }
    pass('real second-tab account switch suspends old summaries and delayed reads, then explicit reload and normal login restore only the matching account');
}
async function verifyRealQueuePagination() {
    step(null, 'seed 101 genuine pending AI records in an independent pagination ledger');
    const ledger = await api('v1/ledger/create.json', seed.owner, { type: 1, name: `Synthetic pending pagination ${randomBytes(5).toString('hex')}` });
    const ids = [];
    for (let index = 0; index < 101; index++) {
        const row = await api('v1/ai/review/create.json', seed.owner, { ledgerId: ledger.id, sourceType: 1,
            sourceText: 'Synthetic pagination candidate', clientSessionId: randomBytes(16).toString('hex') });
        ids.push(row.id);
    }
    assert.equal(new Set(ids).size, 101);
    const page = await login(seed.owner, 'desktop'); const before = page.apiRequests.length;
    await changeLedger(page, ledger); await counts(homePanel(page), { ai: 101, periodic: 0, local: 0 });
    const requests = page.apiRequests.slice(before).filter(request => request.path.endsWith('/ai/review/list.json') && request.ledgerId === ledger.id);
    assert(requests.some(request => request.beforeId === '0' && request.limit === '100'));
    assert(requests.some(request => request.beforeId !== '0' && ids.includes(request.beforeId) && request.limit === '100'));
    const group = await expand(homePanel(page), 'ai'); assert.equal(await group.locator('.pending-entries > li').count(), 10);
    const readonlyStart = readOnlyStart(page);
    await group.getByRole('button', { name: '继续显示（剩余 91 项）', exact: true }).click();
    assert.equal(await group.locator('.pending-entries > li').count(), 30);
    await counts(homePanel(page), { ai: 101, periodic: 0, local: 0 });
    readOnlyEnd(page, readonlyStart, 'show more only changes rendered navigation entries');
    const result = { ledgerId: ledger.id, records: 101, distinctIds: 101, realListRequests: requests, initialRenderedRows: 10, expandedRenderedRows: 30 };
    await page.context().close();
    pass('101 real AI candidates require two full API pages while render expansion preserves the complete count');
    return result;
}
async function maximumMobileFont(page) {
    step(page, 'select the maximum application text size through the real settings UI');
    await mobileNavigate(page, '/settings/textsize');
    const slider = current(page).locator('.range-slider'); await slider.waitFor();
    const bounds = await slider.boundingBox(); assert(bounds && bounds.width > 20);
    await slider.click({ position: { x: bounds.width - 2, y: bounds.height / 2 } });
    await current(page).locator('.preview-font-size-xxxx-large').waitFor();
    await page.getByText('checkmark_alt', { exact: true }).filter({ visible: true }).last().click();
    await page.waitForFunction(() => document.documentElement.classList.contains('font-size-xxxx-large'));
    await home(page);
    assert(await homePanel(page).evaluate(element => parseFloat(getComputedStyle(element).fontSize) >= 21));
}
async function openTemplate(page) {
    await page.goto(`${base}/desktop#/template/list`, { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: '新增模板', exact: true }).first().click();
    const editor = page.getByRole('dialog').filter({ has: page.locator('.template-creation-panel') });
    await editor.locator('.template-creation-panel').getByRole('status').waitFor(); return editor;
}
async function fillTemplate(page, editor, name) {
    const recovery = editor.locator('.template-creation-panel').getByRole('button', { name: '继续编辑已保存草稿', exact: true }); if (await recovery.isVisible()) await recovery.click();
    await editor.getByLabel('模板名称', { exact: true }).fill(name); await editor.getByLabel(/^支出金额/).fill('1.23');
    await editor.getByRole('combobox', { name: '分类', exact: true }).locator('..').click();
    const menu = page.locator('.two-column-select-menu').filter({ visible: true });
    await menu.getByText(seed.categoryRoot.name, { exact: true }).click(); await menu.getByText(seed.category.name, { exact: true }).click();
    await editor.getByLabel('模板名称', { exact: true }).click(); await editor.locator('.template-creation-panel').getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
}
async function closeEditor(editor) { await editor.getByRole('button', { name: '取消', exact: true }).click(); await editor.waitFor({ state: 'hidden' }); }
async function localRecovery(page) {
    step(page, 'create local template draft using the production editor'); let editor = await openTemplate(page);
    await fillTemplate(page, editor, 'Synthetic pending original draft'); await closeEditor(editor); await home(page);
    await counts(homePanel(page), { ai: 2, periodic: 2, local: 1 });
    let dialog = await notifications(page, { ai: 2, periodic: 2, local: 1 }); const before = readOnlyStart(page);
    await dialog.getByTestId('pending-local').getByRole('button', { name: '恢复原模板', exact: true }).first().click();
    editor = page.getByRole('dialog').filter({ has: page.locator('.template-creation-panel') }); await editor.waitFor();
    await editor.locator('.template-creation-panel').getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await editor.getByLabel('模板名称', { exact: true }).inputValue(), 'Synthetic pending original draft');
    readOnlyEnd(page, before, 'notification opens original local draft');
    const originalTarget = page.pendingTargets.findLast(target => target.kind === 'template-create' && target.recovery === 'draft'); assert(originalTarget);
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic replacement draft'); await editor.locator('.template-creation-panel').getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    await closeEditor(editor);
    // Successful pending entries now retain their original locator in the URL.
    // Revisit it from Home rather than issuing a no-op goto to the same URL.
    await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 1 });
    const staleBefore = readOnlyStart(page);
    await page.goto(`${base}/desktop#/template/list?pendingTarget=${encodeURIComponent(JSON.stringify(originalTarget))}`, { waitUntil: 'networkidle' });
    await page.getByText(/无法打开原待处理/).waitFor(); assert.equal(await page.locator('.template-creation-panel:visible').count(), 0);
    readOnlyEnd(page, staleBefore, 'replaced draft rejects the old target');
    await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 1 });
    pass('production-created local draft is deduplicated, opens by its original locator and rejects an older replaced target');

    step(page, 'produce a real committed template request with a dropped response'); editor = await openTemplate(page);
    await fillTemplate(page, editor, 'Synthetic pending durable request'); await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    let dropped = false;
    await page.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); assert.equal(response.status(), 200); await response.body(); dropped = true; await route.abort('failed'); });
    phase = 'lost real response'; const postIndex = page.createPosts.length;
    await editor.locator('.template-creation-panel').getByRole('button', { name: '确认创建模板', exact: true }).click();
    await editor.locator('.template-creation-panel').getByRole('button', { name: '重试原请求', exact: true }).waitFor(); assert(dropped);
    const originalRequest = page.createPosts[postIndex]; assert(originalRequest); await page.unroute('**/templates/add_durable.json'); await closeEditor(editor);
    phase = 'local pending original locator'; await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 1 });
    dialog = await notifications(page, { ai: 2, periodic: 2, local: 1 }); const originalBefore = readOnlyStart(page);
    await dialog.getByTestId('pending-local').locator('li[data-pending-state="unknown-result"]').getByRole('button', { name: '恢复原模板', exact: true }).first().click();
    editor = page.getByRole('dialog').filter({ has: page.locator('.template-creation-panel') });
    await editor.locator('.template-creation-panel').getByRole('button', { name: '重试原请求', exact: true }).waitFor();
    await editor.locator('.template-creation-panel').getByText('Synthetic pending durable request', { exact: false }).waitFor();
    readOnlyEnd(page, originalBefore, 'open pending original request without replay'); assert.equal(page.createPosts.length, postIndex + 1);
    await editor.locator('.template-creation-panel').getByRole('button', { name: '重试原请求', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal(page.createPosts.length, postIndex + 2); assert.deepEqual(page.createPosts[postIndex + 1], originalRequest);
    const templates = await api(`v1/transaction/templates/list.json?ledgerId=${seed.ledgerA.id}&templateType=1`, seed.owner);
    assert.equal(templates.filter(row => row.name === 'Synthetic pending durable request').length, 1);
    await home(page); await counts(homePanel(page), { ai: 2, periodic: 2, local: 0 });
    pass('real lost-response pending entry only opens its immutable original request; explicit retry reuses the same payload and produces one template');
}
async function geometry(page, label) {
    step(page, `layout ${label}`); const panel = homePanel(page); await settled(panel);
    for (const key of ['ai', 'periodic', 'local']) await expand(panel, key);
    const measured = await panel.evaluate(element => {
        const viewport = window.innerWidth; const bounds = element.getBoundingClientRect();
        return { viewport, panel: { left: bounds.left, right: bounds.right, width: bounds.width },
            overflow: element.scrollWidth > element.clientWidth + 2, fontSize: getComputedStyle(element).fontSize,
            darkClass: document.documentElement.classList.contains('dark'), rootClasses: document.documentElement.className,
            color: getComputedStyle(element).color, backgroundColor: getComputedStyle(element).backgroundColor,
            buttons: [...element.querySelectorAll('button')].filter(button => button.getClientRects().length).map(button => {
                const rect = button.getBoundingClientRect(); return { text: button.textContent.trim(), left: rect.left, right: rect.right, height: rect.height };
            }) };
    });
    assert(measured.panel.left >= -2 && measured.panel.right <= measured.viewport + 2, `${label} panel bounded`); assert.equal(measured.overflow, false);
    assert(measured.buttons.every(button => button.left >= -2 && button.right <= measured.viewport + 2 && button.height >= 47), `${label} controls reachable`);
    await panel.scrollIntoViewIfNeeded(); await page.screenshot({ path: resolve(output, `${label}.png`), fullPage: true }); geometries.push({ label, ...measured });
}
function expectedConsole(item) {
    const ratePath = '/api/v1/exchange_rates/latest.json';
    const rateFailureObserved = failedResources.some(resource => resource.phase === item.phase && resource.path === ratePath && resource.status === 400);
    if (rateFailureObserved && item.location === `${base}${ratePath}` && /^Failed to load resource: the server responded with a status of 400\b/.test(item.message)) return true;
    if (rateFailureObserved && item.location.startsWith(`${base}/js/`)
        && /^\[Finexy Error\] failed to retrieve latest exchange rates data AxiosError: Request failed with status code 400(?:\n|$)/.test(item.message)) return true;
    if (item.location === 'https://api.github.com/repos/SMTM-PH/finexy-ai-bookkeeping/releases/latest' && /^Failed to load resource: the server responded with a status of 403\b/.test(item.message)) return true;
    if (item.phase === 'negative offline' && item.location.startsWith(`${base}/api/`) && item.message === 'Failed to load resource: net::ERR_INTERNET_DISCONNECTED') return true;
    if (item.phase === 'lost real response' && item.location === `${base}/api/v1/transaction/templates/add_durable.json` && /^Failed to load resource: net::ERR_FAILED/.test(item.message)) return true;
    if (item.phase === 'ordinary recovery' && item.location === `${base}/api/v1/transactions/add.json` && /^Failed to load resource: net::ERR_FAILED/.test(item.message)) return true;
    if (item.phase === 'cold document entries' && [`${base}/api/v1/ledger/list.json`, `${base}/api/v1/ledger/member/list.json`].includes(item.location) && /^Failed to load resource: net::ERR_FAILED/.test(item.message)) return true;
    return item.phase === 'negative revoked membership' && item.location.startsWith(`${base}/api/`) && /^Failed to load resource: the server responded with a status of 403\b/.test(item.message);
}
try {
    await mkdir(output, { recursive: true }); assert.equal((await fetch(`${base}/healthz.json`)).status, 200);
    phase = 'synthetic fixture'; seed = await seedLedgerPendingWebFixture(api, password, async () => {
        const startedAt = new Date().toISOString(); let completed = false;
        try {
            execFileSync(docker, ['exec', '-e', `EBK_UUID_SERVER_ID=${runtime.cronUuidServerId}`, runtime.containerId,
                '/ezbookkeeping/ezbookkeeping', 'cron', 'run', '--name', 'CreateScheduledTransaction'], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 });
            completed = true;
        } finally {
            await writeFile(resolve(output, 'cron-run.json'), JSON.stringify({ webUuidServerId: runtime.uuidServerId, cronUuidServerId: runtime.cronUuidServerId,
                distinctServerIds: runtime.uuidServerId !== runtime.cronUuidServerId, startedAt, finishedAt: new Date().toISOString(), completed }, null, 2));
        }
    });
    pass('real APIs and cron create A/B/0 queues plus an already booked AI result without automatic occurrence posting');
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    phase = 'desktop current-ledger and notification'; const desktop = await login(seed.owner, 'desktop');
    await counts(homePanel(desktop), { ai: 1, periodic: 0, local: 0 });
    await changeLedger(desktop, seed.ledgerA); await counts(homePanel(desktop), { ai: 2, periodic: 2, local: 0 });
    let dialog = await notifications(desktop, { ai: 2, periodic: 2, local: 0 });
    await dialog.getByRole('button', { name: '关闭待处理', exact: true }).focus(); await desktop.keyboard.press('Tab');
    assert.equal(await dialog.evaluate(element => element.contains(document.activeElement)), true, 'native dialog contains keyboard focus');
    await desktop.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' });
    assert.equal(await desktop.getByRole('button', { name: '通知', exact: true }).evaluate(element => element === document.activeElement), true);
    await changeLedger(desktop, seed.ledgerB); await counts(homePanel(desktop), { ai: 1, periodic: 0, local: 0 });
    dialog = await notifications(desktop, { ai: 1, periodic: 0, local: 0 }); await closeNotifications(desktop, dialog);
    await changeLedger(desktop, seed.personal); await counts(homePanel(desktop), { ai: 1, periodic: 0, local: 0 });
    await changeLedger(desktop, seed.ledgerA); await counts(homePanel(desktop), { ai: 2, periodic: 2, local: 0 });
    pass('desktop homepage and notification share exact current-ledger A/B/0 counts and notification keyboard focus stays usable');
    phase = 'cold document entries'; coldAcceptance = await verifyColdDocumentEntries();
    phase = 'original desktop queue navigation'; await verifyQueueNavigation(desktop);
    phase = 'late real queue response'; await verifyLateQueueRead(desktop);
    phase = 'late original target after ABA'; await verifyLateTargetABA(desktop);
    phase = 'local recovery'; await localRecovery(desktop);
    phase = 'shared roles';
    let memberPage;
    for (const [actor, name] of [[seed.admin, 'admin'], [seed.member, 'member'], [seed.viewer, 'viewer']]) {
        const page = await login(actor, 'desktop'); await changeLedger(page, seed.ledgerA); await counts(homePanel(page), { ai: 2, periodic: 2, local: 0 });
        dialog = await notifications(page, { ai: 2, periodic: 2, local: 0 }); await closeNotifications(page, dialog);
        if (name === 'viewer') {
            await page.locator('.activities .status').filter({ hasText: '只读' }).first().waitFor();
            assert.equal(await homePanel(page).locator('.pending-entries').getByText(new RegExp(seed.confirmedAI.id)).count(), 0);
        }
        if (name === 'member') memberPage = page; else await page.context().close();
    }
    pass('admin, member and viewer read the same shared queues while an already booked readonly activity is excluded');
    phase = 'negative revoked membership'; step(memberPage, 'remove synthetic membership through real API, then re-read pending');
    await api('v1/ledger/member/remove.json', seed.owner, { ledgerId: seed.ledgerA.id, memberId: seed.memberIDs.get(seed.member) });
    await refresh(memberPage);
    assert.match(await homePanel(memberPage).locator('.pending-header p').textContent(), /待核验/);
    assert.match(await homePanel(memberPage).getByTestId('pending-ai').locator('summary > strong').textContent(), /待核验/);
    assert.match(await homePanel(memberPage).getByTestId('pending-periodic').locator('summary > strong').textContent(), /待核验/);
    assert((await memberPage.getByRole('button', { name: '切换当前账本', exact: true }).textContent()).includes(seed.ledgerA.name));
    await memberPage.context().close();
    pass('live membership removal makes counts unknown and preserves the original selected ledger without falling back to zero');
    phase = 'negative offline'; await desktop.context().setOffline(true);
    try { await refresh(desktop); await homePanel(desktop).locator('.pending-header p').filter({ hasText: '待核验' }).waitFor();
        assert.match(await homePanel(desktop).getByTestId('pending-ai').locator('summary > strong').textContent(), /待核验/);
        assert.match(await homePanel(desktop).getByTestId('pending-periodic').locator('summary > strong').textContent(), /待核验/);
    } finally { await desktop.context().setOffline(false); }
    phase = 'online recovery'; await refresh(desktop); await counts(homePanel(desktop), { ai: 2, periodic: 2, local: 0 });
    pass('a real offline queue read remains unknown and returns to exact counts only after a successful refresh');
    phase = 'mobile scopes and layout'; const mobile = await login(seed.owner, 'mobile');
    await counts(homePanel(mobile), { ai: 1, periodic: 0, local: 0 }); await changeLedger(mobile, seed.ledgerA); await counts(homePanel(mobile), { ai: 2, periodic: 2, local: 0 });
    await changeLedger(mobile, seed.ledgerB); await counts(homePanel(mobile), { ai: 1, periodic: 0, local: 0 }); await changeLedger(mobile, seed.ledgerA);
    phase = 'original mobile queue navigation'; await verifyQueueNavigation(mobile); phase = 'mobile scopes and layout';
    for (const width of [375, 768, 1024]) {
        await mobile.setViewportSize({ width, height: 1000 });
        for (const colorScheme of ['light', 'dark']) {
            await mobile.emulateMedia({ colorScheme });
            await until(async () => await mobile.evaluate(() => document.documentElement.classList.contains('dark')) === (colorScheme === 'dark'), 'mobile system theme did not apply');
            await geometry(mobile, `mobile-${width}-${colorScheme}`);
        }
    }
    await maximumMobileFont(mobile); await geometry(mobile, 'mobile-maximum-application-font');
    await mobile.setViewportSize({ width: 375, height: 520 }); await homePanel(mobile).getByRole('button', { name: '刷新待处理', exact: true }).focus();
    assert.equal(await homePanel(mobile).getByRole('button', { name: '刷新待处理', exact: true }).evaluate(element => element === document.activeElement), true);
    await geometry(mobile, 'mobile-short-viewport-focused-control');
    await desktop.setViewportSize({ width: 1440, height: 1080 }); await geometry(desktop, 'desktop-1440');
    await desktop.evaluate(() => { document.documentElement.style.zoom = '1.25'; }); await geometry(desktop, 'desktop-1440-125-percent');
    pass('mobile A/B/0 scopes and 375/768/1024 system themes, real maximum application font, short focused viewport and desktop 125% retain bounded pending controls');
    phase = 'financial invariance';
    for (const ledger of [seed.personal, seed.ledgerA, seed.ledgerB]) assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${seed.accounts.get(ledger.id).id}`, seed.owner)).balance, seed.balances.get(ledger.id));
    assert.equal((await api(`v1/ai/review/list.json?ledgerId=${seed.ledgerA.id}&status=1&beforeId=0&limit=100`, seed.owner)).length, 2);
    assert.equal((await api(`v1/schedule/review/list.json?ledgerId=${seed.ledgerA.id}&status=1&offset=0&limit=100`, seed.owner)).length, 2);
    pass('navigation and template recovery preserve all baseline balances and pending financial confirmations');
    phase = 'real queue pagination'; paginationAcceptance = await verifyRealQueuePagination();
    phase = 'real cross-tab account switch'; await verifyCrossTabSession(desktop);
    phase = 'ordinary recovery';
    const { verifyOrdinaryPendingWeb } = await import('./ledger-pending-ordinary-web.mjs');
    ordinaryAcceptance = await verifyOrdinaryPendingWeb({ browser, base, api, seed, password, output, pass, step, login,
        changeLedger, home, refresh, counts, homePanel, mobileNavigate, readOnlyStart, readOnlyEnd, notifications, closeNotifications });
    assert.deepEqual(jsErrors, []); assert.deepEqual(consoleErrors.filter(item => !expectedConsole(item)), []);
    await writeFile(resolve(output, 'summary.json'), JSON.stringify({ status: 'passed', checks, count: checks.length, containerId: info.Id, imageId: info.Image,
        sourceSHA256: runtime.sourceSHA256, binarySHA256: runtime.binarySHA256, webUuidServerId: runtime.uuidServerId, cronUuidServerId: runtime.cronUuidServerId,
        mounts: 0, credentialsPersisted: false, storageStateSaved: false,
        storeInjected: false, queueResponsesMocked: false, localJournalsCreatedByProductionUI: true, unexpectedJavaScriptErrors: 0,
        unexpectedConsoleErrors: 0, expectedConsoleErrors: consoleErrors, failedResources, readonlyWindows, geometries, ordinaryAcceptance, paginationAcceptance, coldAcceptance, networkFaults,
        optionalExchangeRateFailurePolicy: { endpoint: '/api/v1/exchange_rates/latest.json', httpStatus: 400,
            classification: 'Only the actual same-phase HTTP 400 resource event and its matching store logger are expected; every JavaScript unhandled error remains forbidden.',
            cachePreservation: 'Verified separately by the background store tests; this browser does not inject a cached rate snapshot.' },
        notCovered: ['physical mobile soft keyboard', 'manual TalkBack or full accessibility', 'Android device', 'deployment'] }, null, 2));
} catch (error) {
    await mkdir(output, { recursive: true }); let screenshot = null;
    if (activePage && !activePage.isClosed()) try { screenshot = 'failure.png'; await activePage.screenshot({ path: resolve(output, screenshot), fullPage: true, mask: [activePage.locator('input[type="password"]')] }); } catch { screenshot = null; }
    const pageState = activePage && !activePage.isClosed() ? { url: redact(activePage.url()),
        bodyText: redact((await activePage.locator('body').innerText().catch(() => '')).slice(0, 18000)),
        apiRequests: activePage.apiRequests?.slice(-80), pendingTargets: activePage.pendingTargets, navigationEvents: activePage.pendingNavigationEvents } : null;
    await writeFile(resolve(output, 'failure.json'), JSON.stringify({ status: 'failed', phase, currentStep, errorType: error.name, message: redact(error.message), stack: redact(error.stack), pageState,
        checks, lastHTTP, screenshot, javascriptErrors: jsErrors, consoleErrors, failedResources, credentialsPersisted: false, storeInjected: false, queueResponsesMocked: false }, null, 2));
    console.error(`FAIL ${phase} at ${currentStep}: ${error.name}`); process.exitCode = 1;
} finally { if (browser) await browser.close(); }
