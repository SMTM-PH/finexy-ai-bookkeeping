import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const containerName = 'finexy-budget-e2e';
const base = 'http://127.0.0.1:18111';
const info = JSON.parse(execFileSync('docker', ['inspect', containerName], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(port => port.HostPort === '18111' && port.HostIp === '127.0.0.1'));
const output = 'artifacts/ledger-budget-20261004';
const password = randomBytes(18).toString('hex');
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const now = new Date();
const month = now.getFullYear() * 100 + now.getMonth() + 1;
async function api(path, token, body, status = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {})
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, status, path.split('?')[0]);
    const data = await response.json();
    if (status === 200) { assert(data.success); assert(Object.hasOwn(data, 'result'), 'successful budget responses must have a result'); }
    return data.result;
}
async function register(currency = 'CNY') {
    const username = `budget_${randomBytes(6).toString('hex')}`;
    return { username, ...(await api('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic budget QA', language: 'zh-Hans', defaultCurrency: currency, categories: [] })) };
}
let browser;
try {
    await mkdir(output, { recursive: true });
    for (let i = 0; i < 60; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* starting */ }
        if (i === 59) throw new Error('Disposable budget service did not start');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register(); const admin = await register('EUR'); const member = await register('USD'); const viewer = await register('CAD'); const outsider = await register();
    const a = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic budget A' });
    const b = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic budget B' });
    const get = (user, ledgerId, status = 200) => api(`v1/budget/monthly/get.json?yearMonth=${month}&ledgerId=${ledgerId}`, user.token, undefined, status);
    const save = async (user, ledgerId, amount, currency = 'USD', categoryAmounts = {}, status = 200, revision) => {
        const expectedRevision = revision ?? (await get(user, ledgerId))?.revision ?? 0;
        return api('v1/budget/monthly/set.json', user.token, { ledgerId, yearMonth: month, amount, currency, categoryAmounts, expectedRevision }, status);
    };
    const remove = async (user, ledgerId, status = 200, revision) => {
        const expectedRevision = revision ?? (await get(user, ledgerId))?.revision ?? 0;
        return api('v1/budget/monthly/delete.json', user.token, { ledgerId, yearMonth: month, expectedRevision }, status);
    };
    for (const [user, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invite = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: a.id, inviteeName: user.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', user.token, { token: invite.token });
        if (role === 2) {
            const me = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, user.token)).find(row => row.isCurrentUser);
            await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: me.id, role: 2 });
        }
    }
    assert.equal(await get(owner, a.id), null);
    const personal = await api('v1/budget/monthly/set.json', owner.token, { yearMonth: month, amount: 99000, categoryAmounts: {} });
    assert.equal(personal.ledgerId, '0'); assert.equal(personal.currency, 'CNY');
    const initial = await save(owner, a.id, 100000);
    for (const user of [owner, admin, member, viewer]) { const value = await get(user, a.id); assert.equal(value.id, initial.id); assert.equal(value.currency, 'USD'); }
    assert.equal(await get(owner, b.id), null); assert.equal(await get(member, '0'), null);
    assert.equal((await get(owner, '0')).amount, 99000);
    await get(outsider, a.id, 403);
    pass('personal and A/B budgets stay separate; all four members see the same explicit currency and outsiders are denied');
    await save(admin, a.id, 80000);
    for (const user of [member, viewer]) { await save(user, a.id, 1, 'CNY', {}, 403); await remove(user, a.id, 403); }
    assert.equal((await get(owner, a.id)).amount, 80000);
    pass('owner/admin manage budgets; member/viewer cannot overwrite or clear the limit');
    const root = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense root', type: 2, icon: '1', color: '000000' });
    const leaf = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense leaf', type: 2, parentId: root.id, icon: '1', color: '000000' });
    await save(owner, a.id, 100000, 'USD', { [leaf.id]: 10000 });
    const foreignRoot = await api('v1/transaction/categories/add.json', outsider.token, { name: 'Synthetic foreign root', type: 2, icon: '1', color: '000000' });
    const foreignLeaf = await api('v1/transaction/categories/add.json', outsider.token, { name: 'Synthetic foreign leaf', type: 2, parentId: foreignRoot.id, icon: '1', color: '000000' });
    for (const id of [root.id, foreignLeaf.id]) await save(owner, a.id, 100000, 'USD', { [id]: 100 }, 400);
    for (const [amount, currency] of [[1.25, 'USD'], [10000000000000, 'USD'], [100, 'ZZZ']]) await save(owner, a.id, amount, currency, {}, 400);
    assert.deepEqual((await get(owner, a.id)).categoryAmounts, { [leaf.id]: 10000 });
    pass('invalid integer/currency and root/foreign categories fail without changing the saved budget');
    const old = await get(owner, a.id);
    await save(admin, a.id, 100000, 'USD', old.categoryAmounts);
    await save(owner, a.id, 200000, 'USD', old.categoryAmounts, 409, old.revision);
    await remove(owner, a.id, 409, old.revision);
    const beforeDelete = await get(owner, a.id); await remove(owner, a.id);
    const recreated = await save(owner, a.id, 100000, 'USD', old.categoryAmounts);
    assert(recreated.revision > beforeDelete.revision);
    await save(admin, a.id, 300000, 'USD', {}, 409, beforeDelete.revision);
    assert.equal((await get(owner, a.id)).amount, 100000);
    pass('stale edits and deletes return conflict; delete/recreate cannot reuse a prior budget revision');
    const empty = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic budget-only ledger' });
    await save(owner, empty.id, 5000, 'CNY');
    const preview = await api(`v1/ledger/delete/preview.json?id=${empty.id}`, owner.token);
    assert.equal(preview.monthlyBudgetCount, 1); assert.equal(preview.canDelete, false); assert(preview.blockingReasons.includes('monthlyBudgets'));
    await api('v1/ledger/delete.json', owner.token, { id: empty.id }, 409);
    await remove(owner, empty.id);
    assert.equal((await api(`v1/ledger/delete/preview.json?id=${empty.id}`, owner.token)).canDelete, true);
    await api('v1/ledger/delete.json', owner.token, { id: empty.id });
    pass('budget-only ledgers cannot be deleted; clearing the budget allows protected empty-ledger deletion');
    const account = await api('v1/accounts/add.json', owner.token, { ledgerId: a.id, name: 'Synthetic USD', category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 0, balanceTime: 946684800 });
    const cny = await api('v1/accounts/add.json', owner.token, { ledgerId: a.id, name: 'Synthetic CNY', category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 0, balanceTime: 946684800 });
    for (const [accountId, amount] of [[account.id, 9000], [account.id, -1000], [cny.id, 999999]]) await api('v1/transactions/add.json', owner.token, { ledgerId: a.id, type: 3, time: Math.floor(Date.now() / 1000), utcOffset: 480, categoryId: leaf.id, sourceAccountId: accountId, sourceAmount: amount, comment: 'Synthetic budget usage', clientSessionId: randomBytes(16).toString('hex') });
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const errors = [];
    async function desktop(user) {
        const page = await browser.newPage({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
        page.setDefaultTimeout(20000); page.on('pageerror', error => errors.push(error.name));
        await page.goto(`${base}/desktop`, { waitUntil: 'networkidle' });
        await page.getByLabel('用户名', { exact: true }).fill(user.username); await page.getByLabel('密码', { exact: true }).fill(password);
        await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(/#\/$/);
        await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: a.name }).click();
        await page.goto(`${base}/desktop#/schedule/list`, { waitUntil: 'networkidle' });
        await page.locator('.monthly-budget-panel').getByText('本月总额度 · USD', { exact: true }).waitFor();
        return page;
    }
    const page = await desktop(owner); const panel = page.locator('.monthly-budget-panel');
    await panel.getByText(/使用率 8(?:\.0)?%/).waitFor();
    assert.match(await panel.locator('.budget-overview').innerText(), /80\.00/);
    assert.match(await panel.locator('.saved-category-limits').innerText(), /20\.00/);
    await panel.getByLabel('本月总额度', { exact: true }).fill('1200.00');
    await panel.getByRole('button', { name: '保存本月额度', exact: true }).click();
    await panel.getByText('本月预算已保存。', { exact: true }).waitFor(); assert.equal((await get(owner, a.id)).amount, 120000);
    pass('production desktop uses same-currency net expenses and category remaining, and saves to the chosen ledger');
    await panel.getByLabel('本月总额度', { exact: true }).fill('1600.00');
    await save(admin, a.id, 180000, 'USD', { [leaf.id]: 10000 });
    let conflictWrites = 0;
    const countConflictWrite = request => { if (request.url().includes('/budget/monthly/set.json')) conflictWrites++; };
    page.on('request', countConflictWrite);
    const conflictResponse = page.waitForResponse(response => response.url().includes('/budget/monthly/set.json') && response.request().method() === 'POST');
    await panel.getByRole('button', { name: '保存本月额度', exact: true }).click();
    assert.equal((await conflictResponse).status(), 409);
    await panel.getByRole('alert').first().waitFor();
    assert.equal(await panel.getByLabel('本月总额度', { exact: true }).inputValue(), '1600.00');
    assert.equal(conflictWrites, 1, 'conflict cannot automatically retry a write');
    await panel.getByRole('button', { name: '读取服务器预算核对', exact: true }).click();
    await panel.getByRole('button', { name: '保留我的输入并采用此版本', exact: true }).waitFor();
    assert.match(await panel.innerText(), /1800\.00/);
    assert.equal(await panel.getByLabel('本月总额度', { exact: true }).inputValue(), '1600.00');
    await panel.getByRole('button', { name: '保留我的输入并采用此版本', exact: true }).click();
    assert.equal(conflictWrites, 1, 'baseline adoption alone cannot write');
    await panel.getByRole('button', { name: '保存本月额度', exact: true }).click();
    await panel.getByText('本月预算已保存。', { exact: true }).waitFor();
    assert.equal((await get(owner, a.id)).amount, 160000);
    page.off('request', countConflictWrite);
    await panel.getByLabel('本月总额度', { exact: true }).fill('1200.00');
    await panel.getByRole('button', { name: '保存本月额度', exact: true }).click();
    await panel.getByText('本月预算已保存。', { exact: true }).waitFor();
    assert.equal((await get(owner, a.id)).amount, 120000);
    pass('production conflict preserves input and requires reading and explicitly adopting the new baseline before another save');
    for (const user of [member, viewer]) {
        const readonly = await desktop(user);
        assert.equal(await readonly.locator('.monthly-budget-panel').getByRole('button', { name: '保存本月额度', exact: true }).count(), 0);
        assert.match(await readonly.locator('.monthly-budget-panel').innerText(), /由所有者或管理员管理/);
        await readonly.close();
    }
    pass('ordinary and read-only members see a real budget without management inputs');
    const adminPage = await desktop(admin); const adminPanel = adminPage.locator('.monthly-budget-panel');
    await adminPanel.getByLabel('本月总额度', { exact: true }).fill('1500.00');
    const adminRow = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, admin.token)).find(row => row.isCurrentUser);
    let deniedWrites = 0; adminPage.on('request', request => { if (request.url().includes('/budget/monthly/set.json')) deniedWrites++; });
    await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: adminRow.id, role: 4 });
    await adminPanel.getByRole('button', { name: '保存本月额度', exact: true }).click();
    await adminPanel.getByRole('alert').filter({ hasText: '权限已变化' }).waitFor();
    assert.equal(deniedWrites, 0); assert.equal((await get(owner, a.id)).amount, 120000);
    await adminPage.close();
    pass('live downgrade stops an open management form before POST and explains the changed permission');
    await panel.getByRole('button', { name: '隐藏预算金额', exact: true }).click();
    assert.equal(await panel.locator('progress').count(), 0); assert.equal(await panel.getByLabel('本月总额度', { exact: true }).count(), 0);
    assert(!(await panel.innerText()).includes('1200.00'));
    await panel.getByRole('button', { name: '显示预算金额', exact: true }).click();
    await panel.getByRole('button', { name: '清除本月限额', exact: true }).click();
    await panel.getByRole('button', { name: '保留预算', exact: true }).click(); assert.equal((await get(owner, a.id)).amount, 120000);
    pass('amount hiding covers editor and progress; cancelling clear preserves the budget');
    await page.route('**/api/v1/budget/monthly/get.json*', route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true }) }));
    await panel.getByRole('button', { name: '刷新预算', exact: true }).click(); await panel.getByRole('alert').first().waitFor();
    assert(!(await panel.innerText()).includes('尚未设置预算'));
    await page.unroute('**/api/v1/budget/monthly/get.json*'); await panel.getByRole('button', { name: '重试读取预算与权限', exact: true }).click();
    await page.locator('.monthly-budget-panel[aria-busy="false"]').waitFor();
    await panel.getByText('本月总额度 · USD', { exact: true }).waitFor();
    await panel.getByText('预算额度内 · 使用率 6.7%', { exact: true }).waitFor();
    pass('missing result is a visible failure and retry restores the real budget');
    await page.screenshot({ path: `${output}/desktop-budget.png`, fullPage: true });

    const mobileContext = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 }, reducedMotion: 'reduce' });
    const mobile = await mobileContext.newPage(); mobile.setDefaultTimeout(20000);
    mobile.on('pageerror', error => errors.push(error.name));
    const current = () => mobile.locator('.view-main > .page.page-current');
    async function navigate(path) {
        await mobile.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
        await mobile.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
        await mobile.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
        await mobile.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange && !document.querySelector('.view-main.router-transition'));
    }
    async function switchBook(name) {
        await navigate('/ledger/manage');
        await current().locator('.list .item-link').filter({ hasText: name }).click();
        await current().getByText('切换到此账本', { exact: true }).click();
        await current().getByText(new RegExp(`正在使用：${name}`)).waitFor();
    }
    await mobile.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await current().locator('input[type="text"]').first().fill(owner.username);
    await current().locator('input[type="password"]').first().fill(password);
    await current().getByText('登录', { exact: true }).click();
    await current().getByText('当前账本：默认个人账本', { exact: true }).waitFor();
    await switchBook(a.name); await navigate('/settings/budget');
    const mobilePanel = () => current().locator('.monthly-budget-panel');
    await mobilePanel().getByText('本月总额度 · USD', { exact: true }).waitFor();
    await mobilePanel().getByLabel('本月总额度', { exact: true }).fill('1750.00');
    await mobilePanel().getByRole('button', { name: '预算下一个月', exact: true }).click();
    await mobilePanel().getByText(/尚未设置预算/).waitFor();
    assert.equal(await mobilePanel().getByLabel('本月总额度', { exact: true }).inputValue(), '');
    await mobilePanel().getByRole('button', { name: '预算上一个月', exact: true }).click();
    await mobilePanel().getByText('本月总额度 · USD', { exact: true }).waitFor();
    assert.equal(await mobilePanel().getByLabel('本月总额度', { exact: true }).inputValue(), '1750.00');
    await mobilePanel().getByRole('button', { name: '保存本月额度', exact: true }).click();
    await mobilePanel().getByText('本月预算已保存。', { exact: true }).waitFor();
    assert.equal((await get(owner, a.id)).amount, 175000);
    await switchBook(b.name); await navigate('/settings/budget');
    await mobilePanel().getByText(/尚未设置预算/).waitFor();
    assert.equal(await mobilePanel().getByLabel('本月总额度', { exact: true }).inputValue(), '');
    await switchBook(a.name); await navigate('/settings/budget');
    await mobilePanel().getByText('本月总额度 · USD', { exact: true }).waitFor();
    assert.equal((await get(owner, '0')).amount, 99000);
    pass('production mobile budget saves to its ledger, retains monthly drafts and cannot display A limits in B');
    await mobilePanel().getByRole('button', { name: '清除本月限额', exact: true }).click();
    await mobilePanel().getByRole('button', { name: '确认清除本月预算', exact: true }).click();
    await mobilePanel().getByText(/尚未设置预算/).waitFor();
    assert.equal(await get(owner, a.id), null);
    await mobilePanel().getByLabel('本月总额度', { exact: true }).fill('1750.00');
    await mobilePanel().getByLabel('预算币种', { exact: true }).selectOption('USD');
    await mobilePanel().getByRole('button', { name: '保存本月额度', exact: true }).click();
    await mobilePanel().getByText('本月预算已保存。', { exact: true }).waitFor();
    const accountAfter = await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${a.id}`, owner.token);
    assert.equal(accountAfter.balance, -8000);
    await mobilePanel().getByText('预算额度内 · 使用率 4.6%', { exact: true }).waitFor();
    pass('mobile clear requires confirmation and preserves recorded net expenses and account balances');
    await mobile.screenshot({ path: `${output}/mobile-budget-light.png` });
    await navigate('/settings/textsize');
    await current().locator('.range-slider').evaluate(element => element.f7Range.setValue(6));
    await mobile.locator('.navbar a').filter({ has: mobile.locator('.f7-icons', { hasText: 'checkmark_alt' }) }).last().click();
    await mobile.waitForFunction(() => document.documentElement.classList.contains('font-size-xxxx-large'));
    await navigate('/settings/budget');
    await mobilePanel().getByText('本月总额度 · USD', { exact: true }).waitFor();
    await mobile.screenshot({ path: `${output}/mobile-budget-light-max-font.png` });
    await navigate('/settings');
    await current().locator('.item-link').filter({ hasText: /^主题/ }).click();
    await Promise.all([mobile.waitForEvent('load'), mobile.locator('.popup.modal-in').getByText('深色', { exact: true }).click()]);
    await mobile.waitForLoadState('networkidle');
    await mobile.waitForFunction(() => document.documentElement.classList.contains('dark') && document.documentElement.classList.contains('font-size-xxxx-large'));
    await navigate('/settings/budget');
    await mobilePanel().getByText('本月总额度 · USD', { exact: true }).waitFor();
    for (const [width, height] of [[375, 812], [844, 390], [768, 1024], [1024, 768]]) {
        await mobile.setViewportSize({ width, height });
        const saveButton = mobilePanel().getByRole('button', { name: '保存本月额度', exact: true });
        await mobilePanel().getByLabel('本月总额度', { exact: true }).focus();
        await saveButton.scrollIntoViewIfNeeded();
        const bounds = await saveButton.boundingBox();
        assert(bounds && bounds.width >= 44 && bounds.height >= 44, 'mobile budget save target at least 44px');
        assert(bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0 && bounds.y + bounds.height <= height, 'mobile save reachable while field focused');
        assert.equal(await mobilePanel().evaluate(element => element.scrollWidth > element.clientWidth + 1), false, 'budget panel has no horizontal overflow');
        if (width === 375 || width === 844) await mobile.screenshot({ path: `${output}/mobile-budget-dark-${width}.png` });
    }
    pass('mobile real light/dark and maximum font preserve narrow/landscape controls without horizontal overflow');
    assert.deepEqual(errors, []);
    await writeFile(`${output}/budget-e2e.json`, JSON.stringify({ passed: checks.length, checks }, null, 2));
} catch (error) {
    console.error(String(error.message).replace(/eyJ[A-Za-z0-9_.-]+/g, '[redacted]')); process.exitCode = 1;
} finally { if (browser) await browser.close(); }
