// Behavioral Active Blackbox Good Communication; D63-D65 and I5.
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
const origin=process.env.CQ_ORIGIN,evidence=process.env.CQ_BROWSER_EVIDENCE;
const project={value:randomUUID()},headers={Authorization:`Bearer ${process.env.CQ_TOKEN}`,'CQ-Session':randomUUID(),'CQ-Protocol-Version':'0.1.0','Content-Type':'application/json'};
async function call(command){const r=await fetch(origin+'/api/call',{method:'POST',headers,body:JSON.stringify(command)});assert.equal(r.status,200);const value=await r.json();assert.equal(value.Failed,undefined);return value;}
await call({Initialize:{config:{project,endpoint:origin,name:'Completion fixture'}}});
await call({Change:{input:{project,change:{request:{value:randomUUID()},fences:[],reason:'Completion fixture',mutations:[{Create:{draft:{title:'Completion target',body:'',labels:['fixture'],archived:false,citations:[],content:{Defect:{status:'Open',severity:'Low',observed:'Actual',expected:'Expected',reproduction:'Steps',cause:null,resolution:[]}}}}}]}}}});
const browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1366,height:768}});
let held=null;
await context.routeWebSocket(/\/ws$/,route=>{
 const server=route.connectToServer();route.onMessage(message=>{const f=JSON.parse(String(message));const q=f.Call?.command.Read?.input.selection.QueryComplete;if(held!==null&&held.id===null&&q?.query===held.query)held.id=f.Call.id.value;server.send(message);});
 server.onMessage(message=>{const f=JSON.parse(String(message));if(held!==null&&f.Reply?.id.value===held.id){held.release=()=>route.send(message);held.resolve();}else route.send(message);});
});
const page=await context.newPage();page.setDefaultTimeout(8000);const cases=[],errors=[];page.on('pageerror',e=>errors.push(String(e)));
function hold(query){let resolve;const ready=new Promise(done=>{resolve=done;});held={query,id:null,release:null,resolve};return ready;}
async function captured(ready){let timer;try{await Promise.race([ready,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Held completion did not arrive')),8000);})]);}finally{clearTimeout(timer);}}
const popup=page.locator('.query-popup'),query=page.getByLabel('Search query',{exact:true});
const complete=()=>page.waitForFunction(()=>document.querySelector('.query-popup').getAttribute('aria-busy')==='false');
try{
 await page.goto(origin);await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByText('Connection: ALIVE',{exact:true}).waitFor();await page.getByLabel('Project',{exact:true}).selectOption(project.value);await page.getByText('Data: current',{exact:true}).waitFor();
 let ready=hold('ledger:d');await query.focus();
 const immediate=await query.evaluate(input=>{input.value='ledger:d';input.setSelectionRange(8,8);input.dispatchEvent(new Event('input',{bubbles:true}));return [...document.querySelectorAll('#query-suggestions [role=option]')].map(n=>({text:n.textContent,selected:n.getAttribute('aria-selected'),disabled:n.disabled}));});
 assert.deepEqual(immediate.map(i=>i.text),['decisions · Value','defects · Value']);assert.equal(immediate[0].selected,'true');assert.ok(immediate.every(i=>!i.disabled));await captured(ready);
 await query.press('ArrowDown');held.release();held=null;await complete();assert.equal(await page.getByRole('listbox',{name:'Query suggestions'}).getByRole('option',{selected:true}).textContent(),'defects · Value');
 assert.equal(await page.locator('#query-suggestions [role=option]').count(),2);cases.push('Enum hints are synchronous; backend merge deduplicates and preserves keyboard selection');
 ready=hold('id:D');await query.fill('id:D');assert.equal(await popup.evaluate(n=>n.hidden),false);assert.ok((await page.locator('#query-suggestions button').evaluateAll(nodes=>nodes.map(n=>n.disabled))).every(Boolean));await captured(ready);
 assert.equal(await query.getAttribute('aria-activedescendant'),null);assert.equal(await popup.evaluate(n=>n.hidden),false);held.release();held=null;await complete();await page.getByRole('option',{name:'D1 · Completion target · Item',exact:true}).waitFor();cases.push('Popup remains open through debounce/held response; obsolete suggestions are disabled');
 ready=hold('ledger:t');await query.fill('ledger:t');await captured(ready);await query.fill('status:r');await page.getByRole('option',{name:'ready · Value',exact:true}).waitFor();held.release();held=null;await complete();assert.equal(await page.getByRole('option',{name:'tasks · Value',exact:true}).count(),0);cases.push('Delayed enum response cannot replace a newer local/backend completion');
 await query.fill('ledger:tasks AND status:re');await complete();const end=await popup.boundingBox();await query.press('Home');await complete();const start=await popup.boundingBox();assert.ok(end.x-start.x>100,{end,start});
 await query.evaluate(input=>{input.setSelectionRange(7,7);});await page.waitForTimeout(50);const middle=await popup.boundingBox();assert.ok(middle.x>start.x+20&&middle.x<end.x);cases.push('Popup follows caret navigation and selection changes');
 await query.fill('word '.repeat(250)+'ledger:d');await query.press('End');await page.waitForTimeout(50);const long=await popup.boundingBox();assert.ok(long.x>=8&&long.x+long.width<=1366-7);assert.ok(await query.evaluate(n=>n.scrollLeft>0));
 await page.setViewportSize({width:480,height:700});await page.waitForTimeout(50);const small=await popup.boundingBox();assert.ok(small.x>=7&&small.x+small.width<=473);cases.push('Long horizontally scrolled input and narrow viewport keep popup within bounds');
 await page.setViewportSize({width:1366,height:768});await query.fill('');await query.focus();await complete();const height=await popup.evaluate(n=>({height:n.clientHeight,scroll:n.scrollHeight,bottom:n.getBoundingClientRect().bottom,max:parseFloat(getComputedStyle(n).maxHeight)}));assert.ok(height.height>400&&height.bottom<=761&&height.max>600);cases.push('Suggestion list uses available viewport height');
 await query.press('Escape');assert.equal(await popup.isVisible(),false);await query.press('Control+Space');await popup.waitFor();await page.getByRole('button',{name:'Search',exact:true}).focus();assert.equal(await popup.isVisible(),false);cases.push('Explicit Escape and focus dismissal remain available');
 assert.deepEqual(errors,[]);await query.focus();await complete();await page.screenshot({path:evidence+'/completion.png',fullPage:true});
}finally{if(held?.release)held.release();await writeFile(evidence+'/completion-results.json',JSON.stringify({cases,errors},null,2)+'\n');await browser.close();}
