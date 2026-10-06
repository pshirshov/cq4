import { readFile, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { isDeepStrictEqual } from "node:util";

const MAX_CONFIG_BYTES = 2097152;
const MAX_FRAME_BYTES = 2097152;
const MAX_PENDING = 32;
// What the bridge allows a request that does not wait: the host's own deadline for it and a margin, so the host answers or fails first.
const REQUEST_MILLIS = 35000;
// The longest wait a dispatch command may ask the host for (the host's DispatchWaits.MaxMillis).
const MAX_WAIT_MILLIS = 120000;
// A status refresh only repaints the footer: it is given up early and alone, without closing the connection.
const STATUS_MILLIS = 5000;
const PROTOCOL = "2025-03-26";
const DRIVER_FOOTER = "cq-driver";
const DRIVER_TOGGLE = "ctrl+alt+a";
const DRIVER_OFF = "CQ driver off";
const DRIVE_USAGE = "/cq:drive <target IDs> through=<phase> or /cq:drive workset=<id>";
const PREVIEW_LINES = 12;
const TITLE_CODE_POINTS = 80;
const LEDGER_PREFIX = { Milestones: "M", Ideas: "I", Defects: "D", Goals: "G", Tasks: "T", Researches: "RS", Hypothesis: "H", Questions: "Q",
  Decisions: "K", Reviews: "R", Handoffs: "HO", OperatorActions: "OA", Memories: "MEM", Upstream: "U" };
const FAILURE_STOPS = new Set(["Failure", "NotBound"]);
// `cq wait` exit code for a session whose host no longer runs.
const WAIT_HOST_GONE = 3;
const MAX_WAIT_OUTPUT_BYTES = 1048576;
// The phases in which the host works on a unit without the session, by the variant of the dispatch reply that reports them.
const WORKING = { Status: ["Preparing", "Running", "Stopping", "Validating", "Publishing"], Integration: ["Preparing", "Running"],
  Combination: ["Preparing"], Revalidation: ["Running"] };
const UNIT_KIND = { Status: "Attempt", Integration: "Integration", Combination: "Combination", Revalidation: "Revalidation" };
// A dispatch command that waits for work is allowed that wait on top of the deadline every other request keeps; Revalidate waits
// for its round as long as a status call may. A wait the host refuses is answered at once and gets no allowance.
function waitMillis(tool, parameters) {
  if (tool !== "dispatch" || parameters === null || typeof parameters !== "object") return 0;
  const commands = Object.entries(parameters);
  if (commands.length !== 1) return 0;
  const [command, body] = commands[0];
  if (command === "Revalidate") return MAX_WAIT_MILLIS;
  const wait = body === null || typeof body !== "object" ? undefined : body.waitMillis;
  return Number.isInteger(wait) && wait >= 0 && wait <= MAX_WAIT_MILLIS ? wait : 0;
}

function reference(id) {
  const prefix = LEDGER_PREFIX[id.ledger];
  if (prefix === undefined) throw new Error("Unknown CQ ledger " + id.ledger);
  return prefix + id.number;
}
function variant(value) {
  const entries = Object.entries(value);
  if (entries.length !== 1) throw new Error("Invalid CQ variant");
  return entries[0];
}
function reason(value) {
  const [name, body] = variant(value);
  switch (name) {
    case "Blocked": return "blocked by " + reference(body.prerequisite);
    case "Shared": return "shared with " + reference(body.producer);
    case "Context": return body.relation + " " + reference(body.source);
    default: return name.toLowerCase();
  }
}
// One line per unit `cq wait` reported: what ended and how.
function ended(end) {
  const items = end.unit.members.length === 0 ? "" : " on " + end.unit.members.map(reference).join(",");
  return `CQ: ${end.unit.kind.toLowerCase()} ${end.unit.id}${items} ended: ${end.phase}` + (end.next === null ? "" : `, next ${end.next}`) +
    (end.blocker === null ? "" : `, blocker: ${end.blocker.replace(/\s+/g, " ")}`);
}
// An item title is user text: it is shown on one line, without control characters and bounded in length.
function shown(title) {
  const points = Array.from(title.replace(/\p{Cc}/gu, " "));
  return points.length <= TITLE_CODE_POINTS ? points.join("") : points.slice(0, TITLE_CODE_POINTS - 1).join("") + "…";
}
// The host preview as three separate groups: what the driver may advance, what it only shows, and why each item is or is not ready.
function previewText(preview) {
  const reasons = values => values.length === 0 ? "" : " — " + values.map(reason).join(", ");
  const group = (title, lines) => [`${title} (${lines.length}):`, ...lines.slice(0, PREVIEW_LINES).map(line => "  " + line),
    ...(lines.length > PREVIEW_LINES ? [`  … ${lines.length - PREVIEW_LINES} more`] : [])];
  const summary = item => `${item.status}: ${shown(item.title)}`;
  return [
    ...group("Advanceable", preview.advanceable.map(member => `${reference(member.item.id)}${member.root ? " (target)" : ""} ${summary(member.item)}`)),
    ...group("Context only, never advanced", preview.context.map(entry => `${reference(entry.item.id)} ${summary(entry.item)}${reasons(entry.reasons)}`)),
    ...group("Readiness", preview.readiness.map(entry => `${reference(entry.item)}: ${entry.ready ? "ready" : "not ready"}${reasons(entry.reasons)}`)),
  ].join("\n");
}

class Connection {
  constructor(configuration) {
    this.sequence = 0;
    this.pending = new Map();
    this.abandoned = new Set();
    this.failed = undefined;
    this.buffer = Buffer.alloc(0);
    this.process = spawn(configuration.command, configuration.args, {
      cwd: configuration.directory, env: process.env, stdio: ["pipe", "pipe", "inherit"], detached: false,
    });
    this.exited = new Promise(resolve => this.process.once("close", resolve));
    this.process.once("error", error => this.fail(error));
    this.process.once("exit", (code, signal) => this.fail(new Error(`CQ host exited (${code ?? signal}); restart the session; retained deliveries can be recovered`)));
    this.process.stdin.on("error", error => this.fail(error));
    this.process.stdout.on("data", chunk => {
      try {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        let end;
        while ((end = this.buffer.indexOf(10)) !== -1) {
          if (end > MAX_FRAME_BYTES) throw new Error("CQ response exceeds its frame bound");
          const frame = this.buffer.subarray(0, end);
          this.buffer = this.buffer.subarray(end + 1);
          this.receive(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(frame)));
        }
        if (this.buffer.length > MAX_FRAME_BYTES) throw new Error("CQ response exceeds its frame bound");
      } catch (error) { this.fail(error); this.process.stdin.destroy(); }
    });
  }
  fail(error) {
    if (this.failed !== undefined) return;
    this.failed = error;
    for (const request of this.pending.values()) request.reject(error);
    this.pending.clear();
  }
  send(value) {
    if (this.failed !== undefined) throw this.failed;
    const frame = JSON.stringify(value) + "\n";
    if (Buffer.byteLength(frame) > MAX_FRAME_BYTES || this.process.stdin.writableLength > MAX_FRAME_BYTES)
      throw new Error("CQ request exceeds its frame/queue bound");
    this.process.stdin.write(frame);
  }
  receive(value) {
    if (value.jsonrpc !== "2.0") throw new Error("Invalid CQ JSON-RPC response");
    if (value.method === "ping" && typeof value.id === "string") {
      this.send({ jsonrpc: "2.0", id: value.id, result: {} });
      return;
    }
    if (this.abandoned.delete(value.id)) return;
    const request = this.pending.get(value.id);
    if (request === undefined) throw new Error("Uncorrelated CQ response");
    this.pending.delete(value.id);
    if (value.error !== undefined) request.reject(new Error("CQ rejected operation: " + value.error.message));
    else if (value.result === undefined) request.reject(new Error("CQ response result is missing"));
    else request.resolve(value.result);
  }
  // `millis` is the deadline of this request; exceeding it fails the connection.
  async rpc(method, params, signal, millis) {
    if (this.pending.size + this.abandoned.size >= MAX_PENDING) throw new Error("CQ request queue is full");
    const id = ++this.sequence;
    let timer;
    const abort = () => { this.fail(new Error("CQ operation interrupted; session closed to bound pending effects")); this.process.stdin.destroy(); };
    try {
      return await new Promise((resolve, reject) => {
        this.pending.set(id, { resolve, reject });
        timer = setTimeout(abort, millis);
        if (signal !== undefined) {
          signal.addEventListener("abort", abort, { once: true });
          if (signal.aborted) { abort(); return; }
        }
        this.send({ jsonrpc: "2.0", id, method, params });
      });
    } finally {
      clearTimeout(timer);
      if (signal !== undefined) signal.removeEventListener("abort", abort);
      this.pending.delete(id);
    }
  }
  // A request without effects that is given up after `millis`: its late reply is dropped and the connection stays open.
  // An unanswered request still counts against the pending bound, so a host that stops answering fills it and fails the connection there.
  async poll(method, params, millis) {
    if (this.failed !== undefined) throw this.failed;
    if (this.pending.size + this.abandoned.size >= MAX_PENDING) throw new Error("CQ request queue is full");
    const id = ++this.sequence;
    let timer;
    try {
      return await new Promise((resolve, reject) => {
        this.pending.set(id, { resolve, reject });
        timer = setTimeout(() => { this.abandoned.add(id); reject(new Error("CQ request timed out")); }, millis);
        this.send({ jsonrpc: "2.0", id, method, params });
      });
    } finally {
      clearTimeout(timer);
      this.pending.delete(id);
    }
  }
  async close() {
    this.process.stdin.end();
    let deadline;
    try {
      const drained = await Promise.race([this.exited.then(() => true), new Promise(resolve => { deadline = setTimeout(() => resolve(false), REQUEST_MILLIS); })]);
      if (!drained) {
        this.process.kill("SIGKILL");
        throw new Error("CQ host exceeded its shutdown deadline; retained deliveries require recovery");
      }
    } finally { clearTimeout(deadline); }
  }
}

