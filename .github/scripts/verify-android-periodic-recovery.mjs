import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const execute = promisify(execFile);
const serial = 'emulator-5554';
const application = 'com.finexy.mobile.qa';
const runner = `${application}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = 'com.finexy.mobile.data.PeriodicProcessRecoveryE2ETest';
const container = process.env.FINEXY_ANDROID_E2E_CONTAINER;
assert(container === 'finexy-periodic-android-e2e', 'A reserved disposable recovery container is required');
const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const evidenceDirectory = resolve(repository, process.env.FINEXY_ANDROID_RECOVERY_EVIDENCE || 'artifacts/periodic-mobile-android-20261005/process-recovery');
assert(evidenceDirectory.startsWith(resolve(repository, 'artifacts') + sep), 'Evidence must stay inside repository artifacts');
const namespace = `ledger-write-recovery-${randomBytes(8).toString('hex')}`;
const evidenceFile = `files/sync-recovery/${namespace}.json`;
const delay = milliseconds => new Promise(resolveDelay => setTimeout(resolveDelay, milliseconds));

async function command(program, arguments_, allowFailure = false, timeout = 30_000) {
    try {
        return (await execute(program, arguments_, { encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024 })).stdout;
    } catch (error) {
        if (allowFailure) return error.stdout || '';
        // Device/server errors may contain data: keep them out of user-facing output.
        throw new Error(`${program} command failed; recovery sequence stopped`);
    }
}
const adb = (...arguments_) => command('adb', ['-s', serial, ...arguments_]);

function verifySingleTest(output, method) {
    const statuses = [];
    let fields = {};
    for (const line of output.replaceAll('\r', '').split('\n')) {
        if (line.startsWith('INSTRUMENTATION_STATUS: ')) {
            const field = line.slice('INSTRUMENTATION_STATUS: '.length);
            const split = field.indexOf('=');
            assert(split > 0, 'Malformed instrumentation status');
            const key = field.slice(0, split);
            assert(!(key in fields), 'Repeated instrumentation status field');
            fields[key] = field.slice(split + 1);
        } else if (line.startsWith('INSTRUMENTATION_STATUS_CODE: ')) {
            const code = Number(line.slice('INSTRUMENTATION_STATUS_CODE: '.length));
            assert.equal(fields.class, testClass, 'Unexpected recovery test class');
            assert.equal(fields.test, method, 'Unexpected recovery test method');
            assert.equal(fields.id, 'AndroidJUnitRunner');
            assert.equal(fields.numtests, '1', 'The phase must discover exactly one mandatory test');
            assert.equal(fields.current, '1');
            statuses.push(code);
            fields = {};
        }
    }
    assert.deepEqual(statuses, [1, 0], 'The recovery phase must start and pass; skips are forbidden');
    assert.equal(Object.keys(fields).length, 0, 'Truncated instrumentation bundle');
    assert.deepEqual([...output.matchAll(/^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$/gm)].map(match => Number(match[1])), [-1]);
    assert.match(output, /^OK \(1 test\)\s*$/m);
    assert(!/INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_RESULT: shortMsg=|FAILURES!!!/.test(output), 'Runner failed');
}

async function phase(method, filename) {
    const output = await command('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'finexy.recovery.url', 'http://10.0.2.2:18086',
        '-e', 'finexy.recovery.namespace', namespace,
        '-e', 'class', `${testClass}#${method}`, runner], false, 120_000);
    await writeFile(resolve(evidenceDirectory, filename), output);
    verifySingleTest(output, method);
}

async function readWitness() {
    const output = await adb('exec-out', 'run-as', application, 'cat', evidenceFile);
    const witness = JSON.parse(output);
    assert.deepEqual(Object.keys(witness).sort(), ['finish', 'preparePid', 'start', 'state', 'workerPid']);
    for (const field of ['preparePid', 'workerPid', 'start', 'finish']) assert(Number.isSafeInteger(witness[field]) && witness[field] >= 0, 'Invalid witness metadata');
    assert(['PREPARED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY', 'CANCELLED', 'INVALID_PROCESS'].includes(witness.state), 'Unexpected worker state');
    return witness;
}

