import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const origin = process.env.CQ_ORIGIN;
const evidence = process.env.CQ_BROWSER_EVIDENCE;
const project = { value: randomUUID() };
const id = number => ({ project, ledger: 'Tasks', number: String(number) });
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
async function post(command) {
  const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
  assert.equal(response.status, 200, await response.clone().text());
  const result = await response.json(); assert.equal(result.Failed, undefined, JSON.stringify(result)); return result;
}
const change = mutations => post({ Change: { input: { project, change: { request: { value: randomUUID() }, mutations, fences: [], reason: 'Relationship graph fixture' } } } });
const detail = async number => (await post({ Read: { input: { project, selection: { ItemDetail: { id: id(number) } } } } })).Detail.view;
await post({ Initialize: { config: { project, endpoint: origin, name: `Graph navigation ${project.value}` } } });
await change(Array.from({ length: 17 }, (_, index) => ({ Create: { draft: {
  title: index === 0 ? 'Selected work' : index === 1 ? 'Prerequisite <script>literal</script>' : `Related work ${index + 1}`,
  body: 'Graph fixture', labels: [], archived: false, citations: [],
  content: { Task: { status: 'Ready', acceptance: ['Read-only graph'], result: null, validation: [] } },
} } })));
for (let number = 2; number <= 17; number++) {
  const root = await detail(1); const target = await detail(number);
  await change([{ Reference: { source: id(1), expectedSource: root.item.revision, relation: number <= 3 ? 'BlockedBy' : 'Blocks',
    target: id(number), expectedTarget: target.item.revision, present: true } }]);
}
let root = await detail(1); const neighbor = await detail(2);
await change([{ Reference: { source: id(1), expectedSource: root.item.revision, relation: 'RelatesTo', target: id(2), expectedTarget: neighbor.item.revision, present: true } }]);
const before = await detail(1);
const browser = await chromium.launch({ headless: true });
const errors = []; const cases = [];
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  let armed = false; let held = null; let capture;
  const captured = new Promise(resolve => { capture = resolve; });
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer(); let request = null;
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (armed && frame.Call && frame.Call.command.Search && frame.Call.command.Search.input.query.endsWith('archived:all')) {
        armed = false; request = frame.Call.id.value;
      }
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (request !== null && frame.Reply && frame.Reply.id.value === request) {
        held = () => route.send(message); request = null; capture();
      } else route.send(message);
    });
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(String(error)));
  await page.goto(origin); await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click(); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  await page.getByLabel('Project', { exact: true }).selectOption(project.value);
  await page.getByRole('button', { name: 'T1 · Selected work', exact: true }).click();
  await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Relationships · T1', exact: true });
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  assert.equal(await dialog.getByText('T2 Blocks T1', { exact: true }).count(), 1);
  assert.equal(await dialog.getByText('T1 Blocks T4', { exact: true }).count(), 1);
  assert.equal(await dialog.locator('svg line[marker-end]').count(), 12);
  assert.equal(await dialog.locator('.graph-neighbors button').count(), 12);
  assert.equal(await dialog.locator('script').count(), 0);
  assert.equal((await dialog.innerText()).includes(project.value), false);
  cases.push('prerequisites and dependents use readable identities, correct blocks arrows and escaped titles');
  await page.screenshot({ path: `${evidence}/relationship-graph.png` });
  await dialog.getByRole('button', { name: 'Next relationships', exact: true }).click();
  await dialog.getByText('Edges 13–16 of 16', { exact: true }).waitFor();
  assert.equal(await dialog.locator('.graph-neighbors button').count(), 4);
  assert.equal(await dialog.getByRole('button', { name: 'Next relationships', exact: true }).isDisabled(), true);
  await dialog.getByRole('button', { name: 'Previous relationships', exact: true }).click();
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  cases.push('bounded edge pages cover all neighbors');
  const prerequisite = dialog.getByRole('button', { name: 'T2 · Prerequisite <script>literal</script>', exact: true });
  await prerequisite.focus(); await page.keyboard.press('Enter');
  const recentered = page.getByRole('dialog', { name: 'Relationships · T2', exact: true });
  await recentered.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
  assert.equal(await recentered.getByText('T2 Blocks T1', { exact: true }).count(), 1);
  await recentered.getByRole('button', { name: 'Back in graph', exact: true }).click();
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  await dialog.getByRole('combobox', { name: 'Graph relationships', exact: true }).selectOption('all');
  await dialog.getByText('Edges 1–12 of 17', { exact: true }).waitFor();
  assert.equal(await dialog.getByText('T1 RelatesTo T2', { exact: true }).count(), 1);
  assert.equal(await dialog.locator('svg line:not([marker-end])').count(), 1, 'symmetric relationships have no directed arrow');
  cases.push('keyboard recentering, back navigation and labelled symmetric relationships');
  await page.setViewportSize({ width: 640, height: 800 });
  const layout = await dialog.locator('.dialog-body').evaluate(node => ({ width: node.clientWidth, scroll: node.scrollWidth }));
  assert.ok(layout.scroll <= layout.width + 1, 'graph fits a narrow viewport');
  await page.screenshot({ path: `${evidence}/relationship-graph-narrow.png` });
  await dialog.getByRole('button', { name: 'T4 · Related work 4', exact: true }).click();
  const dependent = page.getByRole('dialog', { name: 'Relationships · T4', exact: true });
  await dependent.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
  await dependent.getByRole('button', { name: 'Open item', exact: true }).click();
  await page.getByRole('heading', { name: 'T4 · Related work 4', exact: true }).waitFor();
  await dependent.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
  await dependent.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
  armed = true; await dependent.getByRole('button', { name: 'Refresh graph', exact: true }).click();
  let timeout;
  try { await Promise.race([captured, new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Missing held graph reply')), 10000); })]); }
  finally { clearTimeout(timeout); }
  await dependent.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: 'T1 · Selected work', exact: true }).click();
  await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  held();
  await page.waitForTimeout(100);
  assert.equal(await dialog.getByText('Edges 1–12 of 16', { exact: true }).count(), 1);
  assert.equal(await dialog.locator('[role=alert]').isVisible(), false);
  cases.push('a late reply from a closed graph cannot overwrite a newly centered graph');
  assert.deepEqual(await detail(1), before, 'graph reads must preserve ledger revisions and references');
  assert.deepEqual(errors, []);
  cases.push('narrow layout, open main item and read-only ledger behavior');
  await context.tracing.stop({ path: `${evidence}/relationship-graph-trace.zip` });
  await writeFile(`${evidence}/relationship-graph-results.json`, JSON.stringify({ cases, errors }, null, 2));
  console.log(JSON.stringify({ cases, errors })); await context.close();
} finally { await browser.close(); }
