// Behavioral-Active Blackbox Good-Communication; regression: D28, D31–D37.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';
import { hold } from './hold.mjs';

export async function redesignChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function call(command) {
    const response = await fetch(origin + '/api/call', { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  await call({ Initialize: { config: { project, endpoint: origin, name: `Semantic UI ${project.value}` } } });
  const draft = { title: 'Explain the integration guard', body: 'Retain changes while reviewing evidence.', labels: ['integration'], archived: false, citations: [],
    content: { Defect: { status: 'Open', severity: 'High', observed: 'Checked-out main is refused', expected: 'Coordinate the normal checkout', reproduction: 'Inspect the recorded precondition', cause: 'Ref-only integration', resolution: [{ description: 'Checked existing guard', origin: 'ModelDeclared', citations: [{ File: { path: 'host/IntegrationCoordinator.scala', revision: null } }] }] } } };
  await call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Semantic fixture', mutations: [
    { Create: { draft } }, { Create: { draft: { ...draft, title: 'Neighbor for relationship search' } } }
  ] } } } });
  const context = await browser.newContext({ storageState, viewport: { width: 1366, height: 768 } });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  const page = await context.newPage(); page.setDefaultTimeout(5000);
  const failures = []; const cases = []; const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  const check = async (id, body) => { try { await body(); cases.push(id); } catch (error) { failures.push(`${id}: ${String(error)}`); } };
  async function enter() {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByRole('button', { name: 'D1 · Explain the integration guard', exact: true }).click();
    await page.getByRole('heading', { name: 'D1 · Explain the integration guard', exact: true }).waitFor();
  }
  try {
    await enter();
    await check('D28 project creation is a header dialog', async () => {
      const add = page.getByRole('button', { name: 'New project', exact: true }); assert.equal(await add.count(), 1);
      assert.equal(await add.evaluate(node => node.closest('header') !== null), true); await add.click();
      const dialog = page.getByRole('dialog', { name: 'New project', exact: true }); await dialog.waitFor();
      await dialog.getByLabel('New project name').fill('Dismissed project'); await dialog.getByRole('button', { name: 'Close', exact: true }).click();
      assert.equal(await page.getByLabel('Project', { exact: true }).inputValue(), project.value);
    });
    await check('D32 semantic evidence viewer', async () => {
      await page.getByText('Checked existing guard', { exact: true }).waitFor();
      await page.getByText('host/IntegrationCoordinator.scala', { exact: true }).waitFor();
      assert.equal(await page.locator('#detail-pane').getByText(/"description"\s*:/).count(), 0);
    });
    await check('D32 structured evidence survives in-place editing', async () => {
      await page.getByRole('button', { name: 'Edit current revision', exact: true }).click();
      await page.getByLabel('description', { exact: true }).fill('Checked existing guard and its preconditions');
      await page.getByLabel('path', { exact: true }).fill('host/IntegrationCoordinator.scala');
      assert.equal(await page.locator('#detail-pane fieldset').count(), 0);
      await page.screenshot({ path: `${evidence}/redesign-editor.png`, fullPage: true });
      await page.getByRole('button', { name: 'Save item', exact: true }).click();
      await page.getByText('Checked existing guard and its preconditions', { exact: true }).waitFor();
      const result = await call({ Read: { input: { project, selection: { ItemDetail: { id: { project, ledger: 'Defects', number: '1' } } } } } });
      assert.deepEqual(result.Detail.view.item.draft, { ...draft, content: { Defect: { ...draft.content.Defect, resolution: [{ ...draft.content.Defect.resolution[0], description: 'Checked existing guard and its preconditions' }] } } });
    });
    await check('D33 new item uses a dialog and preserves selection', async () => {
      await page.getByRole('button', { name: 'New item', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'New item', exact: true }); assert.equal(await dialog.count(), 1);
      await dialog.getByLabel('title', { exact: true }).fill('Saved local dialog draft'); await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('heading', { name: 'D1 · Explain the integration guard', exact: true }).count(), 1);
      await page.getByRole('button', { name: 'New item', exact: true }).click();
      assert.equal(await dialog.getByLabel('title', { exact: true }).inputValue(), 'Saved local dialog draft');
      await hold(page, dialog.getByRole('button', { name: 'Discard local draft', exact: true }));
    });
    if (await page.getByRole('button', { name: 'Discard local draft', exact: true }).isVisible()) await hold(page, page.getByRole('button', { name: 'Discard local draft', exact: true }));
    await check('D85/D86 title-only idea is accepted and faults are shown as text', async () => {
      await page.getByRole('button', { name: 'New item', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'New item', exact: true });
      await dialog.getByRole('button', { name: 'Create Idea', exact: true }).click();
      await dialog.getByLabel('title', { exact: true }).fill('x'.repeat(301));
      await dialog.getByRole('button', { name: 'Save item', exact: true }).click();
      await dialog.getByRole('alert').filter({ hasText: 'Title must contain 1–300 characters' }).waitFor();
      assert.equal((await dialog.getByRole('alert').textContent()).includes('{'), false, 'fault rendered as JSON');
      await dialog.getByLabel('title', { exact: true }).fill('Only a title');
      await dialog.getByRole('button', { name: 'Save item', exact: true }).click();
      await page.getByText(/Saved I\d+ in project/).first().waitFor();
      await page.locator('#detail-pane').getByRole('heading', { name: /^I\d+ · Only a title$/ }).waitFor();
      const ideas = await call({ Search: { input: { project, query: 'ledger:Ideas', after: null, snapshot: null, limit: 20 } } });
      assert.equal(ideas.Found.page.items.length, 1);
      await page.getByRole('button', { name: 'D1 · Explain the integration guard', exact: true }).click();
      await page.locator('#detail-pane').getByRole('heading', { name: 'D1 · Explain the integration guard', exact: true }).waitFor();
    });
    await check('D34 history is a table and semantic revision viewer', async () => {
      await page.getByRole('button', { name: 'History', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'History · D1', exact: true }); assert.equal(await dialog.count(), 1);
      await dialog.getByRole('table', { name: 'Revisions', exact: true }).waitFor();
      await dialog.getByRole('button', { name: 'View revision 1', exact: true }).click();
      await dialog.getByText('Checked existing guard', { exact: true }).waitFor();
      assert.equal(await dialog.locator('textarea,input').count(), 0);
      await page.screenshot({ path: `${evidence}/redesign-history.png`, fullPage: true }); await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    });
    await check('D35 relationship target discovery', async () => {
      const target = page.getByRole('combobox', { name: 'Target item', exact: true }); assert.equal(await target.count(), 1);
      await target.fill('Neighbor'); const match = page.getByRole('option', { name: 'D2 · Neighbor for relationship search', exact: true });
      await match.click(); await page.getByRole('button', { name: 'Preview relationship', exact: true }).click();
      await page.getByRole('region', { name: 'Graph change preview', exact: true }).waitFor();
      await page.getByRole('button', { name: 'Cancel graph preview', exact: true }).click();
    });
    await check('D36 automatic item usage and project dialog', async () => {
      await page.locator('#detail-pane').getByRole('table', { name: 'Usage totals', exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: 'Selected item usage', exact: true }).count(), 0);
      assert.equal(await page.locator('#detail-pane').getByRole('button', { name: 'Usage for whole project', exact: true }).count(), 0);
      await page.getByRole('button', { name: 'Project usage', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Project usage', exact: true }); await dialog.waitFor();
      await dialog.getByRole('table', { name: 'Usage totals', exact: true }).waitFor();
      await page.screenshot({ path: `${evidence}/redesign-project-usage.png`, fullPage: true });
      await dialog.getByRole('button', { name: 'Close', exact: true }).click();
      assert.equal(await page.getByRole('heading', { name: 'D1 · Explain the integration guard', exact: true }).count(), 1);
    });
    await check('D31 right/bottom layout persists', async () => {
      const toggle = page.getByRole('button', { name: 'Dock detail below', exact: true }); assert.equal(await toggle.count(), 1); await toggle.click();
      const positions = () => page.evaluate(() => { const results = document.getElementById('results-pane').getBoundingClientRect(); const detail = document.getElementById('detail-pane').getBoundingClientRect(); return { resultsBottom: results.bottom, detailTop: detail.top }; });
      let bounds = await positions(); assert.ok(bounds.detailTop >= bounds.resultsBottom);
      const separator = page.getByRole('separator', { name: 'Resize results', exact: true });
      const height = Number(await separator.getAttribute('aria-valuenow')); await separator.press('ArrowUp');
      await enter(); bounds = await positions(); assert.ok(bounds.detailTop >= bounds.resultsBottom);
      assert.equal(Number(await separator.getAttribute('aria-valuenow')), height - 16);
      await page.screenshot({ path: `${evidence}/redesign-bottom.png`, fullPage: true });
      await page.getByRole('button', { name: 'Dock detail right', exact: true }).click();
    });
    await check('D37 pane width persists', async () => {
      const splitter = page.getByRole('separator', { name: 'Resize results', exact: true }); const before = Number(await splitter.getAttribute('aria-valuenow'));
      await splitter.press('ArrowRight'); const resized = Number(await splitter.getAttribute('aria-valuenow')); assert.equal(resized, before + 16);
      await enter(); assert.equal(Number(await splitter.getAttribute('aria-valuenow')), resized);
    });
    await page.getByRole('table', { name: 'Usage totals', exact: true }).waitFor();
    await page.screenshot({ path: `${evidence}/redesign-desktop.png`, fullPage: true });
    assert.deepEqual(errors, []); assert.deepEqual(failures, []);
  } finally {
    await writeFile(`${evidence}/redesign-results.json`, JSON.stringify({ cases, failures, errors }, null, 2));
    await context.tracing.stop({ path: `${evidence}/redesign-trace.zip` }); await context.close();
  }
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const browser = await chromium.launch({ headless: true }); const context = await browser.newContext();
  try {
    const page = await context.newPage(); await page.goto(process.env.CQ_ORIGIN);
    await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await redesignChecks(browser, await context.storageState(), process.env.CQ_ORIGIN, process.env.CQ_BROWSER_EVIDENCE);
  } finally { await browser.close(); }
}
