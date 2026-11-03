import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// No mounted user database and no saved tokens or browser storage artifacts.
const base = 'http://127.0.0.1:18110';
const info = JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert.equal(info.NetworkSettings.Ports['8080/tcp'][0].HostPort, '18110');
const output = 'artifacts/mobile-ledger-write-20261004';
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
let browser, page, releasePendingRoute = () => {};
function responseSignal(tab, predicate) {
    const promise = tab.waitForResponse(predicate);
    promise.catch(() => {}); // A UI failure can occur before the response wait finishes.
    return promise;
}
try {
    await mkdir(output, { recursive: true });
    for (let attempt = 0; attempt < 40; attempt++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* isolated service starting */ }
        if (attempt === 39) throw new Error('Isolated service did not start');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register('移动写入隔离验收');
    const admin = await register('管理员验收');
    const member = await register('普通成员验收');
    const viewer = await register('只读验收');
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 };
    const personal = await api('v1/accounts/add.json', owner.token, { ...accountDraft, name: 'Synthetic default account' });
    const ledger = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic shared write book' });
    const other = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic other book' });
    const account = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: ledger.id, name: 'Synthetic source' });
    const destination = await api('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: ledger.id, currency: 'USD', name: 'Synthetic destination' });
    const categories = {};
    for (const type of [1, 2, 3]) {
        const root = await api('v1/transaction/categories/add.json', owner.token, { name: `Synthetic root ${type}`, type, icon: '1', color: '000000' });
        categories[type] = await api('v1/transaction/categories/add.json', owner.token, { name: `Synthetic leaf ${type}`, type, parentId: root.id, icon: '1', color: '000000' });
    }
    for (const [user, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invite = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledger.id, inviteeName: user.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', user.token, { token: invite.token });
        if (role === 2) {
            const me = (await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, user.token)).find(row => row.isCurrentUser);
            await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledger.id, memberId: me.id, role: 2 });
        }
    }
    const members = await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, owner.token);
    const memberRow = members.find(row => row.role === 3);
    const defaultBefore = await api(`v1/accounts/get.json?id=${personal.id}`, owner.token);
    const draft = (comment, overrides = {}) => ({ ledgerId: ledger.id, type: 3, time: Math.floor(Date.now() / 1000), utcOffset: 480,
        categoryId: categories[2].id, sourceAccountId: account.id, destinationAccountId: '0', sourceAmount: 123, destinationAmount: 0,
        hideAmount: false, tagIds: [], pictureIds: [], comment, clientSessionId: randomBytes(16).toString('hex'), ...overrides });
    const get = (user, id) => api(`v1/transactions/get.json?id=${id}&ledgerId=${ledger.id}&with_pictures=true`, user.token);
    const ownRows = new Map();
    for (const user of [owner, admin, member]) ownRows.set(user.username, await api('v1/transactions/add.json', user.token, draft(`Synthetic ${user.username}`)));
    for (const user of [owner, admin, member, viewer]) {
        for (const author of [owner, admin, member]) {
            const row = await get(user, ownRows.get(author.username).id);
            assert.equal(row.editable, user === owner || (user !== viewer && user === author), 'live recorder editability');
        }
    }
    for (const user of [admin, member, viewer]) {
        const row = ownRows.get(owner.username);
        await api('v1/transactions/modify.json', user.token, { ...draft('Forbidden edit'), id: row.id }, user === viewer ? 403 : 400);
        await api('v1/transactions/delete.json', user.token, { ledgerId: ledger.id, id: row.id }, user === viewer ? 403 : 400);
    }
    await api('v1/transactions/add.json', viewer.token, draft('Forbidden create'), 403);
    pass('four roles enforce recorder ownership for get, create, modify and delete');

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
    const addPath = (comment, overrides = {}) => '/transaction/add?' + Object.entries({ ledgerId: ledger.id, type: '3', categoryId: categories[2].id,
        accountId: account.id, destinationAccountId: '0', amount: '123', comment, noTransactionDraft: 'true', ...overrides })
        .map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&');
    async function openAdd(tab, comment, overrides = {}) {
        await navigate(tab, addPath(comment, overrides));
        await current(tab).locator('textarea').first().waitFor();
        await tab.waitForFunction(() => !!document.querySelector('.page-current textarea') && !document.querySelector('.page-current .skeleton-text'));
    }
    const submit = async tab => {
        await tab.bringToFront();
        await tab.waitForFunction(() => !document.querySelector('.view-main.router-transition'));
        await tab.getByLabel('保存流水', { exact: true }).last().click();
    };
    async function write(tab, comment, overrides = {}) {
        await openAdd(tab, comment, overrides);
        const saved = responseSignal(tab, res => res.url().includes('/transactions/add.json') && res.request().method() === 'POST');
        await submit(tab); const res = await saved; assert.equal(res.status(), 200, 'mobile create');
        const row = (await res.json()).result;
        assert.equal(row.ledgerId, ledger.id);
        assert.equal(res.request().postDataJSON().sourceAmount, Number(overrides.amount ?? 123), 'confirmed source amount');
        await tab.waitForFunction(id => !Object.keys(localStorage).some(key => key.endsWith(`pending:${id}`)), ledger.id);
        return row;
    }
    page = await mobile(owner); await switchBook(page, ledger.name);
    await navigate(page, '/transaction/list');
    await current(page).getByText(ownRows.get(owner.username).comment, { exact: true }).waitFor();
    const expense = await write(page, 'Synthetic mobile expense');
    await write(page, 'Synthetic mobile income', { type: '2', categoryId: categories[1].id, amount: '321' });
    await write(page, 'Synthetic mobile cross currency transfer', { type: '4', categoryId: categories[3].id, destinationAccountId: destination.id, amount: '200', destinationAmount: '30' });
    await write(page, 'Synthetic mobile external out', { type: '4', categoryId: categories[3].id, destinationAccountId: '0', amount: '10' });
    await write(page, 'Synthetic mobile external in', { type: '4', categoryId: categories[3].id, accountId: '0', destinationAccountId: destination.id, amount: '0', destinationAmount: '20' });
    pass('production mobile creates expense, income and internal/external transfers in the chosen ledger');
    await navigate(page, `/transaction/edit?ledgerId=${ledger.id}&id=${expense.id}&type=3`);
    await page.waitForFunction(() => document.querySelector('.page-current textarea')?.value === 'Synthetic mobile expense');
    await current(page).locator('textarea').first().fill('Synthetic mobile expense edited');
    const edited = responseSignal(page, res => res.url().includes('/transactions/modify.json'));
    await submit(page); assert.equal((await edited).status(), 200);
    assert.equal((await get(owner, expense.id)).comment, 'Synthetic mobile expense edited');
    await navigate(page, `/transaction/add?ledgerId=${ledger.id}&id=${expense.id}&type=3&withTime=true`);
    await page.waitForFunction(() => document.querySelector('.page-current textarea')?.value === 'Synthetic mobile expense edited');
    const copied = responseSignal(page, res => res.url().includes('/transactions/add.json'));
    await submit(page); const copy = (await (await copied).json()).result;
    assert.notEqual(copy.id, expense.id); assert.equal(copy.time, expense.time);
    pass('mobile edit and explicit duplicate preserve the ledger and create separate IDs');

    await navigate(page, '/transaction/list');
    // Use the production swipeout controller to reveal its destructive control.
    const copyElement = current(page).locator(`li.swipeout[id*="${copy.id}"]`);
    await copyElement.waitFor();
    await copyElement.evaluate(el => document.querySelector('.view-main').f7View.app.swipeout.open(el, 'right'));
    await copyElement.locator('.swipeout-actions-right a.color-red').click();
    const deleted = responseSignal(page, res => res.url().includes('/transactions/delete.json'));
    await page.locator('.actions-modal.modal-in').getByText('删除', { exact: true }).click();
    assert.equal((await deleted).status(), 200);
    await api(`v1/transactions/get.json?id=${copy.id}&ledgerId=${ledger.id}`, owner.token, undefined, 400);
    pass('mobile swipeout deletion confirms the actual row and ledger');

    const tag = await api('v1/transaction/tags/add.json', owner.token, { name: 'Synthetic owner tag', icon: '1', color: '000000' });
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXfoAAAAASUVORK5CYII=', 'base64');
    await page.reload({ waitUntil: 'networkidle' });
    await openAdd(page, 'Synthetic owner resources', { tagIds: tag.id });
    const uploaded = responseSignal(page, res => res.url().includes('/transaction/pictures/upload.json'));
    await current(page).locator('input[type="file"]').first().setInputFiles({ name: 'synthetic.png', mimeType: 'image/png', buffer: png });
    assert.equal((await uploaded).status(), 200);
    await page.waitForFunction(() => !document.querySelector('[aria-label="保存流水"]')?.closest('.disabled'));
    const attachedSaved = responseSignal(page, res => res.url().includes('/transactions/add.json'));
    await submit(page); const attached = (await (await attachedSaved).json()).result;
    assert.deepEqual(attached.tagIds, [tag.id]); assert.equal(attached.pictures.length, 1);
    const form = new FormData(); form.set('picture', new Blob([png], { type: 'image/png' }), 'synthetic.png');
    const ownerUpload = await fetch(`${base}/api/v1/transaction/pictures/upload.json`, { method: 'POST', headers: { Authorization: `Bearer ${owner.token}`, 'X-Timezone-Offset': '480' }, body: form });
    assert.equal(ownerUpload.status, 200); const picture = (await ownerUpload.json()).result;
    const memberOriginal = ownRows.get(member.username);
    await api('v1/transactions/modify.json', owner.token, { ...draft(memberOriginal.comment), id: memberOriginal.id,
        time: memberOriginal.time, tagIds: [tag.id], pictureIds: [picture.pictureId] });
    pass('owner saves tags and a real uploaded attachment in the selected ledger');

    const memberTab = await mobile(member); await switchBook(memberTab, ledger.name);
    await navigate(memberTab, `/transaction/edit?ledgerId=${ledger.id}&id=${ownRows.get(owner.username).id}&type=3`);
    await current(memberTab).locator('textarea').first().waitFor();
    assert.equal(await memberTab.getByLabel('保存流水', { exact: true }).filter({ visible: true }).count(), 0);
    const mine = ownRows.get(member.username);
    await navigate(memberTab, `/transaction/edit?ledgerId=${ledger.id}&id=${mine.id}&type=3`);
    await memberTab.waitForFunction(value => document.querySelector('.page-current textarea')?.value === value, mine.comment);
    await current(memberTab).locator('textarea').first().fill('Synthetic member edit');
    const memberEdited = responseSignal(memberTab, res => res.url().includes('/transactions/modify.json'));
    await submit(memberTab); assert.equal((await memberEdited).status(), 200);
    const preserved = await get(owner, mine.id);
    assert.deepEqual(preserved.tagIds, [tag.id]); assert.deepEqual(preserved.pictures.map(row => row.pictureId), [picture.pictureId]);
    await api('v1/transactions/modify.json', member.token, { ...draft('Forbidden resource removal'), id: mine.id, time: mine.time }, 400);
    await navigate(memberTab, `/transaction/add?ledgerId=${ledger.id}&id=${mine.id}&type=3`);
    await memberTab.locator('.dialog.modal-in').getByText('取消', { exact: true }).click();
    await navigate(memberTab, `/transaction/add?ledgerId=${ledger.id}&id=${mine.id}&type=3&copyAgain=true`);
    await memberTab.locator('.dialog.modal-in').getByText('确定', { exact: true }).click();
    const memberCopied = responseSignal(memberTab, res => res.url().includes('/transactions/add.json'));
    await submit(memberTab); const basicCopy = (await (await memberCopied).json()).result;
    assert.deepEqual(basicCopy.tagIds, []); assert(!basicCopy.pictures?.length);
    pass('nonowner edits preserve owner associations and duplicate requires visible confirmation before dropping them');
    const viewerTab = await mobile(viewer); await switchBook(viewerTab, ledger.name);
    await openAdd(viewerTab, 'Synthetic viewer denied');
    await current(viewerTab).getByText('当前成员仅可查看，不能保存流水。', { exact: true }).waitFor();
    assert.equal(await viewerTab.getByLabel('保存流水', { exact: true }).filter({ visible: true }).count(), 0);
    await openAdd(memberTab, 'Synthetic revoked while open');
    await api('v1/ledger/member/change_role.json', owner.token, { ledgerId: ledger.id, memberId: memberRow.id, role: 4 });
    let revokedWrites = 0; memberTab.on('request', req => { if (req.url().includes('/transactions/add.json')) revokedWrites++; });
    await submit(memberTab);
    await current(memberTab).getByRole('alert').filter({ hasText: '失去记账权限' }).waitFor();
    assert.equal(revokedWrites, 0);
    pass('member UI edits only own rows, viewer has no save, live downgrade stops an open form');

    // Response is lost after the server commits. The immutable journal must survive reload.
    await navigate(page, '/transaction/list');
    const balanceBefore = (await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance;
    let original;
    await page.route('**/api/v1/transactions/add.json', async route => {
        original = route.request().postDataJSON();
        const res = await route.fetch(); assert.equal(res.status(), 200); await route.abort('failed');
    });
    await openAdd(page, 'Synthetic lost response', { amount: '77' });
    await submit(page);
    await current(page).getByText('重试原请求', { exact: true }).waitFor();
    await page.locator('.toast.modal-in').first().waitFor();
    await page.locator('.toast').first().waitFor({ state: 'hidden' });
    await page.screenshot({ path: `${output}/pending-light.png` });
    await page.unroute('**/api/v1/transactions/add.json');
    await page.reload({ waitUntil: 'networkidle' });
    await navigate(page, `/transaction/add?ledgerId=${ledger.id}&type=3`);
    await current(page).getByText('重试原请求', { exact: true }).waitFor();
    assert.match(await current(page).innerText(), /Synthetic shared write book/);
    assert(await current(page).locator('[inert] textarea').first().isVisible());
    const retried = responseSignal(page, res => res.url().includes('/transactions/add.json'));
    await current(page).getByText('重试原请求', { exact: true }).click();
    const retryResponse = await retried; assert.equal(retryResponse.status(), 200);
    assert.deepEqual(retryResponse.request().postDataJSON(), original);
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, balanceBefore - 77);
    await page.waitForFunction(id => !Object.keys(localStorage).some(key => key.endsWith(`pending:${id}`)), ledger.id);
    pass('lost committed response restores the chosen ledger and retries the same snapshot/key after reload exactly once');

    await navigate(page, '/transaction/list');
    const concurrentBalanceBefore = (await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance;
    let firstResultId;
    let release, received, finished; const held = new Promise(resolve => { received = resolve; }); const released = new Promise(resolve => { release = resolve; });
    const routeFinished = new Promise(resolve => { finished = resolve; });
    let routeFailure = false;
    releasePendingRoute = () => release();
    const concurrentBodies = [];
    await page.route('**/api/v1/transactions/add.json', async route => {
        try {
            concurrentBodies.push(route.request().postDataJSON()); const res = await route.fetch(); assert.equal(res.status(), 200); firstResultId = (await res.json()).result.id; received(); await released; await route.abort('failed');
        } catch {
            routeFailure = true; received();
        } finally { finished(); }
    });
    await openAdd(page, 'Synthetic parallel tabs', { amount: '11' });
    const second = await page.context().newPage(); second.setDefaultTimeout(15000); second.on('pageerror', error => errors.push(error.name));
    await second.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await openAdd(second, 'Synthetic competing tab input', { amount: '99' });
    // Both tabs prepare together; serialize HTTP preflight because SQLite can
    // transiently reject simultaneous readers during a write transaction.
    await second.route('**/api/v1/transactions/add.json', async route => { await held; await route.continue(); });
    const secondSaved = responseSignal(second, res => res.url().includes('/transactions/add.json'));
    await Promise.all([submit(page), submit(second)]); await held;
    const secondResult = await secondSaved;
    assert.equal(secondResult.status(), 200); assert.deepEqual(secondResult.request().postDataJSON(), concurrentBodies[0]);
    assert.equal((await secondResult.json()).result.id, firstResultId);
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, concurrentBalanceBefore - concurrentBodies[0].sourceAmount);
    release(); await routeFinished; assert.equal(routeFailure, false); await page.unroute('**/api/v1/transactions/add.json');
    await current(page).getByText('重试原请求', { exact: true }).waitFor();
    const lateRetry = responseSignal(page, res => res.url().includes('/transactions/add.json'));
    await current(page).getByText('重试原请求', { exact: true }).click();
    const lateResponse = await lateRetry;
    assert.equal(lateResponse.status(), 200);
    assert.deepEqual(lateResponse.request().postDataJSON(), concurrentBodies[0]);
    assert.equal((await lateResponse.json()).result.id, firstResultId);
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, concurrentBalanceBefore - concurrentBodies[0].sourceAmount);
    await page.waitForFunction(id => !Object.keys(localStorage).some(key => key.endsWith(`pending:${id}`)), ledger.id);
    await second.close();
    pass('parallel production tabs reuse the persisted request and cannot create a second financial record');

    await navigate(page, '/transaction/list');
    let refreshWriteCount = 0; const countWrites = req => { if (req.url().includes('/transactions/add.json')) refreshWriteCount++; };
    page.on('request', countWrites);
    await page.route('**/api/v1/transactions/list.json*', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false }) }));
    await write(page, 'Synthetic committed before failed refresh', { amount: '7' });
    await current(page).getByRole('alert').filter({ hasText: '流水加载失败' }).waitFor();
    assert.equal(refreshWriteCount, 1);
    await page.unroute('**/api/v1/transactions/list.json*');
    await current(page).getByText('重试', { exact: true }).click();
    await current(page).getByText('Synthetic committed before failed refresh', { exact: true }).waitFor();
    assert.equal(refreshWriteCount, 1); page.off('request', countWrites);
    pass('acknowledged save followed by a failed list refresh retries only reads');

    await navigate(page, '/settings/page');
    await current(page).locator('.item-link').filter({ hasText: '自动保存草稿' }).click();
    await page.locator('.popup.modal-in').getByText('启用', { exact: true }).click();
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('ebk_app_settings') || '{}').autoSaveTransactionDraft === 'enabled');
    await navigate(page, '/transaction/list');
    await openAdd(page, 'Synthetic scoped draft', { noTransactionDraft: 'false', time: '1700000000' });
    await navigate(page, '/transaction/list');
    await switchBook(page, other.name);
    await navigate(page, `/transaction/add?ledgerId=${other.id}&type=3`);
    await current(page).locator('textarea').first().waitFor(); assert.equal(await current(page).locator('textarea').first().inputValue(), '');
    await switchBook(page, ledger.name); await navigate(page, `/transaction/add?ledgerId=${ledger.id}&type=3`);
    await page.waitForFunction(() => document.querySelector('.page-current textarea')?.value === 'Synthetic scoped draft');
    assert.match(await current(page).innerText(), /2023.*11.*15/);
    pass('drafts stay in their original ledger across explicit switches');
    await navigate(page, '/settings/textsize');
    await current(page).locator('.range-slider').evaluate(element => element.f7Range.setValue(6));
    await page.locator('.navbar a').filter({ has: page.locator('.f7-icons', { hasText: 'checkmark_alt' }) }).last().click();
    await page.waitForFunction(() => document.documentElement.classList.contains('font-size-xxxx-large'));
    await navigate(page, `/transaction/add?ledgerId=${ledger.id}&type=3`);
    await page.waitForFunction(() => document.querySelector('.page-current textarea')?.value === 'Synthetic scoped draft');
    await page.screenshot({ path: `${output}/form-light-max-font.png` });
    await navigate(page, '/settings');
    await current(page).locator('.item-link').filter({ hasText: /^主题/ }).click();
    await Promise.all([
        page.waitForEvent('load'),
        page.locator('.popup.modal-in').getByText('深色', { exact: true }).click(),
    ]);
    await page.waitForLoadState('networkidle');
    await page.waitForFunction(() => document.documentElement.classList.contains('dark') && document.documentElement.classList.contains('font-size-xxxx-large'));
    await navigate(page, `/transaction/add?ledgerId=${ledger.id}&type=3`);
    await page.waitForFunction(() => document.querySelector('.page-current textarea')?.value === 'Synthetic scoped draft');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.screenshot({ path: `${output}/form-dark.png` });
    for (const [width, height] of [[375, 812], [844, 390], [768, 1024], [1024, 768], [1440, 900]]) {
        await page.setViewportSize({ width, height });
        assert(await current(page).locator('textarea').first().isVisible());
        assert(await page.getByLabel('保存流水', { exact: true }).last().isVisible());
        const saveBounds = await page.getByLabel('保存流水', { exact: true }).last().boundingBox();
        assert(saveBounds.width >= 44 && saveBounds.height >= 44, 'save target remains at least 44px');
        assert(saveBounds.x >= 0 && saveBounds.x + saveBounds.width <= width && saveBounds.y + saveBounds.height <= height, 'save stays in the viewport');
        const navbarParts = await page.locator('.mobile-transaction-edit-navbar').last().evaluate(element => {
            const inner = element.querySelector('.navbar-inner');
            return ['.left', '.title', '.right'].map(selector => inner.querySelector(selector).getBoundingClientRect().toJSON());
        });
        assert(navbarParts[1].left >= navbarParts[0].right && navbarParts[1].right <= navbarParts[2].left, 'title does not overlap navigation controls');
    }
    assert.equal((await api(`v1/accounts/get.json?id=${personal.id}`, owner.token)).balance, defaultBefore.balance);
    assert.deepEqual(errors, []);
    pass('production settings apply real dark theme and maximum font; responsive save targets remain reachable and the default account stays unchanged');
    await writeFile(`${output}/mobile-write-e2e.json`, JSON.stringify({ passed: checks.length, checks }, null, 2));
} catch (error) {
    releasePendingRoute();
    if (page) await page.screenshot({ path: `${output}/failure.png` }).catch(() => {});
    if (page) console.error(await page.locator('.navbar a:visible').evaluateAll(items => items.map(item => ({ tag: item.tagName, class: item.className, navbar: item.closest('.navbar-inner')?.className, text: item.textContent }))).catch(() => []));
    console.error(String(error.message).replace(/eyJ[A-Za-z0-9_.-]+/g, '[redacted]')); process.exitCode = 1;
} finally { if (browser) await browser.close(); }
