import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const base = 'http://127.0.0.1:18136', mock = 'http://127.0.0.1:18137', output = 'artifacts/ai-review-source-20261006';
for (const [name, images, port] of [['finexy-ai-source-e2e', ['finexy-ai-review-source:20261006-root3', 'finexy-ai-mobile-source:20261007-root2'], '18136'], ['finexy-ai-source-mock-e2e', ['finexy-ai-recognition-mock:20261005-root1'], '18137']]) {
    const info = JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0];
    assert.equal(info.Name, `/${name}`); assert(images.includes(info.Config.Image)); assert.deepEqual(info.Mounts, []);
    assert(info.NetworkSettings.Ports['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === port));
    if (port === '18136') assert(info.Config.Env.includes('EBK_UUID_SERVER_ID=255'));
}
const password = randomBytes(18).toString('hex'), checks = [], errors = [], creates = [], recognitions = [];
let browser, page, financialWrites = 0;
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
async function api(path, token, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, 200, path.split('?')[0]); const data = await response.json(); assert(data.success); return data.result;
}
const audit = () => fetch(`${mock}/audit`).then(r => r.json());
async function control(options = {}) {
    const response = await fetch(`${mock}/control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
        result: { type: 'expense', account: 'SyntheticSourceUSD', category: 'SyntheticSourceExpense', amount: '-5.00' },
        forbidden: ['SyntheticForeignSourceAccount'], required: ['SyntheticSourceUSD', 'SyntheticSourceExpense'], ...options,
    }) }); assert.equal(response.status, 204);
}
try {
    await mkdir(output, { recursive: true });
    const username = `ai_source_${randomBytes(6).toString('hex')}`;
    const owner = await api('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic source QA', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const ledger = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic source ledger' });
    const other = await api('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic foreign source ledger' });
    const body = { category: 2, type: 1, icon: '1', color: '000000', currency: 'USD', balance: 10000, balanceTime: 946684800 };
    const account = await api('v1/accounts/add.json', owner.token, { ...body, ledgerId: ledger.id, name: 'SyntheticSourceUSD' });
    await api('v1/accounts/add.json', owner.token, { ...body, ledgerId: other.id, name: 'SyntheticForeignSourceAccount' });
    const root = await api('v1/transaction/categories/add.json', owner.token, { type: 2, name: 'Synthetic source root', icon: '1', color: '000000' });
    await api('v1/transaction/categories/add.json', owner.token, { type: 2, parentId: root.id, name: 'SyntheticSourceExpense', icon: '1', color: '000000' });
    await control();
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    context.on('page', p => p.on('pageerror', e => errors.push(e.name)));
    context.on('request', req => {
        if (req.url().endsWith('/ai/review/create.json')) creates.push(req.postDataJSON());
        if (req.url().includes('/llm/transactions/recognize_')) recognitions.push(req.url());
        if (req.method() === 'POST' && (req.url().endsWith('/transactions/add.json') || req.url().endsWith('/ai/review/confirm.json') || req.url().endsWith('/ai/review/resolve.json'))) financialWrites++;
    });
    page = await context.newPage(); page.setDefaultTimeout(20000);
    await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(/#\/$/);
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: ledger.name }).click();
    await page.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' });
    const dialog = (p = page) => p.getByRole('dialog').filter({ has: p.locator('.source-dialog') });
    async function open(p = page, restore = false) {
        await p.getByRole('button', { name: restore ? '恢复 AI 来源' : 'AI 文字 / 票据', exact: true }).click();
        await dialog(p).getByRole('status').first().waitFor();
        await dialog(p).getByLabel('需要识别的文字', { exact: true }).or(dialog(p).getByRole('button', { name: /继续编辑来源草稿|重试原保存请求|重新读取原条目/ })).first().waitFor();
        return dialog(p);
    }
    async function resume(p = page) { await dialog(p).getByRole('button', { name: '继续编辑来源草稿', exact: true }).click(); }
    async function saved(p = page) { await dialog(p).getByRole('status').filter({ hasText: '来源已保存到本机' }).waitFor(); }
    async function close(p = page) { await dialog(p).getByRole('button', { name: '关闭并保留本机记录', exact: true }).click(); await dialog(p).waitFor({ state: 'hidden' }); }
    async function closeEditor(p = page) {
        const editor = p.getByRole('dialog').filter({ has: p.locator('.ai-review-confirmation-panel') });
        await editor.waitFor(); await editor.getByRole('button', { name: '取消', exact: true }).click(); await editor.waitFor({ state: 'hidden' });
    }
    let d = await open();
    await d.getByLabel('需要识别的文字', { exact: true }).fill('Synthetic original source 1.'); await saved();
    await page.reload({ waitUntil: 'networkidle' }); await open(page, true); await resume();
    assert.equal(await dialog().getByLabel('需要识别的文字', { exact: true }).inputValue(), 'Synthetic original source 1.');
    assert.equal((await audit()).requests, 0); assert.equal(recognitions.length, 0); assert.equal(creates.length, 0);
    pass('production source reload restores raw text without provider, queue or financial HTTP');
    await dialog().getByRole('button', { name: '发送前核对隐私说明', exact: true }).click(); assert.equal((await audit()).requests, 0);
    await dialog().getByRole('button', { name: '确认发送并识别', exact: true }).click();
    await dialog().locator('.recognition-result').waitFor(); await saved();
    assert.equal((await audit()).requests, 1); assert.equal((await audit()).violations, 0); assert.equal(creates.length, 0);
    assert((await dialog().locator('.recognition-result').innerText()).includes('USD'));
    await page.reload({ waitUntil: 'networkidle' }); await open(page, true); await resume(); await dialog().locator('.recognition-result').waitFor();
    assert.equal((await audit()).requests, 1);
    pass('explicit privacy confirmation uses real ledger provider context; stored recognition survives reload without another call');
    await dialog().getByRole('button', { name: '预览待复核条目', exact: true }).click(); assert.equal(creates.length, 0);
    await page.route('**/ai/review/create.json', async route => { const r = await route.fetch(); assert.equal(r.status(), 200); await r.body(); await route.abort('failed'); });
    await dialog().getByRole('button', { name: '确认保存待复核', exact: true }).click(); await dialog().getByRole('button', { name: '重试原保存请求', exact: true }).waitFor();
    assert.equal(creates.length, 1); const frozen = creates[0]; assert.equal(frozen.ledgerId, ledger.id); assert.equal(frozen.recognizedData.sourceAccountId, account.id);
    await page.unroute('**/ai/review/create.json'); await page.reload({ waitUntil: 'networkidle' }); await open(page, true);
    await dialog().getByRole('button', { name: '重试原保存请求', exact: true }).click(); await dialog().waitFor({ state: 'hidden' });
    await closeEditor(); assert.deepEqual(creates[1], frozen); assert.equal(creates.length, 2);
    assert.equal((await api(`v1/ai/review/list.json?ledgerId=${ledger.id}&status=1&limit=100`, owner.token)).length, 1);
    assert.equal((await api(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, owner.token)).balance, 10000);
    pass('lost committed queue reply restores exact key and payload once, with no balance change');
    await open(); await dialog().getByLabel('需要识别的文字', { exact: true }).fill('Synthetic known receipt source'); await saved();
    await dialog().getByRole('button', { name: '改为手动复核', exact: true }).click();
    await page.route('**/ai/review/get.json?*', route => route.abort('failed'));
    await dialog().getByRole('button', { name: '确认保存待复核', exact: true }).click(); await dialog().getByRole('button', { name: '重新读取原条目', exact: true }).waitFor();
    const count = creates.length; await page.unroute('**/ai/review/get.json?*'); await page.reload({ waitUntil: 'networkidle' }); await open(page, true);
    await dialog().getByRole('button', { name: '重新读取原条目', exact: true }).click(); await closeEditor(); assert.equal(creates.length, count);
    pass('known queue receipt recovery only reads after reload and never repeats creation');
    await open(); await dialog().getByLabel('需要识别的文字', { exact: true }).fill('Synthetic two-tab source'); await saved();
    const second = await context.newPage(); await second.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' }); await open(second, true); await resume(second);
    await dialog(second).getByLabel('需要识别的文字', { exact: true }).fill('Synthetic newer second page'); await saved(second);
    await dialog().getByRole('alert').filter({ hasText: '另一页面' }).waitFor(); assert.equal(await dialog().getByLabel('需要识别的文字').count(), 0);
    await dialog().getByRole('button', { name: '重新读取本机状态', exact: true }).click(); await resume();
    assert.equal(await dialog().getByLabel('需要识别的文字').inputValue(), 'Synthetic newer second page'); await close(second); await second.close();
    pass('actual two-tab source revision conflict locks stale edits and requires explicit restoration');
    await dialog().getByLabel('票据图片', { exact: true }).check(); await saved();
    const png = await page.evaluate(() => { const c = document.createElement('canvas'); c.width = 200; c.height = 100; const ctx = c.getContext('2d'); ctx.fillStyle = '#ff0000'; ctx.fillRect(0, 0, 100, 100); ctx.fillStyle = '#0000ff'; ctx.fillRect(100, 0, 100, 100); return c.toDataURL('image/png').split(',')[1]; });
    await dialog().getByLabel('选择原图片', { exact: true }).setInputFiles({ name: 'synthetic-source.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') }); await saved();
    await dialog().getByRole('button', { name: '缩小范围', exact: true }).click(); await saved();
    await dialog().getByRole('button', { name: '顺时针旋转 90°', exact: true }).click(); await saved();
    await dialog().getByRole('button', { name: '预览裁剪结果', exact: true }).click(); await dialog().getByAltText('将发送给模型的裁剪和旋转结果').waitFor();
    assert.deepEqual(await dialog().getByAltText('将发送给模型的裁剪和旋转结果').evaluate(img => [img.naturalWidth, img.naturalHeight]), [90, 180]);
    const encrypted = await page.evaluate(async () => {
        const db = await new Promise((resolve, reject) => { const r = indexedDB.open('finexy-ai-source-images', 1); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(new Error('Image DB open')); });
        const result = await new Promise((resolve, reject) => { const t = db.transaction('images', 'readonly'), r = t.objectStore('images').getAll(); r.onsuccess = () => resolve(r.result); t.onerror = () => reject(new Error('Image DB read')); }); db.close();
        return result.length === 1 && typeof result[0].data === 'string' && !result[0].data.includes('data:image') && !result[0].data.includes('iVBORw0');
    }); assert(encrypted);
    await page.reload({ waitUntil: 'networkidle' }); await open(page, true); await resume();
    await dialog().getByAltText('本机原票据，裁剪边界见四角控件').waitFor();
    assert((await dialog().innerText()).includes('旋转 90°')); assert.equal(await dialog().getByRole('group', { name: '左上角', exact: true }).getByLabel('横向百分比').inputValue(), '5');
    await dialog().getByRole('button', { name: '预览裁剪结果', exact: true }).click(); await dialog().getByAltText('将发送给模型的裁剪和旋转结果').waitFor();
    assert.deepEqual(await dialog().getByAltText('将发送给模型的裁剪和旋转结果').evaluate(img => [img.naturalWidth, img.naturalHeight]), [90, 180]);
    pass('real encrypted IndexedDB image and normalized corners restore; perspective crop and 90 degree output dimensions match');
    await dialog().screenshot({ path: `${output}/source-image-crop.png` });
    await control(); await dialog().getByRole('button', { name: '发送前核对隐私说明', exact: true }).click(); assert.equal((await audit()).requests, 0);
    await dialog().getByRole('button', { name: '确认发送并识别', exact: true }).click(); await dialog().locator('.recognition-result').waitFor(); await saved();
    assert.equal((await audit()).requests, 1); assert.equal((await audit()).violations, 0);
    await dialog().getByRole('button', { name: '预览待复核条目', exact: true }).click();
    await dialog().getByRole('button', { name: '确认保存待复核', exact: true }).click(); await closeEditor();
    assert.equal(creates.at(-1).sourceType, 2); assert(!JSON.stringify(creates.at(-1)).includes('base64'));
    const remaining = await page.evaluate(async () => { const db = await new Promise(resolve => { const r = indexedDB.open('finexy-ai-source-images', 1); r.onsuccess = () => resolve(r.result); }); const count = await new Promise(resolve => { const r = db.transaction('images', 'readonly').objectStore('images').count(); r.onsuccess = () => resolve(r.result); }); db.close(); return count; }); assert.equal(remaining, 0);
    pass('explicit image model call uses real ledger context; queue stores structured source and acknowledged creation clears encrypted original');
    await open(); await dialog().getByLabel('需要识别的文字').fill('Synthetic cancel source'); await saved(); await control({ hold: true });
    await dialog().getByRole('button', { name: '发送前核对隐私说明', exact: true }).click(); await dialog().getByRole('button', { name: '确认发送并识别', exact: true }).click();
    await page.waitForFunction(() => document.body.textContent.includes('正在识别'));
    for (let i = 0; i < 80 && !(await audit()).requests; i++) await new Promise(r => setTimeout(r, 100)); assert.equal((await audit()).requests, 1);
    await dialog().getByRole('button', { name: '取消识别，保留来源', exact: true }).click();
    const canceled = page.waitForResponse(r => r.url().includes('/recognize_text.json')); await fetch(`${mock}/release`, { method: 'POST' }); await canceled;
    assert.equal(await dialog().locator('.recognition-result').count(), 0); await close(); await open(page, true); await resume();
    assert.equal(await dialog().getByLabel('需要识别的文字').inputValue(), 'Synthetic cancel source');
    pass('cancel during actual held provider request preserves source and ignores late recognition without queue or financial writes');
    await close(); await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: other.name }).click();
    await page.getByRole('button', { name: 'AI 文字 / 票据', exact: true }).waitFor(); assert.equal(await page.getByRole('button', { name: '恢复 AI 来源', exact: true }).count(), 0);
    await open(); assert.equal(await dialog().getByLabel('需要识别的文字').inputValue(), ''); await close();
    pass('switching ledger clears source recovery and another ledger cannot inherit original image or text');
    async function switchTo(name) { await page.getByRole('button', { name: '切换当前账本', exact: true }).click(); await page.getByRole('option').filter({ hasText: name }).click(); }
    await switchTo(ledger.name); await open(page, true); await resume(); await control({ hold: true });
    await dialog().getByRole('button', { name: '发送前核对隐私说明', exact: true }).click(); await dialog().getByRole('button', { name: '确认发送并识别', exact: true }).click();
    for (let i = 0; i < 80 && !(await audit()).requests; i++) await new Promise(r => setTimeout(r, 100)); assert.equal((await audit()).requests, 1);
    await close(); await switchTo(other.name); await open();
    const staleReply = page.waitForResponse(r => r.url().includes('/recognize_text.json')); await fetch(`${mock}/release`, { method: 'POST' }); await staleReply;
    assert.equal(await dialog().getByLabel('需要识别的文字').inputValue(), ''); assert.equal(await dialog().locator('.recognition-result').count(), 0);
    await close(); await switchTo(ledger.name); await open(page, true); await resume();
    assert.equal(await dialog().getByLabel('需要识别的文字').inputValue(), 'Synthetic cancel source');
    pass('held provider reply after closing and reopening another ledger cannot replace or close the new source page');
    const callsBeforeQuota = recognitions.length;
    await page.evaluate(() => { window.syntheticSourceStorage = Storage.prototype.setItem; Storage.prototype.setItem = function(key, value) { if (key.includes('ai-review-source')) throw new DOMException('Synthetic source quota denied', 'QuotaExceededError'); return window.syntheticSourceStorage.call(this, key, value); }; });
    await dialog().getByLabel('需要识别的文字').fill('Synthetic unsaved quota source'); await dialog().getByRole('alert').filter({ hasText: 'quota' }).waitFor();
    assert(await dialog().getByRole('button', { name: '发送前核对隐私说明', exact: true }).isDisabled()); assert.equal(recognitions.length, callsBeforeQuota);
    await page.evaluate(() => { Storage.prototype.setItem = window.syntheticSourceStorage; delete window.syntheticSourceStorage; });
    await dialog().getByRole('button', { name: '重新读取本机状态', exact: true }).click(); await resume();
    assert.equal(await dialog().getByLabel('需要识别的文字').inputValue(), 'Synthetic cancel source'); await close();
    pass('actual storage quota failure locks upload and creation and preserves the last durable source');
    await switchTo('默认个人账本'); await open(); await dialog().getByLabel('需要识别的文字').fill('Synthetic personal manual source'); await saved();
    await dialog().getByRole('button', { name: '改为手动复核', exact: true }).click(); await dialog().getByRole('button', { name: '确认保存待复核', exact: true }).click(); await closeEditor();
    assert.equal(creates.at(-1).ledgerId, '0'); assert.equal(creates.at(-1).recognizedData, undefined);
    pass('default personal source preserves ledger ID 0 and explicit manual queue creation without recognition or financial writes');
    await switchTo(ledger.name); await page.getByRole('button', { name: '新增交易', exact: true }).click();
    const manual = page.getByRole('dialog').filter({ has: page.locator('.transaction-edit-body') });
    await manual.locator('.transaction-ai-image-action').click(); await dialog().getByRole('button', { name: '继续编辑来源草稿', exact: true }).waitFor();
    await close(); assert.equal(await manual.count(), 1);
    await manual.getByLabel(/^支出金额/).fill('12.34'); await manual.locator('.transaction-ai-image-action').click();
    await page.getByText('当前手动表单有输入，请先保存或取消，再使用 AI 来源录入', { exact: true }).waitFor();
    assert.equal(await dialog().count(), 0); assert.equal(await manual.getByLabel(/^支出金额/).inputValue(), '12.34');
    pass('ordinary editor AI image action uses durable ledger source dialog and protects existing manual input');
    assert.equal(financialWrites, 0); assert.deepEqual(errors, []);
    await writeFile(`${output}/browser-summary.json`, JSON.stringify({ checks, pageErrors: errors, queueCreationRequests: creates.length, recognitionRequests: recognitions.length, financialWrites, productionDeployment: false }, null, 2));
    console.log(`PASS ${checks.length} production source checks`);
} catch (error) {
    if (page) await page.screenshot({ path: `${output}/failure.png`, fullPage: true }).catch(() => {});
    console.error(error); process.exitCode = 1;
} finally { if (browser) await browser.close(); }
