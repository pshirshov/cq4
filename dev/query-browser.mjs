import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, receivedReply } from './browser-protocol.mjs';

export async function queryChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() }; const other = { value: randomUUID() };
  for (const id of [project, other]) await post({ Initialize: { config: { project: id, endpoint: origin, name: `Query ${id.value}` } } });
  await post({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Query browser fixture', mutations: [
    { Create: { draft: { title: 'Completion target', body: 'Query popup fixture', labels: [], archived: false, citations: [],
      content: { Task: { status: 'Ready', acceptance: ['Query navigation'], result: null, validation: [] } } } } },
  ] } } } });
  const context = await browser.newContext({ storageState });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  await trackProtocol(context);
  let delay = null;
  const exchanges = [];
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (delay !== null && delay.id === null && frame.Call && frame.Call.command.Read && frame.Call.command.Read.input.selection.QueryComplete &&
          frame.Call.command.Read.input.selection.QueryComplete.query === delay.query) delay.id = frame.Call.id.value;
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (delay !== null && frame.Reply && frame.Reply.id.value === delay.id) {
        exchanges.push({ query: delay.query, reply: frame.Reply }); delay.release = () => route.send(message); delay.resolve();
      } else route.send(message);
    });
  });
  const page = await context.newPage(); const errors = []; const cases = [];
  page.on('pageerror', error => errors.push(String(error)));
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByRole('button', { name: 'T1 · Completion target', exact: true }).waitFor();
    const query = page.getByRole('combobox', { name: 'Search query', exact: true });
    for (const [text, option, expected] of [
      ['led', 'ledger · Field', 'ledger:'], ['blocked-b', 'blocked-by · Relation', 'blocked-by:'],
      ['alpha O', 'OR · Operator', 'alpha OR '], ['status:Do', 'done · Value', 'status:done'],
      ['id:T', 'T1 · Completion target · Item', 'id:T1'],
    ]) {
      await query.fill(text); await page.getByRole('option', { name: option, exact: true }).waitFor();
      await query.press('ArrowDown'); await query.press('Enter');
      assert.equal(await query.inputValue(), expected); assert.equal(await query.getAttribute('aria-expanded'), 'false'); cases.push(option);
    }
    await query.fill('ledgar:Tasks AND status:Ready');
    await query.evaluate(input => input.setSelectionRange(3, 3)); await query.press('Control+Space');
    await page.getByRole('option', { name: 'ledger · Field', exact: true }).waitFor();
    await page.screenshot({ path: `${evidence}/query-completion.png`, fullPage: true });
    await query.press('ArrowUp'); await query.press('Tab');
    assert.equal(await query.inputValue(), 'ledger:Tasks AND status:Ready');
    await query.focus(); assert.equal(await query.evaluate(input => input.selectionStart), 6); cases.push('middle token and Tab acceptance');
    await query.fill('ledger:unknown'); await page.getByRole('button', { name: 'Show query error', exact: true }).waitFor();
    assert.equal(await query.getAttribute('aria-invalid'), 'true');
    assert.equal(await page.locator('.query-diagnostic mark').textContent(), 'unknown');
    await page.getByRole('button', { name: 'Show query error', exact: true }).click();
    assert.deepEqual(await query.evaluate(input => [input.selectionStart, input.selectionEnd]), [7, 14]);
    assert.equal(await page.locator('.query-diagnostic').isVisible(), true, 'Locating the error must preserve the diagnostic');
    await page.screenshot({ path: `${evidence}/query-positioned-error.png`, fullPage: true }); cases.push('positioned syntax error');
    for (const scenario of ['newer-text', 'dismissed', 'project-change']) {
      let resolve;
      const captured = new Promise(done => { resolve = done; });
      delay = { query: 'ledger:t', id: null, release: null, resolve };
      await query.fill(delay.query);
      let timer;
      try { await Promise.race([captured, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Missing held query reply')), 10000); })]); }
      finally { clearTimeout(timer); }
      if (scenario === 'newer-text') {
        await query.fill('status:r'); await page.getByRole('option', { name: 'ready · Value', exact: true }).waitFor();
      } else if (scenario === 'dismissed') await query.press('Escape');
      else { await page.getByLabel('Project', { exact: true }).selectOption(other.value); await page.getByText('No matching items.', { exact: true }).waitFor(); }
      assert.notEqual(delay.release, null); delay.release();
      await receivedReply(page, delay.id);
      assert.equal(await page.getByRole('option', { name: 'tasks · Value', exact: true }).count(), 0);
      assert.equal(await query.getAttribute('aria-expanded'), scenario === 'newer-text' ? 'true' : 'false');
      if (scenario === 'newer-text') await page.getByRole('option', { name: 'ready · Value', exact: true }).waitFor();
      cases.push(scenario); delay = null;
    }
    assert.deepEqual(errors, []);
    console.log('Chromium query: five suggestion kinds, middle-token replacement, keyboard acceptance/dismissal, positioned errors and stale text/project replies passed');
  } finally {
    await writeFile(`${evidence}/query-results.json`, JSON.stringify({ cases, errors, exchanges }, null, 2));
    await context.tracing.stop({ path: `${evidence}/query-trace.zip` }); await context.close();
  }
}
