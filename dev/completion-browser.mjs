// Behavioral Active Blackbox Good Communication; D63-D65, I5, D97 and I23.
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
let held=null;const browses=[],pending=new Set();
await context.routeWebSocket(/\/ws$/,route=>{
 const server=route.connectToServer();route.onMessage(message=>{const f=JSON.parse(String(message));const read=f.Call?.command.Read?.input.selection;if(read?.Browse){browses.push(read.Browse.query);pending.add(f.Call.id.value);}else if(read?.ItemDetail)pending.add(f.Call.id.value);if(held!==null&&held.id===null&&read?.[held.kind]?.query===held.query)held.id=f.Call.id.value;server.send(message);});
 server.onMessage(message=>{const f=JSON.parse(String(message));if(f.Reply)pending.delete(f.Reply.id.value);if(held!==null&&f.Reply?.id.value===held.id){held.release=()=>route.send(message);held.resolve();}else route.send(message);});
});
const page=await context.newPage();page.setDefaultTimeout(8000);const cases=[],errors=[];page.on('pageerror',e=>errors.push(String(e)));
function holdReply(kind,query){let resolve;const ready=new Promise(done=>{resolve=done;});held={kind,query,id:null,release:null,resolve};return ready;}
const hold=query=>holdReply('QueryComplete',query);
async function captured(ready){let timer;try{await Promise.race([ready,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Held reply did not arrive')),8000);})]);}finally{clearTimeout(timer);}}
const popup=page.locator('.query-popup'),query=page.getByLabel('Search query',{exact:true});
const complete=()=>page.waitForFunction(()=>document.querySelector('.query-popup').getAttribute('aria-busy')==='false');
// D97 helpers. `browses` holds the query of every Browse request in order; `pending` holds the Browse and ItemDetail calls without a reply.
async function until(condition,message){const deadline=Date.now()+8000;while(!condition()){if(Date.now()>deadline)assert.fail(message);await new Promise(done=>setTimeout(done,10));}}
const focused=()=>query.evaluate(n=>document.activeElement===n);
// The result list is busy from the start of a reload until the selected item and its usage are read again.
async function settled(){for(;;){await page.waitForFunction(()=>{const lists=document.querySelectorAll('tbody[aria-busy]');return lists.length===1&&lists[0].getAttribute('aria-busy')==='false'&&[...document.querySelectorAll('span')].some(n=>n.textContent==='Data: current');});if(pending.size===0){await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done))));if(pending.size===0)return;}await page.waitForTimeout(20);}}
async function apply(text){const before=browses.length;await query.fill(text);await page.getByRole('button',{name:'Search',exact:true}).click();await until(()=>browses.length>before,`Search did not browse ${text}`);assert.equal(browses.at(-1),text);await settled();}
// Every Browse request since `before` carries the empty query, and there is at least one.
async function emptied(before,message){await until(()=>browses.length>before,message);assert.deepEqual([...new Set(browses.slice(before))],[''],message);}
async function cleared(){assert.equal(await query.inputValue(),'');assert.equal(await focused(),true,'focus stays in the search input');assert.equal(await popup.isVisible(),false);assert.equal(await query.getAttribute('aria-invalid'),null);}
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
 // D97: Clear query submits the empty query at once, so the results stop showing the previous filter.
 const clear=page.getByRole('button',{name:'Clear query',exact:true}),target=page.getByRole('button',{name:'D1 · Completion target',exact:true}),none=page.getByText('No matching items.',{exact:true});
 await target.waitFor();await apply('ledger:tasks');await none.waitFor();assert.equal(await target.count(),0);
 ready=hold('ledger:t');await query.fill('ledger:t');await captured(ready);let before=browses.length;
 await clear.click();await cleared();await emptied(before,'Pointer clear did not browse the empty query');await target.waitFor();await settled();assert.equal(await none.count(),0);await cleared();
 held.release();held=null;await page.waitForTimeout(250);await cleared();
 await apply('ledger:tasks');await none.waitFor();await query.fill('ledger:unknown');await complete();assert.equal(await query.getAttribute('aria-invalid'),'true');before=browses.length;
 await clear.focus();await clear.press('Enter');await emptied(before,'Keyboard clear did not browse the empty query');await target.waitFor();await settled();assert.equal(await none.count(),0);await cleared();
 cases.push('D97 clear after a submitted filter empties input and diagnostics, browses the empty query without Enter and shows the excluded item again; focus stays in the input and a held reply stays rejected (pointer and keyboard)');
 // A live change after the clear is read with the empty query: under the old filter the new defect would stay hidden.
 before=browses.length;await call({Change:{input:{project,change:{request:{value:randomUUID()},fences:[],reason:'Live change after clear',mutations:[{Create:{draft:{title:'Live after clear',body:'',labels:['fixture'],archived:false,citations:[],content:{Defect:{status:'Open',severity:'Low',observed:'Actual',expected:'Expected',reproduction:'Steps',cause:null,resolution:[]}}}}}]}}}});
 const other=page.getByRole('button',{name:'D2 · Live after clear',exact:true});await other.waitFor();await emptied(before,'Live refresh after clear did not browse the empty query');await settled();
 cases.push('D97 live refreshes after a clear use the empty query');
 // With an item selected and its view open, the reload reads the item again and must not take focus from the input.
 const view=page.getByRole('heading',{name:'D1 · Completion target',exact:true});await target.click();await view.waitFor();
 await apply('id:D1');await other.waitFor({state:'detached'});await view.waitFor();
 ready=hold('ledger:t');await query.fill('ledger:t');await captured(ready);before=browses.length;
 await clear.click();await cleared();await emptied(before,'Pointer clear with a selection did not browse the empty query');await other.waitFor();await settled();await cleared();assert.equal(await view.isVisible(),true);
 held.release();held=null;await page.waitForTimeout(250);await cleared();
 await apply('id:D1');await other.waitFor({state:'detached'});await query.fill('ledger:unknown');await complete();assert.equal(await query.getAttribute('aria-invalid'),'true');before=browses.length;
 await clear.focus();await clear.press('Enter');await emptied(before,'Keyboard clear with a selection did not browse the empty query');await other.waitFor();await settled();await cleared();assert.equal(await view.isVisible(),true);
 cases.push('D97 clear with an item selected and its view open reloads all items, keeps the view and leaves focus in the input (pointer and keyboard)');
 // The applied query is already empty here. The reload starts inside the click itself: no debounce and no check of the applied query.
 before=browses.length;assert.equal(await clear.evaluate(n=>{n.click();return [...document.querySelectorAll('span')].find(s=>s.textContent.startsWith('Data: ')).textContent;}),'Data: synchronizing');
 await emptied(before,'Clear with an empty applied query did not browse');await settled();await cleared();
 cases.push('D97 clear submits synchronously, also when the applied query is already empty');
 await page.getByRole('button',{name:'Close item view',exact:true}).click();await view.waitFor({state:'detached'});
 const clearBounds=await clear.boundingBox(),fieldBounds=await page.locator('.query-field').boundingBox();assert.ok(clearBounds.x>fieldBounds.x&&clearBounds.x+clearBounds.width<=fieldBounds.x+fieldBounds.width);
 cases.push('In-field clear stays inside the search field');
 // I23: a valid typed query is applied after a pause without Enter. Each case records its own failure so one run reports all of them.
 const failures=[],search=page.getByRole('button',{name:'Search',exact:true}),selectedOption=()=>page.getByRole('listbox',{name:'Query suggestions'}).getByRole('option',{selected:true});
 const LIVE_WAIT_MS=900,pause=()=>page.waitForTimeout(LIVE_WAIT_MS),caret=()=>query.evaluate(n=>n.selectionStart);
 const defect=title=>call({Change:{input:{project,change:{request:{value:randomUUID()},fences:[],reason:'Live search fixture',mutations:[{Create:{draft:{title,body:'',labels:['fixture'],archived:false,citations:[],content:{Defect:{status:'Open',severity:'Low',observed:'Actual',expected:'Expected',reproduction:'Steps',cause:null,resolution:[]}}}}}]}}}});
 async function live(name,body){try{await body();cases.push(name);}catch(error){failures.push(`${name}: ${String(error.message).split('\n')[0]}`);}finally{if(held?.release)held.release();held=null;}}
 await live('I23 a query typed key by key is browsed once with the final text; focus, caret and the popup with its active option stay',async()=>{
  await apply('');await query.fill('');const before=browses.length;await query.pressSequentially('id:D2',{delay:40});
  await until(()=>browses.length>before,'Typed valid query was not browsed');await complete();await settled();await pause();
  assert.deepEqual(browses.slice(before),['id:D2']);await other.waitFor();assert.equal(await target.count(),0);
  assert.equal(await focused(),true);assert.equal(await caret(),5);assert.equal(await popup.isVisible(),true);
  assert.equal(await selectedOption().textContent(),'D2 · Live after clear · Item');assert.equal(await query.getAttribute('aria-expanded'),'true');
 });
 await live('I23 invalid and incomplete text is not browsed; rows, status, live refresh and the applied query stay, without a notification',async()=>{
  await apply('ledger:defects');const before=browses.length;
  for(const text of ['ledger:unknown','alpha AND']){
   await query.fill(text);await complete();await pause();assert.deepEqual(browses.slice(before),[],text);
   assert.equal(await target.isVisible(),true);assert.equal(await other.isVisible(),true);assert.equal(await page.getByText('Data: current',{exact:true}).count(),1);
   assert.equal(await page.locator('.notification-toast').evaluate(n=>n.matches(':popover-open')),false,text);
  }
  await defect('Live under previous query');await page.getByRole('button',{name:'D3 · Live under previous query',exact:true}).waitFor();await settled();
  assert.deepEqual([...new Set(browses.slice(before))],['ledger:defects']);assert.equal(await query.inputValue(),'alpha AND');
 });
 await live('I23 a held reply for a superseded typed query is ignored: only the rows of the newer query are shown',async()=>{
  await apply('');await page.evaluate(()=>{const list=document.querySelector('tbody[aria-busy]');window.renderedRows=[];new MutationObserver(()=>{window.renderedRows.push([...list.querySelectorAll('tr.item-row')].map(row=>row.dataset.item));}).observe(list,{childList:true});});
  const ready=holdReply('Browse','id:D1');const before=browses.length;await query.fill('id:D1');await captured(ready);
  await query.fill('id:D2');await complete();await pause();assert.equal(await target.isVisible(),true);assert.equal(await other.isVisible(),true);
  held.release();held=null;await until(()=>browses.at(-1)==='id:D2','Newer typed query was not browsed');await target.waitFor({state:'detached'});await settled();
  assert.deepEqual(browses.slice(before),['id:D1','id:D2']);assert.equal(await other.isVisible(),true);
  const rendered=await page.evaluate(()=>window.renderedRows);assert.ok(rendered.length>0);assert.ok(rendered.every(rows=>rows.some(key=>key.endsWith('-D2'))),'The rows of the superseded reply were shown');
 });
 await live('I23 Enter right after typing browses once and cancels the pending live update',async()=>{
  await apply('');const before=browses.length;await query.pressSequentially('target',{delay:10});await query.press('Enter');
  await until(()=>browses.length>before,'Enter did not browse');await settled();await pause();assert.deepEqual(browses.slice(before),['target']);
 });
 await live('I23 typing a character and deleting it does not browse',async()=>{
  await apply('');await query.fill('');const before=browses.length;await query.press('x');await query.press('Backspace');await pause();
  assert.equal(await query.inputValue(),'');assert.deepEqual(browses.slice(before),[]);
 });
 await live('I23 an accepted suggestion is applied without Enter and does not reopen the popup',async()=>{
  await apply('');const before=browses.length;await query.fill('ledger:t');await page.getByRole('option',{name:'tasks · Value',exact:true}).click();
  assert.equal(await query.inputValue(),'ledger:tasks');await until(()=>browses.length>before,'Accepted suggestion was not browsed');await none.waitFor();await settled();await pause();
  assert.deepEqual(browses.slice(before),['ledger:tasks']);assert.equal(await focused(),true);assert.equal(await popup.isVisible(),false);
 });
 await live('I23 a typed query follows the item-view rule of a submitted one: a result with the selected item keeps the view, invalid text changes nothing, a result without it closes the view',async()=>{
  await apply('');await target.click();await view.waitFor();let before=browses.length;await query.fill('id:D1');
  await until(()=>browses.length>before,'Typed query containing the selected item was not browsed');await other.waitFor({state:'detached'});await settled();assert.equal(await view.isVisible(),true);
  before=browses.length;await query.fill('ledger:unknown');await complete();await pause();assert.deepEqual(browses.slice(before),[]);assert.equal(await view.isVisible(),true);assert.equal(await target.isVisible(),true);
  await query.fill('id:D2');await until(()=>browses.length>before,'Typed query without the selected item was not browsed');await view.waitFor({state:'detached'});await settled();
  assert.deepEqual(browses.slice(before),['id:D2']);assert.equal(await target.count(),0);assert.equal(await other.isVisible(),true);assert.equal(await focused(),true);
 });
 await live('I23 nothing is browsed during input-method composition; the composed text is browsed after it ends',async()=>{
  await apply('');await query.focus();const before=browses.length;
  await query.evaluate(input=>{input.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));input.value='target';input.setSelectionRange(6,6);input.dispatchEvent(new Event('input',{bubbles:true}));});
  await pause();assert.deepEqual(browses.slice(before),[]);
  await query.evaluate(input=>{input.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true}));});
  await until(()=>browses.length>before,'Composed text was not browsed');await settled();await pause();assert.deepEqual(browses.slice(before),['target']);
 });
 assert.deepEqual(failures,[]);await apply('');
 await query.fill('ledger:tasks AND status:re');await complete();const end=await popup.boundingBox();await query.press('Home');await complete();const start=await popup.boundingBox();assert.ok(end.x-start.x>100,{end,start});
 await query.evaluate(input=>{input.setSelectionRange(7,7);});await page.waitForTimeout(50);const middle=await popup.boundingBox();assert.ok(middle.x>start.x+20&&middle.x<end.x);cases.push('Popup follows caret navigation and selection changes');
 await query.fill('word '.repeat(250)+'ledger:d');await query.press('End');await page.waitForTimeout(50);const long=await popup.boundingBox();assert.ok(long.x>=8&&long.x+long.width<=1366-7);assert.ok(await query.evaluate(n=>n.scrollLeft>0));
 await page.setViewportSize({width:480,height:700});await page.waitForTimeout(50);const small=await popup.boundingBox();assert.ok(small.x>=7&&small.x+small.width<=473);cases.push('Long horizontally scrolled input and narrow viewport keep popup within bounds');
 await page.setViewportSize({width:1366,height:768});await query.fill('');await query.focus();await complete();const height=await popup.evaluate(n=>({height:n.clientHeight,scroll:n.scrollHeight,bottom:n.getBoundingClientRect().bottom,max:parseFloat(getComputedStyle(n).maxHeight)}));assert.ok(height.height>400&&height.bottom<=761&&height.max>600);cases.push('Suggestion list uses available viewport height');
 await query.press('Escape');assert.equal(await popup.isVisible(),false);await query.press('Control+Space');await popup.waitFor();await page.getByRole('button',{name:'Search',exact:true}).focus();assert.equal(await popup.isVisible(),false);cases.push('Explicit Escape and focus dismissal remain available');
 assert.deepEqual(errors,[]);await query.focus();await complete();await page.screenshot({path:evidence+'/completion.png',fullPage:true});
}finally{if(held?.release)held.release();await writeFile(evidence+'/completion-results.json',JSON.stringify({cases,errors},null,2)+'\n');await browser.close();}
