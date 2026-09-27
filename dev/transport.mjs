import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { AjvJsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/ajv';

const origin = process.env.CQ_ORIGIN;
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'Content-Type': 'application/json',
  'CQ-Protocol-Version': '0.1.0', 'CQ-Session': process.env.CQ_SESSION };
const first = { value: randomUUID() };
const second = { value: randomUUID() };
const id = () => ({ value: randomUUID() });
const draft = title => ({ title, body: 'Markdown λ', labels: [], archived: false,
  content: { Task: { status: 'Ready', acceptance: ['Observable outcome'], result: null, validation: [] } }, citations: [] });
const change = title => ({ project: first, change: { request: id(), mutations: [{ Create: { draft: draft(title) } }], fences: [], reason: 'Actual client test' } });
const post = (path, body, credentials) => fetch(`${origin}${path}`, { method: 'POST', headers: credentials, body: JSON.stringify(body) });
async function call(command, credentials) {
  const response = await post('/api/call', command, credentials);
  assert.equal(response.status, 200, await response.clone().text());
  return response.json();
}
assert.equal((await fetch(`${origin}/api/hello`)).status, 401);
assert.equal((await fetch(`${origin}/api/hello`, { headers: { ...headers, Origin: 'https://invalid.example' } })).status, 403);
assert.deepEqual(await (await fetch(`${origin}/api/hello`, { headers })).json(), { version: '0.1.0', supported: ['0.1.0'] });
assert.equal((await post('/api/call', {}, { ...headers, 'CQ-Protocol-Version': '99.0.0' })).status, 400);
assert.equal((await post('/api/call', { text: 'x'.repeat(2 * 1024 * 1024) }, headers)).status, 413);
const initialize = (project, name) => ({ Initialize: { config: { project, endpoint: origin, name } } });
const original = await call(initialize(first, 'first'), headers);
await call(initialize(second, 'second'), headers);
assert.deepEqual(await call(initialize(first, 'reattached'), headers), original);
const projects = await call({ Projects: { after: null, limit: 200 } }, headers);
assert.ok(projects.Projects.page.projects.some(p => p.id.value === first.value));
assert.ok(projects.Projects.page.projects.some(p => p.id.value === second.value));
const operation = change('HTTP created');
const created = await call({ Change: { input: operation } }, headers);
const item = created.Changed.ack.items[0];
assert.equal(item.id.number, '1');
assert.deepEqual(await call({ Change: { input: operation } }, headers), created);
const secondCreated = await call({ Change: { input: { ...change('Independent counter'), project: second } } }, headers);
assert.equal(secondCreated.Changed.ack.items[0].id.number, '1');
const search = project => ({ project, query: 'archived:all', after: null, snapshot: null, limit: 20 });
const page = (await call({ Search: { input: search(first) } }, headers)).Found.page;
assert.equal(page.items.length, 1);
const read = project => ({ project, selection: { ItemDetail: { id: item.id } } });
assert.ok((await call({ Read: { input: read(second) } }, headers)).Failed.fault.Denied);
async function grant(role) {
  const response = await post('/api/grant', { project: first, actor: { subject: role, session: id(), role }, expiresAt: String(Date.now() + 60000) }, headers);
  assert.equal(response.status, 200);
  return { ...headers, Authorization: `Bearer ${(await response.json()).value}` };
}
const worker = await grant('Worker');
assert.ok((await call({ Change: { input: change('Denied worker') } }, worker)).Failed.fault.Denied);
assert.ok((await call({ Search: { input: search(second) } }, worker)).Failed.fault.Denied);
assert.equal((await post('/api/grant', { project: first, actor: { subject: 'escalation', session: id(), role: 'Governor' }, expiresAt: String(Date.now() + 60000) }, worker)).status, 401);
const rootClaim = { project: first, action: { Acquire: { id: id(), members: [item.id], durationMillis: '60000' } } };
const claim = (await call({ ClaimWork: { input: rootClaim } }, headers)).Claimed.claim;
const replace = { project: first, change: { request: id(), reason: 'Replace', fences: [], mutations: [{ Replace: { id: item.id, expected: item.revision, draft: draft('Changed') } }] } };
assert.ok((await call({ Change: { input: replace } }, headers)).Failed.fault.StaleFence);
replace.change.fences = [claim.fence];
assert.ok((await call({ Change: { input: replace } }, headers)).Changed);
const stale = { ...replace, change: { ...replace.change, request: id() } };
assert.ok((await call({ Change: { input: stale } }, headers)).Failed.fault.Conflict);
await call({ ClaimWork: { input: { project: first, action: { Release: { fence: claim.fence } } } } }, headers);
const history = await call({ Read: { input: { project: first, selection: { History: { id: item.id, before: { value: '9223372036854775807' }, limit: 20 } } } } }, headers);
assert.equal(history.History.page.entries.length, 2);

