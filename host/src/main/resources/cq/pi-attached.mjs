import { readFile, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { isDeepStrictEqual } from "node:util";

const MAX_CONFIG_BYTES = 2097152;
const MAX_FRAME_BYTES = 2097152;
const MAX_PENDING = 32;
const REQUEST_MILLIS = 35000;
const PROTOCOL = "2025-03-26";

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
  pi.on("session_start", async () => {
    if (connection !== undefined) throw new Error("CQ connection is already active");
    sequence = 0;
    turn = 0;
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
  });
  pi.on("session_shutdown", async () => {
    const active = connection;
    connection = undefined;
    if (active !== undefined) await active.close();
  });
  pi.on("turn_start", () => { turn += 1; });
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
