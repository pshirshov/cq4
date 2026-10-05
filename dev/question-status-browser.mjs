// Behavioral-Active Blackbox Good-Communication; D70: what the open views show after Questions are answered, without a reload.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {hold as holdControl} from './hold.mjs';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
assert.ok(origin && evidence && process.env.CQ_TOKEN, 'Question status fixture needs origin, token and evidence directory');
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function call(command) {
  const response = await fetch(origin + '/api/call', {method: 'POST', headers, body: JSON.stringify(command)});
  assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
}
const QUESTIONS = 9;
const project = {value: randomUUID()};
await call({Initialize: {config: {project, endpoint: origin, name: 'Question status checks'}}});
const change = mutations => call({Change: {input: {project, change: {request: {value: randomUUID()}, mutations, fences: [], reason: 'Question status fixture'}}}});
const question = number => ({title: `Question ${number}`, body: '', labels: [], archived: false, citations: [], content: {
  Question: {status: 'Open', prompt: `Choose ${number}`, context: 'A human answer is required.', alternatives: ['Yes', 'No'], recommendation: null, answer: null},
}});
const goal = {title: 'Parent goal', body: '', labels: [], archived: false, citations: [],
  content: {Goal: {status: 'Open', outcome: 'Answered questions', scope: 'Questions', acceptance: ['Every question is answered']}}};
const created = (await change([{Create: {draft: goal}}, ...Array.from({length: QUESTIONS}, (_, index) => ({Create: {draft: question(index + 1)}}))])).Changed.ack.items;
const parent = created.find(item => item.id.ledger === 'Goals').id;
const revision = async id => (await call({Read: {input: {project, selection: {ItemDetail: {id}}}}})).Detail.view.item.revision;
// The parent produces every Question, so its item view lists them under Relationships.
for (const item of created.filter(item => item.id.ledger === 'Questions'))
  await change([{Reference: {source: parent, expectedSource: await revision(parent), relation: 'Produces', target: item.id, expectedTarget: item.revision, present: true}}]);
const detail = async name => (await call({Read: {input: {project, selection: {ItemDetail: {id: {project, ledger: 'Questions', number: name.slice(1)}}}}}})).Detail.view.item;
const stored = async names => Object.fromEntries(await Promise.all(names.map(async name => [name, (await detail(name)).draft.content.Question.status])));

