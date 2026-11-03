import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
const base='http://127.0.0.1:18110';
assert.deepEqual(JSON.parse(execFileSync('docker',['inspect','finexy-agent-e2e'],{encoding:'utf8'}))[0].Mounts,[]);
const username='art_'+randomBytes(6).toString('hex'),password=randomBytes(18).toString('hex');
async function request(path,token,body){
 const res=await fetch(base+'/api/'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','X-Timezone-Offset':'480',...(token?{Authorization:'Bearer '+token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
 const data=await res.json().catch(()=>null),code=data?.error?.code??data?.errorCode;
 // Report route/status/code only: bodies can contain secrets or financial data.
 const safeCode=typeof code==='number'||(typeof code==='string'&&/^[a-z\d_.-]{1,80}$/i.test(code))?String(code):'unspecified';
 assert(res.status===200&&data?.success===true,`API ${path.split('?')[0]} HTTP ${res.status} code ${safeCode}`);
 return data.result;
}
const owner=await request('register.json','',{username,password,email:username+'@example.invalid',nickname:'艺术卡验收',language:'zh-Hans',defaultCurrency:'CNY',categories:[]});
const brands=[['工商银行（艺术验收）','icbc','koi'],['中国银行（艺术验收）','boc','koi'],['交通银行（艺术验收）','bocom','koi'],['邮储（艺术验收）','psbc','jade'],['农村商业银行（艺术验收）','rural','jade'],['支付宝（艺术验收）','alipay','moon'],['微信（艺术验收）','wechat','jade'],['京东（艺术验收）','jd','koi'],['抖音（艺术验收）','douyin','moon'],['基金（艺术验收）','investment','jade']];
const accounts=[];
for(const [name]of brands){
 const account=await request('v1/accounts/add.json',owner.token,{name,category:2,type:1,icon:'1',color:'000000',currency:'CNY',balance:123456,balanceTime:946684800});
 assert.equal(typeof account.id,'string');assert.match(account.id,/^\d+$/);accounts.push(account);
}
const before=await request('v1/accounts/list.json?ledgerId=0',owner.token);
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_EXECUTABLE_PATH});
try{
 const page=await browser.newPage({viewport:{width:1440,height:1000},locale:'zh-CN',reducedMotion:'reduce'});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/desktop#/login',{waitUntil:'networkidle'});await page.getByLabel('用户名',{exact:true}).fill(username);await page.getByLabel('密码',{exact:true}).fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL(/#\/$/);
 await page.goto(base+'/desktop#/account/list',{waitUntil:'networkidle'});
 const card=page.locator('.holo-card'),picker=page.getByRole('combobox',{name:'选择账户',exact:true});
 const wallet=page.getByRole('listbox',{name:'钱包中的账户',exact:true}),options=wallet.getByRole('option');
 await options.last().waitFor();
 const optionIds=await options.evaluateAll(items=>items.map(item=>item.dataset.accountId));
 assert.equal(optionIds.length,10);assert(optionIds.every(id=>typeof id==='string'&&/^\d+$/.test(id)));
 assert.deepEqual([...optionIds].sort(),accounts.map(account=>account.id).sort());
 assert.deepEqual((await picker.locator('option').evaluateAll(items=>items.map(item=>item.value))).sort(),[...optionIds].sort());
 async function selected(id){
  const account=accounts.find(item=>item.id===id);assert(account,'Selected option must belong to the fixture');
  await page.waitForFunction(({id,name})=>document.querySelector('.holo-card')?.dataset.accountId===id
   &&document.querySelector('select[aria-label="选择账户"]')?.value===id
   &&document.querySelector('.account-detail > h2')?.textContent===name
   &&document.querySelector('.stack-card[aria-selected="true"]')?.dataset.accountId===id,{id,name:account.name});
  assert.equal(await options.evaluateAll(items=>items.filter(item=>item.getAttribute('aria-selected')==='true').length),1);
  assert.equal(await options.evaluateAll(items=>items.filter(item=>item.tabIndex===0).length),1);
  assert.match(await page.locator('.account-detail .balance').innerText(),/1,234\.56/);
 }
 // Exercise the actual stacked option, preserving each server ID as a string.
 await options.last().click();await selected(optionIds.at(-1));
 await options.last().evaluate(item=>item.focus({preventScroll:true}));
 await page.keyboard.press('Home');await selected(optionIds[0]);
 assert(await wallet.evaluate(item=>item.scrollTop<=item.querySelector('.stack-card').offsetTop),'Home restores the first option inside the wallet viewport');
 const outerScroll=await page.evaluate(()=>({window:window.scrollY,main:document.querySelector('main')?.scrollTop||0,body:document.querySelector('.body')?.scrollTop||0}));
 await page.keyboard.press('End');await selected(optionIds.at(-1));
 const endScroll=await wallet.evaluate(item=>{const active=item.querySelector('[aria-selected="true"]');return {top:item.scrollTop,height:item.clientHeight,content:item.scrollHeight,selectedTop:active.offsetTop,selectedBottom:active.offsetTop+active.offsetHeight};});
 assert(endScroll.content>endScroll.height,'The complete wallet must scroll internally');assert(endScroll.top>0);
 assert(endScroll.selectedTop>=endScroll.top-1&&endScroll.selectedBottom<=endScroll.top+endScroll.height+1,'Keyboard selection stays in the wallet viewport');
 assert.deepEqual(await page.evaluate(()=>({window:window.scrollY,main:document.querySelector('main')?.scrollTop||0,body:document.querySelector('.body')?.scrollTop||0})),outerScroll);
 await page.keyboard.press('ArrowUp');await selected(optionIds.at(-2));
 await page.keyboard.press('ArrowDown');await selected(optionIds.at(-1));
 await page.keyboard.press('ArrowDown');await selected(optionIds.at(-1));
 await page.keyboard.press('Home');await selected(optionIds[0]);
 await page.keyboard.press('ArrowUp');await selected(optionIds[0]);
 await page.keyboard.press('ArrowDown');await selected(optionIds[1]);
 assert.equal(await page.evaluate(()=>document.activeElement?.dataset.accountId),optionIds[1]);
 const targets=await options.evaluateAll(items=>items.map((item,index)=>({width:item.getBoundingClientRect().width,exposedHeight:index+1<items.length?items[index+1].offsetTop-item.offsetTop:item.offsetHeight})));
 assert(targets.every(item=>item.width>=44&&item.exposedHeight>=44),'Every exposed card strip retains a 44px target');
 assert((await options.allTextContents()).every(value=>value.trim()===''),'Stacked artwork has no visible typography');assert.equal(await options.locator('text').count(),0);
 await options.locator('img').evaluateAll(async images=>{for(const image of images){await image.decode();if(!image.naturalWidth||new URL(image.currentSrc).origin!==location.origin)throw new Error('Wallet art must decode from local assets');}});
 const landscapeRatios=[],captionContrasts=[];
 for(let i=0;i<brands.length;i++){
  const [name,brand,art]=brands[i];await picker.selectOption(accounts[i].id);await selected(accounts[i].id);
  await page.waitForFunction(({brand,art})=>{const c=document.querySelector('.holo-card');return c?.dataset.brand===brand&&c?.dataset.artwork===art&&[...c.querySelectorAll('img')].every(x=>x.complete&&x.naturalWidth>=512);},{brand,art});
  assert.equal((await card.innerText()).trim(),'');assert.equal(await card.locator('text').count(),0);
  assert.equal(await card.locator('.holo-brand-seal svg').count(),1);
  assert.match(await page.locator('.holo-caption').innerText(),/1,234\.56/);assert.equal(await page.locator('.holo-caption h3').innerText(),name);
  await card.locator('img').evaluateAll(async images=>{for(const image of images){await image.decode();const source=new URL(image.currentSrc);if(source.origin!==location.origin||!/\/img\/.*\.webp$/.test(source.pathname))throw new Error('Card art must use locally bundled WebP assets');}});
  const ratio=await card.locator('.holo-front').evaluate(item=>item.getBoundingClientRect().width/item.getBoundingClientRect().height);
  assert(Math.abs(ratio-1.586)<.035,'Account art retains landscape bank-card proportions');landscapeRatios.push(ratio);
  const caption=await page.locator('.holo-caption h3').evaluate(item=>{
   const probe=document.createElement('span');probe.style.color='var(--ink, #12141a)';item.parentElement.append(probe);const ink=getComputedStyle(probe).color;probe.remove();
   let ancestor=item,background='rgb(255, 255, 255)';while(ancestor){const candidate=getComputedStyle(ancestor).backgroundColor;if(candidate!=='rgba(0, 0, 0, 0)'&&candidate!=='transparent'){background=candidate;break;}ancestor=ancestor.parentElement;}
   const color=getComputedStyle(item).color;
   const luminance=value=>{const channels=value.match(/[\d.]+/g).slice(0,3).map(Number).map(channel=>channel/255).map(channel=>channel<=.04045?channel/12.92:((channel+.055)/1.055)**2.4);return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;};
   const fore=luminance(color),back=luminance(background);return {color,ink,contrast:(Math.max(fore,back)+.05)/(Math.min(fore,back)+.05)};
  });
  assert.equal(caption.color,caption.ink,'Caption uses actual ink instead of inherited white');assert(caption.contrast>=4.5,'Account names remain readable outside artwork');captionContrasts.push(caption.contrast);
  if([0,5,6].includes(i))await card.screenshot({path:`artifacts/agent-access/art-card-${art}-final.png`});
 }
 await page.getByRole('searchbox',{name:'搜索账户',exact:true}).fill('支付宝');await page.waitForFunction(()=>document.querySelectorAll('.stack-card').length===1);
 assert.deepEqual(await options.evaluateAll(items=>items.map(item=>item.dataset.accountId)),[accounts[5].id]);await selected(accounts[5].id);
 await page.getByRole('button',{name:'清除筛选',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.stack-card').length===10);
 assert.deepEqual((await options.evaluateAll(items=>items.map(item=>item.dataset.accountId))).sort(),[...optionIds].sort());
 await picker.selectOption(accounts[5].id);await page.locator('.holo-illustration').evaluate(async x=>{await x.decode();});
 await page.locator('.account-layout').screenshot({path:'artifacts/agent-access/art-cards-management-final.png'});
 await page.getByRole('link',{name:'总览',exact:true}).click();await page.waitForURL(/#\/$/);await page.waitForFunction(()=>document.querySelector('.wallet-carousel .holo-illustration')?.complete&&document.querySelector('.wallet-carousel .holo-illustration')?.naturalWidth>=512);
 const homeCard=page.locator('.wallet-carousel .holo-card');assert.equal((await homeCard.innerText()).trim(),'');
 const homeRatio=await homeCard.locator('.holo-front').evaluate(item=>item.getBoundingClientRect().width/item.getBoundingClientRect().height);assert(Math.abs(homeRatio-1.586)<.035,'Home and management share the landscape format');
 await page.locator('.finance-home').evaluate(x=>x.scrollTop=0);await page.screenshot({path:'artifacts/agent-access/art-cards-home-final.png'});
 assert.deepEqual(await request('v1/accounts/list.json?ledgerId=0',owner.token),before);assert.deepEqual(errors,[]);
 await writeFile('artifacts/agent-access/art-cards-artwork-final.json',JSON.stringify({brands:10,illustrations:3,noCardTypography:true,localAssetsDecoded:true,externalFinancialCaptions:true,captionContrasts,landscapeRatios,homeRatio,wallet:{stringIds:10,synchronizedSelection:true,keyboardHomeEndArrows:true,internalScroll:true,exposedTargetsAtLeast44px:true,filterReset:true},balancesUnchanged:true,consoleErrors:errors},null,2));
 console.log('PASS ten brand marks, three local illustrations, landscape no-text art, readable captions, complete wallet string IDs, synchronized click/keyboard/scroll/filter selection, unchanged accounts and clean console');
}finally{await browser.close();}
