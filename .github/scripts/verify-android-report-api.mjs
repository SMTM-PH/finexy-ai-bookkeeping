import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { access, cp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { isAbsolute, relative, resolve } from 'node:path';

// Host-only preparation. This program never invokes ADB, an emulator, installation,
// instrumentation or a device probe. Future device commands are written for review.
const docker = process.env.DOCKER_CLI || 'C:/Users/abc123/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe';
const root = resolve('.');
const evidenceRoot = resolve(root, 'artifacts/android-report-host-20261026');
const [operation, revision, ...options] = process.argv.slice(2);
if (operation === '--help' || operation === '-h') {
    console.log('Usage: node .github/scripts/verify-android-report-api.mjs prepare|verify|cleanup <freshRevision> [--android-candidate artifacts/<passed-gate>] [--frozen-source]');
    console.log('Default Android candidate: explicitly legacy 207-file report build; no automatic latest selection. Current Android files must match exactly.');
    console.log('Frozen mode requires the selected gate/source-snapshot/android tree to match its complete manifest, with no extra files. Verify retains the prepared APK pair.');
    console.log('This runner never invokes a device. Device/API/SAF acceptance remains a separate workflow.');
    process.exit(0);
}
let requestedAndroidCandidate = null, frozenSource = false;
for (let index = 0; index < options.length; index++) {
    if (options[index] === '--android-candidate') {
        assert.equal(requestedAndroidCandidate, null, 'Android candidate may be selected only once');
        requestedAndroidCandidate = options[++index];
        assert(requestedAndroidCandidate && !requestedAndroidCandidate.startsWith('--'), 'Candidate directory is missing');
    } else if (options[index] === '--frozen-source') {
        assert.equal(frozenSource, false, 'Frozen source mode may be selected only once'); frozenSource = true;
    } else assert.fail('Unknown host-runner option');
}
assert(['prepare', 'verify', 'cleanup'].includes(operation), 'Use prepare|verify|cleanup <freshRevision>');
assert(/^[a-zA-Z0-9_-]{1,40}$/.test(revision || ''), 'A bounded evidence revision is required');
const folder = resolve(evidenceRoot, revision);
const runtimePath = resolve(folder, 'runtime.json');
const PORT = 18155, UUID_SERVER = 245;
const BINARY_SHA = '6a8791d9afc3ba2f1da1f760d8124e8f0ac186e5fabd0ace4382e7fb3e7108c6';
const BACKEND_MANIFEST_SHA = '2aa56600172eac860453798ec60cf8a4cd633cddfb9e347b1ad370ec894589c5';
const WEB_SHA = '341f96c8ef2d001556670e693d8abaef0f68883d5b20e24ca3741a5f1f3aed0a';
const BASE_IMAGE = 'sha256:55acb245ad0a04e786762dd9b5155348b663ba792457a2bec12ab84616e123fd';
const backendFolder = resolve(root, 'artifacts/ledger-report-providers-20261022/budget-schema-verified');
const webFolder = resolve(root, 'artifacts/ledger-pending-20261023/browser');
const hash = value => createHash('sha256').update(value).digest('hex');
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = async (name, value) => writeFile(resolve(folder, name), `${JSON.stringify(value, null, 2)}\n`);
const command = (...args) => execFileSync(docker, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 120000 }).trim();
let phase = 'daemon';
assert(command('info', '--format', '{{.ServerVersion}}'), 'The Docker daemon must be available');

