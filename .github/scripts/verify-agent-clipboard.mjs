import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Never generate test credentials on a real service or a container with data mounts.
const base = 'http://127.0.0.1:18110';
const info = JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP finexy-http.test 127.0.0.1', '--no-proxy-server'] });
const password = randomBytes(18).toString('hex');
const username = `clipboard_${randomBytes(6).toString('hex')}`;
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
try {
    const registered = await fetch(`${base}/api/register.json`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, email: `${username}@example.invalid`, nickname: '复制验收', password, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] }) });
    assert.equal((await registered.json()).success, true);
    const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    // Serve the same isolated app under a genuinely insecure origin without external DNS/proxies.
    await context.route('http://finexy-http.test:18110/**', async route => {
        try {
            const response = await route.fetch({ url: route.request().url().replace('http://finexy-http.test:18110', base) });
            await route.fulfill({ response });
        } catch {
            // Browser teardown can interrupt background requests. Never log request headers.
            await route.abort().catch(() => {});
        }
    });
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: base });
    const reader = await context.newPage();
    await reader.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', () => errors.push('pageerror'));
    await page.goto('http://finexy-http.test:18110/desktop#/login', { waitUntil: 'networkidle' });
    assert.equal(await page.evaluate(() => isSecureContext), false);
    assert.equal(await page.evaluate(() => !!navigator.clipboard), false);
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(/#\/$/);
    await page.goto('http://finexy-http.test:18110/desktop#/user/settings?tab=aiSetting', { waitUntil: 'networkidle' });
    await page.getByLabel('Finexy 登录密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '生成令牌', exact: true }).click();
    const field = page.getByLabel('完整令牌', { exact: true });
    await field.waitFor();
    // Test credentials remain in memory; never include them in logs or screenshots.
    const secret = await field.inputValue();
    await page.getByRole('button', { name: '复制令牌', exact: true }).click();
    await page.getByText('令牌已复制', { exact: true }).waitFor();
    assert(await reader.evaluate(() => navigator.clipboard.readText()) === secret, 'complete token copied');
    assert.equal(await page.evaluate(() => document.querySelectorAll('textarea').length), 1, 'temporary copying node removed');
    pass('HTTP without Clipboard API copies complete token to real clipboard');
    await page.getByRole('button', { name: '全选令牌', exact: true }).click();
    assert.equal(await field.evaluate(el => el.selectionStart === 0 && el.selectionEnd === el.value.length && document.activeElement === el), true);
    await page.keyboard.press('Control+C');
    assert(await reader.evaluate(() => navigator.clipboard.readText()) === secret, 'complete token copied');
    pass('select-token button and native keyboard copy');
    await page.getByRole('button', { name: '复制 Codex 配置', exact: true }).click();
    await page.getByText('配置已复制，未包含令牌', { exact: true }).waitFor();
    assert.equal((await reader.evaluate(() => navigator.clipboard.readText())).replaceAll('\r\n', '\n'), await page.locator('.agent-guide pre').innerText());
    pass('HTTP configuration copies exact text without token');
    await page.evaluate(() => { window.savedCopyCommand = document.execCommand; document.execCommand = () => false; });
    await page.getByRole('button', { name: '复制令牌', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '未能自动复制，已全选内容' }).waitFor();
    assert.equal(await field.evaluate(el => el.selectionStart === 0 && el.selectionEnd === el.value.length && document.activeElement === el), true);
    assert.equal(await page.getByText('配置已复制，未包含令牌', { exact: true }).count(), 0);
    await page.getByRole('button', { name: '复制 Codex 配置', exact: true }).click();
    assert.equal(await page.evaluate(() => getSelection().toString() === document.querySelector('.agent-guide pre').textContent), true);
    await page.evaluate(() => { document.execCommand = window.savedCopyCommand; delete window.savedCopyCommand; });
    await page.getByRole('button', { name: '复制令牌', exact: true }).click();
    await page.getByText('令牌已复制', { exact: true }).waitFor();
    assert.equal(await page.getByRole('alert').count(), 0);
    pass('blocked copy selects correct content, clears stale success and recovers');
    await page.getByRole('button', { name: '关闭令牌显示', exact: true }).click();
    assert.equal(await field.count(), 0);
    assert(!(await page.locator('.agent-access').innerText()).includes(secret));
    pass('closing display removes token');
    await page.setViewportSize({ width: 1440, height: 1000 });
    const mobile = await context.newPage();
    mobile.on('pageerror', () => errors.push('mobile-pageerror'));
    await mobile.goto('http://finexy-http.test:18110/mobile', { waitUntil: 'networkidle' });
    await mobile.waitForFunction(() => document.querySelector('a[href="/settings"]'));
    await mobile.locator('a[href="/settings"]').click();
    await mobile.locator('a[href="/user/ai-settings"]').click();
    await mobile.getByRole('heading', { name: 'Agent 接入', exact: true }).waitFor();
    await mobile.getByLabel('Finexy 登录密码', { exact: true }).fill(password);
    await mobile.getByRole('button', { name: '生成令牌', exact: true }).click();
    await mobile.getByLabel('完整令牌', { exact: true }).waitFor();
    const mobileSecret = await mobile.getByLabel('完整令牌', { exact: true }).inputValue();
    for (const width of [375, 768, 1024]) {
        await mobile.setViewportSize({ width, height: 900 });
        await mobile.getByRole('button', { name: '复制令牌', exact: true }).click();
        await mobile.getByText('令牌已复制', { exact: true }).waitFor();
        assert(await reader.evaluate(() => navigator.clipboard.readText()) === mobileSecret, 'complete mobile token copied');
        assert.equal(await mobile.locator('.agent-access').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    }
    await mobile.getByRole('button', { name: '关闭令牌显示', exact: true }).click();
    pass('mobile HTTP copy at 375/768/1024px without overflow');
    await reader.getByLabel('用户名', { exact: true }).fill(username);
    await reader.getByLabel('密码', { exact: true }).fill(password);
    await reader.getByRole('button', { name: '登录', exact: true }).click();
    await reader.waitForURL(/#\/$/);
    await reader.goto(`${base}/desktop#/user/settings?tab=aiSetting`, { waitUntil: 'networkidle' });
    await reader.getByLabel('Finexy 登录密码', { exact: true }).fill(password);
    await reader.getByRole('button', { name: '生成令牌', exact: true }).click();
    await reader.getByLabel('完整令牌', { exact: true }).waitFor();
    const secureSecret = await reader.getByLabel('完整令牌', { exact: true }).inputValue();
    await reader.getByRole('button', { name: '复制令牌', exact: true }).click();
    await reader.getByText('令牌已复制', { exact: true }).waitFor();
    assert(await reader.evaluate(() => navigator.clipboard.readText()) === secureSecret, 'complete secure token copied');
    await reader.evaluate(() => { navigator.clipboard.writeText = async () => { throw new DOMException('Denied', 'NotAllowedError'); }; });
    await reader.getByRole('button', { name: '复制 Codex 配置', exact: true }).click();
    await reader.getByText('配置已复制，未包含令牌', { exact: true }).waitFor();
    assert.equal((await reader.evaluate(() => navigator.clipboard.readText())).replaceAll('\r\n', '\n'), await reader.locator('.agent-guide pre').innerText());
    await reader.getByRole('button', { name: '关闭令牌显示', exact: true }).click();
    pass('secure Clipboard API and permission-denied fallback');
    assert.deepEqual(errors, []);
    await mkdir('artifacts/agent-access', { recursive: true });
    await writeFile('artifacts/agent-access/clipboard-final.json', JSON.stringify({ passed: checks.length, checks }, null, 2));
} catch (error) {
    // Playwright errors can include Authorization headers. Keep even synthetic tokens private.
    throw new Error(String(error.message).replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[redacted]').replaceAll(password, '[redacted]'));
} finally {
    for (const context of browser.contexts()) await context.unrouteAll({ behavior: 'ignoreErrors' });
    await browser.close();
}
