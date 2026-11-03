import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { access, cp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { isAbsolute, relative, resolve } from 'node:path';

// Host-only disposable runtime. No ADB, APK install, instrumentation or real financial writes.
const docker = process.env.DOCKER_CLI || 'C:/Users/abc123/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe';
const root = resolve('.');
const [operation, revision] = process.argv.slice(2);
assert(['prepare', 'verify', 'cleanup'].includes(operation), 'Use prepare|verify|cleanup <freshRevision>');
assert(/^[a-zA-Z0-9_-]{1,40}$/.test(revision || ''), 'A bounded evidence revision is required');
const folder = resolve(root, `artifacts/ai-review-android-capture-20261027/runtime-${revision}`);
const runtimePath = resolve(folder, 'runtime.json');
const PORT = 18141, MODEL_PORT = 18144, UUID_SERVER = 247;
const LABEL = 'finexy.capture.android.run';
const BINARY_SHA = '6a8791d9afc3ba2f1da1f760d8124e8f0ac186e5fabd0ace4382e7fb3e7108c6';
const BACKEND_MANIFEST_SHA = '2aa56600172eac860453798ec60cf8a4cd633cddfb9e347b1ad370ec894589c5';
const WEB_SHA = '341f96c8ef2d001556670e693d8abaef0f68883d5b20e24ca3741a5f1f3aed0a';
const BASE_IMAGE = 'sha256:55acb245ad0a04e786762dd9b5155348b663ba792457a2bec12ab84616e123fd';
const MODEL_IMAGE = 'sha256:30ad4a77748749840966cb84820259ec631fe479131bff899f5f6e82d8eb8ae2';
const backendFolder = resolve(root, 'artifacts/ledger-report-providers-20261022/budget-schema-verified');
const webFolder = resolve(root, 'artifacts/ledger-pending-20261023/browser');
const hash = value => createHash('sha256').update(value).digest('hex');
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = async (name, value) => writeFile(resolve(folder, name), `${JSON.stringify(value, null, 2)}\n`);
const command = (...args) => execFileSync(docker, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 120000 }).trim();
let phase = 'daemon';

