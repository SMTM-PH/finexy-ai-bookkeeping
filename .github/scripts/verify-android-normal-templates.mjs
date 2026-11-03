import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const run = promisify(execFile);
const container = 'finexy-normal-template-android-e2e';
const evidence = resolve('artifacts/normal-template-management-20261005');
const testClass = 'com.finexy.mobile.NormalTemplateNavigationE2ETest';
const runner = 'com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner';
const command = async (program, args, timeout = 30000) => (await run(program, args, { encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024 })).stdout;
const inspection = JSON.parse(await command('docker', ['inspect', container]))[0];
assert.equal(inspection.Name, `/${container}`);
assert.equal(inspection.Config.Image, 'finexy-ledger-periodic-candidate:20261004-root8');
assert.equal(inspection.Image, 'sha256:007f36fbbfa0e9df40987f0d9baa9239f6418120316be9d56b0dcfb9665baf51');
assert.deepEqual(inspection.Mounts, []);
assert(inspection.Config.Env.includes('EBK_UUID_SERVER_ID=237'));
assert(inspection.Config.Env.includes('EBK_AUTH_ENABLE_REGISTER=true'));
assert(inspection.NetworkSettings.Ports['8080/tcp'].some(port => port.HostPort === '18112' && port.HostIp === '127.0.0.1'));
assert.equal((await (await fetch('http://127.0.0.1:18112/healthz.json')).json()).success, true);
const packages = await command('adb', ['-s', 'emulator-5554', 'shell', 'pm', 'list', 'packages', '-U', 'com.finexy.mobile']);
const uids = new Map([...packages.matchAll(/^package:(\S+) uid:(\d+)\s*$/gm)].map(m => [m[1], m[2]]));
assert(uids.has('com.finexy.mobile.qa') && uids.get('com.finexy.mobile.qa') !== uids.get('com.finexy.mobile'));
assert((await command('adb', ['-s', 'emulator-5554', 'shell', 'pm', 'list', 'instrumentation'])).split(/\r?\n/).includes(`instrumentation:${runner} (target=com.finexy.mobile.qa)`));
await mkdir(evidence, { recursive: true });
await writeFile(resolve(evidence, 'container-guard.json'), JSON.stringify({ containerId: inspection.Id, imageId: inspection.Image, mounts: [], uuidServerId: 237, port: 18112, qaUid: uids.get('com.finexy.mobile.qa'), mainUid: uids.get('com.finexy.mobile') }, null, 2));
console.log('PASS isolated image, endpoint, QA UID and runner guards');
const source = await readFile('android/app/src/qaAndroidTest/java/com/finexy/mobile/NormalTemplateNavigationE2ETest.kt', 'utf8');
const expected = [...source.matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)].map(m => m[1]);
assert.equal(expected.length, 8);
const output = await command('adb', ['-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'finexy.template.e2e.url', 'http://10.0.2.2:18112', '-e', 'class', testClass, runner], 360000);
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
assert.match(output, /^OK \(8 tests\)\s*$/m);
assert.deepEqual([...output.matchAll(/^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$/gm)].map(m => Number(m[1])), [-1]);
await writeFile(resolve(evidence, 'e2e-summary.json'), JSON.stringify({ status: 'passed', cases: expected, skips: 0, containerId: inspection.Id, imageId: inspection.Image }, null, 2));
console.log('PASS all four roles, production navigation, management lifecycle, revoked permissions, dual amounts, tag confirmation and ledger isolation');
