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
const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1800,height:1000}});page.setDefaultTimeout(8000);
const cases=[],errors=[];page.on('pageerror',e=>errors.push(String(e)));
const current=()=>page.getByText('Data: current',{exact:true}).waitFor();
const layout=()=>page.locator('.items-table').evaluate(table=>({pane:table.parentElement.clientWidth,width:table.getBoundingClientRect().width,columns:[...table.querySelectorAll('th')].map(c=>c.getBoundingClientRect().width)}));
async function settled(){await page.waitForTimeout(60);return layout();}
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
 await dialog.getByRole('button',{name:'Cancel edit',exact:true}).click();await page.screenshot({path:evidence+'/sizing.png',fullPage:true});assert.deepEqual(errors,[]);
}finally{await writeFile(evidence+'/sizing-results.json',JSON.stringify({cases,errors},null,2)+'\n');await browser.close();}
