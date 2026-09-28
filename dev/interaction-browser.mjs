import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

export async function interactionChecks(browser, storageState, origin, evidence) {
  const context = await browser.newContext({ storageState });
  const page = await context.newPage(); const cases = []; const failures = []; const errors = [];
  page.setDefaultTimeout(5000); page.on('pageerror', error => errors.push(String(error)));
  async function check(name, effect) {
    try { await effect(); cases.push(name); }
    catch (error) { failures.push({ name, error: String(error) }); }
  }
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    const name = `Interactions ${randomUUID()}`;
    await page.getByLabel('New project name').fill(name);
    await page.getByRole('button', { name: 'Create project', exact: true }).click();
    await page.getByLabel('Project', { exact: true }).getByRole('option', { name, exact: true }).waitFor({ state: 'attached' });
    await page.getByText('Data: current', { exact: true }).waitFor();
    await check('hover, pointer transfer, keyboard access and Escape dismissal', async () => {
      const indicator = page.locator('header summary');
      const diagnostics = page.getByRole('region', { name: 'Connection diagnostics', exact: true });
      await indicator.hover(); await diagnostics.waitFor();
      await page.getByRole('button', { name: 'Retry connection', exact: true }).hover();
      assert.equal(await diagnostics.isVisible(), true);
      await page.getByRole('button', { name: 'Search', exact: true }).hover();
      await diagnostics.waitFor({ state: 'hidden' });
      await indicator.focus(); await diagnostics.waitFor();
      await page.keyboard.press('Tab');
      assert.equal(await page.getByRole('button', { name: 'Retry connection', exact: true }).evaluate(node => node === document.activeElement), true);
      await page.keyboard.press('Escape'); await diagnostics.waitFor({ state: 'hidden' });
      assert.equal(await indicator.evaluate(node => node === document.activeElement), true);
      await page.getByLabel('Search query').focus();
    });
    await check('Task to Idea stores a valid draft without transient decoder failures', async () => {
      await page.getByRole('button', { name: 'New item', exact: true }).click();
      await page.getByLabel('title', { exact: true }).fill('Persisted Idea');
      await page.getByLabel('content', { exact: true }).selectOption('Idea');
      const alerts = await page.getByRole('alert').allTextContents();
      assert.equal(alerts.some(text => text.includes('Draft storage failed')), false, JSON.stringify(alerts));
      assert.equal(await page.getByLabel('status', { exact: true }).inputValue(), 'Proposed');
      const project = await page.getByLabel('Project', { exact: true }).inputValue();
      const draft = await page.evaluate(id => JSON.parse(localStorage.getItem(`cq-draft:${id}:new`)), project);
      assert.equal(draft.value.content.Idea.status, 'Proposed');
      await page.reload(); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
      await page.getByLabel('Project', { exact: true }).selectOption(project);
      await page.getByRole('button', { name: 'New item', exact: true }).click();
      assert.equal(await page.getByLabel('content', { exact: true }).inputValue(), 'Idea');
      assert.equal(await page.getByLabel('title', { exact: true }).inputValue(), 'Persisted Idea');
    });
    await check('partial ledger values show completions before diagnostics', async () => {
      const query = page.getByLabel('Search query');
      await query.fill('ledger:t');
      await page.getByRole('option', { name: 'tasks · Value', exact: true }).waitFor();
      assert.equal(await query.getAttribute('aria-invalid'), null);
      assert.equal(await page.locator('.query-diagnostic').isVisible(), false);
      await query.press('ArrowDown'); await query.press('Enter');
      assert.equal(await query.inputValue(), 'ledger:tasks');
      await query.fill('ledger:nonexistent'); await page.getByRole('button', { name: 'Search', exact: true }).click();
      await page.getByText('Data: invalid query', { exact: true }).waitFor();
      assert.equal(await query.getAttribute('aria-invalid'), 'true');
    });
    assert.deepEqual(errors, []); assert.deepEqual(failures, []);
    console.log('Browser interactions: hover/focus diagnostics, valid Idea draft and contextual ledger completion passed');
  } finally {
    await writeFile(`${evidence}/interaction-results.json`, JSON.stringify({ cases, failures, errors }, null, 2));
    await page.screenshot({ path: `${evidence}/interactions.png`, fullPage: true }); await context.close();
  }
}
