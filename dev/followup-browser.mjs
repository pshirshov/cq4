// Behavioral-Active Blackbox Good-Communication; D51–D57 and I3/I4.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function call(command) {
  const response = await fetch(origin + '/api/call', {method: 'POST', headers, body: JSON.stringify(command)});
  assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
}
const project = {value: randomUUID()};
await call({Initialize: {config: {project, endpoint: origin, name: 'UI follow-up'}}});
const draft = {title: 'Reference source', body: 'See D2 and Q1. Not XD2 or D02 or https://example.org/D2/path.', labels: [], archived: false, citations: [],
  content: {Defect: {status: 'Open', severity: 'Medium', observed: 'UI report', expected: 'Usable UI', reproduction: 'Inspect', cause: null, resolution: []}}};
const change = mutations => call({Change: {input: {project, change: {request: {value: randomUUID()}, mutations, fences: [], reason: 'UI follow-up fixture'}}}});
await change(Array.from({length: 60}, (_, i) => ({Create: {draft: {...draft, title: i === 0 ? draft.title : `Report ${i + 1}`}}})));
const browser = await chromium.launch({headless: true});
const context = await browser.newContext({viewport: {width: 1366, height: 768}});
const page = await context.newPage(); page.setDefaultTimeout(6000);
const cases = [], errors = []; page.on('pageerror', error => errors.push(String(error)));
await context.tracing.start({screenshots: true, snapshots: true, sources: true});
const current = () => page.getByText('Data: current', {exact: true}).waitFor();
const table = page.getByRole('table', {name: 'Items', exact: true});
const dialog = () => page.getByRole('dialog', {name: 'New item', exact: true});
async function check(name, body) { await body(); cases.push(name); }
try {
  await page.goto(origin); await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
  await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
  await page.getByLabel('Project', {exact: true}).selectOption(project.value); await current();
  const query = page.getByRole('combobox', {name: 'Search query', exact: true});
  await check('D51 first current completion accepts Enter; arrows and raw submission remain available', async () => {
    await query.fill('ledger:d'); await page.getByRole('option', {name: 'decisions · Value', exact: true}).waitFor();
    assert.equal(await page.getByRole('listbox', {name: 'Query suggestions'}).getByRole('option', {selected: true}).textContent(), 'decisions · Value');
    await query.press('Enter'); assert.equal(await query.inputValue(), 'ledger:decisions');
    await query.fill('ledger:d'); await page.getByRole('option', {name: 'decisions · Value', exact: true}).waitFor();
    await query.press('ArrowDown'); await query.press('Enter'); assert.equal(await query.inputValue(), 'ledger:defects');
    await query.fill('ledger:d'); await page.getByRole('option', {name: 'decisions · Value', exact: true}).waitFor();
    await query.press('Escape'); await query.press('Enter'); await page.getByText('Data: invalid query', {exact: true}).waitFor();
    await query.fill('ledger:Defects'); await page.getByRole('button', {name: 'Search', exact: true}).click(); await current();
  });
  await check('D52 mouse and keyboard resize without sorting; D59 temporary overrides reset on reload', async () => {
    await page.getByRole('button', {name: 'Dock detail below', exact: true}).click();
    await page.waitForTimeout(60);
    const handle = table.getByRole('separator', {name: 'Resize Status column', exact: true});
    const before = Number(await handle.getAttribute('aria-valuenow'));
    const bounds = await handle.boundingBox(); assert.ok(bounds);
    await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2); await page.mouse.down();
    await page.mouse.move(bounds.x + bounds.width / 2 + 45, bounds.y + bounds.height / 2); await page.mouse.up();
    assert.equal(Number(await handle.getAttribute('aria-valuenow')), before + 45);
    await handle.press('ArrowRight'); assert.equal(Number(await handle.getAttribute('aria-valuenow')), before + 61);
    assert.equal(await table.getByRole('columnheader').first().getAttribute('aria-sort'), 'ascending');
    await page.reload(); await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
    await page.getByLabel('Project', {exact: true}).selectOption(project.value); await current();
    await page.waitForFunction(width => Number(document.querySelector('[aria-label="Resize Status column"]').getAttribute('aria-valuenow')) === width, before);
  });
  await check('D53 modified date and time occupy one line', async () => {
    const tops = await table.locator('tbody time').first().evaluate(node => [...node.children].map(c => c.getBoundingClientRect().top));
    assert.equal(tops.length, 2); assert.equal(tops[0], tops[1]);
  });
  await check('D54 count inside search; headers fixed from the first pixel of scrolling', async () => {
    assert.match(await page.locator('.query-field [role=status]').textContent(), /items/);
    const pane = page.getByRole('region', {name: 'Results', exact: true});
    const header = table.getByRole('columnheader').first(); const first = await header.boundingBox();
    for (const offset of [1, 50, 150]) {
      await pane.evaluate((node, value) => {node.scrollTop = value;}, offset);
      await page.waitForFunction(value => document.getElementById('results-pane').scrollTop === value, offset);
      assert.equal((await header.boundingBox()).y, first.y);
    }
    await pane.evaluate(node => {node.scrollTop = 0; node.scrollLeft = 0;});
  });
  await check('I3 quick types retain common text and use valid initial states', async () => {
    await page.getByRole('button', {name: 'New item', exact: true}).click();
    await dialog().getByLabel('title', {exact: true}).fill('Rapid entry');
    await dialog().getByLabel('body', {exact: true}).fill('Shared draft content');
    for (const [kind, status] of [['Idea', 'Proposed'], ['Goal', 'Open'], ['Defect', 'Open'], ['Idea', 'Proposed']]) {
      await dialog().getByRole('button', {name: `Create ${kind}`, exact: true}).click();
      assert.equal(await dialog().getByLabel('content', {exact: true}).inputValue(), kind);
      assert.equal(await dialog().getByLabel('status', {exact: true}).inputValue(), status);
      assert.equal(await dialog().getByLabel('body', {exact: true}).inputValue(), 'Shared draft content');
      assert.equal(await dialog().getByRole('button', {name: `Create ${kind}`, exact: true}).getAttribute('aria-pressed'), 'true');
    }
  });
  await check('I4 button and shortcut create distinct items and retain type and focus', async () => {
    for (const [title, keyboard] of [['Rapid entry', false], ['Next entry', true]]) {
      await dialog().getByLabel('title', {exact: true}).fill(title);
      await dialog().getByLabel('outcome', {exact: true}).fill('Easier intake');
      await dialog().getByLabel('motivation', {exact: true}).fill('Many ideas');
      if (keyboard) await dialog().getByLabel('title', {exact: true}).press('Control+Enter');
      else await dialog().getByRole('button', {name: 'Save item and create next', exact: true}).click();
      await page.waitForFunction(() => document.querySelector('dialog[open] textarea[aria-label=title]')?.value === '');
      assert.equal(await dialog().getByLabel('content', {exact: true}).inputValue(), 'Idea');
      assert.equal(await dialog().getByLabel('title', {exact: true}).evaluate(node => node === document.activeElement), true);
    }
    const saved = await call({Search: {input: {project, query: 'ledger:Ideas', after: null, snapshot: null, limit: 20}}});
    assert.equal(saved.Found.page.items.length, 2);
    await dialog().getByRole('button', {name: 'Cancel edit', exact: true}).click();
  });
  await check('D55 save and draft notifications stay outside item content after navigation', async () => {
    assert.equal(await page.locator('#detail-pane').getByText('Saved', {exact: true}).count(), 0);
    await page.getByRole('button', {name: 'New item', exact: true}).click();
    await dialog().getByLabel('title', {exact: true}).fill('Retained local draft');
    await dialog().getByRole('button', {name: 'Cancel edit', exact: true}).click();
    await page.getByRole('button', {name: 'New item', exact: true}).click();
    await page.locator('.notification-toast').getByText(/restored your local draft/).waitFor();
    await dialog().getByRole('button', {name: 'Cancel edit', exact: true}).click();
    await table.getByRole('button', {name: 'D1 · Reference source', exact: true}).click();
    await page.locator('#detail-pane').getByRole('heading', {name: 'D1 · Reference source', exact: true}).waitFor();
    assert.doesNotMatch(await page.locator('#detail-pane').textContent(), /restored your local draft|Saved I/);
  });
  assert.deepEqual(errors, []);
  await page.screenshot({path: evidence + '/followup.png', fullPage: true});
} finally {
  await writeFile(evidence + '/followup-results.json', JSON.stringify({cases, errors}, null, 2));
  await context.tracing.stop({path: evidence + '/followup-trace.zip'}); await browser.close();
}
