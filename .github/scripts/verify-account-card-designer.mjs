import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const base = 'http://127.0.0.1:18110';
assert.deepEqual(JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0].Mounts, []);
const password = randomBytes(18).toString('hex');
const checks = [];
const pass = name => { checks.push(name); console.log('PASS ' + name); };
for (let i = 0; i < 60; i++) { try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch {} await new Promise(resolve => setTimeout(resolve, 250)); }
async function request(path, token, body, expected = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, expected, path);
    const data = await response.json();
    if (expected === 200) assert(data.success, path);
    return data.result;
}
async function register() {
    const username = 'card_' + randomBytes(6).toString('hex');
    const session = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: '卡面验收', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    return { username, token: session.token };
}
const owner = await register();
const other = await register();
const accountDraft = { name: '旅行储蓄', category: 2, type: 1, icon: '1', color: 'f05537', currency: 'CNY', balance: 123456, balanceTime: 946684800 };
const account = await request('v1/accounts/add.json', owner.token, accountDraft);
const second = await request('v1/accounts/add.json', owner.token, { ...accountDraft, name: '日常钱包' });
const query = `v1/accounts/card/get.json?ledgerId=0&accountId=${account.id}`;
const get = () => request(query, owner.token);
const update = (style, expected = 200, token = owner.token, ledgerId = '0') => request('v1/accounts/card/update.json', token, { accountId: account.id, ledgerId, appearance: style }, expected);
let style = await get();
assert.equal(style.revision, 0);
const initialBalance = (await request(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance;
const initialCount = await request('v1/transactions/count.json?ledgerId=0', owner.token);
await request(query, other.token, undefined, 400);
await update(style, 400, other.token);
await request(`v1/accounts/card/get.json?accountId=${account.id}`, owner.token, undefined, 400);
await update({ ...style, background: 'data:image/svg+xml;base64,PHN2Zz4=' }, 400);
await update({ ...style, background: 'data:image/png;base64,YWJj' }, 400);
await update({ ...style, background: 'https://example.invalid/image.png' }, 400);
await update({ ...style, imageX: -1 }, 400);
const apiToken = (await request('v1/tokens/generate/api.json', owner.token, { password, scopes: ['read', 'accounts:manage'], ledgerId: '0', expiresInSeconds: 86400 })).token;
await request(query, apiToken, undefined, 403);
await update(style, 403, apiToken);
pass('normal-session-only, explicit ledger, ownership and image/control validation');
style = await update({ ...style, theme: 'mint' });
await update({ ...style, revision: 0 }, 409);
const races = await Promise.all(['mint', 'copper'].map(theme => fetch(`${base}/api/v1/accounts/card/update.json`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner.token}` }, body: JSON.stringify({ accountId: account.id, ledgerId: '0', appearance: { ...style, theme } }) })));
assert.deepEqual(races.map(r => r.status).sort(), [200, 409]);
style = await get();
pass('server persistence, stale initial save and concurrent update conflict');
const ledger = await request('v1/ledger/create.json', owner.token, { type: 1, name: '共享卡面验收' });
await update(style, 400, owner.token, ledger.id);
const sharedAccount = await request('v1/accounts/add.json', owner.token, { ...accountDraft, ledgerId: ledger.id, name: '共享账户' });
const invitation = await request('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledger.id, inviteeName: other.username, role: 4, expiresInSeconds: 86400 });
await request('v1/ledger/invitation/accept.json', other.token, { token: invitation.token });
const sharedQuery = `v1/accounts/card/get.json?ledgerId=${ledger.id}&accountId=${sharedAccount.id}`;
const otherStyle = await request(sharedQuery, other.token);
await request('v1/accounts/card/update.json', other.token, { ledgerId: ledger.id, accountId: sharedAccount.id, appearance: { ...otherStyle, theme: 'copper' } });
assert.equal((await request(sharedQuery, owner.token)).theme, 'classic');
const member = (await request(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, owner.token)).find(m => !m.isCurrentUser);
await request('v1/ledger/member/remove.json', owner.token, { ledgerId: ledger.id, memberId: member.id });
await request(sharedQuery, other.token, undefined, 403);
pass('shared viewer has personal appearance; member revocation and wrong ledger rejected');

let browser;
try {
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const errors = [];
    async function openAccount() {
        const page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
        await page.getByLabel('用户名', { exact: true }).fill(owner.username);
        await page.getByLabel('密码', { exact: true }).fill(password);
        await page.getByRole('button', { name: '登录', exact: true }).click();
        await page.waitForURL(/#\/$/);
        await page.goto(`${base}/desktop#/account/list`, { waitUntil: 'networkidle' });
        await page.getByRole('combobox', { name: '选择账户', exact: true }).selectOption(account.id);
        await page.getByRole('button', { name: '自定义卡面', exact: true }).waitFor();
        await page.waitForFunction(() => !document.querySelector('.designer-entry button')?.disabled);
        return page;
    }
    const page = await openAccount();
    await mkdir('artifacts/agent-access', { recursive: true });
    const card = page.locator('.holo-card');
    for (const theme of ['classic', 'midnight', 'mint', 'copper']) {
        await update({ ...await get(), theme, foil: 22 });
        await page.getByRole('button', { name: '刷新卡面', exact: true }).click();
        await page.waitForFunction(theme => document.querySelector('.holo-card')?.dataset.theme === theme, theme);
        assert.equal(await page.locator('.holo-brand-seal').count(), 1);
        assert.equal(await page.locator('.holo-glare').count(), 2);
        await card.screenshot({ path: `artifacts/agent-access/holo-effects-${theme}.png` });
    }
    await card.hover({ position: { x: 28, y: 45 } });
    const tilt = await card.evaluate(el => getComputedStyle(el).getPropertyValue('--tilt-y'));
    assert.notEqual(parseFloat(tilt), 0);
    assert.notEqual(await page.locator('.holo-front .holo-glare').evaluate(el => getComputedStyle(el).transform), 'none');
    await card.focus(); await page.keyboard.press('f');
    assert.equal(await card.getAttribute('data-side'), 'back');
    assert.equal(await page.locator('.holo-back').getAttribute('inert'), null);
    await page.keyboard.press('r');
    assert.equal(await card.getAttribute('data-side'), 'front');
    assert.equal(await card.evaluate(el => parseFloat(getComputedStyle(el).getPropertyValue('--tilt-y'))), 0);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await card.hover({ position: { x: 30, y: 50 } });
    assert.equal(await card.evaluate(el => parseFloat(getComputedStyle(el).getPropertyValue('--tilt-y'))), 0);
    assert(await page.locator('.holo-front .holo-glare').evaluate(el => parseFloat(getComputedStyle(el).transitionDuration) <= 0.001));
    await page.getByRole('button', { name: '自定义卡面', exact: true }).click();
    await page.getByLabel('光泽强度', { exact: true }).fill('0');
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.holo-front .holo-foil')).opacity === '0');
    for (const layer of ['.holo-front .holo-foil', '.holo-front .holo-glare', '.holo-stars']) {
        await page.waitForFunction(selector => getComputedStyle(document.querySelector(selector)).opacity === '0', layer);
        assert.equal(await page.locator(layer).evaluate(el => getComputedStyle(el).opacity), '0');
    }
    await page.getByLabel('光泽强度', { exact: true }).fill('100');
    assert.equal(await page.locator('.holo-caption-balance').evaluate(el => getComputedStyle(el).color), 'rgb(18, 20, 26)');
    assert.equal((await card.innerText()).trim(), '', 'front/back artwork has no typography');
    assert.match(await page.locator('.holo-caption').innerText(), /1,234\.56/);
    await page.waitForFunction(() => document.querySelector('.holo-illustration')?.complete && document.querySelector('.holo-illustration')?.naturalWidth === 1024);
    await card.screenshot({ path: 'artifacts/agent-access/holo-effects-full-foil.png' });
    await page.getByRole('button', { name: '取消调整', exact: true }).click();
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    pass('four metallic themes, pointer tilt, flip/reset, reduced motion and zero/full foil');
    // Generate test-only art: a landscape and a transparent foreground, no user files.
    const artwork = await page.evaluate(() => {
        const canvas = document.createElement('canvas'); canvas.width = 600; canvas.height = 900;
        const c = canvas.getContext('2d');
        c.fillStyle = '#476b85'; c.fillRect(0, 0, 600, 900);
        c.fillStyle = '#dfc28c'; c.beginPath(); c.arc(430, 280, 90, 0, Math.PI * 2); c.fill();
        c.fillStyle = '#243f45'; c.beginPath(); c.moveTo(0, 700); c.lineTo(220, 350); c.lineTo(600, 780); c.lineTo(600, 900); c.lineTo(0, 900); c.fill();
        const background = canvas.toDataURL('image/png').split(',')[1];
        c.clearRect(0, 0, 600, 900); c.fillStyle = '#f0dcb2'; c.beginPath(); c.arc(300, 460, 110, 0, Math.PI * 2); c.fill();
        return { background, subject: canvas.toDataURL('image/png').split(',')[1] };
    });
    const upload = async (label, data) => {
        await page.getByLabel(label, { exact: true }).setInputFiles({ name: 'synthetic.png', mimeType: 'image/png', buffer: Buffer.from(data, 'base64') });
        await page.waitForFunction(() => !document.querySelector('.designer-controls')?.textContent.includes('正在处理图片'));
    };
    await page.getByRole('button', { name: '自定义卡面', exact: true }).click();
    await upload('选择背景图', artwork.background);
    await upload('选择主体图', artwork.subject);
    await page.getByRole('button', { name: '午夜蓝', exact: true }).click();
    await page.getByLabel('背景缩放', { exact: true }).fill('130');
    await page.getByLabel('主体缩放', { exact: true }).fill('75');
    await page.getByLabel('光泽强度', { exact: true }).fill('45');
    await page.getByLabel('主体景深', { exact: true }).fill('80');
    assert.equal(await page.locator('.holo-card').count(), 1);
    assert.equal(await page.locator('.holo-background').count(), 1);
    assert.equal(await page.locator('.holo-subject').count(), 1);
    await mkdir('artifacts/agent-access', { recursive: true });
    await page.locator('.card-designer').evaluate(el => el.scrollIntoView({ block: 'start' }));
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.screenshot({ path: 'artifacts/agent-access/card-designer-editor-final.png' });
    await page.getByRole('button', { name: '保存卡面', exact: true }).click();
    await page.getByText('卡面已保存到服务器，其他设备可刷新读取。', { exact: true }).waitFor();
    const uploaded = await get();
    assert.equal(await page.locator('.holo-card').getAttribute('data-account-id'), account.id, 'selected account changed while editing');
    assert(uploaded.background.startsWith('data:image/jpeg;base64,') && uploaded.subject.startsWith('data:image/png;base64,'));
    assert.equal(uploaded.imageZoom, 130); assert.equal(uploaded.foil, 45);
    const device2 = await openAccount();
    assert.equal(await device2.locator('.holo-card').getAttribute('data-theme'), 'midnight');
    assert.equal(await device2.locator('.holo-subject').count(), 1);
    pass('real image selection, browser metadata-free compression, preview, save and second-browser sync');
    await page.getByRole('button', { name: '自定义卡面', exact: true }).click();
    await page.getByRole('button', { name: '暖铜色', exact: true }).click();
    await device2.getByRole('button', { name: '自定义卡面', exact: true }).click();
    await device2.getByRole('button', { name: '松石绿', exact: true }).click();
    await device2.getByRole('button', { name: '保存卡面', exact: true }).click();
    await device2.getByText('卡面已保存到服务器，其他设备可刷新读取。', { exact: true }).waitFor();
    await page.getByRole('button', { name: '保存卡面', exact: true }).click();
    await page.locator('.designer-conflict').waitFor();
    assert.equal(await page.locator('.holo-card').getAttribute('data-theme'), 'copper');
    await page.getByRole('button', { name: '读取最新版本，保留当前草稿', exact: true }).click();
    await page.getByRole('button', { name: '保存卡面', exact: true }).click();
    await page.getByText('卡面已保存到服务器，其他设备可刷新读取。', { exact: true }).waitFor();
    assert.equal((await get()).theme, 'copper');
    pass('two-device conflict preserves draft and requires explicit rebase and save');
    await page.getByRole('button', { name: '自定义卡面', exact: true }).click();
    await page.getByLabel('选择背景图', { exact: true }).setInputFiles({ name: 'bad.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') });
    await page.getByText('请选择 10 MB 以内的 JPG、PNG 或 WebP 图片。', { exact: true }).waitFor();
    await page.context().setOffline(true);
    await page.getByRole('button', { name: '保存卡面', exact: true }).click();
    await page.getByText(/保存失败，请检查网络/).waitFor();
    assert.equal(await page.locator('.holo-subject').count(), 1);
    await page.context().setOffline(false);
    await page.getByRole('button', { name: '取消调整', exact: true }).click();
    pass('invalid upload and offline save keep prior artwork and draft; cancel rolls back preview');
    // An old load response must never put account A's artwork on account B.
    let release;
    const barrier = new Promise(resolve => { release = resolve; });
    const oldStyle = await get();
    await page.route('**/accounts/card/get.json?*', async route => {
        if (new URL(route.request().url()).searchParams.get('accountId') === account.id) {
            await barrier;
            await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, result: oldStyle }) });
        } else await route.continue();
    });
    await page.getByRole('button', { name: '刷新卡面', exact: true }).click();
    await page.getByRole('combobox', { name: '选择账户', exact: true }).selectOption(second.id);
    await page.waitForFunction(() => !document.querySelector('.designer-entry button')?.disabled);
    release(); await page.waitForTimeout(200);
    assert.equal(await page.locator('.holo-background').count(), 0);
    assert.equal(await page.locator('.holo-card').getAttribute('data-theme'), 'classic');
    await page.unroute('**/accounts/card/get.json?*');
    pass('rapid account switch ignores stale image response');
    await page.getByRole('combobox', { name: '选择账户', exact: true }).selectOption(account.id);
    await page.waitForFunction(() => !document.querySelector('.designer-entry button')?.disabled);
    for (const scheme of ['light', 'dark']) {
        await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
        await page.getByRole('button', { name: '自定义卡面', exact: true }).click();
        for (const width of [1160, 1440, 1800]) {
            await page.setViewportSize({ width, height: 1000 });
            for (const zoom of [1, 1.25]) {
                await page.evaluate(zoom => document.documentElement.style.setProperty('zoom', String(zoom), 'important'), zoom);
                await page.waitForFunction(zoom => Number(getComputedStyle(document.documentElement).zoom) === zoom, zoom);
                await page.getByLabel('光泽强度', { exact: true }).focus();
                await page.keyboard.press('Home');
                assert.equal(await page.getByLabel('光泽强度', { exact: true }).inputValue(), '0');
                const bounds = await page.locator('.designer-controls').evaluate(el => ({ overflow: el.scrollWidth > el.clientWidth + 1, buttons: [...el.querySelectorAll('button')].every(button => button.getBoundingClientRect().height >= 44 * parseFloat(document.documentElement.style.zoom || '1') - 1), zoom: getComputedStyle(document.documentElement).zoom }));
                assert(!bounds.overflow && bounds.buttons, JSON.stringify({ scheme, width, zoom, ...bounds }));
            }
        }
        await page.getByRole('button', { name: '取消调整', exact: true }).click();
    }
    await page.evaluate(() => document.documentElement.style.zoom = '1');
    await page.waitForFunction(() => Number(getComputedStyle(document.documentElement).zoom) === 1);
    for (const width of [375, 768, 1024]) {
        await page.setViewportSize({ width, height: 850 });
        await card.evaluate(el => el.scrollIntoView({ block: 'center', inline: 'center' }));
        const bounds = await card.boundingBox();
        assert(bounds && bounds.x >= -1 && bounds.x + bounds.width <= width + 1, `card reachable at ${width}px: ${JSON.stringify(bounds)}`);
        await page.getByRole('button', { name: '翻看背面', exact: true }).click();
        assert.equal(await card.getAttribute('data-side'), 'back');
        await page.getByRole('button', { name: '回到正面', exact: true }).click();
    }
    pass('375/768/1024px card visibility and flip controls');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.locator('.holo-card').screenshot({ path: 'artifacts/agent-access/card-designer-card-final.png' });
    pass('light/dark system, desktop widths, 125% zoom, keyboard range and 44px controls');
    await page.getByRole('button', { name: '自定义卡面', exact: true }).click();
    await page.getByRole('button', { name: '恢复默认卡面', exact: true }).click();
    assert.equal(await page.locator('.holo-background').count(), 0);
    assert((await get()).background);
    await page.getByRole('button', { name: '保存卡面', exact: true }).click();
    await page.getByText('卡面已保存到服务器，其他设备可刷新读取。', { exact: true }).waitFor();
    assert.equal((await get()).background, '');
    execFileSync('docker', ['restart', 'finexy-agent-e2e'], { encoding: 'utf8' });
    for (let i = 0; i < 60; i++) { try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch {} await new Promise(resolve => setTimeout(resolve, 250)); }
    assert.equal((await get()).theme, 'classic');
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance, initialBalance);
    assert.deepEqual(await request('v1/transactions/count.json?ledgerId=0', owner.token), initialCount);
    assert.deepEqual(errors, []);
    pass('explicit reset persists after server restart; exact balance and transaction count unchanged');
    await writeFile('artifacts/agent-access/card-designer-http-browser-final.json', JSON.stringify({ passed: checks.length, groups: checks }, null, 2));
} finally { await browser?.close(); }
console.log(`OK (${checks.length} groups)`);
