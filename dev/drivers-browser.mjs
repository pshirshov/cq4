import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

export async function driversChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function call(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  await call({ Initialize: { config: { project, endpoint: origin, name: `Drivers ${project.value}` } } });
  async function create(title) {
    return (await call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Workset browser fixture', mutations: [
      { Create: { draft: { title, body: '', labels: [], archived: false, citations: [], content: { Task: { status: 'Ready', acceptance: ['Observed'], result: null, validation: [] } } } } }
    ] } } } })).Changed.ack.items[0].id;
  }
  const first = await create('First'); await create('Outside');
  const key = { harness: 'Claude', session: `browser-${randomUUID()}` };
  await call({ Driver: { input: { project, request: { Control: { key, origin: 'UserPromptSubmit', action: { Start: { target: { Inline: { targets: [first], through: 'Work' } }, attached: null } } } } } } });
  let drift = false;
  const context = await browser.newContext({ storageState });
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (drift && frame.Call?.command.Search?.input.after != null) {
        drift = false;
        void create('Query snapshot moves').then(() => server.send(message));
      } else server.send(message);
    });
    server.onMessage(message => route.send(message));
  }); const page = await context.newPage(); const errors = [];
  page.on('pageerror', error => errors.push(String(error))); page.setDefaultTimeout(7000);
  const cases = [];
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value); await page.getByText('Data: current', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Drivers and worksets', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Drivers and worksets', exact: true });
    await dialog.locator('.drive-card').getByText('Binding', { exact: true }).waitFor();
    cases.push('committed drivers appear with their binding state');
    await dialog.getByLabel('Workset targets', { exact: true }).fill('T1');
    await dialog.getByRole('button', { name: 'Preview targets', exact: true }).click();
    await dialog.getByRole('table', { name: 'Workset members', exact: true }).getByText('Advanceable', { exact: true }).waitFor();
    await dialog.getByRole('button', { name: 'Store previewed workset', exact: true }).click();
    await dialog.getByText('Workset saved. Choose it again under Saved worksets.', { exact: true }).waitFor();
    const saved = (await call({ Workset: { input: { project, action: { BrowseSaved: { after: null, limit: 10 } } } } })).WorksetsListed.page.entries;
    const workset = saved[0].id.value;
    await dialog.locator('.workset-card').getByRole('button', { name: 'T1 · First', exact: true }).waitFor();
    assert.equal(await dialog.getByLabel('Stored workset ID', { exact: true }).count(), 0);
    await dialog.getByRole('button', { name: 'Filter items to workset', exact: true }).click();
    await page.getByText('Data: current', { exact: true }).waitFor();
    await page.getByText('Workset: T1 · through Integrate', { exact: true }).waitFor();
    assert.equal(await page.locator('.item-row').count(), 1);
    await page.getByRole('button', { name: 'Clear workset filter', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.item-row').length === 2);
    cases.push('previewed targets store and filter to advanceable members, with an explicit clear control');
    await page.getByRole('button', { name: 'Drivers and worksets', exact: true }).click();
    const park = dialog.getByRole('button', { name: `Park Claude ${key.session}`, exact: true });
    await park.click(); await page.waitForTimeout(300);
    assert.equal((await call({ Driver: { input: { project, request: { Summaries: {} } } } })).Driver.reply.Listed.drivers[0].state, 'Binding');
    await park.focus(); await page.keyboard.down('Space'); await page.waitForTimeout(1300); await page.keyboard.up('Space');
    await dialog.locator('.drive-card').getByText('Off', { exact: true }).waitFor();
    cases.push('an ordinary click does not park; holding confirms and refreshes committed state');
    await dialog.locator('.workset-card').getByRole('button', { name: 'T1 · First', exact: true }).click();
    await dialog.getByRole('heading', { name: 'Current stored workset evaluation', exact: true }).waitFor();
    assert.equal(await dialog.getByRole('button', { name: 'Filter items to workset', exact: true }).isEnabled(), true);
    cases.push('a saved scope is chosen by item ID and title without a UUID');
    await dialog.getByLabel('Find items for workset', { exact: true }).fill('Outside');
    await dialog.locator('.workset-matches').getByRole('button', { name: 'T2 · Outside', exact: true }).click();
    assert.equal(await dialog.getByLabel('Workset targets', { exact: true }).inputValue(), 'T2');
    await dialog.getByLabel('Find items for workset', { exact: true }).fill('T1');
    await dialog.locator('.workset-matches').getByRole('button', { name: 'T1 · First', exact: true }).click();
    assert.equal(await dialog.getByLabel('Workset targets', { exact: true }).inputValue(), 'T2 T1');
    cases.push('items are selected by title or exact ID with visible recognizable results');
    for (let index = 0; index < 9; index++) await call({ Workset: { input: { project, action: { Create: { targets: [first], through: 'Plan' } } } } });
    await dialog.getByRole('button', { name: 'Refresh saved worksets', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.workset-card').length === 8);
    await dialog.getByRole('button', { name: 'More saved worksets', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.workset-card').length === 10);
    assert.equal(await dialog.getByRole('button', { name: 'More saved worksets', exact: true }).isVisible(), false);
    cases.push('saved worksets remain discoverable across bounded pages');
    await dialog.getByLabel('Workset target mode', { exact: true }).selectOption('query');
    await dialog.getByLabel('Workset targets', { exact: true }).fill('ledger:Tasks');
    await dialog.getByRole('button', { name: 'Preview targets', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('table[aria-label="Workset members"]')?.querySelectorAll('tr').length === 3);
    cases.push('submitted query results become explicit previewed targets');
    await page.screenshot({ path: `${evidence}/drivers-worksets.png`, fullPage: true });
    for (let offset = 0; offset < 61; offset += 64) {
      const mutations = Array.from({ length: Math.min(64, 61 - offset) }, (_, index) => ({ Create: { draft: {
        title: `Paged ${offset + index}`, body: '', labels: [], archived: false, citations: [],
        content: { Task: { status: 'Ready', acceptance: ['Observed'], result: null, validation: [] } }
      } } }));
      await call({ Change: { input: { project, change: { request: { value: randomUUID() }, fences: [], reason: 'Query page fixture', mutations } } } });
    }
    await dialog.getByRole('button', { name: 'Preview targets', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('table[aria-label="Workset members"]')?.querySelectorAll('tr').length === 64);
    cases.push('query resolution follows every page at one snapshot');
    drift = true;
    await dialog.getByRole('button', { name: 'Preview targets', exact: true }).click();
    await dialog.getByRole('alert').getByText(/Snapshot changed/).waitFor();
    assert.equal(await dialog.getByRole('table', { name: 'Workset members', exact: true }).count(), 0);
    assert.equal(await dialog.getByRole('button', { name: 'Store previewed workset', exact: true }).isDisabled(), true);
    cases.push('a query snapshot that moves between pages is refused instead of mixing targets');

    assert.deepEqual(errors, []);
    await writeFile(`${evidence}/drivers-browser.json`, JSON.stringify({ status: 'passed', cases, project, key }, null, 2));
  } finally { await context.close(); }
}
