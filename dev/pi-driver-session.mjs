// Behavioral Effectual Good Communication: the generated Pi extension driving a real attached host, server and child process.
// The model is simulated: it does what the generated /cq:advance prompt instructs with each directive the extension submits.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { chmodSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { setTimeout as sleep } from "node:timers/promises";
import { runtime } from "./pi-runtime.mjs";

const command = process.argv.slice(2);
const root = resolve(process.env.CQ_PI_DRIVER_EVIDENCE);
const origin = process.env.CQ_ORIGIN;
const operatorToken = process.env.CQ_TOKEN;
const operatorSession = process.env.CQ_SESSION;
const guardianBinary = process.env.CQ_GUARDIAN_TEST_BINARY;
const identity = () => ({ value: randomUUID() });
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";

mkdirSync(root, { recursive: true });
const repository = join(root, "consumer");
mkdirSync(repository);
execFileSync("git", ["init", "--quiet", repository]);
execFileSync("git", ["-C", repository, "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "--allow-empty", "-m", "Consumer base"]);
const credential = join(root, "credential");
writeFileSync(credential, operatorToken + "\n", { mode: 0o600 });
const guardian = guardianBinary ?? join(root, "cq-guardian");
if (guardianBinary === undefined) execFileSync("gcc", ["-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", guardian, "host/native/guardian.c"]);
const python = execFileSync("python3", ["-c", "import sys; print(sys.executable)"], { encoding: "utf8" }).trim();
const native = join(root, "fixture-harness");
writeFileSync(native, `#!${python}\n` + readFileSync("dev/dispatch-fixture.py", "utf8").replace('print("codex-cli 0.156.1")', 'print("fixture 0.156.1 2.1.280 0.99.1")'));
chmodSync(native, 0o700);
const limits = { startupMillis: "5000", heartbeatMillis: "1000", graceMillis: "300", killMillis: "2000", retainedOutputBytes: 262144 };
const settings = join(root, "settings.json");
writeFileSync(settings, JSON.stringify({ integrationTarget: null, stateRoot: join(root, "sessions"), guardian, checks: [], evaluation: null, limits,
  harnesses: [["Codex", "fixture-provider", "0.156.1"], ["Claude", "anthropic", "2.1.280"], ["Pi", "fixture-provider", "0.99.1"]].map(([harness, provider, version]) =>
    ({ harness, executable: native, model: "fixture-model", provider, version, providerExtensions: [], providerEnvironment: [] })) }));
// The attached host inherits this process environment through the extension: a token file and settings, no operator token.
for (const name of Object.keys(process.env)) if (name.startsWith("CQ_")) delete process.env[name];
process.env.CQ_TOKEN_FILE = credential;
process.env.CQ_SETTINGS = settings;
const cli = values => execFileSync(command[0], [...command.slice(1), ...values], { cwd: repository, encoding: "utf8", timeout: 60000 });
cli(["init", "--endpoint", origin]);
const wrapper = join(root, "cq-fixture-entrypoint");
writeFileSync(wrapper, "#!/bin/sh\nexec " + command.map(quote).join(" ") + ' "$@"\n');
chmodSync(wrapper, 0o700);

// The generated assets: the extension with its configuration, and the four unchanged workflow prompts. Drive and park are extension commands.
cli(["configure", "pi", "--settings", settings, "--executable", wrapper]);
assert.deepEqual(readdirSync(join(repository, ".pi/extensions")).sort(), ["cq-host.js", "cq-host.json"]);
assert.deepEqual(readdirSync(join(repository, ".pi/prompts")).sort(), ["cq:advance.md", "cq:begin.md", "cq:review.md", "cq:upstream.md"]);
assert.equal(readFileSync(join(repository, ".pi/extensions/cq-host.js"), "utf8"), readFileSync("host/src/main/resources/cq/pi-attached.mjs", "utf8"));
const advancePrompt = readFileSync(join(repository, ".pi/prompts/cq:advance.md"), "utf8");
assert(advancePrompt.includes("--start-token UUID") && advancePrompt.includes("--resume-token UUID") && advancePrompt.includes("WorkflowRequest.Advance"));
const { default: extension } = await import(pathToFileURL(join(repository, ".pi/extensions/cq-host.js")));

async function operator(value) {
  const response = await fetch(origin + "/api/call", { method: "POST", body: JSON.stringify(value), headers: { Authorization: "Bearer " + operatorToken,
    "CQ-Session": operatorSession, "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json" } });
  assert.equal(response.status, 200);
  return response.json();
}
async function session(id) {
  const pi = runtime(id);
  await extension(pi.pi);
  await pi.start();
  const tool = async (name, value) => JSON.parse((await pi.tools.get("cq_" + name).execute(randomUUID(), value, undefined)).content[0].text);
  return { ...pi, tool, refused: (name, value, pattern) => assert.rejects(() => tool(name, value), pattern),
    driver: async () => (await tool("session", { Driver: {} })).Driver.reply.Status.value };
}
const texts = pi => pi.notices.map(notice => notice.message);
const reference = id => "T" + id.number;

// Two concurrent Pi sessions, each with its own attached host. Pi's session identifiers are UUIDs.
const first = await session(randomUUID());
const second = await session(randomUUID());
const context = (await first.tool("session", { Context: {} })).Context.value;
const project = context.project.project;
const draft = { title: "Driven task", body: "Advance the fixture", labels: [], archived: false,
  content: { Task: { status: "Ready", acceptance: ["Report findings"], result: null, validation: [] } }, citations: [] };
const change = (pi, mutations, fences) => pi.tool("change", { project, change: { request: identity(), mutations, fences, reason: "Pi driver fixture" } });
const detail = async id => (await first.tool("read", { project, selection: { ItemDetail: { id } } })).Detail.view.item;
const [target, outsider, other] = (await change(first, [{ Create: { draft } }, { Create: { draft } }, { Create: { draft } }], [])).Changed.ack.items;
const status = async pi => (await operator({ Driver: { input: { project, request: { Control: { key: { harness: "Pi", session: pi.id }, origin: "Extension", action: { Status: {} } } } } } })).Driver.reply.Status.value;
const driveInput = `${reference(target.id)} through=explore`;
const line = `CQ driver on: ${reference(target.id)} through explore`;

// What the model does with a submitted directive, as the generated /cq:advance prompt instructs: one typed activation with the directive's roots, phase and token.
const recorded = [];
function activation(text) {
  const match = /^\/cq:advance --roots (\S+) --through (\S+) --(start|resume)-token (\S+)$/.exec(text);
  assert(match !== null, text);
  const roots = match[1].split(",").map(value => [target, outsider, other].find(item => reference(item.id) === value).id);
  const through = match[2][0].toUpperCase() + match[2].slice(1);
  return { Workflow: { id: identity(), request: { Advance: { roots, through } }, operatorRequirements: `/cq:advance --roots ${match[1]} --through ${match[2]}`,
    token: { [match[3] === "start" ? "Start" : "Resume"]: { token: { value: match[4] } } } } };
}
async function activate(pi) {
  const submitted = pi.sent.at(-1);
  assert.deepEqual(submitted.options, { deliverAs: "followUp", expandPromptTemplates: true });
  const request = activation(submitted.content);
  const accepted = (await pi.tool("session", request)).Workflow.value;
  const cycle = (await pi.driver()).cycle;
  recorded.push({ directive: submitted.content, request: request.Workflow.request, token: request.Workflow.token, cycle: cycle.id, run: cycle.run });
  assert.deepEqual(accepted.cycle, cycle.id);
  assert.deepEqual(new Set(cycle.roots.map(reference)), new Set(/--roots (\S+)/.exec(submitted.content)[1].split(",")));
  assert.equal(cycle.through.toLowerCase(), /--through (\S+)/.exec(submitted.content)[1]);
  return { accepted, cycle };
}

// Rejected drives: the host rejection is shown and no driver exists.
for (const [input, text] of [["through=explore", /Invalid: Drive targets are empty/], [`${reference(target.id)} through=ship`, /Invalid: Unknown through phase ship/],
  [`${reference(target.id)} X9 through=explore`, /Invalid: Unknown item ID X9/], [`workset=${randomUUID()}`, /^CQ driver not started: Missing: /]]) {
  await first.drive(input);
  assert.equal(first.notices.at(-1).type, "error", input);
  assert.match(first.notices.at(-1).message, text);
}
assert.equal(await first.driver(), null);
assert.equal(first.footer(), "CQ driver off");
await first.toggle();
assert.match(first.notices.at(-1).message, /no workset yet; run \/cq:drive <target IDs> through=<phase>/);
assert.equal(await status(first), null);

// Cycle 1: drive with target IDs and a phase, preview, start directive, activation, a dispatched child keeps the run active.
await first.drive(driveInput);
const begun = await status(first);
assert.equal(begun.state, "On");
assert.deepEqual(begun.attached, context.session, "the attached host binds its own attached session at drive-start");
assert.match(texts(first).join("\n"), new RegExp(`${line}\\nAdvanceable \\(1\\):\\n  ${reference(target.id)} \\(target\\) Ready: Driven task\\nContext only, never advanced \\(0\\):\\nReadiness \\(1\\):\\n  ${reference(target.id)}: ready`));
assert.equal(first.footer(), `${line}; 0 active children`);
assert.match(first.sent.at(-1).content, new RegExp(`^/cq:advance --roots ${reference(target.id)} --through explore --start-token [0-9a-f-]{36}$`));
const one = await activate(first);
const run = one.cycle.run;
assert.deepEqual((await first.tool("session", { Context: {} })).Context.value.workflow.id, run);
const selection = { request: identity(), roots: [target.id], work: { Worker: { mode: "Probe" } }, guidance: [], artifacts: [], previous: null, limits };
const [choice] = (await first.tool("dispatch", { Select: { request: selection } })).Selection.value.choices;
const claim = (await first.tool("claim", { project, action: { Acquire: { id: identity(), members: [target.id], durationMillis: "180000" } } })).Claimed.claim;
const child = (await first.tool("dispatch", { StartChoice: { choice: choice.id, harness: "Codex", fence: claim.fence } })).Status.value;
await first.settle("completed");
assert.equal(first.footer(), `${line}; 1 active child`);
assert.match(first.sent.at(-1).content, new RegExp(`^/cq:advance --roots ${reference(target.id)} --through explore --resume-token [0-9a-f-]{36}$`));
const resumed = await activate(first);
assert.deepEqual(resumed.cycle.id, one.cycle.id);
assert.deepEqual(resumed.cycle.run, run, "a resume reattaches to the cycle's run");
assert.equal(resumed.cycle.lineage.filter(entry => entry.member.Run !== undefined).length, 1, "no duplicate run");
assert.deepEqual((await first.tool("session", { Context: {} })).Context.value.workflow.id, run);
const current = await detail(target.id);
const produced = (await change(first, [{ Produce: { producer: target.id, expected: current.revision, drafts: [{ ...draft, title: "Descendant of cycle 1" }], milestone: null } }], [claim.fence])).Changed.ack.items
  .find(item => item.id.number !== target.id.number);
await first.tool("dispatch", { Cancel: { attempt: child.attempt } });
const deadline = Date.now() + 60000;
while ((await first.driver()).activeChildren !== 0) { assert(Date.now() < deadline, "The cancelled child did not settle"); await sleep(200); }

// Cycle 2: the recomputed set gains the descendant, a new start directive is issued and accepted; an unchanged cycle then ends quiescent.
await first.settle("completed");
assert(texts(first).includes(`CQ driver: the advanceable set changed to 2 items; added ${reference(produced.id)}`));
assert.match(first.sent.at(-1).content, /--start-token [0-9a-f-]{36}$/);
assert.notEqual(first.sent.at(-1).content, first.sent[0].content);
const two = await activate(first);
assert.notDeepEqual(two.cycle.id, one.cycle.id);
assert.equal(two.cycle.number, 2);
assert.equal(first.sent.length, 3);
await first.settle("completed");
assert.match(first.notices.at(-1).message, /^CQ driver stopped \(quiescent\): The previous cycle changed nothing/);
assert.match(first.footer(), new RegExp(`^CQ driver off: ${reference(target.id)} through explore; stopped \\(quiescent\\): `));
assert.equal(first.sent.length, 3);
await first.settle("completed");
assert.equal(first.sent.length, 3, "driver off: a finished turn submits nothing");

// A stored workset in a second concurrent session; the toggle key restarts the first session's last workset. Each key has its own driver.
const stored = (await operator({ Workset: { input: { project, action: { Create: { targets: [other.id], through: "Plan" } } } } })).WorksetStored.workset;
await second.drive(`workset=${stored.id.value}`);
assert.equal(second.footer(), `CQ driver on: ${reference(other.id)} through plan; 0 active children`);
assert.match(second.sent.at(-1).content, new RegExp(`^/cq:advance --roots ${reference(other.id)} --through plan --start-token `));
await first.toggle();
const [a, b] = [await status(first), await status(second)];
assert.equal(a.state, "On");
assert.equal(b.state, "On");
assert.deepEqual(b.workset, stored.id);
assert.notEqual(a.key.session, b.key.session);
assert.notDeepEqual(a.attached, b.attached);
await second.park();
assert.equal(second.notices.at(-1).message, `CQ driver parked: ${reference(other.id)} through plan`);
assert.equal((await status(second)).stopped.reason, "Parked");
assert.equal((await status(first)).state, "On", "parking one session leaves the other session's driver on");
await second.settle("completed");
assert.equal(second.sent.length, 1);

// Failure stops. Each leaves the ledger as it was; the toggle key started this drive, and restarts the next ones.
const ledger = async () => JSON.stringify(await Promise.all([target.id, outsider.id, other.id, produced.id].map(detail)));
const failure = async detailPattern => {
  assert.equal(first.notices.at(-1).type, "error");
  assert.match(first.notices.at(-1).message, detailPattern);
  assert.match(first.footer(), detailPattern);
  assert.equal((await status(first)).state, "Off");
};
// The directive is ignored.
let before = await ledger();
let submitted = first.sent.length;
await first.settle("completed");
await failure(/stopped \(failure\): directive not started/);
assert.equal(first.sent.length, submitted);
assert.equal(await ledger(), before);
// An advance that is not the directive: no token.
await first.toggle();
submitted = first.sent.length;
const untracked = activation(first.sent.at(-1).content);
await first.refused("session", { Workflow: { ...untracked.Workflow, token: null } }, /untracked activation/);
assert.deepEqual((await first.tool("session", { Context: {} })).Context.value.workflow.id, two.cycle.run, "no run started");
await first.settle("completed");
await failure(/stopped \(failure\): untracked activation/);
assert.equal(first.sent.length, submitted);
assert.equal(await ledger(), before);
// A direct change without a cycle ID against an item outside the advanceable set, during an active cycle.
await first.toggle();
submitted = first.sent.length;
await activate(first);
const outside = await detail(outsider.id);
await first.refused("change", { project, change: { request: identity(), mutations: [{ Replace: { id: outsider.id, expected: outside.revision, draft: { ...draft, title: "Outside the workset" } } }],
  fences: [], reason: "Pi driver fixture" } }, /out-of-set change/);
await first.settle("completed");
await failure(/stopped \(failure\): .*out-of-set change/);
assert.equal(first.sent.length, submitted);
assert.equal(await ledger(), before);
// Driver off: the session writes as it did before.
await change(first, [{ Replace: { id: outsider.id, expected: outside.revision, draft: { ...draft, title: "Driver off: written as before" } } }], []);

// Closing the Pi session parks a driver that is still on.
await first.toggle();
assert.equal((await status(first)).state, "On");
await first.stop();
const closed = await status(first);
assert.equal(closed.state, "Off");
assert.equal(closed.stopped.reason, "Parked");
await second.stop();

writeFileSync(join(root, "activations.json"), JSON.stringify(recorded, null, 2) + "\n");
console.log(JSON.stringify({ piDriver: "passed", sessions: [first.id, second.id], cycles: recorded.map(entry => ({ directive: entry.directive, cycle: entry.cycle, run: entry.run })) }));
