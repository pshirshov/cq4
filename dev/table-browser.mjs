// Behavioral-Active Blackbox Good-Communication; D29/D30.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

export async function tableChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function call(command) {
    const response = await fetch(origin + '/api/call', { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  await call({ Initialize: { config: { project, endpoint: origin, name: `Table ${project.value}` } } });
  const draft = { title: 'Visible defect', body: '', labels: [], archived: false, citations: [], content: {
    Defect: { status: 'Open', severity: 'High', observed: 'Rows are cards', expected: 'A sortable table', reproduction: 'Open results', cause: null, resolution: [] }
  } };
  await call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Table fixture', mutations: [{ Create: { draft } }] } } } });
  const context = await browser.newContext({ storageState, viewport: { width: 1366, height: 768 } });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  const page = await context.newPage(); page.setDefaultTimeout(5000);
  const browses = []; const errors = []; page.on('pageerror', error => errors.push(String(error)));
  // The browses of the current document: a reload opens a new socket, and what the previous document sent last is not counted for it.
  page.on('websocket', socket => {
    browses.length = 0;
    socket.on('framesent', frame => {
      const browse = JSON.parse(String(frame.payload)).Call?.command.Read?.input.selection.Browse; if (browse) browses.push(browse);
    });
  });
  const reopen = async () => {
    browses.length = 0; await page.reload(); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value); await page.getByText('Data: current', { exact: true }).waitFor();
  };
  const savedView = () => page.evaluate(() => JSON.parse(localStorage.getItem('cq-items-view')));
  const cases = []; const failures = [];
  const check = async (name, body) => { try { await body(); cases.push(name); } catch (error) { failures.push(`${name}: ${String(error)}`); } };
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByRole('button', { name: 'D1 · Visible defect', exact: true }).waitFor();
    await check('D30 semantic table with merged ID/type column', async () => {
      const table = page.getByRole('table', { name: 'Items', exact: true });
      assert.equal(await table.count(), 1);
      assert.deepEqual(await table.getByRole('columnheader').allTextContents(), ['ID', 'Title', 'Status', '', 'Severity', '', 'Last modified']);
      const work = table.getByRole('columnheader').nth(3);
      assert.equal(await work.getByRole('img', { name: 'In progress', exact: true }).count(), 1);
      assert.equal(await table.locator('tbody .item-work').first().locator('svg').count(), 0);
      const milestone = table.getByRole('columnheader').nth(5);
      assert.equal(await milestone.getByRole('img', { name: 'Milestone', exact: true }).locator('path').getAttribute('d'),
        await page.getByRole('button', { name: 'Milestones', exact: true }).locator('.navigation-icon path').getAttribute('d'));
      assert.equal(await milestone.locator('svg[aria-label="Milestone"]').count(), 1);
      assert.deepEqual(await table.getByRole('separator').evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-label'))),
        ['ID', 'Title', 'Status', 'In progress', 'Severity', 'Milestone', 'Last modified'].map(name => `Resize ${name} column`));
      const id = table.locator('tbody .item-id').first();
      assert.equal(await id.locator('svg').count(), 1); assert.equal(await id.getAttribute('title'), 'Defects');
      assert.equal(await table.getByRole('cell', { name: 'High', exact: true }).count(), 1);
    });
    await check('D29 live navigation counts', async () => {
      const defects = page.getByRole('button', { name: 'Defects', exact: true });
      await defects.locator('.navigation-count').waitFor();
      await page.waitForFunction(() => document.querySelector('button[aria-label="Defects"] .navigation-count').textContent === '1');
      assert.equal(await defects.locator('.navigation-icon').count(), 1);
    });
    await check('D29/D30 redundant headings removed', async () => {
      assert.equal(await page.getByRole('heading', { name: 'Workspace', exact: true }).count(), 0);
      assert.equal(await page.getByRole('heading', { name: 'Items', exact: true }).count(), 0);
    });
    await check('D30 sorts items beyond the first batch and supports every column', async () => {
      const additions = Array.from({ length: 84 }, (_, index) => ({ Create: { draft: { ...draft, title: `Item ${String(84 - index).padStart(3, '0')}`,
        content: { Defect: { ...draft.content.Defect, severity: ['Low', 'Medium', 'High', 'Critical'][index % 4] } } } } }));
      for (const mutations of [additions.slice(0, 42), additions.slice(42)])
        await call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Global sort fixture', mutations } } } });
      await page.getByText('40 items · more available', { exact: true }).waitFor();
      const table = page.getByRole('table', { name: 'Items', exact: true });
      const first = () => table.locator('tbody .item-row').first().getByRole('cell').first().textContent();
      async function sort(label, expected) {
        await table.getByRole('button', { name: `Sort by ${label}`, exact: true }).click();
        await page.waitForFunction(id => document.querySelector('.items-table tbody .item-row td').textContent === id, expected);
        assert.equal(await first(), expected);
      }
      assert.equal(await table.getByRole('button', { name: 'D85 · Item 001', exact: true }).count(), 0);
      await sort('title', 'D85'); await sort('title', 'D1');
      await sort('severity', 'D5'); await sort('severity', 'D2');
      await sort('status', 'D1');
      assert.equal(await table.getByRole('button', { name: 'Sort by type', exact: true }).count(), 0);
      await sort('ID', 'D1'); await sort('ID', 'D85');
      assert.equal(await table.getByRole('columnheader').first().getAttribute('aria-sort'), 'descending');
      const pane = page.getByRole('region', { name: 'Results', exact: true });
      for (const count of [80, 85]) {
        await pane.evaluate(node => { node.scrollTop = node.scrollHeight; });
        await page.waitForFunction(size => document.querySelectorAll('.item-row').length === size, count);
      }
      assert.deepEqual(await table.locator('tbody .item-id').allTextContents(), Array.from({ length: 85 }, (_, i) => `D${85 - i}`));
    });
    await check('D29 counts update during invalid searches and exclude archives', async () => {
      const query = page.getByLabel('Search query'); await query.fill('alpha AND');
      await page.getByRole('button', { name: 'Search', exact: true }).click();
      await page.getByText('Data: invalid query', { exact: true }).waitFor();
      await call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Count archive fixture', mutations: [
        { Replace: { id: { project, ledger: 'Defects', number: '1' }, expected: { value: '1' }, draft: { ...draft, archived: true, content: { Defect: { ...draft.content.Defect, status: 'Resolved' } } } } }
      ] } } } });
      await page.waitForFunction(() => document.querySelector('button[aria-label="Defects"] .navigation-count').textContent === '84');
      assert.equal(await page.getByRole('button', { name: 'All items', exact: true }).locator('.navigation-count').textContent(), '84');
      await page.getByRole('button', { name: 'All items', exact: true }).click();
      await page.getByText('Data: current', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: 'D1 · Visible defect', exact: true }).count(), 0);
    });
    const table = page.getByRole('table', { name: 'Items', exact: true });
    const sorted = (index, direction) => page.waitForFunction(([column, value]) =>
      document.querySelectorAll('.items-table th')[column].getAttribute('aria-sort') === value, [index, direction]);
    await check('I15 sort field and direction survive a reload', async () => {
      const title = table.getByRole('button', { name: 'Sort by title', exact: true });
      await title.click(); await sorted(1, 'ascending'); await title.click(); await sorted(1, 'descending');
      assert.deepEqual(await savedView(), { field: 'Title', direction: 'Descending', grouped: false });
      await reopen();
      assert.equal(await table.getByRole('columnheader').nth(1).getAttribute('aria-sort'), 'descending');
      assert.deepEqual(browses[0].order, { field: 'Title', direction: 'Descending', grouped: false });
    });
    await check('I15 malformed or outdated view state falls back to ID ascending and is overwritten', async () => {
      for (const saved of ['{"field":"Title"', 'null', '{"field":"Title","direction":"Descending"}', '{"field":"Rank","direction":"Descending","grouped":false}']) {
        await page.evaluate(value => localStorage.setItem('cq-items-view', value), saved);
        await reopen();
        assert.equal(await table.getByRole('columnheader').first().getAttribute('aria-sort'), 'ascending');
        assert.deepEqual(browses[0].order, { field: 'Id', direction: 'Ascending', grouped: false });
        assert.equal(await page.getByRole('alert').count(), 0); assert.deepEqual(errors, []);
      }
      await table.getByRole('button', { name: 'Sort by status', exact: true }).click(); await sorted(2, 'ascending');
      assert.deepEqual(await savedView(), { field: 'Status', direction: 'Ascending', grouped: false });
    });
    await check('I15 a failed view-state write warns and keeps the chosen order', async () => {
      await page.evaluate(() => { Storage.prototype.setItem = () => { throw new DOMException('Quota exceeded', 'QuotaExceededError'); }; });
      await table.getByRole('button', { name: 'Sort by severity', exact: true }).click(); await sorted(4, 'ascending');
      await page.getByRole('alert').getByText('Items view could not be saved in this browser.').waitFor();
      await reopen();
      assert.equal(await table.getByRole('columnheader').nth(2).getAttribute('aria-sort'), 'ascending');
    });
    const change = mutations => call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Milestone fixture', mutations } } } });
    const item = (ledger, number) => ({ project, ledger, number: String(number) });
    const membership = (task, revision, milestone, expected, present) => ({ Reference: { source: item('Tasks', task), expectedSource: { value: String(revision) },
      relation: 'PartOf', target: item('Milestones', milestone), expectedTarget: { value: String(expected) }, present } });
    const search = async text => {
      await page.getByLabel('Search query').fill(text); await page.getByRole('button', { name: 'Search', exact: true }).click();
      await page.getByText('Data: current', { exact: true }).waitFor();
    };
    // Item rows by ID; group header rows by their text behind '#'.
    const lines = () => table.locator('tbody tr').evaluateAll(rows => rows.map(row =>
      row.classList.contains('item-row') ? row.querySelector('.item-id').textContent : `#${row.textContent}`));
    const shows = expected => page.waitForFunction(value => JSON.stringify([...document.querySelectorAll('.items-table tbody tr')].map(row =>
      row.classList.contains('item-row') ? row.querySelector('.item-id').textContent : `#${row.textContent}`)) === value, JSON.stringify(expected));
    const grouping = table.getByRole('checkbox', { name: 'Group by milestone', exact: true });
    await check('I15 milestone cells show M<n> for members only, without controls', async () => {
      await change([...['Later', 'Sooner'].map(title => ({ Create: { draft: { ...draft, title, content: { Milestone: { status: 'Open', objective: 'Group rows' } } } } })),
        ...['One', 'Two', 'Three', 'Four'].map(title => ({ Create: { draft: { ...draft, title, content: { Task: { status: 'Ready', acceptance: ['Listed'], result: null, validation: [] } } } } }))]);
      await change([membership(1, 1, 2, 1, true), membership(2, 1, 1, 1, true)]); await change([membership(4, 1, 1, 2, true)]);
      await table.getByRole('button', { name: 'Sort by ID', exact: true }).click(); await sorted(0, 'ascending');
      await search('ledger:Tasks'); await shows(['T1', 'T2', 'T3', 'T4']);
      const cells = table.locator('tbody td.item-milestone');
      assert.deepEqual(await cells.allTextContents(), ['M2', 'M1', '', 'M1']);
      assert.equal(await cells.locator('button, a, input, [tabindex]').count(), 0);
      assert.deepEqual(await table.locator('tbody tr').first().locator('td').evaluateAll(nodes => nodes.map(node => node.className)),
        ['item-id', '', 'item-status', 'item-work', 'item-severity', 'item-milestone', 'item-modified']);
      assert.ok(await cells.evaluateAll(nodes => nodes.every(node => node.scrollWidth <= node.clientWidth)));
      assert.equal(await table.locator('thead').evaluate(node => /milestone/i.test(node.innerText)), false);
    });
    await check('I15 grouping reissues the browse and inserts one unfocusable header per group', async () => {
      assert.equal(await grouping.isChecked(), false); browses.length = 0;
      await grouping.check(); await shows(['#M1', 'T2', 'T4', '#M2', 'T1', '#—', 'T3']);
      // The page also reloads its rows for a live update, 500 ms or more after the change that caused it, and after a load that such an
      // update interrupted. Such a browse can be sent between the line above and the click, in the order of that moment, so the first
      // recorded browse is not necessarily the one the click caused. Every browse sent after the click carries the new order, and the
      // grouped rows are shown from one of them, so the last one recorded here does.
      assert.deepEqual(browses.at(-1).order, { field: 'Id', direction: 'Ascending', grouped: true });
      assert.deepEqual(await savedView(), { field: 'Id', direction: 'Ascending', grouped: true });
      const headings = table.locator('tbody tr.item-group');
      assert.equal(await headings.locator('button, a, input, [tabindex]').count(), 0);
      assert.deepEqual(await headings.evaluateAll(rows => rows.map(row => [row.querySelectorAll('svg').length, row.cells.length, row.cells[0].colSpan])), [[1, 1, 7], [1, 1, 7], [1, 1, 7]]);
      const focus = () => page.evaluate(() => document.activeElement.getAttribute('aria-label'));
      await table.getByRole('button', { name: 'T2 · Two', exact: true }).focus();
      for (const [key, expected] of [['ArrowDown', 'T4 · Four'], ['ArrowDown', 'T1 · One'], ['ArrowDown', 'T3 · Three'], ['ArrowDown', 'T3 · Three'], ['ArrowUp', 'T1 · One'],
        ['ArrowUp', 'T4 · Four'], ['Home', 'T2 · Two'], ['End', 'T3 · Three']]) { await page.keyboard.press(key); assert.equal(await focus(), expected); }
      await page.screenshot({ path: `${evidence}/table-grouped.png`, fullPage: true });
      await table.getByRole('button', { name: 'Sort by title', exact: true }).click(); await shows(['#M1', 'T4', 'T2', '#M2', 'T1', '#—', 'T3']);
      await table.getByRole('button', { name: 'Sort by ID', exact: true }).click(); await shows(['#M1', 'T2', 'T4', '#M2', 'T1', '#—', 'T3']);
    });
    await check('I15 live updates move rows between groups without duplicating headers', async () => {
      await change([membership(3, 1, 2, 2, true)]); await shows(['#M1', 'T2', 'T4', '#M2', 'T1', 'T3']);
      await change([membership(3, 2, 2, 3, false), { Create: { draft: { ...draft, title: 'Five', content: { Task: { status: 'Ready', acceptance: ['Listed'], result: null, validation: [] } } } } }]);
      await shows(['#M1', 'T2', 'T4', '#M2', 'T1', '#—', 'T3', 'T5']);
      assert.deepEqual(await table.locator('tbody td.item-milestone').allTextContents(), ['M1', 'M1', 'M2', '', '']);
    });
    await check('I15 milestones themselves are listed under the unassigned group; an empty result has no group header', async () => {
      await search('ledger:Milestones'); await shows(['#—', 'M1', 'M2']);
      await search('ledger:Tasks Absent'); await shows(['#No matching items.']);
      assert.equal(await table.locator('tbody td').getAttribute('colspan'), String(await table.getByRole('columnheader').count()));
    });
    await check('I15 grouping survives a reload and can be switched off', async () => {
      await reopen();
      assert.equal(await grouping.isChecked(), true); assert.equal(browses[0].order.grouped, true);
      await search('ledger:Tasks'); await shows(['#M1', 'T2', 'T4', '#M2', 'T1', '#—', 'T3', 'T5']);
      browses.length = 0; await grouping.uncheck(); await shows(['T1', 'T2', 'T3', 'T4', 'T5']);
      assert.equal(browses.at(-1).order.grouped, false); assert.equal((await savedView()).grouped, false);
      assert.deepEqual(errors, []);
    });
    await page.screenshot({ path: `${evidence}/table.png`, fullPage: true });
    assert.deepEqual(failures, []);
  } finally {
    await writeFile(`${evidence}/table-results.json`, JSON.stringify({ cases, failures }, null, 2));
    await context.tracing.stop({ path: `${evidence}/table-trace.zip` }); await context.close();
  }
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const browser = await chromium.launch({ headless: true }); const context = await browser.newContext();
  try {
    const page = await context.newPage(); await page.goto(process.env.CQ_ORIGIN);
    await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await tableChecks(browser, await context.storageState(), process.env.CQ_ORIGIN, process.env.CQ_BROWSER_EVIDENCE);
  } finally { await browser.close(); }
}