function contained(name) {
    const path = resolve(root, name), suffix = relative(root, path);
    assert(suffix && !suffix.startsWith('..') && !isAbsolute(suffix), 'Manifest path escaped the workspace');
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
    assert.equal(runtime.port, PORT); assert.equal(runtime.uuidServerId, UUID_SERVER);
    assert.equal(runtime.url, `http://127.0.0.1:${PORT}`);
    assert.equal(runtime.containerName, `finexy-report-android-api-e2e-${runtime.runId}`);
    assert.equal(runtime.networkName, `finexy-report-android-api-${runtime.runId}`);
    assert.equal(runtime.imageTag, `finexy-report-android-api:20261026-${runtime.runId}`);
    assert.equal(runtime.binarySHA256, BINARY_SHA); assert.equal(runtime.webSourceSHA256, WEB_SHA);
    assert.equal(runtime.baseImageId, BASE_IMAGE);
    assert(runtime.imageId === null || /^sha256:[a-f0-9]{64}$/.test(runtime.imageId));
    assert(runtime.containerId === null || /^[a-f0-9]{64}$/.test(runtime.containerId));
}
function owned(info, runtime) {
    validateRuntime(runtime);
    assert.equal(info.Id, runtime.containerId); assert.equal(info.Image, runtime.imageId);
    assert.equal(info.Name, `/${runtime.containerName}`);
    assert.equal(info.Config.Labels['finexy.report.android.run'], runtime.runId);
    assert.equal(info.HostConfig.AutoRemove, true); assert.equal(info.Mounts.length, 0, 'Mounted data is forbidden');
    assert.deepEqual(info.HostConfig.PortBindings['8080/tcp'], [{ HostIp: '127.0.0.1', HostPort: String(PORT) }]);
    assert(info.Config.Env.includes(`EBK_UUID_SERVER_ID=${UUID_SERVER}`));
    assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
    assert(info.Config.Env.includes('EBK_DATA_ENABLE_EXPORT=true'));
    assert(info.NetworkSettings.Networks[runtime.networkName]);
}

const androidBinding = proof => Object.fromEntries(['candidateSchema', 'artifactDirectory', 'summarySHA256', 'sourceSHA256', 'sourceMode', 'qaAppSHA256', 'qaTestSHA256'].map(key => [key, proof[key]]));
async function verifyInputs(candidateRequest = requestedAndroidCandidate, useFrozenSource = frozenSource) {
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

    const args = [resolve(root, '.github/scripts/android-report-candidate.py'),
        ...(candidateRequest ? ['--candidate', candidateRequest] : []), ...(useFrozenSource ? ['--frozen-source'] : [])];
    const checked = spawnSync('python', args, { encoding: 'utf8', timeout: 120000, maxBuffer: 4 * 1024 * 1024 });
    const androidProof = JSON.parse(checked.stdout || '{}');
    await save('android-candidate-check.json', androidProof);
    assert.equal(checked.status, 0, 'Android candidate source/APK proof failed; inspect android-candidate-check.json');
    assert.equal(androidProof.state, 'passed'); assert.equal(androidProof.sourceViewVerified, true);
    const android = await readJson(resolve(contained(androidProof.artifactDirectory), 'source-after.json'));
    return { backend, web, android, androidProof,
        runtimeFiles: { '/ezbookkeeping/ezbookkeeping': BINARY_SHA, ...publicExpected, ...templates },
        summary: { backendSourceFiles: 630, backendSourceManifestSHA256: BACKEND_MANIFEST_SHA, binarySHA256: BINARY_SHA,
            webSourceFiles: Object.keys(web.files).length, webSourceSHA256: WEB_SHA, publicFiles: distFiles.length,
            templateFiles: templateFiles.length, androidSourceFiles: Object.keys(android.files).length, androidSourceSHA256: android.sha256,
            androidSourceMode: androidProof.sourceMode, currentAndroidSourceVerified: androidProof.currentSourceVerified,
            androidCandidate: androidBinding(androidProof), qaAppSHA256: androidProof.qaAppSHA256, qaTestSHA256: androidProof.qaTestSHA256,
            qaAppPath: androidProof.qaAppPath, qaTestPath: androidProof.qaTestPath, targetPackage: 'com.finexy.mobile.qa',
            testPackage: 'com.finexy.mobile.qa.test', credentialsPersisted: false, deviceUsed: false } };
}

