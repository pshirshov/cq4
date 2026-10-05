// Behavioral Active Blackbox Good Communication: the Pi extension driver against a scripted `cq/driver` host over stdio.
import assert from "node:assert/strict";
import { copyFile, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { runtime } from "./pi-runtime.mjs";

const names = ["session", "dispatch", "search", "read", "graph", "change", "apply", "claim", "usage"];
const tools = names.map(name => ({ name, description: "fixture " + name, inputSchema: { type: "object", properties: {}, additionalProperties: true } }));
const project = { value: "0199a0c0-0000-7000-8000-000000000001" };
const item = (ledger, number) => ({ project, ledger, number: String(number) });
const summary = (ledger, number, title, status) =>
  ({ id: item(ledger, number), revision: { value: "1" }, title, status, archived: false, labels: [], updatedAt: "1", outcome: "Open" });
const preview = {
  workset: null, targets: [item("Goals", 1), item("Tasks", 4)], through: "Work", snapshot: { cursor: { value: "7" }, rootsHash: "hash" },
  advanceable: [{ item: summary("Goals", 1, "Auto-driver", "Open"), root: true }, { item: summary("Tasks", 4, "Host core", "Ready"), root: true },
    { item: summary("Tasks", 9, "Produced descendant", "Draft"), root: false }],
  context: [{ item: summary("Questions", 3, "Which key", "Open"), reasons: [{ Context: { source: item("Tasks", 9), relation: "BlockedBy" } }] }],
  readiness: [{ item: item("Goals", 1), ready: true, reasons: [] }, { item: item("Tasks", 4), ready: true, reasons: [] },
    { item: item("Tasks", 9), ready: false, reasons: [{ Blocked: { prerequisite: item("Questions", 3) } }] }],
};
const ON = "CQ driver on: G1,T4 through work; 0 active children";
const status = (session, state, line, stopped) => ({ key: { harness: "Pi", session }, state, attached: null, workset: null, targets: preview.targets,
  through: "Work", cycle: null, activeChildren: 0, directives: 0, stopped, line });
const started = session => ({ Started: { status: status(session, "On", ON, null), preview, bind: null, message: "CQ driver on: G1,T4 through work" } });
const directive = (flag, token) => ({ cycle: { value: "0199a0c0-0000-7000-8000-0000000000c1" }, token: { [flag === "--start-token" ? "Start" : "Resume"]: { token: { value: token } } },
  text: `/cq:advance --roots G1,T4 --through work ${flag} ${token}` });
const proceed = (session, flag, token, line, messages) => ({ Continue: { directive: directive(flag, token), status: status(session, "On", line, null), messages } });
const stop = (session, reason, detail, label) => {
  const stopped = { reason, detail };
  return { Stop: { stopped, status: status(session, "Off", `CQ driver off: G1,T4 through work; stopped (${label}): ${detail}`, stopped),
    messages: [`CQ driver stopped (${label}): ${detail}`] } };
};
const parked = session => {
  const stopped = { reason: "Parked", detail: "Parked by the operator" };
  return { Parked: { status: status(session, "Off", "CQ driver off: G1,T4 through work; stopped (parked): Parked by the operator", stopped),
    message: "CQ driver parked: G1,T4 through work" } };
};
const status_ = (session, line) => ({ Status: { value: status(session, "On", line, null) } });
const failed = (variant, message) => ({ Failed: { fault: { [variant]: { message } } } });

// One extension instance connected to its own scripted host: each `cq/driver` call consumes the next scripted reply and is logged.
async function session(id) {
  const root = await mkdtemp(join(tmpdir(), "cq-pi-driver-"));
  const extension = join(root, "cq-host.mjs");
  await copyFile("host/src/main/resources/cq/pi-attached.mjs", extension);
  const host = join(root, "host.mjs");
  await writeFile(host, `
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
const file = name => new URL('./' + name, import.meta.url);
const config = JSON.parse(readFileSync(file('cq-host.json'), 'utf8'));
const send = value => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...value }) + '\\n');
createInterface({ input: process.stdin }).on('line', line => {
  const value = JSON.parse(line);
  if (value.method === 'initialize') send({ id: value.id, result: { protocolVersion: '2025-03-26' } });
  else if (value.method === 'tools/list') send({ id: value.id, result: { tools: config.tools } });
  else if (value.method === 'cq/session') send({ id: value.id, result: { directory: config.directory } });
  else if (value.method === 'tools/call') {
    // The next scripted dispatch reply, as the attached host returns it to the extension: the JSON as one text block.
    const replies = JSON.parse(readFileSync(file('tool-replies.json'), 'utf8'));
    writeFileSync(file('tool-replies.json'), JSON.stringify(replies.slice(1)));
    send({ id: value.id, result: { isError: false, content: [{ type: 'text', text: JSON.stringify(replies[0]) }] } });
  }
  else if (value.method === 'cq/driver') {
    appendFileSync(file('requests.jsonl'), JSON.stringify(value.params) + '\\n');
    const replies = JSON.parse(readFileSync(file('replies.json'), 'utf8'));
    if (replies.length === 0) send({ id: value.id, error: { code: -32000, message: 'unscripted driver call' } });
    else {
      writeFileSync(file('replies.json'), JSON.stringify(replies.slice(1)));
      // A scripted { Delayed: { millis, reply } } answers late, as a host that is busy or stalled does.
      if (replies[0].Delayed === undefined) send({ id: value.id, result: replies[0] });
      else setTimeout(() => send({ id: value.id, result: replies[0].Delayed.reply }), replies[0].Delayed.millis);
    }
  }
});
`);
  // The installed executable: `cq wait ...` is the scripted waiter, anything else starts the scripted host.
  const waiter = join(root, "waiter.mjs");
  await writeFile(waiter, `
import { appendFileSync, existsSync, readFileSync } from 'node:fs';
const file = name => new URL('./' + name, import.meta.url);
appendFileSync(file('waits.jsonl'), JSON.stringify({ pid: process.pid, args: process.argv.slice(2) }) + '\\n');
const number = readFileSync(file('waits.jsonl'), 'utf8').split('\\n').length - 1;
const poll = setInterval(() => {
  if (!existsSync(file('wait-' + number + '.json'))) return;
  clearInterval(poll);
  const { exit, output } = JSON.parse(readFileSync(file('wait-' + number + '.json'), 'utf8'));
  process.stdout.write(JSON.stringify(output) + '\\n', () => process.exit(exit));
}, 20);
`);
  const executable = join(root, "cq");
  await writeFile(executable, `#!/bin/sh\nif [ "$1" = wait ]; then exec ${JSON.stringify(process.execPath)} ${JSON.stringify(waiter)} "$@"; fi\nexec ${JSON.stringify(process.execPath)} "$@"\n`, { mode: 0o700 });
  await writeFile(join(root, "waits.jsonl"), "");
  await writeFile(join(root, "tool-replies.json"), "[]");
  await writeFile(join(root, "cq-host.json"), JSON.stringify({ command: executable, args: [host], directory: root, tools }));
  await writeFile(join(root, "requests.jsonl"), "");
  await writeFile(join(root, "replies.json"), "[]");
  const pi = runtime(id);
  await (await import(pathToFileURL(extension))).default(pi.pi);
  return {
    ...pi,
    script: replies => writeFile(join(root, "replies.json"), JSON.stringify(replies)),
    requests: async () => (await readFile(join(root, "requests.jsonl"), "utf8")).split("\n").filter(line => line !== "").map(line => JSON.parse(line)),
    unconsumed: async () => JSON.parse(await readFile(join(root, "replies.json"), "utf8")),
    root,
    // One dispatch tool call of the model, answered with `reply`.
    async dispatch(command, reply) {
      await writeFile(join(root, "tool-replies.json"), JSON.stringify([reply]));
      return pi.tools.get("cq_dispatch").execute("call", command, undefined, undefined, pi.context);
    },
    // The `cq wait` processes the extension has started so far, oldest first; `finish` ends the latest with an exit code and its JSON output.
    waits: async () => (await readFile(join(root, "waits.jsonl"), "utf8")).split("\n").filter(line => line !== "").map(line => JSON.parse(line)),
    async finish(exit, output) {
      const number = (await readFile(join(root, "waits.jsonl"), "utf8")).split("\n").length - 1;
      await writeFile(join(root, `wait-${number}.json`), JSON.stringify({ exit, output }));
    },
  };
}

const texts = pi => pi.notices.map(notice => notice.message);
const followUp = { deliverAs: "followUp", expandPromptTemplates: true };

test("registers /cq:drive, /cq:park and one toggle key, and shows the driver off before any drive", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  assert.deepEqual([...pi.commands.keys()].sort(), ["cq:drive", "cq:park"]);
  assert.deepEqual([...pi.shortcuts.keys()], ["ctrl+alt+a"]);
  assert.equal(pi.footer(), "CQ driver off");
  // Driver off: a finished turn asks the host nothing and sends nothing.
  await pi.settle("completed");
  assert.deepEqual(await pi.requests(), []);
  assert.deepEqual(pi.sent, []);
  await pi.stop();
});

