import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
const bundle = await build({ stdin: { contents: `export * as api from './generated/typescript/cq/api/index.js';
export { ReferencePopup } from './web/src/references.js'; export { RequirementsDialog } from './web/src/requirements.js';`,
  resolveDir: process.cwd() }, bundle: true, format: 'iife', globalName: 'CQComponents', write: false });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.setContent('<html><body></body></html>');
  await page.addStyleTag({ content: await readFile('web/style.css', 'utf8') });
  await page.addScriptTag({ content: bundle.outputFiles[0].text });
  if (process.argv.includes('--markdown')) {
    const actual = await page.evaluate(() => {
      const { api, ReferencePopup } = CQComponents;
      const popup = new ReferencePopup(async () => { throw new Error('Unexpected call'); });
      const container = document.createElement('div');
      container.className = 'field-value';
      container.append(popup.render(new api.ProjectId('00000000-0000-0000-0000-000000000001'),
        '# Heading\n\n**Bold** and *emphasis* referencing D127.\n\n- First\n- Second\n\n`D70`\n\n```text\nD123\n```\n\n[Guide](https://example.com/D1)\n\n<script>window.injected=true</script>\n\n[Unsafe](javascript:alert(1))'));
      document.body.append(container);
      return { heading: container.querySelector('h1')?.textContent, strong: container.querySelector('strong')?.textContent,
        emphasis: container.querySelector('em')?.textContent, list: container.querySelectorAll('ul > li').length,
        code: [...container.querySelectorAll('code')].map(node => node.textContent.trim()),
        references: [...container.querySelectorAll('button.item-reference')].map(node => node.textContent),
        guide: container.querySelector('a')?.getAttribute('href'), script: container.querySelectorAll('script').length,
        unsafe: container.querySelectorAll('[href^="javascript:"]').length };
    });
    assert.deepEqual(actual, { heading: 'Heading', strong: 'Bold', emphasis: 'emphasis', list: 2,
      code: ['D70', 'D123'], references: ['D127'], guide: 'https://example.com/D1', script: 0, unsafe: 0 });
    if (process.env.CQ_BROWSER_EVIDENCE !== undefined) await page.screenshot({ path: process.env.CQ_BROWSER_EVIDENCE + '/markdown-components.png', fullPage: true });
    console.log('PASS: Markdown formatting, code/reference boundaries and inert unsafe content');
  }
  if (process.argv.includes('--requirements')) {
    await page.evaluate(() => {
      const { api, RequirementsDialog } = CQComponents;
      const a = new api.ProjectId('00000000-0000-0000-0000-000000000001');
      const b = new api.ProjectId('00000000-0000-0000-0000-000000000002');
      const values = new Map([[a.value, new api.ProjectRequirements(a, new api.Revision(1n), 'Original A', undefined)],
        [b.value, new api.ProjectRequirements(b, new api.Revision(1n), 'Original B', undefined)]]);
      let release;
      const dialog = new RequirementsDialog({ saved: () => {}, call: async command => {
        if (command.input.action instanceof api.RequirementsAction_Read) return new api.Result_Requirements(values.get(command.input.project.value));
        if (command.input.project.value === a.value && release === undefined) await new Promise(resolve => { release = resolve; });
        if (command.input.action.expected.value !== values.get(command.input.project.value).revision.value)
          return new api.Result_Failed(new api.Fault_Conflict('Stale requirements revision'));
        const value = new api.ProjectRequirements(command.input.project, new api.Revision(command.input.action.expected.value + 1n), command.input.action.text, undefined);
        values.set(value.project.value, value); return new api.Result_Requirements(value);
      }});
      document.body.append(dialog.element);
      window.fixture = { dialog, a, b, release: () => release(), values };
      dialog.open(a);
    });
    const editor = page.getByRole('textbox', { name: 'Standing requirements', exact: true });
    await editor.fill('Saved A');
    await page.getByRole('button', { name: 'Save requirements', exact: true }).click();
    await page.evaluate(() => fixture.dialog.open(fixture.b));
    await editor.fill('Dirty B');
    await page.evaluate(() => fixture.release());
    await page.waitForFunction(() => fixture.values.get(fixture.a.value).text === 'Saved A');
    await page.evaluate(() => fixture.dialog.open(fixture.a));
    assert.equal(await editor.inputValue(), 'Saved A');
    await page.getByRole('button', { name: 'Save requirements', exact: true }).click();
    await page.waitForTimeout(100);
    assert.equal(await page.getByRole('heading', { name: 'Edit conflict', exact: true }).count(), 0);
    assert.equal(await page.evaluate(() => fixture.values.get(fixture.a.value).revision.value.toString()), '3');
    console.log('PASS: requirements save completion updates its project draft across a project switch');
  }
} finally { await browser.close(); }
