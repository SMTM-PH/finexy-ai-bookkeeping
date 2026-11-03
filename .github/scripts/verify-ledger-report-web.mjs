import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, relative, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { encodeLedgerReportAPI } from '../../testdata/ledger_report/fixture.mjs';
import { seedLedgerReportWebFixture, parseReportCSV, totalReportCSV } from './ledger-report-web-fixture.mjs';

// This runner only attaches to an operator-created, owned disposable candidate.
// It never starts Docker Desktop, mounts user data, mocks report responses,
// injects a store, saves storageState, or writes credentials into its evidence.
const docker = process.env.DOCKER_CLI || 'docker';
const container = process.env.FINEXY_REPORT_WEB_CONTAINER;
const imageId = process.env.FINEXY_REPORT_WEB_IMAGE_ID;
const base = process.env.FINEXY_REPORT_WEB_URL || 'http://127.0.0.1:18129';
const output = process.env.FINEXY_REPORT_WEB_EVIDENCE || 'artifacts/ledger-report-web-20261022';
assert(process.env.PLAYWRIGHT_MODULE, 'PLAYWRIGHT_MODULE must point to the available Playwright runtime');
assert(/^finexy-report-web-e2e-[a-zA-Z0-9_-]+$/.test(container || ''));
assert(/^sha256:[a-f0-9]{64}$/.test(imageId || ''));
const target = new URL(base);
assert.equal(target.origin, 'http://127.0.0.1:18129');
assert.equal(target.pathname, '/');
assert(!target.username && !target.password && !target.search && !target.hash);
const artifactRoot = resolve('artifacts');
const artifactRelative = relative(artifactRoot, resolve(output));
assert(artifactRelative && !artifactRelative.startsWith('..') && !isAbsolute(artifactRelative));
assert(artifactRelative.startsWith('ledger-report-web-'));
const info = JSON.parse(execFileSync(docker, ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Image, imageId);
assert.equal(info.Name, `/${container}`);
assert.equal(info.State.Running, true);
assert.equal(info.HostConfig.AutoRemove, true);
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.Config.Env.includes('EBK_DATA_ENABLE_EXPORT=true'));
assert.deepEqual(info.NetworkSettings.Ports['8080/tcp'].map(port => [port.HostIp, port.HostPort]), [['127.0.0.1', '18129']]);

const password = (await import('node:crypto')).randomBytes(24).toString('hex');
const checks = [];
const pages = [];
const jsErrors = [];
const consoleErrors = [];
const failedResources = [];
const downloads = [];
let browser;
let phase = 'health';
let currentStep = 'candidate health';
let activePage;
let seed;
let lastHTTP;
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
function step(page, name) {
    if (page) activePage = page;
    currentStep = `${page?.reportPlatform ?? 'runner'}: ${name}`;
}
function redact(value) {
    let text = String(value ?? '');
    for (const secret of [password, ...[seed?.owner, seed?.admin, seed?.member, seed?.viewer, seed?.outsider].map(actor => actor?.token)]) {
        if (secret) text = text.replaceAll(secret, '[REDACTED]');
    }
    return text;
}
function observeConsole(page, platform) {
    page.on('console', message => {
        if (message.type() === 'error') consoleErrors.push({ platform, phase,
            networkError: /^Failed to load resource:/.test(message.text()), name: 'BrowserConsoleError',
            message: redact(message.text()).slice(0, 1200), location: redact(message.location().url).split('?')[0] });
    });
    page.on('response', response => {
        if (response.status() >= 400) failedResources.push({ platform, phase, status: response.status(), path: new URL(response.url()).pathname });
    });
    page.on('requestfailed', request => failedResources.push({ platform, phase,
        path: new URL(request.url()).pathname, failure: request.failure()?.errorText ?? 'unknown' }));
}
function expectedConsoleError(item) {
    if (!item.networkError) return false;
    // The real upstream release check is unrelated to the local report APIs.
    // Preserve its exact 403 diagnostic; do not mock it or ignore arbitrary URLs.
    if (item.location === 'https://api.github.com/repos/SMTM-PH/finexy-ai-bookkeeping/releases/latest' &&
        /^Failed to load resource: the server responded with a status of 403\b/.test(item.message)) return true;
    if (item.phase === 'negative offline' && item.location === `${base}/api/v1/reports/query.json` &&
        item.message === 'Failed to load resource: net::ERR_INTERNET_DISCONNECTED') return true;
    return item.phase === 'negative changed data and role' &&
        (item.location === `${base}/api/v1/reports/export.csv` && /^Failed to load resource: the server responded with a status of 409\b/.test(item.message) ||
         item.location === `${base}/api/v1/reports/query.json` && /^Failed to load resource: the server responded with a status of 403\b/.test(item.message));
}
function observeLegacyRequest(page, request) {
    const path = new URL(request.url()).pathname;
    if (!/\/api\/v1\/transactions\/statistics(?:\.json|\/)/.test(path)) return;
    const route = page.reportPlatform === 'desktop' ? new URL(page.url()).hash.replace(/^#/, '').split('?')[0]
        : page.reportRoute ?? '/';
    const isReport = page.reportPlatform === 'desktop' ? ['/statistics/transaction', '/insights/explorer'].includes(route)
        : route === '/statistic/transaction';
    const record = { path, phase, route };
    if (isReport) page.legacyReportRequests.push(record);
    else page.otherSurfaceLegacyRequests.push(record);
}
async function api(path, actor, body, expected = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '0', ...(actor ? { Authorization: `Bearer ${actor.token}` } : {})
    }, ...(body === undefined ? {} : { body: encodeLedgerReportAPI(body) }) });
    lastHTTP = { path: path.split('?')[0], status: response.status, expected };
    assert.equal(response.status, expected, lastHTTP.path);
    const value = await response.json();
    if (expected === 200) { assert.equal(value.success, true); assert(Object.hasOwn(value, 'result')); }
    return value.result;
}
const current = page => page.locator('.view-main > .page.page-current');
const panel = page => (page.reportPlatform === 'mobile' ? current(page) : page).getByTestId('ledger-report-panel');
const escapeRegex = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const idOrder = (a, b) => a.length - b.length || a.localeCompare(b);
const major = value => {
    const amount = BigInt(value), absolute = amount < 0n ? -amount : amount;
    return `${amount < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`;
};
const reportResponse = response => new URL(response.url()).pathname.endsWith('/api/v1/reports/query.json');
async function result(response, expected = 200) {
    assert.equal(response.status(), expected, 'report HTTP response');
    const value = await response.json();
    assert.equal(value.success, true); assert(Object.hasOwn(value, 'result'));
    return value.result;
}
async function reportAction(page, action, predicate = () => true) {
    step(page, 'report action: wait for matching real query response');
    const response = page.waitForResponse(response => {
        if (!reportResponse(response)) return false;
        try { return predicate(response.request().postDataJSON().query); } catch { return false; }
    });
    const [responseValue] = await Promise.all([response, action()]);
    step(page, 'report action: validate query response');
    const value = await result(responseValue);
    step(page, 'report action: wait for update control after response');
    await panel(page).getByRole('button', { name: '更新报告', exact: true }).waitFor();
    return { value, request: responseValue.request().postDataJSON() };
}