const login = await post('/api/login', {}, headers);
assert.equal(login.status, 200);
const cookie = login.headers.get('set-cookie');
assert.ok(cookie.includes('HttpOnly') && cookie.includes('SameSite=Strict'));
const browserHeaders = { 'Content-Type': 'application/json', 'CQ-Protocol-Version': '0.1.0', Cookie: cookie.split(';')[0], Origin: origin };
assert.ok((await call({ Search: { input: search(first) } }, browserHeaders)).Found);
const noOrigin = { ...browserHeaders };
delete noOrigin.Origin;
assert.equal((await post('/api/call', { Projects: { after: null, limit: 10 } }, noOrigin)).status, 401);

const socket = new WebSocket(origin.replace('http:', 'ws:') + '/ws', { headers: browserHeaders });
const frames = [];
const pending = new Map();
socket.on('message', data => {
  const frame = JSON.parse(data.toString());
  frames.push(frame);
  if (frame.Ping) socket.send(JSON.stringify({ Pong: { nonce: frame.Ping.nonce } }));
  if (frame.Reply && pending.has(frame.Reply.id.value)) pending.get(frame.Reply.id.value)(frame.Reply.result);
  if (frame.Pong && pending.has(frame.Pong.nonce)) pending.get(frame.Pong.nonce)(frame.Pong);
});
await once(socket, 'open');
function exchange(key, frame) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { pending.delete(key); reject(new Error('WebSocket response deadline')); }, 5000);
    pending.set(key, result => { clearTimeout(timeout); pending.delete(key); resolve(result); });
    socket.send(JSON.stringify(frame));
  });
}
const nonce = randomUUID();
assert.deepEqual(await exchange(nonce, { Ping: { nonce } }), { nonce });
const request = id();
const detail = await exchange(request.value, { Call: { id: request, command: { Read: { input: read(first) } } } });
assert.equal(detail.Detail.view.item.draft.title, 'Changed');
const subscription = id();
const replay = await exchange(subscription.value, { Subscribe: { id: subscription, project: first, after: { value: '0' } } });
assert.equal(replay.Changes.page.events.length, 2);
const changed = await call({ Change: { input: change('Live event') } }, headers);
const deadline = Date.now() + 5000;
while (!frames.some(f => f.Changes && f.Changes.page.events.some(e => e.cursor.value === changed.Changed.ack.cursor.value))) {
  assert.ok(Date.now() < deadline, 'Committed change must reach subscriber');
  await new Promise(resolve => setTimeout(resolve, 20));
}
assert.ok(frames.filter(f => f.Changes).every(f => f.Changes.subscription.value === subscription.value));
const closed = once(socket, 'close');
socket.send('{malformed');
assert.equal((await closed)[0], 1007);

const client = new Client({ name: 'cq-ledger-client', version: '0.1.0' });
await client.connect(new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), { requestInit: { headers } }));
const listed = await client.listTools();
assert.deepEqual(listed.tools.map(t => t.name), ['search', 'read', 'graph', 'change', 'apply', 'claim', 'usage']);
const schema = listed.tools.find(t => t.name === 'change');
const validator = new AjvJsonSchemaValidator().getValidator(schema.inputSchema);
const mcpChange = change('MCP created');
assert.equal(validator(mcpChange).valid, true);
assert.equal(validator({ ...mcpChange, project: { value: 'invalid' } }).valid, false);
const mcpResult = await client.callTool({ name: 'change', arguments: mcpChange });
assert.equal(mcpResult.isError, false);
assert.ok(mcpResult.structuredContent.Changed);
const mcpRead = await client.callTool({ name: 'read', arguments: read(first) });
assert.equal(mcpRead.structuredContent.Detail.view.item.draft.title, 'Changed');
const queryInput = { ...search(first), query: 'ledger:Tasks ("MCP created" OR "not present") NOT status:Done' };
const httpQuery = await call({ Search: { input: queryInput } }, headers);
const mcpQuery = await client.callTool({ name: 'search', arguments: queryInput });
assert.deepEqual(mcpQuery.structuredContent, httpQuery);
assert.deepEqual(httpQuery.Found.page.items.map(item => item.title), ['MCP created']);
const malformedQuery = { ...queryInput, query: 'alpha AND' };
const httpDiagnostic = await call({ Search: { input: malformedQuery } }, headers);
const mcpDiagnostic = await client.callTool({ name: 'search', arguments: malformedQuery });
assert.equal(mcpDiagnostic.isError, true);
assert.deepEqual(mcpDiagnostic.structuredContent, httpDiagnostic);
assert.deepEqual(httpDiagnostic.Failed.fault.QuerySyntax.diagnostic.span, { start: 9, end: 9 });
const completion = { project: first, selection: { QueryComplete: { query: 'status:Re', cursor: 9, limit: 20 } } };
const httpCompletion = await call({ Read: { input: completion } }, headers);
const mcpCompletion = await client.callTool({ name: 'read', arguments: completion });
assert.deepEqual(mcpCompletion.structuredContent, httpCompletion);
assert.ok(httpCompletion.QueryAnalyzed.analysis.suggestions.some(value => value.text === 'ready'));
assert.ok(httpCompletion.QueryAnalyzed.analysis.suggestions.every(value => value.span.start === 7 && value.span.end === 9));
const references = await client.callTool({ name: 'read', arguments: { project: first,
  selection: { QueryComplete: { query: 'blocked-by:T', cursor: 12, limit: 1 } } } });
