// Behavioral Active Effectual Good Communication: extension/native stdio boundary.
import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile, copyFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const root = await mkdtemp(join(tmpdir(), "cq-pi-attached-"));
const extension = join(root, "cq-host.mjs");
await copyFile("host/src/main/resources/cq/pi-attached.mjs", extension);
const names = ["session", "dispatch", "search", "read", "graph", "change", "apply", "claim", "usage"];
const tools = names.map(name => ({ name, description: "fixture " + name, inputSchema: { type: "object", properties: {}, additionalProperties: true } }));
const fixture = join(root, "native.mjs");
await writeFile(fixture, `
import { readFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
const config = JSON.parse(readFileSync(new URL('./cq-host.json', import.meta.url), 'utf8'));
const seen = [];
const lines = createInterface({ input: process.stdin });
const send = value => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...value })+'\\n');
lines.on('line', line => {
  const value = JSON.parse(line); seen.push(value);
  if (value.method === 'initialize') { send({id:value.id,result:{protocolVersion:'2025-03-26'}}); send({id:'owner-ping',method:'ping'}); }
  else if (value.method === 'tools/list') send({id:value.id,result:{tools:config.tools}});
  else if (value.method === 'tools/call') {
    if (!value.params.arguments.hold) send({id:value.id,result:{isError:false,content:[{type:'text',text:'bounded reply'}]}});
  }
  else if (value.method === 'cq/piUsage') send({id:value.id,result:{}});
  else if (value.method === 'cq/session') send({id:value.id,result:{directory:'/fixture/session'}});
});
lines.on('close', () => writeFileSync(new URL('./observed.json', import.meta.url), JSON.stringify(seen)));
`);
await writeFile(join(root, "cq-host.json"), JSON.stringify({ command: process.execPath, args: [fixture], directory: root, tools }));
const { default: load } = await import(pathToFileURL(extension));
// The deadlines the extension arms, as it asks the runtime for them.
const armed = [];
const arm = globalThis.setTimeout;
globalThis.setTimeout = (callback, millis, ...rest) => { armed.push(millis); return arm(callback, millis, ...rest); };
async function deadline(tool, parameters) {
  armed.length = 0;
  await pi.registered.get("cq_" + tool).execute("deadline", parameters, undefined);
  assert.equal(armed.length, 1, "one deadline per request");
  return armed[0];
}
function runtime() {
  const handlers = new Map();
  const registered = new Map();
  return { handlers, registered, on: (name, handler) => handlers.set(name, handler), registerTool: tool => registered.set(tool.name, tool),
    registerCommand: () => {}, registerShortcut: () => {} };
}
const idle = { ui: { setStatus: () => {} } };
const pi = runtime();
await load(pi);
assert.equal(pi.registered.size, 9);
await pi.handlers.get("session_start")(undefined, idle);
const response = await pi.registered.get("cq_session").execute("first", {}, undefined);
assert.deepEqual(response.content, [{ type: "text", text: "bounded reply" }]);
// A request that does not wait keeps the short deadline; a dispatch command that waits is allowed its wait on top of it.
const attempt = { value: "00000000-0000-4000-8000-000000000001" };
assert.equal(await deadline("session", { Context: {} }), 35000);
assert.equal(await deadline("dispatch", { Cancel: { attempt } }), 35000);
assert.equal(await deadline("dispatch", { Status: { attempt, waitMillis: 0 } }), 35000);
assert.equal(await deadline("dispatch", { Status: { attempt, waitMillis: 120000 } }), 155000);
assert.equal(await deadline("dispatch", { IntegrationStatus: { id: attempt, waitMillis: 60000 } }), 95000);
assert.equal(await deadline("dispatch", { CombinationStatus: { id: attempt, waitMillis: 120000 } }), 155000);
assert.equal(await deadline("dispatch", { Revalidate: { id: attempt, result: attempt, fence: {} } }), 155000);
// A wait the host refuses is answered at once, and the same field of another tool is no wait.
assert.equal(await deadline("dispatch", { Status: { attempt, waitMillis: 120001 } }), 35000);
assert.equal(await deadline("dispatch", { Status: { attempt, waitMillis: "120000" } }), 35000);
assert.equal(await deadline("session", { Status: { attempt, waitMillis: 120000 } }), 35000);
pi.handlers.get("turn_start")();
await pi.handlers.get("message_end")({ message: { role: "assistant", provider: "provider", model: "model", timestamp: 1000, stopReason: "stop",
  content: [{ type: "text", text: "PRIVATE_ASSISTANT_TEXT" }], usage: { input: 10, output: 3, cacheRead: 2, cacheWrite: 0, totalTokens: 15, cost: { total: 0.001 } } } },
{ sessionManager: { getSessionId: () => "native-session" } });
await pi.handlers.get("session_shutdown")();
await pi.handlers.get("session_shutdown")();
const seen = JSON.parse(await readFile(join(root, "observed.json"), "utf8"));
assert(seen.some(value => value.id === "owner-ping" && value.result !== undefined));
const usage = seen.find(value => value.method === "cq/piUsage").params;
assert.equal(usage.sequence, "1");
assert.equal(usage.turn, "1");
assert.equal(usage.input, "10");
assert.equal(usage.reasoning, null);
assert.deepEqual(usage.costUSD, { value: "0.001" });
assert(!JSON.stringify(seen).includes("PRIVATE_ASSISTANT_TEXT"));
await assert.rejects(() => pi.registered.get("cq_session").execute("after-close", {}, undefined), /unavailable/);
await pi.handlers.get("session_start")(undefined, idle);
pi.handlers.get("turn_start")();
await pi.handlers.get("message_end")({ message: { role: "assistant", provider: "provider", model: "model", timestamp: 2000, stopReason: "stop",
  usage: { input: 5, output: 2, cacheRead: 0, cacheWrite: 0, totalTokens: 7 } } },
{ sessionManager: { getSessionId: () => "next-native-session" } });
await pi.handlers.get("session_shutdown")();
const restarted = JSON.parse(await readFile(join(root, "observed.json"), "utf8")).find(value => value.method === "cq/piUsage").params;
assert.equal(restarted.sequence, "1", "New CQ host must receive its own contiguous sequence");
assert.equal(restarted.turn, "1");
assert.equal(restarted.session, "next-native-session");
const interrupted = runtime();
await load(interrupted);
await interrupted.handlers.get("session_start")(undefined, idle);
const controller = new AbortController();
const request = interrupted.registered.get("cq_session").execute("aborted", { hold: true }, controller.signal);
controller.abort();
await assert.rejects(() => request, /interrupted/);
await interrupted.handlers.get("session_shutdown")();
console.log(JSON.stringify({ stdio: "passed", heartbeat: "passed", usageMetadataOnly: "passed", shutdown: "passed", restart: "passed", abort: "passed", deadlines: "passed" }));