function contained(name) {
    const path = resolve(root, name), suffix = relative(root, path);
    assert(suffix && !suffix.startsWith('..') && !isAbsolute(suffix), 'Manifest path escaped workspace');
    return path;
}
async function files(directory) {
    const result = [];
    for (const entry of await readdir(directory, { withFileTypes: true })) {
        const path = resolve(directory, entry.name);
        assert(!entry.isSymbolicLink(), 'Fixture inputs must not contain symbolic links');
        if (entry.isDirectory()) result.push(...await files(path));
        else if (entry.isFile()) result.push(path);
    }
    return result.sort();
}
function absent(result) {
    if (result.status === 0) return false;
    assert.equal(result.status, 1, 'Docker inspection did not complete');
    assert.match(result.stderr || '', /No such (?:object|container|network)|network[^\r\n]*not found/i,
        'Inspection failed without proving absence');
    return true;
}
function validateRuntime(runtime) {
    assert.match(runtime.runId || '', new RegExp(`^[a-f0-9]{12}-${revision}$`));
    assert.equal(runtime.port, PORT); assert.equal(runtime.modelPort, MODEL_PORT); assert.equal(runtime.uuidServerId, UUID_SERVER);
    assert.equal(runtime.url, `http://127.0.0.1:${PORT}`); assert.equal(runtime.modelUrl, `http://127.0.0.1:${MODEL_PORT}`);
    assert.equal(runtime.containerName, `finexy-capture-android-${runtime.runId}`);
    assert.equal(runtime.modelName, `finexy-capture-model-${runtime.runId}`);
    assert.equal(runtime.networkName, `finexy-capture-network-${runtime.runId}`);
    assert.equal(runtime.imageTag, `finexy-capture-android:20261027-${runtime.runId}`);
    assert.equal(runtime.binarySHA256, BINARY_SHA); assert.equal(runtime.webSourceSHA256, WEB_SHA);
    assert.equal(runtime.baseImageId, BASE_IMAGE); assert.equal(runtime.modelImageId, MODEL_IMAGE);
    assert(runtime.imageId === null || /^sha256:[a-f0-9]{64}$/.test(runtime.imageId));
    for (const id of [runtime.containerId, runtime.modelId, runtime.networkId]) assert(id === null || /^[a-f0-9]{64}$/.test(id));
}
function owned(info, runtime, model = false) {
    validateRuntime(runtime);
    assert.equal(info.Id, model ? runtime.modelId : runtime.containerId);
    assert.equal(info.Image, model ? MODEL_IMAGE : runtime.imageId);
    assert.equal(info.Name, '/' + (model ? runtime.modelName : runtime.containerName));
    assert.equal(info.Config.Labels[LABEL], runtime.runId);
    assert.equal(info.Config.Labels['finexy.capture.role'], model ? 'synthetic-model' : 'service');
    assert.equal(info.HostConfig.AutoRemove, true); assert.equal(info.Mounts.length, 0, 'Mounted data is forbidden');
    assert.deepEqual(info.HostConfig.PortBindings, { '8080/tcp': [{ HostIp: '127.0.0.1', HostPort: String(model ? MODEL_PORT : PORT) }] });
    assert.deepEqual(Object.keys(info.NetworkSettings.Networks), [runtime.networkName]);
    if (model) assert.deepEqual(info.Config.Entrypoint, ['/mock/mock']);
    else {
        assert(info.Config.Env.includes(`EBK_UUID_SERVER_ID=${UUID_SERVER}`));
        assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
        assert(info.Config.Env.includes('EBK_CRON_ENABLE_REMOVE_EXPIRED_TOKENS=false'));
        assert(info.Config.Env.includes('EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false'));
    }
}
function ownedNetwork(info, runtime) {
    validateRuntime(runtime); assert.equal(info.Id, runtime.networkId);
    assert.equal(info.Name, runtime.networkName); assert.equal(info.Labels[LABEL], runtime.runId);
    const allowed = new Set([runtime.containerId, runtime.modelId]);
    for (const id of Object.keys(info.Containers)) assert(allowed.has(id), 'Unowned container joined fixture network');
}