await mkdir(evidenceDirectory, { recursive: true });
try {
    const inspection = JSON.parse(await command('docker', ['inspect', container]))[0];
    assert.deepEqual(inspection.Mounts, [], 'Recovery must never touch a user data volume');
    assert.equal(inspection.Config.Image, 'finexy-ledger-periodic-candidate:20261004-root8');
    assert(inspection.Config.Env.includes('EBK_UUID_SERVER_ID=235'));
    assert.equal(inspection.Name, '/finexy-periodic-android-e2e');
    assert(inspection.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'), 'Disposable registration is required');
    assert(inspection.NetworkSettings.Ports['8080/tcp']?.some(port => port.HostPort === '18086'), 'Recovery port must match the inspected container');
    const health = await (await fetch('http://127.0.0.1:18086/healthz.json')).json();
    assert.equal(health.success, true, 'Disposable server must be healthy');
    assert.equal((await adb('get-state')).trim(), 'device');
    const packages = await adb('shell', 'pm', 'list', 'packages', '-U', 'com.finexy.mobile');
    const uids = new Map([...packages.matchAll(/^package:(\S+) uid:(\d+)\s*$/gm)].map(match => [match[1], Number(match[2])]));
    assert(uids.has(application), 'Install the isolated QA application first');
    assert(!uids.has('com.finexy.mobile') || uids.get(application) !== uids.get('com.finexy.mobile'), 'QA must have a different UID from the main app');
    const instrumentation = await adb('shell', 'pm', 'list', 'instrumentation');
    assert(instrumentation.split(/\r?\n/).includes(`instrumentation:${runner} (target=${application})`), 'Instrumentation must target only the QA package');
    console.log('PASS disposable server, separate QA UID and instrumentation guards');

    let cronRunning = false;
    const timer = setInterval(async () => {
        if (cronRunning) return;
        cronRunning = true;
        try { await command('docker', ['exec', container, '/ezbookkeeping/ezbookkeeping', 'cron', 'run', '--name', 'CreateScheduledTransaction']); }
        catch { /* prepare has a bounded wait and asserts actual dispatch */ }
        finally { cronRunning = false; }
    }, 1000);
    try { await phase('prepareLostPeriodicResponseAndPersistentIntent', 'prepare.txt'); }
    finally { clearInterval(timer); }

    const prepared = await readWitness();
    assert.equal(prepared.state, 'PREPARED', 'Worker must not execute inside prepare instrumentation');
    assert(prepared.preparePid > 0 && prepared.workerPid === 0 && prepared.start === 0 && prepared.finish === 0);
    await writeFile(resolve(evidenceDirectory, 'prepared-witness.json'), JSON.stringify(prepared, null, 2));
    console.log('PASS prepare persisted the original nondefault request after a lost response');

    await adb('shell', 'am', 'kill', application);
    const previousPids = await command('adb', ['-s', serial, 'shell', 'pidof', application], true);
    assert(!previousPids.trim().split(/\s+/).includes(String(prepared.preparePid)), 'Prepare process must terminate before background recovery');
    const bootstrap = await adb('shell', 'am', 'start', '-W', '-n', `${application}/com.finexy.mobile.MainActivity`);
    await writeFile(resolve(evidenceDirectory, 'bootstrap.txt'), bootstrap);
    const bootstrapPid = Number((await adb('shell', 'pidof', '-s', application)).trim());
    assert(Number.isSafeInteger(bootstrapPid) && bootstrapPid > 0 && bootstrapPid !== prepared.preparePid, 'Bootstrap must create a new QA process');
    console.log('PASS prepare exited; only the separate QA process was started');

    let witness;
    const deadline = Date.now() + 150_000;
    while (Date.now() < deadline) {
        witness = await readWitness();
        if (witness.state === 'SUCCEEDED') break;
        assert(['PREPARED', 'RUNNING'].includes(witness.state), 'Background worker did not succeed');
        await delay(500);
    }
    assert.equal(witness?.state, 'SUCCEEDED', 'Background worker timed out');
    assert(witness.workerPid > 0 && witness.workerPid !== prepared.preparePid && witness.start > 0 && witness.finish >= witness.start);
    await writeFile(resolve(evidenceDirectory, 'worker-witness.json'), JSON.stringify(witness, null, 2));
    // The witness is written before returning to WorkManager; let its final state commit.
    await delay(2000);
    console.log('PASS production sync completed in a new background process before verify');

    await phase('verifyPeriodicWorkerReplayedOnceAndCleanUp', 'verify.txt');
    assert.deepEqual(await readWitness(), witness, 'Verify must not rerun the worker in its instrumentation process');
    await writeFile(resolve(evidenceDirectory, 'summary.json'), JSON.stringify({
        passed: true, syntheticAccountsOnly: true, container, image: inspection.Image,
        application, namespace, preparePid: prepared.preparePid, bootstrapPid,
        workerPid: witness.workerPid, workerStart: witness.start, workerFinish: witness.finish,
        verifyPassedAfterBackgroundCompletion: true,
        originalRequestReplayedOnce: true, defaultLedgerUnchanged: true, fixtureCleaned: true,
    }, null, 2));
    console.log('PASS verify: one posting, one balance change, acknowledged queue and unchanged default ledger');
} catch (error) {
    await writeFile(resolve(evidenceDirectory, 'failure.txt'), String(error.message));
    console.error(`Recovery verification failed: ${error.message}`);
    process.exitCode = 1;
}
