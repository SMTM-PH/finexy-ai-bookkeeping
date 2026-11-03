import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Synthetic records only, on a disposable service without user data mounts.
const base = 'http://127.0.0.1:18110';
const info = JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const password = randomBytes(18).toString('hex');
const username = `history_${randomBytes(6).toString('hex')}`;
async function request(path, token, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const json = await response.json();
    assert.equal(response.status, 200, `HTTP ${path}`);
    assert(json.success, `success ${path}`);
    return json.result;
}
let browser;
let page;
try {
    let healthy = false;
    for (let i = 0; i < 60; i++) {
        try { healthy = (await fetch(`${base}/healthz.json`)).ok; } catch { /* server starting */ }
        if (healthy) break;
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert(healthy, 'isolated server health');
    const owner = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: '历史流水验收', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 0, balanceTime: 946684800 };
    const account = await request('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'History account' });
    const root = await request('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic root', type: 2, icon: '1', color: '000000' });
    const category = await request('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const now = new Date();
    const month = now.getMonth();
    const year = now.getFullYear();
    const previous = new Date(year, month - 1, 15, 12).getTime() / 1000;
    const current = new Date(year, month, 1, 0, 0, 1).getTime() / 1000;
    const draft = { type: 3, utcOffset: 480, sourceAccountId: account.id, categoryId: category.id, sourceAmount: 100 };
    // 105 transactions in the same second stress both API cursors and Vue row keys.
    const longTitle = `Historic 104 超长商户交易名称与商品说明 ${'测试商品和商户描述 '.repeat(12)}[wechat:${'420000'.repeat(8)}]`;
    for (let i = 0; i < 105; i++) await request('v1/transactions/add.json', owner.token, { ...draft, time: previous, sourceAmount: i === 104 ? 123456789 : 100, comment: i === 104 ? longTitle : `Historic ${String(i).padStart(3, '0')}` });
    await request('v1/transactions/add.json', owner.token, { ...draft, time: current, sourceAmount: 321, comment: 'Current expense' });
    const ledger = await request('v1/ledger/create.json', owner.token, { type: 1, name: 'History separate ledger' });
    const other = await request('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Separate account', ledgerId: ledger.id });
    await request('v1/transactions/add.json', owner.token, { ...draft, time: previous, sourceAccountId: other.id, ledgerId: ledger.id, comment: 'Separate ledger expense' });
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    page = await browser.newPage({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(/#\/$/);
    await page.goto(`${base}/desktop#/transaction/list?pageType=1&dateType=7`, { waitUntil: 'networkidle' });
    await page.getByText('Current expense', { exact: true }).waitFor();
    assert.match(await page.locator('tbody').innerText(), /History account/);
    assert.match(await page.locator('tbody').innerText(), /Synthetic expense/);
    assert.match(await page.locator('.transaction-range').first().innerText(), /本月.*1 笔/);
    assert.equal(await page.getByText(/^Historic /).count(), 0);
    pass('current month deep link applies actual date range');
    await page.getByRole('button', { name: '查看全部日期', exact: true }).first().click();
    await page.waitForFunction(() => document.querySelector('.transaction-range')?.textContent?.includes('106 笔'));
    assert.match(await page.locator('.table-foot').innerText(), /共 106 笔/);
    assert.match(await page.locator('.metrics article').filter({ hasText: '本月支出' }).innerText(), /3\.21/);
    const longRow = page.locator('.transaction-table tbody tr').filter({ hasText: longTitle });
    await longRow.waitFor();
    assert.equal(await longRow.locator('.transaction-name b').getAttribute('title'), longTitle);
    assert.match(await longRow.locator('.transaction-amount-cell').innerText(), /-.*1,234,567\.89/);
    assert.equal(await longRow.locator('.transaction-date-cell').innerText(), new Date(previous * 1000).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai' }));
    for (const width of [1396, 1440]) {
        await page.setViewportSize({ width, height: 871 });
        const layout = await longRow.evaluate(row => {
            const table = row.closest('table'), wrap = table.parentElement, name = row.querySelector('.transaction-name b');
            const bounds = wrap.getBoundingClientRect();
            return { overflow: wrap.scrollWidth > wrap.clientWidth + 1, nameClipped: name.scrollWidth > name.clientWidth, keyCellsVisible: ['.transaction-amount-cell', '.transaction-date-cell'].every(selector => { const r = row.querySelector(selector).getBoundingClientRect(); return r.left >= bounds.left && r.right <= bounds.right && r.right <= innerWidth; }), headers: [...table.querySelectorAll('thead th')].map(th => th.textContent.trim()) };
        });
        assert(!layout.overflow && layout.nameClipped && layout.keyCellsVisible, JSON.stringify(layout));
        assert.deepEqual(layout.headers, ['', '交易', '金额', '日期', '类型', '分类', '账户', '状态']);
    }
    for (const colorScheme of ['light', 'dark']) {
        await page.emulateMedia({ colorScheme });
        assert.equal(await longRow.locator('.transaction-name b').isVisible(), true);
        assert.equal(await longRow.locator('.transaction-amount-cell').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    }
    await page.setViewportSize({ width: 1745, height: 1000 });
    await page.evaluate(() => { document.documentElement.style.zoom = '1.25'; });
    await page.waitForFunction(() => Number(getComputedStyle(document.documentElement).zoom) === 1.25);
    assert.equal(await page.locator('.transaction-table-wrap').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
    await page.waitForFunction(() => Number(getComputedStyle(document.documentElement).zoom) === 1);
    await page.setViewportSize({ width: 1396, height: 871 });
    await page.getByRole('button', { name: '紧凑', exact: true }).click();
    assert.equal(await longRow.locator('.transaction-amount-cell').isVisible(), true);
    await page.getByRole('button', { name: '舒展', exact: true }).click();
    await longRow.focus(); await page.keyboard.press('Enter');
    await page.getByRole('heading', { name: longTitle, exact: true }).waitFor();
    assert.equal(await page.locator('.transaction-detail').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    await page.getByRole('button', { name: '关闭详情', exact: true }).click();
    await page.locator('.transaction-detail').waitFor({ state: 'hidden' });
    await page.locator('.transaction-table').screenshot({ path: 'artifacts/agent-access/transaction-layout-table-final.png' });
    pass('long names stay bounded; full amount/date visible; themes, 125% zoom, compact rows and keyboard detail work');
    for (let i = 0; i < 10; i++) await page.getByRole('button', { name: '下一页', exact: true }).click();
    assert.match(await page.locator('.table-foot').innerText(), /11 \/ 11/);
    assert.equal(await page.locator('tbody tr').count(), 6);
    assert.equal(await page.getByRole('button', { name: '下一页', exact: true }).isDisabled(), true);
    pass('all dates reads every cursor page, distinct same-second rows, last page and current-month KPI');
    await page.getByRole('button', { name: '筛选', exact: true }).click();
    const dates = page.getByRole('combobox', { name: '日期范围', exact: true });
    await dates.selectOption('8');
    await page.waitForFunction(() => document.querySelector('.transaction-range')?.textContent?.includes('105 笔'));
    assert.equal(await page.getByText('Current expense', { exact: true }).count(), 0);
    await page.waitForURL(url => new URL(url.hash.slice(1), base).searchParams.get('dateType') === '8');
    assert.equal(new URL(page.url().split('#')[1], base).searchParams.get('dateType'), '8');
    const previousDate = new Date(year, month - 1, 1);
    const previousMonth = `${previousDate.getFullYear()}-${String(previousDate.getMonth() + 1).padStart(2, '0')}`;
    await page.locator('input[type="month"]').fill(previousMonth);
    await page.locator('input[type="month"]').dispatchEvent('change');
    assert.match(await page.locator('.transaction-range').first().innerText(), /105 笔/);
    await page.getByRole('button', { name: '清除筛选', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.transaction-range')?.textContent?.includes('106 笔'));
    pass('last month and specific month work; clearing filters restores all dates');
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click();
    await page.getByRole('option').filter({ hasText: 'History separate ledger' }).click();
    await page.getByText('Separate ledger expense', { exact: true }).waitFor();
    assert.match(await page.locator('.table-foot').innerText(), /共 1 笔/);
    assert.equal(await page.getByText(/^Historic /).count(), 0);
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click();
    await page.getByRole('option').filter({ hasText: '默认个人账本' }).click();
    await page.waitForFunction(() => document.querySelector('.transaction-range')?.textContent?.includes('106 笔'));
    assert.equal(await page.getByText('Separate ledger expense', { exact: true }).count(), 0);
    pass('ledger changes isolate history and restore default ledger snapshot');
    let fail = true;
    await page.route('**/api/v1/transactions/list.json?**', async route => { if (fail) await route.fulfill({ status: 200, json: { success: true } }); else await route.continue(); });
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '读取流水失败' }).waitFor();
    await page.getByText('流水尚未成功读取', { exact: true }).waitFor();
    fail = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.transaction-range')?.textContent?.includes('106 笔'));
    assert.equal(await page.getByRole('alert').filter({ hasText: '读取流水失败' }).count(), 0);
    pass('invalid response shows recoverable failure, never a successful empty ledger');
    for (const width of [1440, 768, 375]) {
        await page.setViewportSize({ width, height: 1000 });
        await dates.waitFor({ state: 'visible' });
        // Desktop workspace retains horizontal scrolling below its minimum width.
        await dates.scrollIntoViewIfNeeded();
        assert.equal(await dates.evaluate(el => el.getBoundingClientRect().right <= innerWidth + 1), true);
    }
    assert.deepEqual(errors, []);
    await mkdir('artifacts/agent-access', { recursive: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.screenshot({ path: 'artifacts/agent-access/history-filter-final.png' });
    pass('production cold start and visible date controls at desktop/tablet/narrow widths');
    await writeFile('artifacts/agent-access/history-e2e-final.json', JSON.stringify({ passed: checks.length, checks }, null, 2));
} catch (error) {
    if (page) {
        await page.screenshot({ path: 'artifacts/agent-access/history-failure.png' }).catch(() => {});
        await writeFile('artifacts/agent-access/history-failure.txt', (await page.locator('body').innerText()).replace(/eyJ[A-Za-z0-9_.-]+/g, '[redacted]')).catch(() => {});
    }
    console.error(String(error.message).replace(/eyJ[A-Za-z0-9_.-]+/g, '[redacted]'));
    process.exitCode = 1;
} finally {
    if (browser) await browser.close();
}