test("drive with target IDs and a through phase shows the host preview, the footer line and submits the start directive verbatim", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "11111111-1111-4111-8111-111111111111", ON, [])]);
  await pi.drive("G1 T4 through=work");
  assert.deepEqual(await pi.requests(), [{ Start: { session: "pi-session-a", input: "G1 T4 through=work" } }, { Continue: { session: "pi-session-a" } }]);
  const shown = texts(pi).join("\n");
  assert.match(shown, /CQ driver on: G1,T4 through work/);
  assert.match(shown, /Advanceable \(3\):\n {2}G1 \(target\) Open: Auto-driver\n {2}T4 \(target\) Ready: Host core\n {2}T9 Draft: Produced descendant/);
  assert.match(shown, /Context only, never advanced \(1\):\n {2}Q3 Open: Which key — BlockedBy T9/);
  assert.match(shown, /Readiness \(3\):\n {2}G1: ready\n {2}T4: ready\n {2}T9: not ready — blocked by Q3/);
  assert.equal(pi.footer(), ON);
  assert.deepEqual(pi.sent, [{ content: "/cq:advance --roots G1,T4 --through work --start-token 11111111-1111-4111-8111-111111111111", options: followUp }]);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});

test("a turn that ends while the host's work is in flight submits nothing and keeps the drive on; the turn after it is decided as usual", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "11111111-1111-4111-8111-111111111111", ON, [])]);
  await pi.drive("G1 T4 through=work");
  const busy = "CQ driver on: G1,T4 through work; 1 active child";
  const waiting = { Waiting: { status: { ...status(pi.id, "On", busy, null), activeChildren: 1 }, message: "CQ driver waiting: cycle 1 has attempt a in flight; the session continues when it ends" } };
  await pi.script([status_(pi.id, busy), waiting, status_(pi.id, busy), waiting, status_(pi.id, ON), proceed(pi.id, "--start-token", "22222222-2222-4222-8222-222222222222", ON, [])]);
  await pi.settle("completed");
  await pi.settle("completed");
  assert.equal(pi.sent.length, 1, "no directive while the host works");
  assert.equal(pi.footer(), busy);
  assert.equal(pi.notices.filter(notice => notice.type === "error").length, 0);
  // The waiter's message started a turn; when it ends with nothing in flight the driver decides the cycle and continues.
  await pi.settle("completed");
  assert.equal(pi.sent.length, 2);
  assert.match(pi.sent.at(-1).content, /--start-token 22222222-2222-4222-8222-222222222222$/);
  assert.deepEqual(await pi.unconsumed(), []);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});

