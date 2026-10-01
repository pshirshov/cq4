// Behavioral-Active Blackbox Good-Communication; G2 large dialog variant: fixed 90%×90% geometry, header-fixed body scrolling,
// default dialogs unchanged and usage panels returned to the detail pane.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {hold, HOLD_SETTLE_MS} from './hold.mjs';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function post(path, body) {
  const response = await fetch(origin + path, {method: 'POST', headers, body: JSON.stringify(body)});
  assert.equal(response.status, 200, await response.clone().text()); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
}
const call = command => post('/api/call', command);
const id = () => ({value: randomUUID()});
const paragraphs = count => Array.from({length: count}, (_, i) => `Paragraph ${i + 1} of the long question context keeps the dialog body overflowing.`).join('\n\n');
const question = (number, context) => ({title: `Question ${number}`, body: `Question body ${number}`, labels: [], archived: false, citations: [], content: {
  Question: {status: 'Open', prompt: `Choose ${number}`, context, alternatives: ['Go', 'Python'], answer: null},
}});
const task = title => ({title, body: 'Task body', labels: [], archived: false, citations: [],
  content: {Task: {status: 'Ready', acceptance: ['Accept'], result: null, validation: []}}});

async function fixture(name) {
  const project = id();
  const change = mutations => call({Change: {input: {project, change: {request: id(), mutations, fences: [], reason: 'Dialog size fixture'}}}});
  await call({Initialize: {config: {project, endpoint: origin, name}}});
  const created = await change([{Create: {draft: question(1, 'Short.')}}, {Create: {draft: question(2, paragraphs(160))}}, {Create: {draft: question(3, 'Medium context.\n\n' + paragraphs(4))}},
    {Create: {draft: task('Sized task')}}]);
  const item = created.Changed.ack.items.find(entry => entry.id.ledger === 'Tasks');
  const revised = await change([{Replace: {id: item.id, expected: item.revision, draft: task('Sized task revised')}}]);
  const zero = {value: '0', measurement: 'Observed'};
  const counts = n => ({input: {value: String(n), measurement: 'Observed'}, output: zero, cacheRead: zero, cacheWrite: zero, reasoning: zero});
  const money = {amount: null, currency: null, basis: 'Unknown', pricingVersion: null};
  const host = operation => post('/api/usage', {project, operation});
  const assignment = {id: id(), project, attribution: 'Direct', members: [item.id], cohort: randomUUID(), evaluation: null};
  const attempt = {id: id(), assignment: assignment.id, parent: null, session: id(), role: 'Worker', harness: 'Codex',
    provider: 'controlled-browser-fixture', model: 'no-model-call', collector: 'fixture', startedAt: '1000', phase: 'Work'};
  await host({Assign: {value: assignment}}); await host({Start: {value: attempt}});
  await host({Meter: {value: {key: 'size', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: money}}});
  await host({Ingest: {value: {observation: {id: id(), attempt: attempt.id, source: 'Direct fixture', position: '1', occurredAt: '2000', receivedAt: '0',
    scope: 'Increment', counters: counts(40), inputIncludesCache: true, outputIncludesReasoning: true, cost: money,
    completeness: 'Complete', gaps: [], evidence: null, supersedes: null}, meter: 'size', disposition: 'Contribution', detailReason: null}}});
  return {project, change, item: revised.Changed.ack.items[0], attempt};
}

