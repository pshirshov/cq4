import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

export async function catalogueChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function call(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() }; const added = { value: randomUUID() };
  await call({ Initialize: { config: { project, endpoint: origin, name: `Catalogue ${project.value}` } } });
  const context = await browser.newContext({ storageState }); const cases = []; const errors = [];
  let arm = false; let heldId = null; let held = null; let latest = 0n;
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (arm && frame.Call && frame.Call.command.Projects) { arm = false; heldId = frame.Call.id.value; }
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (frame.Updated && frame.Updated.revision.catalogue !== null) latest = BigInt(frame.Updated.revision.catalogue.value);
      if (frame.Reply && frame.Reply.id.value === heldId) held = { route, message };
      else route.send(message);
    });
  });
  const page = await context.newPage();
  async function until(predicate) {
    const deadline = Date.now() + 7000;
    while (!predicate()) { assert.ok(Date.now() < deadline, 'Expected catalogue watch event'); await new Promise(resolve => setTimeout(resolve, 20)); }
  }
  page.setDefaultTimeout(7000); page.on('pageerror', error => errors.push(String(error)));
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    const projects = page.getByLabel('Project', { exact: true }); await projects.selectOption(project.value);
    await page.getByText('Data: current', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'New item', exact: true }).click();
    await page.getByLabel('title', { exact: true }).fill('Unsaved across catalogue updates');
    await page.getByLabel('Search query').fill('alpha AND'); await page.getByRole('button', { name: 'Search', exact: true }).click();
    await page.getByText('Data: invalid query', { exact: true }).waitFor();
    const name = `External ${added.value}`;
    await call({ Initialize: { config: { project: added, endpoint: origin, name } } });
    await projects.getByRole('option', { name, exact: true }).waitFor({ state: 'attached' });
    assert.equal(await projects.inputValue(), project.value);
    await call({ RenameProject: { project, expected: { value: '1' }, name: 'Renamed selected project' } });
    await projects.getByRole('option', { name: 'Renamed selected project', exact: true }).waitFor({ state: 'attached' });
    assert.equal(await projects.inputValue(), project.value);
    assert.equal(await page.getByLabel('title', { exact: true }).inputValue(), 'Unsaved across catalogue updates');
    assert.equal(await page.getByText('Data: invalid query', { exact: true }).count(), 1);
    cases.push('external create and rename update the catalogue while retaining selection, local draft and invalid query');
    arm = true;
    await call({ RenameProject: { project, expected: { value: '2' }, name: 'Held catalogue revision' } });
    await until(() => held !== null);
    await call({ RenameProject: { project, expected: { value: '3' }, name: 'Latest catalogue revision' } });
    const now = await call({ Projects: { after: null, snapshot: null, limit: 1 } });
    await until(() => latest >= BigInt(now.Projects.page.cursor.value));
    held.route.send(held.message); held = null; heldId = null;
    await projects.getByRole('option', { name: 'Latest catalogue revision', exact: true }).waitFor({ state: 'attached' });
    assert.equal(await projects.inputValue(), project.value);
    assert.equal(await page.getByLabel('title', { exact: true }).inputValue(), 'Unsaved across catalogue updates');
    cases.push('a held catalogue response catches up newer notifications without replacing selection or draft');
    await context.setOffline(true); await page.getByText('Data: stale', { exact: true }).waitFor();
    await call({ RenameProject: { project: added, expected: { value: '1' }, name: 'Renamed while disconnected' } });
    await context.setOffline(false);
    await projects.getByRole('option', { name: 'Renamed while disconnected', exact: true }).waitFor({ state: 'attached' });
    assert.equal(await projects.inputValue(), project.value);
    cases.push('reconnection catches up catalogue changes'); assert.deepEqual(errors, []);
  } finally {
    await writeFile(`${evidence}/catalogue-results.json`, JSON.stringify({ cases, errors }, null, 2));
    await page.screenshot({ path: `${evidence}/catalogue.png`, fullPage: true }); await context.close();
  }
}
