import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { WebSocketServer } from 'ws';
import { writeFile } from 'node:fs/promises';

// Behavioral / Effectual / Good Communication: real Chromium sockets, controlled local peer and browser clock.
const output = await build({ stdin: { contents: `
  import { ConnectionManager } from './web/src/connection.ts';
  const observations = { status: null, updates: 0, activations: 0, events: 0 };
  const manager = new ConnectionManager('ws://' + location.host + '/ws', {
    status: value => { observations.status = value; observations.updates++; },
    active: () => observations.activations++, event: () => observations.events++, disconnected: () => {},
  });
  window.probe = { observations, destroy: () => manager.destroy(), retry: () => manager.retry() };
`, resolveDir: process.cwd() }, bundle: true, write: false });
const code = output.outputFiles[0].text;
let holdHandshake = true; let silentPeer = null; const upgrades = []; const peers = []; const errors = []; const evidence = [];
const server = createServer((request, response) => {
  response.setHeader('Content-Type', request.url === '/probe.js' ? 'text/javascript' : 'text/html');
  response.end(request.url === '/probe.js' ? code : '<!doctype html><title>Connection fixture</title><script src="/probe.js"></script>');
});
const ws = new WebSocketServer({ noServer: true });
server.on('upgrade', (request, socket, head) => {
  upgrades.push(socket); socket.on('error', () => {});
  if (!holdHandshake) ws.handleUpgrade(request, socket, head, peer => {
    peers.push(peer);
    peer.on('message', data => { const frame = JSON.parse(String(data)); if (frame.Ping && peer !== silentPeer) peer.send(JSON.stringify({ Pong: frame.Ping })); });
  });
});
server.listen(0, '127.0.0.1'); await once(server, 'listening');
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext(); await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
const page = await context.newPage(); page.on('pageerror', error => errors.push(String(error)));
await page.clock.install();
const stats = () => page.evaluate(() => window.probe.observations.status);
try {
  await page.goto(origin); await page.waitForFunction(() => window.probe.observations.status.state === 'NEW');
  await page.clock.runFor(10000);
  const timeout = await stats();
  assert.equal(timeout.connections, 0); assert.equal(timeout.phase.kind, 'Backoff');
  assert.ok(timeout.events.some(event => event.includes('Connect timeout')));
  assert.equal(upgrades.length, 1);
  evidence.push({ case: 'actual native CONNECTING blackhole expires and schedules backoff', stats: timeout });
  holdHandshake = false;
  await page.clock.runFor(1000); await page.waitForFunction(() => window.probe.observations.status.state === 'ALIVE');
  assert.equal(upgrades.length, 2);
  const active = await stats(); assert.equal(active.attempts, 0); assert.equal(active.pool[0].rtt[0].count, 1);
  await page.clock.fastForward(2000);
  await page.waitForFunction(() => window.probe.observations.status.pool[0].rtt[0].count === 2);
  assert.equal(upgrades.length, 2, 'A short scheduling pause checks the actual existing socket');
  silentPeer = peers[0];
  await page.clock.fastForward(6000);
  await page.waitForFunction(() => window.probe.observations.status.active === 3);
  assert.equal(upgrades.length, 3);
  assert.equal((await stats()).connections, 1);
  evidence.push({ case: 'actual short/long event-loop pause probes/replaces and promotes one native socket', stats: await stats() });
  await page.evaluate(() => window.probe.destroy());
  const afterDestroy = await page.evaluate(() => window.probe.observations);
  assert.equal(afterDestroy.status.connections, 0);
  await page.evaluate(() => {
    document.dispatchEvent(new Event('resume')); window.dispatchEvent(new Event('online'));
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })); window.probe.retry();
  });
  await page.clock.runFor(60000);
  assert.deepEqual(await page.evaluate(() => window.probe.observations), afterDestroy);
  assert.equal(upgrades.length, 3); assert.deepEqual(errors, []);
  evidence.push({ case: 'native teardown ignores late socket callbacks and lifecycle/retry signals', observations: afterDestroy });
  console.log(JSON.stringify(evidence, null, 2));
} finally {
  if (process.env.CQ_BROWSER_EVIDENCE) {
    await writeFile(`${process.env.CQ_BROWSER_EVIDENCE}/connection-native.json`, JSON.stringify({ evidence, errors }, null, 2));
    await context.tracing.stop({ path: `${process.env.CQ_BROWSER_EVIDENCE}/connection-native-trace.zip` });
  }
  await browser.close();
  for (const peer of peers) peer.terminate();
  for (const socket of upgrades) socket.destroy();
  ws.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}
