import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { connectionChecks } from './connection-browser.mjs';

const origin = process.env.CQ_ORIGIN;
const evidence = process.env.CQ_BROWSER_EVIDENCE;
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(String(error)));
async function current() { await page.getByText('Data: current', { exact: true }).waitFor(); }
try {
  await page.goto(origin);
  await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  const name = `Browser ${randomUUID()}`;
  await page.getByLabel('New project name').fill(name);
  await page.getByRole('button', { name: 'Create project', exact: true }).click();
  await page.getByLabel('Project', { exact: true }).getByRole('option', { name, exact: true }).waitFor({ state: 'attached' });
  await current();
  const project = await page.getByLabel('Project', { exact: true }).inputValue();
  await page.getByRole('button', { name: 'New item', exact: true }).click();
  await page.getByLabel('title', { exact: true }).fill('Browser task');
  await page.getByLabel('body', { exact: true }).fill('Unsaved draft survives a page reload.');
  await page.getByLabel('acceptance entry', { exact: true }).fill('The visible client saves an item.');
  await page.reload();
  await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  await page.getByLabel('Project', { exact: true }).selectOption(project);
  await current();
  await page.getByRole('button', { name: 'New item', exact: true }).click();
  assert.equal(await page.getByLabel('title', { exact: true }).inputValue(), 'Browser task');
  await page.getByRole('button', { name: 'Save item', exact: true }).click();
  await page.getByRole('heading', { name: 'T1 · Browser task', exact: true }).waitFor();
  await current();
  await page.getByRole('button', { name: 'Edit current revision', exact: true }).click();
  await page.getByLabel('title', { exact: true }).fill('Edited browser task');
  await page.getByRole('button', { name: 'Save item', exact: true }).click();
  await page.getByRole('heading', { name: 'T1 · Edited browser task', exact: true }).waitFor();
  await page.getByRole('button', { name: 'History', exact: true }).click();
  await page.getByText('Revision 2 · Browser edit', { exact: true }).waitFor();
  await page.getByText('Revision 1 · Browser edit', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Usage audit', exact: true }).click();
  await page.getByText('No usage observations in this scope.', { exact: true }).waitFor();

  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  const input = { Change: { input: { project: { value: project }, change: { request: { value: randomUUID() }, fences: [], reason: 'Concurrent client',
    mutations: [{ Create: { draft: { title: 'External change', body: 'Live event', labels: [], archived: false, citations: [],
      content: { Idea: { status: 'Proposed', outcome: 'Live page updates', motivation: 'Shared work' } } } } }] } } } };
  assert.equal((await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(input) })).status, 200);
  await page.getByRole('button', { name: 'I1 · External change', exact: true }).waitFor();
  await current();
  await page.screenshot({ path: `${evidence}/browser-workspace.png`, fullPage: true });
  await context.setOffline(true);
  await page.getByText('Data: stale', { exact: true }).waitFor();
  await context.setOffline(false);
  await current();
  await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'New item', exact: true }).click();
  const content = page.getByLabel('content', { exact: true });
  assert.equal(await content.locator('option').count(), 14);
  for (const branch of ['Milestone', 'Idea', 'Defect', 'Goal', 'Task', 'Research', 'Hypothesis', 'Question', 'Decision', 'Review', 'Handoff', 'OperatorAction', 'Memory', 'Upstream']) {
    await content.selectOption(branch);
    assert.equal(await content.inputValue(), branch);
  }
  assert.deepEqual(errors, []);
  console.log('Chromium: login, project creation, all 14 forms, persisted draft, create/edit/history, usage audit, external live update and offline recovery passed');
  await connectionChecks(browser, await context.storageState(), origin, evidence);
} finally {
  await writeFile(`${evidence}/browser-errors.json`, JSON.stringify(errors, null, 2));
  await context.tracing.stop({ path: `${evidence}/browser-trace.zip` });
  await browser.close();
}
