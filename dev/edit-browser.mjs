import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, receivedReply, settledRequests } from './browser-protocol.mjs';

export async function editChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  await post({ Initialize: { config: { project, endpoint: origin, name: `Edit ${project.value}` } } });
  const draft = title => ({ title, body: 'Original body', labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Keep navigation while saving'], result: null, validation: [] } } });
  await post({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Edit fixture',
    mutations: ['Edit A', 'Edit B'].map(title => ({ Create: { draft: draft(title) } })) } } } });
  const other = { value: randomUUID() };
  await post({ Initialize: { config: { project: other, endpoint: origin, name: `Other ${other.value}` } } });
  for (const scenario of ['item', 'project']) {
    const before = await post({ Read: { input: { project, selection: { ItemDetail: { id: { project, ledger: 'Tasks', number: '1' } } } } } });
    const context = await browser.newContext({ storageState });
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true }); await trackProtocol(context);
    let request = null; let held = null; let resolve;
    const captured = new Promise(done => { resolve = done; });
    await context.routeWebSocket(/\/ws$/, route => {
      const server = route.connectToServer();
      route.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (request === null && frame.Call && frame.Call.command.Change) request = frame.Call.id.value;
        server.send(message);
      });
      server.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (frame.Reply && frame.Reply.id.value === request) {
          assert.ok(frame.Reply.result.Changed, 'Hold an actual committed acknowledgement');
          held = { message, route }; resolve();
        } else route.send(message);
      });
    });
    const page = await context.newPage(); const errors = []; const cases = [];
    page.on('pageerror', error => errors.push(String(error)));
    try {
      await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
      await page.getByLabel('Project', { exact: true }).selectOption(project.value);
      await page.getByRole('button', { name: 'T1 · Edit A', exact: true }).click();
      await page.getByRole('button', { name: 'Edit current revision', exact: true }).click();
      await page.getByLabel('body', { exact: true }).fill('Saved body after navigation');
      await page.getByRole('button', { name: 'Save item', exact: true }).click();
      let timer;
      try { await Promise.race([captured, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Missing committed acknowledgement')), 10000); })]); }
      finally { clearTimeout(timer); }
      if (scenario === 'item') {
        await page.getByRole('button', { name: 'T2 · Edit B', exact: true }).click();
        await page.getByRole('heading', { name: 'T2 · Edit B', exact: true }).waitFor();
      } else {
        await page.getByLabel('Project', { exact: true }).selectOption(other.value);
        await page.getByText('No matching items.', { exact: true }).waitFor();
      }
      assert.notEqual(held, null); held.route.send(held.message);
      await receivedReply(page, request); await page.getByText('Saved', { exact: true }).waitFor(); await settledRequests(page);
      if (scenario === 'item') assert.equal(await page.getByRole('heading', { name: 'T2 · Edit B', exact: true }).count(), 1, 'A committed save must not move selection back after navigation');
      else {
        assert.equal(await page.getByLabel('Project', { exact: true }).inputValue(), other.value);
        assert.equal(await page.getByText('No matching items.', { exact: true }).count(), 1);
        assert.equal(await page.getByRole('heading', { name: 'T1 · Edit A', exact: true }).count(), 0);
      }
      assert.match(await page.getByRole('status').filter({ hasText: 'Saved' }).textContent(), new RegExp(project.value));
      const current = await post({ Read: { input: { project, selection: { ItemDetail: { id: { project, ledger: 'Tasks', number: '1' } } } } } });
      assert.equal(BigInt(current.Detail.view.item.revision.value), BigInt(before.Detail.view.item.revision.value) + 1n); assert.equal(current.Detail.view.item.draft.body, 'Saved body after navigation');
      cases.push(`acknowledgement after ${scenario} navigation`); assert.deepEqual(errors, []);
    } finally {
      await writeFile(`${evidence}/edit-${scenario}-results.json`, JSON.stringify({ cases, errors }, null, 2));
      await page.screenshot({ path: `${evidence}/edit-${scenario}.png`, fullPage: true });
      await context.tracing.stop({ path: `${evidence}/edit-${scenario}.zip` }); await context.close();
    }
  }
  console.log('Chromium edit completion: held committed replies preserve item/project navigation and report the saved scope');
}