assert.equal(references.structuredContent.QueryAnalyzed.analysis.suggestions[0].text, 'T1');
assert.equal(references.structuredContent.QueryAnalyzed.analysis.hasMore, true);
const child = mcpResult.structuredContent.Changed.ack.items[0];
await call({ Change: { input: { project: first, change: { request: id(), fences: [], reason: 'Workset topology', mutations: [{ Reference: {
  source: item.id, expectedSource: mcpRead.structuredContent.Detail.view.item.revision, relation: 'Produces',
  target: child.id, expectedTarget: child.revision, present: true,
} }] } } } }, headers);
const graphInput = { project: first, roots: [item.id], after: null, snapshot: null, limit: 1 };
const httpGraph = await call({ Graph: { input: graphInput } }, headers);
const mcpGraph = await client.callTool({ name: 'graph', arguments: graphInput });
assert.deepEqual(mcpGraph.structuredContent, httpGraph);
const graphPage = httpGraph.Workset.page;
assert.equal(graphPage.selectedCount, 2);
assert.equal(graphPage.contextCount, 0);
assert.equal(graphPage.hasMore, true);
assert.equal(graphPage.entries[0].root, true);
const nextGraph = await client.callTool({ name: 'graph', arguments: { ...graphInput, after: graphPage.after, snapshot: graphPage.snapshot } });
assert.deepEqual(nextGraph.structuredContent.Workset.page.entries.map(value => value.item.id), [child.id]);
assert.equal(nextGraph.structuredContent.Workset.page.hasMore, false);
const terminationRead = { project: first, selection: { Termination: { roots: [item.id], intent: 'Cancel' } } };
const httpPreview = (await call({ Read: { input: terminationRead } }, headers)).Termination.preview;
const mcpPreview = await client.callTool({ name: 'read', arguments: terminationRead });
assert.deepEqual(mcpPreview.structuredContent.Termination.preview, httpPreview);
assert.equal(httpPreview.plan.canApply, true);
assert.equal(httpPreview.plan.entries.filter(entry => entry.effect.Change).length, 2);
const terminationChange = { project: first, change: { request: id(), reason: 'Reviewed client termination', fences: [],
  mutations: [{ Terminate: { roots: [item.id], intent: 'Cancel', snapshot: httpPreview.snapshot } }] } };
assert.ok((await call({ Change: { input: terminationChange } }, worker)).Failed.fault.Denied);
const terminationResult = await client.callTool({ name: 'change', arguments: terminationChange });
assert.equal(terminationResult.structuredContent.Changed.ack.items.length, 2);
assert.deepEqual(await call({ Change: { input: terminationChange } }, headers), terminationResult.structuredContent);
const cancelledDetail = await client.callTool({ name: 'read', arguments: read(first) });
assert.equal(cancelledDetail.structuredContent.Detail.view.item.draft.content.Task.status, 'Cancelled');
const cancelledHistory = await call({ Read: { input: { project: first, selection: { History: { id: item.id,
  before: { value: '9223372036854775807' }, limit: 20 } } } } }, headers);
assert.equal(cancelledHistory.History.page.entries[0].reason, terminationChange.change.reason);
const producer = (await call({ Change: { input: change('Claimed producer') } }, headers)).Changed.ack.items[0];
const producerClaim = (await client.callTool({ name: 'claim', arguments: { project: first,
  action: { Acquire: { id: id(), members: [producer.id], durationMillis: '300000' } } } })).structuredContent.Claimed.claim;
const production = { project: first, change: { request: id(), reason: 'Atomic client production', fences: [producerClaim.fence],
  mutations: [{ Produce: { producer: producer.id, expected: producer.revision, drafts: [draft('Produced one'), draft('Produced two')] } }] } };