async function verifyRuntime(runtime) {
    const info = JSON.parse(command('inspect', runtime.containerId))[0];
    owned(info, runtime); assert.equal(info.State.Running, true);
    const expected = await readJson(resolve(folder, 'runtime-files.json'));
    const paths = Object.keys(expected), actual = {};
    for (let index = 0; index < paths.length; index += 35) {
        for (const line of command('exec', runtime.containerId, 'sha256sum', ...paths.slice(index, index + 35)).split('\n')) {
            const match = /^([a-f0-9]{64})\s+(.+)$/.exec(line); assert(match); actual[match[2]] = match[1];
        }
    }
    assert.equal(paths.filter(path => actual[path] !== expected[path]).length, 0, 'Runtime asset or binary drift');
    const staticPaths = command('exec', runtime.containerId, 'find', '/ezbookkeeping/public', '/ezbookkeeping/templates', '-type', 'f').split('\n').filter(Boolean).sort();
    assert.deepEqual(staticPaths, paths.filter(path => path !== '/ezbookkeeping/ezbookkeeping').sort());
    return { containerId: info.Id, imageId: info.Image, mounts: 0, port: PORT, uuidServerId: UUID_SERVER,
        verifiedRuntimeFiles: paths.length, binarySHA256: BINARY_SHA, mismatches: [], deviceUsed: false };
}