// Frozen backend/web source, binary and actual packaged assets are verified separately.
async function verifyInputs() {
    const backendPath = resolve(backendFolder, 'source-candidate.json');
    const backendBytes = await readFile(backendPath);
    assert.equal(hash(backendBytes), BACKEND_MANIFEST_SHA);
    const backend = JSON.parse(backendBytes.toString('utf8').replace(/^\uFEFF/, ''));
    assert.equal(Object.keys(backend).length, 630);
    for (const [name, digest] of Object.entries(backend)) assert.equal(hash(await readFile(contained(name))), digest, `Backend drift: ${name}`);
    const allGo = [...(await files(resolve(root, 'pkg'))), ...(await files(resolve(root, 'cmd'))),
        ...(await readdir(root, { withFileTypes: true })).filter(entry => entry.isFile()).map(entry => resolve(root, entry.name))]
        .filter(path => path.endsWith('.go')).map(path => relative(root, path).replaceAll('\\', '/'));
    assert.equal(allGo.filter(path => !Object.hasOwn(backend, path)).length, 0, 'Additional Go source is outside the binary candidate');
    const accepted = await readJson(resolve(backendFolder, 'acceptance-summary.json'));
    assert.equal(accepted.state, 'passed'); assert.equal(accepted.CGO.staticBuildExitCode, 0);
    assert.equal(accepted.sourceManifestSHA256, BACKEND_MANIFEST_SHA); assert.equal(accepted.binarySHA256, BINARY_SHA);
    assert.equal(hash(await readFile(resolve(backendFolder, 'finexy'))), BINARY_SHA);

    const web = await readJson(resolve(webFolder, 'source-candidate14.json'));
    const webGate = await readJson(resolve(root, 'artifacts/ledger-pending-20261023/gate-rates-verified/status.json'));
    assert.equal(web.sha256, WEB_SHA); assert.equal(webGate.sourceSHA256, WEB_SHA);
    assert.equal(webGate.passed, true); assert.equal(webGate.sourceUnchanged, true);
    for (const [name, digest] of Object.entries(web.files)) assert.equal(hash(await readFile(contained(name))), digest, `Web source drift: ${name}`);
    const webRun = await readJson(resolve(webFolder, 'attempt-candidate14/run-status.json'));
    const webResult = await readJson(resolve(webFolder, 'attempt-candidate14/summary.json'));
    assert.equal(webRun.driverExitCode, 0); assert.equal(webRun.cleanupExitCode, 0);
    assert.equal(webResult.status, 'passed'); assert.equal(webResult.sourceSHA256, WEB_SHA);
    const acceptedFiles = await readJson(resolve(webFolder, 'runtime-candidate14/runtime-files.json'));
    const publicExpected = Object.fromEntries(Object.entries(acceptedFiles).filter(([path]) => path.startsWith('/ezbookkeeping/public/')));
    const distFiles = await files(resolve(root, 'dist'));
    assert.equal(distFiles.length, Object.keys(publicExpected).length);
    for (const path of distFiles) {
        const destination = `/ezbookkeeping/public/${relative(resolve(root, 'dist'), path).replaceAll('\\', '/')}`;
        assert.equal(hash(await readFile(path)), publicExpected[destination], 'Public asset differs from the accepted web runtime');
    }
    const templates = {}, templatesRoot = resolve(root, 'templates');
    const acceptedTemplateRoot = resolve(webFolder, 'runtime-candidate14/context/templates');
    const acceptedTemplates = await files(acceptedTemplateRoot);
    const templateFiles = await files(templatesRoot);
    assert.equal(templateFiles.length, acceptedTemplates.length);
    for (const path of templateFiles) {
        const name = relative(templatesRoot, path).replaceAll('\\', '/');
        const digest = hash(await readFile(path));
        assert.equal(digest, hash(await readFile(resolve(acceptedTemplateRoot, name))), 'Template drift after web acceptance');
        templates[`/ezbookkeeping/templates/${name}`] = digest;
    }

    return { backend, web, runtimeFiles: { '/ezbookkeeping/ezbookkeeping': BINARY_SHA, ...publicExpected, ...templates },
        summary: { backendSourceFiles: 630, backendSourceManifestSHA256: BACKEND_MANIFEST_SHA, binarySHA256: BINARY_SHA,
            webSourceFiles: Object.keys(web.files).length, webSourceSHA256: WEB_SHA, publicFiles: distFiles.length,
            templateFiles: templateFiles.length, androidSourceChecked: false, deviceUsed: false } };
}


