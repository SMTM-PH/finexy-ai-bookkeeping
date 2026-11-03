import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Only a disposable container and synthetic accounts; never a user's service.
const base = 'http://127.0.0.1:18110';
const info = JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
const password = randomBytes(18).toString('hex');
const username = `wallet_${randomBytes(6).toString('hex')}`;
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function request(path, token, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const data = await response.json();
    assert.equal(response.status, 200, `HTTP ${path}`);
    assert(data.success, `success ${path}`);
    return data.result;
}
let browser;
let page;
try {
    let healthy = false;
    for (let i = 0; i < 60; i++) {
        try { healthy = (await fetch(`${base}/healthz.json`)).ok; } catch { /* starting */ }
        if (healthy) break;
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert(healthy);
    const owner = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: '钱包验收', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const draft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balanceTime: 946684800 };
    const accounts = [];
    for (let i = 1; i <= 18; i++) accounts.push(await request('v1/accounts/add.json', owner.token, { ...draft, name: i === 1 ? '测试银行储蓄账户（验收）' : i === 18 ? 'Synthetic 18 very long account name for wrapping' : `Synthetic ${String(i).padStart(2, '0')}`, balance: i === 18 ? -123456789 : i * 100, category: i === 18 ? 3 : 2, currency: i === 9 ? 'USD' : 'CNY' }));
    const hidden = await request('v1/accounts/add.json', owner.token, { ...draft, name: 'Hidden wallet', balance: 9900 });
    await request('v1/accounts/hide.json', owner.token, { id: hidden.id, hidden: true });
    const ledger = await request('v1/ledger/create.json', owner.token, { type: 1, name: 'Separate wallet ledger' });
    const other = await request('v1/accounts/add.json', owner.token, { ...draft, ledgerId: ledger.id, name: 'Separate wallet', balance: 123 });
    const empty = await request('v1/ledger/create.json', owner.token, { type: 1, name: 'Empty wallet ledger' });
    const styleQuery = account => `v1/accounts/card/get.json?ledgerId=0&accountId=${account.id}`;
    const firstStyle = await request(styleQuery(accounts[0]), owner.token);
    await request('v1/accounts/card/update.json', owner.token, { ledgerId: '0', accountId: accounts[0].id, appearance: { ...firstStyle, theme: 'mint' } });
    const secondStyle = await request(styleQuery(accounts[1]), owner.token);
    await request('v1/accounts/card/update.json', owner.token, { ledgerId: '0', accountId: accounts[1].id, appearance: { ...secondStyle, theme: 'midnight' } });
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(/#\/$/);
    const card = page.locator('.wallet-carousel .holo-card');
    const previous = page.getByRole('button', { name: '上一张钱包卡片', exact: true });
    const next = page.getByRole('button', { name: '下一张钱包卡片', exact: true });
    await page.waitForFunction(() => document.querySelector('.wallet-pager')?.textContent?.includes('1 / 18'));
    await page.waitForFunction(() => document.querySelector('.wallet-carousel .holo-card')?.dataset.theme === 'mint');
    await page.locator('.currency-switch .cur').click();
    await page.getByRole('button', { name: '刷新余额', exact: true }).click();
    await page.getByText('余额数据已更新', { exact: true }).waitFor();
    assert.equal(await page.locator('.home-data-error').filter({ hasText: '未能完整读取当前账本' }).count(), 0, 'unchanged valid data is a completed refresh');
    assert.equal(await card.count(), 1);
    assert.equal((await card.innerText()).trim(), '', 'art faces must contain no visible text');
    await page.waitForFunction(() => [...document.querySelectorAll('.holo-illustration, .holo-garden')].every(image => image.complete && image.naturalWidth === 1024));
    const proportions = await page.evaluate(() => {
        const box = selector => document.querySelector(selector).getBoundingClientRect();
        return { card: box('.wallet-carousel .holo-card').height, balance: box('.balance-card').height, stats: box('.stats').height, chart: box('.chart-card').height, activitiesTop: box('.activities').top, insightBottom: Math.max(box('.stats').bottom, box('.chart-card').bottom), balanceBottom: box('.balance-card').bottom, widths: ['.balance-card', '.stats', '.chart-card'].map(selector => box(selector).width), amountClipped: [...document.querySelectorAll('.stat strong')].some(el => el.scrollWidth > el.clientWidth + 1) };
    });
    assert(proportions.card <= 300 && proportions.balance <= 760 && proportions.stats <= 400 && proportions.chart <= 450, JSON.stringify(proportions));
    assert(Math.abs(proportions.widths[1] - proportions.widths[2]) <= 1 && proportions.widths[1] >= proportions.widths[0] * 2 && !proportions.amountClipped);
    assert(proportions.activitiesTop >= proportions.insightBottom && proportions.activitiesTop < proportions.balanceBottom);
    pass('short wallet card, horizontal metrics, wide chart, natural heights and complete amounts');
    assert.equal(await previous.isDisabled(), true);
    const found = [];
    for (let i = 0; i < 18; i++) {
        found.push(await card.getAttribute('data-account-id'));
        if (i < 17) await next.click();
    }
    assert.deepEqual(found, accounts.map(account => account.id));
    assert.equal(await next.isDisabled(), true);
    assert.match(await page.locator('.wallet-carousel .holo-caption').innerText(), /-.*1,234,567\.89/);
    assert.match(await page.locator('.whead').innerText(), /共 19 个账户/);
    pass('one compact card, all 18 visible accounts including currencies and signed liabilities, disabled boundaries');
    await card.focus();
    await page.keyboard.press('ArrowLeft');
    assert.equal(await card.getAttribute('data-account-id'), accounts[16].id);
    await page.keyboard.press('ArrowRight');
    assert.equal(await card.getAttribute('data-account-id'), accounts[17].id);
    assert.equal(await card.evaluate(el => document.activeElement === el), true);
    await page.locator('.wallet-carousel').getByRole('button', { name: '查看详情', exact: true }).click();
    await page.getByRole('dialog').getByRole('heading', { name: accounts[17].name, exact: true }).waitFor();
    await page.getByRole('button', { name: '关闭账户详情', exact: true }).click();
    pass('keyboard navigation keeps focus; selected card detail button opens exact account details');
    const currency = page.locator('.currency-switch .cur');
    await currency.click();
    await page.getByRole('button', { name: '隐藏金额', exact: false }).click();
    assert.match(await page.locator('.wallet-carousel .holo-caption').innerText(), /\*{6}/);
    assert.doesNotMatch(await page.locator('.wallet-carousel .holo-caption').innerText(), /1,234,567/);
    assert(!(await card.getAttribute('aria-label')).includes('1,234,567'));
    await card.focus(); await page.keyboard.press('f');
    assert.equal(await card.getAttribute('data-side'), 'back');
    assert.match(await page.locator('.wallet-carousel .holo-caption').innerText(), /\*{6}/);
    assert.doesNotMatch(await card.innerText(), /1,234,567/);
    await page.keyboard.press('r');
    await page.locator('.wallet-carousel').getByRole('button', { name: '查看详情', exact: true }).click();
    assert.equal(await page.locator('.drawer-balance').innerText(), '******');
    await page.getByRole('button', { name: '关闭账户详情', exact: true }).click();
    await currency.click();
    await page.getByRole('button', { name: '显示金额', exact: false }).click();
    await request('v1/accounts/modify.json', owner.token, { id: accounts[17].id, name: 'Renamed selected wallet', category: 3, icon: '1', color: '000000' });
    await currency.click();
    await page.getByRole('button', { name: '刷新余额', exact: true }).click();
    await page.locator('.wallet-carousel .holo-caption h3').filter({ hasText: 'Renamed selected wallet' }).waitFor();
    assert.equal(await card.getAttribute('data-account-id'), accounts[17].id);
    pass('amount privacy applies to card and drawer; refresh preserves selected string ID');
    await request('v1/accounts/hide.json', owner.token, { id: accounts[17].id, hidden: true });
    await currency.click();
    await page.getByRole('button', { name: '刷新余额', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.wallet-pager')?.textContent?.includes('1 / 17'));
    assert.equal(await card.getAttribute('data-account-id'), accounts[0].id);
    async function switchLedger(name) {
        await page.getByRole('button', { name: '切换当前账本', exact: true }).click();
        await page.getByRole('option').filter({ hasText: name }).click();
    }
    await switchLedger('Separate wallet ledger');
    await page.locator('.wallet-carousel .holo-caption h3').filter({ hasText: 'Separate wallet' }).waitFor();
    assert.equal(await card.getAttribute('data-account-id'), other.id);
    assert.equal(await previous.isDisabled(), true);
    assert.equal(await next.isDisabled(), true);
    await switchLedger('Empty wallet ledger');
    await page.getByText('还没有可用账户', { exact: true }).waitFor();
    assert.equal(await card.count(), 0);
    await switchLedger('默认个人账本');
    await page.waitForFunction(() => document.querySelector('.wallet-pager')?.textContent?.includes('1 / 17'));
    assert.equal(await card.getAttribute('data-account-id'), accounts[0].id);
    pass('hidden selection resets; ledger switch and empty ledger never retain old cards');
    for (const scheme of ['light', 'dark']) for (const zoom of [1, 1.25]) {
        await page.emulateMedia({ colorScheme: scheme });
        await page.evaluate(value => document.documentElement.style.zoom = value, zoom);
        await page.waitForFunction(value => Number(getComputedStyle(document.documentElement).zoom) === value, zoom);
        await card.scrollIntoViewIfNeeded();
        assert.equal(await card.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
        const box = await previous.boundingBox();
        assert(box.width / zoom >= 43.9 && box.height / zoom >= 43.9);
    }
    await page.evaluate(() => document.documentElement.style.zoom = 1);
    await page.waitForFunction(() => Number(getComputedStyle(document.documentElement).zoom) === 1);
    assert(await card.evaluate(el => parseFloat(getComputedStyle(el).transitionDuration) <= 0.001));
    for (const width of [1024, 768, 375]) {
        await page.setViewportSize({ width, height: 900 });
        await next.scrollIntoViewIfNeeded();
        await next.click();
        assert.equal(await card.getAttribute('data-account-id'), accounts[1].id);
        await previous.scrollIntoViewIfNeeded();
        await previous.click();
        assert.equal(await card.getAttribute('data-account-id'), accounts[0].id);
        assert.equal(await card.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await card.scrollIntoViewIfNeeded();
    await page.locator('.wallets').screenshot({ path: 'artifacts/agent-access/home-holo-wallet-final.png' });
    await page.locator('.finance-home').evaluate(el => el.scrollTop = 0);
    await page.screenshot({ path: 'artifacts/agent-access/wallet-carousel-final.png' });
    await page.setViewportSize({ width: 1396, height: 871 });
    await page.locator('.finance-home').evaluate(el => el.scrollTop = 0);
    await page.screenshot({ path: 'artifacts/agent-access/home-illustrated-1396-final.png' });
    await page.setViewportSize({ width: 1440, height: 1000 });
    assert.deepEqual(errors, []);
    pass('light/dark preferences, 100%/125% zoom, reduced motion, narrow viewport navigation and 44px controls');
    const initialBalances = await request('v1/accounts/list.json?ledgerId=0', owner.token);
    const initialTransactions = await request('v1/transactions/count.json?ledgerId=0', owner.token);
    const artwork = await page.evaluate(() => {
        const canvas = document.createElement('canvas'); canvas.width = 280; canvas.height = 400;
        const c = canvas.getContext('2d'); c.fillStyle = '#18382c'; c.fillRect(0, 0, 280, 400);
        c.fillStyle = '#c9a575'; c.beginPath(); c.arc(160, 180, 80, 0, 2 * Math.PI); c.fill();
        return canvas.toDataURL('image/png');
    });
    const savedStyle = await request('v1/accounts/card/update.json', owner.token, { ledgerId: '0', accountId: accounts[0].id, appearance: { ...await request(styleQuery(accounts[0]), owner.token), background: artwork } });
    async function refreshArtwork() {
        await currency.click(); await page.getByRole('button', { name: '刷新余额', exact: true }).click();
    }
    await refreshArtwork();
    await page.waitForFunction(() => !!document.querySelector('.wallet-carousel .holo-background'));
    assert.equal(await card.locator('.holo-background').getAttribute('src'), artwork);
    await card.focus(); await page.keyboard.press('f');
    assert.equal(await card.getAttribute('data-side'), 'back');
    await page.keyboard.press('r');
    assert.equal(await card.getAttribute('data-side'), 'front');
    await page.getByRole('link', { name: '管理', exact: true }).click();
    await page.waitForURL(/#\/account\/list$/);
    await page.getByRole('combobox', { name: '选择账户', exact: true }).selectOption(accounts[0].id);
    await page.waitForFunction(() => document.querySelector('.holo-card')?.dataset.theme === 'mint' && !!document.querySelector('.holo-background'));
    assert.equal(await page.locator('.holo-background').getAttribute('src'), artwork);
    await page.getByRole('link', { name: '总览', exact: true }).click();
    await page.waitForURL(/#\/$/);
    await page.waitForFunction(() => !!document.querySelector('.wallet-carousel .holo-background'));
    pass('home and account management share server-saved material and image; flip/reset preserved');
    let release;
    const barrier = new Promise(resolve => { release = resolve; });
    await page.route('**/accounts/card/get.json?*', async route => {
        if (new URL(route.request().url()).searchParams.get('accountId') === accounts[0].id) {
            await barrier; await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, result: savedStyle }) });
        } else await route.continue();
    });
    await refreshArtwork(); await next.click();
    await page.waitForFunction(() => document.querySelector('.wallet-carousel .holo-card')?.dataset.theme === 'midnight');
    release(); await page.waitForTimeout(150);
    assert.equal(await card.getAttribute('data-account-id'), accounts[1].id);
    assert.equal(await card.getAttribute('data-theme'), 'midnight');
    assert.equal(await card.locator('.holo-background').count(), 0);
    await page.unroute('**/accounts/card/get.json?*');
    await page.route('**/accounts/card/get.json?*', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false }) }));
    await refreshArtwork();
    await page.getByText('卡面读取失败，暂显示默认样式。', { exact: false }).waitFor();
    assert.equal(await card.getAttribute('data-theme'), 'classic');
    await page.unroute('**/accounts/card/get.json?*');
    await page.getByRole('button', { name: '重试读取', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.wallet-carousel .holo-card')?.dataset.theme === 'midnight');
    assert.deepEqual(await request('v1/accounts/list.json?ledgerId=0', owner.token), initialBalances);
    assert.deepEqual(await request('v1/transactions/count.json?ledgerId=0', owner.token), initialTransactions);
    assert.deepEqual(errors, []);
    pass('stale account artwork ignored, failure/retry visible, balances and transaction count unchanged');
    while (!(await previous.isDisabled())) await previous.click();
    const preservedArtwork = await request(styleQuery(accounts[0]), owner.token);
    await request('v1/accounts/card/update.json', owner.token, { ledgerId: '0', accountId: accounts[0].id, appearance: { ...preservedArtwork, background: '', subject: '' } });
    const categoryBalances = (await request('v1/accounts/list.json?ledgerId=0', owner.token)).map(account => [account.id, account.balance]).sort();
    const patterns = new Set();
    for (const category of [1, 2, 8, 3, 4, 5, 6, 9, 7]) {
        await request('v1/accounts/modify.json', owner.token, { id: accounts[0].id, name: accounts[0].name, category, icon: '1', color: '000000' });
        await page.locator('.cur').click();
        await page.getByRole('button', { name: '刷新余额', exact: true }).click();
        await page.waitForFunction(category => {
            const names = { 1: 'cash', 2: 'vault', 8: 'reserve', 3: 'credit', 4: 'digital', 5: 'debt', 6: 'receivable', 9: 'deposit', 7: 'investment' };
            return document.querySelector('.wallet-carousel .account-card-artwork')?.dataset.pattern === names[category];
        }, category);
        patterns.add(await page.locator('.wallet-carousel .account-card-artwork').getAttribute('data-pattern'));
        await card.screenshot({ path: `artifacts/agent-access/account-illustration-${category}.png` });
    }
    assert.equal(patterns.size, 9);
    await request('v1/accounts/modify.json', owner.token, { id: accounts[0].id, name: accounts[0].name, category: 2, icon: '1', color: '000000' });
    await page.locator('.cur').click();
    await page.getByRole('button', { name: '刷新余额', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.wallet-carousel .account-card-artwork')?.dataset.pattern === 'vault');
    assert.deepEqual((await request('v1/accounts/list.json?ledgerId=0', owner.token)).map(account => [account.id, account.balance]).sort(), categoryBalances);
    await request('v1/accounts/card/update.json', owner.token, { ledgerId: '0', accountId: accounts[0].id, appearance: { ...preservedArtwork, revision: (await request(styleQuery(accounts[0]), owner.token)).revision } });
    pass('nine category identities refresh with raster artwork without changing balances or custom artwork');
    await mkdir('artifacts/agent-access', { recursive: true });
    await writeFile('artifacts/agent-access/wallet-carousel-final.json', JSON.stringify({ passed: checks.length, checks }, null, 2));
} catch (error) {
    console.log('Failed route:', page?.url());
    if (page) await page.screenshot({ path: 'artifacts/agent-access/wallet-carousel-failure.png' }).catch(() => {});
    console.error(String(error.message).replace(/eyJ[A-Za-z0-9_.-]+/g, '[redacted]'));
    process.exitCode = 1;
} finally {
    if (browser) await browser.close();
}