test("the extension waits for the host's work itself and tells the session when a unit ends; the model starts no waiter", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  const until = async (what, holds) => { for (let i = 0; i < 400; i++) { if (await holds()) return; await new Promise(resolve => setTimeout(resolve, 25)); } assert.fail("Not reached: " + what); };
  const alive = pid => { try { process.kill(pid, 0); return true; } catch { return false; } };
  const [a, b, c] = ["00000000-0000-4000-8000-00000000000a", "00000000-0000-4000-8000-00000000000b", "00000000-0000-4000-8000-00000000000c"];
  const running = (attempt, phase) => ({ Status: { value: { attempt: { value: attempt }, phase, next: "Wait" } } });
  const unit = (id, kind) => ({ kind, id, members: [item("Tasks", 4)] });
  const named = (...units) => ["wait", "--session", pi.root, ...units.flat(), "--json"];
  // A start that shows a running child puts it under one waiter; a reply about a unit already followed starts nothing.
  await pi.dispatch({ StartChoice: {} }, running(a, "Preparing"));
  await until("the first waiter", async () => (await pi.waits()).length === 1);
  await pi.dispatch({ Status: {} }, running(a, "Running"));
  // A second child while the first is waited for: one waiter names both, and the replaced one reports nothing.
  await pi.dispatch({ StartChoice: {} }, running(b, "Running"));
  await until("the waiter for both", async () => (await pi.waits()).length === 2);
  const [first, second] = await pi.waits();
  assert.deepEqual([first.args, second.args], [named(["--attempt", a]), named(["--attempt", a], ["--attempt", b])]);
  await until("the replaced waiter to end", async () => !alive(first.pid));
  assert.deepEqual(pi.injected, []);
  // One child ends: the session is told in one compact message that starts a turn, and the other child is waited for again by name.
  await pi.finish(0, { Ended: { units: [{ unit: unit(a, "Attempt"), phase: "Completed", next: "ConsiderAcceptance", blocker: null }], active: [unit(b, "Attempt")] } });
  await until("the message", async () => pi.injected.length === 1);
  assert.deepEqual(pi.injected[0], { message: { customType: "cq-wait", display: true, details: {},
    content: `CQ: attempt ${a} on T4 ended: Completed, next ConsiderAcceptance\nCQ still works on 1 more; you are told when they end.\n` +
      "Read details with cq_dispatch Status (waitMillis 0) only if you need them." }, options: { triggerTurn: true } });
  await until("the waiter for the other child", async () => (await pi.waits()).length === 3);
  assert.deepEqual((await pi.waits())[2].args, named(["--attempt", b]));
  // A terminal reply, a failed start and an integration that rests on the session start no waiter; an integration the host prepares does.
  await pi.dispatch({ Status: {} }, running(a, "Completed"));
  await pi.dispatch({ Integrate: {} }, { Integration: { value: { id: { value: c }, phase: "Ready" } } });
  assert.equal((await pi.waits()).length, 3);
  await pi.dispatch({ PrepareIntegration: {} }, { Integration: { value: { id: { value: c }, phase: "Preparing" } } });
  await until("the waiter with the integration", async () => (await pi.waits()).length === 4);
  assert.deepEqual((await pi.waits())[3].args, named(["--attempt", b], ["--integration", c]));
  await pi.finish(0, { Ended: { units: [{ unit: unit(b, "Attempt"), phase: "Failed", next: "Retry", blocker: "Harness exited\nwith code 1" },
    { unit: { ...unit(c, "Integration"), members: [] }, phase: "Ready", next: "Confirm", blocker: null }], active: [] } });
  await until("the second message", async () => pi.injected.length === 2);
  assert.equal(pi.injected[1].message.content, `CQ: attempt ${b} on T4 ended: Failed, next Retry, blocker: Harness exited with code 1\n` +
    `CQ: integration ${c} ended: Ready, next Confirm\nRead details with cq_dispatch Status (waitMillis 0) only if you need them.`);
  await new Promise(resolve => setTimeout(resolve, 200));
  assert.equal((await pi.waits()).length, 4, "nothing is left to wait for");
  // A host that is gone ends the following; a session that ends stops its waiter.
  await pi.dispatch({ StartChoice: {} }, running(a, "Running"));
  await until("the waiter after the pause", async () => (await pi.waits()).length === 5);
  await pi.finish(3, { HostGone: { active: [unit(a, "Attempt")] } });
  await until("the host-gone notice", async () => pi.notices.some(notice => notice.type === "error" && /CQ host is not running/.test(notice.message)));
  assert.equal(pi.injected.length, 2);
  await pi.dispatch({ StartChoice: {} }, running(b, "Running"));
  await until("the last waiter", async () => (await pi.waits()).length === 6);
  const last = (await pi.waits())[5];
  assert(alive(last.pid));
  await pi.stop();
  await until("the waiter to stop with its session", async () => !alive(last.pid));
  assert.equal(pi.injected.length, 2);
});

