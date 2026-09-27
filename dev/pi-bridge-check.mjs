import assert from "node:assert/strict";
import { createServer } from "node:http";
import { copyFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { test } from "node:test";

test("Pi bridge (Behavioral Active Blackbox; local HTTP Communication)", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cq-pi-bridge-"));
  const registered = new Map();
  let initialized = false;
  let reply = "normal";
  let toolCalls = 0;
  let redirects = 0;
  const server = createServer(async (request, response) => {
    if (request.url === "/redirected") { redirects++; response.writeHead(500).end(); return; }
    assert.equal(request.headers.authorization, "Bearer scoped-fixture");
    assert.equal(request.headers["mcp-protocol-version"], "2025-03-26");
    let body = "";
    for await (const part of request) body += part;
    const call = JSON.parse(body);
    if (call.method === "notifications/initialized") {
      assert.equal(call.id, undefined);
      initialized = true;
      response.writeHead(202).end();
      return;
    }
    const send = result => response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ jsonrpc: "2.0", id: call.id, result }));
    if (call.method === "initialize") { send({ protocolVersion: "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "fixture", version: "1" } }); return; }
    if (!initialized) {
      response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ jsonrpc: "2.0", id: call.id, error: { code: -32000, message: "Client did not finish initialization" } }));
      return;
    }
    if (call.method === "tools/list") {
      send({ tools: ["read", "change"].map(name => ({ name, description: "Fixture " + name, inputSchema: { type: "object", properties: {} } })) });
      return;
    }
    assert.equal(call.method, "tools/call");
    assert.equal(call.params.name, "read");
    toolCalls++;
    if (reply === "redirect") { response.writeHead(302, { Location: "/redirected" }).end(); return; }
    if (reply === "oversize") { send({ content: [{ type: "text", text: "x".repeat(2097153) }] }); return; }
    if (reply === "denied") { send({ isError: true, content: [{ type: "text", text: "Permission denied" }] }); return; }
    send({ isError: false, content: [{ type: "text", text: "bounded result λ" }] });
  });
  try {
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    assert.notEqual(address, null);
    const asset = join(directory, "pi-bridge.mjs");
    await copyFile(new URL("../host/src/main/resources/cq/pi-bridge.mjs", import.meta.url), asset);
    await writeFile(join(directory, "pi-mcp.json"), JSON.stringify({ endpoints: [{ name: "cq", url: `http://127.0.0.1:${address.port}/mcp`, token: "scoped-fixture", tools: ["read"] }] }));
    const bridge = (await import(pathToFileURL(asset))).default;
    await bridge({ registerTool(tool) { assert(!registered.has(tool.name)); registered.set(tool.name, tool); } });
    assert.deepEqual([...registered.keys()], ["cq_read"]);
    const read = registered.get("cq_read");
    const invoke = () => read.execute("call", {}, undefined, undefined, {});
    assert.deepEqual((await invoke()).content, [{ type: "text", text: "bounded result λ" }]);
    reply = "denied";
    await assert.rejects(invoke, /Permission denied/);
    reply = "oversize";
    await assert.rejects(invoke, /byte bound/);
    reply = "redirect";
    await assert.rejects(invoke);
    assert.equal(redirects, 0);
    assert.equal(toolCalls, 4);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});

test("Pi governor registers the complete domain inventory and local dispatch", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cq-pi-governor-"));
  const domain = ["search", "read", "graph", "change", "apply", "claim", "usage"];
  const local = ["dispatch"];
  const server = createServer(async (request, response) => {
    let body = "";
    for await (const part of request) body += part;
    const call = JSON.parse(body);
    const tools = request.url === "/domain" ? domain : local;
    assert.equal(request.headers.authorization, "Bearer fixture-" + request.url.slice(1));
    if (call.method === "notifications/initialized") { response.writeHead(202).end(); return; }
    const result = call.method === "initialize" ? { protocolVersion: "2025-03-26" } :
      { tools: tools.map(name => ({ name, description: "Fixture " + name, inputSchema: { type: "object", properties: {} } })) };
    response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ jsonrpc: "2.0", id: call.id, result }));
  });
  try {
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    assert.notEqual(address, null);
    const asset = join(directory, "pi-bridge.mjs");
    await copyFile(new URL("../host/src/main/resources/cq/pi-bridge.mjs", import.meta.url), asset);
    const endpoints = [
      { name: "cq", url: `http://127.0.0.1:${address.port}/domain`, token: "fixture-domain", tools: domain },
      { name: "cq_host", url: `http://127.0.0.1:${address.port}/local`, token: "fixture-local", tools: local },
    ];
    const configuration = join(directory, "pi-mcp.json");
    await writeFile(configuration, JSON.stringify({ endpoints }));
    const bridge = (await import(pathToFileURL(asset))).default;
    const registered = [];
    await bridge({ registerTool(tool) { registered.push(tool.name); } });
    assert.deepEqual(registered, [...domain.map(name => "cq_" + name), "cq_host_dispatch"]);
    for (const invalid of [[...domain, "read"], Array.from({ length: 11 }, (_, index) => "tool_" + String.fromCharCode(97 + index))]) {
      await writeFile(configuration, JSON.stringify({ endpoints: [{ ...endpoints[0], tools: invalid }] }));
      await assert.rejects(() => bridge({ registerTool() { assert.fail("Invalid inventory registered a tool"); } }), /Invalid scoped CQ Pi connection/);
    }
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
