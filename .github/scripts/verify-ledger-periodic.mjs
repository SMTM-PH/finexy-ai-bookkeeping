import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const container = 'finexy-periodic-e2e';
const base = 'http://127.0.0.1:18112';
const output = 'artifacts/ledger-periodic-20261004';
const expectedImage = process.env.FINEXY_PERIODIC_IMAGE || 'finexy-ledger-periodic-candidate:20261004-root8';
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert.equal(info.Name, `/${container}`);
assert.equal(info.Config.Image, expectedImage);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=234'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(port => port.HostPort === '18112' && port.HostIp === '127.0.0.1'));
const password = randomBytes(18).toString('hex');
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, token, body, status = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const data = await response.json();
    if (response.status !== status) await writeFile(`${output}/unexpected-http.json`, JSON.stringify({ path: path.split('?')[0], status: response.status, expected: status, errorCode: data.errorCode, errorMessage: data.errorMessage }, null, 2));
    assert.equal(response.status, status, path.split('?')[0]);
    if (status === 200) { assert(data.success, path.split('?')[0]); assert(Object.hasOwn(data, 'result')); }
    return data.result;
}
async function register() {
    const username = `periodic_${randomBytes(6).toString('hex')}`;
    return { username, ...(await api('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic periodic QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] })) };
}
function runCron() {
    // Existing public CLI, same disposable service database. It only enqueues snapshots.
    try { execFileSync('docker', ['exec', container, '/ezbookkeeping/ezbookkeeping', 'cron', 'run', '--name', 'CreateScheduledTransaction'], { stdio: 'pipe', timeout: 30000 }); }
    catch { throw new Error('Synthetic periodic dispatch failed'); }
}
async function templateAction(page, verb, trigger) {
    const [response] = await Promise.all([
        page.waitForResponse(response => response.url().includes(`/transaction/templates/${verb}.json`)),
        trigger(),
    ]);
    assert.equal(response.status(), 200);
    return response;
}
async function settledMenu(page, selector) {
    const activeSelector = `.v-menu.v-overlay--active ${selector}`;
    const menu = page.locator(`${activeSelector}:visible`);
    await menu.waitFor();
    await page.waitForFunction(selector => { const element = document.querySelector(selector); return element && getComputedStyle(element).pointerEvents !== 'none'; }, activeSelector);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    return menu;
}
let browser;
let activePage;
try {
    await mkdir(output, { recursive: true });
    for (let i = 0; i < 60; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* startup */ }
        if (i === 59) throw new Error('Disposable periodic service did not start');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register(); const admin = await register(); const member = await register(); const viewer = await register(); const outsider = await register();
    const a = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic periodic A' });
    const b = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic periodic B' });
    for (const [user, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: a.id, inviteeName: user.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', user.token, { token: invitation.token });
        if (role === 2) {
            const me = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, user.token)).find(row => row.isCurrentUser);
            await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: a.id, memberId: me.id, role: 2 });
        }
    }
    const root = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense root', type: 2, icon: '1', color: '000000' });
    const leaf = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic expense leaf', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const transferRoot = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic transfer root', type: 3, icon: '1', color: '000000' });
    const transferLeaf = await api('v1/transaction/categories/add.json', owner.token, { name: 'Synthetic transfer leaf', type: 3, parentId: transferRoot.id, icon: '1', color: '000000' });
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', balance: 100000, balanceTime: 946684800 };
    const usd = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: a.id, name: 'Synthetic periodic USD', currency: 'USD' });
    const cny = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: a.id, name: 'Synthetic periodic CNY', currency: 'CNY' });
    const personalAccount = await api('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Synthetic personal', currency: 'CNY' });
    const bAccount = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: b.id, name: 'Synthetic periodic B account', currency: 'CNY' });
    const tag = await api('v1/transaction/tags/add.json', owner.token, { name: 'Synthetic owner tag', color: '000000' });
    const utc = new Date();
    const utcMinute = utc.getUTCHours() * 60 + Math.floor(utc.getUTCMinutes() / 15) * 15;
    let utcOffset = (1440 - utcMinute) % 1440; if (utcOffset > 840) utcOffset -= 1440;
    const draft = { ledgerId: a.id, templateType: 2, name: 'Synthetic shared expense', type: 3, categoryId: leaf.id, sourceAccountId: usd.id, destinationAccountId: '0', sourceAmount: 250, destinationAmount: 0, utcOffset, hideAmount: false, tagIds: [tag.id], comment: 'Synthetic only', scheduledFrequencyType: 3, scheduledFrequency: '1', scheduledStartDate: '2020-01-01' };
    const create = (user, body = {}, status = 200) => api('v1/transaction/templates/add.json', user.token, { ...draft, clientSessionId: randomBytes(16).toString('hex'), ...body }, status);
    const list = (user, ledgerId = a.id, status = 200) => api(`v1/transaction/templates/list.json?templateType=2&ledgerId=${ledgerId}`, user.token, undefined, status);
    const queue = (user, status = 1, ledgerId = a.id, http = 200) => api(`v1/schedule/review/list.json?ledgerId=${ledgerId}&status=${status}&offset=0&limit=100`, user.token, undefined, http);
    const first = await create(admin);
    const quick = await create(admin, { templateType: 1, name: 'Synthetic shared quick template' });
    const sameNameQuick = await create(owner, { templateType: 1, name: quick.name, sourceAmount: 600 });
    for (const [user, editable] of [[owner, true], [admin, true], [member, false], [viewer, false]]) {
        const rows = await list(user); assert.equal(rows.length, 1); assert.equal(rows[0].id, first.id); assert.equal(rows[0].ledgerId, a.id); assert.equal(rows[0].editable, editable);
    }
    assert.deepEqual(await list(owner, '0'), []); assert.deepEqual(await list(owner, b.id), []); assert.deepEqual(await list(member, '0'), []);
    await list(outsider, a.id, 403);
    for (const user of [member, viewer]) {
        await create(user, {}, 403);
        await api('v1/transaction/templates/modify.json', user.token, { ...draft, id: first.id }, 403);
        for (const [action, body] of [['hide', { id: first.id, hidden: true }], ['move', { newDisplayOrders: [{ id: first.id, displayOrder: 2 }] }], ['delete', { id: first.id }]]) await api(`v1/transaction/templates/${action}.json`, user.token, { ledgerId: a.id, ...body }, 403);
    }
    pass('four roles read the same owner template; only owner/admin manage; personal A B and outsiders stay isolated');
    for (const [user, editable] of [[owner, true], [admin, true], [member, false], [viewer, false]]) {
        const rows = await api(`v1/transaction/templates/list.json?templateType=1&ledgerId=${a.id}`, user.token);
        assert.equal(rows.length, 2); assert(rows.every(item => item.ledgerId === a.id && item.editable === editable));
        assert.deepEqual(new Set(rows.map(item => item.id)), new Set([quick.id, sameNameQuick.id]));
    }
    assert.deepEqual(await api('v1/transaction/templates/list.json?templateType=1&ledgerId=0', owner.token), []);
    pass('ordinary templates retain distinct IDs with equal names and share the same ledger and role boundaries');
    await create(owner, { sourceAccountId: bAccount.id }, 400); await create(owner, { sourceAccountId: personalAccount.id }, 400);
    await api(`v1/transaction/templates/get.json?id=${first.id}&ledgerId=${b.id}`, owner.token, undefined, 400);
    const legacy = await create(owner, { ledgerId: '0', sourceAccountId: personalAccount.id, name: 'Synthetic default schedule' });
    const legacyBody = { ...draft, sourceAccountId: personalAccount.id, name: 'Synthetic legacy omitted ledger', clientSessionId: randomBytes(16).toString('hex') }; delete legacyBody.ledgerId;
    assert.equal((await api('v1/transaction/templates/add.json', owner.token, legacyBody)).ledgerId, '0');
    assert.equal((await list(owner, '0')).length, 2); assert.equal(legacy.ledgerId, '0');
    pass('cross-ledger accounts and wrong-scope get fail; omitted ledger remains default personal');
    await api('v1/transaction/templates/hide.json', admin.token, { ledgerId: a.id, id: first.id, hidden: true });
    const hidden = await create(owner, { name: 'Synthetic paused schedule', scheduledFrequencyType: 0, scheduledFrequency: '' });
    const transfer = await create(owner, { name: 'Synthetic dual currency', type: 4, categoryId: transferLeaf.id, sourceAmount: 700, destinationAccountId: cny.id, destinationAmount: 4900 });
    const refund = await create(owner, { name: 'Synthetic refund', sourceAmount: -100 });
    const balance = async account => (await api(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance;
    const before = [await balance(usd), await balance(cny), await balance(personalAccount)];
    runCron(); runCron();
    const pending = await queue(owner); assert.equal(pending.length, 3); assert(pending.some(item => item.templateId === first.id)); assert(!pending.some(item => item.templateId === hidden.id));
    for (const item of pending) { assert.equal(item.ledgerId, a.id); assert.equal(item.snapshot.ledgerId, a.id); }
    assert.deepEqual([await balance(usd), await balance(cny), await balance(personalAccount)], before);
    const defaultPending = await queue(owner, 1, '0'); assert.equal(defaultPending.length, 2);
    pass('public cron dispatches hidden active schedules once, skips paused schedules and changes no balances');
    const due = pending.find(item => item.templateId === first.id);
    const action = { ledgerId: a.id, templateId: first.id, scheduledUnixTime: due.scheduledUnixTime };
    for (const verb of ['confirm', 'dismiss', 'restore']) await api(`v1/schedule/review/${verb}.json`, viewer.token, action, 403);
    await queue(outsider, 1, a.id, 403);
    await api('v1/schedule/review/confirm.json', owner.token, { ...action, ledgerId: b.id }, 400);
    assert.equal((await queue(owner)).length, 3);
    pass('viewer and outsider cannot review; a wrong ledger never confirms another ledger occurrence');
    await api('v1/schedule/review/dismiss.json', member.token, action); assert.equal((await queue(owner, 3)).length, 1); assert.equal(await balance(usd), before[0]);
    await api('v1/schedule/review/restore.json', admin.token, action); assert.equal((await queue(owner, 3)).length, 0);
    const confirmations = await Promise.all([owner, admin, member].map(user => api('v1/schedule/review/confirm.json', user.token, action)));
    assert(confirmations.every(item => item.transactionId === confirmations[0].transactionId && item.ledgerId === a.id));
    assert.equal(await balance(usd), before[0] - 250);
    const posted = await api(`v1/transactions/get.json?id=${confirmations[0].transactionId}&ledgerId=${a.id}&trim_tag=true`, owner.token);
    assert.equal(posted.ledgerId, a.id); assert.deepEqual(posted.tagIds, [tag.id]);
    assert.equal(typeof posted.recorderUid, 'string'); assert.notEqual(posted.recorderUid, '0'); assert.equal(posted.recorderUid, posted.payerUid);
    assert.equal((await queue(owner)).length, 2);
    pass('dismiss and restore do not post; concurrent confirmations preserve tags and create one attributed financial write');
    for (const item of [transfer, refund]) {
        const row = (await queue(member)).find(value => value.templateId === item.id);
        await api('v1/schedule/review/confirm.json', member.token, { ledgerId: a.id, templateId: item.id, scheduledUnixTime: row.scheduledUnixTime });
    }
    assert.equal(await balance(usd), before[0] - 250 - 700 + 100); assert.equal(await balance(cny), before[1] + 4900); assert.equal(await balance(personalAccount), before[2]);
    pass('dual currency transfer and signed refund preserve both exact amounts and isolate default balances');
    const stress = [];
    for (let i = 0; i < 10; i++) stress.push(await create(owner, { name: `Synthetic concurrent round ${i}`, sourceAmount: 1 }));
    runCron();
    const stressQueue = await queue(owner);
    assert.equal(stressQueue.length, stress.length);
    const stressBalance = await balance(usd);
    for (let i = 0; i < stress.length; i++) {
        const capturedStress = stressQueue.find(item => item.templateId === stress[i].id);
        assert(capturedStress);
        const request = { ledgerId: a.id, templateId: stress[i].id, scheduledUnixTime: capturedStress.scheduledUnixTime };
        const results = await Promise.all(Array.from({ length: 12 }, (_, slot) => api('v1/schedule/review/confirm.json', [owner, admin, member][slot % 3].token, request)));
        assert(results.every(item => item.transactionId === results[0].transactionId && item.ledgerId === a.id));
        assert.equal(await balance(usd), stressBalance - i - 1);
        await api('v1/transaction/templates/delete.json', owner.token, { ledgerId: a.id, id: stress[i].id });
    }
    assert.equal((await queue(owner)).length, 0);
    pass('ten HTTP rounds with twelve simultaneous role-mixed confirmations all return one ID and change balance once');
    const disposable = await create(owner, { name: 'Synthetic deleted template snapshot' }); runCron();
    const captured = (await queue(owner)).find(item => item.templateId === disposable.id); assert(captured);
    await api('v1/schedule/review/dismiss.json', member.token, { ledgerId: a.id, templateId: disposable.id, scheduledUnixTime: captured.scheduledUnixTime });
    await api('v1/transaction/templates/delete.json', admin.token, { ledgerId: a.id, id: disposable.id });
    const deletion = await api(`v1/ledger/delete/preview.json?id=${a.id}`, owner.token);
    assert(deletion.transactionTemplateCount > 0); assert(deletion.scheduledOccurrenceCount > 0); assert(deletion.blockingReasons.includes('transactionTemplates')); assert(deletion.blockingReasons.includes('scheduledOccurrences'));
    assert((await queue(owner, 3)).some(item => item.templateId === disposable.id));
    pass('deleted templates retain dismissed snapshots and appear in protected ledger deletion impact');
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const errors = [];
    async function pageFor(user) {
        const page = await browser.newPage({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
        page.setDefaultTimeout(20000); page.on('pageerror', error => errors.push(error.name));
        await page.goto(`${base}/desktop`, { waitUntil: 'networkidle' });
        await page.getByLabel('用户名', { exact: true }).fill(user.username); await page.getByLabel('密码', { exact: true }).fill(password);
        await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(/#\/$/);
        await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: a.name }).click();
        await page.goto(`${base}/desktop#/schedule/list`, { waitUntil: 'networkidle' });
        try { await page.getByText('账本周期计划', { exact: true }).waitFor(); }
        catch (error) {
            await page.screenshot({ path: `${output}/periodic-ui-diagnostic.png`, fullPage: true });
            await writeFile(`${output}/periodic-ui-diagnostic.json`, JSON.stringify({ pageErrors: errors, url: page.url(), messages: await page.locator('.page-data-error, .toast').allTextContents(), headings: await page.locator('h1, h2, h3').allTextContents() }, null, 2));
            throw error;
        }
        assert.equal(await page.locator('.page-data-error').count(), 0);
        return page;
    }
    const page = await pageFor(owner);
    activePage = page;
    const calendarDays = await page.locator('.calendar .days button').count();
    assert.equal(await page.locator('.calendar .days button.marked').count(), calendarDays);
    assert.equal((await page.locator('.calendar-note span').textContent()).replace(/\s+/g, ' ').trim(), `${calendarDays} 天有计划`);
    assert.equal(await page.locator('.calendar-note b').textContent(), `本月 ${calendarDays * 3} 次到期`);
    const earliestDue = Math.min(...(await list(owner)).filter(item => item.nextScheduledTime).map(item => item.nextScheduledTime));
    const expectedDueDate = new Date(earliestDue * 1000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
    assert.equal(await page.locator('.metrics article').filter({ hasText: '下次到期' }).locator('strong').textContent(), expectedDueDate);
    pass('calendar includes hidden active plans; due dates, day count and occurrence count use distinct accurate units');
    await page.screenshot({ path: `${output}/desktop-owner-light.png`, fullPage: true });
    const refresh = page.getByRole('button', { name: '刷新', exact: true }); await refresh.click();
    await page.getByText('账本周期计划', { exact: true }).waitFor(); assert.equal(await page.locator('.page-data-error').count(), 0);
    await page.getByRole('button', { name: '恢复：Synthetic deleted template snapshot', exact: true }).click();
    await page.getByRole('button', { name: '确认入账：Synthetic deleted template snapshot', exact: true }).waitFor();
    await page.getByRole('button', { name: '确认入账：Synthetic deleted template snapshot', exact: true }).click();
    await page.waitForFunction(() => !Array.from(document.querySelectorAll('button')).some(button => button.getAttribute('aria-label') === '确认入账：Synthetic deleted template snapshot'));
    assert.equal((await queue(owner, 3)).length, 0); assert.equal((await queue(owner)).length, 0);
    pass('real desktop cold start reads selected ledger; refresh, restore and confirmation update actual service queue');
    const balanceBeforeEditor = await balance(usd);
    await page.getByRole('button', { name: '编辑计划：Synthetic shared expense', exact: true }).click();
    let editor = page.getByRole('dialog');
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic edited shared expense');
    await editor.getByLabel(/^支出金额/).fill('4.00');
    await templateAction(page, 'modify', () => editor.getByRole('button', { name: '保存', exact: true }).click());
    await page.getByRole('button', { name: '编辑计划：Synthetic edited shared expense', exact: true }).waitFor();
    let edited = await api(`v1/transaction/templates/get.json?id=${first.id}&ledgerId=${a.id}`, owner.token);
    assert.equal(edited.ledgerId, a.id); assert.equal(edited.sourceAmount, 400); assert.deepEqual(edited.tagIds, [tag.id]); assert.equal(edited.hidden, true);
    const toggle = page.getByRole('checkbox', { name: '启用Synthetic edited shared expense', exact: true });
    const toggleLabel = page.locator('label.switch').filter({ has: toggle });
    const toggleSize = await toggleLabel.boundingBox(); assert(toggleSize.width >= 44 && toggleSize.height >= 44);
    await page.waitForFunction(() => { const control = document.querySelector('[aria-label="启用Synthetic edited shared expense"]'); return control && !control.disabled; });
    await templateAction(page, 'modify', () => toggleLabel.click());
    await page.waitForFunction(() => { const control = document.querySelector('[aria-label="启用Synthetic edited shared expense"]'); return control && !control.checked; });
    edited = await api(`v1/transaction/templates/get.json?id=${first.id}&ledgerId=${a.id}`, owner.token);
    assert.equal(edited.scheduledFrequencyType, 0); assert.equal(edited.hidden, true);
    await page.waitForFunction(() => { const control = document.querySelector('[aria-label="启用Synthetic edited shared expense"]'); return control && !control.disabled; });
    await templateAction(page, 'modify', () => toggleLabel.click());
    await page.waitForFunction(() => { const control = document.querySelector('[aria-label="启用Synthetic edited shared expense"]'); return control && control.checked; });
    edited = await api(`v1/transaction/templates/get.json?id=${first.id}&ledgerId=${a.id}`, owner.token);
    assert.equal(edited.scheduledFrequencyType, draft.scheduledFrequencyType); assert.equal(edited.scheduledFrequency, draft.scheduledFrequency); assert.equal(edited.hidden, true);
    assert.equal(await balance(usd), balanceBeforeEditor);
    pass('real owner editor persists ledger and tags; pause/resume changes execution frequency without hiding or posting');
    await page.getByRole('button', { name: '新建计划', exact: true }).click();
    editor = page.getByRole('dialog');
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic UI created schedule');
    await editor.getByLabel(/^支出金额/).fill('3.21');
    await editor.getByRole('combobox', { name: '分类', exact: true }).locator('..').click();
    let menu = await settledMenu(page, '.two-column-select-menu');
    await menu.getByText(root.name, { exact: true }).click(); await menu.getByText(leaf.name, { exact: true }).click();
    await editor.getByRole('combobox', { name: '分类', exact: true }).locator('..').click();
    menu = await settledMenu(page, '.two-column-select-menu');
    const menuBox = await menu.boundingBox(); assert(menuBox.x >= 0 && menuBox.y >= 0 && menuBox.x + menuBox.width <= 1440 && menuBox.y + menuBox.height <= 1000);
    await menu.getByText(root.name, { exact: true }).click(); await menu.getByText(leaf.name, { exact: true }).click();
    await editor.getByRole('combobox', { name: '来源账户', exact: true }).locator('..').click();
    menu = await settledMenu(page, '.two-column-select-menu');
    const targetAccount = menu.getByText(usd.name, { exact: true });
    if (!await targetAccount.isVisible()) {
        const groups = menu.locator('.primary-list-container .v-list-item');
        for (let i = 0; i < await groups.count(); i++) { await groups.nth(i).click(); if (await targetAccount.isVisible()) break; }
    }
    await targetAccount.click();
    await editor.getByRole('combobox', { name: '开始日期', exact: true }).locator('..').click();
    await (await settledMenu(page, '.date-select-menu')).locator('.dp--today').click();
    await editor.getByLabel('模板名称', { exact: true }).click();
    await editor.getByRole('combobox', { name: '定时交易周期', exact: true }).locator('..').click();
    await (await settledMenu(page, '.schedule-frequency-select-menu')).locator('.schedule-frequency-type-container').getByText('每天', { exact: true }).click();
    await editor.getByLabel('模板名称', { exact: true }).click();
    const added = await templateAction(page, 'add', () => editor.getByRole('button', { name: '添加', exact: true }).click());
    const uiCreated = (await added.json()).result;
    assert.equal(uiCreated.ledgerId, a.id); assert.equal(uiCreated.sourceAccountId, usd.id); assert.equal(uiCreated.categoryId, leaf.id); assert.equal(uiCreated.sourceAmount, 321); assert.equal(uiCreated.scheduledFrequencyType, 3); assert.equal(uiCreated.scheduledFrequency, '0');
    await page.getByRole('button', { name: '编辑计划：Synthetic UI created schedule', exact: true }).waitFor();
    assert.equal(await balance(usd), balanceBeforeEditor); assert.deepEqual(await list(owner, b.id), []);
    pass('real new-plan form writes the explicitly selected ledger/account/category and exact amount without posting');
    await page.goto(`${base}/desktop#/template/list`, { waitUntil: 'networkidle' });
    await page.locator(`.template-grid article[data-template-id="${quick.id}"]`).waitFor();
    assert.equal(await page.locator('.template-grid article').count(), 2);
    await page.locator(`.template-grid article[data-template-id="${quick.id}"]`).getByRole('button', { name: '编辑', exact: true }).click();
    editor = page.getByRole('dialog');
    await editor.getByLabel('模板名称', { exact: true }).fill('Synthetic edited quick template');
    await templateAction(page, 'modify', () => editor.getByRole('button', { name: '保存', exact: true }).click());
    assert.equal((await api(`v1/transaction/templates/get.json?id=${quick.id}&ledgerId=${a.id}`, owner.token)).name, 'Synthetic edited quick template');
    assert.equal((await api(`v1/transaction/templates/get.json?id=${sameNameQuick.id}&ledgerId=${a.id}`, owner.token)).name, quick.name);
    assert.equal(await balance(usd), balanceBeforeEditor);
    pass('real ordinary-template page edits the selected string ID while preserving its equal-name neighbor and balance');
    const viewerPage = await pageFor(viewer);
    assert.equal(await viewerPage.getByRole('button', { name: /^编辑计划：/ }).count(), 0);
    assert.equal(await viewerPage.getByRole('button', { name: /^确认入账：|^忽略：|^恢复：/ }).count(), 0);
    assert(await viewerPage.getByRole('button', { name: '新建计划', exact: true }).isDisabled());
    for (const viewport of [{ width: 844, height: 390 }, { width: 375, height: 667 }]) {
        await viewerPage.setViewportSize(viewport);
        await viewerPage.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        assert(await viewerPage.locator('.workspace').evaluate(element => element.scrollWidth <= element.clientWidth + 1), `Periodic workspace overflows at ${viewport.width}`);
        const boxes = await viewerPage.locator('.topbar, .scope-notice, .metrics, .program, .calendar, .schedule-row, .monthly-budget-panel').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().toJSON()));
        assert(boxes.every(box => box.left >= 0 && box.right <= viewport.width + 1), `Periodic panel clips at ${viewport.width}`);
        await viewerPage.screenshot({ path: `${output}/desktop-viewer-${viewport.width}.png` });
    }
    pass('viewer plan panels and navigation remain inside 844px landscape and 375px narrow viewports');
    assert.deepEqual(errors, []);
    pass('real viewer page has no management or review actions; owner and viewer production startup has no browser exceptions');
    await writeFile(`${output}/periodic-e2e.json`, JSON.stringify({ checks, browserErrors: errors, containerId: info.Id, imageId: info.Image, target: base, syntheticOnly: true }, null, 2));
} catch (error) {
    if (activePage) {
        await activePage.screenshot({ path: `${output}/periodic-action-diagnostic.png`, fullPage: true });
        await writeFile(`${output}/periodic-menu-diagnostic.json`, JSON.stringify(await activePage.evaluate(() => Array.from(document.querySelectorAll('.v-menu.v-overlay--active')).map(element => ({ rect: element.getBoundingClientRect().toJSON(), style: element.getAttribute('style'), content: Array.from(element.querySelectorAll('.v-overlay__content')).map(content => ({ rect: content.getBoundingClientRect().toJSON(), style: content.getAttribute('style'), ancestors: [content.parentElement, content.parentElement.parentElement].map(parent => ({ rect: parent.getBoundingClientRect().toJSON(), transform: getComputedStyle(parent).transform, position: getComputedStyle(parent).position })) })) }))), null, 2));
        await writeFile(`${output}/periodic-action-diagnostic.json`, JSON.stringify({ url: activePage.url(), messages: await activePage.locator('.page-data-error, .toast, .v-snackbar').allTextContents(), dialogText: await activePage.getByRole('dialog').allTextContents() }, null, 2));
    }
    throw error;
} finally { if (browser) await browser.close(); }
