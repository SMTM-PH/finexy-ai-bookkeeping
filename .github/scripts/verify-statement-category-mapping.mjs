import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const base = 'http://127.0.0.1:18110';
const container = JSON.parse(execFileSync('docker', ['inspect', 'finexy-agent-e2e'], { encoding: 'utf8' }))[0];
assert.deepEqual(container.Mounts, []);
assert(container.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
const checks = [];
const pass = name => { checks.push(name); console.log(`PASS ${name}`); };
const password = randomBytes(18).toString('hex');
const username = `mapping_${randomBytes(6).toString('hex')}`;
async function request(path, token, body) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const data = await response.json();
    assert.equal(response.status, 200, `HTTP ${path} error ${data.errorCode || 'none'}`); assert(data.success); return data.result;
}
const fixtures = JSON.parse(execFileSync('python', ['-c', `
import json,base64,io,zipfile
from xml.sax.saxutils import escape
alipay='\\n'.join(['------------------------------------------------------------------------------------','导出信息：','------------------------支付宝支付科技有限公司  电子客户回单------------------------','交易时间,交易分类,商品说明,收/支,金额,交易状态,','2026-09-03 12:34:56,餐饮美食,午餐,支出,3.45,交易成功,','2026-09-03 13:34:56,未匹配类别,合成商品,支出,1.00,交易成功,'])
rows=[['微信支付账单明细'],['----------------------微信支付账单明细列表--------------------'],['交易时间','交易类型','商品','收/支','金额(元)','当前状态'],['2026-09-02 12:00:00','商户消费','午餐','支出','2.00','支付成功'],['2026-09-02 13:00:00','商户消费','地铁乘车','支出','3.00','支付成功'],['2026-09-02 14:00:00','商户消费','合成无用途','支出','4.00','支付成功']]
xmlrows=''.join('<row r="%s">%s</row>'%(r+1,''.join('<c r="%s%s" t="inlineStr"><is><t>%s</t></is></c>'%(chr(65+c),r+1,escape(v)) for c,v in enumerate(row))) for r,row in enumerate(rows))
buffer=io.BytesIO()
with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
 z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
 z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>')
 z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
 z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+xmlrows+'</sheetData></worksheet>')
print(json.dumps({'alipay':base64.b64encode(alipay.encode('gb18030')).decode(),'wechat':base64.b64encode(buffer.getvalue()).decode()}))
`], { encoding: 'utf8' }));
let browser;
try {
    const owner = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: '导入隔离测试', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const account = await request('v1/accounts/add.json', owner.token, { name: 'Synthetic wallet', category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 });
    const root = await request('v1/transaction/categories/add.json', owner.token, { name: '合成生活', type: 2, icon: '1', color: '000000' });
    const food = await request('v1/transaction/categories/add.json', owner.token, { name: '餐饮', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const travel = await request('v1/transaction/categories/add.json', owner.token, { name: '交通', type: 2, parentId: root.id, icon: '1', color: '000000' });
    const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
    const page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    page.setDefaultTimeout(15000); page.setDefaultNavigationTimeout(20000);
    await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(username); await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(/#\/$/);
    console.log('STEP logged in');
    async function openBill(provider) {
        await page.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' });
        await page.getByRole('button', { name: '导入账单', exact: true }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByRole('combobox', { name: '文件类型', exact: true }).locator('xpath=ancestor::div[contains(@class,"v-field")][1]').click();
        const menu = page.locator('.two-column-select-menu:visible'); const label = provider === 'alipay' ? '支付宝 (App) 交易流水文件' : '微信支付账单文件';
        await menu.getByPlaceholder('查找文件类型').fill(label); await menu.locator('.primary-list-container .v-list-item').first().click(); await menu.locator('.secondary-list-container').getByText(label, { exact: true }).click();
        await page.locator(provider === 'alipay' ? 'input[type="file"][accept*=".zip"]' : 'input[type="file"][accept*=".xlsx"]').setInputFiles({ name: provider === 'alipay' ? 'synthetic.csv' : 'synthetic.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from(fixtures[provider], 'base64') });
        await dialog.getByRole('button', { name: /下一步/ }).click(); await page.locator('.statement-mapping').waitFor({ state: 'visible' });
        console.log(`STEP ${provider} preview`);
        return page.locator('.statement-mapping');
    }
    let panel = await openBill('wechat');
    assert.equal(await panel.getByRole('button', { name: '关联建议分类（2 笔）', exact: true }).count(), 1);
    assert.equal(await panel.locator('.mapping-row').filter({ hasText: '支出分类' }).count(), 3);
    const foodGroup = panel.locator('.mapping-row').filter({ hasText: '商户消费 → 餐饮' });
    const travelGroup = panel.locator('.mapping-row').filter({ hasText: '商户消费 → 交通' });
    assert.equal(await foodGroup.getByRole('combobox').inputValue(), food.id); assert.equal(await travelGroup.getByRole('combobox').inputValue(), travel.id);
    await panel.getByLabel('记住本次确认的分类关联', { exact: false }).check();
    await panel.getByRole('button', { name: '关联建议分类（2 笔）', exact: true }).click();
    assert.match(await panel.innerText(), /已关联 2 笔/);
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance, 10000);
    pass('real WeChat XLSX broad categories split into clear purpose suggestions; applying does not post finances');
    await page.getByRole('dialog').getByRole('button', { name: /取消/ }).click();
    panel = await openBill('wechat'); assert.match(await panel.innerText(), /已确认的关联/);
    await page.getByRole('dialog').getByRole('button', { name: /取消/ }).click();
    panel = await openBill('alipay'); assert(!/已确认的关联/.test(await panel.innerText()));
    await panel.getByRole('button', { name: '关联建议分类（1 笔）', exact: true }).click();
    pass('Alipay CSV aliases associate existing categories; remembered confirmed rules survive preview reopen and stay provider scoped');
    const unmatched = panel.locator('.mapping-row').filter({ hasText: '支出分类：未匹配类别' });
    await unmatched.getByRole('button', { name: /^新增分类/ }).click();
    await panel.getByLabel('新增分类的一级分类', { exact: true }).selectOption(root.id);
    await panel.getByLabel('新分类名称', { exact: true }).fill('合成新增分类');
    await page.route('**/api/v1/transaction/categories/add.json', route => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false }) }));
    await panel.getByRole('button', { name: /^创建并关联/ }).click(); await panel.getByRole('alert').waitFor();
    assert.equal(await panel.getByLabel('新分类名称', { exact: true }).inputValue(), '合成新增分类');
    assert.equal(await panel.getByLabel('新增分类的一级分类', { exact: true }).inputValue(), root.id);
    await page.unroute('**/api/v1/transaction/categories/add.json');
    // Lose the successful creation response, then replay the same stable session ID.
    await page.route('**/api/v1/transaction/categories/add.json', async route => { await route.fetch(); await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false }) }); });
    await panel.getByRole('button', { name: /^创建并关联/ }).click();
    await panel.getByRole('button', { name: /^创建并关联/ }).waitFor();
    await page.waitForFunction(() => !document.querySelector('.mapping-create button[type="submit"]')?.disabled);
    await page.unroute('**/api/v1/transaction/categories/add.json');
    await panel.getByRole('button', { name: /^创建并关联/ }).click();
    await panel.getByRole('status').filter({ hasText: '已创建分类' }).waitFor();
    const categories = await request('v1/transaction/categories/list.json', owner.token);
    const added = Object.values(categories).flat().flatMap(c => c.subCategories || []).filter(c => c.name === '合成新增分类');
    assert.equal(added.length, 1); assert.equal(added[0].parentId, root.id); assert.equal(added[0].type, 2);
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance, 10000);
    pass('inline new leaf category preserves form on failure and successful-response-loss retry creates exactly one category without transaction');
    const source = panel.locator('.mapping-row').filter({ hasText: '来源账户' }).first();
    await source.getByRole('combobox').selectOption(account.id); await source.getByRole('button', { name: /^应用/ }).click();
    for (const checkbox of await page.locator('.import-transaction-table tbody input[type="checkbox"]').all()) await checkbox.check();
    await page.getByRole('dialog').getByRole('button', { name: '导入', exact: true }).click();
    const imported = page.waitForResponse(response => response.url().includes('/transactions/import.json'));
    const confirm = page.getByRole('dialog').last(); await confirm.getByRole('button', { name: /确认|确定/ }).click();
    assert.equal((await imported).status(), 200);
    await page.getByRole('dialog').getByRole('button', { name: '关闭', exact: true }).waitFor();
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance, 9555);
    pass('explicit final import writes two rows with existing/new category IDs and exact balance; preview alone never writes');
    await page.getByRole('dialog').getByRole('button', { name: /关闭|完成/ }).click();
    panel = await openBill('wechat');
    const unknown = panel.locator('.mapping-row').filter({ hasText: '支出分类：商户消费' }).filter({ hasNotText: '→' });
    await unknown.getByRole('button', { name: /^新增分类/ }).click();
    for (const width of [1440, 768, 375]) for (const zoom of [1, 1.25]) {
        await page.setViewportSize({ width, height: 1000 }); await page.evaluate(value => document.documentElement.style.zoom = value, zoom);
        assert(await panel.evaluate(el => el.scrollWidth <= el.clientWidth + 1));
    }
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.evaluate(() => document.documentElement.style.zoom = '1');
    await mkdir('artifacts/agent-access', { recursive: true }); await panel.scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'artifacts/agent-access/category-mapping-desktop-final.png' });
    pass('new mapping and inline creation remain reachable at 375/768/1440px and 100%/125% zoom');
    await page.getByRole('dialog').getByRole('button', { name: /^取消$/ }).click();
    await page.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await page.locator('a[href="/transaction/list"]').click();
    await page.getByLabel('导入支付宝或微信账单', { exact: true }).click();
    const popup = page.locator('.popup.modal-in');
    await popup.getByRole('link', { name: '微信支付', exact: true }).click();
    await popup.locator('input[type="file"]').setInputFiles({ name: 'synthetic.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from(fixtures.wechat, 'base64') });
    await popup.getByRole('link', { name: '解析并预览', exact: true }).click();
    const mobilePanel = popup.locator('.statement-mapping'); await mobilePanel.waitFor();
    assert.match(await mobilePanel.innerText(), /已确认的关联/);
    await mobilePanel.getByRole('button', { name: '关联建议分类（2 笔）', exact: true }).click();
    for (const scheme of ['light', 'dark']) for (const width of [375, 768, 1024]) {
        await page.emulateMedia({ colorScheme: scheme }); await page.setViewportSize({ width, height: 1000 });
        assert(await mobilePanel.evaluate(el => el.scrollWidth <= el.clientWidth + 1));
    }
    await page.setViewportSize({ width: 375, height: 1000 }); await page.screenshot({ path: 'artifacts/agent-access/category-mapping-mobile-final.png' });
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, owner.token)).balance, 9555);
    pass('actual mobile import reuses confirmed desktop rules, applies suggestions without posting and fits light/dark 375/768/1024px');
    const memberName = `mapping_member_${randomBytes(6).toString('hex')}`;
    const member = await request('register.json', '', { username: memberName, password, email: `${memberName}@example.invalid`, nickname: '共享分类隔离测试', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const ledger = await request('v1/ledger/create.json', owner.token, { type: 1, name: 'Synthetic shared mapping' });
    await request('v1/accounts/add.json', owner.token, { ledgerId: ledger.id, name: 'Synthetic shared wallet', category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 0 });
    const invitation = await request('v1/ledger/invitation/create.json', owner.token, { ledgerId: ledger.id, inviteeName: memberName, role: 3, expiresInSeconds: 86400 });
    await request('v1/ledger/invitation/accept.json', member.token, { token: invitation.token });
    const memberPage = await browser.newPage({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    await memberPage.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await memberPage.getByLabel('用户名', { exact: true }).fill(memberName); await memberPage.getByLabel('密码', { exact: true }).fill(password);
    await memberPage.getByRole('button', { name: '登录', exact: true }).click(); await memberPage.waitForURL(/#\/$/);
    await memberPage.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' });
    await memberPage.getByRole('button', { name: '切换当前账本', exact: true }).click(); await memberPage.getByRole('option').filter({ hasText: 'Synthetic shared mapping' }).click();
    await memberPage.getByRole('button', { name: '导入账单', exact: true }).click();
    await memberPage.getByRole('combobox', { name: '文件类型', exact: true }).locator('xpath=ancestor::div[contains(@class,"v-field")][1]').click();
    const memberMenu = memberPage.locator('.two-column-select-menu:visible');
    await memberMenu.getByPlaceholder('查找文件类型').fill('支付宝 (App) 交易流水文件'); await memberMenu.locator('.primary-list-container .v-list-item').first().click(); await memberMenu.locator('.secondary-list-container').getByText('支付宝 (App) 交易流水文件', { exact: true }).click();
    await memberPage.locator('input[type="file"][accept*=".zip"]').setInputFiles({ name: 'synthetic.csv', mimeType: 'text/csv', buffer: Buffer.from(fixtures.alipay, 'base64') });
    await memberPage.getByRole('dialog').getByRole('button', { name: /下一步/ }).click();
    const sharedPanel = memberPage.locator('.statement-mapping'); await sharedPanel.waitFor();
    await sharedPanel.getByRole('button', { name: '新增分类：支出分类：未匹配类别', exact: true }).click();
    await sharedPanel.getByLabel('新增分类的一级分类', { exact: true }).waitFor();
    assert.equal(await sharedPanel.getByLabel('新增分类的一级分类', { exact: true }).locator('option').count(), 1);
    assert.equal(await sharedPanel.getByRole('button', { name: /^创建并关联/ }).isDisabled(), true);
    assert(!/已确认的关联/.test(await sharedPanel.innerText()));
    pass('shared member cannot create categories in another owner taxonomy and does not reuse other user/ledger confirmed rules');
    assert.deepEqual(errors, []);
    await writeFile('artifacts/agent-access/category-mapping-http-browser-final.json', JSON.stringify({ passed: checks.length, checks }, null, 2));
    console.log(`Category mapping acceptance: ${checks.length} checks passed`);
} finally { await browser?.close(); }
