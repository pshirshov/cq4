import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

export async function draftChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  const id = () => ({ value: randomUUID() });
  const draft = title => ({ title, body: 'Original body', labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Preserve concurrent work'], result: null, validation: [] } } });
  async function call(command) {
    const response = await fetch(origin + '/api/call', { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200);
    const value = await response.json(); assert.equal(value.Failed, undefined); return value;
  }
  const change = (project, mutations) => call({ Change: { input: { project,
    change: { request: id(), mutations, fences: [], reason: 'Draft recovery fixture' } } } });
  async function enter(page, project) {
    await page.goto(origin);
    await page.getByLabel('Operator token').or(page.getByText('Connection: ALIVE', { exact: true })).first().waitFor();
    if (await page.getByLabel('Operator token').isVisible()) {
      await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    }
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByText('Data: current', { exact: true }).waitFor();
  }
  const failures = [];
  for (const scenario of ['stale-base', 'uncertain-create', 'uncertain-login']) {
    const context = await browser.newContext({ storageState });
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    const page = await context.newPage();
    const project = id();
    try {
      await call({ Initialize: { config: { project, endpoint: origin, name: `Draft ${scenario}` } } });
      if (scenario === 'stale-base') {
        const created = await change(project, [{ Create: { draft: draft('Original title') } }]);
        const item = created.Changed.ack.items[0];
        await enter(page, project);
        await page.getByRole('button', { name: 'T1 · Original title', exact: true }).click();
        await page.getByRole('button', { name: 'Edit current revision', exact: true }).click();
        await page.getByLabel('body', { exact: true }).fill('My unsaved local note');
        await change(project, [{ Replace: { id: item.id, expected: item.revision,
          draft: { ...draft('Concurrent title'), body: 'Concurrent body' } } }]);
        await enter(page, project);
        await page.getByRole('button', { name: 'T1 · Concurrent title', exact: true }).click();
        await page.getByRole('button', { name: 'Edit current revision', exact: true }).click();
        assert.equal(await page.getByLabel('body', { exact: true }).inputValue(), 'My unsaved local note');
        await page.getByRole('button', { name: 'Save item', exact: true }).click();
        await page.waitForFunction(() => document.body.textContent.includes('Saved') || document.body.textContent.includes('Conflict'));
        const actual = await call({ Read: { input: { project, selection: { ItemDetail: { id: item.id } } } } });
        assert.equal(actual.Detail.view.item.revision.value, '2', 'Restored draft must retain its original expected revision');
        assert.equal(actual.Detail.view.item.draft.body, 'Concurrent body');
        await page.getByRole('alert').filter({ hasText: 'Conflict' }).waitFor();
        await page.getByRole('heading', { name: 'Edit conflict · T1', exact: true }).waitFor();
        await page.getByText('Your draft is based on revision 1; the current revision is 2.', { exact: true }).waitFor();
        await page.getByRole('button', { name: 'Use current revision as draft base', exact: true }).click();
        assert.equal(await page.getByLabel('body', { exact: true }).inputValue(), 'My unsaved local note');
        await page.getByText('Edit T1 from revision 2', { exact: true }).waitFor();
        await page.getByRole('button', { name: 'Save item', exact: true }).click();
        await page.getByText('Saved', { exact: true }).waitFor();
        const rebased = await call({ Read: { input: { project, selection: { ItemDetail: { id: item.id } } } } });
        assert.equal(rebased.Detail.view.item.revision.value, '3');
        assert.equal(rebased.Detail.view.item.draft.body, 'My unsaved local note');
      } else {
        let dropNext = true;
        let acknowledgeDrop;
        const dropped = new Promise(resolve => { acknowledgeDrop = resolve; });
        await context.routeWebSocket(/\/ws$/, route => {
          const server = route.connectToServer();
          let suppressed = null;
          route.onMessage(message => {
            const frame = JSON.parse(String(message));
            if (dropNext && frame.Call && frame.Call.command.Change) { suppressed = frame.Call.id.value; dropNext = false; }
            server.send(message);
          });
          server.onMessage(message => {
            const frame = JSON.parse(String(message));
            if (frame.Reply && frame.Reply.id.value === suppressed) {
              assert.ok(frame.Reply.result.Changed, 'Suppressed response must acknowledge a committed mutation');
              acknowledgeDrop(); return;
            }
            route.send(message);
          });
        });
        await enter(page, project);
        await page.getByRole('button', { name: 'New item', exact: true }).click();
        await page.getByLabel('title', { exact: true }).fill('Uncertain create');
        await page.getByLabel('acceptance entry', { exact: true }).fill('Retry without duplication');
        await page.getByRole('button', { name: 'Save item', exact: true }).click();
        let deadline;
        try { await Promise.race([dropped, new Promise((_, reject) => { deadline = setTimeout(() => reject(new Error('Expected committed acknowledgement to be dropped')), 10000); })]); }
        finally { clearTimeout(deadline); }
        if (scenario === 'uncertain-login') {
          const status = await page.evaluate(async () => (await fetch('/api/logout', { method: 'POST' })).status);
          assert.equal(status, 204);
        }
        await enter(page, project);
        await page.getByRole('button', { name: 'New item', exact: true }).click();
        assert.equal(await page.getByLabel('title', { exact: true }).inputValue(), 'Uncertain create');
        await page.getByRole('button', { name: 'Save item', exact: true }).click();
        await page.getByText('Saved', { exact: true }).waitFor();
        const actual = await call({ Search: { input: { project, query: 'ledger:Tasks archived:all', after: null, snapshot: null, limit: 20 } } });
        assert.equal(actual.Found.page.items.length, 1, 'Reloaded uncertain create must reuse its persisted request identity');
      }
      console.log(`Chromium draft recovery passed: ${scenario}`);
    } catch (error) { failures.push(`${scenario}: ${String(error)}`); }
    finally {
      await page.screenshot({ path: `${evidence}/draft-${scenario}.png`, fullPage: true });
      await context.tracing.stop({ path: `${evidence}/draft-${scenario}.zip` });
      await context.close();
    }
  }
  assert.deepEqual(failures, []);
}
