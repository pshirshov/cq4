import assert from 'node:assert/strict';
import { once } from 'node:events';
import WebSocket from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { AjvJsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/ajv';

const origin = process.env.CQ_ORIGIN;
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'Content-Type': 'application/json', 'CQ-Protocol-Version': '0.1.0' };
const probe = { project: { value: process.env.CQ_PROJECT_ID }, revision: { value: '9007199254740993' }, text: 'real PostgreSQL λ' };
assert.equal((await fetch(`${origin}/api/hello`)).status, 401);
assert.equal((await fetch(`${origin}/api/hello`, { headers: { ...headers, Origin: 'https://invalid.example' } })).status, 403);
const hello = await fetch(`${origin}/api/hello`, { headers });
assert.deepEqual(await hello.json(), { version: '0.1.0', supported: ['0.1.0'] });
const post = await fetch(`${origin}/api/probe`, { method: 'POST', headers, body: JSON.stringify(probe) });
assert.equal(post.status, 200);
assert.deepEqual(await post.json(), probe);
assert.equal((await fetch(`${origin}/api/probe`, {
  method: 'POST', headers: { ...headers, 'CQ-Protocol-Version': '99.0.0' }, body: JSON.stringify(probe),
})).status, 400);
const crossProject = { ...probe, project: { value: '00000000-0000-0000-0000-000000000002' } };
assert.equal((await fetch(`${origin}/api/probe`, { method: 'POST', headers, body: JSON.stringify(crossProject) })).status, 400);
const socket = new WebSocket(origin.replace('http:', 'ws:') + '/ws', { headers });
await once(socket, 'open');
const received = once(socket, 'message');
socket.send(JSON.stringify(probe));
assert.deepEqual(JSON.parse((await received)[0].toString()), probe);
socket.close();
await once(socket, 'close');

const client = new Client({ name: 'cq-stack-proof', version: '0.1.0' });
const transport = new StreamableHTTPClientTransport(new URL(`${origin}/mcp`), { requestInit: { headers } });
await client.connect(transport);
const tools = await client.listTools();
assert.equal(tools.tools[0].name, 'probe');
assert.equal(new AjvJsonSchemaValidator().getValidator(tools.tools[0].inputSchema)(probe).valid, true);
const result = await client.callTool({ name: 'probe', arguments: probe });
assert.equal(result.isError, false);
assert.deepEqual(result.structuredContent, probe);
const denied = await client.callTool({ name: 'probe', arguments: crossProject });
assert.equal(denied.isError, true);
await client.close();
console.log('HTTP authentication, origin, version, project scope, PostgreSQL exchange, WebSocket and MCP SDK checks passed');
