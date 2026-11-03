import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const run = promisify(execFile);
const name = 'finexy-template-submission-e2e';
const image = 'finexy-template-submission-candidate:20261005-root1';
const evidence = 'artifacts/template-submission-20261005';
const bases = ['http://127.0.0.1:18113', 'http://127.0.0.1:18114'];
const docker = async args => (await run('docker', args, { encoding: 'utf8', timeout: 40000, maxBuffer: 4 * 1024 * 1024 })).stdout.trim();
const inspect = async () => JSON.parse(await docker(['inspect', name]))[0];
const expectedImageId = (await readFile(`${evidence}/runtime-image-id.txt`, 'utf8')).trim();
let containerId;
async function guard() {
    const info = await inspect();
    assert.equal(info.Name, `/${name}`); assert.equal(info.Config.Image, image); assert.equal(info.Image, expectedImageId);
    assert.equal(info.State.Running, true); assert.deepEqual(info.Mounts, []);
    if (containerId) assert.equal(info.Id, containerId);
    for (const entry of ['EBK_UUID_SERVER_ID=238', 'EBK_AUTH_ENABLE_REGISTER=true', 'EBK_DUPLICATE_CHECKER_DUPLICATE_SUBMISSIONS_INTERVAL=0', 'EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false']) assert(info.Config.Env.includes(entry));
    for (const [internal, external] of [['8080/tcp', '18113'], ['8081/tcp', '18114']]) {
        assert(info.NetworkSettings.Ports[internal].some(port => port.HostIp === '127.0.0.1' && port.HostPort === external));
    }
    return info;
}
async function healthy(base) {
    for (let attempt = 0; attempt < 120; attempt++) {
        try { if ((await (await fetch(`${base}/healthz.json`, { signal: AbortSignal.timeout(1500) })).json()).success === true) return; } catch { /* startup */ }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('Isolated template server did not become healthy');
}
async function startSecond() {
    await guard();
    await docker(['exec', '-d', '-e', 'EBK_SERVER_HTTP_PORT=8081', '-e', 'EBK_UUID_SERVER_ID=239', name, '/ezbookkeeping/ezbookkeeping', 'server', 'run']);
    await healthy(bases[1]);
    const pids = (await docker(['exec', name, 'pidof', 'ezbookkeeping'])).split(/\s+/);
    assert.equal(new Set(pids).size, 2, 'Two independent service processes must be live');
    return pids;
}
async function request(path, token, body, expected = 200, base = bases[0]) {
    const response = await fetch(`${base}/api/${path}`, { method: body === undefined ? 'GET' : 'POST', headers: {
        'Content-Type': 'application/json', 'X-Timezone-Offset': '480', ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000) });
    const data = await response.json();
    if (response.status !== expected) {
        await writeFile(`${evidence}/unexpected-http.json`, JSON.stringify({ path: path.split('?')[0], status: response.status, expected, errorCode: data.errorCode }, null, 2));
    }
    assert.equal(response.status, expected, path.split('?')[0]);
    if (expected === 200) { assert.equal(data.success, true); assert(Object.hasOwn(data, 'result')); }
    return data;
}
const api = async (...args) => (await request(...args)).result;
const checks = [];
const pass = text => { checks.push(text); console.log(`PASS ${text}`); };
const addPath = 'v1/transaction/templates/add.json';
const list = (user, ledgerId, kind = 1) => api(`v1/transaction/templates/list.json?ledgerId=${ledgerId}&templateType=${kind}`, user.token);
let proxy;
try {
    await mkdir(evidence, { recursive: true });
    const initial = await guard(); containerId = initial.Id;
    await writeFile(`${evidence}/container-guard.json`, JSON.stringify({ containerId, imageId: initial.Image, mounts: [], ports: [18113,18114], uuids: [238,239] }, null, 2));
    await healthy(bases[0]);
    const register = async label => {
        const username = `tpl_${randomBytes(8).toString('hex')}`;
        return { username, ...(await api('register.json', '', { username, password: randomBytes(20).toString('hex'), email: `${username}@example.invalid`, nickname: `Synthetic ${label}`, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] })) };
    };
    const owner = await register('owner'), admin = await register('admin'), member = await register('member'), viewer = await register('viewer');
    const a = await api('v1/ledger/create.json', owner.token, { type:1, name:'Synthetic template A' });
    const b = await api('v1/ledger/create.json', owner.token, { type:1, name:'Synthetic template B' });
    let adminMemberId;
    for (const [user, role] of [[admin,2],[member,3],[viewer,4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner.token, { ledgerId:a.id, inviteeName:user.username, role:role===2?3:role, expiresInSeconds:86400 });
        await api('v1/ledger/invitation/accept.json', user.token, { token:invitation.token });
        if (role===2) {
            adminMemberId = (await api(`v1/ledger/member/list.json?ledgerId=${a.id}`, admin.token)).find(row=>row.isCurrentUser).id;
            await api('v1/ledger/member/change_role.json', owner.token, { ledgerId:a.id, memberId:adminMemberId, role:2 });
        }
    }
    const category = async type => {
        const root = await api('v1/transaction/categories/add.json', owner.token, { name:`Synthetic root ${type}`, type, icon:'1', color:'000000' });
        return api('v1/transaction/categories/add.json', owner.token, { name:`Synthetic leaf ${type}`, type, parentId:root.id, icon:'1', color:'000000' });
    };
    const expense = await category(2), transfer = await category(3);
    const account = (name, currency, ledgerId) => api('v1/accounts/add.json', owner.token, { name,currency,ledgerId,type:1,category:2,icon:'1',color:'000000',balance:100000,balanceTime:946684800 });
    const usd = await account('Synthetic USD','USD',a.id), cny = await account('Synthetic CNY','CNY',a.id), other = await account('Synthetic B','CNY',b.id), personal = await account('Synthetic personal','CNY','0');
    const tag = await api('v1/transaction/tags/add.json',owner.token,{name:'Synthetic owner tag',color:'000000'});
    const draft = { ledgerId:a.id,templateType:1,name:'Synthetic transfer',type:4,categoryId:transfer.id,sourceAccountId:usd.id,destinationAccountId:cny.id,
        sourceAmount:1234,destinationAmount:9012,hideAmount:true,tagIds:[tag.id],comment:'Immutable synthetic request' };
    async function financial(ledgerId) {
        const accounts = await api(`v1/accounts/list.json?ledgerId=${ledgerId}`,owner.token);
        const transactions = await api(`v1/transactions/list.json?ledgerId=${ledgerId}&count=50`,owner.token);
        assert(Array.isArray(transactions.items) && transactions.items.length < 50, 'Synthetic financial snapshot must be complete');
        return { accounts,transactions };
    }
    const finances = new Map(await Promise.all(['0',a.id,b.id].map(async id=>[id,await financial(id)])));
    async function unchanged() { for (const [id, value] of finances) assert.deepEqual(await financial(id),value); }

    // Drop an actual HTTP response after the backend has fully committed it.
    let dropped = 0;
    proxy = createServer(async (incoming,outgoing) => {
        try {
            assert.equal(incoming.method,'POST'); assert.equal(incoming.url,`/api/${addPath}`);
            const body = []; for await (const chunk of incoming) body.push(chunk);
            const response = await fetch(`${bases[0]}${incoming.url}`, { method:'POST',headers:{'Content-Type':'application/json','X-Timezone-Offset':'480',Authorization:incoming.headers.authorization},body:Buffer.concat(body),signal:AbortSignal.timeout(30000) });
            assert.equal(response.status,200); await response.arrayBuffer(); dropped++; outgoing.destroy();
        } catch { outgoing.destroy(); }
    });
    await new Promise((resolve,reject)=>{proxy.once('error',reject);proxy.listen(18115,'127.0.0.1',resolve);});
    const lost = {...draft,clientSessionId:'lost-response'};
    await assert.rejects(()=>request(addPath,admin.token,lost,200,'http://127.0.0.1:18115'));
    assert.equal(dropped,1);
    const initialTemplates = await list(owner,a.id);
    assert.equal(initialTemplates.length,1); const committed = initialTemplates[0];
    const replay = await api(addPath,admin.token,lost);
    assert.equal(replay.id,committed.id);assert.equal(replay.destinationAmount,9012);assert.deepEqual(replay.tagIds,[tag.id]);
    await unchanged(); pass('lost HTTP response retries the original dual-amount template exactly once with cache disabled');
    await new Promise(resolve=>proxy.close(resolve)); proxy=undefined;

    const beforePid = (await guard()).State.Pid;
    await docker(['restart',containerId]);await healthy(bases[0]);
    assert.notEqual((await guard()).State.Pid,beforePid);
    assert.equal((await api(addPath,admin.token,lost)).id,committed.id);
    assert.equal((await list(owner,a.id)).length,1); await unchanged();
    pass('container process restart preserves the receipt and original result');

    const processPids=await startSecond();
    const concurrent = {...draft,clientSessionId:'two-processes'};
    let transientFailures=0;
    const send = async index => {
        for (let attempt=0;attempt<12;attempt++) {
            const response=await fetch(`${bases[index%2]}/api/${addPath}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${admin.token}`},body:JSON.stringify(concurrent)});
            const result=await response.json();
            if(response.status===200){assert.equal(result.success,true);return result.result;}
            assert.equal(response.status,500,'Only an unresolved database error can retry the unchanged request');transientFailures++;
            await new Promise(resolve=>setTimeout(resolve,50));
        }
        throw new Error('Concurrent immutable request failed to converge');
    };
    const results=await Promise.all(Array.from({length:12},(_,index)=>send(index)));
    assert.equal(new Set(results.map(row=>row.id)).size,1);assert.equal((await list(owner,a.id)).length,2);
    await unchanged();pass('two live service processes with different UUID IDs converge twelve same-key requests on one template');
    await writeFile(`${evidence}/concurrency.json`,JSON.stringify({processPids,requests:12,uniqueResults:1,transientFailures},null,2));

    for(const change of [{sourceAmount:1235},{destinationAmount:9013},{comment:'Changed'},{tagIds:[]},{hideAmount:false},{name:'Changed'},
        {templateType:2,scheduledFrequencyType:3,scheduledFrequency:'0',utcOffset:480}]) {
        await request(addPath,admin.token,{...lost,...change},400);
    }
    assert.equal((await list(owner,a.id)).length,2);await unchanged();pass('same key rejects changed financial fields, tags, hidden amount and schedule type');
    const ownerSame=await api(addPath,owner.token,lost);
    assert.notEqual(ownerSame.id,committed.id);
    const personalDraft={...draft,type:3,categoryId:expense.id,sourceAccountId:personal.id,destinationAccountId:'0',destinationAmount:0,sourceAmount:-123,ledgerId:'0',clientSessionId:'lost-response'};
    const personalResult=await api(addPath,owner.token,personalDraft);
    const bResult=await api(addPath,owner.token,{...personalDraft,ledgerId:b.id,sourceAccountId:other.id});
    assert.equal((await list(owner,'0')).length,1);assert.equal((await list(owner,b.id)).length,1);
    assert.notEqual(personalResult.id,bResult.id);await unchanged();pass('actor and default/A/B ledger scopes allow independent keys without financial writes');
    for(const user of [member,viewer]) await request(addPath,user.token,lost,403);
    await api('v1/ledger/member/change_role.json',owner.token,{ledgerId:a.id,memberId:adminMemberId,role:4});
    await request(addPath,admin.token,lost,403, bases[1]);
    await api('v1/ledger/member/change_role.json',owner.token,{ledgerId:a.id,memberId:adminMemberId,role:2});
    await unchanged();pass('member/viewer and revoked administrator cannot create or replay owner receipts');
    await api('v1/transaction/templates/delete.json',owner.token,{ledgerId:a.id,id:committed.id});
    const tombstone=await api(addPath,admin.token,lost,200,bases[1]);
    assert.equal(tombstone.id,committed.id);assert.equal(tombstone.deleted,true);assert.equal(tombstone.editable,false);
    assert(!(await list(owner,a.id)).some(row=>row.id===committed.id));await unchanged();pass('deleted template replays its original tombstone and never recreates it');
    const scheduled={...personalDraft,templateType:2,clientSessionId:'scheduled-key',scheduledFrequencyType:3,scheduledFrequency:'0',utcOffset:480,scheduledStartDate:'2026-10-01'};
    const scheduledFirst=await api(addPath,owner.token,scheduled);
    assert.equal((await api(addPath,owner.token,scheduled,200,bases[1])).id,scheduledFirst.id);
    await request(addPath,owner.token,{...scheduled,utcOffset:0},400);
    await api('v1/transaction/templates/delete.json',owner.token,{ledgerId:'0',id:scheduledFirst.id});
    const deletedSchedule=await api(addPath,owner.token,scheduled);
    assert.equal(deletedSchedule.deleted,true);assert.equal(deletedSchedule.nextScheduledTime,undefined);await unchanged();
    pass('scheduled templates bind calendar and timezone and return no future date after deletion');
    const invalid={...personalDraft,clientSessionId:'failed-key',categoryId:'99999999'};
    await request(addPath,owner.token,invalid,400);
    const valid=await api(addPath,owner.token,{...personalDraft,clientSessionId:'failed-key'});
    assert.equal((await api(addPath,owner.token,{...personalDraft,clientSessionId:'failed-key'})).id,valid.id);
    const legacy1=await api(addPath,owner.token,{...personalDraft,clientSessionId:''});
    const legacy2=await api(addPath,owner.token,{...personalDraft,clientSessionId:''});
    assert.notEqual(legacy1.id,legacy2.id);await unchanged();pass('invalid creation rolls back the key; legacy unkeyed creation remains independent');
    await writeFile(`${evidence}/api-summary.json`,JSON.stringify({status:'passed',checks,containerId,imageId:initial.Image,financialSnapshotsUnchanged:true,syntheticOnly:true},null,2));
} finally { if(proxy) await new Promise(resolve=>proxy.close(resolve)); }
