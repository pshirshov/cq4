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
  const cases = []; const failures = [];
  const check = async (name, body) => { try { await body(); cases.push(name); } catch (error) { failures.push(`${name}: ${String(error)}`); } };
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByRole('button', { name: 'D1 · Visible defect', exact: true }).waitFor();
    await check('D30 semantic table with merged ID/type column', async () => {
      const table = page.getByRole('table', { name: 'Items', exact: true });
      assert.equal(await table.count(), 1);
      assert.deepEqual(await table.getByRole('columnheader').allTextContents(), ['ID', 'Title', 'Status', 'Severity', 'Last modified']);
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
