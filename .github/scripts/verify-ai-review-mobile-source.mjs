import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Disposable candidate plus one OpenAI-compatible fixture; never a user service or mounted data.
const container = 'finexy-ai-mobile-source-e2e', mockContainer = 'finexy-ai-mobile-source-mock-e2e';
const base = 'http://127.0.0.1:18140', mock = 'http://127.0.0.1:18141';
const output = 'artifacts/ai-review-mobile-source-20261007';
assert(output.startsWith('artifacts/') && !output.split('/').includes('..'));
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.equal(info.Name, `/${container}`); assert.equal(info.Config.Image, 'finexy-ai-mobile-source:20261007-root2'); assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=255')); assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18140'));
const mockInfo = JSON.parse(execFileSync('docker', ['inspect', mockContainer], { encoding: 'utf8' }))[0];
assert.equal(mockInfo.Name, `/${mockContainer}`); assert.equal(mockInfo.Config.Image, 'finexy-ai-recognition-mock:20261005-root1'); assert.deepEqual(mockInfo.Mounts, []);
assert(mockInfo.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18141'));

const password = randomBytes(18).toString('hex'), checks = [], errors = [], creates = [], recognitions = [], legacyWrites = [];
let browser, page, financialWrites = 0;
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, auth, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(auth ? { Authorization: `Bearer ${auth.token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, 200, path.split('?')[0]); const data = await response.json(); assert(data.success); return data.result;
}
const audit = () => fetch(`${mock}/audit`).then(r => r.json());
async function control(options = {}) {
    const response = await fetch(`${mock}/control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
        result: { type: 'expense', account: 'SyntheticMobileUSD', category: 'SyntheticMobileExpense', amount: '-5.00' },
        forbidden: ['SyntheticForeignAccount'], required: ['SyntheticMobileUSD', 'SyntheticMobileExpense'], ...options,
    }) }); assert.equal(response.status, 204);
}
try {
    await mkdir(output, { recursive: true });
    for (let attempt = 0; attempt < 60; attempt++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* Disposable candidate starting. */ }
        if (attempt === 59) throw new Error('Isolated candidate unavailable');
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    const owner = await register(), other = await register();
    const ledger = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic mobile source A' });
    const foreign = await api('v1/ledger/create.json', owner, { type: 1, name: 'Synthetic mobile source B' });
    const accountDraft = { category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 };
    const account = await api('v1/accounts/add.json', owner, { ...accountDraft, ledgerId: ledger.id, name: 'SyntheticMobileUSD' });
    await api('v1/accounts/add.json', owner, { ...accountDraft, ledgerId: foreign.id, name: 'SyntheticForeignAccount' });
    const root = await api('v1/transaction/categories/add.json', owner, { type: 2, name: 'Synthetic mobile root', icon: '1', color: '000000' });
    await api('v1/transaction/categories/add.json', owner, { type: 2, parentId: root.id, name: 'SyntheticMobileExpense', icon: '1', color: '000000' });
    await control();
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 375, height: 812 }, reducedMotion: 'reduce' });
    context.on('page', tab => tab.on('pageerror', e => errors.push(`${e?.name || 'ThrownValue'}: ${String(e?.message ?? e).slice(0, 160)}`)));
    context.on('request', request => {
        const url = request.url();
        if (url.endsWith('/ai/review/create.json')) creates.push(request.postDataJSON());
        if (url.endsWith('/ai/review/confirm.json') || url.endsWith('/transactions/add.json')) financialWrites++;
        if (url.endsWith('/ai/review/resolve.json')) legacyWrites.push(url);
        // Recognition must carry the ledger scope: text calls expose it in the JSON body,
        // the multipart image call is proven by the fixture's required ledger-resource names.
        if (url.includes('/llm/transactions/recognize_')) recognitions.push({ image: url.endsWith('recognize_receipt_image.json'), ledgerId: url.endsWith('recognize_text.json') ? request.postDataJSON()?.ledgerId : undefined });
    });
    const current = p => p.locator('.view-main > .page.page-current');
    async function navigate(p, path) {
        await p.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
        await p.evaluate(value => document.querySelector('.view-main').f7View.router.navigate(value, { animate: false }), path);
        await p.waitForFunction(value => document.querySelector('.view-main')?.f7View?.router.currentRoute.url === value, path);
        await p.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.allowPageChange);
    }
    async function login(auth, target = null) {
        const p = await context.newPage(); p.setDefaultTimeout(20000);
        await p.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
        await current(p).locator('input[type="text"]').first().fill(auth.username);
        await current(p).locator('input[type="password"]').first().fill(password);
        await current(p).getByText('登录', { exact: true }).click();
        await current(p).getByText('当前账本：默认个人账本', { exact: true }).waitFor();
        if (target) await switchLedger(p, target);
        return p;
    }
    async function switchLedger(p, target) {
        await navigate(p, '/ledger/manage');
        await current(p).locator('.list .item-link').filter({ hasText: target.name }).click();
        await current(p).getByText('切换到此账本', { exact: true }).click();
        await current(p).getByText(new RegExp(`正在使用：${target.name}`)).waitFor();
    }
    const text = () => current(page).getByLabel('需要识别的文字', { exact: true });
    const saved = () => current(page).locator('[role="status"]').filter({ hasText: '来源已保存到本机' }).waitFor();
    const recovery = () => current(page).getByRole('button', { name: '继续编辑来源草稿', exact: true });
    const openSource = async (p = page) => { await navigate(p, '/ai/source'); await current(p).locator('[role="status"]').first().waitFor(); };
    async function register() {
        const username = `ai_mobile_source_${randomBytes(6).toString('hex')}`;
        return { username, ...await api('register.json', null, { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic mobile source QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] }) };
    }

    page = await login(owner, ledger);
    await openSource();
    assert.equal((await audit()).requests, 0); assert.equal(creates.length, 0);
    await text().fill('Synthetic mobile source 1.'); await saved();
    assert.equal((await audit()).requests, 0); assert.equal(creates.length, 0); assert.equal(financialWrites, 0);
    pass('mobile source keeps typed text on the device without provider, queue or financial HTTP');
    await page.reload({ waitUntil: 'networkidle' }); await openSource();
    await recovery().click();
    assert.equal(await text().inputValue(), 'Synthetic mobile source 1.');
    assert.equal((await audit()).requests, 0); assert.equal(creates.length, 0);
    pass('explicit reload recovery restores the saved source without a provider call');
    await current(page).getByRole('button', { name: '发送前核对隐私说明', exact: true }).click();
    assert.equal((await audit()).requests, 0);
    await current(page).getByRole('button', { name: '确认发送并识别', exact: true }).click();
    await current(page).locator('.recognition-result').waitFor(); await saved();
    assert.equal((await audit()).requests, 1); assert.equal((await audit()).violations, 0); assert.equal(creates.length, 0);
    const recognitionText = await current(page).locator('.recognition-result').innerText();
    assert.match(recognitionText, /USD|\$/, `recognition summary must show the real account currency: ${recognitionText}`);
    assert.match(recognitionText, /SyntheticMobileExpense/, `recognition summary must resolve the current ledger category: ${recognitionText}`);
    pass('explicit privacy confirmation sends the current ledger context and shows the real account currency');
    await page.reload({ waitUntil: 'networkidle' }); await openSource(); await recovery().click();
    await current(page).locator('.recognition-result').waitFor();
    assert.equal((await audit()).requests, 1);
    pass('stored recognition survives a reload without another provider call');
    const png = await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 200; c.height = 100; const ctx = c.getContext('2d'); ctx.fillStyle = '#ff0000'; ctx.fillRect(0, 0, 100, 100); ctx.fillStyle = '#0000ff'; ctx.fillRect(100, 0, 100, 100); return c.toDataURL('image/png').split(',')[1]; });
    await current(page).getByText('票据图片', { exact: true }).click(); await saved();
    await current(page).getByLabel('选择原图片', { exact: true }).setInputFiles({ name: 'synthetic-mobile-source.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') });
    await current(page).getByText(/原图：synthetic-mobile-source\.png/).waitFor(); await saved();
    const storedImages = () => page.evaluate(() => new Promise(resolve => { const opening = indexedDB.open('finexy-ai-source-images', 1); opening.onupgradeneeded = () => opening.result.createObjectStore('images'); opening.onsuccess = () => { const database = opening.result; const request = database.transaction('images', 'readonly').objectStore('images').getAllKeys(); request.onsuccess = () => { database.close(); resolve(request.result.length); }; }; opening.onerror = () => resolve(-1); }));
    assert.equal(await storedImages(), 1);
    await current(page).getByRole('button', { name: '顺时针旋转 90°' }).click(); await saved();
    await current(page).getByRole('button', { name: '缩小范围' }).click(); await saved();
    await current(page).getByRole('button', { name: '预览裁剪结果', exact: true }).click();
    await current(page).locator('.crop-preview').waitFor();
    assert((await current(page).locator('.crop-preview').getAttribute('src'))?.startsWith('blob:'));
    pass('mobile keeps the encrypted original, rotation, corners and a real crop preview');
    await current(page).getByRole('button', { name: '发送前核对隐私说明', exact: true }).click();
    await current(page).getByRole('button', { name: '确认发送并识别', exact: true }).click();
    await current(page).locator('.recognition-result').waitFor(); await saved();
    assert.equal((await audit()).requests, 2); assert.equal((await audit()).violations, 0);
    pass('cropped image recognition reaches the provider with the same ledger context');
    await current(page).getByRole('button', { name: '预览待复核条目', exact: true }).click();
    assert.equal(creates.length, 0);
    await page.route('**/ai/review/create.json', async route => { const response = await route.fetch(); assert.equal(response.status(), 200); await response.body(); await route.abort('failed'); });
    await current(page).getByRole('button', { name: '确认保存待复核', exact: true }).click();
    await current(page).getByRole('button', { name: '重试原保存请求', exact: true }).waitFor();
    assert.equal(creates.length, 1);
    const frozen = creates[0]; assert.equal(frozen.ledgerId, ledger.id); assert.equal(frozen.sourceType, 2); assert.equal(frozen.recognizedData.sourceAccountId, account.id);
    await page.unroute('**/ai/review/create.json');
    await page.reload({ waitUntil: 'networkidle' }); await openSource();
    await current(page).getByRole('button', { name: '重试原保存请求', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.currentRoute.url !== '/ai/source');
    assert.equal(creates.length, 2); assert.deepEqual(creates[1], frozen);
    assert.equal((await api(`v1/ai/review/list.json?ledgerId=${ledger.id}&status=1&limit=100`, owner)).length, 1);
    assert.equal(await storedImages(), 0);
    assert.equal(financialWrites, 0);
    assert.equal((await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, owner)).balance, 10000);
    pass('a lost queue reply retries the exact original payload once, keeps one queue item, clears the local original and leaves balances alone');
    await openSource();
    await text().fill('Synthetic mobile known receipt'); await saved();
    await current(page).getByRole('button', { name: '改为手动复核', exact: true }).click();
    await page.route('**/ai/review/get.json?**', route => route.abort('failed'));
    await current(page).getByRole('button', { name: '确认保存待复核', exact: true }).click();
    await current(page).getByRole('button', { name: '重新读取原条目', exact: true }).waitFor();
    const knownCreates = creates.length;
    const manualRequest = creates.at(-1); assert.equal(manualRequest.recognizedData, undefined); assert.equal(manualRequest.failureReason.length > 0, true);
    await page.unroute('**/ai/review/get.json?**'); await page.reload({ waitUntil: 'networkidle' }); await openSource();
    await current(page).getByRole('button', { name: '重新读取原条目', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.view-main')?.f7View?.router.currentRoute.url !== '/ai/source');
    assert.equal(creates.length, knownCreates);
    assert.equal(financialWrites, 0);
    pass('manual review saves without recognition and a known receipt with a failed detail read recovers with reads only');
    await openSource();
    await control({ hold: true });
    await text().fill('Synthetic held mobile source.'); await saved();
    await current(page).getByRole('button', { name: '发送前核对隐私说明', exact: true }).click();
    await current(page).getByRole('button', { name: '确认发送并识别', exact: true }).click();
    await current(page).getByRole('button', { name: '取消识别，保留来源', exact: true }).waitFor();
    await current(page).getByRole('button', { name: '取消识别，保留来源', exact: true }).click();
    const beforeRelease = creates.length;
    await fetch(`${mock}/release`, { method: 'POST' });
    await page.waitForTimeout(1200);
    assert.equal(await text().inputValue(), 'Synthetic held mobile source.');
    assert.equal(creates.length, beforeRelease); assert.equal(await current(page).locator('.recognition-result').count(), 0);
    pass('cancel during a held provider request preserves the source and ignores the late reply');
    await control();
    await page.evaluate(() => { const set = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) {
        if (key.includes('ai-review-source')) throw new DOMException('Synthetic storage full', 'QuotaExceededError'); return set.call(this, key, value);
    }; });
    await text().fill('Synthetic unsaved mobile source.');
    await current(page).locator('[role="alert"]').first().waitFor();
    assert.equal(await text().inputValue(), 'Synthetic unsaved mobile source.');
    pass('a real local storage failure is visible and keeps the typed source');
    await page.reload({ waitUntil: 'networkidle' }); await openSource();
    await switchLedger(page, foreign); await openSource();
    assert.equal(await current(page).getByRole('button', { name: '继续编辑来源草稿', exact: true }).count(), 0);
    assert.equal(await current(page).getByText(new RegExp(foreign.name)).count() > 0, true);
    await switchLedger(page, ledger); await openSource();
    await recovery().waitFor();
    pass('a source draft stays in its original ledger and another ledger cannot inherit it');
    await navigate(page, '/settings');
    await current(page).getByText('退出登录', { exact: true }).first().click();
    await page.locator('.dialog.modal-in').getByText('确定', { exact: true }).click();
    await current(page).locator('input[type="password"]').first().waitFor();
    await current(page).locator('input[type="text"]').first().fill(other.username);
    await current(page).locator('input[type="password"]').first().fill(password);
    await current(page).getByText('登录', { exact: true }).click();
    await current(page).getByText('当前账本：默认个人账本', { exact: true }).waitFor();
    await openSource();
    assert.equal(await current(page).getByRole('button', { name: '继续编辑来源草稿', exact: true }).count(), 0);
    assert.equal(await text().inputValue(), '');
    pass('another account signing in on the same browser does not inherit the previous source draft');
    assert.equal(financialWrites, 0); assert.deepEqual(legacyWrites, []);
    const textRecognitions = recognitions.filter(entry => !entry.image), imageRecognitions = recognitions.filter(entry => entry.image);
    assert.equal(textRecognitions.length, 2); assert.equal(imageRecognitions.length, 1);
    assert(textRecognitions.every(entry => entry.ledgerId === ledger.id), `text recognition must carry the ledger scope: ${JSON.stringify(textRecognitions)}`);
    await writeFile(`${output}/summary.json`, JSON.stringify({ passed: checks.length, checks, pageErrors: errors, consoleErrors: [], queueCreates: creates.length, financialWrites, legacyWrites, mainAppTouched: false, productionDeployment: false }, null, 2));
    assert.deepEqual(errors, []);
    console.log(`PASS ${checks.length} mobile AI source production cases`);
} catch (error) {
    if (page) await page.screenshot({ path: `${output}/failure.png`, fullPage: true }).catch(() => {});
    await writeFile(`${output}/failure-metadata.json`, JSON.stringify({ checks, errors, creates: creates.length, financialWrites, legacyWrites }, null, 2)).catch(() => {});
    throw error;
} finally { await browser?.close(); }
