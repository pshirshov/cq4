// Behavioral Active Effectual Good Communication: the generated Pi extension loaded into a real Pi process.
// Pi is real; the model provider and the CQ host are scripted. No login is read: Pi runs with a private home and agent directory and
// a wrong key, and its provider is a local imitation of the OpenAI Responses API. It is not part of a gate: the gates have no Pi.
// Usage: CQ_PI_EXECUTABLE=/absolute/pi CQ_PI_VERSION=1.0.0 [CQ_PI_HOST_CONFIG=/project/.pi/extensions/cq-host.json] node dev/pi-real-check.mjs
// With CQ_PI_HOST_CONFIG, a file `cq configure pi` generated, Pi registers the generated tool contracts and expands the generated prompts.
import assert from "node:assert/strict";
import { spawn, execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

const executable = process.env.CQ_PI_EXECUTABLE;
const version = process.env.CQ_PI_VERSION;
assert(executable !== undefined && version !== undefined, "CQ_PI_EXECUTABLE and CQ_PI_VERSION are required");
const generated = process.env.CQ_PI_HOST_CONFIG;
const DEADLINE_MILLIS = 60000;
const MODEL = "gpt-5.5";
const NAMES = ["session", "dispatch", "search", "read", "graph", "change", "apply", "claim", "usage"];
const tools = generated === undefined
  ? NAMES.map(name => ({ name, description: "fixture " + name, inputSchema: { type: "object", properties: {}, additionalProperties: true } }))
  : JSON.parse(readFileSync(generated, "utf8")).tools;
assert.deepEqual(tools.map(tool => tool.name), NAMES);
const root = mkdtempSync(join(tmpdir(), "cq-pi-real-"));
const lines = file => readFileSync(join(root, file), "utf8").split("\n").filter(line => line !== "").map(line => JSON.parse(line));
// The scripted waiter is a process of its own: its record appears once it has started.
async function waits(count) {
  for (let elapsed = 0; lines("waits.jsonl").length < count; elapsed += 20) {
    assert(elapsed < DEADLINE_MILLIS, `the extension started ${lines("waits.jsonl").length} of ${count} waiters`);
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  return lines("waits.jsonl");
}

// The provider: every request is answered with a completed response, a function call when one is planned and text otherwise.
const requests = [];
const planned = [];
const provider = createServer((request, response) => {
  const chunks = [];
  request.on("data", chunk => chunks.push(chunk));
  request.on("end", () => {
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    const number = requests.push({ tools: body.tools.map(tool => tool.name), input: JSON.stringify(body.input), effort: body.reasoning?.effort });
    const call = planned.shift();
    const item = call === undefined
      ? { id: "msg_" + number, type: "message", role: "assistant", status: "completed", content: [{ type: "output_text", text: "Done " + number, annotations: [] }] }
      : { id: "fc_" + number, type: "function_call", status: "completed", call_id: "call_" + number, name: call.name, arguments: JSON.stringify(call.arguments) };
    const base = { id: "resp_" + number, object: "response", model: body.model, status: "in_progress", output: [] };
    const usage = { input_tokens: 20 + number, input_tokens_details: { cached_tokens: 4 }, output_tokens: 7, output_tokens_details: { reasoning_tokens: 2 }, total_tokens: 27 + number };
    const events = [{ type: "response.created", response: base },
      ...(call === undefined
        ? [{ type: "response.output_item.added", output_index: 0, item: { ...item, content: [], status: "in_progress" } },
          { type: "response.output_text.delta", output_index: 0, item_id: item.id, content_index: 0, delta: item.content[0].text }]
        : [{ type: "response.output_item.added", output_index: 0, item: { ...item, arguments: "", status: "in_progress" } },
          { type: "response.function_call_arguments.done", output_index: 0, item_id: item.id, arguments: item.arguments }]),
      { type: "response.output_item.done", output_index: 0, item },
      { type: "response.completed", response: { ...base, status: "completed", output: [item], usage } }];
    const stream = events.map(event => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join("");
    response.writeHead(200, { "content-type": "text/event-stream", "content-length": Buffer.byteLength(stream) });
    response.end(stream);
  });
});
await new Promise(resolve => provider.listen(0, "127.0.0.1", resolve));

// The CQ host and `cq wait`, scripted: driver and dispatch replies are consumed from files, and every request is logged.
writeFileSync(join(root, "host.mjs"), `
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
const file = name => new URL('./' + name, import.meta.url);
const config = JSON.parse(readFileSync(file('tools.json'), 'utf8'));
const send = value => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...value }) + '\\n');
const next = name => { const replies = JSON.parse(readFileSync(file(name), 'utf8')); writeFileSync(file(name), JSON.stringify(replies.slice(1))); return replies[0]; };
const input = createInterface({ input: process.stdin });
input.on('line', line => {
  const value = JSON.parse(line);
  if (value.method === 'initialize') send({ id: value.id, result: { protocolVersion: '2025-03-26' } });
  else if (value.method === 'tools/list') send({ id: value.id, result: { tools: config } });
  else if (value.method === 'cq/session') send({ id: value.id, result: { directory: '/fixture/session' } });
  else if (value.method === 'cq/piUsage') { appendFileSync(file('usage.jsonl'), JSON.stringify(value.params) + '\\n'); send({ id: value.id, result: {} }); }
  else if (value.method === 'tools/call') {
    appendFileSync(file('calls.jsonl'), JSON.stringify(value.params) + '\\n');
    send({ id: value.id, result: { isError: false, content: [{ type: 'text', text: JSON.stringify(next('tool-replies.json')) }] } });
  } else if (value.method === 'cq/driver') {
    appendFileSync(file('driver.jsonl'), JSON.stringify(value.params) + '\\n');
    const reply = next('driver-replies.json');
    if (reply === undefined) send({ id: value.id, error: { code: -32000, message: 'unscripted driver call' } });
    else send({ id: value.id, result: reply });
  }
});
input.on('close', () => appendFileSync(file('closed'), 'closed\\n'));
`);
writeFileSync(join(root, "waiter.mjs"), `
import { appendFileSync, existsSync, readFileSync } from 'node:fs';
const file = name => new URL('./' + name, import.meta.url);
appendFileSync(file('waits.jsonl'), JSON.stringify(process.argv.slice(2)) + '\\n');
const poll = setInterval(() => {
  if (!existsSync(file('wait.json'))) return;
  clearInterval(poll);
  process.stdout.write(readFileSync(file('wait.json'), 'utf8') + '\\n', () => process.exit(0));
}, 20);
`);
const cq = join(root, "cq");
writeFileSync(cq, `#!/bin/sh\nif [ "$1" = wait ]; then exec ${JSON.stringify(process.execPath)} ${JSON.stringify(join(root, "waiter.mjs"))} "$@"; fi\nexec ${JSON.stringify(process.execPath)} "$@"\n`, { mode: 0o700 });
writeFileSync(join(root, "tools.json"), JSON.stringify(tools));
for (const file of ["usage.jsonl", "calls.jsonl", "driver.jsonl", "waits.jsonl"]) writeFileSync(join(root, file), "");
for (const file of ["tool-replies.json", "driver-replies.json"]) writeFileSync(join(root, file), "[]");

// The project as `cq configure pi` leaves it: the extension beside its configuration, and the workflow prompts.
const project = join(root, "project");
mkdirSync(join(project, ".pi/extensions"), { recursive: true });
writeFileSync(join(project, ".pi/extensions/cq-host.js"), readFileSync("host/src/main/resources/cq/pi-attached.mjs"));
writeFileSync(join(project, ".pi/extensions/cq-host.json"), JSON.stringify({ command: cq, args: [join(root, "host.mjs")], directory: project, tools }));
if (generated === undefined) {
  mkdirSync(join(project, ".pi/prompts"));
  writeFileSync(join(project, ".pi/prompts/cq:advance.md"), "FIXTURE ADVANCE PROMPT $ARGUMENTS\n");
} else cpSync(join(dirname(generated), "../prompts"), join(project, ".pi/prompts"), { recursive: true });
const home = join(root, "home");
mkdirSync(home);
const LAUNCH = ["--offline", "--no-prompt-templates", "--prompt-template", ".pi/prompts", "--provider", "openai", "--model", MODEL, "--mode", "rpc"];

// One Pi process in RPC mode with its own agent directory; `trust` is the content of its trust.json, when it has one.
function pi(name, flags, trust) {
  const agent = join(root, name);
  mkdirSync(agent);
  writeFileSync(join(agent, "models.json"), JSON.stringify({ providers: { openai: { baseUrl: `http://127.0.0.1:${provider.address().port}/v1` } } }));
  if (trust !== undefined) writeFileSync(join(agent, "trust.json"), JSON.stringify(trust, null, 2) + "\n");
  const child = spawn(executable, [...flags, ...LAUNCH], { cwd: project, stdio: ["pipe", "pipe", "pipe"],
    env: { HOME: home, PATH: process.env.PATH, LANG: "C.UTF-8", PI_CODING_AGENT_DIR: agent, OPENAI_API_KEY: "sk-proj-cq-pi-real-check-wrong-key" } });
  const records = [];
  const listeners = new Set();
  let buffer = Buffer.alloc(0);
  let stderr = "";
  child.stderr.on("data", chunk => { stderr += chunk; });
  child.stdout.on("data", chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    let end;
    while ((end = buffer.indexOf(10)) !== -1) {
      records.push(JSON.parse(buffer.subarray(0, end).toString("utf8")));
      buffer = buffer.subarray(end + 1);
    }
    for (const listener of listeners) listener();
  });
  const exited = new Promise(resolve => child.once("close", resolve));
  // The first record at or after `from` that `holds` accepts, with its position.
  function seen(what, holds, from) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { listeners.delete(look); reject(new Error(`Pi did not report ${what}; stderr: ${stderr}`)); }, DEADLINE_MILLIS);
      function look() {
        const at = records.findIndex((record, index) => index >= from && holds(record));
        if (at === -1) return;
        clearTimeout(timer);
        listeners.delete(look);
        resolve({ record: records[at], at });
      }
      listeners.add(look);
      look();
    });
  }
  const event = (type, from) => seen(type, record => record.type === type, from);
  async function command(value) {
    const id = randomUUID();
    const from = records.length;
    child.stdin.write(JSON.stringify({ id, ...value }) + "\n");
    const { record } = await seen("the response to " + value.type, candidate => candidate.type === "response" && candidate.id === id, from);
    assert.equal(record.success, true, JSON.stringify(record));
    return record.data;
  }
  const ui = method => records.filter(record => record.type === "extension_ui_request" && record.method === method);
  return { agent, records, seen, event, command, ui, position: () => records.length,
    footer: () => ui("setStatus").filter(record => record.statusKey === "cq-driver").map(record => record.statusText),
    async stop() { child.stdin.end(); return exited; } };
}
const commands = async session => (await session.command({ type: "get_commands" })).commands.map(entry => `${entry.source} ${entry.name}`);