async function mobileNavigate(page, path) {
    step(page, `mobile navigate ${path}: wait for router`);
    await page.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
    page.reportRoute = path;
    await page.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
    step(page, `mobile navigate ${path}: wait for active route`);
    await page.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
    await page.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange && !document.querySelector('.view-main.router-transition'));
    await current(page).waitFor();
}
async function openReports(page) {
    step(page, 'open reports: navigate to production route');
    if (page.reportPlatform === 'desktop') {
        // Normal login lands on HomePage, whose existing trend load completes
        // before its ledger chooser is enabled. Finish that visible state before
        // a full document navigation so its request is not misattributed to the
        // destination URL while the old document is unloading.
        if (new URL(page.url()).hash === '#/') {
            await page.getByRole('button', { name: '切换当前账本', exact: true }).and(page.locator(':enabled')).waitFor();
        }
        await page.goto(`${base}/desktop#/statistics/transaction`, { waitUntil: 'networkidle' });
    }
    else await mobileNavigate(page, '/statistic/transaction');
    step(page, 'open reports: wait for panel');
    await panel(page).waitFor();
    step(page, 'open reports: wait for update control');
    await panel(page).getByRole('button', { name: '更新报告', exact: true }).waitFor();
}
async function changeLedger(page, ledger) {
    step(page, 'change ledger: open ledger selection');
    if (page.reportPlatform === 'desktop') {
        await page.getByRole('button', { name: '切换当前账本', exact: true }).click();
        step(page, 'change ledger: select synthetic ledger');
        await page.getByRole('listbox', { name: '可用账本', exact: true }).getByRole('option').filter({ hasText: ledger.name }).click();
        step(page, 'change ledger: wait for selected ledger');
        await page.getByRole('button', { name: '切换当前账本', exact: true }).filter({ hasText: ledger.name }).waitFor();
    } else {
        await mobileNavigate(page, '/ledger/manage');
        step(page, 'change ledger: open synthetic ledger detail');
        await current(page).locator('.list .item-link').filter({ hasText: ledger.name }).click();
        const switchButton = current(page).getByText('切换到此账本', { exact: true });
        if (await switchButton.isVisible()) await switchButton.click();
        step(page, 'change ledger: wait for selected ledger notice');
        await current(page).getByText(new RegExp(`正在使用：${escapeRegex(ledger.name)}`)).waitFor();
        await mobileNavigate(page, '/statistic/transaction');
    }
    step(page, 'change ledger: wait for report panel');
    await panel(page).waitFor();
}
async function login(actor, platform) {
    step(null, `${platform} login: create ephemeral browser context`);
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'UTC', reducedMotion: 'reduce',
        viewport: platform === 'desktop' ? { width: 1440, height: 1080 } : { width: 390, height: 844 } });
    const page = await context.newPage(); page.reportPlatform = platform; page.actor = actor;
    page.setDefaultTimeout(20000);
    page.reportRequests = []; page.legacyReportRequests = []; page.otherSurfaceLegacyRequests = []; page.downloadCount = 0;
    page.on('pageerror', error => jsErrors.push({ platform, phase, name: error.name }));
    observeConsole(page, platform);
    page.on('request', request => {
        const path = new URL(request.url()).pathname;
        observeLegacyRequest(page, request);
        if (path.endsWith('/api/v1/reports/query.json')) {
            const query = request.postDataJSON()?.query;
            page.reportRequests.push({ ledgerId: query?.ledgerId, startDate: query?.startDate, endDate: query?.endDate });
        }
    });
    page.on('download', () => { ++page.downloadCount; });
    pages.push(page);
    step(page, 'login: navigate to normal login page');
    await page.goto(`${base}/${platform}${platform === 'desktop' ? '#/login' : ''}`, { waitUntil: 'networkidle' });
    const form = platform === 'desktop' ? page : current(page);
    step(page, 'login: fill normal credentials');
    const usernameInput = form.locator('input[autocomplete="username"]:visible');
    const passwordInput = platform === 'mobile' ? form.getByPlaceholder('你的密码', { exact: true })
        : form.locator('input[autocomplete="current-password"]:visible');
    await usernameInput.waitFor({ state: 'visible' });
    await passwordInput.waitFor({ state: 'visible' });
    assert.equal(await usernameInput.count(), 1, 'one visible login username input');
    assert.equal(await passwordInput.count(), 1, 'one uniquely named login password input');
    assert.equal(await passwordInput.getAttribute('autocomplete'), 'current-password');
    await usernameInput.fill(actor.username);
    await passwordInput.fill(password);
    if (platform === 'desktop') {
        step(page, 'login: submit normal login');
        await form.getByRole('button', { name: '登录', exact: true }).click();
        step(page, 'login: wait for authenticated home');
        await page.waitForURL(/#\/$/);
        await page.getByRole('button', { name: '切换当前账本', exact: true }).waitFor();
        await openReports(page);
    } else {
        step(page, 'login: submit normal login');
        await form.getByText('登录', { exact: true }).click();
        step(page, 'login: wait for authenticated ledger notice');
        await current(page).getByText('当前账本：默认个人账本', { exact: true }).waitFor();
    }
    await changeLedger(page, seed.ledgerA);
    return page;
}

