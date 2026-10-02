import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

// Q32: the operator edits the project's standing requirements in the browser under revision comparison.
export async function requirementsChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function call(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const project = { value: randomUUID() };
  const stored = async () => (await call({ Requirements: { input: { project, action: { Read: {} } } } })).Requirements.value;
  await call({ Initialize: { config: { project, endpoint: origin, name: `Requirements ${project.value}` } } });
  const context = await browser.newContext({ storageState }); const cases = []; const errors = [];
  const page = await context.newPage();
  page.setDefaultTimeout(7000); page.on('pageerror', error => errors.push(String(error)));
  const dialog = page.getByRole('dialog', { name: 'Standing requirements', exact: true });
  const editor = dialog.getByLabel('Standing requirements', { exact: true });
  const save = dialog.getByRole('button', { name: 'Save requirements', exact: true });
  async function open() {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByText('Data: current', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Standing requirements', exact: true }).click();
  }
  try {
    await open();
    await dialog.getByText('No standing requirements have been saved for this project.', { exact: true }).waitFor();
    assert.equal(await editor.inputValue(), '');
    const first = 'Every change carries a focused test.\nNo release gate runs in a worker workspace.';
    await editor.fill(first); await save.click();
    await dialog.getByText(/^Revision 1 · operator · /).waitFor();
    await page.getByText('Standing requirements saved at revision 1.', { exact: true }).waitFor();
    assert.deepEqual([(await stored()).text, (await stored()).revision.value], [first, '1']);
    cases.push('a multi-line text is saved at revision 1 and shows who changed it and when');
    await open();
    await dialog.getByText(/^Revision 1 · operator · /).waitFor();
    assert.equal(await editor.inputValue(), first);
    cases.push('a reload shows the saved text and its revision');
    await call({ Requirements: { input: { project, action: { Replace: { expected: { value: '1' }, text: 'Changed elsewhere.' } } } } });
    await editor.fill('My later text.'); await save.click();
    await dialog.getByRole('heading', { name: 'Edit conflict', exact: true }).waitFor();
    await dialog.getByText('Your text is based on revision 1; the current revision is 2.', { exact: false }).waitFor();
    assert.equal(await dialog.getByLabel('Current standing requirements', { exact: true }).textContent(), 'Changed elsewhere.');
    assert.equal(await editor.inputValue(), 'My later text.');
    assert.deepEqual([(await stored()).text, (await stored()).revision.value], ['Changed elsewhere.', '2']);
    cases.push('a save from a stale revision changes nothing and shows the current text beside the kept draft');
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name: 'Standing requirements', exact: true }).click();
    assert.equal(await editor.inputValue(), 'My later text.');
    await dialog.getByRole('button', { name: 'Use current revision as base', exact: true }).click();
    await dialog.getByText(/^Revision 2 · operator · /).waitFor();
    await save.click();
    await dialog.getByText(/^Revision 3 · operator · /).waitFor();
    assert.equal(await dialog.getByRole('heading', { name: 'Edit conflict', exact: true }).count(), 0);
    assert.deepEqual([(await stored()).text, (await stored()).revision.value], ['My later text.', '3']);
    cases.push('the unsaved text survives closing the dialog, and saving on the current revision replaces the text');
    await editor.fill('x'.repeat(8193)); await save.click();
    await dialog.getByRole('alert').getByText('Standing requirements exceed 8192 code points: 8193 supplied', { exact: false }).waitFor();
    assert.equal((await stored()).revision.value, '3');
    cases.push('an oversized text is refused with the server bound and stores nothing');
    assert.deepEqual(errors, []);
  } finally {
    await writeFile(`${evidence}/requirements-results.json`, JSON.stringify({ cases, errors }, null, 2));
    await page.screenshot({ path: `${evidence}/requirements.png`, fullPage: true }); await context.close();
  }
}
