import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
    ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const baseUrl = process.env.FINEXY_VERIFY_URL || 'http://127.0.0.1:18089';
const packageInfo = JSON.parse(await readFile(new URL('../../package.json', import.meta.url), 'utf8'));
const expectedVersion = process.env.FINEXY_EXPECTED_VERSION || packageInfo.version;
const healthResponse = await fetch(`${baseUrl}/healthz.json`, { cache: 'no-store' });
assert.equal(healthResponse.status, 200, 'server health HTTP status');
const health = await healthResponse.json();
assert.equal(health.success, true, 'server health response');
assert.equal(health.result?.version, expectedVersion, 'deployed backend release version');
const versionPattern = new RegExp(`v${expectedVersion.replaceAll('.', '\\.')}\\b`);
const browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {})
});

try {
    for (const route of ['/desktop', '/mobile']) {
        const context = await browser.newContext();
        try {
            const page = await context.newPage();
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            page.on('console', message => {
                if (message.type() === 'error') errors.push(message.text());
            });
            const response = await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle' });
            assert.equal(response.status(), 200, `${route}: HTTP status`);
            await page.waitForFunction(() => document.querySelector('#app')?.children.length > 0
                && /log.?in|sign.?in|登录/i.test(document.body.innerText));
            assert.match(await page.locator('body').innerText(), versionPattern, `${route}: frontend release version`);
            assert.deepEqual(errors, [], `${route}: browser errors`);
            console.log(`${route}: frontend/backend ${expectedVersion}, login mounted, no browser errors`);
        } finally {
            await context.close();
        }
    }
} finally {
    await browser.close();
}
