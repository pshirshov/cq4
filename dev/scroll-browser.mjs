import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { settledRequests, trackProtocol } from './browser-protocol.mjs';

export async function scrollChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  const draft = title => ({ title, body: 'Scrolling fixture', labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Append results without losing context'], result: null, validation: [] } } });
  const change = mutations => post({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Scrolling fixture', mutations } } } });
  await post({ Initialize: { config: { project, endpoint: origin, name: `Scrolling ${project.value}` } } });
  const created = [];
  for (const [start, length] of [[0, 50], [50, 35]]) {
    const result = await change(Array.from({ length }, (_, index) => ({ Create: { draft: draft(`Result ${start + index + 1}`) } })));
    created.push(...result.Changed.ack.items);
  }
  const context = await browser.newContext({ storageState, viewport: { width: 1280, height: 720 } });
  await trackProtocol(context);
  let hold = false; let held = null;
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (hold && frame.Call?.command.Read?.input.project.value === project.value && frame.Call.command.Read.input.selection.Browse && frame.Call.command.Read.input.selection.Browse.after !== null) {
        hold = false; held = { message, server }; return;
      }
      server.send(message);
    });
    server.onMessage(message => route.send(message));
  });
  const page = await context.newPage(); const errors = []; const cases = []; const failures = [];
  page.setDefaultTimeout(10000); page.on('pageerror', error => errors.push(String(error)));
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByText('40 items · more available', { exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    try {
      for (const width of [1280, 320]) {
        await page.setViewportSize({ width, height: 720 });
        // The Help button (T6) is the last header control; the indicator sits directly to its left.
        const box = await page.locator('.connection-indicator').boundingBox(); assert.notEqual(box, null);
        const help = await page.locator('header').getByRole('button', { name: 'Help', exact: true }).boundingBox(); assert.notEqual(help, null);
        assert.ok(Math.abs(help.x + help.width - (width - 12)) < 2 && help.x - (box.x + box.width) >= 0 && help.x - (box.x + box.width) <= 12 && box.y < 20,
          `Indicator must be at top right, left of Help: ${JSON.stringify({ width, box, help })}`);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      }
      cases.push('connection indicator remains in the top-right corner on laptop and narrow screens');
    } catch (error) { failures.push(String(error)); }
    await page.setViewportSize({ width: 1280, height: 720 });
    const pane = page.getByRole('region', { name: 'Results', exact: true });
    const bottom = () => pane.evaluate(node => { node.scrollTop = node.scrollHeight; });
    await bottom();
    await page.waitForFunction(() => document.querySelectorAll('.item-row').length === 80, null, { timeout: 10000 });
    assert.equal(await page.getByRole('button', { name: 'T1 · Result 1', exact: true }).count(), 1);
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    await bottom();
    await page.waitForFunction(() => document.querySelectorAll('.item-row').length === 85);
    assert.equal(await page.getByRole('button', { name: /^(First page|Next page)$/ }).count(), 0);
    const ids = await page.locator('.item-row').evaluateAll(nodes => nodes.map(node => node.dataset.item));
    assert.equal(new Set(ids).size, 85); cases.push('scrolling appends all three batches once and preserves earlier items');
    const last = page.getByRole('button', { name: 'T85 · Result 85', exact: true });
    await last.focus();
    const position = await pane.evaluate(node => node.scrollTop);
    const updated = created[74];
    await change([{ Replace: { id: updated.id, expected: updated.revision, draft: draft('Updated result 75') } }]);
    await page.getByRole('button', { name: 'T75 · Updated result 75', exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    assert.equal(await page.locator('.item-row').count(), 85);
    assert.equal(await last.evaluate(node => node === document.activeElement), true);
    assert.ok(Math.abs(await pane.evaluate(node => node.scrollTop) - position) < 2);
    cases.push('live refresh retains the loaded range, scroll position and keyboard focus');
    const query = page.getByLabel('Search query');
    await query.fill('ledger:Tasks'); await page.getByRole('button', { name: 'Search', exact: true }).click();
    await page.getByText('40 items · more available', { exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    hold = true; await bottom();
    const deadline = Date.now() + 10000;
    while (held === null && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
    assert.notEqual(held, null, 'Expected a held continuation request');
    await query.fill('"absent item"'); await page.getByRole('button', { name: 'Search', exact: true }).click();
    held.server.send(held.message);
    await page.getByText('No matching items.', { exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor(); await settledRequests(page);
    assert.equal(await page.locator('.item-row').count(), 0);
    cases.push('delayed continuation is discarded after changing the query');
    assert.deepEqual(errors, []); assert.deepEqual(failures, []);
    console.log('Scrolling: automatic bounded batches, live range/focus preservation, stale query ownership and top-right connection indicator passed');
  } catch (error) { failures.push(String(error)); throw error; }
  finally {
    await writeFile(`${evidence}/scroll-results.json`, JSON.stringify({ cases, errors, failures }, null, 2));
    await page.screenshot({ path: `${evidence}/scroll.png` }); await context.close();
  }
}
