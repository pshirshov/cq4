import { open } from "node:fs/promises";

const MAX_CONFIG_BYTES = 262144;
const MAX_MESSAGE_BYTES = 2097152;
const MAX_TOOLS = 10;
const REQUEST_MILLIS = 30000;
// The longest wait a dispatch command may ask the host for (the host's DispatchWaits.MaxMillis).
const MAX_WAIT_MILLIS = 120000;
const PROTOCOL = "2025-03-26";

// A dispatch command that waits for work is allowed that wait on top of the deadline every other request keeps; Revalidate waits
// for its round as long as a status call may. A wait the host refuses is answered at once and gets no allowance.
function waitMillis(endpoint, tool, parameters) {
  if (endpoint !== "cq_host" || tool !== "dispatch" || parameters === null || typeof parameters !== "object") return 0;
  const commands = Object.entries(parameters);
  if (commands.length !== 1) return 0;
  const [command, body] = commands[0];
  if (command === "Revalidate") return MAX_WAIT_MILLIS;
  const wait = body === null || typeof body !== "object" ? undefined : body.waitMillis;
  return Number.isInteger(wait) && wait >= 0 && wait <= MAX_WAIT_MILLIS ? wait : 0;
}

export default async function (pi) {
  const file = await open(new URL("./pi-mcp.json", import.meta.url), "r");
  let configuration;
  try {
    const bytes = Buffer.alloc(MAX_CONFIG_BYTES + 1);
    let length = 0;
    while (length < bytes.length) {
      const part = await file.read(bytes, length, bytes.length - length, null);
      if (part.bytesRead === 0) break;
      length += part.bytesRead;
    }
    if (length > MAX_CONFIG_BYTES) throw new Error("CQ Pi configuration exceeds its byte bound");
    configuration = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(0, length)));
  } finally {
    await file.close();
  }
  if (!Array.isArray(configuration.endpoints) || configuration.endpoints.length > 2)
    throw new Error("Invalid CQ Pi endpoint inventory");
  const names = new Set();
  let sequence = 0;

  async function rpc(endpoint, method, params, signal, millis) {
    const id = ++sequence;
    const body = JSON.stringify({ jsonrpc: "2.0", id, method, params });
    if (Buffer.byteLength(body, "utf8") > MAX_MESSAGE_BYTES) throw new Error("CQ request exceeds its byte bound");
    const timeout = AbortSignal.timeout(millis);
    const bounded = signal === undefined ? timeout : AbortSignal.any([timeout, signal]);
    const response = await fetch(endpoint.url, {
      method: "POST", redirect: "error", signal: bounded,
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream",
        Authorization: "Bearer " + endpoint.token, "MCP-Protocol-Version": PROTOCOL },
      body,
    });
    if (!response.ok) {
      if (response.body !== null) await response.body.cancel();
      throw new Error("CQ HTTP request failed with status " + response.status);
    }
    if (response.body === null) throw new Error("CQ response body is missing");
    const reader = response.body.getReader();
    const parts = [];
    let length = 0;
    try {
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        length += part.value.byteLength;
        if (length > MAX_MESSAGE_BYTES) {
          await reader.cancel();
          throw new Error("CQ response exceeds its byte bound");
        }
        parts.push(part.value);
      }
    } finally {
      reader.releaseLock();
    }
    const value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(parts, length)));
    if (value.jsonrpc !== "2.0" || value.id !== id) throw new Error("CQ response identity mismatch");
    if (value.error !== undefined) throw new Error("CQ rejected the MCP operation");
    if (value.result === undefined) throw new Error("CQ response result is missing");
    return value.result;
  }

  async function initialized(endpoint) {
    const response = await fetch(endpoint.url, {
      method: "POST", redirect: "error", signal: AbortSignal.timeout(REQUEST_MILLIS),
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream",
        Authorization: "Bearer " + endpoint.token, "MCP-Protocol-Version": PROTOCOL },
      body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
    });
    if (response.body !== null) await response.body.cancel();
    if (response.status !== 202) throw new Error("CQ did not acknowledge MCP initialization");
  }

  for (const endpoint of configuration.endpoints) {
    if (!/^(cq|cq_host)$/.test(endpoint.name) || names.has(endpoint.name) ||
        typeof endpoint.token !== "string" || endpoint.token.length === 0 || endpoint.token.length > 8192 ||
        !Array.isArray(endpoint.tools) || endpoint.tools.length > MAX_TOOLS || new Set(endpoint.tools).size !== endpoint.tools.length ||
        !endpoint.tools.every(name => /^[a-z][a-z_]{0,30}$/.test(name)))
      throw new Error("Invalid scoped CQ Pi connection");
    const url = new URL(endpoint.url);
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash)
      throw new Error("Invalid CQ Pi endpoint URL");
    names.add(endpoint.name);
    const handshake = await rpc(endpoint, "initialize", {
      protocolVersion: PROTOCOL, capabilities: {}, clientInfo: { name: "cq-pi", version: "0.1.0" },
    }, undefined, REQUEST_MILLIS);
    if (handshake.protocolVersion !== PROTOCOL) throw new Error("CQ Pi MCP protocol mismatch");
    await initialized(endpoint);
    const listed = await rpc(endpoint, "tools/list", {}, undefined, REQUEST_MILLIS);
    if (!Array.isArray(listed.tools) || listed.nextCursor !== undefined || listed.tools.length > MAX_TOOLS)
      throw new Error("CQ Pi requires a complete bounded tool inventory");
    for (const name of endpoint.tools) {
      const matches = listed.tools.filter(tool => tool.name === name);
      if (matches.length !== 1) throw new Error("Required CQ tool is unavailable or ambiguous: " + name);
      const tool = matches[0];
      if (typeof tool.description !== "string" || tool.inputSchema === null || typeof tool.inputSchema !== "object")
        throw new Error("Invalid CQ tool schema");
      pi.registerTool({
        name: endpoint.name + "_" + name, label: "CQ " + name, description: tool.description,
        parameters: tool.inputSchema,
        async execute(_callId, parameters, signal, _onUpdate, _context) {
          const result = await rpc(endpoint, "tools/call", { name, arguments: parameters }, signal, REQUEST_MILLIS + waitMillis(endpoint.name, name, parameters));
          if (!Array.isArray(result.content) || result.content.length > 16 ||
              !result.content.every(part => part.type === "text" && typeof part.text === "string"))
            throw new Error("CQ returned unsupported tool content");
          if (result.isError === true) throw new Error(result.content.map(part => part.text).join("\n").slice(0, 2000));
          return { content: result.content, details: {} };
        },
      });
    }
  }
}