test("drive with a stored workset passes the input unchanged; a busy session waits for the turn to settle", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  pi.state.idle = false;
  await pi.script([started(pi.id)]);
  await pi.drive("workset=0199a0c0-0000-7000-8000-0000000000aa");
  assert.deepEqual(await pi.requests(), [{ Start: { session: "pi-session-a", input: "workset=0199a0c0-0000-7000-8000-0000000000aa" } }]);
  assert.deepEqual(pi.sent, []);
  await pi.script([{ Status: { value: status(pi.id, "On", ON, null) } }, proceed(pi.id, "--start-token", "22222222-2222-4222-8222-222222222222", ON, [])]);
  await pi.settle("completed");
  assert.equal(pi.sent.length, 1);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});

test("a rejected drive reports the host rejection and leaves the driver off", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  for (const [input, fault, text] of [
    ["through=work", failed("Invalid", "Drive targets are empty; name at least one item ID. Empty targets never mean the whole project"), /Drive targets are empty/],
    ["G1 through=ship", failed("Invalid", "Unknown through phase ship; expected explore, plan, work, review or integrate"), /Unknown through phase ship/],
    ["workset=0199a0c0-0000-7000-8000-0000000000ab", failed("Missing", "Unknown workset"), /Missing: Unknown workset/],
  ]) {
    await pi.script([fault]);
    await pi.drive(input);
    const notice = pi.notices.at(-1);
    assert.equal(notice.type, "error");
    assert.match(notice.message, /^CQ driver not started: /);
    assert.match(notice.message, text);
  }
  assert.equal(pi.footer(), "CQ driver off");
  assert.deepEqual(pi.sent, []);
  const before = (await pi.requests()).length;
  await pi.settle("completed");
  await pi.toggle();
  assert.equal((await pi.requests()).length, before, "a rejected drive leaves neither a driver nor a last workset");
  await pi.stop();
});

