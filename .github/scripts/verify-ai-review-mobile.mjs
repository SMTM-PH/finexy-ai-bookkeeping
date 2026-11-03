import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const container = 'finexy-ai-review-mobile-e2e', base = 'http://127.0.0.1:18138';
const output = process.env.FINEXY_AI_MOBILE_EVIDENCE || 'artifacts/ai-review-mobile-20261006/browser';
assert(output.startsWith('artifacts/') && !output.split('/').includes('..'));
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Name, `/${container}`); assert.deepEqual(info.Mounts, []);
assert(['finexy-ai-review-mobile:20261006-root1', 'finexy-ai-review-mobile:20261006-root2', 'finexy-ai-mobile-source:20261007-root2'].includes(info.Config.Image));
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=255'));
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18138'));
const checks = [], errors = [], consoleMessages = [], posts = [], password = randomBytes(18).toString('hex');
let phase = 'setup';
let browser, persistent, page, legacyWrites = 0;
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, auth, body) {
    const res = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(auth ? { Authorization: `Bearer ${auth.token}` } : {})
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(res.status, 200, path.split('?')[0]); const data = await res.json(); assert(data.success); return data.result;
}
async function register() {
    const username = `ai_mobile_${randomBytes(6).toString('hex')}`;
    return { username, ...await api('register.json', null, { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] }) };
}
try {
    await mkdir(output, { recursive: true });
    for (let attempt = 0; attempt < 60; attempt++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* Disposable candidate starting. */ }
        if (attempt === 59) throw new Error('Isolated candidate unavailable');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register(), admin = await register(), member = await register(), viewer = await register();
    const ledger = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic mobile AI A' });
    const other = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic mobile AI B' });
    for (const [auth, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invite = await api('v1/ledger/invitation/create.json', owner, { ledgerId: ledger.id, inviteeName: auth.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', auth, { token: invite.token });
        if (role === 2) {
            const self = (await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, auth)).find(row => row.isCurrentUser);
            await api('v1/ledger/member/change_role.json', owner, { ledgerId: ledger.id, memberId: self.id, role });
        }
    }
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 };
    const account = await api('v1/accounts/add.json', owner, { ...accountDraft, ledgerId: ledger.id, name: 'Synthetic source USD' });
    const destination = await api('v1/accounts/add.json', owner, { ...accountDraft, currency: 'EUR', ledgerId: ledger.id, name: 'Synthetic target EUR' });
    const categories = {};
    for (const type of [1, 2, 3]) {
        const root = await api('v1/transaction/categories/add.json', owner, { type, name: `Synthetic root ${type}`, icon: '1', color: '000000' });
        categories[type] = await api('v1/transaction/categories/add.json', owner, { type, parentId: root.id, name: `Synthetic leaf ${type}`, icon: '1', color: '000000' });
    }
    const seed = (name, auth = owner, data = {}, targetLedgerId = ledger.id) => api('v1/ai/review/create.json', auth, { ledgerId: targetLedgerId, clientSessionId: randomBytes(16).toString('hex'), sourceType: 1,
        sourceText: name, failureReason: '', recognizedData: { ledgerId: targetLedgerId, type: 3, sourceAccountId: account.id, sourceAmount: 123,
            destinationAccountId: '0', destinationAmount: 0, categoryId: categories[2].id, time: Math.floor(Date.now() / 1000) - 60, comment: name, ...data } });
    const raw = await seed('Synthetic raw mobile'), known = await seed('Synthetic known mobile'), ignored = await seed('Synthetic ignored mobile');
    const unmatched = await seed('Synthetic unmatched mobile', owner, { sourceAccountId: '0', categoryId: '0' });
    const ownerRow = await seed('Synthetic permissions owner'), memberRow = await seed('Synthetic permissions member', member);
    const cross = await seed('Synthetic dual currency mobile', owner, { type: 4, categoryId: categories[3].id, destinationAccountId: destination.id, sourceAmount: 150, destinationAmount: 125 });
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 }, reducedMotion: 'reduce' });
    context.on('page', tab => tab.on('pageerror', e => errors.push(`${phase} | ${e?.name || 'ThrownValue'}: ${String(e?.message ?? e).slice(0, 160)}`)));
    // Chromium reports an unhandled rejection's source location only through the console message.
    context.on('page', tab => tab.on('console', message => {
        if (message.type() !== 'error') return;
        const location = message.location();
        consoleMessages.push(`${phase} | ${message.text().slice(0, 160)} @ ${location.url}:${location.lineNumber}`);
    }));
    context.on('request', r => {
        if (r.url().endsWith('/ai/review/confirm.json')) posts.push(r.postDataJSON());
        if (r.url().endsWith('/transactions/add.json') || r.url().endsWith('/ai/review/resolve.json')) legacyWrites++;
    });
    const current = p => p.locator('.view-main > .page.page-current');
    const panel = p => current(p).locator('.ai-review-confirmation-panel');
    async function navigate(p, path) {
        await p.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
        await p.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
        await p.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
        await p.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
    }
    async function switchLedger(p, target) {
        await navigate(p, '/ledger/manage');
        await current(p).locator('.list .item-link').filter({ hasText: target.name }).click();
        await current(p).getByText('切换到此账本', { exact: true }).click();
        await current(p).getByText(new RegExp(`正在使用：${target.name}`)).waitFor();
    }
    async function login(auth, ctx = context) {
        const p = await ctx.newPage(); p.setDefaultTimeout(20000);
        await p.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
        await current(p).locator('input[type="text"]').first().fill(auth.username);
        await current(p).locator('input[type="password"]').first().fill(password);
        await current(p).getByText('登录', { exact: true }).click();
        await current(p).getByText('当前账本：默认个人账本', { exact: true }).waitFor();
        await switchLedger(p, ledger); await navigate(p, '/ai/review');
        await current(p).locator('.mobile-ai-review-card').first().waitFor(); return p;
    }
    async function open(p, row, targetLedgerId = ledger.id) {
        await navigate(p, `/transaction/add?ledgerId=${targetLedgerId}&aiReviewId=${row.id}`);
        await panel(p).getByRole('status').filter({ hasText: /开始编辑|可恢复|结果待确认|已确认入账/ }).waitFor();
    }
    async function recover(p) {
        await navigate(p, '/ai/review'); await current(p).getByRole('button', { name: '恢复原条目', exact: true }).click();
        await panel(p).getByRole('status').filter({ hasText: /可恢复|结果待确认|已确认入账/ }).waitFor();
    }
    // The production discard is serialized through a real IndexedDB transaction, so the stored draft
    // disappears a few tasks after the click. Leaving before it commits lets the queue read the
    // not-yet-removed draft; wait for the production UI and the stored key instead of racing it.
    async function discardDraft(p) {
        await panel(p).getByRole('button', { name: '丢弃本机草稿', exact: true }).click();
        await panel(p).getByRole('button', { name: '确认丢弃', exact: true }).click();
        await panel(p).getByRole('button', { name: '丢弃本机草稿', exact: true }).waitFor({ state: 'hidden' });
        await p.waitForFunction(ledgerId => !Object.keys(localStorage).some(key => key.includes('ai-review-draft') && key.endsWith(`:${ledgerId}`)), ledger.id);
    }
    // A persistent profile can reload with a live session or with the login page; both are a real restart.
    async function ensureLoggedIn(p, auth) {
        await p.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
        const passwordInput = current(p).locator('input[type="password"]').first();
        if (await passwordInput.isVisible().catch(() => false)) {
            await current(p).locator('input[type="text"]').first().fill(auth.username);
            await passwordInput.fill(password);
            await current(p).getByText('登录', { exact: true }).click();
            await current(p).getByText('当前账本：默认个人账本', { exact: true }).waitFor();
        }
    }
    page = await login(owner); await open(page, raw);
    await current(page).locator('.transaction-edit-amount').first().click();
    const pad = () => page.locator('.numpad-sheet.modal-in');
    for (let n = 0; n < 4; n++) await pad().locator('.numpad-button').filter({ has: page.getByText('delete_left', { exact: true }) }).click();
    await pad().getByText('1', { exact: true }).click(); await pad().getByText('+', { exact: true }).click(); await pad().getByText('2', { exact: true }).click();
    await panel(page).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor(); assert.equal(posts.length, 0);
    await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await panel(page).getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await current(page).locator('.transaction-edit-amount').first().click();
    assert.match(await pad().locator('.numpad-values').innerText(), /1\s*\+\s*2/);
    await pad().locator('.numpad-button-confirm').click(); await pad().locator('.numpad-button-confirm').click(); await pad().waitFor({ state: 'hidden' });
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor(); assert.equal(posts.length, 0);
    pass('375px production shared-ledger queue and unfinished calculator survive reload without financial writes');
    await page.route('**/ai/review/confirm.json', async route => { const response = await route.fetch(); assert.equal(response.status(), 200); await response.body(); await route.abort('failed'); });
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).click(); await panel(page).getByRole('button', { name: '重试原请求', exact: true }).waitFor();
    assert.equal(posts[0].transaction.sourceAmount, 300); assert.equal(posts[0].ledgerId, ledger.id);
    assert(await current(page).locator('.transaction-edit-amount').first().evaluate(el => !!el.closest('[inert]')));
    const originalRequest = posts[0], saved = await api(`v1/ai/review/get.json?ledgerId=${ledger.id}&id=${raw.id}`, owner);
    await page.unroute('**/ai/review/confirm.json'); await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await panel(page).getByRole('button', { name: '重试原请求', exact: true }).click(); await panel(page).waitFor({ state: 'hidden' });
    assert.deepEqual(posts[1], originalRequest); assert.equal(posts.length, 2);
    assert.equal((await api(`v1/ai/review/get.json?ledgerId=${ledger.id}&id=${raw.id}`, owner)).transactionId, saved.transactionId);
    assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, owner)).balance, 9700);
    pass('committed response loss locks editor; recovery resends exact original atomic request and changes balance once');
    await open(page, known); await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    let committed = false;
    await page.route('**/ai/review/confirm.json', async route => { const response = await route.fetch(); committed = true; await route.fulfill({ response }); });
    await page.route('**/ai/review/get.json?**', route => committed ? route.abort('failed') : route.continue());
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).click(); await panel(page).getByRole('status').filter({ hasText: '已确认入账' }).waitFor();
    const knownPosts = posts.length;
    await page.unroute('**/ai/review/confirm.json'); await page.unroute('**/ai/review/get.json?**');
    await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await panel(page).getByRole('button', { name: '重新读取原结果', exact: true }).click(); await panel(page).waitFor({ state: 'hidden' });
    assert.equal(posts.length, knownPosts);
    pass('known receipt with failed detail read survives reload and finishes with reads only');
    await navigate(page, '/ai/review');
    const ignoredCard = () => current(page).locator('.mobile-ai-review-card').filter({ hasText: ignored.sourceText });
    await ignoredCard().getByRole('button', { name: '忽略条目', exact: true }).click();
    await current(page).getByRole('button', { name: '确认忽略', exact: true }).click();
    await ignoredCard().waitFor({ state: 'hidden' });
    await current(page).getByRole('button', { name: '已忽略', exact: true }).click();
    await ignoredCard().getByRole('button', { name: '恢复到待复核', exact: true }).click();
    await current(page).getByRole('button', { name: '确认恢复', exact: true }).click();
    await ignoredCard().waitFor({ state: 'hidden' }); assert.equal(posts.length, knownPosts);
    pass('explicit ignore and restore retain original scope and never create financial transactions');
    await open(page, unmatched);
    assert.match(await current(page).locator('.transaction-edit-amount').first().innerText(), /1\.23/);
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('alert').waitFor();
    await panel(page).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    assert.equal(await panel(page).getByRole('button', { name: '确认入账', exact: true }).count(), 0);
    assert.equal(posts.length, knownPosts);
    pass('unmatched account and category stay unselected; no default account fallback or financial write');
    // Remove this deliberately incomplete draft through the production recovery UI.
    await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await discardDraft(page);
    await navigate(page, '/ai/review');
    await open(page, ignored);
    await current(page).locator('.transaction-edit-comment textarea').fill('Synthetic draft A');
    await panel(page).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    const second = await context.newPage(); second.setDefaultTimeout(20000);
    await second.goto(`${base}/mobile`, { waitUntil: 'networkidle' }); await recover(second);
    await panel(second).getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await current(second).locator('.transaction-edit-comment textarea').fill('Synthetic draft B');
    await panel(second).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    await panel(page).getByRole('button', { name: '继续编辑已保存草稿' }).waitFor();
    assert(await current(page).locator('.transaction-edit-comment').evaluate(el => !!el.closest('[inert]')));
    await panel(page).getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await current(page).locator('.transaction-edit-comment textarea').inputValue(), 'Synthetic draft B');
    await second.close();
    pass('two real mobile tabs detect changed draft revision, lock stale controls and require explicit restore');
    await page.evaluate(() => { const set = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) {
        if (key.includes('ai-review-draft')) throw new DOMException('Synthetic storage full', 'QuotaExceededError'); return set.call(this, key, value);
    }; });
    await current(page).locator('.transaction-edit-comment textarea').fill('Synthetic unsaved C');
    await panel(page).getByRole('alert').waitFor();
    assert(await current(page).locator('.transaction-edit-comment').evaluate(el => !!el.closest('[inert]')));
    await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await panel(page).getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await current(page).locator('.transaction-edit-comment textarea').inputValue(), 'Synthetic draft B');
    await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await discardDraft(page);
    await navigate(page, '/ai/review');
    assert.equal(await current(page).getByRole('button', { name: '恢复原条目', exact: true }).count(), 0);
    pass('actual storage failure blocks replacement confirmation; reload retains last saved input and exit does not recreate discarded draft');
    await switchLedger(page, other); await navigate(page, '/ai/review');
    await current(page).getByText('当前账本没有待复核条目。', { exact: true }).waitFor();
    assert.equal(await current(page).locator('.mobile-ai-review-card').count(), 0); assert.equal(await current(page).getByRole('button', { name: '恢复原条目', exact: true }).count(), 0);
    await page.route('**/ai/review/list.json?**', route => route.abort('failed'));
    await current(page).getByRole('button', { name: '刷新当前账本', exact: true }).click();
    await current(page).getByRole('alert').waitFor(); assert.equal(await current(page).getByText('当前账本没有待复核条目。', { exact: true }).count(), 0);
    await page.unroute('**/ai/review/list.json?**'); await switchLedger(page, ledger); await navigate(page, '/ai/review');
    pass('ledger switch clears rows and recovery; request failure has visible retry and cannot masquerade as an empty queue');
    for (const [auth, expectedOwner, expectedOwn] of [[admin, true, true], [member, false, true], [viewer, false, false]]) {
        const ctx = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 } });
        const p = await login(auth, ctx);
        const ownerCard = current(p).locator('.mobile-ai-review-card').filter({ hasText: ownerRow.sourceText });
        const ownCard = current(p).locator('.mobile-ai-review-card').filter({ hasText: memberRow.sourceText });
        assert.equal(await ownerCard.getByRole('button', { name: '编辑并入账', exact: true }).count(), expectedOwner ? 1 : 0);
        assert.equal(await ownCard.getByRole('button', { name: '编辑并入账', exact: true }).count(), expectedOwn ? 1 : 0);
        await open(p, ownerRow);
        assert.equal(await current(p).getByRole('button', { name: '预览并确认', exact: true }).count(), expectedOwner ? 1 : 0);
        await ctx.close();
    }
    pass('actual admin, member and viewer pages follow original item editability and current member permissions');
    await open(page, cross);
    const amounts = current(page).locator('.transaction-edit-amount');
    assert.match(await amounts.nth(0).innerText(), /1\.50/); assert.match(await amounts.nth(1).innerText(), /1\.25/);
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor();
    assert.match(await panel(page).innerText(), /USD|\$/); assert.match(await panel(page).innerText(), /EUR|€/);
    await panel(page).getByRole('button', { name: '继续编辑', exact: true }).click();
    await panel(page).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    for (const theme of ['light', 'dark']) {
        await page.evaluate(value => { const settings = JSON.parse(localStorage.getItem('ebk_app_settings') || '{}'); settings.theme = value; settings.fontSize = 6; localStorage.setItem('ebk_app_settings', JSON.stringify(settings)); }, theme);
        await page.reload({ waitUntil: 'networkidle' }); await recover(page);
        await panel(page).getByRole('button', { name: '继续编辑已保存草稿' }).click();
        await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
        await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor();
        for (const viewport of [{ width: 375, height: 812 }, { width: 812, height: 375 }, { width: 768, height: 1024 }]) {
            await page.setViewportSize(viewport);
            assert(await current(page).evaluate(el => el.scrollWidth <= innerWidth + 1));
            assert(await panel(page).getByRole('button', { name: '确认入账', exact: true }).evaluate(el => el.getBoundingClientRect().height >= 44));
            await page.screenshot({ path: `${output}/${theme}-${viewport.width}.png`, fullPage: true });
        }
        await panel(page).getByRole('button', { name: '继续编辑', exact: true }).click();
    }
    pass('cross-currency transfer preserves dual amounts; preview fits light/dark maximum-font portrait, landscape and tablet with 44px actions');
    // One active draft per ledger is the contract, so clear the unfinished cross-currency draft first.
    await page.reload({ waitUntil: 'networkidle' }); await recover(page);
    await discardDraft(page); await navigate(page, '/ai/review');
    const refund = await seed('Synthetic refund mobile', owner, { sourceAmount: -123, comment: 'Synthetic refund mobile' });
    await open(page, refund);
    assert.match(await current(page).locator('.transaction-edit-amount').first().innerText(), /1\.23/);
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor();
    assert.match(await panel(page).innerText(), /退款/);
    const balanceBeforeRefund = (await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, owner)).balance;
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).click(); await panel(page).waitFor({ state: 'hidden' });
    assert.equal(posts.at(-1).transaction.sourceAmount, -123); assert.equal(posts.at(-1).transaction.type, 3);
    assert.equal((await api(`v1/ai/review/get.json?ledgerId=${ledger.id}&id=${refund.id}`, owner)).status, 2);
    assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, owner)).balance, balanceBeforeRefund + 123);
    pass('negative expense (refund) keeps its sign and refund type through preview and the atomic confirm');
    const externalAccount = await api('v1/accounts/add.json', owner, { ...accountDraft, ledgerId: ledger.id, name: 'Synthetic external USD' });
    const externalOut = await seed('Synthetic external out', owner, { type: 4, categoryId: categories[3].id, sourceAccountId: externalAccount.id, sourceAmount: 200, destinationAccountId: '0', destinationAmount: 0, comment: 'Synthetic external out' });
    const externalIn = await seed('Synthetic external in', owner, { type: 4, categoryId: categories[3].id, sourceAccountId: '0', sourceAmount: 0, destinationAccountId: externalAccount.id, destinationAmount: 300, comment: 'Synthetic external in' });
    // Accounts created through the API are not in this page's already loaded account list.
    await page.reload({ waitUntil: 'networkidle' });
    await open(page, externalOut);
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor();
    assert.match(await panel(page).innerText(), /系统外/);
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).click(); await panel(page).waitFor({ state: 'hidden' });
    assert.equal(posts.at(-1).transaction.destinationAccountId, '0'); assert.equal(posts.at(-1).transaction.destinationAmount, 0); assert.equal(posts.at(-1).transaction.sourceAmount, 200);
    assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${externalAccount.id}`, owner)).balance, 9800);
    await open(page, externalIn);
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor();
    assert.match(await panel(page).innerText(), /系统外/);
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).click(); await panel(page).waitFor({ state: 'hidden' });
    assert.equal(posts.at(-1).transaction.sourceAccountId, '0'); assert.equal(posts.at(-1).transaction.sourceAmount, 0); assert.equal(posts.at(-1).transaction.destinationAmount, 300);
    assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${externalAccount.id}`, owner)).balance, 10100);
    pass('external transfers keep the system-out side, single-side amounts and direction through the atomic confirm');
    const personalAccount = await api('v1/accounts/add.json', owner, { ...accountDraft, ledgerId: '0', currency: 'EUR', name: 'Synthetic personal EUR' });
    const personal = await seed('Synthetic personal mobile', owner, { sourceAccountId: personalAccount.id, sourceAmount: 50, comment: 'Synthetic personal mobile' }, '0');
    await switchLedger(page, { name: '默认个人账本' }); await navigate(page, '/ai/review');
    await current(page).locator('.mobile-ai-review-card').filter({ hasText: personal.sourceText }).waitFor();
    assert.equal(await current(page).locator('.mobile-ai-review-card').count(), 1);
    await open(page, personal, '0');
    await current(page).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).waitFor();
    assert.match(await panel(page).innerText(), /EUR|€/);
    await panel(page).getByRole('button', { name: '确认入账', exact: true }).click(); await panel(page).waitFor({ state: 'hidden' });
    assert.equal(posts.at(-1).ledgerId, '0'); assert.equal(posts.at(-1).transaction.ledgerId, '0');
    assert.equal((await api(`v1/accounts/get.json?ledgerId=0&id=${personalAccount.id}`, owner)).balance, 9950);
    await switchLedger(page, ledger); await navigate(page, '/ai/review');
    pass('default personal ledger keeps ID 0 and confirms with its own currency through the atomic path');
    const memberContext = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 } });
    const memberPage = await login(member, memberContext);
    await open(memberPage, memberRow);
    await current(memberPage).getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel(memberPage).getByRole('button', { name: '确认入账', exact: true }).waitFor();
    const memberSelf = (await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, member)).find(row => row.isCurrentUser);
    const balanceBeforeDowngrade = (await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, owner)).balance;
    await api('v1/ledger/member/change_role.json', owner, { ledgerId: ledger.id, memberId: memberSelf.id, role: 4 });
    await panel(memberPage).getByRole('button', { name: '确认入账', exact: true }).click();
    await panel(memberPage).getByRole('alert').first().waitFor();
    assert.match(await panel(memberPage).locator('[role="alert"]').first().innerText(), /权限|保留/);
    assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, owner)).balance, balanceBeforeDowngrade);
    assert.equal((await api(`v1/ai/review/get.json?ledgerId=${ledger.id}&id=${memberRow.id}`, owner)).status, 1);
    await api('v1/ledger/member/change_role.json', owner, { ledgerId: ledger.id, memberId: memberSelf.id, role: 3 });
    await memberContext.close();
    pass('losing write permission after preview blocks confirmation and preserves the original item and balance');
    phase = 'late-response';
    await navigate(page, '/ai/review');
    await current(page).locator('.mobile-ai-review-card').first().waitFor();
    let delayedOldLedger = false;
    await page.route('**/ai/review/list.json?**', async route => {
        if (!delayedOldLedger) { delayedOldLedger = true; await new Promise(resolve => setTimeout(resolve, 3000)); }
        await route.continue().catch(() => { /* The page may already have left the original ledger. */ });
    });
    await current(page).getByRole('button', { name: '刷新当前账本', exact: true }).click();
    await switchLedger(page, other); await navigate(page, '/ai/review');
    await current(page).getByText('当前账本没有待复核条目。', { exact: true }).waitFor();
    await page.waitForTimeout(3600);
    assert.equal(await current(page).locator('.mobile-ai-review-card').count(), 0);
    assert.equal(await current(page).getByRole('button', { name: '恢复原条目', exact: true }).count(), 0);
    assert.equal(await current(page).getByText('当前账本没有待复核条目。', { exact: true }).count(), 1);
    await page.unroute('**/ai/review/list.json?**');
    pass('a late queue response from the previous ledger cannot replace the new ledger context');
    phase = 'browser-restart';
    const profile = `${output}/persistent-profile`;
    await rm(profile, { recursive: true, force: true });
    const persistentOptions = { headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 } };
    persistent = await chromium.launchPersistentContext(profile, persistentOptions);
    let restartPage = persistent.pages()[0] || await persistent.newPage();
    restartPage.setDefaultTimeout(20000);
    await ensureLoggedIn(restartPage, owner);
    await switchLedger(restartPage, ledger); await navigate(restartPage, '/ai/review');
    await open(restartPage, ignored);
    await current(restartPage).locator('.transaction-edit-comment textarea').fill('Synthetic durable across browser process restart');
    await panel(restartPage).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    await persistent.close();
    persistent = await chromium.launchPersistentContext(profile, persistentOptions);
    restartPage = persistent.pages()[0] || await persistent.newPage();
    restartPage.setDefaultTimeout(20000);
    await ensureLoggedIn(restartPage, owner);
    await recover(restartPage);
    await panel(restartPage).getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await current(restartPage).locator('.transaction-edit-comment textarea').inputValue(), 'Synthetic durable across browser process restart');
    await restartPage.reload({ waitUntil: 'networkidle' }); await recover(restartPage);
    await discardDraft(restartPage);
    await persistent.close(); persistent = null;
    pass('a real browser process restart keeps the local AI draft recoverable and discardable');
    phase = 'account-switch';
    await switchLedger(page, ledger); await navigate(page, '/ai/review');
    await open(page, ignored);
    await current(page).locator('.transaction-edit-comment textarea').fill('Synthetic cross account draft');
    await panel(page).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    // Let the queue reload finish before signing out, so the session teardown has no concurrent request.
    await navigate(page, '/ai/review');
    await current(page).locator('.mobile-ai-review-card').first().waitFor();
    await navigate(page, '/settings');
    phase = 'account-switch/logout';
    await current(page).getByText('退出登录', { exact: true }).first().click();
    await page.locator('.dialog.modal-in').getByText('确定', { exact: true }).click();
    phase = 'account-switch/login';
    // Signing out can force the app's own invalid-token reload; wait for the login page instead of racing it.
    await current(page).locator('input[type="password"]').first().waitFor();
    await current(page).locator('input[type="text"]').first().fill(viewer.username);
    await current(page).locator('input[type="password"]').first().fill(password);
    await current(page).getByText('登录', { exact: true }).click();
    await current(page).getByText('当前账本：默认个人账本', { exact: true }).waitFor();
    phase = 'account-switch/verify';
    await switchLedger(page, ledger); await navigate(page, '/ai/review');
    await current(page).locator('.mobile-ai-review-card').first().waitFor();
    assert.equal(await current(page).getByRole('button', { name: '恢复原条目', exact: true }).count(), 0);
    assert.equal(await current(page).getByRole('button', { name: '编辑并入账', exact: true }).count(), 0);
    pass('another account signing in on the same browser does not inherit the previous local draft, recovery entry or write controls');
    assert.equal(legacyWrites, 0);
    // Always keep the evidence, then fail on the gate so a run is never silently reported as passing.
    await writeFile(`${output}/summary.json`, JSON.stringify({ passed: checks.length, checks, pageErrors: errors, consoleErrors: consoleMessages, legacyFinancialWrites: legacyWrites, atomicPosts: posts.length, mainAppTouched: false, productionDeployment: false }, null, 2));
    assert.deepEqual(errors, []);
    console.log(`PASS ${checks.length} mobile AI production cases`);
} catch (error) {
    if (page) await page.screenshot({ path: `${output}/failure.png`, fullPage: true }).catch(() => {});
    // Never log browser storage, credentials or full request bodies.
    throw error;
} finally { await persistent?.close().catch(() => {}); await browser?.close(); }
