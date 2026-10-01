import { readFile, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { isDeepStrictEqual } from "node:util";

const MAX_CONFIG_BYTES = 2097152;
const MAX_FRAME_BYTES = 2097152;
const MAX_PENDING = 32;
const REQUEST_MILLIS = 35000;
const PROTOCOL = "2025-03-26";
const DRIVER_FOOTER = "cq-driver";
const DRIVER_TOGGLE = "ctrl+alt+a";
const DRIVER_OFF = "CQ driver off";
const DRIVE_USAGE = "/cq:drive <target IDs> through=<phase> or /cq:drive workset=<id>";
const PREVIEW_LINES = 12;
const LEDGER_PREFIX = { Milestones: "M", Ideas: "I", Defects: "D", Goals: "G", Tasks: "T", Researches: "RS", Hypothesis: "H", Questions: "Q",
  Decisions: "K", Reviews: "R", Handoffs: "HO", OperatorActions: "OA", Memories: "MEM", Upstream: "U" };
const FAILURE_STOPS = new Set(["Failure", "NotBound"]);

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
// The host preview as three separate groups: what the driver may advance, what it only shows, and why each item is or is not ready.
function previewText(preview) {
  const reasons = values => values.length === 0 ? "" : " — " + values.map(reason).join(", ");
  const group = (title, lines) => [`${title} (${lines.length}):`, ...lines.slice(0, PREVIEW_LINES).map(line => "  " + line),
    ...(lines.length > PREVIEW_LINES ? [`  … ${lines.length - PREVIEW_LINES} more`] : [])];
  const summary = item => `${item.status}: ${item.title}`;
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
    const request = this.pending.get(value.id);
    if (request === undefined) throw new Error("Uncorrelated CQ response");
    this.pending.delete(value.id);
    if (value.error !== undefined) request.reject(new Error("CQ rejected operation: " + value.error.message));
    else if (value.result === undefined) request.reject(new Error("CQ response result is missing"));
    else request.resolve(value.result);
  }
  async rpc(method, params, signal) {
    if (this.pending.size >= MAX_PENDING) throw new Error("CQ request queue is full");
    const id = ++this.sequence;
    let timer;
    const abort = () => { this.fail(new Error("CQ operation interrupted; session closed to bound pending effects")); this.process.stdin.destroy(); };
    try {
      return await new Promise((resolve, reject) => {
        this.pending.set(id, { resolve, reject });
        timer = setTimeout(abort, REQUEST_MILLIS);
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
  const show = (context, status) => context.ui.setStatus(DRIVER_FOOTER, status === null ? DRIVER_OFF : status.line);
  // The session key is Pi's own session identifier; the attached host adds its attached session.
  async function driver(context, action, fields) {
    if (connection === undefined) throw new Error("CQ attached host is unavailable; restart the session");
    const reply = await connection.rpc("cq/driver", { [action]: { session: context.sessionManager.getSessionId(), ...fields } }, undefined);
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
    try { reply = await driver(context, "Continue", {}); }
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
      const initialized = await started.rpc("initialize", { protocolVersion: PROTOCOL, capabilities: {}, clientInfo: { name: "cq-pi-attached", version: "0.1.0" } }, undefined);
      if (initialized.protocolVersion !== PROTOCOL) throw new Error("CQ protocol version mismatch");
      started.send({ jsonrpc: "2.0", method: "notifications/initialized" });
      const inventory = await started.rpc("tools/list", {}, undefined);
      if (!isDeepStrictEqual(inventory.tools, configuration.tools)) throw new Error("CQ tool contracts changed; rerun cq configure pi and restart");
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
      await active.close();
    }
  });
  pi.on("turn_start", () => { turn += 1; });
  pi.on("turn_end", async (event, context) => {
    outcome = event.outcome;
    if (driving) show(context, expect(await driver(context, "Status", {}), "Status").value);
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
    try { await connection.rpc("cq/piUsage", record, undefined); }
    catch (error) { connection.fail(error); connection.process.stdin.destroy(); throw error; }
  });
  for (const tool of configuration.tools) {
    pi.registerTool({
      name: "cq_" + tool.name, label: "CQ " + tool.name, description: tool.description,
      parameters: tool.inputSchema, executionMode: "sequential",
      promptGuidelines: tool.name === "session" ? ["Before CQ work, call cq_session Context and follow its instructions; activate a typed workflow before dispatch. This interactive session is the Governor. Do not run cq run."] : [],
      async execute(_id, parameters, signal) {
        if (connection === undefined) throw new Error("CQ attached host is unavailable; restart the session");
        const result = await connection.rpc("tools/call", { name: tool.name, arguments: parameters }, signal);
        if (!Array.isArray(result.content) || result.content.length > 16 || !result.content.every(part => part.type === "text" && typeof part.text === "string"))
          throw new Error("CQ returned unsupported tool content");
        if (result.isError === true) throw new Error(result.content.map(part => part.text).join("\n").slice(0, 2000));
        return { content: result.content, details: {} };
      },
    });
  }
}