test("the session key is Pi's session identifier: stable across turns and distinct between concurrent sessions", async () => {
  const [first, second] = [await session("0199a0c0-aaaa-7000-8000-00000000000a"), await session("0199a0c0-bbbb-7000-8000-00000000000b")];
  for (const pi of [first, second]) {
    await pi.start();
    await pi.script([started(pi.id), proceed(pi.id, "--start-token", "33333333-3333-4333-8333-333333333333", ON, []),
      { Status: { value: status(pi.id, "On", ON, null) } }, proceed(pi.id, "--resume-token", "44444444-4444-4444-8444-444444444444", ON, []),
      { Status: { value: status(pi.id, "On", ON, null) } }, proceed(pi.id, "--resume-token", "55555555-5555-4555-8555-555555555555", ON, [])]);
    await pi.drive("G1 T4 through=work");
  }
  for (const pi of [first, second]) { await pi.settle("completed"); await pi.settle("completed"); }
  await first.script([parked(first.id)]);
  await first.park();
  const keys = async pi => (await pi.requests()).map(request => Object.values(request)[0].session);
  assert.deepEqual(new Set(await keys(first)), new Set([first.id]));
  assert.deepEqual(new Set(await keys(second)), new Set([second.id]));
  assert.equal((await keys(first)).length, 7);
  assert.equal((await keys(second)).length, 6, "parking one session sends nothing from the other");
  await first.stop();
  await second.script([parked(second.id)]);
  await second.stop();
});