async function hostReportReadiness(runtime) {
    const routes = [];
    const raw = async (path, token, body, expectedStatus = 200) => {
        const response = await fetch(`${runtime.url}${path}`, {
            method: body === undefined ? 'GET' : 'POST', redirect: 'error', signal: AbortSignal.timeout(20000),
            headers: { 'Content-Type': 'application/json', 'X-Timezone-Offset': '0', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
            ...(body === undefined ? {} : { body: JSON.stringify(body) })
        });
        routes.push({ path, status: response.status });
        assert.equal(response.status, expectedStatus, `Unexpected status for ${path}`);
        return response;
    };
    const api = async (path, token, body) => {
        const data = await (await raw(`/api/${path}`, token, body)).json();
        assert.equal(data.success, true, `Unsuccessful ${path}`); assert(Object.hasOwn(data, 'result'));
        return data.result;
    };
    assert.equal((await raw('/healthz.json')).ok, true);
    const query = { ledgerId: '0', startDate: '2026-10-01', endDate: '2026-10-31', timezone: 'UTC' };
    await raw('/api/v1/reports/query.json', '', { query }, 400);
    // Both credentials stay in this call's memory; no storage-state, token or response dump is saved.
    const username = `report_host_${randomBytes(7).toString('hex')}`;
    const password = randomBytes(24).toString('hex');
    const registered = await api('register.json', '', { username, password, email: `${username}@example.invalid`,
        nickname: 'Synthetic report host readiness', language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
    assert.equal(typeof registered.token, 'string');
    const authorized = await api('authorize.json', '', { loginName: username, password });
    assert.equal(typeof authorized.token, 'string');
    const token = authorized.token;
    const before = await api('v1/accounts/list.json?with_balance=true', token);
    assert(Array.isArray(before)); assert.equal(before.length, 0);
    const page = await api('v1/reports/query.json', token, { query, count: 2 });
    assert.equal(page.dataComplete, true); assert.equal(page.matchedCount, 0);
    assert.equal(page.details.length, 0); assert.equal(page.totals.length, 0); assert.equal(page.exportAllowed, true);
    assert.equal(page.query.ledgerId, '0'); assert.match(page.snapshotHash, /^[a-f0-9]{64}$/);
    const preview = await api('v1/reports/export/preview.json', token, { query, expectedSnapshotHash: page.snapshotHash });
    assert.equal(preview.snapshotHash, page.snapshotHash); assert.equal(preview.matchedCount, 0);
    assert.equal(preview.includesHiddenAmounts, false);
    assert(Number(preview.expiresAt) > Date.now() / 1000);
    const csv = await raw('/api/v1/reports/export.csv', token, { query, expectedSnapshotHash: page.snapshotHash,
        previewToken: preview.previewToken, confirmed: true, includeHiddenAmounts: false });
    assert.match(csv.headers.get('Content-Type') || '', /^text\/csv(?:;|$)/i);
    assert.equal(csv.headers.get('X-Finexy-Report-Snapshot'), page.snapshotHash);
    assert.equal(csv.headers.get('X-Finexy-Report-Count'), '0');
    assert.equal(csv.headers.get('X-Finexy-Report-Amount-Unit'), 'minor');
    const csvText = await csv.text();
    const lines = csvText.replace(/^\uFEFF/, '').trimEnd().split(/\r?\n/);
    assert.equal(lines.length, 1); assert.equal(lines[0].split(',').length, 37);
    const after = await api('v1/accounts/list.json?with_balance=true', token);
    assert(Array.isArray(after)); assert.equal(after.length, 0);
    await api('logout.json', token);
    return { hostReadinessOnly: true, deviceUsed: false, androidHttpTestsExecuted: false,
        normalRegistrationAndLogin: true, emptyLedgerQueryPreviewAndCsv: true, csvColumns: 37,
        originalAccountCount: 0, finalAccountCount: 0, financialWriteRequests: 0,
        credentialsPersisted: false, routes };
}

async function cleanup(runtime) {
    validateRuntime(runtime);
    assert(command('info', '--format', '{{.ServerVersion}}'), 'Cleanup requires a live daemon');
    let containerAbsent = runtime.containerId === null;
    if (runtime.containerId) {
        const inspected = spawnSync(docker, ['inspect', runtime.containerId], { encoding: 'utf8' });
        containerAbsent = absent(inspected);
        if (!containerAbsent) {
            owned(JSON.parse(inspected.stdout)[0], runtime);
            command('stop', runtime.containerId);
            containerAbsent = absent(spawnSync(docker, ['inspect', runtime.containerId], { encoding: 'utf8' }));
        }
    }
    assert(containerAbsent);
    const inspectedNetwork = spawnSync(docker, ['network', 'inspect', runtime.networkName], { encoding: 'utf8' });
    let networkAbsent = absent(inspectedNetwork);
    if (!networkAbsent) {
        const network = JSON.parse(inspectedNetwork.stdout)[0];
        assert.equal(network.Name, runtime.networkName); assert.equal(network.Labels['finexy.report.android.run'], runtime.runId);
        assert.equal(Object.keys(network.Containers).length, 0, 'Owned network still has live containers');
        command('network', 'rm', runtime.networkName);
        networkAbsent = absent(spawnSync(docker, ['network', 'inspect', runtime.networkName], { encoding: 'utf8' }));
    }
    assert(networkAbsent);
    const proof = { state: 'cleaned', containerId: runtime.containerId, networkName: runtime.networkName,
        containerAbsent, networkAbsent, daemonAvailable: true, deviceUsed: false };
    await save('cleanup.json', proof);
    return proof;
}

let activeRuntime;
try {
    if (operation === 'prepare') {
        try { await access(folder); throw new Error('Evidence revision already exists; use a fresh revision'); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
        await mkdir(folder, { recursive: true });
        await save('invocation.json', { operation, revision, scriptSHA256: hash(await readFile(new URL(import.meta.url))),
            androidCandidate: requestedAndroidCandidate || 'legacy-report-207 (default)', frozenSource,
            startedAtUtc: new Date().toISOString(), processId: process.pid, deviceUsed: false });
        phase = 'inputs';
        const inputs = await verifyInputs();
        await save('input-verification.json', inputs.summary);
        await save('android-candidate.json', inputs.androidProof);
        await save('backend-source.json', inputs.backend); await save('web-source.json', inputs.web);
        await save('android-source.json', inputs.android); await save('runtime-files.json', inputs.runtimeFiles);
        await new Promise((done, fail) => { const server = createServer(); server.once('error', fail); server.listen(PORT, '127.0.0.1', () => server.close(done)); });
        assert.equal(command('image', 'inspect', 'finexy-ai-android-source:20261019-root1', '--format', '{{.Id}}'), BASE_IMAGE);
        const runId = `${randomBytes(6).toString('hex')}-${revision}`;
        activeRuntime = { runId, port: PORT, uuidServerId: UUID_SERVER, url: `http://127.0.0.1:${PORT}`,
            containerName: `finexy-report-android-api-e2e-${runId}`, networkName: `finexy-report-android-api-${runId}`,
            imageTag: `finexy-report-android-api:20261026-${runId}`, baseImageId: BASE_IMAGE,
            imageId: null, containerId: null, binarySHA256: BINARY_SHA, webSourceSHA256: WEB_SHA,
            androidCandidate: androidBinding(inputs.androidProof), deviceUsed: false };
        await save('runtime.json', activeRuntime);
        const context = resolve(folder, 'context'); await mkdir(context);
        await cp(resolve(root, 'dist'), resolve(context, 'public'), { recursive: true, errorOnExist: true });
        await cp(resolve(root, 'templates'), resolve(context, 'templates'), { recursive: true, errorOnExist: true });
        await cp(resolve(backendFolder, 'finexy'), resolve(context, 'ezbookkeeping'), { errorOnExist: true });
        await writeFile(resolve(context, 'Dockerfile'), `FROM ${BASE_IMAGE}\nUSER root\nRUN rm -rf /ezbookkeeping/public /ezbookkeeping/templates && mkdir -p /ezbookkeeping/public /ezbookkeeping/templates\nCOPY --chown=1000:1000 ezbookkeeping /ezbookkeeping/ezbookkeeping\nCOPY --chown=1000:1000 public /ezbookkeeping/public\nCOPY --chown=1000:1000 templates /ezbookkeeping/templates\nUSER 1000:1000\n`);
        phase = 'image';
        const build = spawnSync(docker, ['build', '-t', activeRuntime.imageTag, context], { encoding: 'utf8', timeout: 300000, maxBuffer: 16 * 1024 * 1024 });
        await writeFile(resolve(folder, 'build.log'), `${build.stdout || ''}\n${build.stderr || ''}`);
        assert.equal(build.status, 0, 'Owned image build failed');
        activeRuntime.imageId = command('image', 'inspect', activeRuntime.imageTag, '--format', '{{.Id}}');
        await save('runtime.json', activeRuntime);
        phase = 'container';
        command('network', 'create', '--label', `finexy.report.android.run=${runId}`, activeRuntime.networkName);
        activeRuntime.containerId = command('run', '--rm', '-d', '--name', activeRuntime.containerName,
            '--label', `finexy.report.android.run=${runId}`, '--network', activeRuntime.networkName,
            '-p', `127.0.0.1:${PORT}:8080`, '-e', `EBK_UUID_SERVER_ID=${UUID_SERVER}`,
            '-e', 'EBK_AUTH_ENABLE_REGISTER=true', '-e', 'EBK_DATA_ENABLE_EXPORT=true', activeRuntime.imageId);
        await save('runtime.json', activeRuntime);
        phase = 'health';
        let healthy = false;
        for (let i = 0; i < 100; i++) {
            try { healthy = (await fetch(`${activeRuntime.url}/healthz.json`, { signal: AbortSignal.timeout(2000), redirect: 'error' })).ok; } catch { /* Only this owned candidate is starting. */ }
            if (healthy) break;
            await new Promise(done => setTimeout(done, 200));
        }
        assert(healthy, 'Owned report service did not become healthy');
        const verified = await verifyRuntime(activeRuntime);
        await save('prepare-verification.json', verified);
        await save('launch-parameters.json', { reviewOnly: true, commandsExecuted: false,
            instruction: 'The device owner must check the authorized AVD/packages and re-run host verify immediately before device work.',
            androidCandidate: androidBinding(inputs.androidProof),
            appApk: inputs.summary.qaAppPath, appApkSHA256: inputs.summary.qaAppSHA256,
            testApk: inputs.summary.qaTestPath, testApkSHA256: inputs.summary.qaTestSHA256,
            targetPackage: 'com.finexy.mobile.qa', runner: 'com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner',
            instrumentationArguments: { class: 'com.finexy.mobile.data.LedgerReportApiE2ETest',
                'finexy.e2e.url': 'http://10.0.2.2:18155', 'finexy.e2e.disposable': 'true' },
            targetSerial: 'emulator-5554', installMode: 'install -r', forbiddenTask: 'connectedDebugAndroidTest' });
        console.log(JSON.stringify({ state: 'prepared', revision, port: PORT, uuidServerId: UUID_SERVER, deviceUsed: false }));
    } else {
        activeRuntime = await readJson(runtimePath); validateRuntime(activeRuntime);
        if (operation === 'verify') {
            phase = 'android-candidate-selection';
            let effectiveCandidate = requestedAndroidCandidate;
            if (activeRuntime.androidCandidate) {
                if (effectiveCandidate) assert.equal(relative(root, resolve(root, effectiveCandidate)).replaceAll('\\', '/'), activeRuntime.androidCandidate.artifactDirectory,
                    'Verification must use the candidate selected during prepare');
                effectiveCandidate ||= activeRuntime.androidCandidate.artifactDirectory;
                assert.equal(frozenSource, activeRuntime.androidCandidate.sourceMode === 'frozen-source-snapshot',
                    'Verification must explicitly retain the prepared source mode');
            }
            phase = 'inputs'; const inputs = await verifyInputs(effectiveCandidate, frozenSource);
            const preparedBinding = activeRuntime.androidCandidate || null;
            if (preparedBinding) assert.deepEqual(androidBinding(inputs.androidProof), preparedBinding, 'Prepared Android APK pair or source changed');
            else {
                const original = await readJson(resolve(folder, 'input-verification.json'));
                assert.equal(inputs.summary.androidSourceSHA256, original.androidSourceSHA256, 'Legacy runtime cannot adopt another source candidate');
                assert.equal(inputs.summary.qaAppSHA256, original.qaAppSHA256, 'Legacy runtime cannot replace its QA application');
                assert.equal(inputs.summary.qaTestSHA256, original.qaTestSHA256, 'Legacy runtime cannot replace its QA test package');
            }
            phase = 'runtime'; const verified = await verifyRuntime(activeRuntime);
            phase = 'host-report-readiness'; const readiness = await hostReportReadiness(activeRuntime);
            await save('verification.json', { state: 'passed', scope: 'Host isolation and report-route readiness only',
                inputs: inputs.summary, runtime: verified, readiness, androidHttpTestsExecuted: false, deviceUsed: false,
                verifiedAtUtc: new Date().toISOString() });
            console.log(JSON.stringify({ state: 'verified', revision, hostReadinessOnly: true, androidHttpTestsExecuted: false, deviceUsed: false }));
        } else console.log(JSON.stringify(await cleanup(activeRuntime)));
    }
} catch (error) {
    // Never write raw API bodies, credentials or financial response fields on failure.
    if (activeRuntime && operation === 'prepare') {
        try { await cleanup(activeRuntime); } catch { /* The failure below explicitly retains cleanup-required state. */ }
    }
    try { await save(`failure-${operation}.json`, { phase, errorType: error.name, cleanupRequired: Boolean(activeRuntime), deviceUsed: false }); } catch { /* Inputs may have failed before evidence creation. */ }
    console.error(JSON.stringify({ state: 'failed', operation, phase, errorType: error.name, cleanupRequired: Boolean(activeRuntime), deviceUsed: false }));
    process.exitCode = 1;
}
