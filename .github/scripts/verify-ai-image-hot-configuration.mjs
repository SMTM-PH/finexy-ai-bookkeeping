import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';

// Fixed no-volume development service and local synthetic model, never user/NAS targets.
const docker = process.env.FINEXY_DOCKER_CLI || 'docker.exe';
const base = 'http://127.0.0.1:18141', mock = 'http://127.0.0.1:18144';
const output = resolve(process.env.FINEXY_AI_HOT_CONFIGURATION_EVIDENCE || 'artifacts/ai-review-android-source-20261019/image-hot-configuration');
assert(output.startsWith(resolve('artifacts') + sep), 'Evidence must remain under repository artifacts');
const info = JSON.parse(execFileSync(docker, ['inspect', 'finexy-ai-source-android-e2e'], { encoding: 'utf8' }))[0];
assert.equal(info.Config.Image, 'finexy-ai-android-source:20261019-root1');
assert.equal(info.Name, '/finexy-ai-source-android-e2e'); assert.deepEqual(info.Mounts, []); assert(info.State.Running);
assert(info.HostConfig.PortBindings['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18141'));
const mockInfo = JSON.parse(execFileSync(docker, ['inspect', 'finexy-ai-source-android-mock'], { encoding: 'utf8' }))[0];
assert.deepEqual(mockInfo.Mounts, []);
assert.equal(mockInfo.Image, 'sha256:30ad4a77748749840966cb84820259ec631fe479131bff899f5f6e82d8eb8ae2');
assert(mockInfo.HostConfig.PortBindings['8080/tcp'].some(p => p.HostIp === '127.0.0.1' && p.HostPort === '18144'));

const checks = [], pass = name => { checks.push(name); console.log(`PASS ${name}`); };
await mkdir(output, { recursive: true });
async function request(path, token, body, expected = 200) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    assert.equal(response.status, expected, path.split('?')[0]);
    const data = await response.json(); assert.equal(data.success, expected === 200);
    return data.result;
}
async function image(token, expected) {
    const form = new FormData(); form.set('ledgerId', '0');
    form.set('image', new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')], { type: 'image/png' }), 'synthetic.png');
    const response = await fetch(`${base}/api/v1/llm/transactions/recognize_receipt_image.json`, {
        method: 'POST', headers: { 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: form,
    });
    assert.equal(response.status, expected, 'image route must remain registered as configuration changes');
    const data = await response.json(); assert.equal(data.success, expected === 200);
    return data.result;
}
const audit = async () => { const response = await fetch(`${mock}/audit`); assert.equal(response.status, 200); return response.json(); };
async function financial(token) {
    const rows = await request('v1/transactions/list.json?max_time=0&min_time=0&type=0&count=50&page=1&with_count=true', token);
    const accounts = await request('v1/accounts/list.json', token);
    return { rows, accounts };
}
try {
    // This must be the first account on the fresh candidate; no configuration credentials are retained.
    const username = `aicfg${randomBytes(8).toString('hex')}`, password = randomBytes(18).toString('hex');
    const owner = await request('register.json', '', { username, password, email: `${username}@example.invalid`, nickname: 'Synthetic image config', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    const account = await request('v1/accounts/add.json', owner.token, { category: 2, type: 1, icon: '1', color: '000000', name: 'ImageHotUSD', currency: 'USD', balance: 10000, balanceTime: 946684800 });
    const parent = await request('v1/transaction/categories/add.json', owner.token, { type: 2, name: 'Image hot parent', icon: '1', color: '000000' });
    await request('v1/transaction/categories/add.json', owner.token, { type: 2, parentId: parent.id, name: 'ImageHotExpense', icon: '1', color: '000000' });
    const control = await fetch(`${mock}/control`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
        result: { type: 'expense', account: 'ImageHotUSD', category: 'ImageHotExpense', amount: '-5.00' }, required: ['ImageHotUSD', 'ImageHotExpense'], forbidden: [],
    }) }); assert.equal(control.status, 204);
    const baseline = await financial(owner.token);
    await image(owner.token, 400); assert.equal((await audit()).requests, 0);
    pass('disabled-at-start image route rejects with a structured result, not 404');
    const configuration = { enabled: true, imageEnabled: true, baseUrl: 'http://host.docker.internal:18144/v1', apiKey: 'synthetic-fixture-only', modelId: 'synthetic', enableThinking: 'off', requestTimeout: 10000 };
    await request('v1/llm/configuration/update.json', owner.token, configuration);
    const first = await image(owner.token, 200);
    assert.equal(first.sourceAccountId, account.id); assert.equal(first.sourceAmount, -500); assert.equal((await audit()).requests, 1);
    pass('enabling images after startup immediately recognizes in the original process');
    await request('v1/llm/configuration/update.json', owner.token, { ...configuration, imageEnabled: false });
    await image(owner.token, 400); assert.equal((await audit()).requests, 1);
    pass('disabling images immediately rejects without calling the model');
    await request('v1/llm/transactions/recognize_text.json', owner.token, { ledgerId: '0', text: 'Synthetic image toggle text regression' });
    assert.equal((await audit()).requests, 2);
    pass('text recognition remains available while images are disabled');
    await request('v1/llm/configuration/update.json', owner.token, configuration);
    await image(owner.token, 200); assert.equal((await audit()).requests, 3);
    pass('re-enabling images works without restarting the service');
    await image('', 400); assert.equal((await audit()).requests, 3);
    pass('anonymous image calls remain rejected before model invocation');
    assert.equal((await audit()).violations, 0); assert.deepEqual(await financial(owner.token), baseline);
    assert.deepEqual(await request('v1/ai/review/list.json?ledgerId=0&status=1&beforeId=0&limit=100', owner.token), []);
    pass('recognition does not enqueue reviews or change financial data');
    const final = JSON.parse(execFileSync(docker, ['inspect', info.Id], { encoding: 'utf8' }))[0];
    assert.equal(final.State.Pid, info.State.Pid); assert.equal(final.State.StartedAt, info.State.StartedAt);
    pass('same service PID and start time across every configuration change');
    await writeFile(`${output}/summary.json`, JSON.stringify({ passed: true, image: info.Image, checks, audit: await audit(), noRestart: true, syntheticOnly: true }, null, 2));
} catch (error) {
    await writeFile(`${output}/failure.txt`, String(error.message));
    throw error;
}