const browser = await chromium.launch({headless: true});
const cases = [], errors = [];
async function session() {
  const context = await browser.newContext({viewport: {width: 1366, height: 768}});
  const page = await context.newPage(); page.setDefaultTimeout(6000);
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(origin);
  await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
  await page.getByLabel('Project', {exact: true}).selectOption(project.value); await page.getByText('Data: current', {exact: true}).waitFor();
  // A reload would lose this mark, so every observation below also states that the page was not reloaded.
  await page.evaluate(() => {window.questionStatusFixtureLoaded = true;});
  return {context, page};
}
const first = await session();
const page = first.page;
await first.context.tracing.start({screenshots: true, snapshots: true, sources: true});
const names = numbers => numbers.map(number => `Q${number}`);
// Everything the page shows about the Questions and the selected item, read from the document as it stands.
const shown = target => target.evaluate(prefix => ({
  rows: Object.fromEntries([...document.querySelectorAll('.item-row')].map(row => [row.querySelector('.item-id').textContent, row.querySelector('.item-status').textContent])),
  count: document.querySelector('.query-result-count, [role=status]')?.textContent ?? null,
  pane: document.querySelector('.workspace').dataset.detail === 'closed' ? null : {
    heading: document.querySelector('#detail-pane h2')?.textContent ?? null,
    badges: [...document.querySelectorAll('#detail-pane .item-metadata .badge')].map(node => node.textContent),
    answer: document.querySelector('#detail-pane section[data-field="answer"] .field-value')?.textContent ?? null,
    relationships: [...document.querySelectorAll('section[aria-label="Relationships and restore"] > div > p')].map(row => row.firstChild.textContent.trim() + ' ' + row.querySelector('button').textContent.replace(/^Open /, '')),
  },
  sync: [...document.querySelectorAll('.status-metrics span')].map(node => node.textContent).find(text => text.startsWith('Data: ')) ?? null,
  loaded: window.questionStatusFixtureLoaded === true,
}), `status-${project.value}-`);
// Waits until the page shows the expected state; on a timeout the failure names what the page shows instead.
async function expectShown(target, expected, message) {
  const deadline = Date.now() + 6000; let observed;
  for (;;) {
    observed = await shown(target);
    try { assert.deepEqual({rows: observed.rows, pane: observed.pane, sync: observed.sync, loaded: observed.loaded}, {...expected, sync: 'Data: current', loaded: true}, message); return; }
    catch (failure) { if (Date.now() >= deadline) throw failure; }
    await target.waitForTimeout(50);
  }
}
const search = async (target, query) => {
  await target.getByLabel('Search query').fill(query); await target.getByRole('button', {name: 'Search', exact: true}).click();
  await target.getByText('Data: current', {exact: true}).waitFor();
};
const batch = target => target.getByRole('dialog', {name: 'Answer questions', exact: true});
const openBatch = async target => {
  await target.getByRole('button', {name: 'Answer questions', exact: true}).click();
  await batch(target).getByRole('heading', {name: /^Q\d+ · Question \d+$/}).waitFor();
};
const displayed = async target => (await batch(target).getByRole('heading', {name: /^Q\d+ · Question \d+$/}).textContent()).split(' ')[0];
// Answers the displayed Question and returns its name once the dialog has moved on.
async function answer(target, text, keyboard) {
  const name = await displayed(target);
  await batch(target).getByLabel('Answer', {exact: true}).fill(text);
  if (keyboard) await batch(target).getByLabel('Answer', {exact: true}).press('Control+Enter');
  else await holdControl(target, batch(target).getByRole('button', {name: 'Save answer and next', exact: true}));
  await target.waitForFunction(name => {
    const dialog = document.querySelector('dialog[open][aria-label="Answer questions"]');
    return dialog !== null && !(dialog.querySelector('h3')?.textContent ?? '').startsWith(name + ' ');
  }, name);
  assert.equal((await detail(name)).draft.content.Question.answer, text);
  return name;
}
const closeBatch = target => batch(target).getByRole('button', {name: 'Close', exact: true}).click();
const relationships = target => target.locator('section[aria-label="Relationships and restore"]');
const produced = names(Array.from({length: QUESTIONS}, (_, index) => index + 1)).map(name => `Produces ${name}`);
const parentPane = {heading: 'G1 · Parent goal', badges: ['Goal', 'Open'], answer: null, relationships: produced};
const questionPane = (name, status, text) => ({heading: `${name} · Question ${name.slice(1)}`, badges: ['Question', status], answer: text, relationships: ['DerivedFrom G1']});
const statuses = answered => Object.fromEntries([['G1', 'Open'], ...names(Array.from({length: QUESTIONS}, (_, index) => index + 1)).map(name => [name, answered.includes(name) ? 'Answered' : 'Open'])]);
const open = answered => Object.fromEntries(names(Array.from({length: QUESTIONS}, (_, index) => index + 1)).filter(name => !answered.includes(name)).map(name => [name, 'Open']));
const answered = [];
try {
  // 1. The parent's item view is open over a result that lists the parent and its Questions.
  await page.getByRole('button', {name: 'G1 · Parent goal', exact: true}).click();
  await expectShown(page, {rows: statuses(answered), pane: parentPane}, 'the parent and its open Questions before any answer');
  await openBatch(page);
  answered.push(await answer(page, 'First answer', false));
  await expectShown(page, {rows: statuses(answered), pane: parentPane}, 'one answer, dialog still open');
  for (const text of ['Second answer', 'Third answer']) answered.push(await answer(page, text, true));
  await expectShown(page, {rows: statuses(answered), pane: parentPane}, 'several answers, dialog still open');
  await closeBatch(page);
  await expectShown(page, {rows: statuses(answered), pane: parentPane}, 'several answers, dialog closed');
  assert.deepEqual(await stored(answered), Object.fromEntries(answered.map(name => [name, 'Answered'])));
  cases.push('D70 with the parent item view open, one and then several Questions answered through the batch dialog change their result rows while the dialog is open and after it closes, and the parent view keeps its relationships');

  // 2. The result lists only open Questions; the parent's item view was opened from a Question's relationship and stays outside the result.
  await search(page, 'ledger:Questions status:Open');
  const listed = Object.keys(open(answered))[0];
  await page.getByRole('button', {name: `${listed} · Question ${listed.slice(1)}`, exact: true}).click();
  await expectShown(page, {rows: open(answered), pane: questionPane(listed, 'Open', null)}, 'an open Question selected in the list of open Questions');
  await relationships(page).getByRole('button', {name: 'Open G1', exact: true}).click();
  await expectShown(page, {rows: open(answered), pane: parentPane}, 'the parent opened from the relationship of a listed Question');
  await openBatch(page);
  answered.push(await answer(page, 'Fourth answer', false));
  await expectShown(page, {rows: open(answered), pane: parentPane}, 'one answer in the list of open Questions, dialog still open');
  await page.getByText(`${QUESTIONS - answered.length} items`, {exact: true}).waitFor();
  answered.push(await answer(page, 'Fifth answer', true));
  await expectShown(page, {rows: open(answered), pane: parentPane}, 'two answers in the list of open Questions, dialog still open');
  await closeBatch(page);
  await expectShown(page, {rows: open(answered), pane: parentPane}, 'two answers in the list of open Questions, dialog closed');
  await page.getByText(`${QUESTIONS - answered.length} items`, {exact: true}).waitFor();
  cases.push('D70 answered Questions leave a list of open Questions and its count while the batch dialog is open and after it closes, and the parent view opened from a relationship stays');

  // 3. The answered Question is in no result row: only the item view, opened from the parent's relationship, shows it.
  await search(page, 'ledger:Goals');
  await page.getByRole('button', {name: 'G1 · Parent goal', exact: true}).click();
  await expectShown(page, {rows: {G1: 'Open'}, pane: parentPane}, 'the parent alone in the result');
  await openBatch(page);
  const outside = await displayed(page);
  await closeBatch(page);
  await relationships(page).getByRole('button', {name: `Open ${outside}`, exact: true}).click();
  await expectShown(page, {rows: {G1: 'Open'}, pane: questionPane(outside, 'Open', null)}, 'an open Question shown only in the item view');
  await openBatch(page);
  assert.equal(await answer(page, 'Sixth answer', false), outside); answered.push(outside);
  await expectShown(page, {rows: {G1: 'Open'}, pane: questionPane(outside, 'Answered', 'Sixth answer')}, 'the Question outside the result answered, dialog still open');
  await closeBatch(page);
  await expectShown(page, {rows: {G1: 'Open'}, pane: questionPane(outside, 'Answered', 'Sixth answer')}, 'the Question outside the result answered, dialog closed');
  cases.push('D70 a Question shown only in the item view, outside the result, shows the answer saved through the batch dialog while the dialog is open and after it closes');

  // 4. Another browser session answers through its own batch dialog; this page learns of it only from change notifications.
  const second = await session();
  try {
    const other = second.page;
    const remote = async text => { await openBatch(other); const name = await answer(other, text, false); await closeBatch(other); answered.push(name); return name; };
    await openBatch(page); const next = await displayed(page); await closeBatch(page);
    await page.getByRole('button', {name: 'G1 · Parent goal', exact: true}).click();
    await relationships(page).getByRole('button', {name: `Open ${next}`, exact: true}).click();
    await expectShown(page, {rows: {G1: 'Open'}, pane: questionPane(next, 'Open', null)}, 'the next open Question shown only in the item view');
    assert.equal(await remote('Seventh answer'), next);
    await expectShown(page, {rows: {G1: 'Open'}, pane: questionPane(next, 'Answered', 'Seventh answer')}, 'another session answered the Question shown only in the item view');

    // 5. The item view shows the Question itself and the result lists it.
    await search(page, 'ledger:Questions');
    await openBatch(page); const selected = await displayed(page); await closeBatch(page);
    await page.getByRole('button', {name: `${selected} · Question ${selected.slice(1)}`, exact: true}).click();
    const all = () => { const rows = statuses(answered); delete rows.G1; return rows; };
    await expectShown(page, {rows: all(), pane: questionPane(selected, 'Open', null)}, 'an open Question selected in the list of all Questions');
    assert.equal(await remote('Eighth answer'), selected);
    await expectShown(page, {rows: all(), pane: questionPane(selected, 'Answered', 'Eighth answer')}, 'another session answered the selected, listed Question');

    await search(page, 'ledger:Questions status:Open');
    const last = Object.keys(open(answered));
    assert.equal(last.length, 1);
    await page.getByRole('button', {name: `${last[0]} · Question ${last[0].slice(1)}`, exact: true}).click();
    await relationships(page).getByRole('button', {name: 'Open G1', exact: true}).click();
    await expectShown(page, {rows: open(answered), pane: parentPane}, 'the last open Question listed under the parent view');
    assert.equal(await remote('Ninth answer'), last[0]);
    await expectShown(page, {rows: {}, pane: parentPane}, 'another session answered the last listed open Question');
    await page.getByText('0 items', {exact: true}).waitFor();
    assert.deepEqual(await shown(other).then(view => view.loaded), true);
  } finally { await second.context.close(); }
  cases.push('D70 answers saved by another browser session reach a Question shown only in the item view, a selected and listed Question, and a list of open Questions under the parent view');
  assert.deepEqual(await stored(answered), Object.fromEntries(answered.map(name => [name, 'Answered'])));
  assert.equal(answered.length, QUESTIONS);
  assert.deepEqual(errors, []);
  await page.screenshot({path: evidence + '/question-status.png', fullPage: true});
} catch (failure) {
  await writeFile(evidence + '/question-status-failure.json', JSON.stringify({cases, answered, shown: await shown(page).catch(error => String(error))}, null, 2));
  await page.screenshot({path: evidence + '/question-status-failure.png', fullPage: true}).catch(() => {});
  throw failure;
} finally {
  await writeFile(evidence + '/question-status-results.json', JSON.stringify({cases, errors}, null, 2));
  await first.context.tracing.stop({path: evidence + '/question-status-trace.zip'}); await browser.close();
}
console.log(JSON.stringify({cases, errors}, null, 2));
