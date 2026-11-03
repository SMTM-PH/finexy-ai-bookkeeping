import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer } from 'node:http';

const run = promisify(execFile);
const evidence = resolve('artifacts/template-editor-process-20261005');
const application = 'com.finexy.mobile.qa';
const runner = `${application}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = 'com.finexy.mobile.TemplateEditorProcessE2ETest';
const source = await readFile('android/app/src/qaAndroidTest/java/com/finexy/mobile/TemplateEditorProcessE2ETest.kt','utf8');
assert.deepEqual([...source.matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)].map(match=>match[1]).sort(),
    ['prepareRawFormsAndConfirmedCreationThroughProductionPages','verifyProductionPagesInFreshProcessAfterWorkerRecovery']);
const container = 'finexy-template-editor-process-e2e';
const namespace = `ledger-write-recovery-${randomBytes(8).toString('hex')}`;
const upstream = 'http://127.0.0.1:18121';
const imageId = 'sha256:56e53787747c76e5fce56030a0ff078b03128354d0071d9d5ac77190f0a045bd';
const delay = ms => new Promise(resolveDelay => setTimeout(resolveDelay, ms));
async function command(program, args, allowFailure = false, timeout = 30000) {
    try { return (await run(program, args, { encoding:'utf8', timeout, maxBuffer:4*1024*1024 })).stdout; }
    catch (error) { if (allowFailure) return error.stdout || ''; throw new Error(`${program} command failed; sequence stopped`); }
}
const adb = (...args) => command('adb', ['-s', 'emulator-5554', ...args]);
function verifyPhase(output, method) {
    let fields = {}; const codes = []; let resultSeen = false; let terminated = false;
    for (const line of output.replaceAll('\r','').split('\n')) {
        if (line.startsWith('INSTRUMENTATION_STATUS: ')) {
            assert(!resultSeen && !terminated,'Status appeared after final result');
            const value = line.slice(24); const split = value.indexOf('='); assert(split > 0);
            const key = value.slice(0,split); assert(!(key in fields)); fields[key] = value.slice(split+1);
        } else if (line.startsWith('INSTRUMENTATION_STATUS_CODE: ')) {
            assert(!resultSeen && !terminated,'Status appeared after final result');
            assert.equal(fields.class,testClass); assert.equal(fields.test,method);
            assert.equal(fields.numtests,'1'); assert.equal(fields.current,'1'); assert.equal(fields.id,'AndroidJUnitRunner');
            codes.push(Number(line.split(':')[1])); fields = {};
        } else if (line.startsWith('INSTRUMENTATION_RESULT: ')) {
            assert(!terminated); assert.deepEqual(fields,{}); assert.deepEqual(codes,[1,0]); resultSeen = true;
        } else if (line.startsWith('INSTRUMENTATION_CODE: ')) {
            assert(resultSeen && !terminated); assert.deepEqual(fields,{}); assert.deepEqual(codes,[1,0]); terminated = true;
        } else if (line.startsWith('INSTRUMENTATION_')) {
            assert.fail('Unexpected instrumentation protocol record');
        }
    }
    assert(resultSeen && terminated);
    assert.deepEqual(codes,[1,0]); assert.deepEqual(fields,{});
    assert.deepEqual([...output.matchAll(/^OK \((\d+) tests?\)\s*$/gm)].map(m=>Number(m[1])),[1]);
    assert.deepEqual([...output.matchAll(/^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$/gm)].map(m=>Number(m[1])),[-1]);
    assert(!/INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|FAILURES!!!|INSTRUMENTATION_RESULT: shortMsg=/.test(output));
}
if (process.argv.includes('--self-test')) {
    const method = 'prepareRawFormsAndConfirmedCreationThroughProductionPages';
    const status = code => `INSTRUMENTATION_STATUS: class=${testClass}\nINSTRUMENTATION_STATUS: test=${method}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS_CODE: ${code}\n`;
    const finalResult = 'INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n';
    const success = status(1)+status(0)+finalResult;
    verifyPhase(success,method);
    const rejected = [
        status(1)+status(-2)+finalResult,
        status(1)+status(-3)+finalResult,
        status(0)+finalResult,
        success+status(0), success.replace('numtests=1','numtests=2'),
        success.replace('current=1','current=2'), success.replace(testClass,'UnexpectedClass'),
        success.replace(`test=${method}`,'test=UnexpectedMethod'), success.replace('id=AndroidJUnitRunner','id=UnexpectedRunner'),
        success.replace('OK (1 test)','OK (2 tests)'), success.replace('INSTRUMENTATION_CODE: -1',''),
        success+'INSTRUMENTATION_CODE: -1\n', success+'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n',
        success.replace(`INSTRUMENTATION_STATUS: class=${testClass}`,`INSTRUMENTATION_STATUS: class=${testClass}\nINSTRUMENTATION_STATUS: class=${testClass}`),
        'INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'+status(1)+status(0),
        success.replace('INSTRUMENTATION_RESULT: stream=\n',''),success.replace('OK (1 test)','OK (1 test)\nOK (2 tests)')
    ];
    for (const invalid of rejected) assert.throws(()=>verifyPhase(invalid,method));
    console.log(JSON.stringify({passed:true,accepted:1,rejected:rejected.length}));
    process.exit(0);
}
if (process.argv.includes('--verify-logs')) {
    verifyPhase(await readFile(resolve(evidence,'prepare.txt'),'utf8'),'prepareRawFormsAndConfirmedCreationThroughProductionPages');
    verifyPhase(await readFile(resolve(evidence,'verify.txt'),'utf8'),'verifyProductionPagesInFreshProcessAfterWorkerRecovery');
    console.log(JSON.stringify({passed:true,phases:2,skips:0})); process.exit(0);
}
async function phase(method, file) {
    const output = await command('adb',['-s','emulator-5554','shell','am','instrument','-w','-r',
        '-e','finexy.recovery.namespace',namespace,'-e','finexy.recovery.url','http://10.0.2.2:18123',
        '-e','class',`${testClass}#${method}`,runner],false,180000);
    await writeFile(resolve(evidence,file),output); verifyPhase(output,method);
}
async function readWitness() {
    const result = JSON.parse(await adb('exec-out','run-as',application,'cat',`files/sync-recovery/${namespace}.json`));
    assert.deepEqual(Object.keys(result).sort(),['finish','preparePid','start','state','workerPid']);
    for (const key of ['finish','preparePid','start','workerPid']) assert(Number.isSafeInteger(result[key]) && result[key]>=0);
    assert(['PREPARED','RUNNING','SUCCEEDED','FAILED','RETRY','CANCELLED','INVALID_PROCESS'].includes(result.state));
    return result;
}

