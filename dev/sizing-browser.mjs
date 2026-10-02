// Behavioral Active Blackbox Good Communication; D58-D62.
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
const origin=process.env.CQ_ORIGIN,evidence=process.env.CQ_BROWSER_EVIDENCE;
const project={value:randomUUID()},headers={Authorization:`Bearer ${process.env.CQ_TOKEN}`,'CQ-Session':randomUUID(),'CQ-Protocol-Version':'0.1.0','Content-Type':'application/json'};
async function call(command){const response=await fetch(origin+'/api/call',{method:'POST',headers,body:JSON.stringify(command)});assert.equal(response.status,200);const result=await response.json();assert.equal(result.Failed,undefined);return result;}
await call({Initialize:{config:{project,endpoint:origin,name:'Sizing fixture'}}});
const draft={title:'Sizing report',body:'A semantic document should use its container.',labels:[],archived:false,citations:[],content:{Defect:{status:'Open',severity:'Medium',observed:'Actual',expected:'Expected',reproduction:'Steps',cause:null,resolution:[]}}};
const change=mutations=>call({Change:{input:{project,change:{request:{value:randomUUID()},mutations,fences:[],reason:'Sizing fixture'}}}});
await change([{Create:{draft}},{Create:{draft:{...draft,title:'Long status',content:{Defect:{...draft.content.Defect,status:'NotReproducible'}}}}}]);
const browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1800,height:1000}});
// The proxy orders the live frame of a saved change against the replies around the save. The next Change call takes `order`:
// 'reload' delivers the frame while the reload that follows the save awaits its reply, 'reply' delivers it before the save's own reply.
let order=null,gate=null,items=0n,changes=0;const pending=new Set();
await context.routeWebSocket(/\/ws$/,route=>{
 const server=route.connectToServer();
 const blocked=f=>gate.order==='reload'?(f.Updated!==undefined&&gate.browse===null)||(f.Reply!==undefined&&f.Reply.id.value===gate.browse&&!gate.framed):f.Reply!==undefined&&f.Reply.id.value===gate.change&&!gate.framed;
 const deliver=(message,f)=>{route.send(message);if(f.Reply!==undefined)pending.delete(f.Reply.id.value);const cursor=f.Updated?.revision.project?.items.value;if(cursor!==undefined&&BigInt(cursor)>items)items=BigInt(cursor);};
 const flush=()=>{for(;;){gate.framed=gate.cursor!==null&&items>=gate.cursor;const index=gate.held.findIndex(([,f])=>!blocked(f));if(index<0)break;deliver(...gate.held.splice(index,1)[0]);}if(gate.framed&&gate.held.length===0)gate=null;};
 route.onMessage(message=>{const call=JSON.parse(String(message)).Call;
  if(call!==undefined){pending.add(call.id.value);
   if(call.command.Change!==undefined){changes++;if(order!==null){gate={order,change:call.id.value,cursor:null,browse:null,framed:false,held:[]};order=null;}}
   else if(gate!==null&&gate.browse===null&&call.command.Read?.input.selection.Browse!==undefined){gate.browse=call.id.value;flush();}}
  server.send(message);});
 server.onMessage(message=>{const f=JSON.parse(String(message));
  if(gate===null){deliver(message,f);return;}
  if(f.Reply!==undefined&&f.Reply.id.value===gate.change)gate.cursor=BigInt(f.Reply.result.Changed.ack.cursor.value);
  gate.held.push([message,f]);flush();});
});
const page=await context.newPage();page.setDefaultTimeout(8000);
const cases=[],errors=[];page.on('pageerror',e=>errors.push(String(e)));
const current=()=>page.getByText('Data: current',{exact:true}).waitFor();
const layout=()=>page.locator('.items-table').evaluate(table=>({pane:table.parentElement.clientWidth,width:table.getBoundingClientRect().width,columns:[...table.querySelectorAll('th')].map(c=>c.getBoundingClientRect().width)}));
async function settled(){await page.waitForTimeout(60);return layout();}
// Every call is answered, no frame is held and the result list is not loading, also one frame later.
async function quiet(){const deadline=Date.now()+8000,idle=()=>order===null&&gate===null&&pending.size===0;for(;;){
 assert.ok(Date.now()<deadline,`The page did not settle: ${JSON.stringify({order,gate:gate===null?null:{order:gate.order,cursor:String(gate.cursor),browse:gate.browse,held:gate.held.length},pending:pending.size})}`);
 if(idle()&&await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(()=>done(document.querySelector('tbody[aria-busy]').getAttribute('aria-busy')==='false')))))&&idle())return;
 await page.waitForTimeout(20);}}
