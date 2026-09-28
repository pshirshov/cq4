import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, settledRequests } from './browser-protocol.mjs';

export async function usageLiveChecks(browser, storageState, origin, evidence) {
  const id = () => ({ value: randomUUID() }); const project = id();
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(path, body) {
    const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text()); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  await post('/api/call', { Initialize: { config: { project, endpoint: origin, name: `Live usage ${project.value}` } } });
  const host = operation => post('/api/usage', { project, operation });
  const assignment = { id: id(), project, members: [], attribution: 'Unattributed', cohort: null, evaluation: null };
  const attempt = { id: id(), assignment: assignment.id, parent: null, session: id(), role: 'Governor', harness: 'Codex', provider: 'fixture', model: 'no-model-call', collector: 'fixture', startedAt: '1000' };
  const counter = n => ({ value: String(n), measurement: 'Observed' });
  const counts = n => ({ input: counter(n), output: counter(0), cacheRead: counter(0), cacheWrite: counter(0), reasoning: counter(0) });
  const cost = { amount: null, currency: null, basis: 'Unknown', pricingVersion: null };
  await host({ Assign: { value: assignment } }); await host({ Start: { value: attempt } });
  await host({ Meter: { value: { key: 'live', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: cost } } });
  const upload = (position, amount) => host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: 'live-fixture', position: String(position), occurredAt: '2000', receivedAt: '0',
    scope: 'Increment', counters: counts(amount), inputIncludesCache: true, outputIncludesReasoning: true, cost, completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'live', disposition: 'Contribution', detailReason: null } } });
  const context = await browser.newContext({ storageState });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true }); await trackProtocol(context);
  let armed = false; let heldId = null; let held = null; const connections = [];
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer(); const connection = { sent: [], received: [] }; connections.push(connection);
    route.onMessage(message => {
      const frame = JSON.parse(String(message)); connection.sent.push(frame);
      if (armed && frame.Call && frame.Call.command.Usage && frame.Call.command.Usage.input.selection.Summary) { heldId = frame.Call.id.value; armed = false; }
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message)); connection.received.push(frame);
      if (frame.Reply && frame.Reply.id.value === heldId) { assert.ok(frame.Reply.result.UsageSummary); held = { route, message }; }
      else route.send(message);
    });
  });
  const page = await context.newPage(); const errors = []; const cases = [];
  page.on('pageerror', error => errors.push(String(error)));
  const click = name => page.getByRole('button', { name, exact: true }).click();
  const total = amount => page.getByText(`Unattributed: ${amount} known tokens; 0 unknown measurements; 0 estimated measurements`, { exact: true });
  const summaries = () => connections.flatMap(connection => connection.sent).filter(frame => frame.Call && frame.Call.command.Usage && frame.Call.command.Usage.input.selection.Summary).length;
  async function until(predicate) {
    const deadline = Date.now() + 10000;
    while (!predicate()) { assert.ok(Date.now() < deadline, 'Expected live usage protocol event'); await new Promise(resolve => setTimeout(resolve, 20)); }
  }
  function release() { assert.notEqual(held, null); held.route.send(held.message); held = null; heldId = null; }
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value); await click('Project usage');
    await page.getByRole('heading', { name: 'Usage · project', exact: true }).waitFor(); await settledRequests(page);
    await click('Usage audit'); await page.getByRole('heading', { name: 'Usage audit', exact: true }).waitFor();
    armed = true; await upload(1, 10); await until(() => held !== null);
    const query = page.getByLabel('Search query', { exact: true }); await query.fill('alpha AND'); await click('Search');
    await page.getByText('Data: invalid query', { exact: true }).waitFor(); release();
    await total(10).waitFor(); await settledRequests(page);
    cases.push('query-only invalidation does not discard the only pending usage observation');
    const before = summaries(); armed = true; await upload(2, 5); await until(() => held !== null);
    const next = await upload(3, 7); const cursor = next.Ingested.value.sequence;
    await page.getByText(new RegExp(`latest observed usage cursor ${cursor}\\.$`)).waitFor();
    assert.equal(summaries() - before, 1, 'Usage notifications coalesce behind the active scoped request');
    release(); await total(22).waitFor(); await settledRequests(page);
    assert.equal(summaries() - before, 2, 'One catch-up summary reaches the latest cursor');
    cases.push('multiple usage notifications coalesce and catch up without a request per notification');
    const oldCount = connections.length; await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await until(() => connections.length > oldCount && connections[connections.length - 1].received.some(frame => frame.Reply && frame.Reply.result.Failed && frame.Reply.result.Failed.fault.QuerySyntax));
    await upload(4, 3); await total(25).waitFor({ timeout: 5000 });
    const current = connections[connections.length - 1];
    assert.equal(current.sent.filter(frame => frame.WatchUsage && frame.WatchUsage.project.value === project.value).length, 1);
    cases.push('overlapping healthy connection replacement registers the watch and receives further usage-only updates');
    assert.equal(await page.getByText('Data: invalid query', { exact: true }).count(), 1); assert.deepEqual(errors, []);
  } finally {
    await writeFile(`${evidence}/usage-live-results.json`, JSON.stringify({ cases, errors, connections }, null, 2));
    await page.screenshot({ path: `${evidence}/usage-live.png`, fullPage: true });
    await context.tracing.stop({ path: `${evidence}/usage-live.zip` }); await context.close();
  }
  console.log('Chromium usage watch: query independence, coalesced catch-up and overlapping connection replacement');
}