export default async function (pi) {
  const file = new URL("./cq-host.json", import.meta.url);
  if ((await stat(file)).size > MAX_CONFIG_BYTES) throw new Error("CQ extension configuration exceeds its byte bound");
  const configuration = JSON.parse(await readFile(file, "utf8"));
  if (typeof configuration.command !== "string" || !Array.isArray(configuration.args) || !configuration.args.every(value => typeof value === "string") ||
      typeof configuration.directory !== "string" || !Array.isArray(configuration.tools) || configuration.tools.length !== 9)
    throw new Error("Invalid CQ attached configuration");
  let connection;
  let sequence = 0;
  let turn = 0;
  // Host-held driver state as the last control reply reported it, the drive input last accepted in this Pi session and how the last turn ended.
  let driving = false;
  let workset;
  let outcome = "completed";
  // The session directory of the attached host and the units the host works on, each with the `cq wait` child that waits for them.
  // The model starts no waiter: the extension tells the session when a unit ends.
  let directory;
  let waiter;
  // Whether the waiter was already started again after a failure. A waiter that fails twice is not started a third time: the driver then
  // asks for a resume directive, which keeps the session in its turn, instead of waiting for a message that would not come.
  let restarted = false;
  const working = new Map();
  function watch(context) {
    if (waiter !== undefined) { const stale = waiter; waiter = undefined; stale.kill("SIGTERM"); }
    if (working.size === 0 || connection === undefined) return;
    const named = [...working.values()].flatMap(unit => ["--" + unit.kind.toLowerCase(), unit.id]);
    const child = spawn(configuration.command, ["wait", "--session", directory, ...named, "--json"],
      { cwd: configuration.directory, env: process.env, stdio: ["ignore", "pipe", "inherit"], detached: false });
    waiter = child;
    const chunks = [];
    let size = 0;
    child.stdout.on("data", chunk => { size += chunk.length; if (size <= MAX_WAIT_OUTPUT_BYTES) chunks.push(chunk); });
    const failed = cause => {
      if (!restarted) { restarted = true; watch(context); return; }
      context.ui.notify("CQ waiter failed twice: " + cause + "; this session is not told when its running work ends. " +
        "Read its state with cq_dispatch Status; a drive continues with resume directives", "error");
      if (driving && context.isIdle()) void proceed(context);
    };
    child.once("error", error => { if (waiter === child) { waiter = undefined; failed("it could not start: " + error.message); } });
    child.once("close", code => {
      // A waiter that was replaced or stopped reports nothing: its successor names the same units.
      if (waiter !== child) return;
      waiter = undefined;
      try {
        if (size > MAX_WAIT_OUTPUT_BYTES) throw new Error("its output exceeds its byte bound");
        if (code !== 0 && code !== WAIT_HOST_GONE) throw new Error(`it exited with code ${code}`);
        const [name, body] = variant(JSON.parse(Buffer.concat(chunks).toString("utf8")));
        if (name === "HostGone") {
          working.clear();
          context.ui.notify("CQ host is not running: its children are not followed any more; restart the session; retained deliveries can be recovered", "error");
          // A drive must not stay on in silence: the continuation query fails on the lost host and says so.
          if (driving && context.isIdle()) void proceed(context);
        } else if (name === "Ended") {
          restarted = false;
          for (const end of body.units) working.delete(end.unit.kind + " " + end.unit.id);
          const lines = body.units.map(ended);
          if (working.size > 0) lines.push(`CQ still works on ${working.size} more; you are told when they end.`);
          lines.push("Read details with cq_dispatch Status (waitMillis 0) only if you need them.");
          // Reaches the model at its next tool-call boundary when it is busy, and starts a turn when it is idle.
          pi.sendMessage({ customType: "cq-wait", content: lines.join("\n"), display: true, details: {} }, { triggerTurn: true });
          watch(context);
        } else throw new Error("unexpected outcome " + name);
      } catch (error) { failed(error.message); }
    });
  }
  // A dispatch reply that shows the host working on a unit puts it under the waiter; one that the waiter already names changes nothing.
  function follow(context, text) {
    if (directory === undefined) return;
    const [name, body] = variant(JSON.parse(text));
    if (WORKING[name] === undefined || !WORKING[name].includes(body.value.phase)) return;
    const id = (name === "Status" ? body.value.attempt : body.value.id).value;
    const key = UNIT_KIND[name] + " " + id;
    if (working.has(key)) return;
    working.set(key, { kind: UNIT_KIND[name], id });
    watch(context);
  }
  const show = (context, status) => context.ui.setStatus(DRIVER_FOOTER, status === null ? DRIVER_OFF : status.line);
  // The session key is Pi's own session identifier; the attached host adds its attached session.
  async function driver(context, action, fields) {
    if (connection === undefined) throw new Error("CQ attached host is unavailable; restart the session");
    const request = { [action]: { session: context.sessionManager.getSessionId(), ...fields } };
    const reply = await (action === "Status" ? connection.poll("cq/driver", request, STATUS_MILLIS) : connection.rpc("cq/driver", request, undefined, REQUEST_MILLIS));
    const [name, body] = variant(reply);
    if (name !== "Failed") return reply;
    const [fault, detail] = variant(body.fault);
    throw new Error(typeof detail.message === "string" ? `${fault}: ${detail.message}` : JSON.stringify(body.fault));
  }
  function expect(reply, expected) {
    const [name, body] = variant(reply);
    if (name !== expected) throw new Error(`Unexpected CQ driver reply ${name}; expected ${expected}`);
    return body;
  }
  // The continuation decision is the host's: a directive is submitted unchanged, a stop is shown and nothing is sent.
  async function proceed(context) {
    let reply;
    // Waiting is accepted only while the waiter runs: it is what starts the next turn.
    try { reply = await driver(context, "Continue", { waiting: waiter !== undefined }); }
    catch (error) {
      driving = false;
      context.ui.setStatus(DRIVER_FOOTER, DRIVER_OFF + ": continuation query failed");
      context.ui.notify(`CQ driver stopped: continuation query failed: ${error.message}; run /cq:park to release the driver`, "error");
      return;
    }
    const [name, body] = variant(reply);
    if (name === "Continue") {
      show(context, body.status);
      for (const message of body.messages) context.ui.notify(message, "info");
      pi.sendUserMessage(body.directive.text, { deliverAs: "followUp", expandPromptTemplates: true });
    } else if (name === "Waiting") {
      // Work of the host is in flight and the waiter runs: nothing is sent, and its message starts the turn that continues the cycle.
      show(context, body.status);
    } else if (name === "Stop") {
      driving = false;
      show(context, body.status);
      const messages = body.messages.length === 0 ? ["CQ driver stopped: " + body.stopped.detail] : body.messages;
      for (const message of messages) context.ui.notify(message, FAILURE_STOPS.has(body.stopped.reason) ? "error" : "info");
    } else throw new Error("Unexpected CQ driver continuation reply " + name);
  }
  async function drive(input, context) {
    let started;
    try { started = expect(await driver(context, "Start", { input }), "Started"); }
    catch (error) { context.ui.notify("CQ driver not started: " + error.message, "error"); return; }
    driving = true;
    workset = input;
    show(context, started.status);
    context.ui.notify(started.message + "\n" + previewText(started.preview), "info");
    if (context.isIdle()) await proceed(context);
  }
  async function park(context) {
    let parked;
    try { parked = expect(await driver(context, "Park", {}), "Parked"); }
    catch (error) { context.ui.notify("CQ driver not parked: " + error.message, "error"); return; }
    driving = false;
    show(context, parked.status);
    context.ui.notify(parked.message, "info");
  }
  pi.registerCommand("cq:drive", { description: "Turn the CQ auto-driver on: " + DRIVE_USAGE, handler: drive });
  pi.registerCommand("cq:park", { description: "Turn the CQ auto-driver off", handler: (_arguments, context) => park(context) });
  pi.registerShortcut(DRIVER_TOGGLE, {
    description: "Toggle the CQ auto-driver",
    async handler(context) {
      if (driving) await park(context);
      else if (workset === undefined) context.ui.notify("CQ driver not started: this session has no workset yet; run " + DRIVE_USAGE, "warning");
      else await drive(workset, context);
    },
  });
  pi.on("session_start", async (_event, context) => {
    if (connection !== undefined) throw new Error("CQ connection is already active");
    sequence = 0;
    turn = 0;
    driving = false;
    workset = undefined;
    const started = new Connection(configuration);
    connection = started;
    try {
      const initialized = await started.rpc("initialize", { protocolVersion: PROTOCOL, capabilities: {}, clientInfo: { name: "cq-pi-attached", version: "0.1.0" } }, undefined, REQUEST_MILLIS);
      if (initialized.protocolVersion !== PROTOCOL) throw new Error("CQ protocol version mismatch");
      started.send({ jsonrpc: "2.0", method: "notifications/initialized" });
      const inventory = await started.rpc("tools/list", {}, undefined, REQUEST_MILLIS);
      if (!isDeepStrictEqual(inventory.tools, configuration.tools)) throw new Error("CQ tool contracts changed; rerun cq configure pi and restart");
      const located = await started.rpc("cq/session", {}, undefined);
      if (typeof located.directory !== "string") throw new Error("CQ host did not name its session directory");
      directory = located.directory;
      working.clear();
    } catch (error) {
      started.fail(error);
      await started.close();
      throw error;
    }
    context.ui.setStatus(DRIVER_FOOTER, DRIVER_OFF);
  });
  pi.on("session_shutdown", async (_event, context) => {
    const active = connection;
    if (active === undefined) return;
    // The attached session ends with this host, so a driver bound to it is parked first.
    try { if (driving) await driver(context, "Park", {}); }
    finally {
      driving = false;
      connection = undefined;
      directory = undefined;
      working.clear();
      watch(context);
      await active.close();
    }
  });
  pi.on("turn_start", () => { turn += 1; });
  pi.on("turn_end", async (event, context) => {
    outcome = event.outcome;
    if (!driving) return;
    // The footer is an indicator only: a refresh that fails is shown there and neither ends the turn's handling nor the drive.
    try { show(context, expect(await driver(context, "Status", {}), "Status").value); }
    catch (error) { context.ui.setStatus(DRIVER_FOOTER, "CQ driver status unavailable: " + error.message); }
  });
  pi.on("agent_settled", async (_event, context) => {
    if (!driving) return;
    if (outcome === "completed") await proceed(context);
    else {
      context.ui.notify(`CQ driver: the last turn ended ${outcome}, so the driver parks instead of continuing`, "warning");
      await park(context);
    }
  });
  pi.on("message_end", async (event, context) => {
    const message = event.message;
    if (message.role !== "assistant") return;
    if (connection === undefined) throw new Error("CQ cannot record native Pi usage: attached host is unavailable");
    const count = value => Number.isSafeInteger(value) && value >= 0 ? String(value) : null;
    const usage = message.usage;
    if (usage === undefined) throw new Error("CQ native Pi message has no usage record");
    const cost = usage.cost === undefined ? undefined : usage.cost.total;
    const record = {
      sequence: String(++sequence), session: context.sessionManager.getSessionId(), turn: String(turn),
      provider: message.provider, model: message.model, timestamp: String(message.timestamp),
      responseId: typeof message.responseId === "string" ? message.responseId : null, stopReason: message.stopReason,
      input: count(usage.input), output: count(usage.output), cacheRead: count(usage.cacheRead), cacheWrite: count(usage.cacheWrite),
      reasoning: count(usage.reasoning), totalTokens: count(usage.totalTokens),
      costUSD: typeof cost === "number" && Number.isFinite(cost) && cost >= 0 ? { value: String(cost) } : null,
    };
    try { await connection.rpc("cq/piUsage", record, undefined, REQUEST_MILLIS); }
    catch (error) { connection.fail(error); connection.process.stdin.destroy(); throw error; }
  });
  for (const tool of configuration.tools) {
    pi.registerTool({
      name: "cq_" + tool.name, label: "CQ " + tool.name, description: tool.description,
      parameters: tool.inputSchema, executionMode: "sequential",
      promptGuidelines: tool.name === "session" ? ["Before CQ work, call cq_session Context and follow its instructions; activate a typed workflow before dispatch. This interactive session is the Governor. Do not run cq run."] : [],
      async execute(_id, parameters, signal, _onUpdate, context) {
        if (connection === undefined) throw new Error("CQ attached host is unavailable; restart the session");
        const result = await connection.rpc("tools/call", { name: tool.name, arguments: parameters }, signal, REQUEST_MILLIS + waitMillis(tool.name, parameters));
        if (!Array.isArray(result.content) || result.content.length > 16 || !result.content.every(part => part.type === "text" && typeof part.text === "string"))
          throw new Error("CQ returned unsupported tool content");
        if (result.isError === true) throw new Error(result.content.map(part => part.text).join("\n").slice(0, 2000));
        if (tool.name === "dispatch") follow(context, result.content.map(part => part.text).join(""));
        return { content: result.content, details: {} };
      },
    });
  }
}