test("the toggle key parks a driver that is on and restarts the last workset of this session; without one it asks for /cq:drive", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.toggle();
  assert.deepEqual(await pi.requests(), [], "no workset: the driver does not start");
  assert.equal(pi.notices.at(-1).type, "warning");
  assert.match(pi.notices.at(-1).message, /\/cq:drive <target IDs> through=<phase>/);
  assert.equal(pi.footer(), "CQ driver off");
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "66666666-6666-4666-8666-666666666666", ON, []), parked(pi.id)]);
  await pi.drive("G1 T4 through=work");
  await pi.toggle();
  assert.deepEqual((await pi.requests()).at(-1), { Park: { session: "pi-session-a" } });
  assert.equal(pi.footer(), "CQ driver off: G1,T4 through work; stopped (parked): Parked by the operator");
  assert.equal(pi.notices.at(-1).message, "CQ driver parked: G1,T4 through work");
  await pi.settle("completed");
  assert.equal((await pi.requests()).length, 3, "parked: the next turn end asks the host nothing");
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "77777777-7777-4777-8777-777777777777", ON, [])]);
  await pi.toggle();
  assert.deepEqual((await pi.requests()).slice(3), [{ Start: { session: "pi-session-a", input: "G1 T4 through=work" } }, { Continue: { session: "pi-session-a" } }]);
  assert.equal(pi.footer(), ON);
  assert.equal(pi.sent.length, 2);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});

