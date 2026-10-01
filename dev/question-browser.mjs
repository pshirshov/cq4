// Behavioral-Active Blackbox Good-Communication; D56/D57 and uncertain I4 acknowledgements.
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
await call({Initialize: {config: {project, endpoint: origin, name: 'Question and reference checks'}}});
// I14: only the second question states a recommended alternative.
const recommendation = {alternative: 1, reason: 'The existing tests already cover it.'};
const question = number => ({title: `Question ${number}`, body: 'See Q2 for context.', labels: [], archived: false, citations: [], content: {
  Question: {status: 'Open', prompt: `Choose ${number}`, context: 'A human answer is required.', alternatives: ['Go', 'Python', 'Same as Q3'],
    recommendation: number === 2 ? recommendation : null, answer: null},
}});
const source = {title: 'References', body: 'See Q1 and Q2. Missing Q999. Plain XQ1 Q01 path/Q1.txt `Q1` https://example.org/Q1?a=Q2.', labels: [], archived: false, citations: [],
  content: {Defect: {status: 'Open', severity: 'Medium', observed: 'References', expected: 'Popups', reproduction: 'Activate', cause: null, resolution: []}}};
const change = mutations => call({Change: {input: {project, change: {request: {value: randomUUID()}, mutations, fences: [], reason: 'Question fixture'}}}});
const created = await change([...Array.from({length: 3}, (_, i) => ({Create: {draft: question(i + 1)}})), {Create: {draft: source}}]);
const ids = created.Changed.ack.items.filter(item => item.id.ledger === 'Questions').map(item => item.id);
const detail = async id => (await call({Read: {input: {project, selection: {ItemDetail: {id}}}}})).Detail.view.item;
const browser = await chromium.launch({headless: true}); const context = await browser.newContext({viewport: {width: 1366, height: 768}});
let held = null;
await context.routeWebSocket(/\/ws$/, route => {
  const server = route.connectToServer();
  route.onMessage(message => {
    const frame = JSON.parse(String(message));
    if (held !== null && held.id === null && frame.Call?.command.Change?.input.change.reason === held.reason) held.id = frame.Call.id.value;
    server.send(message);
  });
  server.onMessage(message => {
    const frame = JSON.parse(String(message));
    if (held !== null && frame.Reply?.id.value === held.id) { held.result = frame.Reply.result; held.resolve(); }
    else route.send(message);
  });
});
function hold(reason) {
  let resolve; const captured = new Promise(done => {resolve = done;}); held = {reason, id: null, result: null, resolve};
  return async () => {
    let timer; try { await Promise.race([captured, new Promise((_, reject) => {timer = setTimeout(() => reject(new Error('No committed acknowledgement captured')), 6000);})]); }
    finally { clearTimeout(timer); }
    assert.ok(held.result.Changed);
  };
}
const page = await context.newPage(); page.setDefaultTimeout(6000); const cases = [], errors = [];
page.on('pageerror', error => errors.push(String(error)));
await context.tracing.start({screenshots: true, snapshots: true, sources: true});
async function enter() {
  await page.goto(origin);
  await page.getByLabel('Operator token').or(page.getByText('Connection: ALIVE', {exact: true})).first().waitFor();
  if (await page.getByLabel('Operator token').isVisible()) {
    await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  }
  await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
  await page.getByLabel('Project', {exact: true}).selectOption(project.value); await page.getByText('Data: current', {exact: true}).waitFor();
}
const batch = () => page.getByRole('dialog', {name: 'Answer questions', exact: true});
const open = () => page.getByRole('button', {name: 'Answer questions', exact: true}).click();
try {
  await enter();
  await page.getByRole('button', {name: 'D1 · References', exact: true}).click();
  await page.locator('#detail-pane').getByRole('heading', {name: 'D1 · References', exact: true}).waitFor();
  const document = page.locator('#detail-pane .item-document');
  assert.deepEqual(await document.getByRole('button').allTextContents(), ['Q1', 'Q2', 'Q999']);
  assert.equal(await document.locator('.item-reference svg').count(), 3);
  await document.getByRole('button', {name: 'View Q1', exact: true}).click();
  let reference = page.getByRole('dialog', {name: 'Item reference · Q1', exact: true});
  await reference.getByRole('heading', {name: 'Q1 · Question 1', exact: true}).waitFor();
  assert.equal(await page.locator('#detail-pane').getByRole('heading', {name: 'D1 · References', exact: true}).count(), 1);
  await reference.getByRole('button', {name: 'View Q2', exact: true}).click();
  reference = page.getByRole('dialog', {name: 'Item reference · Q2', exact: true});
  await reference.getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
  await reference.getByRole('button', {name: 'Back', exact: true}).click();
  reference = page.getByRole('dialog', {name: 'Item reference · Q1', exact: true});
  await reference.getByRole('heading', {name: 'Q1 · Question 1', exact: true}).waitFor();
  await reference.getByRole('button', {name: 'Close', exact: true}).click();
  await document.getByRole('button', {name: 'View Q999', exact: true}).press('Enter');
  reference = page.getByRole('dialog', {name: 'Item reference · Q999', exact: true});
  await reference.getByRole('alert').filter({hasText: 'Cannot open Q999'}).waitFor();
  await reference.getByRole('button', {name: 'Close', exact: true}).click();
  cases.push('D57 token boundaries, nested popup/back, keyboard, missing target and preserved selection');

  await page.getByRole('button', {name: 'Q2 · Question 2', exact: true}).click();
  await page.locator('#detail-pane').getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
  const recommended = page.locator('#detail-pane .recommended-alternative');
  assert.equal(await recommended.count(), 1);
  assert.equal(await recommended.textContent(), 'PythonRecommended' + recommendation.reason);
  assert.equal(await page.locator('#detail-pane section[data-field="recommendation"]').count(), 0, 'the recommendation is marked in the alternatives, not repeated as a section');
  cases.push('I14 item view marks the recommended alternative with its reason');

  await open(); await batch().getByRole('heading', {name: 'Q1 · Question 1', exact: true}).waitFor();
  assert.equal(await batch().locator('.recommended-alternative, .recommended-badge, .recommendation-reason').count(), 0, 'a question without a recommendation shows no badge');
  assert.equal(await batch().getByRole('button', {name: /^Pick alternative: /}).count(), 3);
  await batch().getByRole('button', {name: 'View Q2', exact: true}).click();
  reference = page.getByRole('dialog', {name: 'Item reference · Q2', exact: true});
  await reference.getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
  await reference.getByRole('button', {name: 'Close', exact: true}).click();
  cases.push('Item references inside the question batch open the reference popup');
  await batch().getByLabel('Answer', {exact: true}).fill('Retained answer one');
  await batch().getByRole('button', {name: 'Skip / next question', exact: true}).click();
  await batch().getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
  // D69: each alternative is listed once, in the document, with one Pick control placed before it.
  assert.equal((await batch().innerText()).split('Python').length - 1, 1, 'alternative Python is rendered exactly once');
  assert.equal(await batch().locator('.answer-alternatives').count(), 0);
  for (const name of ['Go', 'Python', 'Same as Q3']) assert.equal(await batch().getByRole('button', {name, exact: true}).count(), 0);
  const alternatives = batch().locator('section[data-field="alternatives"] li');
  assert.deepEqual(await alternatives.allTextContents(), ['PickGo', 'PickPythonRecommended' + recommendation.reason, 'PickSame as Q3']);
  assert.equal(await batch().locator('.recommended-badge').count(), 1);
  assert.equal(await batch().getByText('Recommended', {exact: true}).count(), 1);
  assert.equal(await alternatives.nth(1).locator('.recommendation-reason').isVisible(), true);
  assert.equal(await batch().getByRole('button', {name: /^Pick alternative: /}).count(), 2);
  assert.equal(await batch().getByRole('button', {name: /^Pick recommended alternative: /}).count(), 1);
  await alternatives.nth(2).getByRole('button', {name: 'View Q3', exact: true}).click();
  reference = page.getByRole('dialog', {name: 'Item reference · Q3', exact: true});
  await reference.getByRole('heading', {name: 'Q3 · Question 3', exact: true}).waitFor();
  await reference.getByRole('button', {name: 'Close', exact: true}).click();
  assert.equal(await batch().getByLabel('Answer', {exact: true}).inputValue(), '');
  await alternatives.nth(1).getByRole('button', {name: 'Pick recommended alternative: Python', exact: true}).click();
  assert.equal(await batch().getByLabel('Answer', {exact: true}).inputValue(), 'Python');
  // The operator may still pick another alternative than the recommended one.
  await alternatives.first().getByRole('button', {name: 'Pick alternative: Go', exact: true}).click();
  assert.equal(await batch().getByLabel('Answer', {exact: true}).inputValue(), 'Go');
  await batch().screenshot({path: evidence + '/question-alternatives.png'});
  assert.equal((await detail(ids[1])).draft.content.Question.status, 'Open', 'picking does not save');
  assert.equal((await detail(ids[1])).revision.value, '1');
  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click();
  await batch().getByRole('heading', {name: 'Q3 · Question 3', exact: true}).waitFor();
  assert.equal((await detail(ids[1])).draft.content.Question.answer, 'Go');
  assert.deepEqual((await detail(ids[1])).draft.content.Question.recommendation, recommendation, 'answering keeps the recorded recommendation');
  assert.equal((await detail(ids[0])).draft.content.Question.status, 'Open');
  await batch().getByRole('button', {name: 'Previous question', exact: true}).click();
  await batch().getByRole('heading', {name: 'Q1 · Question 1', exact: true}).waitFor();
  assert.equal(await batch().getByLabel('Answer', {exact: true}).inputValue(), 'Retained answer one');
  await change([{Replace: {id: ids[0], expected: {value: '1'}, draft: {...question(1), body: 'Concurrent clarification'}}}]);
  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click();
  await batch().getByRole('alert').filter({hasText: 'Conflict'}).waitFor();
  assert.equal((await detail(ids[0])).revision.value, '2');
  await batch().getByRole('button', {name: 'Use current question as base', exact: true}).click();
  assert.equal(await batch().getByLabel('Answer', {exact: true}).inputValue(), 'Retained answer one');
  await batch().getByLabel('Answer', {exact: true}).press('Control+Enter');
  await batch().getByRole('heading', {name: 'Q3 · Question 3', exact: true}).waitFor();
  assert.equal((await detail(ids[0])).draft.body, 'Concurrent clarification');
  assert.equal((await detail(ids[0])).draft.content.Question.status, 'Answered');
  cases.push('D56/D69/I14 alternatives listed once with pick controls and working links, one recommended badge with its reason, any pick fills without saving, free text, skips, navigation, persisted answers and explicit conflict rebase');

  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click();
  await batch().getByRole('alert').filter({hasText: 'Enter an answer'}).waitFor();
  await batch().getByLabel('Answer', {exact: true}).fill('Third answer');
  const captured = hold('Browser answer to human question');
  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click(); await captured();
  const picks = batch().getByRole('button', {name: /^Pick alternative: /});
  assert.equal(await picks.count(), 3);
  for (const pick of await picks.all()) assert.equal(await pick.isDisabled(), true, 'pick controls are disabled during submit');
  held = null; await enter(); await open();
  await batch().getByRole('button', {name: 'Retry exact answer', exact: true}).click();
  await batch().getByText('All questions in this batch are answered.', {exact: true}).waitFor();
  assert.equal((await detail(ids[2])).revision.value, '2');
  assert.equal((await detail(ids[2])).draft.content.Question.answer, 'Third answer');
  await batch().getByRole('button', {name: 'Close', exact: true}).click();
  cases.push('D56 empty-answer validation and retry after committed-but-lost acknowledgement without extra revision');

  // I14: the recommendation names its alternative by index, so the editor keeps the index on that alternative or clears it visibly.
  const editable = question(4); editable.content.Question.alternatives = ['Go', 'Python', 'Rust']; editable.content.Question.recommendation = recommendation;
  const fourth = (await change([{Create: {draft: editable}}])).Changed.ack.items[0].id;
  const committed = async revision => {
    for (let attempt = 0; attempt < 60; attempt++) {
      const item = await detail(fourth); if (item.revision.value === revision) return item.draft.content.Question;
      await page.waitForTimeout(100);
    }
    throw new Error(`Q4 did not reach revision ${revision}`);
  };
  const form = page.locator('#detail-pane .item-form, .item-form').first();
  const removeFirstAlternative = async () => {
    await page.getByRole('button', {name: 'Q4 · Question 4', exact: true}).click();
    await page.getByRole('button', {name: 'Edit current revision', exact: true}).click();
    await form.locator('[data-field=alternatives] .array-row').first().getByRole('button', {name: 'Remove', exact: true}).click();
  };
  await removeFirstAlternative();
  assert.equal(await form.getByLabel('alternative', {exact: true}).inputValue(), '0', 'the index follows the recommended alternative');
  assert.equal(await form.locator('[data-field=recommendation] [role=status]').isVisible(), false);
  await page.getByRole('button', {name: 'Save item', exact: true}).click();
  let stored = await committed('2');
  assert.deepEqual([stored.alternatives, stored.recommendation], [['Python', 'Rust'], {alternative: 0, reason: recommendation.reason}]);
  await page.waitForFunction(count => document.querySelectorAll('#detail-pane section[data-field="alternatives"] li').length === count, 2);
  assert.equal(await page.locator('#detail-pane .recommended-alternative').textContent(), 'PythonRecommended' + recommendation.reason);
  await removeFirstAlternative();
  assert.equal(await form.getByLabel('Add recommendation', {exact: true}).isChecked(), false, 'a recommendation whose alternative was removed is cleared');
  assert.equal(await form.locator('[data-field=recommendation] [role=status]').textContent(),
    'Recommendation cleared: its alternative “Python” is no longer in the list. Tick “Add recommendation” to state it again.');
  await page.getByRole('button', {name: 'Save item', exact: true}).click();
  stored = await committed('3');
  assert.deepEqual([stored.alternatives, stored.recommendation], [['Rust'], null]);
  await page.waitForFunction(count => document.querySelectorAll('#detail-pane section[data-field="alternatives"] li').length === count, 1);
  assert.equal(await page.locator('#detail-pane .recommended-alternative').count(), 0);
  cases.push('I14 the item editor keeps the recommendation on its alternative when the list changes and clears it visibly when that alternative is removed');

  await page.getByRole('button', {name: 'New item', exact: true}).click();
  const create = page.getByRole('dialog', {name: 'New item', exact: true});
  await create.getByLabel('content', {exact: true}).selectOption('Question');
  assert.equal(await create.getByLabel('reason', {exact: true}).isVisible(), false);
  await create.getByLabel('Add recommendation', {exact: true}).check();
  assert.equal(await create.getByLabel('alternative', {exact: true}).isVisible() && await create.getByLabel('reason', {exact: true}).isVisible(), true);
  cases.push('I14 the item editor offers the recommended alternative index and its reason');
  await create.getByRole('button', {name: 'Create Idea', exact: true}).click();
  await create.getByLabel('title', {exact: true}).fill('Uncertain repeated entry');
  await create.getByLabel('outcome', {exact: true}).fill('One creation');
  await create.getByLabel('motivation', {exact: true}).fill('Retain exact request');
  const capturedCreate = hold('Browser edit');
  await create.getByRole('button', {name: 'Save item and create next', exact: true}).click(); await capturedCreate();
  held = null; await enter(); await page.getByRole('button', {name: 'New item', exact: true}).click();
  await create.getByRole('button', {name: 'Save item', exact: true}).click();
  await page.waitForFunction(() => document.querySelector('dialog[open] textarea[aria-label=title]')?.value === '');
  assert.equal(await create.getByLabel('content', {exact: true}).inputValue(), 'Idea');
  const ideas = await call({Search: {input: {project, query: 'ledger:Ideas', after: null, snapshot: null, limit: 20}}});
  assert.equal(ideas.Found.page.items.length, 1); assert.equal(ideas.Found.page.items[0].revision.value, '1');
  cases.push('I4 repeated entry survives acknowledgement loss and reload without duplicate creation');
  assert.deepEqual(errors, []);
  await page.screenshot({path: evidence + '/question-entry.png', fullPage: true});
} finally {
  await writeFile(evidence + '/question-results.json', JSON.stringify({cases, errors}, null, 2));
  await context.tracing.stop({path: evidence + '/question-trace.zip'}); await browser.close();
}
