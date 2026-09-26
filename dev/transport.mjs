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
const search = project => ({ project, filter: { ledger: null, archived: 'All' }, after: null, snapshot: null, limit: 20 });
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
assert.deepEqual(listed.tools.map(t => t.name), ['search', 'read', 'change', 'claim', 'usage']);
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
const attempts = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Attempts: { filter: { ProjectAll: {} }, after: null, snapshot: null, limit: 20 } } } });
assert.deepEqual(attempts.structuredContent.UsageAttempts.page.entries, []);
const outcomes = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Outcomes: { attempt: id(), after: '0', limit: 20 } } } });
assert.ok(outcomes.structuredContent.Failed.fault.Missing);
const usage = await client.callTool({ name: 'usage', arguments: { project: first, selection: { Summary: { filter: { ProjectAll: {} } } } } });
assert.equal(usage.structuredContent.UsageSummary.report.direct.total.known, '0');
await client.close();
const restricted = new Client({ name: 'cq-worker-client', version: '0.1.0' });
await restricted.connect(new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), { requestInit: { headers: worker } }));
assert.deepEqual((await restricted.listTools()).tools.map(t => t.name), ['search', 'read', 'usage']);
await assert.rejects(restricted.callTool({ name: 'change', arguments: change('Unavailable') }));
const crossProject = await restricted.callTool({ name: 'search', arguments: search(second) });
assert.equal(crossProject.isError, true);
await restricted.close();
console.log('Two projects: HTTP/MCP/WS, signed role scope, browser cookie/origin, counters, idempotency, revisions, claims, history, committed events and usage reads passed');
