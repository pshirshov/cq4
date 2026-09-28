import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, settledRequests } from './browser-protocol.mjs';

export async function resyncChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  const draft = title => ({ title, body: 'Snapshot fixture', labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Resnapshot a stale continuation'], result: null, validation: [] } } });
  const change = mutations => post({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Resync fixture', mutations } } } });
  await post({ Initialize: { config: { project, endpoint: origin, name: `Resync ${project.value}` } } });
  const created = await change(Array.from({ length: 50 }, (_, index) => ({ Create: { draft: draft(`Snapshot ${index + 1}`) } })));
  const context = await browser.newContext({ storageState });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true }); await trackProtocol(context);
  const exchanges = []; let held = null; let suppress = false; let capture;
  const captured = new Promise(resolve => { capture = resolve; });
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (frame.Call && frame.Call.command.Search && frame.Call.command.Search.input.project.value === project.value) {
        exchanges.push({ direction: 'sent', frame });
        if (held === null && frame.Call.command.Search.input.after !== null) {
          held = { message, server, id: frame.Call.id.value }; suppress = true; capture(); return;
        }
      }
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (suppress && frame.Changes && frame.Changes.page.events.length > 0) return;
      if (frame.Reply && exchanges.some(entry => entry.direction === 'sent' && entry.frame.Call.id.value === frame.Reply.id.value)) {
        exchanges.push({ direction: 'received', frame });
        if (held !== null && frame.Reply.id.value === held.id) suppress = false;
      }
      route.send(message);
    });
  });
  const page = await context.newPage(); const errors = []; const cases = [];
  page.on('pageerror', error => errors.push(String(error)));
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByText('40 items · more available', { exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    await page.getByRole('button', { name: 'Next page', exact: true }).click();
    let timer;
    try { await Promise.race([captured, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Missing search continuation')), 10000); })]); }
    finally { clearTimeout(timer); }
    const first = created.Changed.ack.items[0];
    await change([{ Replace: { id: first.id, expected: first.revision, draft: draft('Snapshot updated') } }]);
    assert.notEqual(held, null); held.server.send(held.message);
    await page.getByRole('button', { name: 'T1 · Snapshot updated', exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    const rejected = exchanges.find(entry => entry.direction === 'received' && entry.frame.Reply.id.value === held.id);
    assert.ok(rejected.frame.Reply.result.Failed.fault.Resync, 'The actual server rejects the stale snapshot');
    const continuation = JSON.parse(held.message).Call.command.Search.input;
    assert.notEqual(continuation.after, null); assert.notEqual(continuation.snapshot, null);
    const recovery = exchanges.slice(exchanges.indexOf(rejected) + 1).find(entry => entry.direction === 'sent');
    assert.notEqual(recovery, undefined); assert.equal(recovery.frame.Call.command.Search.input.after, null); assert.equal(recovery.frame.Call.command.Search.input.snapshot, null);
    assert.equal(await page.locator('.item-row').count(), 40);
    assert.equal(await page.getByRole('button', { name: 'T41 · Snapshot 41', exact: true }).count(), 0);
    assert.deepEqual(errors, []); cases.push('actual stale continuation rejection restarts first page without mixing snapshots');
  } finally {
    await writeFile(`${evidence}/resync-results.json`, JSON.stringify({ cases, errors, exchanges }, null, 2));
    await page.screenshot({ path: `${evidence}/resync.png`, fullPage: true });
    await context.tracing.stop({ path: `${evidence}/resync.zip` }); await context.close();
  }
  console.log('Chromium search resync: actual stale continuation rejection and fresh first-page recovery');
}