async function verifyRuntime(runtime) {
    assert(command('info', '--format', '{{.ServerVersion}}'), 'Live Docker daemon required');
    for (const [id, model] of [[runtime.containerId, false], [runtime.modelId, true]]) {
        const info = JSON.parse(command('inspect', id))[0]; owned(info, runtime, model); assert.equal(info.State.Running, true);
    }
    ownedNetwork(JSON.parse(command('network', 'inspect', runtime.networkId))[0], runtime);
    const expected = await readJson(resolve(folder, 'runtime-files.json'));
    const paths = Object.keys(expected), actual = {};
    for (let index = 0; index < paths.length; index += 35) {
        for (const line of command('exec', runtime.containerId, 'sha256sum', ...paths.slice(index, index + 35)).split('\n')) {
            const match = /^([a-f0-9]{64})\s+(.+)$/.exec(line); assert(match); actual[match[2]] = match[1];
        }
    }
    assert.equal(paths.filter(path => actual[path] !== expected[path]).length, 0, 'Runtime binary/assets drift');
    const packagedPaths = command('exec', runtime.containerId, 'find', '/ezbookkeeping/public', '/ezbookkeeping/templates', '-type', 'f').split('\n').filter(Boolean).sort();
    assert.deepEqual(packagedPaths, paths.filter(path => path !== '/ezbookkeeping/ezbookkeeping').sort());
    const modelBinary = command('exec', runtime.modelId, 'sha256sum', '/mock/mock').split(/\s/)[0];
    assert.match(modelBinary, /^[a-f0-9]{64}$/);
    const stored = await readJson(resolve(folder, 'model-image.json'));
    if (stored.binarySHA256) assert.equal(modelBinary, stored.binarySHA256);
    const configuration = JSON.parse(command('exec', runtime.containerId, 'cat', '/ezbookkeeping/data/ai-configuration.json'));
    assert.equal(configuration.enabled, true); assert.equal(configuration.imageEnabled, true);
    assert.equal(configuration.baseUrl, `http://${runtime.modelName}:8080/v1`);
    assert.equal(configuration.apiKey, 'synthetic-fixture-only'); assert.equal(configuration.modelId, 'synthetic');
    return { containerId: runtime.containerId, imageId: runtime.imageId, modelId: runtime.modelId, modelImageId: MODEL_IMAGE,
        modelBinarySHA256: modelBinary, networkId: runtime.networkId, noMounts: true, verifiedRuntimeFiles: paths.length,
        binarySHA256: BINARY_SHA, port: PORT, modelPort: MODEL_PORT, uuidServerId: UUID_SERVER, mismatches: [], deviceUsed: false };
}
async function audit(runtime) {
    const response = await fetch(`${runtime.modelUrl}/audit`, { redirect: 'error', signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, 200); const body = await response.json();
    assert.deepEqual(Object.keys(body).sort(), ['requests', 'violations']);
    for (const key of ['requests', 'violations']) assert(Number.isSafeInteger(body[key]) && body[key] >= 0);
    return { requests: body.requests, violations: body.violations };
}
async function capabilities(runtime, configure = false) {
    const before = await audit(runtime), routes = [];
    const api = async (path, token, body) => {
        phase = `capability:${path.split('?')[0]}`;
        const response = await fetch(`${runtime.url}/api/${path}`, { redirect: 'error', signal: AbortSignal.timeout(20000),
            method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '480',
                ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
        routes.push({ path, status: response.status });
        const result = await response.json();
        if (response.status !== 200 || result.success !== true) await save('api-rejection.json', {
            path: path.split('?')[0], status: response.status, success: result.success === true,
            code: Number.isSafeInteger(result.error?.code) ? result.error.code : null });
        assert.equal(response.status, 200, `Unexpected ${path}`);
        assert.equal(result.success, true); assert(Object.hasOwn(result, 'result')); return result.result;
    };
    // Normal first-user authorization; neither credentials nor token are written to host evidence.
    const username = `capture_host_${randomBytes(7).toString('hex')}`, password = randomBytes(24).toString('hex');
    const registered = await api('register.json', '', { username, password, email: `${username}@example.invalid`,
        nickname: 'Synthetic capture host readiness', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    assert.equal(typeof registered.token, 'string');
    const authorized = await api('authorize.json', '', { loginName: username, password });
    assert.equal(typeof authorized.token, 'string'); const token = authorized.token;
    if (configure) await api('v1/llm/configuration/update.json', token, { enabled: true, imageEnabled: true,
        baseUrl: `http://${runtime.modelName}:8080/v1`, apiKey: 'synthetic-fixture-only', modelId: 'synthetic',
        enableThinking: 'off', requestTimeout: 10000 });
    const configuration = await api('v1/llm/configuration/get.json', token);
    assert.equal(configuration.enabled, true); assert.equal(configuration.imageEnabled, true);
    assert.equal(configuration.baseUrl, `http://${runtime.modelName}:8080/v1`); assert.equal(configuration.apiKeyConfigured, true);
    assert.equal(configuration.modelId, 'synthetic'); assert.equal(configuration.provider, 'openai_compatible');
    const capability = await api('v1/ai/review/capabilities.json?ledgerId=0', token);
    assert.equal(capability.ledgerId, '0'); assert.equal(capability.contractVersion, 1);
    for (const key of ['recognitionLedgerContext', 'durableCreation', 'atomicConfirmation', 'payloadBinding', 'deletedReceipts']) assert.equal(capability[key], true);
    assert.equal(capability.pagination, 'beforeId'); assert.equal(capability.reviewerScope, 'manager-or-creator');
    assert.equal(capability.maxClientSessionIdBytes, 128); assert.equal(capability.maxPageSize, 100);
    assert.equal(capability.confirmationPath, 'v1/ai/review/confirm.json');
    for (const path of ['v1/accounts/list.json', 'v1/ai/review/list.json?ledgerId=0&status=1&beforeId=0&limit=100']) {
        const records = await api(path, token); assert(Array.isArray(records)); assert.equal(records.length, 0);
    }
    // Credentials fall out of scope here; the entire no-volume service is removed after the device run.
    const after = await audit(runtime); assert.deepEqual(after, before, 'Readiness must not invoke or reset model requests');
    return { normalRegistrationAndLogin: true, syntheticLocalConfiguration: true, configurationUpdated: configure,
        ledger0CapabilitiesVerified: true, emptyAccountsAndReviewQueue: true, financialWriteRequests: 0,
        recognitionRequests: 0, credentialsPersisted: false, modelAuditBefore: before, modelAuditAfter: after, routes,
        deviceUsed: false, androidTestsExecuted: false };
}
async function cleanup(runtime) {
    validateRuntime(runtime); assert(command('info', '--format', '{{.ServerVersion}}'), 'Cleanup requires live daemon');
    const result = { state: 'cleaned', daemonAvailable: true, deviceUsed: false, containers: [] };
    for (const [id, model] of [[runtime.containerId, false], [runtime.modelId, true]]) {
        if (!id) continue;
        let inspected = spawnSync(docker, ['inspect', id], { encoding: 'utf8' });
        if (!absent(inspected)) { owned(JSON.parse(inspected.stdout)[0], runtime, model); command('stop', id);
            inspected = spawnSync(docker, ['inspect', id], { encoding: 'utf8' }); }
        assert(absent(inspected)); result.containers.push({ id, role: model ? 'model' : 'service', absent: true });
    }
    if (runtime.networkId) {
        let inspected = spawnSync(docker, ['network', 'inspect', runtime.networkId], { encoding: 'utf8' });
        if (!absent(inspected)) { const info = JSON.parse(inspected.stdout)[0]; ownedNetwork(info, runtime);
            assert.equal(Object.keys(info.Containers).length, 0); command('network', 'rm', runtime.networkId);
            inspected = spawnSync(docker, ['network', 'inspect', runtime.networkId], { encoding: 'utf8' }); }
        assert(absent(inspected)); result.networkId = runtime.networkId; result.networkAbsent = true;
    }
    await save('cleanup.json', result); return result;
}
async function ready(url) {
    for (let i = 0; i < 100; i++) {
        try { if ((await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(2000) })).ok) return; } catch { /* Owned startup only. */ }
        await new Promise(done => setTimeout(done, 200));
    }
    assert.fail('Owned runtime did not become healthy');
}
async function requireFreePort(port) {
    // Windows Docker may briefly retain a just-removed owned published port.
    // This only probes bindability; it never stops an existing listener.
    let last;
    for (let attempt = 0; attempt < 30; attempt++) {
        try {
            await new Promise((done, fail) => { const server = createServer(); server.once('error', fail);
                server.listen(port, '127.0.0.1', () => server.close(done)); });
            return;
        } catch (error) { last = error; if (error.code !== 'EADDRINUSE') throw error; }
        await new Promise(done => setTimeout(done, 1000));
    }
    throw last;
}

let runtime;
try {
    assert(command('info', '--format', '{{.ServerVersion}}'), 'Live Docker daemon required');
    if (operation === 'prepare') {
        try { await access(folder); throw new Error('Evidence revision already exists; choose fresh revision'); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
        await mkdir(folder, { recursive: true }); await cp(new URL(import.meta.url), resolve(folder, 'script-executed.mjs'));
        await save('invocation.json', { operation, revision, startedAtUtc: new Date().toISOString(), processId: process.pid,
            scriptSHA256: hash(await readFile(new URL(import.meta.url))), deviceUsed: false });
        phase = 'inputs'; const inputs = await verifyInputs();
        await save('input-verification.json', inputs.summary); await save('backend-source.json', inputs.backend);
        await save('web-source.json', inputs.web); await save('runtime-files.json', inputs.runtimeFiles);
        phase = 'ports';
        for (const port of [PORT, MODEL_PORT]) await requireFreePort(port);
        assert.equal(command('image', 'inspect', BASE_IMAGE, '--format', '{{.Id}}'), BASE_IMAGE);
        const modelInfo = JSON.parse(command('image', 'inspect', MODEL_IMAGE))[0];
        assert.equal(modelInfo.Id, MODEL_IMAGE); assert.deepEqual(modelInfo.Config.Entrypoint, ['/mock/mock']);
        assert(!modelInfo.Config.Volumes || Object.keys(modelInfo.Config.Volumes).length === 0);
        await save('model-image.json', { id: MODEL_IMAGE, entrypoint: modelInfo.Config.Entrypoint, workingDir: modelInfo.Config.WorkingDir });
        const runId = `${randomBytes(6).toString('hex')}-${revision}`;
        runtime = { runId, port: PORT, modelPort: MODEL_PORT, uuidServerId: UUID_SERVER,
            url: `http://127.0.0.1:${PORT}`, modelUrl: `http://127.0.0.1:${MODEL_PORT}`,
            containerName: `finexy-capture-android-${runId}`, modelName: `finexy-capture-model-${runId}`,
            networkName: `finexy-capture-network-${runId}`, imageTag: `finexy-capture-android:20261027-${runId}`,
            baseImageId: BASE_IMAGE, modelImageId: MODEL_IMAGE, imageId: null, containerId: null, modelId: null, networkId: null,
            binarySHA256: BINARY_SHA, webSourceSHA256: WEB_SHA, deviceUsed: false };
        await save('runtime.json', runtime);
        const context = resolve(folder, 'context'); await mkdir(context);
        await cp(resolve(root, 'dist'), resolve(context, 'public'), { recursive: true, errorOnExist: true });
        await cp(resolve(root, 'templates'), resolve(context, 'templates'), { recursive: true, errorOnExist: true });
        await cp(resolve(backendFolder, 'finexy'), resolve(context, 'ezbookkeeping'), { errorOnExist: true });
        await writeFile(resolve(context, 'Dockerfile'), `FROM ${BASE_IMAGE}\nUSER root\nRUN rm -rf /ezbookkeeping/public /ezbookkeeping/templates && mkdir -p /ezbookkeeping/public /ezbookkeeping/templates\nCOPY --chown=1000:1000 ezbookkeeping /ezbookkeeping/ezbookkeeping\nCOPY --chown=1000:1000 public /ezbookkeeping/public\nCOPY --chown=1000:1000 templates /ezbookkeeping/templates\nUSER 1000:1000\n`);
        phase = 'image'; const build = spawnSync(docker, ['build', '-t', runtime.imageTag, context], { encoding: 'utf8', timeout: 300000, maxBuffer: 16 * 1024 * 1024 });
        await writeFile(resolve(folder, 'build.log'), `${build.stdout || ''}\n${build.stderr || ''}`); assert.equal(build.status, 0);
        runtime.imageId = command('image', 'inspect', runtime.imageTag, '--format', '{{.Id}}'); await save('runtime.json', runtime);
        phase = 'network'; runtime.networkId = command('network', 'create', '--label', `${LABEL}=${runId}`, runtime.networkName); await save('runtime.json', runtime);
        phase = 'model'; runtime.modelId = command('create', '--rm', '--name', runtime.modelName, '--label', `${LABEL}=${runId}`,
            '--label', 'finexy.capture.role=synthetic-model', '--network', runtime.networkName, '-p', `127.0.0.1:${MODEL_PORT}:8080`, MODEL_IMAGE);
        await save('runtime.json', runtime); command('start', runtime.modelId); await ready(`${runtime.modelUrl}/audit`);
        const control = await fetch(`${runtime.modelUrl}/control`, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(10000),
            headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ result: { type: 'expense', account: 'Capture sentinel',
                category: 'Synthetic capture expense', amount: '-1.00' }, forbidden: [], required: [] }) });
        assert.equal(control.status, 204); assert.deepEqual(await audit(runtime), { requests: 0, violations: 0 });
        phase = 'service'; runtime.containerId = command('create', '--rm', '--name', runtime.containerName,
            '--label', `${LABEL}=${runId}`, '--label', 'finexy.capture.role=service', '--network', runtime.networkName,
            '-p', `127.0.0.1:${PORT}:8080`, '-e', `EBK_UUID_SERVER_ID=${UUID_SERVER}`, '-e', 'EBK_AUTH_ENABLE_REGISTER=true',
            '-e', 'EBK_CRON_ENABLE_REMOVE_EXPIRED_TOKENS=false', '-e', 'EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false', runtime.imageId);
        await save('runtime.json', runtime); command('start', runtime.containerId); await ready(`${runtime.url}/healthz.json`);
        phase = 'configuration'; const capability = await capabilities(runtime, true); await save('prepare-capabilities.json', capability);
        phase = 'runtime'; const checked = await verifyRuntime(runtime); await save('prepare-verification.json', checked);
        const modelRecorded = await readJson(resolve(folder, 'model-image.json')); modelRecorded.binarySHA256 = checked.modelBinarySHA256;
        await save('model-image.json', modelRecorded);
        await save('launch-parameters.json', { targetSerial: 'emulator-5554', qaOnly: true,
            runner: 'com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner',
            class: 'com.finexy.mobile.AIPendingCaptureProcessE2ETest',
            instrumentationArguments: { 'finexy.ai.source.url': 'http://10.0.2.2:18141' },
            modelAudit: 'http://10.0.2.2:18144/audit', modelBaseline: await audit(runtime),
            deviceCommandsExecuted: false, hostRuntimeOnly: true });
        console.log(JSON.stringify({ state: 'prepared', revision, evidence: relative(root, folder), serviceId: runtime.containerId, modelId: runtime.modelId, deviceUsed: false }));
    } else {
        runtime = await readJson(runtimePath); validateRuntime(runtime);
        if (operation === 'verify') {
            phase = 'inputs'; const inputs = await verifyInputs();
            phase = 'runtime'; const checked = await verifyRuntime(runtime);
            phase = 'capabilities'; const capability = await capabilities(runtime);
            await save('verification.json', { state: 'passed', verifiedAtUtc: new Date().toISOString(), scope: 'Host runtime and AI capabilities only',
                inputs: inputs.summary, runtime: checked, capabilities: capability, androidTestsExecuted: false, deviceUsed: false });
            console.log(JSON.stringify({ state: 'verified', revision, serviceId: runtime.containerId, modelId: runtime.modelId, modelAudit: capability.modelAuditAfter, deviceUsed: false }));
        } else console.log(JSON.stringify(await cleanup(runtime)));
    }
} catch (error) {
    let cleaned = false;
    if (runtime && operation === 'prepare') { try { await cleanup(runtime); cleaned = true; } catch { /* Retain exact IDs for manual guarded cleanup. */ } }
    const failure = { state: 'failed', operation, phase, errorType: error.name, cleanupRequired: Boolean(runtime) && !cleaned, deviceUsed: false };
    if (typeof error.code === 'string') failure.errorCode = error.code;
    if (error.code === 'ERR_ASSERTION') failure.assertion = { operator: error.operator,
        actualType: typeof error.actual, expectedType: typeof error.expected,
        ...(typeof error.actual === 'number' ? { actualNumber: error.actual } : {}),
        ...(typeof error.expected === 'number' ? { expectedNumber: error.expected } : {}) };
    try { await save(`failure-${operation}.json`, failure); } catch { /* Evidence directory may not yet exist. */ }
    console.error(JSON.stringify(failure)); process.exitCode = 1;
}
