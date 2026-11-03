import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// No mounted user database and no saved tokens or browser storage artifacts.
const base = 'http://127.0.0.1:18114';
const info = JSON.parse(execFileSync('docker', ['inspect', 'finexy-periodic-mobile-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert.equal(info.Config.Image, 'finexy-periodic-mobile:20261005-root1');
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=236'));
assert.equal(info.Name, '/finexy-periodic-mobile-e2e');
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert.equal(info.NetworkSettings.Ports['8080/tcp'][0].HostPort, '18114');
const output = 'artifacts/periodic-mobile-android-20261005/mobile-web';
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const password = randomBytes(18).toString('hex');
async function api(path, token, body, expected = 200) {
    const res = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(res.status, expected, `HTTP ${path.split('?')[0]}`);
    const data = await res.json();
    if (expected === 200) assert(data.success, `success ${path.split('?')[0]}`);
    return data.result;
}
async function register(label) {
    const username = `mobile_write_${randomBytes(6).toString('hex')}`;
    return { username, ...(await api('register.json', '', { username, password, email: `${username}@example.invalid`,
        nickname: label, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] })) };
}
let browser, page;
try {
    await mkdir(output, { recursive: true });
    for (let attempt = 0; attempt < 40; attempt++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch {}
        if (attempt === 39) throw new Error('Disposable mobile service did not start');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register('Periodic owner'); const admin = await register('Periodic admin');
    const member = await register('Periodic member'); const viewer = await register('Periodic viewer');
    const ledger = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic periodic mobile A' });
    const other = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic periodic mobile B' });
    const account = await api('v1/accounts/add.json', owner.token, { ledgerId: ledger.id, name: 'Synthetic USD', category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 });
    const root = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic root', type: 2, icon: '1', color: '000000' });
    const leaf = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense', type: 2, parentId: root.id, icon: '1', color: '000000' });
    for (const [user, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledger.id, inviteeName: user.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', user.token, { token: invitation.token });
        if (role === 2) {
            const me = (await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, user.token)).find(row => row.isCurrentUser);
            await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledger.id, memberId: me.id, role: 2 });
        }
    }
    const utc = new Date(); const minute = utc.getUTCHours() * 60 + utc.getUTCMinutes(); const offset = minute <= 720 ? -minute : 1440 - minute;
    const draft = { ledgerId: ledger.id, templateType: 2, name: 'Synthetic due expense', type: 3, categoryId: leaf.id, sourceAccountId: account.id, destinationAccountId: '0', sourceAmount: 250, destinationAmount: 0, utcOffset: offset, hideAmount: false, tagIds: [], comment: 'Synthetic periodic', scheduledFrequencyType: 3, scheduledFrequency: '0', scheduledStartDate: '2020-01-01' };
    const scheduled = await api('v1/transaction/templates/add.json', owner.token, { ...draft, clientSessionId: randomBytes(16).toString('hex') });
    const refund = await api('v1/transaction/templates/add.json', owner.token, { ...draft, name: 'Synthetic due refund', sourceAmount: -100, clientSessionId: randomBytes(16).toString('hex') });
    await api('v1/transaction/templates/add.json', admin.token, { ...draft, name: 'Synthetic normal', templateType: 1, clientSessionId: randomBytes(16).toString('hex') });
    for (const user of [member, viewer]) await api('v1/transaction/templates/add.json', user.token, { ...draft, clientSessionId: randomBytes(16).toString('hex') }, 403);
    execFileSync('docker', ['exec', 'finexy-periodic-mobile-e2e', '/ezbookkeeping/ezbookkeeping', 'cron', 'run', '--name', 'CreateScheduledTransaction'], { stdio: 'pipe', timeout: 30000 });
    const pending = await api(`v1/schedule/review/list.json?ledgerId=${ledger.id}&status=1&limit=100`, owner.token);
    assert.equal(pending.length, 2);
    pass('four role API gate and real cron produce pending records without posting');
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const errors = [];
    async function mobile(user) {
        const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 390, height: 844 } });
        const tab = await context.newPage(); tab.setDefaultTimeout(15000);
        tab.on('pageerror', error => errors.push(error.name));
        await tab.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
        const current = () => tab.locator('.view-main > .page.page-current');
        await current().locator('input[type="text"]').first().fill(user.username);
        await current().locator('input[type="password"]').first().fill(password);
        await current().getByText('登录', { exact: true }).click();
        await current().getByText('当前账本：默认个人账本', { exact: true }).waitFor();
        return tab;
    }
    const current = tab => tab.locator('.view-main > .page.page-current');
    async function navigate(tab, path) {
        await tab.bringToFront();
        await tab.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
        await tab.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
        await tab.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
        await tab.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange && !document.querySelector('.view-main.router-transition'));
        await current(tab).waitFor();
    }
    async function switchBook(tab, name) {
        await navigate(tab, '/ledger/manage');
        await current(tab).locator('.list .item-link').filter({ hasText: name }).click();
        await current(tab).getByText('切换到此账本', { exact: true }).click();
        await current(tab).getByText(new RegExp(`正在使用：${name}`)).waitFor();
    }
    page = await mobile(owner); await switchBook(page, ledger.name); await navigate(page, '/template/list');
    await current(page).getByText('Synthetic normal', { exact: true }).waitFor();
    assert.equal(await current(page).getByText('应用模板', { exact: true }).count(), 1);
    assert.equal(await current(page).getByText('编辑模板', { exact: true }).count(), 1);
    pass('owner cold startup opens shared normal templates and actual USD amount');
    await switchBook(page, other.name); await navigate(page, '/template/list');
    await current(page).getByText('没有可见模板，可查看隐藏模板或新增。', { exact: true }).waitFor();
    assert.equal(await current(page).getByText('Synthetic normal', { exact: true }).count(), 0);
    await switchBook(page, ledger.name); await navigate(page, '/schedule/list');
    await current(page).getByText('Synthetic due expense', { exact: true }).waitFor();
    pass('A to B to A switch never displays another ledger template cache');
    await current(page).getByText(/^待确认 /).first().click();
    const expenseCard = current(page).locator('.periodic-card').filter({ hasText: 'Synthetic due expense' });
    await expenseCard.getByText('确认入账', { exact: true }).click();
    const receipt = page.waitForResponse(response => response.url().includes('/schedule/review/confirm.json'));
    await page.locator('.actions-modal.modal-in').getByText('确认入账', { exact: true }).click();
    assert.equal((await receipt).status(), 200);
    await expenseCard.waitFor({ state: 'hidden' });
    const confirmed = await api(`v1/schedule/review/list.json?ledgerId=${ledger.id}&status=2&limit=100`, owner.token);
    assert.equal(confirmed.length, 1); assert.equal(confirmed[0].templateId, scheduled.id);
    const refundCard = current(page).locator('.periodic-card').filter({ hasText: 'Synthetic due refund' });
    assert.match(await refundCard.locator('.periodic-amount').innerText(), /1[.,]00/);
    await refundCard.getByText('忽略记录', { exact: true }).click();
    const dismissedResponse = page.waitForResponse(response => response.url().includes('/schedule/review/dismiss.json'));
    await page.locator('.actions-modal.modal-in').getByText('忽略记录', { exact: true }).click();
    assert.equal((await dismissedResponse).status(), 200);
    await current(page).getByText(/^已忽略 /).first().click();
    const ignoredCard = current(page).locator('.periodic-card').filter({ hasText: 'Synthetic due refund' });
    await ignoredCard.getByText('恢复待确认', { exact: true }).waitFor();
    await ignoredCard.getByText('恢复待确认', { exact: true }).click();
    const restoreResponse = page.waitForResponse(response => response.url().includes('/schedule/review/restore.json'));
    await page.locator('.actions-modal.modal-in').getByText('恢复待确认', { exact: true }).click();
    assert.equal((await restoreResponse).status(), 200);
    const restored = await api(`v1/schedule/review/list.json?ledgerId=${ledger.id}&status=1&limit=100`, owner.token);
    assert.equal(restored.length, 1); assert.equal(restored[0].templateId, refund.id);
    const balance = await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token);
    assert.equal(balance.balance, 9750);
    pass('real mobile confirmation, dismissal and restoration change balance only on confirmation');
    for (const user of [admin, member, viewer]) {
        const tab = await mobile(user); await switchBook(tab, ledger.name); await navigate(tab, '/template/list');
        await current(tab).getByText('Synthetic normal', { exact: true }).waitFor();
        assert.equal(await current(tab).getByText('编辑模板', { exact: true }).count(), user === admin ? 1 : 0);
        assert.equal(await current(tab).getByText('应用模板', { exact: true }).count(), user === viewer ? 0 : 1);
        await navigate(tab, '/schedule/review');
        await current(tab).getByText('Synthetic due refund', { exact: true }).waitFor();
        assert.equal(await current(tab).getByText('确认入账', { exact: true }).count(), user === viewer ? 0 : 1);
        await tab.close();
    }
    pass('actual admin, member and viewer pages expose only their allowed operations');
    await page.setViewportSize({ width: 375, height: 812 });
    await current(page).getByText(/^待确认 /).first().click();
    await page.screenshot({ path: `${output}/pending-375.png` });
    const geometry = await current(page).evaluate(element => ({ width: element.scrollWidth, viewport: innerWidth }));
    assert(geometry.width <= geometry.viewport + 1);
    assert.deepEqual(errors, []);
    pass('375px production page has no overflow or browser runtime error');
    await writeFile(`${output}/summary.json`, JSON.stringify({ checks, passed: true, image: info.Image, geometry, browserErrors: errors }, null, 2));
} catch (error) {
    if (page && !page.isClosed()) await page.screenshot({ path: `${output}/failure.png` }).catch(() => {});
    console.error(error.message); process.exitCode = 1;
} finally { await browser?.close(); }
