import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import { hold, HOLD_SETTLE_MS } from './hold.mjs';
const bundle = await build({ stdin: { contents: `export * as api from './generated/typescript/cq/api/index.js';
export { QuestionBatch } from './web/src/questions.js'; export { itemView } from './web/src/presentation.js';
export { ReferencePopup } from './web/src/references.js'; export { RequirementsDialog } from './web/src/requirements.js';
export { ModeDialog } from './web/src/mode.js';`,
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
  if (process.argv.includes('--question-options')) {
    await page.evaluate(() => {
      const { api, QuestionBatch, ReferencePopup, itemView } = CQComponents;
      const uuid = '00000000-0000-4000-8000-000000000001';
      const project = new api.ProjectId(uuid), id = new api.ItemId(project, api.Ledger.Questions, 1n), revision = new api.Revision(1n);
      const alternatives = ['Relax planning and phase order; retain isolated Workers, independent review and host validation.',
        '**First paragraph.**\n\nSecond paragraph.\n\n- Preserve this list'];
      const draft = new api.ItemDraft('Choice layout', '', new Set(), false,
        new api.Content_Question(api.QuestionStatus.Open, 'Choose a mode', '', alternatives,
          new api.QuestionRecommendation(0, 'Keep the ledger current.'), undefined), []);
      const item = new api.Item(id, revision, draft, 0n, 0n,
        new api.Provenance(new api.Actor('fixture', new api.SessionId(uuid), api.Role.Governor), 0n, new api.RequestId(uuid)));
      const summary = new api.ItemSummary(id, revision, draft.title, 'Open', false, new Set(), 0n, new api.ItemOutcome(false, false));
      const call = async command => {
        if (command instanceof api.Command_Search) return new api.Result_Found(new api.ItemPage([summary], new api.ChangeCursor(1n), undefined, false));
        if (command instanceof api.Command_Read) return new api.Result_Detail(new api.ItemView(item, []));
        throw new Error('Unexpected fixture command');
      };
      const popup = new ReferencePopup(call);
      const storage = { length: 0, getItem: () => null, setItem: () => {}, key: () => null };
      const questions = new QuestionBatch({ call, view: value => itemView(value.draft, text => popup.render(project, text)),
        committed: async () => { throw new Error('Unexpected commit'); } }, storage);
      document.body.append(questions.dialog.element); questions.open(project);
    });
    const choices = page.locator('.answer-alternative');
    await choices.first().waitFor();
    for (const width of [1366, 1280, 640]) {
      await page.setViewportSize({ width, height: 768 });
      const layout = await choices.first().evaluate(row => {
        const pick = row.querySelector('button').getBoundingClientRect();
        const paragraph = row.querySelector('p');
        const range = document.createRange(); range.selectNodeContents(paragraph);
        const lines = [...range.getClientRects()].filter(rect => rect.width > 0);
        const badge = row.querySelector('.recommended-badge').getBoundingClientRect();
        return { pick: {top: pick.top, bottom: pick.bottom}, lines: lines.map(rect => ({top: rect.top, bottom: rect.bottom})),
          badge: {top: badge.top, bottom: badge.bottom}, text: row.innerText };
      });
      console.log(JSON.stringify({ width, layout }));
      assert.ok(layout.pick.bottom > layout.lines[0].top && layout.lines[0].bottom > layout.pick.top,
        'Pick must share the first text line instead of forcing a separate line');
      if (width >= 1280) assert.ok(layout.badge.bottom > layout.lines.at(-1).top && layout.lines.at(-1).bottom > layout.badge.top,
        'Recommended must share the last text line when there is room');
    }
    const blocks = await choices.nth(1).evaluate(row => {
      const paragraphs = [...row.querySelectorAll(':scope > p')];
      return { strong: row.querySelector('strong').textContent, paragraphs: paragraphs.map(p => p.textContent),
        separated: paragraphs[1].getBoundingClientRect().top > paragraphs[0].getBoundingClientRect().bottom,
        list: row.querySelector('ul li').textContent };
    });
    assert.deepEqual(blocks, { strong: 'First paragraph.', paragraphs: ['First paragraph.', 'Second paragraph.'], separated: true, list: 'Preserve this list' });
    await choices.nth(1).getByRole('button').click();
    assert.equal(await page.getByRole('textbox', {name: 'Answer', exact: true}).inputValue(), '**First paragraph.**\n\nSecond paragraph.\n\n- Preserve this list');
    console.log('PASS: choice controls share text lines; intentional Markdown blocks and Pick behavior remain intact');
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
  if (process.argv.includes('--mode')) {
    // I30: the catalog decides which modes can be chosen. With a catalog in which the YOLO mode is available, choosing it replaces the
    // ordinary save by the hold-to-confirm control; a release that makes the mode available needs no change of the dialog.
    await page.evaluate(() => {
      const { api, ModeDialog } = CQComponents;
      const project = new api.ProjectId('00000000-0000-0000-0000-000000000001');
      const prompt = new api.CatalogPrompt('fixture.md', 'Fixture instructions');
      const catalog = new api.HelpCatalog([], [], [
        new api.CatalogMode(api.ProcessMode.Rigorous, 'Rigorous', 'Rigorous hint', 'Rigorous description', prompt, undefined),
        new api.CatalogMode(api.ProcessMode.CrossCutting, 'Cross-cutting', 'Cross-cutting hint', 'Cross-cutting description', prompt, undefined),
        new api.CatalogMode(api.ProcessMode.Yolo, 'YOLO cross-cutting', 'YOLO hint', 'YOLO description', prompt, undefined)], 'Fixture effect.');
      const state = { value: new api.ProjectMode(project, new api.Revision(0n), api.ProcessMode.Rigorous, false, undefined), replaced: [], saved: [] };
      const dialog = new ModeDialog({ catalog: async () => catalog, saved: (value, changed) => { state.saved.push([value.mode, changed]); }, call: async command => {
        if (command.input.action instanceof api.ModeAction_Replace) {
          state.replaced.push(command.input.action.mode);
          state.value = new api.ProjectMode(project, new api.Revision(command.input.action.expected.value + 1n), command.input.action.mode, command.input.action.selfReviewWithoutChecks, undefined);
        }
        return new api.Result_Mode(state.value);
      }});
      document.body.append(dialog.element);
      window.fixture = { dialog, project, state };
      dialog.open(project);
    });
    const save = page.getByRole('button', { name: 'Save mode', exact: true });
    const confirm = page.getByRole('button', { name: 'Switch to YOLO cross-cutting', exact: true });
    const radio = name => page.getByRole('radio', { name, exact: true });
    await radio('Rigorous').waitFor();
    assert.deepEqual([await radio('Rigorous').isChecked(), await radio('YOLO cross-cutting').isDisabled(), await save.isVisible(), await confirm.isVisible()], [true, false, true, false]);
    await radio('Cross-cutting').check();
    assert.deepEqual([await save.isVisible(), await confirm.isVisible()], [true, false]);
    await radio('YOLO cross-cutting').check();
    assert.deepEqual([await save.isVisible(), await confirm.isVisible(), await confirm.getAttribute('data-hold')], [false, true, 'idle']);
    // A click is not a hold: nothing is sent.
    await confirm.click(); await page.waitForTimeout(HOLD_SETTLE_MS);
    assert.deepEqual(await page.evaluate(() => fixture.state.replaced), []);
    await hold(page, confirm);
    await page.waitForFunction(() => fixture.state.saved.length === 1);
    assert.deepEqual(await page.evaluate(() => [fixture.state.replaced, fixture.state.saved]), [['Yolo'], [['Yolo', true]]]);
    // In the YOLO mode the choice of another mode, and a save of the same one, is an ordinary save.
    await save.waitFor();
    assert.deepEqual([await radio('YOLO cross-cutting').isChecked(), await confirm.isVisible()], [true, false]);
    await radio('Rigorous').check(); await save.click();
    await page.waitForFunction(() => fixture.state.saved.length === 2);
    assert.deepEqual(await page.evaluate(() => fixture.state.replaced), ['Yolo', 'Rigorous']);
    console.log('PASS: a mode the catalog makes available is chosen with the ordinary save, except the YOLO mode, which is saved by holding');
  }
} finally { await browser.close(); }