const produced = (await client.callTool({ name: 'change', arguments: production })).structuredContent;
assert.equal(produced.Changed.ack.items.length, 3);
assert.deepEqual(await call({ Change: { input: production } }, headers), produced);
const producedChildren = produced.Changed.ack.items.filter(value => value.id.number !== producer.id.number);
const producedDetail = (await call({ Read: { input: { project: first, selection: { ItemDetail: { id: producer.id } } } } }, headers)).Detail.view;
assert.equal(producedDetail.item.revision.value, '2');
assert.deepEqual(producedDetail.refs.map(ref => ref.target), producedChildren.map(value => value.id));
await call({ ClaimWork: { input: { project: first, action: { Release: { fence: producerClaim.fence } } } } }, headers);
const governor = await grant('Governor');
const governingClaim = (await call({ ClaimWork: { input: { project: first,
  action: { Acquire: { id: id(), members: [producer.id, producedChildren[0].id], durationMillis: '300000' } } } } }, governor)).Claimed.claim;
const claimsRead = { project: first, selection: { Claims: { members: [producer.id] } } };
const claimsPreview = (await client.callTool({ name: 'read', arguments: claimsRead })).structuredContent.Claims.preview;
assert.deepEqual((await call({ Read: { input: claimsRead } }, headers)).Claims.preview, claimsPreview);
assert.deepEqual(claimsPreview.claims, [governingClaim]);
assert.equal(claimsPreview.claims[0].members.length, 2);
const takeover = { project: first, action: { Takeover: { id: id(), owner: claim.owner, members: [producer.id],
  durationMillis: '300000', snapshot: claimsPreview.snapshot } } };
assert.ok((await call({ ClaimWork: { input: takeover } }, governor)).Failed.fault.Denied);
const taken = (await client.callTool({ name: 'claim', arguments: takeover })).structuredContent;
assert.deepEqual(await call({ ClaimWork: { input: takeover } }, headers), taken);
assert.deepEqual(taken.Claimed.claim.members, [producer.id]);
assert.ok(taken.Claimed.claim.origin.Takeover);
assert.ok((await call({ ClaimWork: { input: { project: first,
  action: { Renew: { fence: governingClaim.fence, durationMillis: '300000' } } } } }, governor)).Failed.fault.StaleFence);
const collateral = (await call({ Read: { input: { project: first, selection: { Claims: { members: [producedChildren[0].id] } } } } }, headers)).Claims.preview;
assert.deepEqual(collateral.claims, []);
assert.deepEqual(collateral.members, [producedChildren[0]]);
const attempts = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Attempts: { filter: { ProjectAll: {} }, after: null, snapshot: null, limit: 20 } } } });
assert.deepEqual(attempts.structuredContent.UsageAttempts.page.entries, []);
const costs = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Costs: { filter: { ProjectAll: {} }, after: null, snapshot: null, limit: 20 } } } });
assert.deepEqual(costs.structuredContent.UsageCosts.page.entries, []);
const outcomes = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Outcomes: { attempt: id(), after: '0', limit: 20 } } } });
assert.ok(outcomes.structuredContent.Failed.fault.Missing);
const usage = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Summary: { filter: { ProjectAll: {} } } } } });
assert.equal(usage.structuredContent.UsageSummary.report.direct.total.known, '0');
await client.close();
const restricted = new Client({ name: 'cq-worker-client', version: '0.1.0' });
await restricted.connect(new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), { requestInit: { headers: worker } }));
assert.deepEqual((await restricted.listTools()).tools.map(t => t.name), ['search', 'read', 'graph', 'usage']);
await assert.rejects(restricted.callTool({ name: 'change', arguments: change('Unavailable') }));
const crossProject = await restricted.callTool({ name: 'search', arguments: search(second) });
assert.equal(crossProject.isError, true);
const scopedQuery = await restricted.callTool({ name: 'search', arguments: { ...search(first), query: `project:${second.value} OR archived:all` } });
assert.ok(scopedQuery.structuredContent.Found.page.items.every(item => item.id.project.value === first.value));
const crossCompletion = await restricted.callTool({ name: 'read', arguments: { ...completion, project: second } });
assert.equal(crossCompletion.isError, true);
assert.ok(crossCompletion.structuredContent.Failed.fault.Denied);
const crossGraph = await restricted.callTool({ name: 'graph', arguments: { ...graphInput, roots: [secondCreated.Changed.ack.items[0].id] } });
assert.equal(crossGraph.isError, true);
assert.ok(crossGraph.structuredContent.Failed.fault.Denied);
await restricted.close();
console.log('Two projects: HTTP/MCP/WS, signed role scope, browser cookie/origin, counters, idempotency, revisions, claims, history, committed events and usage reads passed');
