import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { cp, mkdir, readFile, readdir, writeFile, access } from 'node:fs/promises';
import { resolve, relative, isAbsolute } from 'node:path';
import { createServer } from 'node:net';

// Only owned disposable candidates. No user service, mounted data, ADB or saved
// browser credentials. start is called only after the root's final client gate.
const docker = process.env.DOCKER_CLI || 'C:/Users/abc123/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe';
const workspace = resolve('.');
const phase = resolve('artifacts/ledger-pending-20261023/browser');
const [operation, revision, sourceManifest, portRaw = '18131', uuidRaw = '242'] = process.argv.slice(2);
assert(['start', 'verify', 'cleanup'].includes(operation));
assert(/^[a-zA-Z0-9_-]{1,40}$/.test(revision || ''), 'fresh evidence revision required');
const folder = resolve(phase, `runtime-${revision}`);
const runtimePath = resolve(folder, 'runtime.json');
const hash = value => createHash('sha256').update(value).digest('hex');
const command = (...args) => execFileSync(docker, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 120000 }).trim();
// An unavailable daemon is not proof that a container or network is absent.
assert(command('info', '--format', '{{.ServerVersion}}'), 'Docker daemon must be available');
const inspect = id => JSON.parse(command('inspect', id))[0];
function confirmedAbsent(result) {
    if (result.status === 0) return false;
    assert.equal(result.status, 1, 'Docker inspection did not complete');
    assert.match(result.stderr || '', /No such (?:object|container|network)|network[^\r\n]*not found/i,
        'Docker inspection failed without proving absence');
    return true;
}
async function save(name, value) { await writeFile(resolve(folder, name), `${JSON.stringify(value, null, 2)}\n`); }
async function files(directory) {
    const result = [];
    for (const entry of await readdir(directory, { withFileTypes: true })) {
        const path = resolve(directory, entry.name);
        if (entry.isDirectory()) result.push(...await files(path)); else if (entry.isFile()) result.push(path);
    }
    return result.sort();
}
function owned(info, runtime) {
    assert.equal(info.Id, runtime.containerId); assert.equal(info.Image, runtime.imageId);
    assert.equal(info.Name, `/${runtime.containerName}`); assert(info.Name.startsWith('/finexy-pending-web-e2e-'));
    assert.equal(info.Config.Labels['finexy.pending.run'], runtime.runId);
    assert.equal(info.HostConfig.AutoRemove, true); assert.deepEqual(info.Mounts, []);
    assert.deepEqual(info.HostConfig.PortBindings['8080/tcp'], [{ HostIp: '127.0.0.1', HostPort: String(runtime.port) }]);
    assert(info.Config.Env.includes(`EBK_UUID_SERVER_ID=${runtime.uuidServerId}`));
    assert(info.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
    assert(info.NetworkSettings.Networks[runtime.networkName]);
}
async function verify(runtime) {
    const info = inspect(runtime.containerId); owned(info, runtime); assert.equal(info.State.Running, true);
    const expected = JSON.parse(await readFile(resolve(folder, 'runtime-files.json'), 'utf8'));
    const actual = {};
    const paths = Object.keys(expected);
    for (let index = 0; index < paths.length; index += 40) {
        for (const line of command('exec', runtime.containerId, 'sha256sum', ...paths.slice(index, index + 40)).split('\n')) {
            const match = /^([a-f0-9]{64})\s+(.+)$/.exec(line); assert(match); actual[match[2]] = match[1];
        }
    }
    const mismatches = paths.filter(path => expected[path] !== actual[path]);
    await save('runtime-verification.json', { containerId: info.Id, imageId: info.Image, mounts: [], files: paths.length,
        binarySHA256: expected['/ezbookkeeping/ezbookkeeping'], mismatches });
    assert.deepEqual(mismatches, []);
}
if (operation === 'start') {
    try { await access(folder); throw new Error('Evidence directory already exists; choose a new revision'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    const port = Number(portRaw), uuidServerId = Number(uuidRaw);
    const cronUuidServerId = 239;
    assert(Number.isInteger(port) && port >= 18130 && port <= 18199 && port !== 18080);
    assert(Number.isInteger(uuidServerId) && uuidServerId >= 240 && uuidServerId <= 255);
    assert.notEqual(cronUuidServerId, uuidServerId);
    assert(sourceManifest, 'pass the source manifest recorded after the final successful client build');
    const manifestPath = resolve(sourceManifest);
    const source = JSON.parse((await readFile(manifestPath, 'utf8')).replace(/^\uFEFF/, ''));
    assert(source.files && /^[a-f0-9]{64}$/.test(source.sha256));
    for (const [name, digest] of Object.entries(source.files)) {
        const path = resolve(workspace, name), rel = relative(workspace, path);
        assert(rel && !rel.startsWith('..') && !isAbsolute(rel));
        assert.equal(hash(await readFile(path)), digest, `client source drift: ${name}`);
    }
    await new Promise((done, fail) => { const server = createServer(); server.once('error', fail); server.listen(port, '127.0.0.1', () => server.close(done)); });
    const candidateFolder = resolve('artifacts/ledger-report-providers-20261022/budget-schema-verified');
    const accepted = JSON.parse(await readFile(resolve(candidateFolder, 'acceptance-summary.json'), 'utf8'));
    assert.equal(accepted.state, 'passed'); assert.equal(accepted.CGO.staticBuildExitCode, 0);
    const binary = await readFile(resolve(candidateFolder, 'finexy'));
    assert.equal(hash(binary), accepted.binarySHA256);
    assert.equal(accepted.binarySHA256, '6a8791d9afc3ba2f1da1f760d8124e8f0ac186e5fabd0ace4382e7fb3e7108c6');
    const baseImage = JSON.parse(command('image', 'inspect', 'finexy-ai-android-source:20261019-root1'))[0];
    assert(/^sha256:[a-f0-9]{64}$/.test(baseImage.Id));
    await mkdir(resolve(folder, 'context'), { recursive: true });
    await cp(resolve('dist'), resolve(folder, 'context/public'), { recursive: true, errorOnExist: true });
    await cp(resolve('templates'), resolve(folder, 'context/templates'), { recursive: true, errorOnExist: true });
    await writeFile(resolve(folder, 'context/ezbookkeeping'), binary);
    await writeFile(resolve(folder, 'context/Dockerfile'), `FROM ${baseImage.Id}\nUSER root\nRUN rm -rf /ezbookkeeping/public && mkdir -p /ezbookkeeping/public\nCOPY --chown=1000:1000 ezbookkeeping /ezbookkeeping/ezbookkeeping\nCOPY --chown=1000:1000 public /ezbookkeeping/public\nCOPY --chown=1000:1000 templates /ezbookkeeping/templates\nUSER 1000:1000\n`);
    const expected = { '/ezbookkeeping/ezbookkeeping': hash(binary) };
    for (const path of await files(resolve(folder, 'context/public'))) expected[`/ezbookkeeping/public/${relative(resolve(folder, 'context/public'), path).replaceAll('\\', '/')}`] = hash(await readFile(path));
    await save('runtime-files.json', expected); await save('source.json', source);
    const runId = `${randomBytes(6).toString('hex')}-${revision}`;
    const runtime = { runId, port, uuidServerId, cronUuidServerId, url: `http://127.0.0.1:${port}`, containerName: `finexy-pending-web-e2e-${runId}`,
        networkName: `finexy-pending-web-${runId}`, imageTag: `finexy-pending-web:20261023-${runId}`, sourceManifest: relative(workspace, manifestPath),
        sourceSHA256: source.sha256, binarySHA256: hash(binary), containerId: null, imageId: null, baseImageId: baseImage.Id };
    await save('runtime.json', runtime);
    const build = spawnSync(docker, ['build', '-t', runtime.imageTag, resolve(folder, 'context')], { encoding: 'utf8', timeout: 300000, maxBuffer: 16 * 1024 * 1024 });
    await writeFile(resolve(folder, 'build.log'), `${build.stdout || ''}\n${build.stderr || ''}`);
    assert.equal(build.status, 0, 'runtime image build');
    runtime.imageId = command('image', 'inspect', runtime.imageTag, '--format', '{{.Id}}');
    command('network', 'create', '--label', `finexy.pending.run=${runId}`, runtime.networkName);
    await save('runtime.json', runtime);
    try {
        runtime.containerId = command('run', '--rm', '-d', '--name', runtime.containerName, '--label', `finexy.pending.run=${runId}`,
            '--network', runtime.networkName, '-p', `127.0.0.1:${port}:8080`, '-e', 'EBK_AUTH_ENABLE_REGISTER=true',
            '-e', 'EBK_DATA_ENABLE_EXPORT=true', '-e', 'EBK_DATA_ENABLE_IMPORT=true', '-e', 'EBK_SECURITY_ENABLE_API_TOKEN=true',
            '-e', 'EBK_MCP_ENABLE_MCP=true', '-e', `EBK_UUID_SERVER_ID=${uuidServerId}`, runtime.imageId);
        await save('runtime.json', runtime); await verify(runtime);
        let healthy = false;
        for (let index = 0; index < 80; index++) {
            try { healthy = (await fetch(`${runtime.url}/healthz.json`)).ok; } catch { /* starting owned candidate */ }
            if (healthy) break; await new Promise(done => setTimeout(done, 250));
        }
        assert(healthy, 'owned candidate health');
        console.log(JSON.stringify({ state: 'ready', runtime: relative(workspace, runtimePath), url: runtime.url, sourceSHA256: source.sha256 }));
    } catch (error) { await save('start-failure.json', { errorType: error.name, message: error.message, cleanupRequired: true }); throw error; }
} else {
    const runtime = JSON.parse(await readFile(runtimePath, 'utf8'));
    assert(new RegExp(`^[a-f0-9]{12}-${revision}$`).test(runtime.runId));
    assert.equal(runtime.containerName, `finexy-pending-web-e2e-${runtime.runId}`);
    assert.equal(runtime.networkName, `finexy-pending-web-${runtime.runId}`);
    assert(runtime.imageId === null && !runtime.containerId || /^sha256:[a-f0-9]{64}$/.test(runtime.imageId || ''));
    assert(Number.isInteger(runtime.port) && runtime.port >= 18130 && runtime.port <= 18199);
    assert.equal(runtime.cronUuidServerId, 239); assert.notEqual(runtime.cronUuidServerId, runtime.uuidServerId);
    if (operation === 'verify') await verify(runtime);
    else {
        let containerAbsent = !runtime.containerId;
        if (runtime.containerId) {
            const status = spawnSync(docker, ['inspect', runtime.containerId], { encoding: 'utf8' });
            if (confirmedAbsent(status)) containerAbsent = true;
            else { owned(JSON.parse(status.stdout)[0], runtime); command('stop', runtime.containerId); containerAbsent = confirmedAbsent(spawnSync(docker, ['inspect', runtime.containerId], { encoding: 'utf8' })); }
        }
        assert(containerAbsent, 'owned temporary container still exists');
        const networkStatus = spawnSync(docker, ['network', 'inspect', runtime.networkName], { encoding: 'utf8' });
        let networkAbsent = confirmedAbsent(networkStatus);
        if (!networkAbsent) {
            const network = JSON.parse(networkStatus.stdout)[0];
            assert.equal(network.Name, runtime.networkName); assert.equal(network.Labels['finexy.pending.run'], runtime.runId);
            assert.equal(Object.keys(network.Containers).length, 0); command('network', 'rm', runtime.networkName);
            networkAbsent = confirmedAbsent(spawnSync(docker, ['network', 'inspect', runtime.networkName], { encoding: 'utf8' }));
        }
        assert(networkAbsent); await save('cleanup.json', { containerId: runtime.containerId, networkName: runtime.networkName, containerAbsent, networkAbsent });
        console.log(JSON.stringify({ state: 'cleaned', containerAbsent, networkAbsent }));
    }
}