const observedVersion = execFileSync(executable, ["--version"], { cwd: project, encoding: "utf8",
  env: { HOME: home, PATH: process.env.PATH, PI_CODING_AGENT_DIR: join(root, "version") } }).trim();
assert.equal(observedVersion, version);

// The documented launch: --approve trusts the project for this process.
const session = pi("agent", ["--approve"], undefined);
await session.seen("the driver footer", record => record.type === "extension_ui_request" && record.method === "setStatus" && record.statusKey === "cq-driver", 0);
assert.deepEqual(session.footer(), ["CQ driver off"]);
const listed = await commands(session);
for (const expected of ["extension cq:drive", "extension cq:park", "prompt cq:advance"]) assert(listed.includes(expected), `${expected} in ${listed}`);
const key = (await session.command({ type: "get_state" })).sessionId;
assert.equal(typeof key, "string");

// A turn in which the model calls cq_dispatch: the call reaches the host, its reply reaches the model, and the unit is waited for.
const attempt = randomUUID();
const running = { Status: { value: { attempt: { value: attempt }, phase: "Running", next: "Wait" } } };
writeFileSync(join(root, "tool-replies.json"), JSON.stringify([running]));
planned.push({ name: "cq_dispatch", arguments: { Status: { attempt: { value: attempt }, waitMillis: 0 } } });
let from = session.position();
assert.equal((await session.command({ type: "prompt", message: "Begin" })).disposition, "started");
await session.event("agent_settled", from);
assert.equal(requests.length, 2);
for (const name of NAMES) assert(requests[0].tools.includes("cq_" + name), `cq_${name} in ${requests[0].tools}`);
assert.deepEqual(lines("calls.jsonl"), [{ name: "dispatch", arguments: { Status: { attempt: { value: attempt }, waitMillis: 0 } } }]);
assert(requests[1].input.includes("function_call_output") && requests[1].input.includes(attempt) && requests[1].input.includes("Running"));
assert.deepEqual(await waits(1), [["wait", "--session", "/fixture/session", "--attempt", attempt, "--json"]]);
// Each assistant response is reported to the host with Pi's session identifier and the usage Pi derived from the provider's counts.
assert.deepEqual(lines("usage.jsonl").map(record => [record.sequence, record.session, record.turn, record.provider, record.model, record.responseId, record.stopReason,
  record.input, record.output, record.cacheRead, record.cacheWrite, record.reasoning, record.totalTokens]), [
  ["1", key, "1", "openai", MODEL, "resp_1", "toolUse", "17", "7", "4", "0", "2", "28"],
  ["2", key, "2", "openai", MODEL, "resp_2", "stop", "18", "7", "4", "0", "2", "29"]]);

