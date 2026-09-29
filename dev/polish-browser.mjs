// Behavioral-Active Blackbox Good-Communication; regressions D43–D48 and D50.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {statusBarChecks} from './statusbar-browser.mjs';
const origin=process.env.CQ_ORIGIN,evidence=process.env.CQ_BROWSER_EVIDENCE;
const headers={'Authorization':`Bearer ${process.env.CQ_TOKEN}`,'CQ-Session':randomUUID(),'CQ-Protocol-Version':'0.1.0','Content-Type':'application/json'};
async function call(command){const response=await fetch(origin+'/api/call',{method:'POST',headers,body:JSON.stringify(command)});assert.equal(response.status,200);return response.json();}
const project={value:randomUUID()};await call({Initialize:{config:{project,endpoint:origin,name:'Polish fixture'}}});
const draft={title:'Padding target',body:'A long enough row for padding selection.',labels:[],archived:false,citations:[],content:{Defect:{status:'Open',severity:'High',observed:'Unselectable padding',expected:'Selectable row',reproduction:'Click padding',cause:null,resolution:[]}}};
const change=mutations=>call({Change:{input:{project,change:{request:{value:randomUUID()},mutations,fences:[],reason:'Polish fixture'}}}});
await change([{Create:{draft}}]);
const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1366,height:768}});page.setDefaultTimeout(3000);
let detailRequests=0;page.on('websocket',socket=>socket.on('framesent',frame=>{const command=JSON.parse(String(frame.payload)).Call?.command;if(command?.Read?.input.selection.ItemDetail)detailRequests++;}));
const cases=[],failures=[];
async function check(name,body){try{await body();cases.push(name);}catch(error){failures.push({name,error:String(error)});}}
try{
 await page.goto(origin);await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();
 await page.getByText('Connection: ALIVE',{exact:true}).waitFor();await page.getByLabel('Project',{exact:true}).selectOption(project.value);
 const table=page.getByRole('table',{name:'Items',exact:true});await table.getByRole('button',{name:'D1 · Padding target',exact:true}).waitFor();
 await check('D43 row padding selects',async()=>{await table.locator('tbody tr').first().getByRole('cell').first().click({position:{x:1,y:1}});await page.getByRole('heading',{name:'D1 · Padding target',exact:true}).waitFor();});
 await check('D44 last modified column',async()=>assert.ok((await table.getByRole('columnheader').allTextContents()).includes('Last modified')));
 await check('D43 title and keyboard select once',async()=>{
  const control=table.getByRole('button',{name:'D1 · Padding target',exact:true});
  for(const activate of [()=>control.click(),async()=>{await control.focus();await control.press('Enter');}]){
   const before=detailRequests;await activate();await page.getByRole('heading',{name:'D1 · Padding target',exact:true}).waitFor();
   assert.equal(detailRequests,before+1);
  }
 });
 const input=page.getByRole('combobox',{name:'Search query',exact:true});const height=await page.locator('header').first().evaluate(node=>node.getBoundingClientRect().height);
 for(const text of ['ledger:','ledger:decisions AND ']){
  await check('D45 stable partial '+JSON.stringify(text),async()=>{await input.fill(text);await input.focus();await page.getByRole('option',{name:text==='ledger:'?'tasks · Value':'ledger · Field',exact:true}).waitFor();assert.equal(await page.locator('header').first().evaluate(node=>node.getBoundingClientRect().height),height,'Completion must not change header height');assert.notEqual(await input.getAttribute('aria-invalid'),'true','A completable prefix must not be marked invalid');});
 }
 await check('D45 no visible suggestion count',async()=>assert.equal(await page.locator('#query-completion-status').isVisible(),false));
 await input.press('Escape');await input.blur();
 await check('D46 square controls',async()=>assert.equal(await page.getByRole('button',{name:'New item',exact:true}).evaluate(node=>getComputedStyle(node).borderTopLeftRadius),'0px'));
 await check('D47 bulk archive control',async()=>assert.equal(await page.getByRole('button',{name:'Archive terminal items',exact:true}).count(),1));
 await check('D48 server rejects nonterminal archive',async()=>{const result=await change([{Create:{draft:{...draft,title:'Invalid archived open defect',archived:true}}}]);assert.ok(result.Failed?.fault.Invalid,'Expected domain Invalid for archiving Open defect, got '+JSON.stringify(result));});
 await check('D50 bottom status bar geometry',async()=>statusBarChecks(page,evidence));
 await page.screenshot({path:evidence+'/polish.png',fullPage:true});
 await writeFile(evidence+'/polish-results.json',JSON.stringify({cases,failures},null,2));assert.deepEqual(failures,[]);
}finally{await browser.close();}
