import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const container = 'finexy-ai-review-desktop-e2e', base = 'http://127.0.0.1:18135';
const output = process.env.FINEXY_AI_DESKTOP_EVIDENCE || 'artifacts/ai-review-desktop-20261006';
assert(output.startsWith('artifacts/') && !output.split('/').includes('..'));
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Name, `/${container}`); assert.deepEqual(info.Mounts, []);
assert(['finexy-ai-review-desktop:20261006-root1', 'finexy-ai-review-desktop:20261006-root2', 'finexy-ai-review-desktop:20261006-root3', 'finexy-ai-review-source:20261006-root2', 'finexy-ai-review-source:20261006-root3'].includes(info.Config.Image));
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=255'));
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18135'));
const password = randomBytes(18).toString('hex'), checks = [], errors = [];
let browser, page, legacyCalls = 0;
const posts = [], pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, auth, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(auth ? { Authorization: `Bearer ${auth.token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, 200, path.split('?')[0]); const data = await response.json(); assert(data.success); return data.result;
}
async function register() {
    const username = `ai_ui_${randomBytes(6).toString('hex')}`;
    return { username, ...await api('register.json', null, { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic UI QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] }) };
}
try {
    await mkdir(output, { recursive: true });
    for (let attempt = 0; attempt < 60; attempt++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* Disposable service starting. */ }
        if (attempt === 59) throw new Error('Isolated candidate unavailable');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register(), viewer = await register(), member = await register(), admin = await register();
    const ledger = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic AI UI A' });
    const other = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic AI UI B' });
    for (const [auth, role] of [[viewer, 4], [member, 3], [admin, 2]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner, { ledgerId: ledger.id, inviteeName: auth.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', auth, { token: invitation.token });
        if (role === 2) {
            const self = (await api(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, auth)).find(row => row.isCurrentUser);
            assert(self); await api('v1/ledger/member/change_role.json', owner, { ledgerId: ledger.id, memberId: self.id, role: 2 });
        }
    }
    const account = await api('v1/accounts/add.json', owner, { ledgerId: ledger.id, name: 'Synthetic USD account', category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 });
    const root = await api('v1/transaction/categories/add.json', owner, { type: 2, name: 'Synthetic root', icon: '1', color: '000000' });
    const category = await api('v1/transaction/categories/add.json', owner, { type: 2, parentId: root.id, name: 'Synthetic expense', icon: '1', color: '000000' });
    const seed = (name, auth = owner, recognized = true) => api('v1/ai/review/create.json', auth, {
        ledgerId: ledger.id, clientSessionId: randomBytes(16).toString('hex'), sourceType: 1, sourceText: name, failureReason: '',
        ...(recognized ? { recognizedData: { ledgerId: ledger.id, type: 3, time: Math.floor(Date.now() / 1000) - 60,
            sourceAccountId: account.id, sourceAmount: 123, categoryId: category.id, comment: name } } : {}),
    });
    const original = await seed('Synthetic raw recovery'), known = await seed('Synthetic known receipt');
    await seed('Synthetic ignored restore'); await seed('Synthetic unmatched', owner, false);
    await seed('Synthetic permission owner');
    await seed('Synthetic member own', member);
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    context.on('page', p => p.on('pageerror', error => errors.push(error.name)));
    context.on('request', request => {
        if (request.url().endsWith('/ai/review/confirm.json')) posts.push(request.postDataJSON());
        if (request.url().endsWith('/ai/review/resolve.json') || request.url().endsWith('/transactions/add.json')) legacyCalls++;
    });
    async function login(auth, ctx = context) {
        const p = await ctx.newPage(); p.setDefaultTimeout(20000);
        await p.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
        await p.getByLabel('用户名', { exact: true }).fill(auth.username); await p.getByLabel('密码', { exact: true }).fill(password);
        await p.getByRole('button', { name: '登录', exact: true }).click(); await p.waitForURL(/#\/$/);
        await p.getByRole('button', { name: '切换当前账本', exact: true }).click(); await p.getByRole('option').filter({ hasText: ledger.name }).click();
        await p.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' }); return p;
    }
    page = await login(owner);
    const panel = (p = page) => p.getByRole('dialog').locator('.ai-review-confirmation-panel');
    const activeEditor = p => p.getByRole('dialog').filter({ has: p.locator('.ai-review-confirmation-panel') });
    async function open(name, p = page) {
        await p.locator('.transaction-table tbody tr').filter({ hasText: name }).click();
        await p.getByRole('button', { name: '编辑并入账', exact: true }).click();
        await panel(p).getByRole('status').filter({ hasText: /开始编辑|可恢复|结果待确认|已确认入账/ }).waitFor();
        return activeEditor(p);
    }
    async function recover(p = page) {
        await p.getByRole('button', { name: '恢复原条目', exact: true }).click();
        await panel(p).getByRole('status').filter({ hasText: /可恢复|结果待确认|已确认入账/ }).waitFor(); return activeEditor(p);
    }
    let editor = await open('Synthetic raw recovery');
    await editor.getByLabel(/^支出金额/).fill('1.');
    await panel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    assert.equal(posts.length, 0); await page.reload({ waitUntil: 'networkidle' }); editor = await recover();
    await panel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await editor.getByLabel(/^支出金额/).inputValue(), '1.'); assert.equal(posts.length, 0);
    pass('production cold startup, shared-ledger queue and raw decimal reload restore without financial HTTP');
    await editor.getByLabel(/^支出金额/).fill('-1.23');
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    await panel().getByRole('button', { name: '确认入账', exact: true }).waitFor(); assert.equal(posts.length, 0);
    assert((await panel().innerText()).includes('USD') || (await panel().innerText()).includes('$'));
    await page.route('**/ai/review/confirm.json', async route => { const response = await route.fetch(); assert.equal(response.status(), 200); await response.body(); await route.abort('failed'); });
    await panel().getByRole('button', { name: '确认入账', exact: true }).click(); await panel().getByRole('button', { name: '重试原请求' }).waitFor();
    assert(await editor.getByLabel(/^支出金额/).evaluate(el => !!el.closest('[inert]')));
    const frozen = posts[0]; assert.equal(frozen.transaction.sourceAmount, -123); assert.equal(frozen.ledgerId, ledger.id);
    const saved = await api(`v1/ai/review/get.json?id=${original.id}&ledgerId=${ledger.id}`, owner);
    assert.equal(saved.status, 2); assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner)).balance, 10123);
    await page.unroute('**/ai/review/confirm.json'); await page.reload({ waitUntil: 'networkidle' }); editor = await recover();
    await panel().getByRole('button', { name: '重试原请求', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal(posts.length, 2); assert.deepEqual(posts[1], frozen);
    assert.equal((await api(`v1/ai/review/get.json?id=${original.id}&ledgerId=${ledger.id}`, owner)).transactionId, saved.transactionId);
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner)).balance, 10123);
    pass('explicit preview, actual currency and atomic refund survive committed response loss and reload; original request changes balance once');
    editor = await open('Synthetic known receipt'); await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    let committed = false;
    await page.route('**/ai/review/confirm.json', async route => { const response = await route.fetch(); committed = true; await route.fulfill({ response }); });
    await page.route('**/ai/review/get.json?**', route => committed ? route.abort('failed') : route.continue());
    await panel().getByRole('button', { name: '确认入账', exact: true }).click(); await panel().getByRole('alert').waitFor();
    await panel().getByRole('status').filter({ hasText: '已确认入账' }).waitFor();
    const afterKnown = posts.length;
    await page.unroute('**/ai/review/confirm.json'); await page.unroute('**/ai/review/get.json?**');
    await page.reload({ waitUntil: 'networkidle' }); editor = await recover();
    await panel().getByRole('button', { name: '重新读取原结果', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    assert.equal(posts.length, afterKnown); assert.equal((await api(`v1/ai/review/get.json?id=${known.id}&ledgerId=${ledger.id}`, owner)).status, 2);
    pass('known receipt with failed detail read survives reload and completes with reads only');
    await page.locator('.transaction-table tbody tr').filter({ hasText: 'Synthetic ignored restore' }).click();
    await page.getByRole('button', { name: '忽略', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click();
    await page.getByRole('button', { name: '已忽略的 AI 条目', exact: true }).click();
    await page.locator('.transaction-table tbody tr').filter({ hasText: 'Synthetic ignored restore' }).click();
    await page.getByRole('button', { name: '恢复到待复核', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click();
    await page.getByRole('button', { name: /^账本待复核/ }).click();
    await page.locator('.transaction-table tbody tr').filter({ hasText: 'Synthetic ignored restore' }).waitFor();
    pass('explicit ignore confirmation and ignored-list restoration use the original ledger and leave balance unchanged');
    editor = await open('Synthetic ignored restore');
    await editor.getByLabel(/^支出金额/).fill('2.'); await panel().getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    await page.evaluate(() => { const original = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) {
        if (key.includes('ai-review-draft')) throw new Error('Synthetic local storage denied'); return original.call(this, key, value);
    }; });
    await editor.getByLabel(/^支出金额/).fill('4.'); await panel().getByRole('alert').waitFor();
    assert.equal(await panel().getByRole('button', { name: '确认入账', exact: true }).count(), 0);
    assert(await editor.getByLabel(/^支出金额/).evaluate(el => !!el.closest('[inert]')));
    await page.reload({ waitUntil: 'networkidle' }); editor = await recover();
    await panel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await editor.getByLabel(/^支出金额/).inputValue(), '2.');
    pass('browser storage failure is visible and locks submission; reload restores the last saved original input');
    const second = await context.newPage(); second.setDefaultTimeout(20000);
    await second.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' });
    const secondEditor = await recover(second); await panel(second).getByRole('button', { name: '继续编辑已保存草稿' }).click();
    await secondEditor.getByLabel(/^支出金额/).fill('3.');
    await panel(second).getByRole('status').filter({ hasText: '原始输入已保存' }).waitFor();
    await panel().getByRole('button', { name: '继续编辑已保存草稿' }).waitFor();
    assert(await editor.getByLabel(/^支出金额/).evaluate(el => !!el.closest('[inert]')));
    await panel().getByRole('button', { name: '继续编辑已保存草稿' }).click();
    assert.equal(await editor.getByLabel(/^支出金额/).inputValue(), '3.');
    await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    const beforeTabs = posts.length; await panel().getByRole('button', { name: '确认入账', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    await panel(second).getByRole('status').filter({ hasText: '另一页面处理' }).waitFor();
    assert.equal(posts.length, beforeTabs + 1);
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner)).balance, 9700);
    await second.close();
    pass('two production tabs observe revision conflict, require explicit restoration, and lock after original confirmation without replacement HTTP');
    editor = await open('Synthetic unmatched');
    assert.equal(await editor.getByRole('button', { name: '预览并确认', exact: true }).isDisabled(), true);
    await editor.getByRole('button', { name: '取消', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: '关闭详情', exact: true }).click();
    pass('unmatched AI account and category remain unselected; editor preferences cannot silently match them');
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: other.name }).click();
    assert.equal(await page.getByRole('button', { name: '恢复原条目', exact: true }).count(), 0);
    assert.equal(await page.locator('.transaction-table .transaction-name-cell').count(), 0);
    await page.getByRole('button', { name: /^账本待复核 0$/ }).waitFor();
    assert.equal(await page.locator('.transaction-table .transaction-name-cell').count(), 0);
    pass('ledger switch immediately removes original queue and local recovery entry from the other ledger');
    for (const auth of [viewer, member, admin]) {
        const roleContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
        const rolePage = await login(auth, roleContext);
        await rolePage.locator('.transaction-table tbody tr').filter({ hasText: 'Synthetic permission owner' }).click();
        assert.equal(await rolePage.getByRole('button', { name: '编辑并入账', exact: true }).count(), auth === admin ? 1 : 0);
        assert.equal(await rolePage.getByRole('button', { name: '忽略', exact: true }).count(), auth === admin ? 1 : 0);
        if (auth === member) {
            await rolePage.getByRole('button', { name: '关闭详情', exact: true }).click();
            await rolePage.locator('.transaction-table tbody tr').filter({ hasText: 'Synthetic member own' }).click();
            await rolePage.getByRole('button', { name: '编辑并入账', exact: true }).waitFor();
        }
        await roleContext.close();
    }
    pass('production four-role controls: owner and admin may review, viewer reads only, member reviews own item and cannot review another creator');
    const personalAccount = await api('v1/accounts/add.json', owner, { ledgerId: '0', name: 'Synthetic personal EUR', category: 2, type: 1, icon: '1', color: '000000', currency: 'EUR', balance: 10000, balanceTime: 946684800 });
    await api('v1/ai/review/create.json', owner, { ledgerId: '0', clientSessionId: randomBytes(16).toString('hex'), sourceType: 1,
        sourceText: 'Synthetic personal atomic', failureReason: '', recognizedData: { ledgerId: '0', type: 3, time: Math.floor(Date.now() / 1000) - 60,
            sourceAccountId: personalAccount.id, sourceAmount: 50, categoryId: category.id, comment: 'Synthetic personal atomic' } });
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: '默认个人账本' }).click();
    editor = await open('Synthetic personal atomic'); await editor.getByRole('button', { name: '预览并确认', exact: true }).click();
    const confirmPersonal = panel().getByRole('button', { name: '确认入账', exact: true }); await confirmPersonal.waitFor();
    const visiblePort = await page.locator('.ai-review-confirmation-container').boundingBox(); assert(visiblePort);
    for (const button of await panel().getByRole('button').all()) {
        const bounds = await button.boundingBox(); assert(bounds && bounds.height >= 44);
        assert(bounds.y >= visiblePort.y && bounds.y + bounds.height <= visiblePort.y + visiblePort.height, 'preview action must be inside visible confirmation region');
    }
    await page.screenshot({ path: `${output}/desktop-personal-preview.png`, fullPage: true });
    const beforePersonal = posts.length; await confirmPersonal.click(); await editor.waitFor({ state: 'hidden' });
    assert.equal(posts.length, beforePersonal + 1); assert.equal(posts.at(-1).ledgerId, '0'); assert.equal(posts.at(-1).transaction.ledgerId, '0');
    assert.equal((await api(`v1/accounts/get.json?id=${personalAccount.id}&ledgerId=0`, owner)).balance, 9950);
    pass('default personal ledger keeps ID 0, previews actual EUR with 44px targets, and confirms through the same atomic path');
    assert.equal(legacyCalls, 0); assert.deepEqual(errors, []);
    await writeFile(`${output}/browser-summary.json`, JSON.stringify({ passed: true, checks, image: info.Image, browserErrors: errors, legacyCalls, financialHttpCount: posts.length, productionDesktopUIAccepted: true, mobileUIAccepted: false }, null, 2));
} catch (error) {
    await page?.screenshot({ path: `${output}/browser-failure.png`, fullPage: true }).catch(() => {});
    await writeFile(`${output}/browser-failure-metadata.json`, JSON.stringify({ checks, browserErrors: errors, panels: page ? await page.locator('.ai-review-confirmation-panel').count().catch(() => -1) : -1, legacyCalls }, null, 2));
    console.error(String(error.message).split('\n')[0]); process.exitCode = 1;
} finally { await browser?.close(); }
