import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
const bundle = await build({ stdin: { contents: `export * as api from './generated/typescript/cq/api/index.js'; export { DriversDialog } from './web/src/drivers.js';`, resolveDir: process.cwd() }, bundle: true, format: 'iife', globalName: 'CQDrivers', write: false });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page.setContent('<html><body></body></html>');
  await page.addStyleTag({ content: await readFile('web/style.css', 'utf8') });
  await page.addScriptTag({ content: bundle.outputFiles[0].text });
  await page.evaluate(() => {
    const { api, DriversDialog } = CQDrivers;
    const project = new api.ProjectId('00000000-0000-4000-8000-000000000001');
    const dialog = new DriversDialog({ call: async command => {
      if (command instanceof api.Command_Driver) return new api.Result_Driver(new api.DriverReply_Listed([]));
      if (command.input.action instanceof api.WorksetAction_BrowseSaved) return new api.Result_WorksetsListed(new api.StoredWorksetPage([], undefined, false));
      throw new Error('Unexpected fixture command');
    }, filter: async () => { throw new Error('Unexpected filter'); }, view: () => { throw new Error('Unexpected view'); } });
    document.body.append(dialog.element); dialog.open(project, null);
  });
  if (process.env.CQ_BROWSER_EVIDENCE !== undefined) await page.screenshot({ path: `${process.env.CQ_BROWSER_EVIDENCE}/drivers-layout.png` });
  assert.equal(await page.getByRole('heading', { name: 'Running drives', exact: true }).count(), 1, 'operators need a distinct Running drives section');
  assert.equal(await page.getByRole('heading', { name: 'Saved worksets', exact: true }).count(), 1, 'saved scopes must be discoverable');
  assert.equal(await page.getByRole('heading', { name: 'Create workset', exact: true }).count(), 1);
  assert.equal(await page.getByLabel('Stored workset ID', { exact: true }).count(), 0, 'ordinary selection must not require a UUID');
  await page.setViewportSize({ width: 640, height: 800 });
  const layout = await page.locator('.dialog-body').evaluate(node => ({ width: node.clientWidth, scroll: node.scrollWidth }));
  assert.ok(layout.scroll <= layout.width + 1, 'the dialog must fit a narrow viewport without horizontal scrolling');
  console.log('PASS: separate drive, saved-scope and creation flows; no UUID input');
} finally { await browser.close(); }
