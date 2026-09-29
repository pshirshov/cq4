// Behavioral-Active Blackbox Good-Communication; D45 grammar prefixes, including JSON escapes.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
const origin=process.env.CQ_ORIGIN;const project={value:randomUUID()};
const headers={'Authorization':`Bearer ${process.env.CQ_TOKEN}`,'CQ-Session':randomUUID(),'CQ-Protocol-Version':'0.1.0','Content-Type':'application/json'};
async function call(command){const response=await fetch(origin+'/api/call',{method:'POST',headers,body:JSON.stringify(command)});assert.equal(response.status,200);const result=await response.json();assert.equal(result.Failed,undefined);return result;}
await call({Initialize:{config:{project,endpoint:origin,name:'Query prefix fixture'}}});
await call({Change:{input:{project,change:{request:{value:randomUUID()},reason:'Prefix fixtures',fences:[],mutations:[{Create:{draft:{title:'Prefix target',body:'',labels:['quote"mark','slash\\value','unicode😀'],archived:false,citations:[],content:{Defect:{status:'Open',severity:'Low',observed:'Missing prefix hints',expected:'Hints',reproduction:'Type prefixes',cause:null,resolution:[]}}}}}]}}}});
const corpus=['ledger:decisions','ledger:"tasks"','status:Resolved','archived:false','blocked-by:D1','id:D1',`project:${project.value}`,'tag:"quote\\"mark"','tag:"slash\\\\value"','tag:"unicode\\uD83D\\uDE00"','(ledger:decisions OR ledger:tasks) AND NOT status:resolved','ledger:decisions AND ','ledger:tasks OR (NOT archived:true) '];
const failures=[],prefixes=[];let checked=0;
for(const query of corpus){let prefix='';for(const symbol of ['',...query]){prefix+=symbol;const result=await call({Read:{input:{project,selection:{QueryComplete:{query:prefix,cursor:prefix.length,limit:50}}}}});checked++;prefixes.push(prefix);if(result.QueryAnalyzed.analysis.suggestions.length===0)failures.push({query:prefix,diagnostic:result.QueryAnalyzed.analysis.diagnostic});}}
await writeFile(process.env.CQ_BROWSER_EVIDENCE+'/query-prefix-results.json',JSON.stringify({checked,failures},null,2));assert.deepEqual(failures,[]);console.log(`PASS: ${checked} character-prefix completions`);

for (const query of ['tag:"bad\\x', 'tag:"bad\\uQQ', 'tag:"bad\\u0000', 'tag:"bad\\uDE00', 'tag:"bad\\uD83Dx']) {
 const result=await call({Read:{input:{project,selection:{QueryComplete:{query,cursor:query.length,limit:50}}}}});
 assert.equal(result.QueryAnalyzed.analysis.suggestions.length,0,query);
 assert.ok(result.QueryAnalyzed.analysis.diagnostic,query);
}
const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1366,height:768}});page.setDefaultTimeout(4000);
let browserChecked=0;
try {
 await page.goto(origin);await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);await page.getByRole('button',{name:'Sign in',exact:true}).click();
 await page.getByText('Connection: ALIVE',{exact:true}).waitFor();await page.getByLabel('Project',{exact:true}).selectOption(project.value);
 const input=page.getByRole('combobox',{name:'Search query',exact:true});const height=await page.locator('header').first().evaluate(node=>node.getBoundingClientRect().height);
 for(const prefix of prefixes){
  await input.fill(prefix);await input.focus();await page.waitForFunction(() => document.querySelector('.query-popup').getAttribute('aria-busy') === 'false');
  await page.locator('#query-suggestions [role=option]').first().waitFor();
  assert.equal(await page.locator('header').first().evaluate(node=>node.getBoundingClientRect().height),height,prefix);
  assert.notEqual(await input.getAttribute('aria-invalid'),'true',prefix);browserChecked++;
 }
 await page.screenshot({path:process.env.CQ_BROWSER_EVIDENCE+'/query-prefix-browser.png',fullPage:true});
} finally {
 await writeFile(process.env.CQ_BROWSER_EVIDENCE+'/query-prefix-browser-results.json',JSON.stringify({checked:browserChecked,expected:prefixes.length},null,2));
 await browser.close();
}