let dropResponses = true;
const bodies = new Set(); const originalIds = new Set(); let dropped = 0; let delivered = 0;
// This in-memory proxy never records bodies, headers, credentials or financial content.
const proxy = createServer(async (request,response) => {
    try {
        assert(request.url.startsWith('/api/') || request.url === '/healthz.json');
        const chunks = []; let size = 0;
        for await (const chunk of request) { size += chunk.length; assert(size<=65536); chunks.push(chunk); }
        const body = Buffer.concat(chunks);
        const headers = new Headers();
        for (const [key,value] of Object.entries(request.headers)) {
            if (!['host','connection','content-length','accept-encoding'].includes(key) && value !== undefined)
                headers.set(key,Array.isArray(value)?value.join(','):value);
        }
        headers.set('accept-encoding','identity');
        const result = await fetch(upstream+request.url,{method:request.method,headers,
            ...(['GET','HEAD'].includes(request.method)?{}:{body}),redirect:'error',signal:AbortSignal.timeout(30000)});
        const bytes = Buffer.from(await result.arrayBuffer());
        if (request.method === 'POST' && request.url === '/api/v1/transaction/templates/add_durable.json' && result.status === 200) {
            const parsed = JSON.parse(bytes.toString('utf8')); assert.equal(parsed.success,true);
            assert.equal(typeof parsed.result.id,'string'); assert.match(parsed.result.id,/^[1-9]\d{0,18}$/);
            bodies.add(createHash('sha256').update(body).digest('hex')); originalIds.add(parsed.result.id);
            if (dropResponses) { dropped++; response.destroy(); return; }
            delivered++;
        }
        response.statusCode = result.status;
        for (const [key,value] of result.headers) if (!['content-length','content-encoding','transfer-encoding','connection'].includes(key)) response.setHeader(key,value);
        response.end(bytes);
    } catch { if (!response.destroyed) { response.statusCode=502; response.end('{"success":false,"errorMessage":"Isolated proxy request failed"}'); } }
});

