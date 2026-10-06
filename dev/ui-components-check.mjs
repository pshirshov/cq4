import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import { hold, HOLD_SETTLE_MS } from './hold.mjs';
const bundle = await build({ stdin: { contents: `export * as api from './generated/typescript/cq/api/index.js';
export { QuestionBatch } from './web/src/questions.js'; export { itemView } from './web/src/presentation.js';
export { ReferencePopup } from './web/src/references.js'; export { RequirementsDialog } from './web/src/requirements.js';
export { ModeDialog, ModeIndicator } from './web/src/mode.js'; export { attemptsTable, unmeasuredNote } from './web/src/usage-view.js';`,
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
    // ordinary save by the hold-to-confirm control, and the exemption from configured checks is a second, separately held decision.
    const exemption = 'Fixture exemption: a change the governing session reviewed itself can be integrated although no check examined it.';
    await page.evaluate(exemption => {
      const { api, ModeDialog, ModeIndicator } = CQComponents;
      const project = new api.ProjectId('00000000-0000-0000-0000-000000000001');
      const prompt = new api.CatalogPrompt('fixture.md', 'Fixture instructions');
      const catalog = new api.HelpCatalog([], [], [
        new api.CatalogMode(api.ProcessMode.Rigorous, 'Rigorous', 'Rigorous hint', 'Rigorous description', prompt, undefined),
        new api.CatalogMode(api.ProcessMode.CrossCutting, 'Cross-cutting', 'Cross-cutting hint', 'Cross-cutting description', prompt, undefined),
        new api.CatalogMode(api.ProcessMode.Yolo, 'YOLO cross-cutting', 'YOLO hint', 'YOLO description\n\n' + exemption, prompt, undefined)], 'Fixture effect.');
      const state = { value: new api.ProjectMode(project, new api.Revision(0n), api.ProcessMode.Rigorous, false, undefined), replaced: [], saved: [] };
      const indicator = new ModeIndicator({ catalog: async () => catalog, saved: () => {}, call: async () => new api.Result_Mode(state.value) }, () => {});
      document.body.append(indicator.element);
      const dialog = new ModeDialog({ catalog: async () => catalog, saved: (value, changed) => { state.saved.push([value.mode, changed]); indicator.show(value, catalog); }, call: async command => {
        if (command.input.action instanceof api.ModeAction_Replace) {
          state.replaced.push([command.input.action.mode, command.input.action.selfReviewWithoutChecks]);
          state.value = new api.ProjectMode(project, new api.Revision(command.input.action.expected.value + 1n), command.input.action.mode, command.input.action.selfReviewWithoutChecks, undefined);
        }
        return new api.Result_Mode(state.value);
      }});
      document.body.append(dialog.element);
      window.fixture = { dialog, project, state };
      dialog.open(project);
    }, exemption);
    const evidence = process.env.CQ_BROWSER_EVIDENCE;
    const shot = async name => { if (evidence !== undefined) await page.getByRole('dialog', { name: 'Process mode', exact: true }).screenshot({ path: `${evidence}/${name}.png` }); };
    const save = page.getByRole('button', { name: 'Save mode', exact: true });
    const confirm = page.getByRole('button', { name: 'Switch to YOLO cross-cutting', exact: true });
    const group = page.getByRole('group', { name: 'Self-review without checks', exact: true });
    const allow = group.getByRole('button', { name: 'Allow self-review without checks', exact: true });
    const withdraw = group.getByRole('button', { name: 'Require a check again', exact: true });
    const radio = name => page.getByRole('radio', { name, exact: true });
    const replaced = () => page.evaluate(() => fixture.state.replaced);
    // The header indicator as the application shows it after each save: its text, its accessible name, its tooltip and its mark.
    const indicated = () => page.locator('button.mode-indicator').evaluate(node => [node.textContent, node.getAttribute('aria-label'), node.title, node.dataset.exempt]);
    const saves = count => page.waitForFunction(expected => fixture.state.saved.length === expected, count);
    await radio('Rigorous').waitFor();
    assert.deepEqual([await radio('Rigorous').isChecked(), await radio('YOLO cross-cutting').isDisabled(), await save.isVisible(), await confirm.isVisible(), await group.isVisible()],
      [true, false, true, false, false]);
    await radio('Cross-cutting').check();
    assert.deepEqual([await save.isVisible(), await confirm.isVisible(), await group.isVisible()], [true, false, false]);
    await radio('YOLO cross-cutting').check();
    assert.deepEqual([await save.isVisible(), await confirm.isVisible(), await confirm.getAttribute('data-hold')], [false, true, 'idle']);
    // The exemption is shown with the YOLO choice, in the catalog's words, and cannot be allowed before the project is in that mode.
    assert.deepEqual([await group.isVisible(), await allow.isDisabled(), await withdraw.isVisible(), await group.getByText(exemption, { exact: true }).isVisible()], [true, true, false, true]);
    await group.getByText('Not allowed. It can be allowed once the project is in this mode.', { exact: true }).waitFor();
    assert.deepEqual(await allow.evaluate(node => node.getAttribute('aria-describedby').split(' ').map(id => document.getElementById(id).textContent)),
      [exemption, 'Not allowed. It can be allowed once the project is in this mode.']);
    // A click is not a hold: nothing is sent.
    await confirm.click(); await page.waitForTimeout(HOLD_SETTLE_MS);
    assert.deepEqual(await replaced(), []);
    await hold(page, confirm);
    await saves(1);
    assert.deepEqual(await page.evaluate(() => [fixture.state.replaced, fixture.state.saved]), [[['Yolo', false]], [['Yolo', true]]]);
    assert.deepEqual(await indicated(), ['Mode: YOLO cross-cutting', 'Process mode: YOLO cross-cutting', 'YOLO hint', 'false']);
    // In the YOLO mode the exemption has its own hold; a click allows nothing.
    await save.waitFor();
    assert.deepEqual([await radio('YOLO cross-cutting').isChecked(), await confirm.isVisible(), await allow.isEnabled(), await allow.getAttribute('data-hold')], [true, false, true, 'idle']);
    await group.getByText('Not allowed for this project.', { exact: true }).waitFor();
    await shot('mode-yolo-checks-required');
    await allow.click(); await page.waitForTimeout(HOLD_SETTLE_MS);
    assert.deepEqual(await replaced(), [['Yolo', false]]);
    await hold(page, allow);
    await saves(2);
    await group.getByText('Allowed for this project.', { exact: true }).waitFor();
    assert.deepEqual([await replaced(), await allow.isVisible(), await withdraw.isVisible(), await group.getAttribute('data-allowed')],
      [[['Yolo', false], ['Yolo', true]], false, true, 'true']);
    // I30: a project that needs no check for a self-reviewed integration says so in the header for as long as that holds.
    assert.deepEqual(await indicated(), ['Mode: YOLO cross-cutting · no checks required', 'Process mode: YOLO cross-cutting, self-review without checks allowed',
      'YOLO hint\n\n' + exemption, 'true']);
    assert.equal(await page.locator('button.mode-indicator').evaluate(node => getComputedStyle(node).borderTopStyle), 'dashed');
    await shot('mode-yolo-exempted');
    // A save of the YOLO mode keeps the exemption; withdrawing it is an ordinary press.
    await save.click(); await saves(3);
    assert.equal((await indicated())[0], 'Mode: YOLO cross-cutting · no checks required');
    await withdraw.click(); await saves(4);
    await group.getByText('Not allowed for this project.', { exact: true }).waitFor();
    assert.deepEqual(await indicated(), ['Mode: YOLO cross-cutting', 'Process mode: YOLO cross-cutting', 'YOLO hint', 'false']);
    assert.deepEqual((await replaced()).slice(2), [['Yolo', true], ['Yolo', false]]);
    // A change to another mode stores no exemption.
    await hold(page, allow); await saves(5);
    await radio('Rigorous').check();
    assert.equal(await group.isVisible(), false);
    await save.click(); await saves(6);
    assert.deepEqual((await replaced()).slice(4), [['Yolo', true], ['Rigorous', false]]);
    assert.deepEqual(await indicated(), ['Mode: Rigorous', 'Process mode: Rigorous', 'Rigorous hint', 'false']);
    await radio('YOLO cross-cutting').check();
    assert.deepEqual([await confirm.isVisible(), await allow.isDisabled()], [true, true]);
    console.log('PASS: the YOLO mode is saved by holding, and its exemption from configured checks by a second hold of its own; another mode stores no exemption; the header shows the exemption while it holds');
  }
  if (process.argv.includes('--attempts')) {
    // I30: an attempt of the Governor role under a governing attempt is the session's own work or review. It has no meter of its own,
    // which the table says in words: a missing measurement is not zero usage.
    await page.evaluate(() => {
      const { api, attemptsTable } = CQComponents;
      const project = new api.ProjectId('00000000-0000-0000-0000-000000000001');
      const session = new api.SessionId('00000000-0000-0000-0000-00000000000a');
      const member = new api.ItemId(project, api.Ledger.Tasks, 7n);
      const id = n => `00000000-0000-0000-0000-0000000000${String(n).padStart(2, '0')}`;
      const unattributed = new api.Assignment(new api.AssignmentId(id(1)), project, new Set(), api.Attribution.Unattributed, undefined, undefined);
      const direct = n => new api.Assignment(new api.AssignmentId(id(n)), project, new Set([member]), api.Attribution.Direct, undefined, undefined);
      const governing = new api.Attempt(new api.AttemptId(id(10)), unattributed.id, undefined, session, api.Role.Governor, api.Harness.Claude,
        'unobserved-interactive-provider', 'unobserved-interactive-model', 'CQ attached session; outer usage unavailable', 1000n, api.UsagePhase.Govern, undefined);
      const own = (n, phase) => new api.Attempt(new api.AttemptId(id(n)), new api.AssignmentId(id(n + 10)), governing.id, session, api.Role.Governor, api.Harness.Claude,
        governing.provider, governing.model, 'CQ host; own work of the governing session, no meter', 2000n, phase, undefined);
      const worker = new api.Attempt(new api.AttemptId(id(13)), new api.AssignmentId(id(23)), governing.id, session, api.Role.Worker, api.Harness.Codex,
        'openai', 'fixture-model', 'CQ native collector 0.1.0', 3000n, api.UsagePhase.Work, api.Effort.High);
      const gap = 'No meter: the work was done in the governing session, whose usage is that session\'s own';
      const ended = attempt => new api.RecordedOutcome(new api.AttemptOutcome(new api.RequestId(id(40)), attempt.id, api.AttemptState.Completed, 4000n, [gap], undefined),
        new api.Actor('CQ host collector', session, api.Role.Collector), 5000n, 1n);
      const work = own(11, api.UsagePhase.Work); const review = own(12, api.UsagePhase.Review);
      document.body.replaceChildren(attemptsTable([
        new api.AttemptView(unattributed, governing, undefined, false), new api.AttemptView(direct(21), work, ended(work), true),
        new api.AttemptView(direct(22), review, undefined, true), new api.AttemptView(direct(23), worker, undefined, true)], { scope: () => {}, outcomes: () => {} }));
    });
    const rows = await page.getByRole('table', { name: 'Attempts', exact: true }).locator('tbody tr').evaluateAll(found => found.map(row =>
      Array.from(row.querySelectorAll('td'), cell => cell.textContent).slice(1, 4)).filter(cells => cells.length > 0));
    assert.deepEqual(rows, [
      ['Claude · Governor', 'unobserved-interactive-provider / unobserved-interactive-model', 'Open'],
      ['Claude · Governor · own work', 'The governing session', 'Completed'],
      ['Claude · Governor · own review', 'The governing session', 'Running'],
      ['Codex · Worker', 'openai / fixture-model · effort high', 'Running']]);
    const details = await page.getByRole('table', { name: 'Attempts', exact: true }).locator('tbody tr').evaluateAll(found => found.map(row =>
      Object.fromEntries(Array.from(row.querySelectorAll('dt'), term => [term.textContent, term.nextElementSibling.textContent]))).filter(entry => 'Attempt' in entry));
    const meter = 'None: the work was done in the governing session, whose usage is that session\'s own';
    assert.deepEqual(details.map(entry => entry.Meter), [undefined, meter, meter, undefined]);
    assert.equal(details[1].Gaps, 'No meter: the work was done in the governing session, whose usage is that session\'s own');
    assert.match(await page.evaluate(() => CQComponents.unmeasuredNote), /^An attempt without a measurement counts no tokens here, which is not zero usage\./);
    console.log('PASS: attempts of the governing session\'s own work and review are named as such, without a model of their own, and say that they have no meter');
  }
} finally { await browser.close(); }
