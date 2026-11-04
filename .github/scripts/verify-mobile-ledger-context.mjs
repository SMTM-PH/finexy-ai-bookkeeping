import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// This script writes only synthetic records to the disposable acceptance service.
const base = 'http://127.0.0.1:18110';
const container = JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(container.Mounts, [], 'acceptance service must have no mounts');
assert(container.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert.equal(container.NetworkSettings.Ports['8080/tcp']?.[0]?.HostPort, '18110');
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const username = `mobile_scope_${randomBytes(6).toString('hex')}`;
const password = randomBytes(18).toString('hex');
function waitForSignal(signal, name) {
    let timeout;
    return Promise.race([signal, new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error(`Timed out: ${name}`)), 15000);
    })]).finally(() => clearTimeout(timeout));
}
async function request(path, token, body) {
    const response = await fetch(`${base}/api/${path}`, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    const json = await response.json();
    assert.equal(response.status, 200, `HTTP ${path} (error ${json.errorCode || 'none'})`);
    assert(json.success, `success ${path}`);
    return json.result;
}
let browser;
let page;
try {
    await mkdir('artifacts/agent-access', { recursive: true });
    const owner = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: '移动账本隔离验收', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 };
    const personal = await request('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Mobile personal account' });
    const root = await request('v1/transaction/categories/add.json', owner.token, { name: 'Mobile category', type: 2, icon: '1', color: '000000' });
    const category = await request('v1/transaction/categories/add.json', owner.token, { name: 'Mobile expense', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const ledger = await request('v1/ledger/create.json', owner.token, { type: 1, name: 'Mobile separate book' });
    const separate = await request('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Mobile separate account', ledgerId: ledger.id });
    const draft = { type: 3, time: Math.floor(Date.now() / 1000), utcOffset: 480, categoryId: category.id, sourceAmount: 123 };
    await request('v1/transactions/add.json', owner.token, { ...draft, sourceAccountId: personal.id, comment: 'Mobile personal expense' });
    const separateTransaction = await request('v1/transactions/add.json', owner.token, { ...draft, ledgerId: ledger.id, sourceAccountId: separate.id, comment: 'Mobile separate expense' });
    const expectedOverview = await request(`v1/ledger/overview.json?ledgerId=${ledger.id}`, owner.token);
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    page = await browser.newPage({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 390, height: 844 } });
    page.setDefaultTimeout(15000);
    const pageErrors = [];
    const requests = [];
    page.on('pageerror', error => pageErrors.push(error.name));
    page.on('request', req => {
        const url = new URL(req.url());
        if (url.pathname.includes('/api/v1/')) requests.push({ path: url.pathname, ledgerId: url.searchParams.get('ledgerId') || '0', method: req.method() });
    });
    const current = () => page.locator('.view-main > .page.page-current');
    const screenshotWithoutToast = async path => {
        await page.locator('.toast:visible').waitFor({ state: 'hidden' });
        await page.screenshot({ path });
    };
    const navigate = async path => {
        await page.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false, clearPreviousHistory: true }), path);
        await page.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router?.currentRoute?.path === value.split('?')[0], path);
        await current().waitFor();
    };
    const viewBook = async name => {
        await current().locator('.list .item-link').filter({ hasText: name }).click();
        await current().getByText(name, { exact: true }).first().waitFor();
    };
    const switchBook = async name => {
        await navigate('/ledger/manage');
        await current().locator('.list .item-link').filter({ hasText: name }).waitFor();
        await viewBook(name);
        await current().getByText('切换到此账本', { exact: true }).click();
        await current().getByText(new RegExp(`正在使用：${name}`)).waitFor();
    };
    await page.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await current().locator('input[type="text"]').first().fill(username);
    await current().locator('input[type="password"]').first().fill(password);
    await current().getByText('登录', { exact: true }).click();
    await current().getByText('当前账本：默认个人账本', { exact: true }).waitFor();
    pass('production mobile cold start and normal login');

    await navigate('/ledger/manage');
    await current().locator('.list .item-link').filter({ hasText: ledger.name }).waitFor();
    await viewBook(ledger.name);
    assert.match(await current().innerText(), /正在使用：默认个人账本/);
    assert.match(await current().innerText(), /正在查看/);
    const controls = await current().locator('.button,.list-button,.item-link').evaluateAll(elements => elements.map(el => el.getBoundingClientRect().height).filter(height => height > 0));
    assert(controls.length && controls.every(height => height >= 44), `ledger controls: ${controls.join(',')}`);
    const backTargets = await page.locator('.mobile-ledger-navbar .link:visible').evaluateAll(elements => elements.map(el => ({ width: el.getBoundingClientRect().width, height: el.getBoundingClientRect().height })));
    assert(backTargets.length && backTargets.every(target => target.width >= 44 && target.height >= 44), 'ledger back control must meet 44px in both dimensions');
    await screenshotWithoutToast('artifacts/first-iteration-20261003/mobile-ledger-view-without-switch.png');
    pass('ledger switch and related controls meet the 44px click target');
    await navigate('/account/list');
    await current().getByText(personal.name, { exact: true }).waitFor();
    assert.equal(await current().getByText(separate.name, { exact: true }).count(), 0);
    pass('viewing another ledger never changes the current read target');

    await switchBook(ledger.name);
    const scopedStart = requests.length;
    await navigate('/');
    await current().getByText(ledger.name, { exact: true }).waitFor();
    await current().getByText(`${expectedOverview.accountCount} 个账户 · ${expectedOverview.transactionCount} 条流水`, { exact: true }).waitFor();
    await screenshotWithoutToast('artifacts/first-iteration-20261003/mobile-ledger-scoped-overview.png');
    assert(!requests.slice(scopedStart).some(item => item.path.endsWith('/transactions/amounts.json')), 'non-default overview must not read personal summary');
    await navigate('/account/list');
    await current().getByText(separate.name, { exact: true }).waitFor();
    assert.equal(await current().getByText(personal.name, { exact: true }).count(), 0);
    await navigate('/transaction/list');
    await current().getByText('Mobile separate expense', { exact: true }).waitFor();
    assert.equal(await current().getByText('Mobile personal expense', { exact: true }).count(), 0);
    await navigate(`/transaction/detail?id=${separateTransaction.id}&type=3`);
    await page.waitForFunction(() => document.querySelector('.page-current textarea')?.value === 'Mobile separate expense');
    for (const suffix of ['/accounts/list.json', '/transaction/categories/list.json', '/transactions/list.json', '/transactions/get.json']) {
        assert(requests.slice(scopedStart).some(item => item.path.endsWith(suffix) && item.ledgerId === ledger.id), `scoped request ${suffix}`);
    }
    pass('selected ledger scopes overview, accounts, transaction history and detail');

    const writesBefore = requests.filter(item => item.method === 'POST').length;
    for (const path of ['/account/add', '/statistic/transaction', '/category/list', '/tag/list', '/template/add', '/schedule/list', '/user/data/management']) {
        await navigate(path);
        await current().getByText('当前移动版本支持此账本的收支、转账、流水管理、月度预算、模板、周期待确认和账本报告；账户管理和高级账户分析暂不支持。标签与附件遵守账本所有者边界。', { exact: true }).waitFor();
        assert.equal(await current().locator('textarea,input').count(), 0, `no editable default-ledger form at ${path}`);
    }
    assert.equal(requests.filter(item => item.method === 'POST').length, writesBefore);
    pass('unsupported non-default deep links show a client limitation and issue no writes');

    await navigate('/transaction/list');
    await current().getByText('Mobile separate expense', { exact: true }).waitFor();
    await page.getByLabel('导入支付宝或微信账单').click();
    await page.locator('.popup.modal-in').getByText(new RegExp(`账单将导入「${ledger.name}」`)).waitFor();
    await page.locator('.popup.modal-in').getByText('关闭', { exact: true }).click();
    pass('statement import visibly binds the book selected when opened');

    await page.route('**/api/v1/savings_goal/list.json*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true }) }));
    await navigate('/savings/goals');
    await current().getByRole('alert').waitFor();
    assert.match(await current().getByRole('alert').innerText(), /无法读取有效存钱计划/);
    assert.equal(await current().getByText('此账本还没有存钱目标。', { exact: true }).count(), 0);
    await page.unroute('**/api/v1/savings_goal/list.json*');
    await current().getByText('重试', { exact: true }).click();
    await current().getByRole('alert').waitFor({ state: 'hidden' });
    await current().getByText('此账本还没有存钱目标。', { exact: true }).waitFor();
    pass('savings failure has visible text and retry; empty state requires a valid current-ledger response');

    await navigate('/ledger/manage');
    await current().getByText(new RegExp(`正在使用：${ledger.name}`)).waitFor();
    await page.route('**/api/v1/ledger/list.json*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true }) }));
    await navigate('/account/list');
    await navigate('/ledger/manage');
    await current().getByRole('alert').waitFor();
    assert.match(await current().innerText(), new RegExp(`正在使用：${ledger.name}`));
    assert.equal(await current().locator('.list .item-link').filter({ hasText: ledger.name }).count(), 1);
    await page.unroute('**/api/v1/ledger/list.json*');
    await current().getByText('重试', { exact: true }).click();
    await current().getByRole('alert').waitFor({ state: 'hidden' });
    pass('a missing ledger result stays an error and preserves the last valid list and selection');

    await switchBook('默认个人账本');
    let releaseOld;
    let receivedOld;
    let fulfilledOld;
    const held = new Promise(resolve => { receivedOld = resolve; });
    const release = new Promise(resolve => { releaseOld = resolve; });
    const fulfilled = new Promise(resolve => { fulfilledOld = resolve; });
    await page.route('**/api/v1/accounts/list.json*', async route => {
        if (new URL(route.request().url()).searchParams.get('ledgerId') !== ledger.id) { await route.continue(); return; }
        const response = await route.fetch();
        receivedOld();
        await release;
        await route.fulfill({ response });
        fulfilledOld();
    });
    // Cold-starting a scoped home triggers a held account read; changing the book
    // while it is in flight must invalidate its ability to replace the new cache.
    await switchBook(ledger.name);
    await navigate('/');
    await waitForSignal(held, 'prior-ledger account request');
    await switchBook('默认个人账本');
    await navigate('/account/list');
    await current().getByText(personal.name, { exact: true }).waitFor();
    releaseOld();
    await waitForSignal(fulfilled, 'prior-ledger account response');
    await page.unroute('**/api/v1/accounts/list.json*');
    await navigate('/account/list?verify=cache');
    await current().getByText(personal.name, { exact: true }).waitFor();
    assert.equal(await current().getByText(separate.name, { exact: true }).count(), 0);
    pass('late prior-ledger account response cannot overwrite the current book');

    await navigate('/transaction/add?noTransactionDraft=true&type=3');
    await current().locator('textarea').first().waitFor();
    assert.equal(await current().getByText('当前移动版本支持此账本的收支、转账、流水管理、月度预算、模板、周期待确认和账本报告；账户管理和高级账户分析暂不支持。标签与附件遵守账本所有者边界。').count(), 0);
    assert.equal(await page.getByText('checkmark_alt', { exact: true }).first().isVisible(), true);
    pass('default ledger retains its ordinary transaction form');
    assert.deepEqual(pageErrors, []);
    await page.screenshot({ path: 'artifacts/agent-access/mobile-ledger-context-final.png' });
    await writeFile('artifacts/agent-access/mobile-ledger-context-e2e.json', JSON.stringify({ passed: checks.length, checks }, null, 2));
} catch (error) {
    if (page) await page.screenshot({ path: 'artifacts/agent-access/mobile-ledger-context-failure.png' }).catch(() => {});
    console.error(String(error.message).replace(/eyJ[A-Za-z0-9_.-]+/g, '[redacted]'));
    process.exitCode = 1;
} finally {
    if (browser) await browser.close();
}
