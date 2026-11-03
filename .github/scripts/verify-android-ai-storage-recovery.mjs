import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { access, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';

const execute = promisify(execFile);
const app = 'com.finexy.mobile.qa';
const testClass = 'com.finexy.mobile.data.AIReviewProcessRecoveryE2ETest';
const resume = process.env.FINEXY_ANDROID_RECOVERY_NAMESPACE;
const namespace = resume || `ledger-write-recovery-${randomBytes(8).toString('hex')}`;
assert(/^ledger-write-recovery-[a-f0-9]{16}$/.test(namespace));
const evidence = resolve(process.env.FINEXY_ANDROID_RECOVERY_EVIDENCE || 'artifacts/ai-review-android-storage-20261019/process');
assert(evidence.startsWith(resolve('artifacts') + sep), 'Evidence must remain under repository artifacts');
const docker = process.env.FINEXY_DOCKER_CLI || 'docker.exe';
const containerName = process.env.FINEXY_ANDROID_AI_CONTAINER || 'finexy-ai-storage-e2e';
assert(['finexy-ai-storage-e2e', 'finexy-ai-source-android-e2e'].includes(containerName));
const pause = ms => new Promise(done => setTimeout(done, ms));
async function command(program, args, allowFailure = false, timeout = 30_000) {
    try { return (await execute(program, args, { encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024 })).stdout; }
    catch (error) { if (allowFailure) return error.stdout || ''; throw new Error(`${program} failed; see retained phase evidence`); }
}
const adb = (...args) => command('adb', ['-s', 'emulator-5554', ...args]);
const witness = async () => JSON.parse(await adb('exec-out', 'run-as', app, 'cat', `files/sync-recovery/${namespace}.json`));
const audit = () => fetch('http://127.0.0.1:18142/__qa/meta').then(r => r.json());
async function phase(method, filename) {
    const output = await command('adb', ['-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'finexy.recovery.url', 'http://10.0.2.2:18142', '-e', 'finexy.recovery.namespace', namespace,
        '-e', 'class', `${testClass}#${method}`, `${app}.test/androidx.test.runner.AndroidJUnitRunner`], false, 150_000);
    const path = resolve(evidence, filename); await writeFile(path, output);
    const verify = `import importlib.util,sys,json,pathlib
s=importlib.util.spec_from_file_location('gate','.github/scripts/verify-android-instrumentation.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
expected=m.expected_tests('ai-storage-process');selected={x for x in expected if x[1]==sys.argv[2]};assert len(selected)==1
print(json.dumps(m.verify_output(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig'),selected)))`;
    await command('python', ['-c', verify, path, method]);
}
await mkdir(evidence, { recursive: true });
try {
    const info = JSON.parse(await command(docker, ['inspect', containerName]))[0];
    assert.equal(info.Name, `/${containerName}`); assert.deepEqual(info.Mounts, []); assert(info.State.Running);
    const acceptedImages = new Map([
        ['finexy-ai-mobile-source:20261007-root2', 'sha256:0549baad737ffb95f06fcd2abd1ed05e97664391fc9ce7ef4952564f1b429fc3'],
        ['finexy-ai-android-source:20261019-root1', 'sha256:55acb245ad0a04e786762dd9b5155348b663ba792457a2bec12ab84616e123fd'],
    ]);
    assert(acceptedImages.has(info.Config.Image));
    assert.equal(info.Image, acceptedImages.get(info.Config.Image));
    for (const entry of ['EBK_UUID_SERVER_ID=249', 'EBK_AUTH_ENABLE_REGISTER=true', 'EBK_USER_ENABLE_SCHEDULED_TRANSACTION=true', 'EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false']) assert(info.Config.Env.includes(entry));
    assert(info.HostConfig.PortBindings['8080/tcp'].some(x => x.HostIp === '127.0.0.1' && x.HostPort === '18141'));
    const proxy = await audit(); assert.equal(proxy.fixture, 'finexy-ai-storage-loss-proxy'); assert.equal(proxy.backend, '127.0.0.1:18141');
    const initial = resume ? await readFile(resolve(evidence, 'initial-audit.json'), 'utf8').then(JSON.parse).catch(() => ({ requests: {}, dropped: {} })) : proxy;
    if (!resume) {
        assert(!await access(resolve(evidence, 'prepare.log')).then(() => true, () => false), 'Use a fresh evidence directory');
        await writeFile(resolve(evidence, 'initial-audit.json'), JSON.stringify(initial, null, 2));
    }
    assert.equal((await adb('get-state')).trim(), 'device');
    const packages = await adb('shell', 'pm', 'list', 'packages', '-U', 'com.finexy.mobile');
    const uids = new Map([...packages.matchAll(/^package:(\S+) uid:(\d+)\s*$/gm)].map(x => [x[1], x[2]]));
    assert(uids.has(app) && uids.get(app) !== uids.get('com.finexy.mobile'));
    const instrumentation = await adb('shell', 'pm', 'list', 'instrumentation');
    assert(instrumentation.includes(`instrumentation:${app}.test/androidx.test.runner.AndroidJUnitRunner (target=${app})`));
    console.log('PASS disposable image, proxy, QA UID and instrumentation guards');
    if (!resume) await phase('prepareLostCreateLostConfirmAndKnownReceipt', 'prepare.log');
    const prepared = resume ? JSON.parse(await readFile(resolve(evidence, 'prepared-witness.json'), 'utf8')) : await witness();
    assert.equal(prepared.state, 'PREPARED'); assert.equal(prepared.workerPid, 0);
    if (!resume) await writeFile(resolve(evidence, 'prepared-witness.json'), JSON.stringify(prepared, null, 2));
    const before = resume ? JSON.parse(await readFile(resolve(evidence, 'before-worker-audit.json'), 'utf8')) : await audit();
    const drops = {};
    for (const path of ['/api/v1/ai/review/create.json', '/api/v1/ai/review/confirm.json', '/api/v1/ai/review/get.json']) {
        drops[path] = before.dropped[path] - (initial.dropped[path] || 0); assert(drops[path] >= 1);
    }
    if (!resume) await writeFile(resolve(evidence, 'before-worker-audit.json'), JSON.stringify(before, null, 2));
    const metadata = JSON.parse(await adb('exec-out', 'run-as', app, 'cat', `files/ai-storage-recovery/${namespace}.json`));
    assert(/^[a-f0-9-]{36}$/.test(metadata.workId) && Number.isSafeInteger(metadata.notBefore));
    if (!resume) await adb('shell', 'am', 'kill', app);
    assert(!(await command('adb', ['-s', 'emulator-5554', 'shell', 'pidof', app], true)).trim().split(/\s+/).includes(String(prepared.preparePid)));
    console.log('PASS real successful responses dropped; prepare exited and its process terminated');
    let current;
    const deadline = Date.now() + 150_000;
    while (Date.now() < deadline) {
        current = await witness();
        assert.equal(current.preparePid, prepared.preparePid, 'Resume must observe the original prepared request');
        if (current.state === 'SUCCEEDED') break;
        assert(['PREPARED', 'RUNNING'].includes(current.state), `Recovery state ${current.state}`);
        const sql = `"SELECT state FROM WorkSpec WHERE id='${metadata.workId}';"`;
        const state = (await adb('shell', 'run-as', app, 'sqlite3', '-readonly', 'no_backup/androidx.work.workdb', sql)).trim();
        assert(['0', '1', '2'].includes(state), 'Owned WorkManager request is missing or terminal without success');
        await pause(1000);
    }
    assert.equal(current?.state, 'SUCCEEDED'); assert(current.workerPid > 0 && current.workerPid !== prepared.preparePid);
    assert(current.finish >= current.start && current.start > 0);
    await writeFile(resolve(evidence, 'worker-witness.json'), JSON.stringify(current, null, 2));
    await pause(2000);
    const after = await audit();
    assert.equal(after.requests['/api/v1/ai/review/create.json'] - before.requests['/api/v1/ai/review/create.json'], 1);
    assert.equal(after.requests['/api/v1/ai/review/confirm.json'] - before.requests['/api/v1/ai/review/confirm.json'], 1);
    assert.equal(after.requests['/api/v1/ai/review/resolve.json'] || 0, 0);
    assert.equal(after.requests['/api/v1/transactions/add.json'] || 0, 0);
    await writeFile(resolve(evidence, 'after-worker-audit.json'), JSON.stringify(after, null, 2));
    await adb('shell', 'am', 'kill', app);
    assert(!(await command('adb', ['-s', 'emulator-5554', 'shell', 'pidof', app], true)).trim().split(/\s+/).includes(String(current.workerPid)));
    console.log('PASS new-PID production Worker completed; one create replay and one confirm replay; known receipt only read');
    await phase('verifyNewProcessRecoveredOriginalOperationsWithoutDuplicateFinances', 'verify.log');
    assert.deepEqual(await witness(), current);
    await writeFile(resolve(evidence, 'summary.json'), JSON.stringify({ passed: true, preparePid: prepared.preparePid,
        workerPid: current.workerPid, workId: metadata.workId, resumedExistingRequest: Boolean(resume), systemScheduled: true, realResponseDrops: drops,
        verifyAfterWorkerCompleted: true, originalRequestReplay: true, knownReceiptReadOnly: true,
        noDuplicateFinancialWrite: true, newerAndUnconfirmedDraftsPreserved: true, qaOnly: true }, null, 2));
    console.log('PASS new verify process: original IDs, once-only refunds, acknowledged requests and preserved raw input');
} catch (error) {
    await writeFile(resolve(evidence, 'failure.txt'), String(error.message));
    console.error(`AI storage recovery gate failed: ${error.message}`); process.exitCode = 1;
}
