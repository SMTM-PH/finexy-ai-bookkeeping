import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile } from 'node:fs/promises';

const run = promisify(execFile);
const evidence = 'artifacts/template-creation-worker-20261005';
const base = 'http://127.0.0.1:18117';
const legacy = 'http://127.0.0.1:18118';
const guards = [];
for (const [name, image, hash, port, uuid] of [
    ['finexy-template-creation-worker-e2e', 'finexy-template-creation-worker-candidate:20261005-root2', (await readFile(`${evidence}/runtime-image-id.txt`, 'utf8')).trim(), '18117', 241],
    ['finexy-template-creation-legacy-e2e', 'finexy-template-submission-candidate:20261005-root1', 'sha256:4b61a406d48be8a93472c4832ec199f56e6765fe5eb11e109c8473356133364b', '18118', 242],
]) {
    const info = JSON.parse((await run('docker', ['inspect', name], { encoding: 'utf8' })).stdout)[0];
    assert.equal(info.Name, `/${name}`); assert.equal(info.Image, hash); assert.equal(info.Config.Image, image);
    assert.equal(info.State.Running, true); assert.deepEqual(info.Mounts, []);
    assert(info.Config.Env.includes(`EBK_UUID_SERVER_ID=${uuid}`)); assert(info.Config.Env.includes('EBK_DUPLICATE_CHECKER_DUPLICATE_SUBMISSIONS_INTERVAL=0'));
    assert(info.Config.Env.includes('EBK_CRON_ENABLE_CREATE_SCHEDULED_TRANSACTION=false'));
    assert.deepEqual(info.NetworkSettings.Ports['8080/tcp'], [{HostIp:'127.0.0.1',HostPort:port}]);
    guards.push({name,containerId:info.Id,imageId:hash,port,uuid,mounts:[]});
}
await writeFile(`${evidence}/containers-guard.json`, JSON.stringify(guards,null,2));
const request = (path, token, body, endpoint = base) => fetch(`${endpoint}/api/${path}`, { method: body === undefined ? 'GET' : 'POST',
    headers: {'Content-Type':'application/json','X-Timezone-Offset':'480',...(token ? {Authorization:`Bearer ${token}`} : {})},
    ...(body === undefined ? {} : {body:JSON.stringify(body)}), signal:AbortSignal.timeout(30000) });
async function api(path, token, body) {
    const response = await request(path, token, body); assert.equal(response.status,200,path);
    const json = await response.json(); assert.equal(json.success,true,path); assert(Object.hasOwn(json,'result')); return json.result;
}
const capabilities = 'v1/transaction/templates/capabilities.json';
const unauthenticated = await request(capabilities);
assert.equal(unauthenticated.status,400);
const unauthenticatedBody = await unauthenticated.json();
assert.equal(unauthenticatedBody.success,false); assert.equal(unauthenticatedBody.errorCode,202012);
const username = `tpl_${randomBytes(8).toString('hex')}`;
const registration = await api('register.json','',{username,password:randomBytes(20).toString('hex'),email:`${username}@example.invalid`,nickname:'Synthetic contract',language:'zh-Hans',defaultCurrency:'CNY',categories:[]});
const token = registration.token;
const cap = await request(capabilities,token); assert.equal(cap.status,200); assert.equal(cap.headers.get('cache-control'),'no-store');
const contract = (await cap.json()).result;
assert.deepEqual(contract,{creationContractVersion:1,durableCreation:true,payloadBinding:true,deletedReceipts:true,maxClientSessionIdBytes:128,submissionScope:'owner-creator-ledger',durableCreatePath:'v1/transaction/templates/add_durable.json',templateTypes:[1,2]});
// Old endpoint existence is inspected without sending a valid create to that server.
assert.equal((await request(capabilities,'',undefined,legacy)).status,404);
assert.equal((await request('v1/transaction/templates/add_durable.json','',{},legacy)).status,404);
const parent = await api('v1/transaction/categories/add.json',token,{name:'Parent',type:2,icon:'1',color:'000000'});
const category = await api('v1/transaction/categories/add.json',token,{name:'Leaf',type:2,parentId:parent.id,icon:'1',color:'000000'});
const account = await api('v1/accounts/add.json',token,{name:'Synthetic sentinel',currency:'CNY',ledgerId:'0',type:1,category:2,icon:'1',color:'000000',balance:1000,balanceTime:946684800});
const draft = {ledgerId:'0',templateType:1,name:'Contract template',type:3,categoryId:category.id,sourceAccountId:account.id,destinationAccountId:'0',sourceAmount:1234,destinationAmount:0,hideAmount:false,tagIds:[],comment:'Synthetic request'};
const financial = async () => ({accounts:await api('v1/accounts/list.json?with_balance=true',token),transactions:await api('v1/transactions/list.json?count=50&with_count=true',token)});
const baseline = await financial(); assert(baseline.transactions.items.length < 50);
for (const key of [undefined,'','   ','x'.repeat(129)]) {
    const result = await request('v1/transaction/templates/add_durable.json',token,{...draft,...(key === undefined ? {} : {clientSessionId:key})});
    assert.equal(result.status,400);
}
const key = randomBytes(16).toString('hex');
const created = await api('v1/transaction/templates/add_durable.json',token,{...draft,clientSessionId:key});
const replay = await api('v1/transaction/templates/add_durable.json',token,{...draft,clientSessionId:key});
assert.equal(replay.id,created.id);
assert.equal((await request('v1/transaction/templates/add_durable.json',token,{...draft,clientSessionId:key,sourceAmount:4321})).status,400);
const list = await api('v1/transaction/templates/list.json?templateType=1',token); assert.equal(list.length,1);
assert.deepEqual(await financial(),baseline);
await writeFile(`${evidence}/contract-api-summary.json`,JSON.stringify({passed:true,authRequired:true,noStore:true,dedicatedEndpoint:true,missingBlankOversizedKeyRejected:true,originalKeyReplay:true,changedPayloadRejected:true,oldRoutesUnavailable:true,financialSnapshotUnchanged:true},null,2));
console.log('PASS authenticated fresh capability, dedicated endpoint, invalid keys, exact replay, old-server missing routes and unchanged finances');