// The session is idle. The waiter reports that the unit ended, and the extension's message starts a turn by itself.
from = session.position();
writeFileSync(join(root, "wait.json"), JSON.stringify({ Ended: { units: [{ unit: { kind: "Attempt", id: attempt, members: [] }, phase: "Succeeded", next: "Review", blocker: null }] } }));
await session.event("agent_start", from);
await session.event("agent_settled", from);
assert.equal(requests.length, 3);
assert(requests[2].input.includes(`CQ: attempt ${attempt} ended: Succeeded, next Review`), requests[2].input);
assert.equal(lines("waits.jsonl").length, 1, "nothing is left to wait for");
assert(!session.records.slice(from).some(record => record.type === "response"), "no command started that turn");

// A drive: the directive the host returns is submitted as a prompt whose template Pi expands, and after the run settles
// the host is asked again and its stop is shown.
const ON = "CQ driver on: T4 through work; 0 active children";
const OFF = "CQ driver off: T4 through work; stopped (completed): Nothing is left to advance";
const status = (state, line, stopped) => ({ key: { harness: "Pi", session: key }, state, attached: null, workset: null, targets: [], through: "Work", cycle: null,
  activeChildren: 0, directives: 0, stopped, line });
const token = randomUUID();
const stopped = { reason: "Completed", detail: "Nothing is left to advance" };
writeFileSync(join(root, "driver-replies.json"), JSON.stringify([
  { Started: { status: status("On", ON, null), bind: null, message: "CQ driver on: T4 through work",
    preview: { workset: null, targets: [], through: "Work", snapshot: { cursor: { value: "7" }, rootsHash: "hash" }, advanceable: [], context: [], readiness: [] } } },
  { Continue: { directive: { cycle: { value: randomUUID() }, token: { Start: { token: { value: token } } }, text: `/cq:advance --roots T4 --through work --start-token ${token}` },
    status: status("On", ON, null), messages: [] } },
  { Status: { value: status("On", ON, null) } },
  { Stop: { stopped, status: status("Off", OFF, stopped), messages: ["CQ driver stopped (completed): Nothing is left to advance"] } }]));
