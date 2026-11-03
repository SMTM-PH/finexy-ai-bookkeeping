import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';

const run = promisify(execFile);
const container = 'finexy-template-editor-android-e2e';
assert(process.argv.length === 2 || (process.argv.length === 4 && process.argv[2] === '--evidence-dir'));
const evidence = resolve(process.argv[3] || 'artifacts/template-editor-20261005');
assert(evidence.startsWith(resolve('artifacts') + sep));
const testClass = 'com.finexy.mobile.NormalTemplateNavigationE2ETest';
const runner = 'com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner';
const command = async (program, args, timeout = 30000) => (await run(program, args, { encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024 })).stdout;
const inspection = JSON.parse(await command('docker', ['inspect', container]))[0];
assert.equal(inspection.Name, `/${container}`);
assert.equal(inspection.Config.Image, 'finexy-template-creation-worker-candidate:20261005-root2');
assert.equal(inspection.Image, 'sha256:56e53787747c76e5fce56030a0ff078b03128354d0071d9d5ac77190f0a045bd');
assert.deepEqual(inspection.Mounts, []);
assert(inspection.Config.Env.includes('EBK_UUID_SERVER_ID=243'));
assert(inspection.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(inspection.NetworkSettings.Ports['8080/tcp'].some(port => port.HostPort === '18119' && port.HostIp === '127.0.0.1'));
assert.equal((await (await fetch('http://127.0.0.1:18119/healthz.json')).json()).success, true);
const old = JSON.parse(await command('docker', ['inspect', 'finexy-template-editor-legacy-e2e']))[0];
assert.equal(old.Config.Image, 'finexy-template-submission-candidate:20261005-root1');
assert.equal(old.Image, 'sha256:4b61a406d48be8a93472c4832ec199f56e6765fe5eb11e109c8473356133364b');
assert.equal(old.State.Running, true); assert.deepEqual(old.Mounts, []);
assert(old.Config.Env.includes('EBK_UUID_SERVER_ID=244'));
assert.deepEqual(old.NetworkSettings.Ports['8080/tcp'], [{ HostPort:'18120', HostIp:'127.0.0.1' }]);
for (const item of [inspection, old]) {
    assert(item.Config.Env.includes('EBK_DUPLICATE_CHECKER_DUPLICATE_SUBMISSIONS_INTERVAL=0'));
    assert(item.Config.Env.includes('EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false'));
}
assert.equal((await fetch('http://127.0.0.1:18120/api/v1/transaction/templates/capabilities.json')).status, 404);

const packages = await command('adb', ['-s', 'emulator-5554', 'shell', 'pm', 'list', 'packages', '-U', 'com.finexy.mobile']);
const uids = new Map([...packages.matchAll(/^package:(\S+) uid:(\d+)\s*$/gm)].map(m => [m[1], m[2]]));
assert(uids.has('com.finexy.mobile.qa') && uids.get('com.finexy.mobile.qa') !== uids.get('com.finexy.mobile'));
assert((await command('adb', ['-s', 'emulator-5554', 'shell', 'pm', 'list', 'instrumentation'])).split(/\r?\n/).includes(`instrumentation:${runner} (target=com.finexy.mobile.qa)`));
await mkdir(evidence, { recursive: true });
await writeFile(resolve(evidence, 'container-guard.json'), JSON.stringify({ containerId: inspection.Id, imageId: inspection.Image, mounts: [], uuidServerId: 243, port: 18119, qaUid: uids.get('com.finexy.mobile.qa'), mainUid: uids.get('com.finexy.mobile') }, null, 2));
console.log('PASS isolated image, endpoint, QA UID and runner guards');
const source = await readFile('android/app/src/qaAndroidTest/java/com/finexy/mobile/NormalTemplateNavigationE2ETest.kt', 'utf8');
const expected = [...source.matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)].map(m => m[1]);
assert.equal(expected.length, 13);
let output;
try {
    output = await command('adb', ['-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'finexy.template.e2e.url', 'http://10.0.2.2:18119', '-e', 'finexy.template.legacy.url', 'http://10.0.2.2:18120', '-e', 'class', testClass, runner], 900000);
} catch (error) {
    await writeFile(resolve(evidence, 'instrumentation-e2e.txt'), error.stdout || '');
    throw new Error('Runner interrupted before a complete result; partial output preserved. Check the device before retrying.');
}
await writeFile(resolve(evidence, 'instrumentation-e2e.txt'), output);
const statuses = new Map(); let fields = {};
for (const line of output.replaceAll('\r', '').split('\n')) {
    if (line.startsWith('INSTRUMENTATION_STATUS: ')) {
        const value = line.slice(24); const split = value.indexOf('=');
        assert(split > 0); const key = value.slice(0, split); assert(!(key in fields)); fields[key] = value.slice(split + 1);
    } else if (line.startsWith('INSTRUMENTATION_STATUS_CODE: ')) {
        assert.equal(fields.class, testClass); assert(expected.includes(fields.test)); assert.equal(fields.numtests, String(expected.length));
        statuses.set(fields.test, [...(statuses.get(fields.test) || []), Number(line.split(':')[1].trim())]); fields = {};
    }
}
assert.deepEqual([...statuses.keys()].sort(), [...expected].sort());
for (const codes of statuses.values()) assert.deepEqual(codes, [1, 0], 'Every mandatory test must start and pass, without skips');
assert.match(output, /^OK \(13 tests\)\s*$/m);
assert.deepEqual([...output.matchAll(/^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$/gm)].map(m => Number(m[1])), [-1]);
const protocol = JSON.parse(await command('python', ['.github/scripts/verify-android-instrumentation.py', '--scope', 'template-editor-navigation', resolve(evidence, 'instrumentation-e2e.txt')]));
await writeFile(resolve(evidence, 'navigation-protocol-summary.json'), JSON.stringify(protocol, null, 2));
await writeFile(resolve(evidence, 'e2e-summary.json'), JSON.stringify({ status: 'passed', cases: expected, skips: 0, containerId: inspection.Id, imageId: inspection.Image }, null, 2));
console.log('PASS 13 mandatory production UI cases including recreation, drafts, confirmation, original-request retry and finance isolation');
