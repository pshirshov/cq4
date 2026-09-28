import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { runInNewContext } from 'node:vm';
import { randomUUID } from 'node:crypto';

// Behavioral / Effectual / Atomic: controlled browser boundaries, public manager API and listener only.
const bundle = await build({ entryPoints: ['web/src/connection.ts'], bundle: true, format: 'iife', globalName: 'CQ', write: false });
function fixture() {
  let now = 100000; let status; let updates = 0; let activations = 0;
  const timers = new Set(); const sockets = [];
  const document = Object.assign(new EventTarget(), { visibilityState: 'visible' });
  const window = new EventTarget(); const network = new EventTarget();
  class Socket extends EventTarget {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 3;
    readyState = 0; sent = []; closes = [];
    constructor() { super(); sockets.push(this); }
    send(data) { assert.equal(this.readyState, Socket.OPEN); this.sent.push(JSON.parse(data)); }
    open() { this.readyState = Socket.OPEN; this.dispatchEvent(new Event('open')); }
    frame(value) { this.dispatchEvent(Object.assign(new Event('message'), { data: JSON.stringify(value) })); }
    pong() { this.frame({ Pong: this.sent.filter(frame => frame.Ping).at(-1).Ping }); }
    close(code, reason) { this.closes.push({ code, reason }); this.readyState = Socket.CLOSED; }
    remoteClose(code) { this.readyState = Socket.CLOSED; this.dispatchEvent(Object.assign(new Event('close'), { code, reason: 'fixture' })); }
  }
  const scope = { document, window, navigator: { connection: network }, WebSocket: Socket, EventTarget, AbortController, TextEncoder, TextDecoder,
    Date: class extends Date { constructor(...args) { super(...(args.length ? args : [now])); } static now() { return now; } },
    crypto: { randomUUID }, setInterval: callback => { timers.add(callback); return callback; }, clearInterval: callback => timers.delete(callback),
    Math: Object.assign(Object.create(Math), { random: () => 0.5 }) };
  runInNewContext(bundle.outputFiles[0].text, scope);
  const manager = new scope.CQ.ConnectionManager('ws://fixture', { status: value => { status = value; updates++; },
    active: () => activations++, event: () => {}, disconnected: () => {} });
  const jump = ms => { now += ms; for (const callback of timers) callback(); };
  return { manager, sockets, timers, document, window, network, jump, advance: ms => { for (let i = 0; i < ms; i += 250) jump(Math.min(250, ms - i)); },
    stats: () => status, updates: () => updates, activations: () => activations, now: () => now,
    visible: state => { document.visibilityState = state; document.dispatchEvent(new Event('visibilitychange')); } };
}
const cases = [];
async function check(name, body) {
  const f = fixture();
  try { await body(f); cases.push({ name, passed: true }); }
  catch (error) { cases.push({ name, passed: false, error: String(error) }); }
  finally { f.manager.destroy(); }
}
await check('native OPEN alone is not liveness; first overdue probe is STALE', f => {
  f.sockets[0].open(); assert.equal(f.stats().state, 'NEW');
  f.advance(5000); assert.equal(f.stats().state, 'STALE'); assert.equal(f.sockets.length, 2);
  f.sockets[0].pong(); assert.equal(f.stats().state, 'ALIVE'); assert.equal(f.sockets[1].readyState, 3);
});
await check('hidden replacement resumes immediately with an existing stale active connection', f => {
  f.sockets[0].open(); f.sockets[0].pong(); f.visible('hidden'); f.advance(15000);
  assert.equal(f.stats().state, 'DEFERRED'); const attempts = f.stats().attempts;
  f.advance(1000); assert.equal(f.sockets.length, 1); assert.equal(f.stats().attempts, attempts);
  f.visible('visible'); assert.equal(f.sockets.length, 2, 'Deferred replacement must start on visibility, before stale grace expires');
});
await check('native CONNECTING timeout does not close a socket that is already OPEN', f => {
  f.sockets[0].readyState = 1; f.advance(10000); assert.equal(f.sockets[0].closes.length, 0);
  f.sockets[0].open(); f.sockets[0].pong(); assert.equal(f.stats().state, 'ALIVE');
});
await check('handshake blackhole is closed and retry is bounded', f => {
  f.advance(10000); assert.equal(f.sockets[0].readyState, 3); assert.equal(f.stats().connections, 0);
  assert.ok(f.stats().nextAttempt > f.now());
});
await check('retry ceiling, manual retry and permanent close are terminal', f => {
  for (let i = 0; i < 12; i++) {
    f.sockets.at(-1).remoteClose(1006);
    const delay = f.stats().nextAttempt - f.now();
    assert.ok(delay >= Math.min(30000, 500 * 2 ** (i + 1)) / 2 && delay <= Math.min(30000, 500 * 2 ** (i + 1)));
    f.advance(Math.ceil(delay / 250) * 250);
  }
  assert.equal(f.sockets.length, 12); assert.equal(f.stats().state, 'STOPPED');
  f.advance(60000); assert.equal(f.sockets.length, 12);
  f.manager.retry(); assert.equal(f.sockets.length, 13); f.sockets.at(-1).open(); f.sockets.at(-1).pong();
  assert.equal(f.stats().attempts, 0); f.sockets.at(-1).remoteClose(1007);
  f.advance(60000); assert.equal(f.stats().state, 'STOPPED'); assert.equal(f.sockets.length, 13);
});
await check('pagehide suspends all work; pageshow resumes one attempt', f => {
  f.sockets[0].open(); f.sockets[0].pong(); f.window.dispatchEvent(new Event('pagehide'));
  assert.equal(f.stats().connections, 0); f.advance(60000); assert.equal(f.sockets.length, 1);
  f.window.dispatchEvent(new Event('pageshow')); f.advance(250); assert.equal(f.sockets.length, 2);
});
await check('resume and network signals respect pool cap and destruction', f => {
  f.sockets[0].open(); f.sockets[0].pong();
  f.document.dispatchEvent(new Event('freeze')); assert.equal(f.sockets.length, 1);
  f.document.dispatchEvent(new Event('resume')); f.network.dispatchEvent(new Event('change')); f.window.dispatchEvent(new Event('online'));
  assert.equal(f.sockets.length, 2); f.manager.destroy(); const updates = f.updates();
  f.sockets[1].open(); f.sockets[0].pong(); f.sockets[1].remoteClose(1006);
  f.document.dispatchEvent(new Event('resume')); f.window.dispatchEvent(new Event('pageshow')); f.manager.retry(); f.jump(60000);
  assert.equal(f.sockets.length, 2); assert.equal(f.updates(), updates); assert.equal(f.timers.size, 0);
});
await check('short scheduling gap probes and long gap proactively replaces', f => {
  f.sockets[0].open(); f.sockets[0].pong(); const before = f.sockets[0].sent.length;
  f.jump(2000); assert.ok(f.sockets[0].sent.length > before, 'A short gap rechecks liveness'); assert.equal(f.sockets.length, 1);
  f.jump(6000); assert.equal(f.sockets.length, 2);
});
await check('every waiting phase has its actual budget; duplicate/unsolicited pongs do not affect statistics', f => {
  assert.equal(f.stats().phase.kind, 'Connect'); assert.equal(f.stats().phase.duration, 10000);
  f.advance(2500); assert.equal((f.stats().phase.started + f.stats().phase.duration - f.now()) / f.stats().phase.duration, 0.75);
  f.sockets[0].open(); f.advance(500); f.sockets[0].pong();
  assert.equal(f.stats().pool[0].rtt[0].count, 1); assert.equal(f.stats().pool[0].rtt[0].median, 500);
  f.sockets[0].pong(); f.sockets[0].frame({ Pong: { nonce: 'unknown' } }); assert.equal(f.stats().pool[0].rtt[0].count, 1);
  f.advance(9500); assert.equal(f.stats().phase.kind, 'Pong'); assert.equal(f.stats().phase.duration, 5000);
  f.advance(5000); assert.equal(f.stats().phase.kind, 'Grace'); assert.equal(f.stats().phase.duration, 15000);
  assert.equal(f.stats().pool[0].missed, 1); f.advance(250); assert.equal(f.stats().pool[0].missed, 1);
  f.sockets[0].pong(); assert.equal(f.stats().pool[0].missed, 1);
  f.sockets[0].remoteClose(1006); assert.equal(f.stats().phase.kind, 'Backoff');
  assert.equal(f.stats().phase.started + f.stats().phase.duration, f.stats().nextAttempt);
  assert.equal(f.stats().lastClose.code, 1006);
});
await check('RTT windows age out samples and retained diagnostics are bounded', f => {
  f.sockets[0].open(); f.advance(100); f.sockets[0].pong();
  f.visible('visible'); f.advance(300); f.sockets[0].pong();
  const measured = f.stats().pool[0].rtt[0];
  assert.equal(measured.min, 100); assert.equal(measured.median, 200); assert.equal(measured.max, 300); assert.equal(measured.count, 2);
  f.jump(31000);
  assert.deepEqual(Array.from(f.stats().pool[0].rtt, window => window.count), [0, 2, 2]);
  f.sockets[0].pong(); f.jump(31000);
  assert.equal(f.stats().pool[0].rtt[1].count, 1); assert.equal(f.stats().pool[0].rtt[2].count, 3);
  f.sockets[0].pong();
  for (let i = 0; i < 520; i++) { f.visible('visible'); f.sockets[0].pong(); }
  assert.equal(f.stats().pool[0].rtt[0].count, 512); assert.equal(f.stats().events.length, 100);
});
console.log(JSON.stringify(cases, null, 2));
assert.ok(cases.every(result => result.passed), `${cases.filter(result => !result.passed).length} connection cases failed`);