from = session.position();
await session.command({ type: "prompt", message: "/cq:drive T4 through=work" });
await session.event("agent_settled", from);
await session.seen("the driver's stop", record => record.type === "extension_ui_request" && record.method === "notify" && record.message.startsWith("CQ driver stopped"), from);
assert.deepEqual(lines("driver.jsonl"), [{ Start: { session: key, input: "T4 through=work" } }, { Continue: { session: key, waiting: false } },
  { Status: { session: key } }, { Continue: { session: key, waiting: false } }]);
assert.equal(requests.length, 4);
assert(requests[3].input.includes(`--roots T4 --through work --start-token ${token}`) && !requests[3].input.includes("/cq:advance"), requests[3].input);
if (generated === undefined) assert(requests[3].input.includes("FIXTURE ADVANCE PROMPT --roots T4"));
assert.deepEqual(session.footer(), ["CQ driver off", ON, ON, ON, OFF]);
assert.deepEqual(JSON.parse(readFileSync(join(root, "driver-replies.json"), "utf8")), []);

// Closing Pi closes the host's connection; the launch with --approve saved no trust decision.
assert.equal(await session.stop(), 0);
assert(existsSync(join(root, "closed")));
assert(!existsSync(join(session.agent, "trust.json")));

// Without --approve, the decision saved in the agent directory's trust.json decides whether Pi loads the project's extension:
// the project's own entry, else the nearest parent's, as `cq doctor harness pi` reads the same file.
async function trusted(name, trust) {
  const probe = pi(name, [], trust);
  const loaded = (await commands(probe)).includes("extension cq:drive");
  assert.equal(await probe.stop(), 0);
  return loaded;
}
assert.equal(await trusted("agent-trusted", { [project]: true }), true);
assert.equal(await trusted("agent-parent", { [root]: true, [project]: null }), true);
assert.equal(await trusted("agent-declined", { [root]: true, [project]: false }), false);
assert.equal(await trusted("agent-undecided", undefined), false);

