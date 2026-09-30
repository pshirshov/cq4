// Behavioral-Active Blackbox Good-Communication; D47/D48 preview, atomicity and acknowledgement loss.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {trackProtocol,receivedReply} from './browser-protocol.mjs';
const origin=process.env.CQ_ORIGIN,evidence=process.env.CQ_BROWSER_EVIDENCE;
const headers={'Authorization':`Bearer ${process.env.CQ_TOKEN}`,'CQ-Session':randomUUID(),'CQ-Protocol-Version':'0.1.0','Content-Type':'application/json'};
async function call(command){const response=await fetch(origin+'/api/call',{method:'POST',headers,body:JSON.stringify(command)});assert.equal(response.status,200);return response.json();}
const project={value:randomUUID()},other={value:randomUUID()};
for(const p of [project,other])await call({Initialize:{config:{project:p,endpoint:origin,name:'Archive '+p.value}}});
const draft=(title,status,labels,archived)=>({title,body:'Preserve content',labels,archived,citations:[],content:{Task:{status,acceptance:['Explicit preview'],result:null,validation:[]}}});
const selected=draft('Selected completed','Done',['batch'],false),cancelled=draft('Selected cancelled','Cancelled',['batch'],false);
const change=mutations=>call({Change:{input:{project,change:{request:{value:randomUUID()},mutations,fences:[],reason:'Archive fixture'}}}});
const created=await change([selected,cancelled,draft('Active','Ready',['batch'],false),draft('Outside filter','Done',[],false),draft('Already archived','Done',['batch'],true)].map(draft=>({Create:{draft}})));
assert.ok(created.Changed);const ids=created.Changed.ack.items.map(i=>i.id);
// D68: a terminal item related to an open item is kept out of the archive selection and listed with its open relation.
const goal={title:'Open goal',body:'Still open',labels:[],archived:false,citations:[],content:{Goal:{status:'Open',outcome:'',acceptance:[],scope:''}}};
const retainedCreate=await change([{Create:{draft:goal}},{Create:{draft:draft('Retained by goal','Done',['batch'],false)}}]);assert.ok(retainedCreate.Changed);
const [goalRev,retainedRev]=retainedCreate.Changed.ack.items;
assert.ok((await change([{Reference:{source:retainedRev.id,expectedSource:retainedRev.revision,relation:'DerivedFrom',target:goalRev.id,expectedTarget:goalRev.revision,present:true}}])).Changed);
const detail=async id=>(await call({Read:{input:{project,selection:{ItemDetail:{id}}}}})).Detail.view.item;
const history=async id=>(await call({Read:{input:{project,selection:{History:{id,before:{value:'9223372036854775807'},limit:200}}}}})).History.page.entries;
const browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1366,height:768}});
await trackProtocol(context);
let previewHold=null;
let hold=false,held=null,resolveHeld;let pendingId=null;
const captured=new Promise(resolve=>{resolveHeld=resolve;});
await context.routeWebSocket(/\/ws$/,route=>{
 const server=route.connectToServer();
 route.onMessage(message=>{const frame=JSON.parse(String(message));if(previewHold!==null&&previewHold.id===null&&frame.Call?.command.Read?.input.selection.ArchivePreview)previewHold.id=frame.Call.id.value;if(hold&&frame.Call?.command.Change?.input.change.mutations.some(m=>m.Archive)){hold=false;pendingId=frame.Call.id.value;}server.send(message);});
 server.onMessage(message=>{const frame=JSON.parse(String(message));if(previewHold!==null&&frame.Reply?.id.value===previewHold.id){previewHold.release=()=>route.send(message);previewHold.resolve();}else if(frame.Reply?.id.value===pendingId){held=frame.Reply;pendingId=null;resolveHeld();}else route.send(message);});
});
const page=await context.newPage();page.setDefaultTimeout(6000);const cases=[];
const dialog=()=>page.getByRole('dialog',{name:'Archive terminal items',exact:true});
const open=()=>page.getByRole('button',{name:'Archive terminal items',exact:true}).click();
try{
 await page.goto(origin);await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByText('Connection: ALIVE',{exact:true}).waitFor();
 await page.getByLabel('Project',{exact:true}).selectOption(project.value);
 await page.getByLabel('Search query').fill('tag:batch archived:all');await page.getByRole('button',{name:'Search',exact:true}).click();await page.getByText('Data: current',{exact:true}).waitFor();
 for(const navigation of ['query','project']){
  let resolve;const captured=new Promise(done=>{resolve=done;});previewHold={id:null,release:null,resolve};
  await open();let timer;try{await Promise.race([captured,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Missing held preview')),6000);})]);}finally{clearTimeout(timer);}
  if(navigation==='query')await page.getByLabel('Search query').evaluate(input=>input.dispatchEvent(new Event('input',{bubbles:true})));
  else await page.getByLabel('Project',{exact:true}).selectOption(other.value);
  const response=previewHold;previewHold=null;response.release();await receivedReply(page,response.id);
  assert.equal(await dialog().isVisible(),false);
  if(navigation==='project')await page.getByLabel('Project',{exact:true}).selectOption(project.value);
 }
 cases.push('Delayed preview cannot reopen after query or project invalidation');
 await open();const selection=dialog().getByRole('table',{name:'Archive selection',exact:true});await selection.getByRole('cell',{name:'Selected cancelled',exact:true}).waitFor();
 assert.equal(await selection.locator('tbody tr').count(),2);assert.equal((await detail(ids[0])).draft.archived,false);cases.push('Preview selects only unarchived terminal filter matches and changes nothing');
 const kept=dialog().getByRole('list',{name:'Retained items',exact:true});assert.equal(await kept.locator('li').count(),1);assert.match(await kept.textContent(),/T6 · Retained by goal · open: G1/);
 const direct=await change([{Archive:{members:[{id:retainedRev.id,revision:(await detail(retainedRev.id)).revision}]}}]);assert.match(direct.Failed?.fault.Invalid?.message??'',/Archive excludes T6: related open items G1/);
 cases.push('D68 terminal item with an open related item is kept out of the selection and refused by the server');
 await change([{Replace:{id:ids[1],expected:{value:'1'},draft:{...cancelled,title:'Edited after preview'}}}]);
 await dialog().getByRole('button',{name:'Confirm archive',exact:true}).click();await dialog().getByText('Archival rejected. Close this dialog and prepare a fresh preview.',{exact:true}).waitFor();
 assert.equal((await detail(ids[0])).draft.archived,false);assert.equal((await detail(ids[1])).draft.archived,false);cases.push('Stale member rejects whole preview');
 await dialog().getByRole('button',{name:'Close',exact:true}).click();await open();await dialog().getByRole('button',{name:'Confirm archive',exact:true}).waitFor();
 hold=true;await dialog().getByRole('button',{name:'Confirm archive',exact:true}).click();
 let timer;try{await Promise.race([captured,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('Missing held archival reply')),6000);})]);}finally{clearTimeout(timer);}
 assert.ok(held.result.Changed);await page.reload();await page.getByText('Connection: ALIVE',{exact:true}).waitFor();await page.getByLabel('Project',{exact:true}).selectOption(other.value);await open();
 assert.equal(await dialog().getByRole('button',{name:'Retry exact archive',exact:true}).count(),0);await dialog().getByRole('button',{name:'Close',exact:true}).click();
 await page.getByLabel('Project',{exact:true}).selectOption(project.value);await open();await dialog().getByRole('button',{name:'Retry exact archive',exact:true}).click();await page.locator('.notification-toast').getByText('Archived 2 items.',{exact:true}).waitFor();
 const after=await Promise.all(ids.map(detail));assert.deepEqual(after.map(i=>i.draft.archived),[true,true,false,false,true]);assert.equal((await history(ids[0])).length,2);assert.equal((await history(ids[1])).length,3);
 assert.equal(await page.evaluate(p=>Object.keys(localStorage).filter(k=>k.startsWith('cq-archive-change:'+p+':')).length,project.value),0);
 cases.push('Reload and project switch retain exact request; replay adds no revision and leaves excluded items untouched');
 assert.equal(await dialog().isVisible(),false);
 const bounded=[];
 for(let offset=0;offset<513;offset+=64){
  const mutations=Array.from({length:Math.min(64,513-offset)},(_,i)=>({Create:{draft:draft('Bounded '+(offset+i),'Done',['bounded'],false)}}));
  const response=await change(mutations);assert.ok(response.Changed);bounded.push(...response.Changed.ack.items);
 }
 const excessive=await change([{Archive:{members:bounded}}]);assert.ok(excessive.Failed?.fault.Invalid);
 await page.getByLabel('Search query').fill('tag:bounded');await page.getByRole('button',{name:'Search',exact:true}).click();await page.getByText('Data: current',{exact:true}).waitFor();
 await open();await dialog().getByRole('button',{name:'Confirm archive',exact:true}).waitFor();
 assert.equal(await dialog().getByRole('table',{name:'Archive selection',exact:true}).locator('tbody tr').count(),512);
 await dialog().getByText(/^Limited preview:/).waitFor();
 await dialog().getByRole('button',{name:'Confirm archive',exact:true}).click();await page.locator('.notification-toast').getByText('Archived 512 items.',{exact:true}).waitFor();
 assert.equal((await detail(bounded[0].id)).draft.archived,true);assert.equal((await detail(bounded[512].id)).draft.archived,false);
 cases.push('513-member request rejected; capped 512-member preview and transport acknowledgement succeed without touching the undisplayed item');
 await page.screenshot({path:evidence+'/archive.png',fullPage:true});
}finally{await writeFile(evidence+'/archive-browser-results.json',JSON.stringify({cases},null,2));await browser.close();}