test("while on, each settled turn queries the continuation and forwards start and resume directives verbatim with their notices", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  const busy = "CQ driver on: G1,T4 through work; 2 active children";
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "88888888-8888-4888-8888-888888888888", ON, []),
    { Status: { value: { ...status(pi.id, "On", busy, null), activeChildren: 2 } } },
    proceed(pi.id, "--resume-token", "99999999-9999-4999-8999-999999999999", busy, []),
    { Status: { value: status(pi.id, "On", ON, null) } },
    proceed(pi.id, "--start-token", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", ON, ["CQ driver: the advanceable set changed to 4 items; added T12"])]);
  await pi.drive("G1 T4 through=work");
  // The footer follows the active child count between directives.
  await pi.fire("turn_end", { type: "turn_end", outcome: "completed" });
  assert.equal(pi.footer(), busy);
  await pi.fire("agent_settled", { type: "agent_settled" });
  await pi.settle("completed");
  assert.deepEqual(pi.sent.map(message => message.content), [
    "/cq:advance --roots G1,T4 --through work --start-token 88888888-8888-4888-8888-888888888888",
    "/cq:advance --roots G1,T4 --through work --resume-token 99999999-9999-4999-8999-999999999999",
    "/cq:advance --roots G1,T4 --through work --start-token aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]);
  assert(pi.sent.every(message => JSON.stringify(message.options) === JSON.stringify(followUp)));
  assert(texts(pi).includes("CQ driver: the advanceable set changed to 4 items; added T12"));
  assert.equal(pi.footer(), ON);
  assert.deepEqual(await pi.unconsumed(), []);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});

test("every stop reason stops the extension, shows the reason in a notice and the footer, and nothing is sent afterwards", async () => {
  for (const [reason, label, detail, type] of [
    ["Quiescent", "quiescent", "No item of the advanceable set is ready to advance", "info"],
    ["UserInputRequired", "user input required", "Awaiting the user on Q3; the driver never answers questions or infers approval", "info"],
    ["LimitReached", "limit reached", "This drive issued its 64 directives; drive again to continue", "info"],
    ["NotBound", "not bound", "failure: no attached CQ session presented the bind token, so the driver never turned on", "error"],
    ["Failure", "failure", "directive not started: the start directive of cycle 1 was not submitted", "error"],
    ["Failure", "failure", "untracked activation: the bound attached session activated a workflow without a start or resume token", "error"],
    ["Failure", "failure", "the activation's workflow, roots or phase differ from the directive of cycle 1", "error"],
    ["Failure", "failure", "out-of-set change: T7 is outside the advanceable set of cycle 1", "error"],
    ["Parked", "parked", "Parked by the operator", "info"],
  ]) {
    const pi = await session("pi-session-a");
    await pi.start();
    await pi.script([started(pi.id), proceed(pi.id, "--start-token", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", ON, []),
      { Status: { value: status(pi.id, "On", ON, null) } }, stop(pi.id, reason, detail, label)]);
    await pi.drive("G1 T4 through=work");
    await pi.settle("completed");
    assert.deepEqual(pi.notices.at(-1), { message: `CQ driver stopped (${label}): ${detail}`, type }, reason);
    assert.equal(pi.footer(), `CQ driver off: G1,T4 through work; stopped (${label}): ${detail}`);
    assert.equal(pi.sent.length, 1, "a stop forwards no directive");
    const asked = (await pi.requests()).length;
    await pi.settle("completed");
    assert.equal((await pi.requests()).length, asked, "stopped: the next turn end asks the host nothing");
    await pi.stop();
    assert.equal((await pi.requests()).length, asked, "stopped: shutdown has no driver to park");
  }
});

test("a host that no longer holds the driver stops the extension with the host's reason", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "cccccccc-cccc-4ccc-8ccc-cccccccccccc", ON, []), { Status: { value: null } },
    { Stop: { stopped: { reason: "Off", detail: "No CQ driver is on for this session" }, status: null, messages: [] } }]);
  await pi.drive("G1 T4 through=work");
  await pi.settle("completed");
  assert.deepEqual(pi.notices.at(-1), { message: "CQ driver stopped: No CQ driver is on for this session", type: "info" });
  assert.equal(pi.footer(), "CQ driver off");
  await pi.stop();
});

test("an interrupted or failed turn parks the driver instead of continuing", async () => {
  for (const outcome of ["aborted", "error"]) {
    const pi = await session("pi-session-a");
    await pi.start();
    await pi.script([started(pi.id), proceed(pi.id, "--start-token", "dddddddd-dddd-4ddd-8ddd-dddddddddddd", ON, []),
      { Status: { value: status(pi.id, "On", ON, null) } }, parked(pi.id)]);
    await pi.drive("G1 T4 through=work");
    await pi.settle(outcome);
    assert.deepEqual((await pi.requests()).at(-1), { Park: { session: "pi-session-a" } });
    assert(texts(pi).some(text => text.includes(outcome)), "the notice names the turn outcome");
    assert.equal(pi.sent.length, 1);
    assert.equal(pi.footer(), "CQ driver off: G1,T4 through work; stopped (parked): Parked by the operator");
    await pi.stop();
  }
});

test("a failed continuation query stops the extension with an explicit error", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([started(pi.id), failed("Denied", "Driver control requires the operator credential")]);
  await pi.drive("G1 T4 through=work");
  assert.equal(pi.notices.at(-1).type, "error");
  assert.match(pi.notices.at(-1).message, /continuation query failed: Denied: Driver control requires the operator credential/);
  assert.match(pi.footer(), /^CQ driver off: continuation query failed/);
  assert.deepEqual(pi.sent, []);
  const asked = (await pi.requests()).length;
  await pi.settle("completed");
  assert.equal((await pi.requests()).length, asked);
  await pi.stop();
});

