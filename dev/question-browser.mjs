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
const question = number => ({title: `Question ${number}`, body: 'See Q2 for context.', labels: [], archived: false, citations: [], content: {
  Question: {status: 'Open', prompt: `Choose ${number}`, context: 'A human answer is required.', alternatives: ['Go', 'Python'], answer: null},
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

  await open(); await batch().getByRole('heading', {name: 'Q1 · Question 1', exact: true}).waitFor();
  await batch().getByRole('button', {name: 'View Q2', exact: true}).click();
  reference = page.getByRole('dialog', {name: 'Item reference · Q2', exact: true});
  await reference.getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
  await reference.getByRole('button', {name: 'Close', exact: true}).click();
  cases.push('Item references inside the question batch open the reference popup');
  await batch().getByLabel('Answer', {exact: true}).fill('Retained answer one');
  await batch().getByRole('button', {name: 'Skip / next question', exact: true}).click();
  await batch().getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
  await batch().getByRole('button', {name: 'Go', exact: true}).click();
  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click();
  await batch().getByRole('heading', {name: 'Q3 · Question 3', exact: true}).waitFor();
  assert.equal((await detail(ids[1])).draft.content.Question.answer, 'Go');
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
  cases.push('D56 alternatives, free text, skips, navigation, persisted answers and explicit conflict rebase');

  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click();
  await batch().getByRole('alert').filter({hasText: 'Enter an answer'}).waitFor();
  await batch().getByLabel('Answer', {exact: true}).fill('Third answer');
  const captured = hold('Browser answer to human question');
  await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click(); await captured();
  held = null; await enter(); await open();
  await batch().getByRole('button', {name: 'Retry exact answer', exact: true}).click();
  await batch().getByText('All questions in this batch are answered.', {exact: true}).waitFor();
  assert.equal((await detail(ids[2])).revision.value, '2');
  assert.equal((await detail(ids[2])).draft.content.Question.answer, 'Third answer');
  await batch().getByRole('button', {name: 'Close', exact: true}).click();
  cases.push('D56 empty-answer validation and retry after committed-but-lost acknowledgement without extra revision');

  await page.getByRole('button', {name: 'New item', exact: true}).click();
  const create = page.getByRole('dialog', {name: 'New item', exact: true});
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