const browser = await chromium.launch({headless: true});
const results = [];
try {
  // Default-variant sizes measured on the base revision before the large variant existed (the UA modal max-width clamps the narrow width).
  for (const {width, height, defaults} of [{width: 1366, height: 768, defaults: {width: 900, maxHeight: 728}}, {width: 800, height: 700, defaults: {width: 768, maxHeight: 684}}]) {
    const viewport = {width, height, defaults};
    const label = `${width}x${height}`;
    const {project, change, item, attempt} = await fixture(`Dialog sizes ${label}`);
    const context = await browser.newContext({viewport: {width, height}});
    const page = await context.newPage(); page.setDefaultTimeout(8000);
    const cases = [], errors = [], measured = {};
    page.on('pageerror', error => errors.push(String(error)));
    await context.tracing.start({screenshots: true, snapshots: true, sources: true});
    const dialog = name => page.getByRole('dialog', {name, exact: true});
    const geometry = locator => locator.evaluate(node => {
      const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
      return {width: rect.width, height: rect.height, top: rect.top, left: rect.left, innerWidth, innerHeight, computedWidth: style.width, maxHeight: style.maxHeight};
    });
    async function large(locator, step) {
      await page.waitForTimeout(40);
      const rect = await geometry(locator);
      assert.ok(Math.abs(rect.width - 0.9 * rect.innerWidth) <= 2, `${label} ${step}: width ${rect.width} is not 0.9 × ${rect.innerWidth}`);
      assert.ok(Math.abs(rect.height - 0.9 * rect.innerHeight) <= 2, `${label} ${step}: height ${rect.height} is not 0.9 × ${rect.innerHeight}`);
      (measured[step] ??= []).push(rect); return rect;
    }
    function same(first, next, step) {
      for (const key of ['width', 'height', 'top', 'left']) assert.ok(Math.abs(first[key] - next[key]) <= 0.5, `${label} ${step}: ${key} changed from ${first[key]} to ${next[key]}`);
    }
    async function headerStays(locator, step) {
      const body = locator.locator(':scope > .dialog-body'), header = locator.locator(':scope > .dialog-header');
      const before = await locator.evaluate(node => {
        const body = node.querySelector(':scope > .dialog-body'); body.scrollTop = 0;
        return {overflow: body.scrollHeight - body.clientHeight, header: node.querySelector(':scope > .dialog-header').getBoundingClientRect().top};
      });
      assert.ok(before.overflow > 50, `${label} ${step}: fixture content must overflow the dialog body (${before.overflow})`);
      const box = await body.boundingBox(); await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.wheel(0, 600);
      await page.waitForFunction(node => node.scrollTop > 0, await body.elementHandle());
      const after = await locator.evaluate(node => {
        const bounds = node.getBoundingClientRect(), header = node.querySelector(':scope > .dialog-header').getBoundingClientRect();
        return {scroll: node.querySelector(':scope > .dialog-body').scrollTop, dialogScroll: node.scrollTop, header: header.top, headerBottom: header.bottom, top: bounds.top, bottom: bounds.bottom};
      });
      assert.ok(after.scroll > 0, `${label} ${step}: dialog body did not scroll`);
      assert.equal(after.dialogScroll, 0, `${label} ${step}: the dialog itself scrolled instead of its body`);
      assert.ok(Math.abs(after.header - before.header) <= 0.5 && after.header >= after.top && after.headerBottom <= after.bottom, `${label} ${step}: header left the view`);
      assert.ok(await header.getByRole('button', {name: 'Close', exact: true}).isVisible());
    }
    async function standard(locator, step) {
      const rect = await geometry(locator);
      const {width, maxHeight} = viewport.defaults;
      assert.ok(Math.abs(parseFloat(rect.computedWidth) - width) <= 0.5, `${label} ${step}: default width ${rect.computedWidth} differs from ${width}`);
      assert.ok(Math.abs(parseFloat(rect.maxHeight) - maxHeight) <= 0.5, `${label} ${step}: default max-height ${rect.maxHeight} differs from ${maxHeight}`);
      (measured[step] ??= []).push(rect);
    }
    try {
      await page.goto(origin);
      await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
      await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
      await page.getByLabel('Project', {exact: true}).selectOption(project.value); await page.getByText('Data: current', {exact: true}).waitFor();

      await page.getByRole('button', {name: 'Answer questions', exact: true}).click();
      const batch = dialog('Answer questions');
      await batch.getByRole('heading', {name: 'Q1 · Question 1', exact: true}).waitFor();
      const first = await large(batch, 'question batch');
      await batch.getByRole('button', {name: 'Skip / next question', exact: true}).click();
      await batch.getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor(); same(first, await large(batch, 'question batch'), 'question skip');
      await headerStays(batch, 'question batch');
      await batch.getByLabel('Answer', {exact: true}).fill('Go');
      await batch.getByRole('button', {name: 'Save answer and next', exact: true}).click();
      await batch.getByRole('heading', {name: 'Q3 · Question 3', exact: true}).waitFor(); same(first, await large(batch, 'question batch'), 'question save');
      await batch.getByRole('button', {name: 'Previous question', exact: true}).click();
      await batch.getByRole('heading', {name: /^Q[12] · Question [12]$/}).waitFor(); same(first, await large(batch, 'question batch'), 'question previous');
      await batch.screenshot({path: `${evidence}/dialog-size-question-${label}.png`});
      await batch.getByRole('button', {name: 'Close', exact: true}).click();
      cases.push('question batch is 90%×90% and stable across Skip/Save/Previous; header stays while the body scrolls');

      await page.getByRole('button', {name: 'New item', exact: true}).click();
      const create = dialog('New item'); await create.getByLabel('title', {exact: true}).waitFor();
      const created = await large(create, 'create item');
      for (const kind of ['Idea', 'Goal', 'Defect']) {
        await create.getByRole('button', {name: `Create ${kind}`, exact: true}).click();
        assert.equal(await create.getByLabel('content', {exact: true}).inputValue(), kind);
        same(created, await large(create, 'create item'), `create item ${kind}`);
      }
      await create.getByLabel('content', {exact: true}).selectOption('Task'); same(created, await large(create, 'create item'), 'create item Task');
      await create.getByRole('button', {name: 'Save item', exact: true}).click();
      await create.getByRole('alert').filter({hasText: /\S/}).waitFor(); same(created, await large(create, 'create item'), 'create item validation error');
      await headerStays(create, 'create item');
      await create.screenshot({path: `${evidence}/dialog-size-create-${label}.png`});
      const drafts = () => page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('cq-draft:') && key.endsWith(':new')).length);
      const discard = create.getByRole('button', {name: 'Discard local draft', exact: true});
      assert.equal(await drafts(), 1); await discard.click(); await page.waitForTimeout(HOLD_SETTLE_MS);
      assert.equal(await drafts(), 1, 'A plain click must keep the stored draft'); assert.equal(await create.isVisible(), true);
      await hold(page, discard); await create.waitFor({state: 'hidden'}); assert.equal(await drafts(), 0, 'Holding removes the stored draft');
      cases.push('New item dialog is 90%×90% and stable across item type changes and a validation error');

      const panels = () => page.evaluate(() => {
        const pane = document.getElementById('detail-pane'), [usage, audit] = [...pane.children].slice(-2);
        const style = node => { const computed = getComputedStyle(node); return ['display', 'overflow', 'padding', 'margin', 'flex', 'minHeight', 'maxHeight'].map(key => computed[key]).join(' '); };
        return {tags: [usage.tagName, audit.tagName], inDialog: usage.closest('dialog') !== null || audit.closest('dialog') !== null, styles: [style(usage), style(audit)]};
      });
      const panelsBefore = await panels();
      await page.getByRole('button', {name: 'Project usage', exact: true}).click();
      const usage = dialog('Project usage');
      await usage.getByRole('heading', {name: 'Usage · project', exact: true}).waitFor();
      const usageRect = await large(usage, 'usage');
      await usage.getByRole('button', {name: 'Attempts', exact: true}).click();
      await usage.getByRole('table', {name: 'Attempts', exact: true}).waitFor(); same(usageRect, await large(usage, 'usage'), 'usage attempts view');
      await usage.getByRole('button', {name: 'Usage audit', exact: true}).click();
      await usage.getByRole('table', {name: 'Usage audit', exact: true}).waitFor(); same(usageRect, await large(usage, 'usage'), 'usage audit view');
      await usage.getByRole('button', {name: 'Attempts', exact: true}).click(); await usage.getByRole('table', {name: 'Attempts', exact: true}).waitFor();
      const link = `Session usage · ${attempt.session.value}`;
      await usage.locator('tbody > tr details').first().evaluate(node => { node.open = true; });
      await usage.getByRole('button', {name: link, exact: true}).click();
      const scoped = dialog('Usage details');
      await scoped.getByRole('heading', {name: `Usage · session ${attempt.session.value}`, exact: true}).waitFor(); same(usageRect, await large(scoped, 'usage'), 'usage session scope');
      await scoped.getByRole('button', {name: 'Usage audit', exact: true}).click();
      await scoped.getByRole('table', {name: 'Usage audit', exact: true}).waitFor(); same(usageRect, await large(scoped, 'usage'), 'usage session audit view');
      await scoped.screenshot({path: `${evidence}/dialog-size-usage-${label}.png`});
      await scoped.getByRole('button', {name: 'Close', exact: true}).click(); await scoped.waitFor({state: 'hidden'});
      const panelsAfter = await panels();
      assert.equal(panelsAfter.inDialog, false, 'usage panels returned to the detail pane');
      assert.deepEqual(panelsAfter, {...panelsBefore, inDialog: false}, 'usage panels keep their detail-pane placement and styling');
      cases.push('usage dialog is 90%×90% and stable across scope and audit views; panels return to the detail pane unchanged');

      await page.getByRole('button', {name: 'New project', exact: true}).click();
      await standard(dialog('New project'), 'new project'); await dialog('New project').getByRole('button', {name: 'Close', exact: true}).click();
      await page.getByRole('button', {name: 'T1 · Sized task revised', exact: true}).click();
      await page.locator('#detail-pane').getByRole('heading', {name: 'T1 · Sized task revised', exact: true}).waitFor();
      await page.getByRole('button', {name: 'History', exact: true}).click();
      const history = dialog('History · T1'); await history.getByRole('button', {name: 'View revision 1', exact: true}).click();
      await standard(history, 'history');
      await history.getByRole('button', {name: 'Preview restore revision 1', exact: true}).click();
      const graph = dialog('Graph change'); await graph.getByRole('heading', {name: 'Graph change preview', exact: true}).waitFor();
      await standard(graph, 'graph change'); await graph.getByRole('button', {name: 'Close', exact: true}).click(); await graph.waitFor({state: 'hidden'});
      await page.getByRole('button', {name: 'Edit current revision', exact: true}).click();
      await page.getByLabel('body', {exact: true}).fill('Local edit');
      await change([{Replace: {id: item.id, expected: item.revision, draft: task('Sized task concurrent')}}]);
      await page.getByRole('button', {name: 'Save item', exact: true}).click();
      const conflict = dialog('Edit conflict · T1'); await conflict.waitFor();
      await standard(conflict, 'conflict'); await conflict.getByRole('button', {name: 'Close', exact: true}).click();
      cases.push('new-project, history, graph-change and conflict dialogs keep their default width and max-height');
      assert.deepEqual(errors, []);
    } finally {
      results.push({viewport, cases, errors, measured});
      await context.tracing.stop({path: `${evidence}/dialog-size-${label}.zip`}); await context.close();
    }
  }
} finally {
  await writeFile(`${evidence}/dialog-size-results.json`, JSON.stringify(results, null, 2) + '\n');
  await browser.close();
}
console.log('Chromium dialog sizes: large question/usage/create dialogs are 90%×90% and stable; default dialogs unchanged');