test("park reports the host message, and closing the Pi session parks a driver that is still on", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([{ Parked: { status: null, message: "CQ driver is already off" } }]);
  await pi.park();
  assert.deepEqual(pi.notices.at(-1), { message: "CQ driver is already off", type: "info" });
  assert.equal(pi.footer(), "CQ driver off");
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", ON, []), parked(pi.id)]);
  await pi.drive("G1 T4 through=work");
  await pi.stop();
  assert.deepEqual((await pi.requests()).at(-1), { Park: { session: "pi-session-a" } });
});

test("a failed status refresh at a turn end is shown in the footer and the drive continues", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "f1f1f1f1-f1f1-4f1f-8f1f-f1f1f1f1f1f1", ON, []),
    failed("Denied", "Driver control requires the operator credential"), proceed(pi.id, "--resume-token", "f2f2f2f2-f2f2-4f2f-8f2f-f2f2f2f2f2f2", ON, [])]);
  await pi.drive("G1 T4 through=work");
  await pi.fire("turn_end", { type: "turn_end", outcome: "completed" });
  assert.equal(pi.footer(), "CQ driver status unavailable: Denied: Driver control requires the operator credential");
  await pi.fire("agent_settled", { type: "agent_settled" });
  assert.equal(pi.sent.length, 2, "the continuation is still asked for and forwarded");
  assert.equal(pi.footer(), ON);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});

test("a status refresh the host does not answer in time is given up and leaves the CQ connection open", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  await pi.script([started(pi.id), proceed(pi.id, "--start-token", "f3f3f3f3-f3f3-4f3f-8f3f-f3f3f3f3f3f3", ON, []),
    { Delayed: { millis: 6500, reply: { Status: { value: status(pi.id, "On", ON, null) } } } },
    proceed(pi.id, "--resume-token", "f4f4f4f4-f4f4-4f4f-8f4f-f4f4f4f4f4f4", ON, []), parked(pi.id)]);
  await pi.drive("G1 T4 through=work");
  const began = Date.now();
  await Promise.race([pi.fire("turn_end", { type: "turn_end", outcome: "completed" }),
    sleep(8000).then(() => { throw new Error("the status refresh was not given up within its own deadline"); })]);
  assert(Date.now() - began >= 4500, "the refresh waits for its deadline");
  assert.match(pi.footer(), /^CQ driver status unavailable: CQ request timed out/);
  await pi.fire("agent_settled", { type: "agent_settled" });
  assert.equal(pi.sent.length, 2, "the connection still serves the continuation");
  // The late reply arrives and is dropped; the connection keeps working.
  await sleep(2500);
  await pi.park();
  assert.equal(pi.notices.at(-1).message, "CQ driver parked: G1,T4 through work");
  await pi.stop();
});

test("preview titles are shown without control characters and bounded in length", async () => {
  const pi = await session("pi-session-a");
  await pi.start();
  const hostile = { ...preview, advanceable: [{ item: summary("Goals", 1, "Red \u001b[31malert\nAdvanceable (99):\r\tinjected", "Open"), root: true },
    { item: summary("Tasks", 4, "t".repeat(300), "Ready"), root: true }], context: [], readiness: [] };
  await pi.script([{ Started: { ...started(pi.id).Started, preview: hostile } }, proceed(pi.id, "--start-token", "f5f5f5f5-f5f5-4f5f-8f5f-f5f5f5f5f5f5", ON, [])]);
  await pi.drive("G1 T4 through=work");
  const shown = texts(pi).find(text => text.includes("Advanceable (2):"));
  assert.deepEqual(shown.split("\n"), ["CQ driver on: G1,T4 through work", "Advanceable (2):", "  G1 (target) Open: Red  [31malert Advanceable (99):  injected",
    "  T4 (target) Ready: " + "t".repeat(79) + "…", "Context only, never advanced (0):", "Readiness (0):"]);
  await pi.script([parked(pi.id)]);
  await pi.stop();
});