async function switchDesktopAccount(page, actor) {
    step(page, 'switch account: open normal account login');
    // Home and the workspace use distinct existing account-menu buttons.
    const accountMenu = page.locator('button.profile:visible, .finance-home button.user:visible');
    await accountMenu.waitFor();
    assert.equal(await accountMenu.count(), 1, 'exactly one visible account menu');
    await accountMenu.click();
    await page.getByText('切换账号', { exact: true }).click();
    await page.waitForURL(/#\/login\?switch=1$/);
    await page.locator('input[autocomplete="username"]').fill(actor.username);
    await page.locator('input[autocomplete="current-password"]').fill(password);
    step(page, 'switch account: await the actual post-login document reload');
    const reloaded = page.waitForEvent('domcontentloaded');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await reloaded;
    await page.waitForURL(/#\/$/);
    await page.getByRole('button', { name: '切换当前账本', exact: true }).waitFor();
    assert.equal(new URL(page.url()).hash, '#/');
    page.actor = actor;
}

function mappedFilter(filter, resources) {
    return filter ? { ...filter, ids: filter.ids.map(id => id === '0' ? '0' : resources.get(id).id) } : null;
}
function mappedQuery(patch = {}, ledger = seed.ledgerA) {
    const q = { ...seed.fixture.query, ...patch, ledgerId: ledger.id };
    return { ...q, accounts: mappedFilter(q.accounts, seed.accounts), categories: mappedFilter(q.categories, seed.categories),
        tags: q.tags?.map(filter => ({ ...filter, ...(filter.ids ? { ids: filter.ids.map(id => seed.tags.get(id).id) } : {}) })) ?? null };
}
async function resourceChecks(page, label, selectedIDs) {
    step(page, `filters: open ${label} picker`);
    const summary = panel(page).locator('summary').filter({ hasText: new RegExp(`^选择${label}`) });
    const group = summary.locator('..');
    if (await group.getAttribute('open') === null) await summary.click();
    const checkboxes = group.getByRole('checkbox');
    step(page, `filters: clear ${label} selection`);
    for (const checkbox of await checkboxes.all()) if (await checkbox.isChecked()) await checkbox.uncheck();
    for (const id of selectedIDs) {
        step(page, `filters: select ${label} #${id}`);
        await group.getByRole('checkbox', { name: new RegExp(`#${escapeRegex(id)}(?![0-9])`) }).check();
    }
}

async function applyQuery(page, q) {
    const form = panel(page).getByTestId('report-filters');
    step(page, 'filters: set report month');
    await form.getByLabel('报告月份', { exact: true }).fill(q.startDate.slice(0, 7));
    step(page, 'filters: set start date');
    await form.getByLabel('开始日期', { exact: true }).fill(q.startDate);
    step(page, 'filters: set end date');
    await form.getByLabel('结束日期', { exact: true }).fill(q.endDate);
    step(page, 'filters: set IANA timezone');
    await form.getByLabel('IANA 时区', { exact: true }).fill(q.timezone);
    for (const [type, label] of [[1, '余额调整'], [2, '收入'], [3, '支出'], [4, '转账']]) {
        step(page, `filters: set ${label} type`);
        await form.getByRole('checkbox', { name: label, exact: true }).setChecked(!q.types || q.types.includes(type));
    }
    for (const [key, label, descendants] of [['accounts', '账户', '包含子账户'], ['categories', '分类', '包含子分类']]) {
        step(page, `filters: set ${label} matching mode`);
        await form.getByLabel(`${label}匹配方式`, { exact: true }).selectOption(q[key]?.mode ?? 'include');
        step(page, `filters: set ${label} descendants`);
        await form.getByRole('checkbox', { name: descendants, exact: true }).setChecked(q[key]?.includeDescendants ?? false);
        await resourceChecks(page, label, q[key]?.ids ?? []);
    }
    // The component owns the filters; these are normal user controls, not a store injection.
    await setTagFilters(page, q.tags ?? []);
    await setCurrencyFilter(page, q.currencies ?? []);
    await setHiddenPolicy(page, q.hidden ?? 'exclude');
    step(page, 'filters: submit report');
    const observed = await reportAction(page, () => form.getByTestId('report-apply').click(), request => request.ledgerId === q.ledgerId && request.startDate === q.startDate && request.endDate === q.endDate);
    assert.equal(observed.request.query.timezone, q.timezone);
    assert.deepEqual([...(observed.request.query.types ?? [1, 2, 3, 4])].sort(), [...(q.types ?? [1, 2, 3, 4])].sort());
    for (const key of ['accounts', 'categories']) {
        const actual = observed.request.query[key];
        if (!q[key]) assert(actual == null);
        else { assert.equal(actual.mode, q[key].mode); assert.deepEqual([...actual.ids].sort(idOrder), [...q[key].ids].sort(idOrder)); assert.equal(actual.includeDescendants, q[key].includeDescendants ?? false); }
    }
    return observed.value;
}

// Stable Chinese labels below are coordinated with the production component.
async function setHiddenPolicy(page, hidden) {
    step(page, 'filters: set hidden-record policy');
    await panel(page).getByLabel('隐藏记录', { exact: true }).selectOption(hidden);
}
async function setCurrencyFilter(page, currencies) {
    step(page, 'filters: set original currencies');
    await panel(page).getByLabel('原币种筛选（可选）', { exact: true }).fill(currencies.join(', '));
}
async function setTagFilters(page, filters) {
    const root = panel(page);
    step(page, 'filters: clear tag clauses');
    await root.getByRole('button', { name: '清除标签筛选', exact: true }).click();
    let groups = root.locator('.report-tag-clause');
    while (await groups.count() < Math.max(1, filters.length)) {
        step(page, 'filters: add tag clause');
        await root.getByRole('button', { name: '增加标签条件', exact: true }).click();
    }
    for (let i = 0; i < await groups.count(); i++) {
        const group = groups.nth(i), filter = filters[i];
        step(page, `filters: set tag clause ${i + 1} mode`);
        await root.getByLabel(`第${i + 1}组标签匹配方式`, { exact: true }).selectOption(filter?.mode ?? 'any');
        if (filter?.mode === 'none') continue;
        const picker = group.locator('details');
        step(page, `filters: open tag clause ${i + 1} picker`);
        if (await picker.getAttribute('open') === null) await picker.locator('summary').click();
        for (const checkbox of await group.getByRole('checkbox').all()) if (await checkbox.isChecked()) await checkbox.uncheck();
        for (const id of filter?.ids ?? []) {
            step(page, `filters: select tag clause ${i + 1} #${id}`);
            await group.getByRole('checkbox', { name: new RegExp(`#${escapeRegex(id)}(?![0-9])`) }).check();
        }
    }
}

function canonicalIDs(details) {
    const runtime = new Map([...seed.transactions].map(([id, row]) => [row.id, id]));
    return details.map(row => { assert(runtime.has(row.id), 'unknown logical row in fixture report'); return runtime.get(row.id); });
}
async function visibleBaseline(page, data, expectedIDs, expectedTotals) {
    step(page, 'visible snapshot: check logical rows and exact totals');
    assert.equal(data.dataComplete, true); assert.equal(data.matchedCount, expectedIDs.length);
    if (expectedTotals) assert.deepEqual(data.totals, expectedTotals);
    const actualIDs = await panel(page).getByTestId('report-detail-row').evaluateAll(rows => rows.map(row => row.getAttribute('data-report-id')));
    assert.deepEqual(actualIDs, data.details.map(row => row.id));
    assert.deepEqual(canonicalIDs(data.details), expectedIDs.slice(0, data.details.length));
    if (data.matchedCount === 0) {
        step(page, 'visible snapshot: wait for real empty state');
        await panel(page).getByRole('heading', { name: '此范围没有匹配流水', exact: true }).waitFor();
        assert.equal(await panel(page).getByTestId('report-totals').count(), 0);
        return;
    }
    step(page, 'visible snapshot: read currency totals');
    const text = (await panel(page).getByTestId('report-totals').innerText()).replaceAll(',', '');
    for (const money of data.totals) {
        assert(text.includes(money.currency));
        for (const key of ['income', 'expense', 'net']) assert(text.includes(major(money[key])), `visible ${key} matches the report snapshot`);
    }
    if (data.matchedCount) {
        for (const testid of ['report-trend', 'report-categories', 'report-tags']) {
            step(page, `visible snapshot: wait for ${testid}`);
            await panel(page).getByTestId(testid).waitFor();
        }
        assert((await panel(page).getByTestId('report-trend').innerText()).includes(data.trend[0].key));
    }
}

async function verifySharedCases(page) {
    for (const scenario of seed.fixture.cases) {
        const value = await applyQuery(page, mappedQuery(scenario.query));
        await visibleBaseline(page, value, scenario.detailIds, scenario.totals);
    }
}
async function visibleMoneySemantics(page) {
    step(page, 'money semantics: read refund and transfer rows');
    const row = id => panel(page).locator(`[data-report-id="${seed.transactions.get(id).id}"]`);
    const refund = seed.fixture.transactions.find(item => item.id === '3');
    assert((await row('3').innerText()).includes('退款'));
    assert((await row('3').innerText()).includes(major(refund.amount)));
    const transfer = await row('5').innerText();
    assert(transfer.includes('CNY') && transfer.includes('USD'));
    assert(transfer.includes(major(seed.fixture.expected.transfer.sourceAmount)) && transfer.includes(major(seed.fixture.expected.transfer.destinationAmount)));
    step(page, 'money semantics: open balance-adjustment detail');
    await row('9').getByRole('button').click();
    const detail = panel(page).getByRole('article', { name: '流水完整详情', exact: true });
    step(page, 'money semantics: wait for complete detail');
    await detail.waitFor();
    assert((await detail.innerText()).includes('调整后目标余额'));
    assert((await detail.innerText()).includes(major(seed.fixture.expected.adjustment.balanceTarget)));
    step(page, 'money semantics: close complete detail');
    await detail.getByRole('button', { name: '关闭详情', exact: true }).click();
    step(page, 'money semantics: hide report amounts');
    await panel(page).getByRole('button', { name: '隐藏报告金额', exact: true }).click();
    assert((await panel(page).getByTestId('report-totals').innerText()).includes('金额已隐藏'));
    assert.equal(await panel(page).locator('.report-bar-track').count(), 0);
    assert.equal(await panel(page).getByRole('button', { name: '预览 CSV 导出', exact: true }).isEnabled(), false);
    assert(!(await row('3').innerText()).includes(major(refund.amount)));
    step(page, 'money semantics: reveal report amounts');
    await panel(page).getByRole('button', { name: '显示报告金额', exact: true }).click();
    assert((await row('3').innerText()).includes(major(refund.amount)));
}
async function previewExport(page) {
    step(page, 'export preview: request actual server proof');
    const response = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/api/v1/reports/export/preview.json'));
    await panel(page).getByRole('button', { name: '预览 CSV 导出', exact: true }).click();
    const proof = await result(await response);
    step(page, 'export preview: wait for visible confirmation');
    const dialog = panel(page).getByTestId('report-export-preview');
    await dialog.waitFor();
    const text = (await dialog.innerText()).replaceAll(',', '');
    assert(text.includes(proof.query.startDate) && text.includes(proof.query.endDate) && text.includes(proof.query.timezone));
    assert(new RegExp(`${proof.matchedCount}\\s*笔`).test(text));
    for (const total of proof.totals) {
        assert(text.includes(total.currency));
        for (const key of ['income', 'expense', 'net', 'transferIn', 'transferOut', 'adjustment']) assert(text.includes(major(total[key])));
    }
    return proof;
}
async function confirmExport(page, hidden = false) {
    step(page, 'export confirmation: acknowledge visible scope and amounts');
    const dialog = panel(page).getByTestId('report-export-preview');
    await dialog.getByRole('checkbox', { name: '我已核对以上范围、笔数和金额', exact: true }).check();
    if (hidden) {
        step(page, 'export confirmation: acknowledge hidden amounts');
        await dialog.getByRole('checkbox', { name: '我同意导出已隐藏记录的实际金额', exact: true }).check();
    }
    step(page, 'export confirmation: confirm and download CSV');
    await dialog.getByRole('button', { name: '确认并下载 CSV', exact: true }).click();
}
async function downloadedCSV(page, proof, expectedRows, hidden = false) {
    step(page, 'CSV download: wait for actual download event');
    const event = page.waitForEvent('download');
    await confirmExport(page, hidden);
    const download = await event;
    step(page, 'CSV download: validate exact file content');
    assert.equal(await download.failure(), null);
    assert.equal(download.suggestedFilename(), `finexy-report-${proof.query.ledgerId}-${proof.query.startDate}-${proof.query.endDate}.csv`);
    const stream = await download.createReadStream(); assert(stream);
    const chunks = []; for await (const chunk of stream) chunks.push(chunk);
    const bytes = Buffer.concat(chunks), rows = parseReportCSV(bytes.toString('utf8'));
    assert.equal(rows.length, proof.matchedCount);
    assert.deepEqual(rows.map(row => row.id), expectedRows.map(row => row.id));
    assert.deepEqual(totalReportCSV(rows), proof.totals);
    if (page.reportPlatform === 'mobile') {
        step(page, 'CSV download: retain actual mobile report route and visible panel');
        assert.equal(await page.evaluate(() => document.querySelector('.view-main')?.f7View?.router.currentRoute.url), '/statistic/transaction');
        assert.equal(await panel(page).isVisible(), true, 'mobile report remains visible after actual download');
    }
    const filename = `${page.reportPlatform}-download-${downloads.length + 1}.csv`;
    await writeFile(`${output}/${filename}`, bytes);
    const metadata = { filename, platform: page.reportPlatform, includesHiddenAmounts: hidden, count: rows.length,
        bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    downloads.push(metadata);
    return { rows, ...metadata };
}

async function geometry(page, label) {
    step(page, `layout ${label}: check visible controls and bounds`);
    await panel(page).getByRole('button', { name: '更新报告', exact: true }).scrollIntoViewIfNeeded();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${label}: viewport overflow`);
    const bounds = await panel(page).boundingBox(); assert(bounds && bounds.width > 0);
    assert(bounds.x >= -1 && bounds.x + bounds.width <= page.viewportSize().width + 1, `${label}: panel bounds`);
    for (const name of ['更新报告', '预览 CSV 导出']) {
        const button = panel(page).getByRole('button', { name, exact: true });
        await button.scrollIntoViewIfNeeded();
        const rect = await button.boundingBox(); assert(rect && rect.width >= 44 && rect.height >= 44, `${label}: ${name} target`);
    }
    step(page, `layout ${label}: save synthetic report screenshot`);
    await page.screenshot({ path: `${output}/${label}.png`, fullPage: true });
}

try {
    await mkdir(output, { recursive: true });
    for (let i = 0; ; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* owned candidate startup */ }
        assert(i < 80, 'candidate health did not become ready'); await pause(250);
    }
    phase = 'public API synthetic seed';
    seed = await seedLedgerReportWebFixture(api, password);
    seed.pagination = await seedPaginationLedger();
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    phase = 'desktop normal login';
    const desktop = await login(seed.owner, 'desktop');
    phase = 'desktop baseline';
    const q = mappedQuery();
    const full = await applyQuery(desktop, q);
    seed.transactions.set(seed.fixture.expected.adjustment.id, full.details.find(row => row.type === 1));
    await visibleBaseline(desktop, full, seed.fixture.expected.detailIds, seed.fixture.expected.totals);
    await visibleMoneySemantics(desktop);
    assert.equal(await desktop.locator('.summary-currency').count(), 0, 'legacy summary currency is absent on reports');
    assert.equal(await desktop.locator('main > .metrics').count(), 0, 'legacy month metrics are absent on reports');
    pass('desktop production route uses the shared exact report snapshot without legacy month summaries');

    phase = 'desktop shared five-dimensional filters';
    await verifySharedCases(desktop);
    pass(`desktop ${seed.fixture.cases.length} shared filter cases affect real requests, aggregate sections and detail rows`);
    await applyQuery(desktop, q);

    phase = 'real CSV download';
    const proof = await previewExport(desktop);
    assert.deepEqual(proof.totals, seed.fixture.expected.totals);
    const exportDialog = panel(desktop).getByTestId('report-export-preview');
    assert.equal(await exportDialog.getByRole('button', { name: '确认并下载 CSV', exact: true }).isEnabled(), false);
    const file = await downloadedCSV(desktop, proof, full.details);
    const expense = file.rows.find(row => row.id === seed.transactions.get('2').id);
    assert.equal(expense.comment, "'" + seed.fixture.transactions.find(row => row.id === '2').comment);
    assert.equal(expense.source_account_name, "'" + seed.fixture.accounts.find(row => row.id === '100').name);
    pass('actual browser download event delivers exact logical IDs, refunds, currencies and protected CSV text');

    phase = 'hidden acknowledgement';
    const hiddenQ = { ...q, hidden: 'include' };
    const hidden = await applyQuery(desktop, hiddenQ);
    const hiddenProof = await previewExport(desktop);
    const hiddenDialog = panel(desktop).getByTestId('report-export-preview');
    await hiddenDialog.getByRole('checkbox', { name: '我已核对以上范围、笔数和金额', exact: true }).check();
    assert.equal(await hiddenDialog.getByRole('button', { name: '确认并下载 CSV', exact: true }).isEnabled(), false);
    await downloadedCSV(desktop, hiddenProof, hidden.details, true);
    pass('hidden records require their own visible acknowledgement before the actual file download');

    phase = 'pagination, drilldown and month comparison';
    await verifyExploration(desktop, q, full);
    pass('production pagination, exact drilldown/return and preceding-month comparison preserve scope');

    phase = 'mobile production route';
    const mobile = await login(seed.owner, 'mobile');
    const mobileFull = await applyQuery(mobile, q);
    await visibleBaseline(mobile, mobileFull, seed.fixture.expected.detailIds, seed.fixture.expected.totals);
    await visibleMoneySemantics(mobile);
    await verifySharedCases(mobile);
    const combination = seed.fixture.cases.find(scenario => scenario.name === 'five-together');
    await visibleBaseline(mobile, await applyQuery(mobile, mappedQuery(combination.query)), combination.detailIds, combination.totals);
    await applyQuery(mobile, q);
    const mobileProof = await previewExport(mobile);
    await downloadedCSV(mobile, mobileProof, mobileFull.details);
    await verifyExploration(mobile, q, mobileFull);
    pass(`mobile actual singular route supports all ${seed.fixture.cases.length} shared filter cases, exploration and browser CSV delivery`);

    phase = 'four roles';
    const rolePages = new Map([[seed.owner, desktop]]);
    for (const actor of [seed.admin, seed.member, seed.viewer]) {
        const page = await login(actor, 'desktop'); rolePages.set(actor, page);
        await visibleBaseline(page, await applyQuery(page, q), seed.fixture.expected.detailIds, seed.fixture.expected.totals);
        const preview = await previewExport(page); await downloadedCSV(page, preview, full.details);
    }
    pass('owner, admin, member and viewer use the real read-only report and permitted export paths');

    phase = 'ledger and account changes';
    await verifyScopeChanges(desktop, mobile, q);
    pass('ledger switches, in-flight scope changes and normal account switching clear old report and confirmation');

    phase = 'cross-tab account changes';
    await verifyCrossTabIdentity(desktop, q);
    pass('a second tab normal account switch clears the original report, details, comparison, options and export proof without automatic reload');

    phase = 'negative offline';
    await verifyOffline(mobile, q);
    pass('real network loss exposes a visible retry and never a fabricated empty report');

    phase = 'negative changed data and role';
    await verifyStaleProofs(rolePages.get(seed.owner), rolePages.get(seed.member), q);
    pass('committed financial changes and live role demotion invalidate browser export confirmation without downloads');

    phase = 'responsive and accessibility';
    await applyQuery(mobile, q);
    for (const width of [375, 768, 1024]) {
        await mobile.setViewportSize({ width, height: 1000 });
        for (const colorScheme of ['light', 'dark']) {
            await mobile.emulateMedia({ colorScheme });
            await geometry(mobile, `mobile-${width}-${colorScheme}`);
        }
    }
    await mobile.evaluate(() => { document.documentElement.style.fontSize = '125%'; });
    await geometry(mobile, 'mobile-max-font');
    await desktop.setViewportSize({ width: 1440, height: 1080 });
    // The previous negative export deliberately invalidated this page's report.
    // Reload through the production form before measuring report/export controls.
    await applyQuery(desktop, q);
    await desktop.evaluate(() => { document.documentElement.style.zoom = '1.25'; });
    await geometry(desktop, 'desktop-125-percent');
    pass('mobile narrow/wide system themes, enlarged font and desktop 125% retain visible controls and bounded layout');

    assert.deepEqual(jsErrors, []);
    assert.deepEqual(consoleErrors.filter(item => !expectedConsoleError(item)), []);
    for (const page of pages) assert.deepEqual(page.legacyReportRequests, []);
    await writeFile(`${output}/summary.json`, JSON.stringify({ status: 'passed', checks, count: checks.length,
        fixtureVersion: seed.fixture.schemaVersion, sharedCases: seed.fixture.cases.length, containerId: info.Id,
        imageId: info.Image, mounts: 0, credentialsPersisted: false, storageStateSaved: false, reportAPIMocked: false,
        unexpectedJavaScriptErrors: jsErrors.length, unexpectedConsoleErrors: 0,
        expectedConsoleErrors: consoleErrors, failedResources,
        legacyRequestsOutsideReport: pages.flatMap(page => page.otherSurfaceLegacyRequests),
        legacyRequestsOnReport: pages.flatMap(page => page.legacyReportRequests), downloads }, null, 2));
} catch (error) {
    await mkdir(output, { recursive: true });
    const pageURL = activePage && !activePage.isClosed() ? redact(activePage.url()) : null;
    let screenshot = null, screenshotErrorType = null;
    if (activePage && !activePage.isClosed()) {
        try {
            screenshot = 'failure.png';
            await activePage.screenshot({ path: `${output}/${screenshot}`, fullPage: true,
                mask: [activePage.locator('input[type="password"]')] });
        } catch (screenshotError) { screenshot = null; screenshotErrorType = screenshotError.name; }
    }
    await writeFile(`${output}/failure.json`, JSON.stringify({ status: 'failed', phase, currentStep,
        pageURL, screenshot, screenshotErrorType, errorType: error.name,
        message: redact(error.message), stack: redact(error.stack),
        checks, lastHTTP, javascriptErrorCount: jsErrors.length, consoleErrorCount: consoleErrors.length, consoleErrors, failedResources,
        credentialsPersisted: false, storageStateSaved: false, reportAPIMocked: false }, null, 2));
    console.error(`FAIL ${phase} at ${currentStep}: ${error.name}`); process.exitCode = 1;
} finally {
    if (browser) await browser.close();
}

// Kept as separate functions so all journeys below act on the actual mounted
// production component and can be independently rerun against an owned candidate.
async function verifyExploration(page, q, baseline) {
    await changeLedger(page, seed.pagination.ledger);
    await applyQuery(page, seed.pagination.query);
    const first = await reportAction(page, () => panel(page).getByLabel('每页明细', { exact: true }).selectOption('20'));
    assert.equal(first.value.details.length, 20); assert.deepEqual(first.value.totals, seed.pagination.totals);
    let currentPage = first.value;
    const seen = [...currentPage.details.map(row => row.id)];
    while (currentPage.nextOffset !== null) {
        const next = await reportAction(page, () => panel(page).getByRole('button', { name: '加载更多明细', exact: true }).click());
        assert.equal(next.request.expectedSnapshotHash, first.value.snapshotHash);
        assert.equal(next.value.offset, currentPage.nextOffset);
        assert.deepEqual(next.value.totals, seed.pagination.totals);
        seen.push(...next.value.details.map(row => row.id)); currentPage = next.value;
    }
    assert.deepEqual(seen, seed.pagination.expectedIDs);
    assert.deepEqual(await panel(page).getByTestId('report-detail-row').evaluateAll(rows => rows.map(row => row.getAttribute('data-report-id'))), seen);
    const paginationProof = await previewExport(page);
    await downloadedCSV(page, paginationProof, seed.pagination.rows);
    await reportAction(page, () => panel(page).getByLabel('每页明细', { exact: true }).selectOption('50'));
    await changeLedger(page, seed.ledgerA);
    await applyQuery(page, q);
    const categoryButton = panel(page).getByTestId('report-categories').locator(`[data-report-group-id="${seed.categories.get('1002').id}"]`);
    assert((await categoryButton.innerText()).includes(seed.categories.get('1001').name), 'duplicate category labels expose their parent path');
    const drill = await reportAction(page, () => categoryButton.click());
    const categoryCase = seed.fixture.cases.find(scenario => scenario.name === 'category-parent');
    assert.deepEqual(canonicalIDs(drill.value.details), categoryCase.detailIds);
    const back = await reportAction(page, () => panel(page).getByRole('button', { name: '返回上一级报告', exact: true }).click());
    assert.deepEqual(canonicalIDs(back.value.details), seed.fixture.expected.detailIds);
    const day = baseline.trend[0].key;
    const dayDrill = await reportAction(page, () => panel(page).getByTestId('report-trend').locator(`[data-report-group-id="${day}"]`).click());
    assert.equal(dayDrill.request.query.startDate, day); assert.equal(dayDrill.request.query.endDate, day);
    assert.deepEqual(canonicalIDs(dayDrill.value.details), seed.fixture.expected.detailIds);
    await reportAction(page, () => panel(page).getByRole('button', { name: '返回上一级报告', exact: true }).click());
    const tagDrill = await reportAction(page, () => panel(page).getByTestId('report-tags').locator(`[data-report-group-id="${seed.tags.get('500').id}"]`).click());
    assert.deepEqual(canonicalIDs(tagDrill.value.details), seed.fixture.cases.find(scenario => scenario.name === 'tags-any').detailIds);
    await reportAction(page, () => panel(page).getByRole('button', { name: '返回上一级报告', exact: true }).click());
    const previous = await reportAction(page, () => panel(page).getByRole('button', { name: '比较上月', exact: true }).click(), request => request.startDate === '2026-09-01');
    assert.equal(previous.value.query.ledgerId, q.ledgerId); assert.equal(previous.value.query.endDate, '2026-09-30');
    assert.equal(previous.value.query.timezone, q.timezone); assert.equal(previous.value.matchedCount, 0);
    await panel(page).getByTestId('report-comparison').waitFor();
    assert((await panel(page).getByTestId('report-comparison').innerText()).includes('2026-09'));
}

async function verifyScopeChanges(desktop, mobile, q) {
    await applyQuery(desktop, q); await previewExport(desktop);
    await panel(desktop).getByTestId('report-export-preview').getByRole('button', { name: '取消导出', exact: true }).click();
    await changeLedger(desktop, seed.ledgerB);
    const other = await applyQuery(desktop, { ...q, ledgerId: seed.ledgerB.id });
    assert.equal(other.matchedCount, 1); assert.equal(other.details[0].id, seed.transactions.get('11').id);
    assert.equal(await panel(desktop).getByTestId('report-export-preview').isVisible(), false);
    await changeLedger(desktop, seed.ledgerA); await applyQuery(desktop, q);
    const cdp = await desktop.context().newCDPSession(desktop);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 800, downloadThroughput: -1, uploadThroughput: -1 });
    const requested = desktop.waitForRequest(request => request.url().endsWith('/api/v1/reports/query.json'));
    await panel(desktop).getByTestId('report-apply').click(); await requested;
    await changeLedger(desktop, seed.ledgerB);
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await cdp.detach();
    const settled = await applyQuery(desktop, { ...q, ledgerId: seed.ledgerB.id });
    assert.equal(settled.details[0].id, seed.transactions.get('11').id);
    await changeLedger(desktop, seed.ledgerA); await applyQuery(desktop, q);
    await previewExport(desktop);
    await panel(desktop).getByTestId('report-export-preview').getByRole('button', { name: '取消导出', exact: true }).click();
    await switchDesktopAccount(desktop, seed.outsider);
    await openReports(desktop);
    const personal = await applyQuery(desktop, { ...q, ledgerId: '0' });
    assert.equal(personal.matchedCount, 0);
    assert.equal(await panel(desktop).getByTestId('report-detail-row').count(), 0);
    assert.equal(await panel(desktop).getByTestId('report-export-preview').isVisible(), false);
    // Restore owner via another real login so later negative tests share the same UI session.
    await switchDesktopAccount(desktop, seed.owner);
    await openReports(desktop); await changeLedger(desktop, seed.ledgerA); await applyQuery(desktop, q);
    await changeLedger(mobile, seed.ledgerB); await applyQuery(mobile, { ...q, ledgerId: seed.ledgerB.id });
    await changeLedger(mobile, seed.ledgerA); await applyQuery(mobile, q);
}

async function verifyCrossTabIdentity(page, q) {
    await applyQuery(page, q);
    await reportAction(page, () => panel(page).getByRole('button', { name: '比较上月', exact: true }).click(), request => request.startDate === '2026-09-01');
    await panel(page).locator(`[data-report-id="${seed.transactions.get('9').id}"]`).getByRole('button').click();
    await panel(page).getByRole('article', { name: '流水完整详情', exact: true }).waitFor();
    await previewExport(page);
    const requestCount = page.reportRequests.length;
    const peer = await page.context().newPage();
    peer.reportPlatform = 'desktop'; peer.actor = seed.owner; peer.legacyReportRequests = []; peer.otherSurfaceLegacyRequests = []; peer.reportRequests = []; peer.downloadCount = 0;
    peer.setDefaultTimeout(20000);
    peer.on('pageerror', error => jsErrors.push({ platform: 'desktop', phase, name: error.name }));
    observeConsole(peer, 'desktop');
    peer.on('request', request => {
        observeLegacyRequest(peer, request);
    });
    pages.push(peer);
    let peerCompleted = false;
    try {
        step(peer, 'cross-tab identity: open a second normal authenticated app tab');
        await peer.goto(`${base}/desktop#/`, { waitUntil: 'networkidle' });
        await peer.getByRole('button', { name: '切换当前账本', exact: true }).waitFor();
        await switchDesktopAccount(peer, seed.outsider);
        step(page, 'cross-tab identity: verify synchronous clearing in the original tab');
        await panel(page).getByRole('alert').filter({ hasText: '登录账号已在其他页面变化' }).waitFor();
        assert.equal(await panel(page).getByTestId('report-export-preview').isVisible(), false);
        for (const id of ['report-totals', 'report-detail-row', 'report-trend', 'report-categories', 'report-tags', 'report-comparison']) {
            assert.equal(await panel(page).getByTestId(id).count(), 0, `${id} is cleared after external account change`);
        }
        assert.equal(await panel(page).getByRole('article', { name: '流水完整详情', exact: true }).count(), 0);
        assert.equal(await panel(page).locator('.report-option-list input').count(), 0, 'old account/category/tag options are cleared');
        assert.equal(await panel(page).getByTestId('report-apply').isEnabled(), false);
        assert.equal(page.reportRequests.length, requestCount, 'old Pinia session must not automatically load the new account');
        await switchDesktopAccount(peer, seed.owner);
        step(page, 'cross-tab identity: keep the invalidated page locked until a real reload');
        assert.equal(await panel(page).getByTestId('report-apply').isEnabled(), false);
        assert.equal(await panel(page).getByTestId('report-export-preview').isVisible(), false);
        assert.equal(page.reportRequests.length, requestCount);
        peerCompleted = true;
    } finally { if (peerCompleted) await peer.close(); }
    step(page, 'cross-tab identity: reload the original page through its visible recovery action');
    const reloaded = page.waitForEvent('domcontentloaded');
    await panel(page).getByRole('button', { name: '刷新页面核对账号', exact: true }).click();
    await reloaded;
    await panel(page).waitFor();
    await changeLedger(page, seed.ledgerA);
    await applyQuery(page, q);
}
async function verifyOffline(page, q) {
    await applyQuery(page, q);
    await page.context().setOffline(true);
    try {
        await panel(page).getByTestId('report-apply').click();
        await panel(page).getByRole('alert').waitFor();
        assert.equal(await panel(page).getByTestId('report-totals').count(), 0);
    } finally { await page.context().setOffline(false); }
    await applyQuery(page, q);
}
async function verifyStaleProofs(ownerPage, memberPage, q) {
    await applyQuery(ownerPage, q); await previewExport(ownerPage);
    await api('v1/transactions/add.json', seed.owner, { ledgerId: seed.ledgerA.id, type: 3,
        categoryId: seed.categories.get('1002').id, sourceAccountId: seed.accounts.get('100').id,
        sourceAmount: 1n, time: BigInt(seed.fixture.transactions.find(row => row.id === '10').transactionTime) + 20n,
        utcOffset: 0, comment: 'Synthetic post-preview mutation' });
    let before = ownerPage.downloadCount;
    let rejected = ownerPage.waitForResponse(response => response.url().endsWith('/api/v1/reports/export.csv'));
    await confirmExport(ownerPage); assert.equal((await rejected).status(), 409);
    await panel(ownerPage).getByTestId('report-export-preview').getByRole('alert').filter({ hasText: '报告内容或权限已变化，请重新读取并核对。' }).waitFor();
    assert.equal(await panel(ownerPage).getByTestId('report-export-preview').getByRole('button', { name: '确认并下载 CSV', exact: true }).isEnabled(), false);
    assert.equal(ownerPage.downloadCount, before);
    await panel(ownerPage).getByTestId('report-export-preview').getByRole('button', { name: '取消导出', exact: true }).click();
    await applyQuery(memberPage, q); await previewExport(memberPage);
    await api('v1/ledger/member/change_role.json', seed.owner, { ledgerId: seed.ledgerA.id, memberId: seed.memberIDs.get(seed.member), role: 4 });
    before = memberPage.downloadCount;
    rejected = memberPage.waitForResponse(response => response.url().endsWith('/api/v1/reports/export.csv'));
    await confirmExport(memberPage); assert.equal((await rejected).status(), 409);
    await panel(memberPage).getByTestId('report-export-preview').getByRole('alert').filter({ hasText: '报告内容或权限已变化，请重新读取并核对。' }).waitFor();
    assert.equal(await panel(memberPage).getByTestId('report-export-preview').getByRole('button', { name: '确认并下载 CSV', exact: true }).isEnabled(), false);
    assert.equal(memberPage.downloadCount, before);
    await panel(memberPage).getByTestId('report-export-preview').getByRole('button', { name: '取消导出', exact: true }).click();
    await applyQuery(memberPage, q);
    await api('v1/ledger/member/remove.json', seed.owner, { ledgerId: seed.ledgerA.id, memberId: seed.memberIDs.get(seed.member) });
    rejected = memberPage.waitForResponse(reportResponse);
    await panel(memberPage).getByTestId('report-apply').click(); assert.equal((await rejected).status(), 403);
    await panel(memberPage).getByRole('alert').filter({ hasText: '当前账本或导出权限已失效，请核对成员权限后重试。' }).waitFor();
    assert.equal(await panel(memberPage).getByTestId('report-totals').count(), 0);
}

async function seedPaginationLedger() {
    // Supplemental pagination data has its own ledger. The A/B financial
    // fixture remains unchanged, and each amount comes from its shared source.
    const ledger = await api('v1/ledger/create.json', seed.owner, { type: 1, name: `Synthetic report pagination ${Date.now()}` });
    const source = seed.fixture.accounts.find(row => row.id === '100');
    const account = await api('v1/accounts/add.json', seed.owner, { ledgerId: ledger.id, name: 'Synthetic pagination account',
        currency: source.currency, balance: 0n, balanceTime: 946684800n, category: 1, type: 1, icon: '1', color: '000000' });
    const amount = BigInt(seed.fixture.transactions.find(row => row.id === '4').amount);
    const time = BigInt(seed.fixture.transactions.find(row => row.id === '10').transactionTime) + 100n;
    const ids = [];
    for (let index = 0; index < 51; index++) {
        const row = await api('v1/transactions/add.json', seed.owner, { ledgerId: ledger.id, type: 3,
            categoryId: seed.categories.get('1002').id, sourceAccountId: account.id, sourceAmount: amount,
            time: time + BigInt(index), utcOffset: 0, comment: `Synthetic pagination ${index + 1}` });
        ids.push(row.id);
    }
    const query = { ...seed.period, ledgerId: ledger.id };
    const report = await api('v1/reports/query.json', seed.owner, { query, count: 200 });
    const expense = amount * BigInt(ids.length);
    const totals = [{ currency: source.currency, income: '0', expense: expense.toString(), net: (-expense).toString(),
        adjustment: '0', transferIn: '0', transferOut: '0' }];
    assert.deepEqual(report.details.map(row => row.id), [...ids].reverse());
    assert.deepEqual(report.totals, totals);
    return { ledger, query, expectedIDs: [...ids].reverse(), rows: report.details, totals };
}