// Presses a save control with the given frame order (null leaves the order to the server) and waits for its effects.
async function save(control,frame){const before=changes;order=frame;await control.click();const deadline=Date.now()+8000;while(changes===before){assert.ok(Date.now()<deadline,'The save sent no change');await page.waitForTimeout(10);}await quiet();}
try{
 await page.goto(origin);await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();await page.getByText('Connection: ALIVE',{exact:true}).waitFor();await page.getByLabel('Project',{exact:true}).selectOption(project.value);await current();
 await page.getByRole('button',{name:'D1 · Sizing report',exact:true}).click();await page.locator('#detail-pane .item-document').waitFor();await page.getByRole('button',{name:'Dock detail below',exact:true}).click();
 const wide=await settled();assert.ok(Math.abs(wide.width-wide.pane)<1);
 await page.setViewportSize({width:1366,height:768});const narrow=await settled();assert.ok(Math.abs(narrow.width-narrow.pane)<1);
 for(const i of [0,2,3,4,5])assert.ok(Math.abs(wide.columns[i]-narrow.columns[i])<1);
 assert.ok(Math.abs((wide.columns[1]-narrow.columns[1])-(wide.pane-narrow.pane))<1);cases.push('Table fills the viewport; only Title absorbs viewport width changes');
 await page.setViewportSize({width:1800,height:1000});await settled();
 const status=page.getByRole('separator',{name:'Resize Status column',exact:true});const box=await status.boundingBox();
 await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+45,box.y+box.height/2);await page.mouse.up();
 const manual=await layout();assert.ok(Math.abs(manual.columns[2]-wide.columns[2]-45)<1);assert.ok(Math.abs(wide.columns[1]-manual.columns[1]-45)<1);
 await status.press('ArrowRight');assert.ok(Math.abs((await layout()).columns[2]-manual.columns[2]-16)<1);
 await page.evaluate(()=>localStorage.setItem('cq-item-column-widths','[200,200,200,200,200,200]'));
 await page.reload();await page.getByText('Connection: ALIVE',{exact:true}).waitFor();await page.getByLabel('Project',{exact:true}).selectOption(project.value);await current();
 const reset=await settled();assert.ok(Math.abs(reset.columns[2]-wide.columns[2])<1);cases.push('Pointer/keyboard overrides preserve full width and reset on reload; obsolete saved widths are ignored');
 const query=page.getByLabel('Search query',{exact:true});await query.fill('id:D1');await page.getByRole('button',{name:'Search',exact:true}).click();await current();
 const short=await settled();assert.ok(short.columns[2]<wide.columns[2]);
 await change([{Replace:{id:{project,ledger:'Defects',number:'1'},expected:{value:'1'},draft:{...draft,content:{Defect:{...draft.content.Defect,status:'NotReproducible'}}}}}]);
 await page.getByRole('cell',{name:'NotReproducible',exact:true}).waitFor();const longer=await settled();assert.ok(Math.abs(longer.columns[2]-wide.columns[2])<1);
 const metadata=await page.locator('.items-table tbody td:not(:nth-child(2))').evaluateAll(nodes=>nodes.map(n=>({nowrap:getComputedStyle(n).whiteSpace,clipped:n.scrollWidth>n.clientWidth})));
 assert.ok(metadata.every(v=>v.nowrap==='nowrap'&&!v.clipped));cases.push('Metadata widths follow query/live content with no wrapping or clipping');
 await page.getByRole('button',{name:'D1 · Sizing report',exact:true}).click();const doc=page.locator('#detail-pane .item-document');await doc.waitFor();const space=await doc.evaluate(n=>({width:n.getBoundingClientRect().width,parent:n.parentElement.clientWidth,left:getComputedStyle(n).marginLeft,right:getComputedStyle(n).marginRight}));assert.ok(Math.abs(space.width-space.parent)<1);cases.push('Document fills content container without centered margins');
 await page.getByRole('button',{name:'New item',exact:true}).click();const dialog=page.getByRole('dialog',{name:'New item',exact:true});await dialog.getByRole('button',{name:'Create Idea',exact:true}).click();
 await dialog.getByLabel('status',{exact:true}).selectOption('Implemented');await dialog.getByLabel('title',{exact:true}).fill('Implemented idea');await dialog.getByLabel('outcome',{exact:true}).fill('Delivered');await dialog.getByLabel('motivation',{exact:true}).fill('Lifecycle');
 const next=dialog.getByRole('button',{name:'Save item and create next',exact:true});assert.equal(await next.locator('kbd').textContent(),'Ctrl/⌘+Enter');assert.equal(await next.getAttribute('aria-keyshortcuts'),'Control+Enter Meta+Enter');
 await next.click();await page.waitForFunction(()=>{const title=document.querySelector('dialog[open] textarea[aria-label=title]');return title!==null&&title.value==='';});
 const ideas=await call({Search:{input:{project,query:'ledger:Ideas status:Implemented',after:null,snapshot:null,limit:20}}});assert.equal(ideas.Found.page.items.length,1);assert.deepEqual(ideas.Found.page.items[0].outcome,{terminal:true,satisfiesDependency:true});cases.push('Implemented Idea saves through browser; shortcut belongs to repeated-entry button');
 await dialog.getByRole('button',{name:'Cancel edit',exact:true}).click();
 // A new item saved under an applied query that does not hold it. Neither the next editor nor the saved item's view may depend on when the
 // live frame of the saved change arrives. Each case records its own failure so one run reports all of them.
 const failures=[],search=page.getByRole('button',{name:'Search',exact:true}),cancel=dialog.getByRole('button',{name:'Cancel edit',exact:true}),view=title=>page.locator('#detail-pane h2',{hasText:title});
 const editing=()=>page.evaluate(()=>{const title=document.querySelector('dialog[open] textarea[aria-label=title]');return title!==null&&title.value===''&&document.activeElement===title;});
 const fill=async title=>{await dialog.getByLabel('title',{exact:true}).fill(title);await dialog.getByLabel('outcome',{exact:true}).fill('Delivered');await dialog.getByLabel('motivation',{exact:true}).fill('Lifecycle');};
 async function compose(text,title){await query.fill(text);await search.click();await quiet();await page.getByRole('button',{name:'D1 · Sizing report',exact:true}).click();await view('D1 · Sizing report').waitFor();await quiet();
  await page.getByRole('button',{name:'New item',exact:true}).click();await dialog.getByRole('button',{name:'Create Idea',exact:true}).click();await fill(title);}
 async function saving(name,body){try{await body();cases.push(name);}catch(error){failures.push(`${name}: ${String(error.message).split('\n')[0]}`);}finally{order=null;if(await dialog.isVisible())await cancel.click();}}
 const idea=async title=>(await call({Search:{input:{project,query:'ledger:Ideas',after:null,snapshot:null,limit:20}}})).Found.page.items.find(item=>item.title===title);
 const detail=async id=>(await call({Read:{input:{project,selection:{ItemDetail:{id}}}}})).Detail.view.item;
 const shown=text=>page.locator('.items-table td.item-status',{hasText:new RegExp(`^${text}$`)});
 for(const [frame,when] of [['reload','during the reload that follows the save'],['reply','before the reply to the save']]){
  await saving(`"Save item and create next" opens the next editor and keeps the saved item when the frame of the saved change arrives ${when}`,async()=>{
   await compose('id:D1',`Next ${frame}`);await save(next,frame);assert.equal(await editing(),true,'the editor for the next item is not open');
   await cancel.click();assert.equal(await view(`Next ${frame}`).isVisible(),true,'the saved item is not shown');});
  await saving(`"Save item" shows the new item when the frame of the saved change arrives ${when}`,async()=>{
   await compose('id:D1',`Plain ${frame}`);await save(dialog.getByRole('button',{name:'Save item',exact:true}),frame);
   assert.equal(await dialog.isVisible(),false,'the New item dialog is open');assert.equal(await view(`Plain ${frame}`).isVisible(),true,'the saved item is not shown');});
 }
 await saving('"Save item and create next" opens the next editor after every item of a series; the last saved item stays over a live reload and a submitted query closes it',async()=>{
  await compose('id:D1','Series one');await save(next,null);assert.equal(await editing(),true,'the editor is not open after the first item');
  await fill('Series two');await save(next,null);assert.equal(await editing(),true,'the editor is not open after the second item');
  await cancel.click();assert.equal(await view('Series two').isVisible(),true,'the saved item is not shown');
  const defect=await detail({project,ledger:'Defects',number:'1'});await change([{Replace:{id:defect.id,expected:defect.revision,draft}}]);await shown('Open').waitFor();await quiet();
  assert.equal(await view('Series two').isVisible(),true,'a live reload closed the view of the saved item');
  await search.click();await view('Series two').waitFor({state:'detached'});await quiet();assert.equal(await page.locator('#detail-pane h2').count(),0);});
 await saving('An item opened through a relationship link from outside the result stays over a live reload and a submitted query closes it',async()=>{
  const [source,target]=[await detail({project,ledger:'Defects',number:'1'}),await detail({project,ledger:'Defects',number:'2'})];
  await change([{Reference:{source:source.id,expectedSource:source.revision,relation:'RelatesTo',target:target.id,expectedTarget:target.revision,present:true}}]);
  await query.fill('id:D1');await search.click();await quiet();await page.getByRole('button',{name:'D1 · Sizing report',exact:true}).click();
  await page.getByRole('button',{name:'Open D2',exact:true}).click();await view('D2 · Long status').waitFor();await quiet();
  const defect=await detail(source.id);await change([{Replace:{id:defect.id,expected:defect.revision,draft:{...draft,content:{Defect:{...draft.content.Defect,status:'Resolved'}}}}}]);await shown('Resolved').waitFor();await quiet();
  assert.equal(await view('D2 · Long status').isVisible(),true,'a live reload closed the view of the linked item');
  await search.click();await view('D2 · Long status').waitFor({state:'detached'});await quiet();assert.equal(await page.locator('#detail-pane h2').count(),0);});
 await saving('A saved item that a live change brings into the result is closed by the live change that takes it out',async()=>{
  await compose('id:D1 OR status:Accepted','Listed later');await save(dialog.getByRole('button',{name:'Save item',exact:true}),null);assert.equal(await view('Listed later').isVisible(),true,'the saved item is not shown');
  for(const [value,listed] of [['Accepted',true],['Declined',false]]){
   const item=await detail((await idea('Listed later')).id);await change([{Replace:{id:item.id,expected:item.revision,draft:{...item.draft,content:{Idea:{...item.draft.content.Idea,status:value}}}}}]);
   await shown('Accepted').waitFor({state:listed?'attached':'detached'});await quiet();
   assert.equal(await view('Listed later').count(),listed?1:0,listed?'the view closed although the result holds the item':'the view stays although the item left the result');}});
 assert.deepEqual(failures,[]);
 await page.screenshot({path:evidence+'/sizing.png',fullPage:true});assert.deepEqual(errors,[]);
}finally{await writeFile(evidence+'/sizing-results.json',JSON.stringify({cases,errors},null,2)+'\n');await browser.close();}
