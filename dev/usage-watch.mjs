import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import WebSocket from 'ws';

const origin = process.env.CQ_ORIGIN; const evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': process.env.CQ_SESSION,
  'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json', Origin: origin };
const id = () => ({ value: randomUUID() }); const first = id(); const second = id(); const cases = [];
async function post(path, body) {
  const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
  assert.equal(response.status, 200, await response.clone().text());
  const result = await response.json(); assert.equal(result.Failed, undefined); return result;
}
for (const project of [first, second]) await post('/api/call', { Initialize: { config: { project, endpoint: origin, name: `Watch ${project.value}` } } });
const assignment = project => ({ id: id(), project, members: [], attribution: 'Unattributed', cohort: null, evaluation: null });
const host = (project, value) => post('/api/usage', { project, operation: { Assign: { value } } });
const database = new URL(process.env.CQ_DATABASE_URL.replace(/^jdbc:/, ''));
function sql(command) {
  return execFileSync('psql', ['--no-psqlrc', '--set', 'ON_ERROR_STOP=1', '--quiet', '--tuples-only', '--no-align',
    '--host', database.hostname, '--port', database.port, '--dbname', database.pathname.slice(1), '--username', process.env.CQ_DATABASE_USER,
    '--command', command], { encoding: 'utf8', timeout: 10000, env: { ...process.env, PGPASSWORD: process.env.CQ_DATABASE_PASSWORD } }).trim();
}
sql('CREATE EXTENSION IF NOT EXISTS pg_stat_statements');
async function connect(credentials) {
  const socket = new WebSocket(origin.replace(/^http/, 'ws') + '/ws', { headers: credentials });
  const frames = []; const waiters = new Set();
  socket.on('message', data => {
    const frame = JSON.parse(String(data)); frames.push(frame);
    if (frame.Ping) socket.send(JSON.stringify({ Pong: { nonce: frame.Ping.nonce } }));
    for (const waiter of [...waiters]) if (waiter.predicate(frame)) { clearTimeout(waiter.timer); waiters.delete(waiter); waiter.resolve(frame); }
  });
  await once(socket, 'open');
  return { socket, frames,
    wait(predicate) {
      const prior = frames.find(predicate); if (prior) return Promise.resolve(prior);
      return new Promise((resolve, reject) => {
        const waiter = { predicate, resolve, timer: setTimeout(() => { waiters.delete(waiter); reject(new Error('Usage watch response timeout')); }, 5000) };
        waiters.add(waiter);
      });
    },
    async close() { const closed = once(socket, 'close'); socket.close(1000, 'Fixture complete'); await closed; assert.equal(waiters.size, 0); },
  };
}
const root = await connect(headers); const recordings = [root.frames]; const sockets = [root];
let queries = [];
try {
  const watch = id(); root.socket.send(JSON.stringify({ WatchUsage: { id: watch, project: first } }));
  const initial = await root.wait(frame => frame.UsageCursor && frame.UsageCursor.subscription.value === watch.value);
  assert.equal(initial.UsageCursor.cursor, '0');
  const value = assignment(first); await host(first, value);
  const changed = await root.wait(frame => frame.UsageCursor && frame.UsageCursor.subscription.value === watch.value && frame.UsageCursor.cursor !== '0');
  sql('SELECT pg_stat_statements_reset()');
  await host(first, value);
  sql('SELECT pg_stat_statements_reset()');
  const stableCount = root.frames.filter(frame => frame.UsageCursor).length;
  await new Promise(resolve => setTimeout(resolve, 1200));
  assert.equal(root.frames.filter(frame => frame.UsageCursor).length, stableCount, 'Stable/replayed usage must not emit repeated notifications');
  queries = JSON.parse(sql("SELECT coalesce(json_agg(row_to_json(s)), '[]'::json) FROM (SELECT query, calls, rows FROM pg_stat_statements WHERE query LIKE '%cq_%' AND query NOT LIKE '%pg_stat_statements%') s"));
  assert.equal(queries.length, 2, 'Watch polling reads only project existence and the indexed usage clock');
  assert.ok(queries.some(row => /^SELECT cursor FROM cq_usage_clock WHERE project_id = \$1$/.test(row.query)));
  assert.ok(queries.some(row => /^SELECT \$\d+ FROM cq_projects WHERE project_id = \$1$/.test(row.query)));
  assert.ok(queries.every(row => Number(row.calls) >= 1 && Number(row.rows) <= Number(row.calls)), JSON.stringify(queries));
  cases.push('initial and changed cursor only; idempotent replay and stable polls emit nothing; actual SQL reads at most one row per indexed query');
  const replacement = id(); root.socket.send(JSON.stringify({ WatchUsage: { id: replacement, project: second } }));
  await root.wait(frame => frame.UsageCursor && frame.UsageCursor.subscription.value === replacement.value);
  const boundary = root.frames.length; await host(first, assignment(first)); await host(second, assignment(second));
  await root.wait(frame => frame.UsageCursor && frame.UsageCursor.subscription.value === replacement.value && frame.UsageCursor.cursor !== '0');
  assert.ok(root.frames.slice(boundary).filter(frame => frame.UsageCursor).every(frame => frame.UsageCursor.subscription.value === replacement.value));
  cases.push('a new project watch replaces the old watch on the same socket');
  const grant = await post('/api/grant', { project: first, actor: { subject: 'watch fixture', session: id(), role: 'Worker' }, expiresAt: String(Date.now() + 60000) });
  const scoped = await connect({ ...headers, Authorization: `Bearer ${grant.value}` }); sockets.push(scoped); recordings.push(scoped.frames);
  const denied = id(); scoped.socket.send(JSON.stringify({ WatchUsage: { id: denied, project: second } }));
  const fault = await scoped.wait(frame => frame.Resync && frame.Resync.subscription.value === denied.value);
  assert.ok(fault.Resync.fault.Denied); assert.equal(scoped.frames.filter(frame => frame.UsageCursor).length, 0);
  const allowed = id(); scoped.socket.send(JSON.stringify({ WatchUsage: { id: allowed, project: first } }));
  const permitted = await scoped.wait(frame => frame.UsageCursor && frame.UsageCursor.subscription.value === allowed.value);
  assert.ok(BigInt(permitted.UsageCursor.cursor) > BigInt(changed.UsageCursor.cursor));
  const missing = id(); root.socket.send(JSON.stringify({ WatchUsage: { id: missing, project: id() } }));
  const absent = await root.wait(frame => frame.Resync && frame.Resync.subscription.value === missing.value); assert.ok(absent.Resync.fault.Missing);
  cases.push('scoped credentials deny foreign projects, allow their own and report missing projects with typed faults');
} finally {
  for (const socket of sockets) await socket.close();
  await writeFile(`${evidence}/usage-watch-results.json`, JSON.stringify({ cases, queries, recordings }, null, 2));
}
console.log('Usage watch: real authorized sockets, replacement, bounded SQL polling, change-only notifications and typed failures passed');
