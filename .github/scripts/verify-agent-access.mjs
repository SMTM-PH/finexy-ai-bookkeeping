import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';

// This test must never target a user's service or mounted financial data.
const base = process.env.FINEXY_AGENT_E2E_URL || 'http://127.0.0.1:18110';
const url = new URL(base);
assert(['127.0.0.1', 'localhost'].includes(url.hostname));
assert.equal(url.port, '18110');
const container = 'finexy-agent-e2e';
const info = JSON.parse(execFileSync('docker', ['inspect', container], { encoding: 'utf8' }))[0];
assert.deepEqual(info.Mounts, []);
assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
const evidence = [];
const pass = name => { evidence.push(name); console.log(`PASS ${name}`); };
const password = randomBytes(18).toString('hex');
let browser;

async function request(path, token, body, expected = 200) {
    const response = await fetch(`${base}/api/${path}`, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const data = await response.json();
    assert.equal(response.status, expected, `HTTP ${path}: ${data.errorMessage || data.error?.message || ''}`);
    if (expected === 200) assert.equal(data.success, true, `success ${path}: ${data.errorMessage || ''}`);
    return data.result;
}
async function register() {
    const username = `agent_${randomBytes(6).toString('hex')}`;
    const result = await request('register.json', '', { username, email: `${username}@example.invalid`, nickname: '隔离测试', password, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    return { username, token: result.token };
}
async function token(session, scopes, type = 'api', seconds = 86400, ledgerId = '0') {
    return (await request(`v1/tokens/generate/${type}.json`, session, { password, name: `test-${type}`, scopes, ledgerId, expiresInSeconds: seconds })).token;
}
async function mcp(token, method, params = {}, expected = 200) {
    const response = await fetch(`${base}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-03-26', Authorization: `Bearer ${token}` }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
    assert.equal(response.status, expected);
    return response.json();
}
async function call(token, name, args) {
    const data = await mcp(token, 'tools/call', { name, arguments: args });
    assert(!data.error, `MCP ${name}: ${data.error?.message || ''}`);
    assert.notEqual(data.result.isError, true);
    return data.result.structuredContent || JSON.parse(data.result.content[0].text);
}

// Synthetic bill fixtures only; do not read a user's exported bills in this gate.
function billFixtures(grouped = false) {
    return JSON.parse(execFileSync(process.env.PYTHON || 'python', ['-c', `
import io, zipfile, base64, json, sys
from xml.sax.saxutils import escape
rows = [['微信支付账单明细'], ['----------------------微信支付账单明细列表--------------------'], ['交易时间','交易类型','收/支','金额(元)','当前状态'], ['2026-09-02 12:34:56','商户消费','支出','￥2.34','支付成功']]
xmlrows = ''.join('<row r="%s">%s</row>' % (r+1, ''.join('<c r="%s%s" t="inlineStr"><is><t>%s</t></is></c>' % (chr(65+c),r+1,escape(str(v))) for c,v in enumerate(row))) for r,row in enumerate(rows))
buffer = io.BytesIO()
with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
 z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
 z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>')
 z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
 z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+xmlrows+'</sheetData></worksheet>')
alipay = '\\n'.join(['------------------------------------------------------------------------------------','导出信息：','------------------------支付宝支付科技有限公司  电子客户回单------------------------','交易时间,交易分类,商品说明,收/支,金额,交易状态,','2026-09-03 12:34:56,餐饮美食,Synthetic lunch,支出,3.45,交易成功,'])
if sys.argv[1] == 'grouped': alipay += '\\n2026-09-03 13:34:56,餐饮美食,Synthetic coffee,支出,1.00,交易成功,'
print(json.dumps({'wechat_pay_app_xlsx':base64.b64encode(buffer.getvalue()).decode(),'alipay_app_csv':base64.b64encode(alipay.encode('gb18030')).decode()}))
`, grouped ? 'grouped' : 'single'], { encoding: 'utf8' }));
}

try {
    let healthy = false;
    for (let i = 0; i < 60; i++) {
        try { healthy = (await fetch(`${base}/healthz.json`)).ok; } catch { /* starting */ }
        if (healthy) break;
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert(healthy, 'isolated server health');
    const a = await register();
    const b = await register();
    const read = await token(a.token, ['read']);
    const write = await token(a.token, ['transactions:write']);
    const manage = await token(a.token, ['accounts:manage']);
    const imports = await token(a.token, ['bills:import']);
    const mcpRead = await token(a.token, ['read'], 'mcp');
    const mcpImport = await token(a.token, ['bills:import'], 'mcp');
    const initialized = await mcp(mcpRead, 'initialize', { protocolVersion: '2025-06-18', clientInfo: { name: 'isolated-agent-gate', version: '1' } });
    assert.equal(initialized.result.serverInfo.version, process.env.FINEXY_EXPECTED_VERSION || '1.9.3');
    assert.deepEqual(await request('v1/agent/access/get.json', a.token), { mcpEnabled: true, mcpAvailable: true });
    await request('v1/agent/access/update.json', manage, { mcpEnabled: false }, 403);
    await request('v1/agent/access/get.json', read, undefined, 403);
    await request('v1/agent/access/update.json', a.token, {}, 400);
    const mcpOther = await token(b.token, ['read'], 'mcp');
    await request('v1/agent/access/update.json', a.token, { mcpEnabled: false });
    await mcp(mcpRead, 'tools/list', {}, 403);
    await mcp(mcpImport, 'tools/call', { name: 'confirm_bill_import', arguments: {} }, 403);
    await request('v1/tokens/generate/mcp.json', a.token, { password, scopes: ['read'], expiresInSeconds: 86400 }, 403);
    await request('v1/accounts/list.json', read);
    assert((await mcp(mcpOther, 'tools/list')).result.tools.length > 0);
    execFileSync('docker', ['restart', container], { stdio: 'ignore' });
    for (let i = 0; i < 60; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* starting */ }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert.equal((await request('v1/agent/access/get.json', a.token)).mcpEnabled, false);
    await mcp(mcpRead, 'initialize', {}, 403);
    await request('v1/agent/access/update.json', a.token, { mcpEnabled: true });
    assert((await mcp(mcpRead, 'tools/list')).result.tools.length > 0);
    pass('MCP switch persists across restart, blocks existing tokens and generation, isolates users and leaves API usable');
    await request('v1/accounts/list.json', read);
    for (const [path, credential] of [
        ['v1/accounts/add.json', read], ['v1/accounts/add.json', write],
        ['v1/transactions/add.json', manage], ['v1/transactions/add.json', imports],
        ['v1/transactions/import.json', imports], ['v1/tokens/generate/api.json', manage],
        ['v1/data/clear/all.json', manage], ['v1/agent/import/preview.json', read],
    ]) await request(path, credential, {}, 403);
    pass('independent REST scopes, no privilege escalation or raw import bypass');

    const account = await request('v1/accounts/add.json', manage, { name: 'Synthetic account', category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 });
    const root = await request('v1/transaction/categories/add.json', manage, { name: 'Synthetic root', type: 2, icon: '1', color: '000000' });
    const category = await request('v1/transaction/categories/add.json', manage, { name: 'Synthetic leaf', type: 2, parentId: root.id, icon: '1', color: '000000' });
    assert.equal(typeof account.id, 'string'); assert.equal(typeof category.id, 'string');
    pass('account/category management with string IDs');

    const readTools = await mcp(mcpRead, 'tools/list');
    assert(readTools.result.tools.some(t => t.name === 'query_transactions'));
    assert(!readTools.result.tools.some(t => t.name === 'add_transaction' || t.name === 'preview_bill_import'));
    assert(readTools.result.tools.every(t => t.annotations.readOnlyHint));
    const importTools = await mcp(mcpImport, 'tools/list');
    const mappingSchema = importTools.result.tools.find(t => t.name === 'map_bill_import').inputSchema.properties.mappings.items;
    assert.equal(mappingSchema.properties.sourceAccountId.type, 'string', 'MCP account IDs must be strings');
    const denied = await mcp(mcpRead, 'tools/call', { name: 'confirm_bill_import', arguments: {} }, 403);
    assert(denied.error || denied.result?.isError);
    pass('MCP tool visibility and server-side scope rejection');

    const file = '微信支付账单明细\n----------------------微信支付账单明细列表--------------------\n交易时间,交易类型,收/支,金额(元),当前状态\n2026-09-01 12:34:56,商户消费,支出,￥1.23,支付成功\n';
    let preview = await call(mcpImport, 'preview_bill_import', { fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(file).toString('base64'), utcOffset: 480 });
    assert.equal(preview.rows.length, 1); assert.equal(preview.rows[0].transaction.sourceAccountId, '0');
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, a.token)).balance, 10000);
    const stale = preview.previewHash;
    preview = await call(mcpImport, 'map_bill_import', { batchId: preview.batchId, previewHash: preview.previewHash, mappings: [{ row: 0, selected: true, sourceAccountId: account.id, destinationAccountId: '0', categoryId: category.id }] });
    assert.equal(preview.readyCount, 1); assert.equal(preview.expenseAmount, 123);
    const reject = await mcp(mcpImport, 'tools/call', { name: 'confirm_bill_import', arguments: { batchId: preview.batchId, previewHash: stale, confirmed: true } }, 400);
    assert(reject.error || reject.result?.isError);
    const unconfirmed = await mcp(mcpImport, 'tools/call', { name: 'confirm_bill_import', arguments: { batchId: preview.batchId, previewHash: preview.previewHash, confirmed: false } }, 400);
    assert(unconfirmed.error || unconfirmed.result?.isError);
    await request('v1/agent/import/map.json', b.token, { batchId: preview.batchId, previewHash: preview.previewHash, mappings: [] }, 400);
    pass('preview and explicit mappings do not change balances; stale/unconfirmed/foreign requests rejected');

    const confirm = { batchId: preview.batchId, previewHash: preview.previewHash, confirmed: true };
    const results = await Promise.all(Array.from({ length: 8 }, () => call(mcpImport, 'confirm_bill_import', confirm)));
    assert(results.every(r => r.importedCount === 1));
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, a.token)).balance, 9877);
    const again = await call(mcpImport, 'preview_bill_import', { fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(file).toString('base64'), utcOffset: 480 });
    assert.equal(again.duplicateCount, 1);
    pass('eight concurrent confirmations post once; source reimport detected');

    execFileSync('docker', ['restart', container], { stdio: 'ignore' });
    for (let i = 0; i < 30; i++) {
        try { if ((await fetch(`${base}/healthz.json`)).ok) break; } catch { /* restarting */ }
        await new Promise(resolve => setTimeout(resolve, 300));
    }
    assert.equal((await call(mcpImport, 'confirm_bill_import', confirm)).importedCount, 1);
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, a.token)).balance, 9877);
    pass('confirmation idempotency survives server restart');

    for (const [fileType, fileBase64] of Object.entries(billFixtures())) {
        let candidate = await request('v1/agent/import/preview.json', imports, { fileType, fileBase64, utcOffset: 480 });
        assert.equal(candidate.rows.length, 1);
        assert.equal(candidate.readyCount, 0);
        candidate = await request('v1/agent/import/map.json', imports, { batchId: candidate.batchId, previewHash: candidate.previewHash, mappings: [{ row: 0, selected: true, sourceAccountId: account.id, categoryId: category.id, destinationAccountId: '0' }] });
        assert.equal(candidate.readyCount, 1);
        const result = await request('v1/agent/import/confirm.json', imports, { batchId: candidate.batchId, previewHash: candidate.previewHash, confirmed: true });
        assert.equal(result.importedCount, 1);
    }
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, a.token)).balance, 9298);
    pass('Alipay GB18030 CSV and WeChat XLSX complete REST preview/map/confirm');

    const ledger = await request('v1/ledger/create.json', a.token, { type: 1, name: 'Selected import ledger' });
    const otherAccount = await request('v1/accounts/add.json', a.token, { ledgerId: ledger.id, name: 'Ledger account', category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 });
    const invitation = await request('v1/ledger/invitation/create.json', a.token, { ledgerId: ledger.id, inviteeName: b.username, role: 3, expiresInSeconds: 86400 });
    await request('v1/ledger/invitation/accept.json', b.token, { token: invitation.token });
    const memberToken = await token(b.token, ['bills:import'], 'api', 86400, ledger.id);
    const ledgerImports = await token(a.token, ['bills:import'], 'api', 86400, ledger.id);
    const ledgerMCP = await token(a.token, ['read', 'transactions:write', 'bills:import'], 'mcp', 86400, ledger.id);
    await request(`v1/accounts/list.json?ledgerId=${ledger.id}`, read, undefined, 403);
    await request(`v1/accounts/get.json?id=${otherAccount.id}`, read, undefined, 403);
    await request(`v1/accounts/get.json?id=${account.id}&ledgerId=${ledger.id}`, ledgerImports, undefined, 403);
    await request('v1/accounts/list.json', ledgerImports, undefined, 403);
    await request('v1/accounts/hide.json', manage, { id: otherAccount.id, hidden: true }, 403);
    for (const path of ['transactions/list/all.json', 'transactions/amounts.json', 'transactions/statistics/asset_trends.json']) await request(`v1/${path}?ledgerId=${ledger.id}`, ledgerImports, undefined, 403);
    await request('v1/tokens/generate/api.json', b.token, { password, ledgerId: '9007199254740993', expiresInSeconds: 86400 }, 400);
    const filteredLedgers = await request('v1/ledger/list.json', ledgerImports);
    assert(filteredLedgers.every(item => item.id === ledger.id));
    const importContext = await call(ledgerMCP, 'query_import_context', { ledgerId: ledger.id });
    await mcp(mcpImport, 'tools/call', { name: 'query_import_context', arguments: { ledgerId: ledger.id } }, 403);
    pass('ledger grants reject omitted/foreign IDs, unsupported global aggregates and inaccessible ledger generation');
    assert.equal(importContext.ledgerId, ledger.id);
    assert(importContext.accounts.some(row => row.id === otherAccount.id));
    assert(!importContext.accounts.some(row => row.id === account.id));
    let lp = await request('v1/agent/import/preview.json', memberToken, { ledgerId: ledger.id, fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(file).toString('base64'), utcOffset: 480 });
    assert.equal(lp.ledgerId, ledger.id); assert.equal(lp.duplicateCount, 0);
    lp = await request('v1/agent/import/map.json', memberToken, { ledgerId: ledger.id, batchId: lp.batchId, previewHash: lp.previewHash, mappings: [{ row: 0, selected: true, sourceAccountId: account.id, categoryId: category.id }] });
    assert.equal(lp.readyCount, 0, 'reject another ledger account');
    lp = await request('v1/agent/import/map.json', memberToken, { ledgerId: ledger.id, batchId: lp.batchId, previewHash: lp.previewHash, mappings: [{ row: 0, selected: true, sourceAccountId: otherAccount.id, categoryId: category.id }] });
    assert.equal(lp.readyCount, 1);
    await request('v1/agent/import/confirm.json', memberToken, { ledgerId: '0', batchId: lp.batchId, previewHash: lp.previewHash, confirmed: true }, 403);
    assert.equal((await request('v1/agent/import/confirm.json', memberToken, { ledgerId: ledger.id, batchId: lp.batchId, previewHash: lp.previewHash, confirmed: true })).importedCount, 1);
    assert.equal((await request(`v1/accounts/get.json?id=${otherAccount.id}&ledgerId=${ledger.id}`, a.token)).balance, 9877);
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, a.token)).balance, 9298);
    const same = await request('v1/agent/import/preview.json', ledgerImports, { ledgerId: ledger.id, fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(file).toString('base64'), utcOffset: 480 });
    assert.equal(same.duplicateCount, 1, 'duplicates visible across members of same ledger');
    const balances = await call(ledgerMCP, 'query_all_accounts_balance', {});
    assert(JSON.stringify(balances).includes('Ledger account'));
    assert(!JSON.stringify(balances).includes('Synthetic account'));
    const ledgerRows = await call(ledgerMCP, 'query_transactions', { page: 1, count: 100, start_time: '2026-01-01T00:00:00+08:00', end_time: '2026-12-31T23:59:59+08:00' });
    assert(ledgerRows.transactions.length > 0);
    assert(!JSON.stringify(ledgerRows).includes('Synthetic account'));
    const mcpExpense = { type: 'expense', time: '2026-09-05T12:00:00+08:00', account_name: 'Ledger account', category_name: 'Synthetic leaf', amount: '0.11', comment: 'Synthetic scoped MCP', dry_run: true };
    await call(ledgerMCP, 'add_transaction', mcpExpense);
    assert.equal((await request(`v1/accounts/get.json?id=${otherAccount.id}&ledgerId=${ledger.id}`, a.token)).balance, 9877);
    await call(ledgerMCP, 'add_transaction', { ...mcpExpense, dry_run: false });
    assert.equal((await request(`v1/accounts/get.json?id=${otherAccount.id}&ledgerId=${ledger.id}`, a.token)).balance, 9866);
    const scopedWriter = await token(a.token, ['transactions:write'], 'api', 86400, ledger.id);
    await request('v1/transactions/add.json', scopedWriter, { ledgerId: ledger.id, type: 3, time: 1788600000, utcOffset: 480, sourceAccountId: account.id, categoryId: category.id, sourceAmount: 11 }, 403);
    pass('MCP account/query/write tools stay in token ledger; dry-run unchanged and foreign source account denied');
    const members = await request(`v1/ledger/member/list.json?ledgerId=${ledger.id}`, a.token);
    const member = members.find(row => !row.isCurrentUser);
    assert(member);
    const freshFile = file.replace('2026-09-01', '2026-09-04');
    let revokedPreview = await request('v1/agent/import/preview.json', memberToken, { ledgerId: ledger.id, fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(freshFile).toString('base64'), utcOffset: 480 });
    revokedPreview = await request('v1/agent/import/map.json', memberToken, { ledgerId: ledger.id, batchId: revokedPreview.batchId, previewHash: revokedPreview.previewHash, mappings: [{ row: 0, selected: true, sourceAccountId: otherAccount.id, categoryId: category.id }] });
    await request('v1/ledger/member/change_role.json', a.token, { ledgerId: ledger.id, memberId: member.id, role: 4 });
    await request('v1/agent/import/confirm.json', memberToken, { ledgerId: ledger.id, batchId: revokedPreview.batchId, previewHash: revokedPreview.previewHash, confirmed: true }, 403);
    await request('v1/agent/import/preview.json', memberToken, { ledgerId: ledger.id, fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(freshFile).toString('base64'), utcOffset: 480 }, 403);
    // Existing Web/Android import endpoint follows the selected ledger too.
    const row = { type: 3, sourceAccountId: otherAccount.id, categoryId: category.id, sourceAmount: 222, time: 1788537600, utcOffset: 480, comment: 'Synthetic ordinary import', ledgerId: ledger.id };
    await request('v1/transactions/import.json', b.token, { ledgerId: ledger.id, transactions: [row], clientSessionId: randomBytes(16).toString('hex') }, 403);
    await request('v1/transactions/import.json', a.token, { ledgerId: ledger.id, transactions: [{ ...row, sourceAccountId: account.id }], clientSessionId: randomBytes(16).toString('hex') }, 400);
    await request('v1/transactions/import.json', a.token, { ledgerId: ledger.id, transactions: [row], clientSessionId: randomBytes(16).toString('hex') });
    assert.equal((await request(`v1/accounts/get.json?id=${otherAccount.id}&ledgerId=${ledger.id}`, a.token)).balance, 9644);
    assert.equal((await request(`v1/accounts/get.json?id=${account.id}`, a.token)).balance, 9298);
    await request('v1/ledger/member/remove.json', a.token, { ledgerId: ledger.id, memberId: member.id });
    await request(`v1/accounts/list.json?ledgerId=${ledger.id}`, memberToken, undefined, 403);
    pass('selected ledger, shared member import, cross-ledger rejection, viewer and revoked access, ledger-scoped dedupe');

    const short = await token(a.token, ['read'], 'api', 1);
    await new Promise(resolve => setTimeout(resolve, 1500));
    await request('v1/accounts/list.json', short, undefined, 401);
    pass('token expiry enforced');

    const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
    browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${base}/desktop#/login`, { waitUntil: 'networkidle' });
    await page.getByLabel('用户名', { exact: true }).fill(a.username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(/#\/$/);
    await page.goto(`${base}/desktop#/user/settings?tab=securitySetting`, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: 'Agent 接入', exact: true }).waitFor();
    const mcpSwitch = page.getByRole('switch', { name: '此账号的 MCP 接入' });
    await page.waitForFunction(() => !document.querySelector('[role="switch"]').disabled);
    assert.equal(await mcpSwitch.getAttribute('aria-checked'), 'true');
    await mcpSwitch.focus();
    await page.keyboard.press('Space');
    await page.waitForFunction(() => document.querySelector('[role="switch"]').getAttribute('aria-checked') === 'false');
    await mcp(mcpRead, 'tools/list', {}, 403);
    await page.reload({ waitUntil: 'networkidle' });
    assert.equal(await mcpSwitch.getAttribute('aria-checked'), 'false');
    let disabledGenerationRequests = 0;
    const countGeneration = req => { if (req.url().includes('/tokens/generate/mcp.json')) disabledGenerationRequests++; };
    page.on('request', countGeneration);
    await page.getByLabel('Finexy 登录密码', { exact: true }).fill(password);
    assert.equal(await page.getByRole('button', { name: '生成令牌', exact: true }).isDisabled(), true);
    await page.getByLabel('Finexy 登录密码', { exact: true }).press('Enter');
    await page.waitForTimeout(200);
    assert.equal(disabledGenerationRequests, 0, 'Enter must not submit MCP generation while access is off');
    page.off('request', countGeneration);
    await mcpSwitch.click();
    await page.waitForFunction(() => document.querySelector('[role="switch"]').getAttribute('aria-checked') === 'true');
    assert((await mcp(mcpRead, 'tools/list')).result.tools.length > 0);
    pass('desktop MCP switch keyboard toggle, reload persistence and restored token access');
    await page.route('**/api/v1/agent/access/update.json', route => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false }) }));
    await mcpSwitch.click();
    await page.getByText('保存 MCP 设置失败，请重新读取状态后重试。', { exact: false }).waitFor();
    assert.equal(await mcpSwitch.isDisabled(), true);
    assert.equal(await mcpSwitch.innerText(), '状态未知');
    assert.equal((await request('v1/agent/access/get.json', a.token)).mcpEnabled, true);
    await page.unroute('**/api/v1/agent/access/update.json');
    await page.getByRole('button', { name: '重试读取状态' }).click();
    await page.waitForFunction(() => !document.querySelector('[role="switch"]').disabled);
    assert.equal(await mcpSwitch.getAttribute('aria-checked'), 'true');
    pass('failed MCP switch save shows unknown state and recovers without pretending success');
    await page.locator('.agent-fields select').first().selectOption('api');
    await page.getByLabel('账单导入', { exact: false }).check();
    await page.getByRole('combobox', { name: /授权账本/ }).selectOption(ledger.id);
    await page.getByLabel('令牌名称', { exact: true }).fill('UI Codex');
    await page.getByLabel('Finexy 登录密码', { exact: true }).fill('synthetic-wrong-password');
    await page.getByRole('button', { name: '生成令牌', exact: true }).click();
    await page.locator('.agent-error').filter({ hasText: '生成失败' }).waitFor();
    assert.equal(await page.getByLabel('Finexy 登录密码', { exact: true }).inputValue(), 'synthetic-wrong-password');
    await page.getByLabel('Finexy 登录密码', { exact: true }).fill(password);
    await page.getByLabel('Finexy 登录密码', { exact: true }).press('Enter');
    await page.getByRole('heading', { name: '令牌已生成', exact: true }).waitFor();
    const uiToken = await page.getByLabel('完整令牌', { exact: true }).inputValue();
    assert(uiToken.length > 100);
    await request('v1/ledger/list.json', uiToken);
    await request('v1/accounts/add.json', uiToken, {}, 403);
    const uiPreview = await request('v1/agent/import/preview.json', uiToken, { ledgerId: ledger.id, fileType: 'wechat_pay_app_csv', fileBase64: Buffer.from(file).toString('base64'), utcOffset: 480 });
    assert.equal(uiPreview.ledgerId, ledger.id);
    await request('v1/accounts/list.json', uiToken, undefined, 403);
    assert.equal(await page.getByLabel('Finexy 登录密码', { exact: true }).count(), 0);
    await page.getByRole('button', { name: '关闭令牌显示', exact: true }).click();
    assert.equal(await page.getByLabel('完整令牌', { exact: true }).count(), 0);
    assert(!(await page.locator('.agent-guide').innerText()).includes(uiToken));
    await page.getByRole('button', { name: '撤销令牌：UI Codex', exact: true }).click();
    await page.locator('.agent-confirm').waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await page.locator('.agent-confirm').waitFor({ state: 'hidden' });
    assert.equal(await page.getByRole('button', { name: '撤销令牌：UI Codex', exact: true }).count(), 1);
    await page.getByRole('button', { name: '撤销令牌：UI Codex', exact: true }).click();
    await page.getByRole('button', { name: '确认撤销', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.agent-confirm[open]'));
    await page.getByRole('button', { name: '撤销令牌：UI Codex', exact: true }).waitFor({ state: 'hidden' });
    await request('v1/accounts/list.json', uiToken, undefined, 401);
    assert.deepEqual(errors, []);
    await mkdir('artifacts/agent-access', { recursive: true });
    await page.getByRole('heading', { name: 'Agent 接入', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'artifacts/agent-access/security-settings-final.png' });
    pass('real browser security deep link, token generation, secret clearing and revocation');
    await page.goto(`${base}/desktop#/transaction/list`, { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: '切换当前账本', exact: true }).click();
    await page.getByRole('option').filter({ hasText: 'Selected import ledger' }).click();
    await page.getByRole('button', { name: '导入支付宝', exact: true }).click();
    await page.getByRole('dialog').getByText('账单将导入「Selected import ledger」', { exact: false }).waitFor();
    await page.evaluate(() => {
        const send = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function(body) {
            if (body instanceof FormData && body.has('ledgerId')) window.__importLedgerId = body.get('ledgerId');
            return send.call(this, body);
        };
    });
    const parsedResponse = page.waitForResponse(response => response.url().includes('/transactions/parse_import.json'));
    await page.locator('input[type="file"][accept*=".zip"]').setInputFiles({ name: 'synthetic-alipay.csv', mimeType: 'text/csv', buffer: Buffer.from(billFixtures(true).alipay_app_csv, 'base64') });
    await page.getByRole('dialog').getByRole('button', { name: /下一步/ }).click();
    const parsed = await parsedResponse;
    assert.equal(parsed.status(), 200);
    assert.equal(await page.evaluate(() => window.__importLedgerId), ledger.id, 'multipart includes selected ledger ID');
    await page.getByText('检查及修改').first().waitFor();
    const mappingPanel = page.locator('.statement-mapping');
    await mappingPanel.waitFor({ state: 'visible' });
    const sourceGroup = mappingPanel.locator('.mapping-row').filter({ hasText: '来源账户' }).first();
    await sourceGroup.getByRole('combobox').selectOption(otherAccount.id);
    const categoryGroup = mappingPanel.locator('.mapping-row').filter({ hasText: '支出分类' }).first();
    await categoryGroup.getByRole('combobox').selectOption(category.id);
    const beforeGroupBalance = (await request(`v1/accounts/get.json?id=${otherAccount.id}&ledgerId=${ledger.id}`, a.token)).balance;
    assert.match(await sourceGroup.innerText(), /2 笔/);
    await sourceGroup.getByRole('button').click();
    await categoryGroup.getByRole('button').click();
    assert.equal(await sourceGroup.getByRole('button').isDisabled(), true, 'default mapping preserves already mapped rows');
    await mappingPanel.getByLabel('覆盖该组已映射项').check();
    assert.equal(await sourceGroup.getByRole('button').isDisabled(), false, 'overwrite requires explicit opt-in');
    await mappingPanel.getByLabel('覆盖该组已映射项').uncheck();
    assert.equal((await request(`v1/accounts/get.json?id=${otherAccount.id}&ledgerId=${ledger.id}`, a.token)).balance, beforeGroupBalance, 'mapping alone must not write finances');
    for (const width of [1440, 768, 375]) {
        await page.setViewportSize({ width, height: 1000 });
        assert.equal(await mappingPanel.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    pass('grouped account/category mappings apply without posting; preserve existing mappings and require overwrite opt-in');
    await page.screenshot({ path: 'artifacts/agent-access/selected-ledger-import-final.png' });
    await page.getByRole('dialog').getByRole('button', { name: /取消/ }).click();
    await page.goto(`${base}/desktop#/user/settings?tab=securitySetting`, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: 'Agent 接入', exact: true }).waitFor();
    pass('Web import entry and preview show the currently selected ledger');


    for (const theme of ['light', 'dark']) for (const zoom of [1, 1.25]) {
        await page.emulateMedia({ colorScheme: theme });
        await page.evaluate(value => { document.documentElement.style.zoom = value; }, zoom);
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const checkbox = await page.locator('.agent-permission input').first().boundingBox();
        assert(Math.abs(checkbox.width / zoom - 18) < 1, `checkbox width ${checkbox.width}, zoom ${zoom}`);
        assert.equal(await page.locator('.agent-access').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    }
    pass('light/dark system themes and 100%/125% zoom');
    await page.evaluate(() => { document.documentElement.style.zoom = '1'; });
    const mobile = await context.newPage();
    const mobileErrors = [];
    mobile.on('pageerror', error => mobileErrors.push(error.message));
    await mobile.setViewportSize({ width: 375, height: 900 });
    await mobile.goto(`${base}/mobile`, { waitUntil: 'networkidle' });
    await mobile.waitForFunction(() => document.querySelector('input[autocomplete="username"]') || document.querySelector('a[href="/settings"]'));
    if (await mobile.locator('input[autocomplete="username"]').count()) {
        await mobile.locator('input[autocomplete="username"]').fill(a.username);
        await mobile.locator('input[autocomplete="current-password"]').fill(password);
        await mobile.getByText('登录', { exact: true }).click();
    }
    await mobile.locator('a[href="/settings"]').click();
    await mobile.locator('a[href="/user/agent-access"]').click();
    await mobile.getByRole('heading', { name: 'Agent 接入', exact: true }).waitFor();
    const mobileSwitch = mobile.getByRole('switch', { name: '此账号的 MCP 接入' });
    await mobileSwitch.click();
    await mobile.waitForFunction(() => document.querySelector('[role="switch"]').getAttribute('aria-checked') === 'false');
    assert.equal((await request('v1/agent/access/get.json', a.token)).mcpEnabled, false);
    await mobileSwitch.click();
    await mobile.waitForFunction(() => document.querySelector('[role="switch"]').getAttribute('aria-checked') === 'true');
    pass('mobile MCP switch saves the same account setting');
    for (const width of [375, 768, 1024]) {
        await mobile.setViewportSize({ width, height: 900 });
        assert.equal(await mobile.locator('.agent-access').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, JSON.stringify(await mobile.locator('.agent-access').evaluate(el => ({ width: innerWidth, client: el.clientWidth, scroll: el.scrollWidth, overflowing: [...el.querySelectorAll('*')].filter(child => child.getBoundingClientRect().right > el.getBoundingClientRect().right + 1).map(child => ({ tag: child.tagName, class: child.className, right: child.getBoundingClientRect().right, width: child.getBoundingClientRect().width })) }))));
        assert.equal(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
    }
    await mobile.setViewportSize({ width: 375, height: 900 });
    await mobile.getByRole('heading', { name: 'Agent 接入', exact: true }).scrollIntoViewIfNeeded();
    await mobile.screenshot({ path: 'artifacts/agent-access/mobile-security-final.png' });
    assert.deepEqual(mobileErrors, []);
    pass('mobile settings entry and 375/768/1024px layouts without overflow');
    const metadata = await request('v1/tokens/list.json', a.token);
    assert(metadata.some(t => t.name === 'test-api' && t.scopes.includes('bills:import') && t.expiresAt));
    const readMetadata = metadata.find(t => t.name === 'test-api' && t.scopes.length === 1);
    await request('v1/tokens/revoke.json', a.token, { tokenId: readMetadata.tokenId });
    await request('v1/accounts/list.json', read, undefined, 401);
    pass('named scopes/expiry listed; revoked token no longer authenticates');
    await writeFile('artifacts/agent-access/http-browser-final.json', JSON.stringify({ passed: evidence.length, checks: evidence }, null, 2));
    console.log(`Agent acceptance: ${evidence.length} checks passed`);
} finally {
    if (browser) await browser.close();
}
