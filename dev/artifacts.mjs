import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const origin = process.env.CQ_ORIGIN;
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'Content-Type': 'application/json',
  'CQ-Protocol-Version': '0.1.0', 'CQ-Session': process.env.CQ_SESSION };
const id = () => ({ value: randomUUID() });
const project = id();
const session = id();
const attempt = id();
async function post(path, body, credentials) {
  return fetch(`${origin}${path}`, { method: 'POST', headers: credentials, body: JSON.stringify(body) });
}
async function accepted(path, body, credentials) {
  const response = await post(path, body, credentials);
  assert.equal(response.status, 200, await response.clone().text());
  return response.json();
}
async function grant(role, scopedSession) {
  const token = await accepted('/api/grant', { project, actor: { subject: role, session: scopedSession, role },
    expiresAt: String(Date.now() + 60000) }, headers);
  return { ...headers, Authorization: `Bearer ${token.value}` };
}
await accepted('/api/call', { Initialize: { config: { project, endpoint: origin, name: 'artifact client fixture' } } }, headers);
const collector = await grant('Collector', session);
const worker = await grant('Worker', session);
const assignment = { id: id(), project, members: [], attribution: 'Unattributed', cohort: null, evaluation: null };
await accepted('/api/usage', { project, operation: { Assign: { value: assignment } } }, collector);
await accepted('/api/usage', { project, operation: { Start: { value: { id: attempt, assignment: assignment.id, parent: null,
  session, role: 'Worker', harness: 'Codex', provider: 'fixture', model: 'fixture', collector: 'fixture', startedAt: '1000', phase: 'Work', effort: null } } } }, collector);
const upload = { project, id: id(), attempt, kind: 'Transcript', mediaType: 'application/x-ndjson', body: '\u0000'.repeat(256 * 1024) };
const metadata = await accepted('/api/artifact', upload, collector);
assert.equal(metadata.sha256, createHash('sha256').update(upload.body).digest('hex'));
assert.equal(metadata.bytes, 256 * 1024);
assert.ok(JSON.stringify(metadata).length < 1024);
assert.deepEqual(await accepted('/api/artifact', upload, collector), metadata);
assert.equal((await post('/api/artifact', upload, worker)).status, 401);
assert.equal((await post('/api/artifact', upload, await grant('Collector', id()))).status, 401);
assert.equal((await post('/api/artifact', { ...upload, body: 'replacement' }, collector)).status, 400);
assert.equal((await post('/api/artifact', { ...upload, id: id(), body: 'x'.repeat(256 * 1024 + 1) }, collector)).status, 413);
const client = new Client({ name: 'cq-artifact-client', version: '0.1.0' });
await client.connect(new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), { requestInit: { headers: worker } }));
const info = await client.callTool({ name: 'read', arguments: { project, selection: { ArtifactInfo: { id: upload.id } } } });
assert.deepEqual(info.structuredContent.ArtifactInfo.metadata, metadata);
const page = await client.callTool({ name: 'read', arguments: { project, selection: { ArtifactText: { id: upload.id, offset: 8192, limit: 8192 } } } });
assert.equal(page.structuredContent.ArtifactText.page.text, '\u0000'.repeat(8192));
assert.equal(page.structuredContent.ArtifactText.page.next, 16384);
assert.equal(page.structuredContent.ArtifactText.page.hasMore, true);
assert.ok(JSON.stringify(page.structuredContent).length < 65536);
const cross = await client.callTool({ name: 'read', arguments: { project: id(), selection: { ArtifactInfo: { id: upload.id } } } });
assert.ok(cross.structuredContent.Failed.fault.Denied);
await client.close();
console.log('Immutable artifact HTTP/MCP upload, collector/session permissions, exact maximum-size control text and bounded explicit drill-down passed');