// A managed child: PiAdapter's argument list with the bridge extension, whose tools come from an MCP endpoint over HTTP.
const mcp = [];
const endpoint = createServer((request, response) => {
  const chunks = [];
  request.on("data", chunk => chunks.push(chunk));
  request.on("end", () => {
    const call = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    mcp.push({ authorization: request.headers.authorization, method: call.method, params: call.params });
    const send = result => response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ jsonrpc: "2.0", id: call.id, result }));
    if (call.method === "notifications/initialized") response.writeHead(202).end();
    else if (call.method === "initialize") send({ protocolVersion: "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "fixture", version: "1" } });
    else if (call.method === "tools/list") send({ tools: tools.filter(tool => tool.name === "read") });
    else send({ isError: false, content: [{ type: "text", text: "artifact text from CQ" }] });
  });
});
await new Promise(resolve => endpoint.listen(0, "127.0.0.1", resolve));
const assets = join(root, "assets");
mkdirSync(assets);
writeFileSync(join(assets, "pi-bridge.mjs"), readFileSync("host/src/main/resources/cq/pi-bridge.mjs"));
writeFileSync(join(assets, "pi-mcp.json"), JSON.stringify({ endpoints: [{ name: "cq", url: `http://127.0.0.1:${endpoint.address().port}/mcp`, token: "scoped-fixture", tools: ["read"] }] }));
const child = join(root, "agent-child");
mkdirSync(child);
writeFileSync(join(child, "models.json"), readFileSync(join(session.agent, "models.json")));
const childSession = randomUUID();
const read = { project: { value: randomUUID() }, selection: { Counts: {} } };
planned.push({ name: "cq_read", arguments: read });
const sent = requests.length;
const managed = spawn(executable, ["--offline", "--mode", "json", "--print", "--no-session", "--session-id", childSession, "--provider", "openai", "--model", MODEL,
  "--thinking", "high", "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes", "--no-approve",
  "--tools", "read,cq_read", "--extension", join(assets, "pi-bridge.mjs"), "--system-prompt", "Reply with the artifact text."], { cwd: project, stdio: ["pipe", "pipe", "pipe"],
  env: { HOME: home, PATH: process.env.PATH, LANG: "C.UTF-8", PI_CODING_AGENT_DIR: child, OPENAI_API_KEY: "sk-proj-cq-pi-real-check-wrong-key" } });
let output = "";
let diagnostics = "";
managed.stdout.on("data", chunk => { output += chunk; });
managed.stderr.on("data", chunk => { diagnostics += chunk; });
managed.stdin.end("Read the artifact.\n");
assert.equal(await new Promise(resolve => managed.once("close", resolve)), 0, diagnostics);
const native = output.split("\n").filter(line => line !== "").map(line => JSON.parse(line));
assert.equal(native[0].type, "session");
assert.equal(native[0].id, childSession);
assert.equal(native.at(-1).type, "agent_settled");
// The allowlist is the whole tool inventory of the child, and the effort is the one the launch names.
assert.deepEqual(requests[sent].tools, ["read", "cq_read"]);
assert.equal(requests[sent].effort, "high");
assert.deepEqual(mcp.map(call => call.method), ["initialize", "notifications/initialized", "tools/list", "tools/call"]);
assert(mcp.every(call => call.authorization === "Bearer scoped-fixture"));
assert.deepEqual(mcp.at(-1).params, { name: "read", arguments: read });
assert(requests[sent + 1].input.includes("artifact text from CQ"));
assert.deepEqual(native.filter(record => record.type === "message_end" && record.message.role === "assistant").map(record =>
  [record.message.stopReason, record.message.provider, record.message.responseId, record.message.usage.totalTokens]),
  [["toolUse", "openai", `resp_${sent + 1}`, 28 + sent], ["stop", "openai", `resp_${sent + 2}`, 29 + sent]]);
endpoint.close();

provider.close();
console.log(JSON.stringify({ pi: observedVersion, contracts: generated === undefined ? "fixture" : "generated", evidence: root,
  registration: "passed", toolCall: "passed", usage: "passed", waiterStartsTurn: "passed", drive: "passed", shutdown: "passed", trust: "passed",
  managedChild: "passed" }));
