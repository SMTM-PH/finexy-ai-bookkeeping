import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Dedicated disposable service only. Never persist authentication artifacts.
const container = 'finexy-template-web-editor-e2e';
const base = 'http://127.0.0.1:18128';
const output = 'artifacts/template-creation-web-editor-20261005';
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Name, `/${container}`); assert.deepEqual(info.Mounts, []);
assert.equal(info.Config.Image, 'finexy-template-web-editor:20261005-root2');
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=249'));
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostPort === '18128' && p.HostIp === '127.0.0.1'));
const password = randomBytes(18).toString('hex');
const checks = [], errors = [];
let legacyCreateCalls = 0;
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, token, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, 200, `HTTP ${path.split('?')[0]}`);
    const data = await response.json(); assert.equal(data.success, true, `success ${path.split('?')[0]}`); return data.result;
}
async function register(label) {
    const username = `template_ui_${randomBytes(6).toString('hex')}`;
    return { username, ...(await api('register.json', '', { username, password, email: `${username}@example.invalid`,
        nickname: label, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] })) };
}
let browser, page;
try {
    await mkdir(output, { recursive: true });
    for (let attempt = 0; attempt < 40; attempt++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* Starting disposable service. */ }
        if (attempt === 39) throw new Error('Disposable service did not start');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register('Synthetic template owner');
    const ledger = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic template A' });
    const other = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic template B' });
    const account = await api('v1/accounts/add.json', owner.token, { ledgerId: ledger.id, name: 'Synthetic USD', category: 2, type: 1,
        icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 });
    const root = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic root', type: 2, icon: '1', color: '000000' });
    const leaf = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const list = kind => api(`v1/transaction/templates/list.json?templateType=${kind}&ledgerId=${ledger.id}`, owner.token);
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    page = await context.newPage(); page.setDefaultTimeout(20000); page.on('pageerror', e => errors.push(e.name));
    let posts = [];
    page.on('request', request => { if (request.url().endsWith('/templates/add_durable.json')) posts.push(request.postDataJSON()); if (request.url().endsWith('/templates/add.json')) legacyCreateCalls++; });
    await page.goto(`${base}/desktop`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(owner.username); await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(/#\/$/);
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: ledger.name }).click();
    async function openDesktop(kind = 1) {
        const previous = page.getByRole('dialog');
        if (await previous.isVisible()) { await previous.getByRole('button', { name: '取消', exact: true }).click(); await previous.waitFor({ state: 'hidden' }); }
        await page.goto(`${base}/desktop#/${kind === 2 ? 'schedule' : 'template'}/list`, { waitUntil: 'networkidle' });
        await page.getByRole('button', { name: kind === 2 ? '新建计划' : '新增模板', exact: true }).first().click();
        await page.getByRole('dialog').locator('.template-creation-panel').waitFor();
        await page.getByRole('dialog').locator('.template-creation-panel').getByRole('status').filter({ hasText: /开始输入|可恢复|结果待确认|已确认创建/ }).waitFor();
        return page.getByRole('dialog');
    }
    async function selectCategory(editor) {
        await editor.getByRole('combobox', { name: '分类', exact: true }).locator('..').click();
        const menu = page.locator('.two-column-select-menu').filter({ visible: true });
        await menu.getByText(root.name, { exact: true }).click(); await menu.getByText(leaf.name, { exact: true }).click();
    }
    const panel = () => page.getByRole('dialog').locator('.template-creation-panel');
    let editor = await openDesktop();
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic desktop recovered');
    await editor.getByLabel(/^支出金额/).fill('1.');
    await selectCategory(editor);
    await panel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    assert.equal(posts.length, 0); assert.equal((await list(1)).length, 0);
    await page.reload({ waitUntil: 'networkidle' }); editor = await openDesktop();
    await panel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await editor.getByLabel('模板名称', { exact: true }).inputValue(), 'Synthetic desktop recovered');
    assert.equal(await editor.getByLabel(/^支出金额/).inputValue(), '1.'); assert.equal(posts.length, 0);
    pass('desktop production cold startup and reload restore raw decimal input without POST');
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel().getByRole('button', { name: '确认创建模板' }).waitFor(); assert.equal(posts.length, 0);
    await page.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); assert.equal(response.status(), 200); await response.body(); await route.abort('failed'); });
    await panel().getByRole('button', { name: '确认创建模板' }).click();
    await panel().getByRole('button', { name: '重试原请求' }).waitFor();
    const original = posts[0]; const saved = (await list(1))[0]; assert.equal(saved.sourceAmount, 100);
    await page.unroute('**/templates/add_durable.json');
    await page.reload({ waitUntil: 'networkidle' }); editor = await openDesktop();
    assert(await editor.getByLabel('模板名称', { exact: true }).evaluate(el => !!el.closest('[inert]')));
    await panel().getByRole('button', { name: '重试原请求' }).click();
    await editor.waitFor({ state: 'hidden' });
    assert.equal(posts.length, 2); assert.deepEqual(posts[1], original);
    const rows = await list(1); assert.equal(rows.length, 1); assert.equal(rows[0].id, saved.id);
    pass('desktop committed write with lost response reloads and retries the exact key/payload once to original ID');
    editor = await openDesktop();
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic receipt read failure');
    await editor.getByLabel(/^支出金额/).fill('-1.23'); await selectCategory(editor);
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    let committed = false;
    await page.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); committed = true; await route.fulfill({ response }); });
    await page.route('**/templates/list.json?**', async route => { if (committed) await route.abort('failed'); else await route.continue(); });
    await panel().getByRole('button', { name: '确认创建模板' }).click();
    await panel().getByRole('status').filter({ hasText: '已确认创建' }).waitFor();
    await panel().getByRole('alert').waitFor();
    const receiptPosts = posts.length;
    await page.unroute('**/templates/add_durable.json'); await page.unroute('**/templates/list.json?**');
    await page.reload({ waitUntil: 'networkidle' }); editor = await openDesktop();
    await panel().getByRole('button', { name: '重新读取原模板' }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal(posts.length, receiptPosts);
    assert.equal((await list(1)).find(row => row.name === 'Synthetic receipt read failure').sourceAmount, -123);
    pass('known receipt survives read failure and reload performs only reads; refund remains negative');

    editor = await openDesktop(2);
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic scheduled recovery');
    await editor.getByLabel(/^支出金额/).fill('2.34'); await selectCategory(editor);
    await editor.getByRole('combobox', { name: '开始日期', exact: true }).locator('..').click();
    await page.locator('.date-select-menu').filter({ visible: true }).locator('.dp--today').click();
    await editor.getByLabel('模板名称', { exact: true }).click();
    await editor.getByRole('combobox', { name: '定时交易周期', exact: true }).locator('..').click();
    await page.locator('.schedule-frequency-select-menu').filter({ visible: true }).locator('.schedule-frequency-type-container').getByText('每天', { exact: true }).click();
    await editor.getByLabel('模板名称', { exact: true }).click();
    await panel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    await page.reload({ waitUntil: 'networkidle' }); editor = await openDesktop(2);
    await panel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel().getByText(/到期只生成待确认记录/).waitFor();
    await panel().getByRole('button', { name: '确认创建模板' }).click(); await editor.waitFor({ state: 'hidden' });
    const schedule = (await list(2))[0]; assert.equal(schedule.sourceAmount, 234); assert.equal(schedule.utcOffset, 480);
    assert.equal(schedule.scheduledFrequencyType, 3); assert.equal(schedule.scheduledFrequency, '0'); assert(schedule.scheduledStartDate);
    pass('production scheduled editor restores date/timezone/frequency and confirms without posting a transaction');

    // A second production tab receives the journal through the real StorageEvent.
    editor = await openDesktop();
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic two tabs');
    await editor.getByLabel(/^支出金额/).fill('3.45'); await selectCategory(editor);
    await panel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    const second = await context.newPage(); second.setDefaultTimeout(20000);
    await second.goto(`${base}/desktop#/template/list`, { waitUntil: 'networkidle' });
    await second.getByRole('button', { name: '新增模板', exact: true }).first().click();
    const secondPanel = second.getByRole('dialog').locator('.template-creation-panel');
    await secondPanel.getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await second.getByRole('dialog').getByRole('button', { name: '预览并确认', exact: true }).click();
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await page.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); await response.body(); await route.abort('failed'); });
    await panel().getByRole('button', { name: '确认创建模板' }).click();
    await panel().getByRole('button', { name: '重试原请求' }).waitFor();
    await secondPanel.getByRole('button', { name: '重试原请求' }).waitFor();
    assert.equal(await secondPanel.getByRole('button', { name: '确认创建模板' }).count(), 0);
    const tabKey = posts.at(-1).clientSessionId;
    await page.unroute('**/templates/add_durable.json');
    let secondRequest;
    second.on('request', r => { if (r.url().endsWith('/templates/add_durable.json')) secondRequest = r.postDataJSON(); });
    await secondPanel.getByRole('button', { name: '重试原请求' }).click();
    await second.getByRole('dialog').waitFor({ state: 'hidden' });
    assert.equal(secondRequest.clientSessionId, tabKey);
    assert.equal((await list(1)).filter(row => row.name === 'Synthetic two tabs').length, 1);
    await panel().getByRole('status').filter({ hasText: '另一页面处理或移除' }).waitFor(); await second.close();
    pass('two real production tabs use IndexedDB locks and storage events to recover one original request');

    editor = await openDesktop();
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic deleted original');
    await editor.getByLabel(/^支出金额/).fill('4.56'); await selectCategory(editor);
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await page.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); await response.body(); await route.abort('failed'); });
    await panel().getByRole('button', { name: '确认创建模板' }).click(); await panel().getByRole('button', { name: '重试原请求' }).waitFor();
    const deleted = (await list(1)).find(row => row.name === 'Synthetic deleted original');
    await api('v1/transaction/templates/delete.json', owner.token, { ledgerId: ledger.id, id: deleted.id });
    await page.unroute('**/templates/add_durable.json');
    await page.reload({ waitUntil: 'networkidle' }); editor = await openDesktop();
    await panel().getByRole('button', { name: '重试原请求' }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal((await list(1)).filter(row => row.name === deleted.name).length, 0);
    assert.deepEqual(posts.at(-1), posts.at(-2));
    pass('deleted original returns its tombstone on exact retry and never creates a replacement template');

    const old = JSON.parse(execFileSync('docker', ['inspect', 'finexy-template-web-old-e2e'], { encoding: 'utf8' }))[0];
    assert.deepEqual(old.Mounts, []); assert.equal(old.Name, '/finexy-template-web-old-e2e');
    assert.equal(old.Config.Image, 'finexy-template-submission-candidate:20261005-root1');
    assert(old.Config.Env.includes('EBK_UUID_SERVER_ID=250'));
    assert(old.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18129'));
    editor = await openDesktop();
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic old server gate');
    await editor.getByLabel(/^支出金额/).fill('5.67'); await selectCategory(editor);
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await page.route('**/templates/capabilities.json', async route => {
        const response = await route.fetch({ url: 'http://127.0.0.1:18129/api/v1/transaction/templates/capabilities.json' });
        assert.equal(response.status(), 404); await route.fulfill({ response });
    });
    const oldPosts = posts.length;
    await panel().getByRole('button', { name: '确认创建模板' }).click();
    await panel().getByRole('alert').filter({ hasText: '请升级服务' }).waitFor();
    await panel().getByRole('button', { name: '重试原请求' }).click();
    await panel().getByRole('alert').filter({ hasText: '请升级服务' }).waitFor(); assert.equal(posts.length, oldPosts);
    await page.unroute('**/templates/capabilities.json');
    await panel().getByRole('button', { name: '重试原请求' }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal(posts.length, oldPosts + 1); assert.equal((await list(1)).filter(row => row.name === 'Synthetic old server gate').length, 1);
    pass('real old-backend capability 404 preserves the queue through repeated retries; upgrade uses dedicated create only');

    const admin = await register('Synthetic template administrator');
    const invitation = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledger.id, inviteeName: admin.username, role: 3, expiresInSeconds: 86400 });
    await api('v1/ledger/invitation/accept.json', admin.token, { token: invitation.token });
    const adminMember = (await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, admin.token)).find(row => row.isCurrentUser);
    const changeRole = role => api('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledger.id, memberId: adminMember.id, role });
    await changeRole(2);
    const adminPage = await browser.newPage({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 } });
    adminPage.setDefaultTimeout(20000); adminPage.on('pageerror', e => errors.push(e.name));
    const adminPosts = [];
    adminPage.on('request', r => { if (r.url().endsWith('/templates/add_durable.json')) adminPosts.push(r.postDataJSON()); });
    await adminPage.goto(`${base}/desktop`, { waitUntil: 'networkidle' });
    await adminPage.getByLabel('用户名', { exact: true }).fill(admin.username); await adminPage.getByLabel('密码', { exact: true }).fill(password);
    await adminPage.getByRole('button', { name: '登录', exact: true }).click(); await adminPage.waitForURL(/#\/$/);
    await adminPage.getByRole('button', { name: '切换当前账本', exact: true }).click(); await adminPage.getByRole('option').filter({ hasText: ledger.name }).click();
    await adminPage.goto(`${base}/desktop#/template/list`, { waitUntil: 'networkidle' });
    await adminPage.getByRole('button', { name: '新增模板', exact: true }).first().click();
    const adminEditor = adminPage.getByRole('dialog'), adminPanel = adminEditor.locator('.template-creation-panel');
    await adminPanel.getByRole('status').filter({ hasText: '开始输入' }).waitFor();
    await adminEditor.getByLabel('模板名称', { exact: true }).fill('Synthetic revoked retry'); await adminEditor.getByLabel(/^支出金额/).fill('7.89');
    await adminEditor.getByRole('combobox', { name: '分类', exact: true }).locator('..').click();
    const adminMenu = adminPage.locator('.two-column-select-menu').filter({ visible: true });
    await adminMenu.getByText(root.name, { exact: true }).click(); await adminMenu.getByText(leaf.name, { exact: true }).click();
    await adminEditor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await adminPage.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); await response.body(); await route.abort('failed'); });
    await adminPanel.getByRole('button', { name: '确认创建模板' }).click(); await adminPanel.getByRole('button', { name: '重试原请求' }).waitFor();
    const adminId = (await list(1)).find(row => row.name === 'Synthetic revoked retry').id;
    await changeRole(4); await adminPage.unroute('**/templates/add_durable.json');
    await adminPanel.getByRole('button', { name: '重试原请求' }).click();
    await adminPanel.getByRole('alert').waitFor(); assert.equal(adminPosts.length, 1);
    await changeRole(2); await adminPanel.getByRole('button', { name: '重试原请求' }).click(); await adminEditor.waitFor({ state: 'hidden' });
    assert.equal(adminPosts.length, 2); assert.deepEqual(adminPosts[1], adminPosts[0]);
    assert.equal((await list(1)).find(row => row.name === 'Synthetic revoked retry').id, adminId);
    await adminPage.close();
    pass('real role revocation blocks an unknown-result POST; restored permission resumes the same original request and ID');

    const mobileContext = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 } });
    const mobile = await mobileContext.newPage(); mobile.setDefaultTimeout(20000); mobile.on('pageerror', e => errors.push(e.name));
    const desktop = page;
    const current = () => mobile.locator('.view-main > .page.page-current');
    const mobilePanel = () => current().locator('.template-creation-panel');
    const mobilePosts = [];
    mobile.on('request', r => { if (r.url().endsWith('/templates/add_durable.json')) mobilePosts.push(r.postDataJSON()); if (r.url().endsWith('/templates/add.json')) legacyCreateCalls++; });
    await mobile.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await current().locator('input[type="text"]').first().fill(owner.username);
    await current().locator('input[type="password"]').first().fill(password);
    await current().getByText('登录', { exact: true }).click();
    await current().getByText('当前账本：默认个人账本', { exact: true }).waitFor();
    page = mobile; // Subsequent diagnostic screenshots capture the current platform.
    async function navigateMobile(path) {
        await mobile.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
        await mobile.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
        await mobile.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
        await mobile.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
    }
    await navigateMobile('/ledger/manage');
    await current().locator('.list .item-link').filter({ hasText: ledger.name }).click();
    await current().getByText('切换到此账本', { exact: true }).click();
    await current().getByText(new RegExp(`正在使用：${ledger.name}`)).waitFor();
    const addMobile = `/template/add?templateType=1&ledgerId=${ledger.id}&accountId=${account.id}&categoryId=${leaf.id}&type=3`;
    await navigateMobile(addMobile);
    await mobilePanel().getByRole('status').filter({ hasText: '开始输入' }).waitFor();
    await current().locator('input[placeholder="模板名称"]').fill('Synthetic mobile calculator');
    await current().locator('.transaction-edit-amount').first().click();
    const pad = mobile.locator('.numpad-sheet.modal-in');
    await pad.getByText('1', { exact: true }).click(); await pad.getByText('+', { exact: true }).click(); await pad.getByText('2', { exact: true }).click();
    await mobilePanel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    assert.equal(mobilePosts.length, 0);
    await mobile.reload({ waitUntil: 'networkidle' }); await navigateMobile(addMobile);
    await mobilePanel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await current().locator('.transaction-edit-amount').first().click();
    await pad.waitFor(); assert.match(await pad.locator('.numpad-values').innerText(), /1\s*\+\s*2/);
    await pad.locator('.numpad-button-confirm').click(); // Calculate, then explicitly accept.
    await pad.locator('.numpad-button-confirm').click(); await pad.waitFor({ state: 'hidden' });
    await current().getByLabel('预览模板', { exact: true }).click();
    await mobilePanel().getByRole('button', { name: '确认创建模板' }).waitFor(); assert.equal(mobilePosts.length, 0);
    await mobile.screenshot({ path: `${output}/mobile-preview-375.png`, fullPage: true });
    assert(await current().evaluate(el => el.scrollWidth <= innerWidth + 1));
    await mobile.route('**/templates/add_durable.json', async route => { const response = await route.fetch(); await response.body(); await route.abort('failed'); });
    await mobilePanel().getByRole('button', { name: '确认创建模板' }).click();
    await mobilePanel().getByRole('button', { name: '重试原请求' }).waitFor();
    await mobile.unroute('**/templates/add_durable.json');
    await mobile.reload({ waitUntil: 'networkidle' }); await navigateMobile(addMobile);
    await mobilePanel().getByRole('button', { name: '重试原请求' }).click();
    await current().locator('.template-creation-panel').waitFor({ state: 'hidden' });
    assert.equal(mobilePosts.length, 2); assert.deepEqual(mobilePosts[0], mobilePosts[1]); assert.equal(mobilePosts[0].sourceAmount, 300);
    assert.equal((await list(1)).filter(row => row.name === 'Synthetic mobile calculator').length, 1);
    pass('375px production mobile editor restores unfinished calculator and retries committed write with exact original payload');
    const addSchedule = addMobile.replace('templateType=1', 'templateType=2');
    await navigateMobile(addSchedule);
    await mobilePanel().getByRole('status').filter({ hasText: '开始输入' }).waitFor();
    await current().locator('input[placeholder="模板名称"]').fill('Synthetic mobile schedule');
    await current().locator('.transaction-edit-amount').first().click();
    await pad.getByText('6', { exact: true }).click(); await pad.getByText('.', { exact: true }).click(); await pad.getByText('7', { exact: true }).click();
    await pad.locator('.numpad-button-confirm').click(); await pad.waitFor({ state: 'hidden' });
    await current().locator('.item-link').filter({ has: mobile.getByText('定时交易周期', { exact: true }) }).click();
    let sheet = mobile.locator('.sheet-modal.modal-in');
    await sheet.locator('.schedule-frequency-type-container').getByText('每天', { exact: true }).click();
    await sheet.locator('.toolbar .right .button').click(); await sheet.waitFor({ state: 'hidden' });
    await current().locator('.item-link').filter({ has: mobile.getByText('开始日期', { exact: true }) }).click();
    sheet = mobile.locator('.date-selection-sheet.modal-in');
    await sheet.locator('.dp--today').click(); await sheet.locator('.toolbar .right .button').click(); await sheet.waitFor({ state: 'hidden' });
    await mobilePanel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    const beforeSchedulePosts = mobilePosts.length;
    await mobile.reload({ waitUntil: 'networkidle' }); await navigateMobile(addSchedule);
    await mobilePanel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await current().getByLabel('预览模板', { exact: true }).click();
    await mobilePanel().getByText(/到期只生成待确认记录/).waitFor(); assert.equal(mobilePosts.length, beforeSchedulePosts);
    await mobilePanel().getByRole('button', { name: '确认创建模板' }).click();
    await current().locator('.template-creation-panel').waitFor({ state: 'hidden' });
    const mobileSchedule = (await list(2)).find(row => row.name === 'Synthetic mobile schedule');
    assert.equal(mobileSchedule.sourceAmount, 670); assert.equal(mobileSchedule.scheduledFrequencyType, 3);
    assert.equal(mobileSchedule.scheduledFrequency, '0'); assert.equal(mobileSchedule.utcOffset, 480); assert(mobileSchedule.scheduledStartDate);
    pass('production mobile schedule restores confirmed amount, daily rule, date and timezone across reload');
    await navigateMobile(addMobile);
    await mobilePanel().getByRole('status').filter({ hasText: '开始输入' }).waitFor();
    await current().locator('input[placeholder="模板名称"]').fill('Synthetic theme preview');
    await current().locator('.transaction-edit-amount').first().click();
    await pad.getByText('8', { exact: true }).click(); await pad.locator('.numpad-button-confirm').click(); await pad.waitFor({ state: 'hidden' });
    await mobilePanel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    for (const theme of ['light', 'dark']) {
        await mobile.evaluate(value => { const settings = JSON.parse(localStorage.getItem('ebk_app_settings') || '{}'); settings.theme = value; settings.fontSize = 6; localStorage.setItem('ebk_app_settings', JSON.stringify(settings)); }, theme);
        await mobile.reload({ waitUntil: 'networkidle' }); await navigateMobile(addMobile);
        await mobilePanel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
        await current().getByLabel('预览模板', { exact: true }).click();
        await mobilePanel().getByRole('button', { name: '确认创建模板' }).waitFor();
        assert(await mobile.evaluate(() => document.documentElement.classList.contains('font-size-xxxx-large')));
        assert.equal(await mobile.evaluate(() => document.documentElement.classList.contains('dark')), theme === 'dark');
        assert.equal(await mobilePanel().evaluate(el => getComputedStyle(el).fontSize), '21px');
        for (const viewport of [{ width: 375, height: 812 }, { width: 844, height: 390 }]) {
            await mobile.setViewportSize(viewport);
            const geometry = await mobilePanel().evaluate(el => ({ width: el.scrollWidth, viewport: innerWidth,
                buttons: [...el.querySelectorAll('button')].map(b => ({ width: b.getBoundingClientRect().width, height: b.getBoundingClientRect().height })) }));
            assert(geometry.width <= geometry.viewport); assert(geometry.buttons.every(b => b.width >= 44 && b.height >= 44));
            await mobilePanel().getByRole('button', { name: '确认创建模板' }).focus();
            await mobile.keyboard.press('Tab'); assert(await mobilePanel().getByRole('button', { name: '继续编辑', exact: true }).evaluate(el => el === document.activeElement));
            await mobile.screenshot({ path: `${output}/mobile-${theme}-max-font-${viewport.width}.png`, fullPage: true });
        }
    }
    assert.equal(mobilePosts.length, beforeSchedulePosts + 1);
    pass('mobile recovery preview uses real light/dark themes and maximum font at 375px/landscape with keyboard focus and 44px targets');

    await mobilePanel().getByRole('button', { name: '继续编辑', exact: true }).click();
    await mobile.evaluate(() => { const original = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) {
        if (key.includes('template-draft')) throw new Error('Synthetic local storage failure'); return original.call(this, key, value);
    }; });
    await current().locator('input[placeholder="模板名称"]').fill('Synthetic cannot save');
    await mobilePanel().getByRole('alert').filter({ hasText: 'Synthetic local storage failure' }).waitFor();
    assert.equal(await mobilePanel().getByRole('button', { name: '确认创建模板' }).count(), 0);
    assert.equal(mobilePosts.length, beforeSchedulePosts + 1);
    await mobile.reload({ waitUntil: 'networkidle' }); await navigateMobile(addMobile);
    await mobilePanel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await current().locator('input[placeholder="模板名称"]').inputValue(), 'Synthetic theme preview');
    pass('real browser storage failure is visible, prevents confirmation, and retains the last saved draft');
    await desktop.close();
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, 10000);
    assert.deepEqual(await api(`v1/transaction/templates/list.json?templateType=1&ledgerId=${other.id}`, owner.token), []);
    assert.deepEqual(errors, []);
    assert.equal(legacyCreateCalls, 0);
    pass('all synthetic template creations leave balance unchanged and ledger B empty; no legacy create fallback or runtime errors');
    await writeFile(`${output}/browser-summary.json`, JSON.stringify({ checks, passed: true, image: info.Image, browserErrors: errors, legacyCreateCalls }, null, 2));
} catch (error) {
    await page?.screenshot({ path: `${output}/browser-failure.png`, fullPage: true }).catch(() => {});
    // Playwright call logs can contain entered credentials. Store only a safe error heading.
    console.error(String(error.message).split('\n')[0]); process.exitCode = 1;
} finally { await browser?.close(); }
