// Behavioral Active Blackbox Good Communication; local/backend completion parity.
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
const bundled=await build({entryPoints:['web/src/query-local.ts'],bundle:true,write:false,platform:'node',format:'esm'});
const {localSuggestions}=await import('data:text/javascript;base64,'+Buffer.from(bundled.outputFiles[0].contents).toString('base64'));
const origin=process.env.CQ_ORIGIN,project={value:randomUUID()},headers={Authorization:`Bearer ${process.env.CQ_TOKEN}`,'CQ-Session':randomUUID(),'CQ-Protocol-Version':'0.1.0','Content-Type':'application/json'};
async function call(command){const r=await fetch(origin+'/api/call',{method:'POST',headers,body:JSON.stringify(command)});assert.equal(r.status,200);const value=await r.json();assert.equal(value.Failed,undefined);return value;}
await call({Initialize:{config:{project,endpoint:origin,name:'Local completion parity'}}});
const examples=[];
for(const field of ['ledger','status','archived']){
 for(const value of localSuggestions(field+':',field.length+1))for(let i=0;i<=value.text.length;i++)examples.push([field+':'+value.text.slice(0,i),field.length+1+i]);
}
examples.push(['(ledger:tasks AND status:rejected)',26],['-ledger:tasks',9],['ledger:  tasks',8],['ledger:tasks AND status:active',10],['"😀" AND ledger:tasks',16]);
for(const [query,cursor] of examples){
 const local=JSON.parse(JSON.stringify(localSuggestions(query,cursor)));assert.ok(local.length>0,JSON.stringify({query,cursor}));
 const result=await call({Read:{input:{project,selection:{QueryComplete:{query,cursor,limit:50}}}}});
 assert.deepEqual(local,result.QueryAnalyzed.analysis.suggestions,JSON.stringify({query,cursor}));
}
for(const query of ['tag:"hello ledger:d','"ledger:d','"word\\" ledger:d','ledger:"t','ledger:\\u0064','tag:abc'])assert.deepEqual(localSuggestions(query,query.length),[],query);
await writeFile(process.env.CQ_BROWSER_EVIDENCE+'/query-local-results.json',JSON.stringify({checked:examples.length,unsupportedFormsDeferToBackend:true},null,2)+'\n');console.log('Local/backend enum completions match on',examples.length,'prefix/caret cases');
