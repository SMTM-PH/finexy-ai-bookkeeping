import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { writeFile, mkdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const base = 'http://127.0.0.1:18110';
const container = 'finexy-agent-e2e';
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
const password = randomBytes(18).toString('hex');
const key = randomBytes(18).toString('hex');
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function request(path, token, body, expected = 200) {
    const r = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const json = await r.json();
    assert.equal(r.status, expected, `unexpected status: ${path}`);
    if (expected === 200) assert(json.success, `request failed: ${path}`);
    return json.result;
}
async function register() {
    const username = `ai_${randomBytes(6).toString('hex')}`;
    return { username, ...(await request('register.json', '', { username, email: `${username}@example.invalid`, nickname: 'AI 隔离测试', password, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] })) };
}
let mode = 'success';
let calls = 0;
const mock = createServer((req, res) => {
    if (req.headers.authorization !== `Bearer ${key}`) { res.writeHead(403); res.end(); return; }
    req.resume();
    calls++;
    res.setHeader('Content-Type', 'application/json');
    if (mode === 'failure') { res.writeHead(503); res.end('{}'); return; }
    res.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ type: 'expense', amount: '1.23', description: '合成计量预览', time: '2026-10-02 12:00:00' }) } }], ...(mode === 'unknown' ? {} : { usage: { prompt_tokens: 120, completion_tokens: 80, total_tokens: 200, prompt_cache_hit_tokens: 100, completion_tokens_details: { reasoning_tokens: 40 } } }) }));
});
await new Promise(resolve => mock.listen(18212, '0.0.0.0', resolve));
let browser;
try {
    for (let i = 0; i < 40; i++) { try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch {} await new Promise(resolve => setTimeout(resolve, 500)); }
    const owner = await register();
    const other = await register();
    assert.equal((await request('v1/llm/usage/get.json', owner.token)).all.requests, 0);
    const configuration = { enabled: true, imageEnabled: false, baseUrl: `http://${process.env.FINEXY_MOCK_HOST || 'host.docker.internal'}:18212`, apiKey: key, modelId: 'synthetic-metering', enableThinking: 'off', requestTimeout: 10000 };
    await request('v1/llm/configuration/update.json', owner.token, configuration);
    await request('v1/llm/configuration/update.json', other.token, configuration, 400);
    assert.equal((await request('v1/llm/balance/get.json', owner.token)).status, 'unsupported');
    await request('v1/llm/balance/get.json', other.token, undefined, 400);
    const agent = (await request('v1/tokens/generate/api.json', owner.token, { name: 'meter-denial', password, scopes: ['read'], ledgerId: '0', expiresInSeconds: 3600 })).token;
    await request('v1/llm/usage/get.json', agent, undefined, 403);
    await request('v1/llm/balance/get.json', agent, undefined, 403);
    pass('owner-only billing, Agent denial and compatible endpoints never queried for balances');
    for (const value of ['success', 'unknown', 'failure']) {
        mode = value;
        await request('v1/llm/transactions/recognize_text.json', owner.token, { text: '合成测试支出1.23元' }, value === 'failure' ? 400 : 200);
    }
    let usage = await request('v1/llm/usage/get.json', owner.token);
    assert.equal(calls, 3);
    assert.equal(usage.all.requests, 3);
    assert.equal(usage.all.failedRequests, 1);
    assert.equal(usage.all.unmeteredRequests, 2);
    assert.equal(usage.all.totalTokens, 200);
    assert.equal(usage.all.cachedTokens, 100);
    assert.equal(usage.all.reasoningTokens, 40);
    assert.equal(usage.all.cacheMeteredRequests, 1);
    assert.equal(usage.all.reasoningMeteredRequests, 1);
    assert.equal(usage.recent.length, 3);
    assert(!JSON.stringify(usage).includes(key));
    assert(!JSON.stringify(usage).includes('合成计量预览'));
    assert.equal((await request('v1/llm/usage/get.json', other.token)).all.requests, 0);
    assert.equal((await request('v1/transactions/count.json', owner.token)).totalCount, 0);
    pass('real synthetic model calls persist exact usage, unknowns and failures without storing content or posting');
    execFileSync('docker', ['restart', container], { stdio: 'ignore' });
    for (let i = 0; i < 40; i++) { try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch {} await new Promise(resolve => setTimeout(resolve, 500)); }
    usage = await request('v1/llm/usage/get.json', owner.token);
    assert.equal(usage.all.totalTokens, 200);
    assert.equal(usage.all.requests, 3);
    pass('metering and model configuration survive service restart');
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(owner.username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(/#\/$/);
    await page.goto(`${base}/desktop#/user/settings?tab=securitySetting`, { waitUntil: 'networkidle' });
    assert.equal(await page.locator('.agent-access').count(), 0);
    const navigation = await page.locator('.settings-layout nav b').allTextContents();
    assert.equal(navigation.indexOf('AI 设置'), navigation.indexOf('安全设置') + 1);
    await page.getByRole('button').filter({ hasText: 'AI 设置' }).click();
    await page.waitForURL(/tab=aiSetting/);
    await page.locator('.ai-usage-details').waitFor();
    assert((await page.locator('.ai-usage-details').innerText()).includes('200'));
    await page.reload({ waitUntil: 'networkidle' });
    await page.locator('.ai-usage-details').waitFor();
    assert.equal(await page.getByRole('button', { name: '保存更改', exact: true }).count(), 0);
    await page.getByRole('button', { name: '刷新用量', exact: true }).first().click();
    await page.waitForFunction(() => !document.querySelector('.ai-service-head button').disabled);
    const mcpSwitch = page.getByRole('switch', { name: '此账号的 MCP 接入' });
    await page.waitForFunction(() => !document.querySelector('[role="switch"]').disabled);
    assert.equal(await mcpSwitch.innerText(), '已开启');
    await mcpSwitch.focus(); await page.keyboard.press('Space');
    await page.waitForFunction(() => document.querySelector('[role="switch"]').getAttribute('aria-checked') === 'false');
    assert.equal(await mcpSwitch.innerText(), '已关闭');
    await mcpSwitch.click();
    await page.waitForFunction(() => document.querySelector('[role="switch"]').getAttribute('aria-checked') === 'true');
    pass('independent AI navigation deep link, reload and current-status-only keyboard switch');
    const contrast = (text, background) => {
        const l = color => { const rgb = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(n => { n /= 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; }); return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722; };
        const a = l(text), b = l(background); return (Math.max(a,b) + .05) / (Math.min(a,b) + .05);
    };
    for (const theme of ['light', 'dark']) for (const zoom of [1, 1.25]) {
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await page.evaluate(z => document.documentElement.style.zoom = z, zoom);
        const colors = await page.locator('.agent-permission small').first().evaluate(el => ({ text: getComputedStyle(el).color, background: getComputedStyle(document.querySelector('.agent-access')).backgroundColor, font: parseFloat(getComputedStyle(el).fontSize) }));
        assert(contrast(colors.text, colors.background) >= 4.5);
        assert(colors.font >= 13);
        assert(await page.locator('.ai-settings').evaluate(el => el.scrollWidth <= el.clientWidth + 1));
    }
    await page.evaluate(() => document.documentElement.style.zoom = '1');
    await mkdir('artifacts/agent-access', { recursive: true });
    await page.getByRole('heading', { name: '模型与用量', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'artifacts/agent-access/ai-settings-desktop-final.png' });
    await page.getByRole('heading', { name: 'Agent 接入', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'artifacts/agent-access/ai-settings-agent-final.png' });
    pass('Agent text contrast, readable font, system themes, zoom and reduced motion');
    // Mock billing responses at the browser boundary; never call a paid provider.
    await page.route('**/api/v1/llm/balance/get.json*', route => route.fulfill({ json: { success: true, result: { status: 'available', available: true, balances: [{ currency: 'CNY', totalBalance: '12.34', toppedUpBalance: '10.00', grantedBalance: '2.34' }], updatedAt: Math.floor(Date.now() / 1000) } } }));
    await page.getByRole('button', { name: '刷新用量', exact: true }).first().click();
    await page.getByText('余额可用', { exact: true }).waitFor();
    assert((await page.locator('.ai-balance-details').innerText()).includes('12.34'));
    await page.unroute('**/api/v1/llm/balance/get.json*');
    await page.route('**/api/v1/llm/balance/get.json*', route => route.fulfill({ json: { success: true, result: { status: 'unavailable', available: false, balances: [], updatedAt: 0 } } }));
    await page.getByRole('button', { name: '刷新用量', exact: true }).first().click();
    await page.getByText('暂未获取余额。可刷新重试，或在官方平台查看。', { exact: true }).waitFor();
    assert.equal(await page.locator('.ai-balance-details').count(), 0);
    await page.unroute('**/api/v1/llm/balance/get.json*');
    pass('billing details render official decimal values and clear stale balances on failure (browser mocks)');
    await page.getByRole('link', { name: '修改服务端模型配置' }).click();
    await page.getByRole('button', { name: '保存并立即生效' }).waitFor();
    await page.waitForFunction(() => document.querySelectorAll('.ai-config-form input')[1]?.value === 'synthetic-metering');
    assert.equal(await page.locator('.ai-config-form input').nth(1).inputValue(), 'synthetic-metering');
    pass('model configuration link opens actual configured model');
    const mobile = await context.newPage();
    mobile.on('pageerror', e => errors.push(e.message));
    await mobile.setViewportSize({ width: 375, height: 900 });
    await mobile.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await mobile.waitForFunction(() => document.querySelector('input[autocomplete="username"]') || document.querySelector('a[href="/settings"]'));
    if (await mobile.locator('input[autocomplete="username"]').count()) {
        await mobile.locator('input[autocomplete="username"]').fill(owner.username);
        await mobile.locator('input[autocomplete="current-password"]').fill(password);
        await mobile.getByText('登录', { exact: true }).click();
    }
    await mobile.locator('a[href="/settings"]').click();
    await mobile.locator('a[href="/user/ai-settings"]').click();
    await mobile.locator('.ai-usage-details').waitFor();
    for (const width of [375, 768, 1024]) {
        await mobile.setViewportSize({ width, height: 900 });
        assert(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        const layout = await mobile.locator('.ai-settings').evaluate(el => ({ client: el.clientWidth, scroll: el.scrollWidth, overflowing: [...el.querySelectorAll('*')].filter(child => child.getBoundingClientRect().right > el.getBoundingClientRect().right + 1).map(child => ({ tag: child.tagName, class: child.className, right: child.getBoundingClientRect().right, width: child.getBoundingClientRect().width })).slice(0, 12) }));
        assert(layout.scroll <= layout.client + 1, `AI layout at ${width}px: ${JSON.stringify(layout)}`);
    }
    await mobile.setViewportSize({ width: 375, height: 900 });
    await mobile.getByRole('heading', { name: '模型与用量', exact: true }).scrollIntoViewIfNeeded();
    await mobile.screenshot({ path: 'artifacts/agent-access/ai-settings-mobile-final.png' });
    assert.deepEqual(errors, []);
    pass('mobile AI settings and 375/768/1024px layouts without browser errors');
    await request('v1/llm/configuration/update.json', owner.token, { ...configuration, enabled: false, apiKey: '' });
    await writeFile('artifacts/agent-access/ai-settings-http-browser-final.json', JSON.stringify({ passed: checks.length, checks }, null, 2));
    console.log(`AI settings acceptance: ${checks.length} groups passed`);
} finally {
    await browser?.close();
    await new Promise(resolve => mock.close(resolve));
}