await mkdir(evidence,{recursive:true});
try {
    const info = JSON.parse(await command('docker',['inspect',container]))[0];
    assert.equal(info.Name,`/${container}`); assert.equal(info.Image,imageId);
    assert.equal(info.Config.Image,'finexy-template-creation-worker-candidate:20261005-root2');
    assert.equal(info.State.Running,true); assert.deepEqual(info.Mounts,[]);
    for (const env of ['EBK_UUID_SERVER_ID=245','EBK_AUTH_ENABLE_REGISTER=true','EBK_DUPLICATE_CHECKER_DUPLICATE_SUBMISSIONS_INTERVAL=0','EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false']) assert(info.Config.Env.includes(env));
    assert.deepEqual(info.NetworkSettings.Ports['8080/tcp'],[{HostIp:'127.0.0.1',HostPort:'18121'}]);
    assert.equal((await (await fetch(upstream+'/healthz.json')).json()).success,true);
    const packages = await adb('shell','pm','list','packages','-U','com.finexy.mobile');
    const uids = new Map([...packages.matchAll(/^package:(\S+) uid:(\d+)\s*$/gm)].map(m=>[m[1],m[2]]));
    assert(uids.has(application) && uids.get(application)!==uids.get('com.finexy.mobile'));
    assert((await adb('shell','pm','list','instrumentation')).includes(`instrumentation:${runner} (target=${application})`));
    await writeFile(resolve(evidence,'container-guard.json'),JSON.stringify({containerId:info.Id,imageId,mounts:[],port:18121,uuid:245,qaUid:uids.get(application),mainUid:uids.get('com.finexy.mobile')},null,2));
    await new Promise((resolveListen,reject)=>{proxy.once('error',reject);proxy.listen(18123,'127.0.0.1',resolveListen);});
    console.log('PASS disposable server, separate UID and bounded response-loss proxy');
    await phase('prepareRawFormsAndConfirmedCreationThroughProductionPages','prepare.txt');
    const prepared = await readWitness(); assert.equal(prepared.state,'PREPARED'); assert(prepared.preparePid>0 && prepared.workerPid===0);
    assert(dropped>=1 && delivered===0 && bodies.size===1 && originalIds.size===1);
    const metadata = JSON.parse(await adb('exec-out','run-as',application,'cat',`files/template-editor-recovery/${namespace}.json`));
    assert.deepEqual(Object.keys(metadata).sort(),['jobId','jobNamespace','preparePid','workId']); assert.equal(metadata.preparePid,prepared.preparePid);
    assert.equal(metadata.jobNamespace,'androidx.work.systemjobscheduler');
    assert(Number.isSafeInteger(metadata.jobId) && metadata.jobId>=0); assert.match(metadata.workId,/^[a-f0-9-]{36}$/);
    await writeFile(resolve(evidence,'prepared-witness.json'),JSON.stringify(prepared,null,2));
    await writeFile(resolve(evidence,'owned-job.json'),JSON.stringify(metadata,null,2));
    console.log('PASS real UI drafts and confirmed original creation persisted after response loss');
    await adb('shell','am','kill',application);
    let pids = await command('adb',['-s','emulator-5554','shell','pidof',application],true);
    if (pids.trim().split(/\s+/).includes(String(prepared.preparePid)))
        await adb('shell','run-as',application,'/system/bin/kill','-9',String(prepared.preparePid));
    pids = await command('adb',['-s','emulator-5554','shell','pidof',application],true);
    assert(!pids.trim().split(/\s+/).includes(String(prepared.preparePid)));
    dropResponses = false;
    console.log('PASS prepare exited and terminated; subsequent responses are delivered');
    let witness; let triggered = false; const start = Date.now();
    while (Date.now()-start<150000) {
        witness = await readWitness();
        if (witness.state==='SUCCEEDED') break;
        assert(['PREPARED','RUNNING'].includes(witness.state),'Background Worker failed');
        if (witness.state==='PREPARED' && !triggered && Date.now()-start>=46000) {
            const output = await adb('shell','cmd','jobscheduler','run','-f','-n',metadata.jobNamespace,application,String(metadata.jobId));
            await writeFile(resolve(evidence,'owned-job-trigger.txt'),output); triggered = true;
        }
        await delay(500);
    }
    assert.equal(witness?.state,'SUCCEEDED'); assert(witness.workerPid>0 && witness.workerPid!==prepared.preparePid && witness.start>0 && witness.finish>=witness.start);
    assert(delivered>=1 && bodies.size===1 && originalIds.size===1);
    await writeFile(resolve(evidence,'worker-witness.json'),JSON.stringify(witness,null,2));
    await delay(2000);
    console.log('PASS original request replay completed in a new production Worker process before verify');
    await phase('verifyProductionPagesInFreshProcessAfterWorkerRecovery','verify.txt');
    const verified = JSON.parse(await adb('exec-out','run-as',application,'cat',`files/template-editor-recovery/${namespace}-verified.json`));
    assert.deepEqual(Object.keys(verified).sort(),['draftsRestored','financesUnchanged','started','verifyPid']);
    assert(Number.isSafeInteger(verified.verifyPid) && verified.verifyPid>0 && verified.verifyPid!==witness.workerPid && verified.verifyPid!==prepared.preparePid);
    assert(verified.started>witness.finish && verified.draftsRestored===true && verified.financesUnchanged===true);
    assert.deepEqual(await readWitness(),witness);
    await writeFile(resolve(evidence,'verified-witness.json'),JSON.stringify(verified,null,2));
    await writeFile(resolve(evidence,'summary.json'),JSON.stringify({passed:true,namespace,containerId:info.Id,imageId,
        preparePid:prepared.preparePid,workerPid:witness.workerPid,verifyPid:verified.verifyPid,ownedJobId:metadata.jobId,
        explicitJobTrigger:triggered,droppedResponses:dropped,deliveredReplays:delivered,allWireBodiesEqual:bodies.size===1,
        originalTemplateIdPreserved:originalIds.size===1,rawFormsRestoredThroughProductionUi:true,unconfirmedFormsNeverUploaded:true,
        originalLedgerOnly:true,completeFinancialSnapshotsUnchanged:true,fixtureCleaned:true},null,2));
    console.log('PASS fresh production pages: original template once, complete drafts restored, scopes and finances unchanged');
} catch (error) {
    await writeFile(resolve(evidence,'failure.txt'),String(error.message));
    console.error(`Process UI verification rejected: ${error.message}`); process.exitCode=1;
} finally { proxy.closeAllConnections(); await new Promise(resolveClose=>proxy.close(resolveClose)); }
