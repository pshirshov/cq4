// Behavioral-Active Blackbox Good-Communication; I24 work in progress: a claim marks its items live, wip: filters them, release and expiry clear the mark.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const session = randomUUID();
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': session, 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function post(path, body) {
  const response = await fetch(origin + path, {method: 'POST', headers, body: JSON.stringify(body)});
  assert.equal(response.status, 200, await response.clone().text()); const result = await response.json(); assert.equal(result.Failed, undefined, JSON.stringify(result)); return result;
}
const call = command => post('/api/call', command);
const id = () => ({value: randomUUID()});
const task = title => ({title, body: title === 'Free' ? 'A long body makes the item view scroll.\n\n'.repeat(60) : '', labels: [], archived: false, citations: [], content: {Task: {status: 'Ready', acceptance: ['Marked'], result: null, validation: []}}});
const project = id();
await call({Initialize: {config: {project, endpoint: origin, name: `Work in progress ${project.value}`}}});
const created = (await call({Change: {input: {project, change: {request: id(), fences: [], reason: 'Work in progress fixture',
  mutations: ['Held', 'Also held', 'Short lease', 'Free'].map(title => ({Create: {draft: task(title)}}))}}}})).Changed.ack.items.map(item => item.id);
const claim = async action => (await call({ClaimWork: {input: {project, action}}})).Claimed.claim;
const SHORT_LEASE_MS = 3000;
// One live poll (500 ms) plus the browse round trip, with margin for a loaded machine.
const LIVE_MS = 8000;

const browser = await chromium.launch({headless: true});
const context = await browser.newContext({viewport: {width: 1366, height: 768}});
await context.tracing.start({screenshots: true, snapshots: true, sources: true});
const page = await context.newPage(); page.setDefaultTimeout(LIVE_MS);
const cases = [], failures = [], errors = []; let navigations = 0;
page.on('pageerror', error => errors.push(String(error)));
page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations++; });
// The name of every call the page sends: the selection of a Read, otherwise the command.
const calls = [];
page.on('websocket', socket => socket.on('framesent', ({payload}) => {
  const command = JSON.parse(String(payload)).Call?.command;
  if (command !== undefined) calls.push(Object.keys(command.Read === undefined ? command : command.Read.input.selection)[0]);
}));
const idle = () => page.waitForFunction(() => document.querySelector('.items-table tbody').getAttribute('aria-busy') === 'false' &&
  [...document.querySelectorAll('span')].some(node => node.textContent === 'Data: current'));
const check = async (name, body) => { try { await body(); cases.push(name); } catch (error) { failures.push(`${name}: ${String(error)}`); } };
const table = page.getByRole('table', {name: 'Items', exact: true});
const row = name => table.locator('tbody tr.item-row').filter({has: page.getByRole('button', {name, exact: true})});
const mark = name => row(name).locator('td.item-work').getByRole('img');
const marked = () => table.locator('tbody tr.item-row').filter({has: page.locator('td.item-work svg')}).locator('.item-id').allTextContents();
const listed = () => table.locator('tbody tr.item-row .item-id').allTextContents();
const shows = ids => page.waitForFunction(expected => JSON.stringify([...document.querySelectorAll('.items-table tbody tr.item-row .item-id')].map(node => node.textContent)) === expected, JSON.stringify(ids));
async function search(query) {
  await page.getByLabel('Search query').fill(query); await page.getByRole('button', {name: 'Search', exact: true}).click();
  await page.getByText('Data: current', {exact: true}).waitFor();
}
try {
  await page.goto(origin);
  await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
  await page.getByLabel('Project', {exact: true}).selectOption(project.value); await page.getByText('Data: current', {exact: true}).waitFor();
  await row('T4 · Free').waitFor();
  const loaded = navigations;
  let held;
  await check('the header is an icon named "In progress" and unclaimed rows carry no mark', async () => {
    const header = table.getByRole('columnheader').nth(3);
    assert.equal(await header.getByRole('img', {name: 'In progress', exact: true}).count(), 1);
    assert.equal((await header.textContent()).trim(), '');
    assert.deepEqual(await marked(), []);
  });
  await check('a claim acquired through the API marks its items without a reload', async () => {
    held = await claim({Acquire: {id: id(), members: [created[0], created[1]], durationMillis: '300000'}});
    await mark('T1 · Held').waitFor(); await mark('T2 · Also held').waitFor();
    assert.deepEqual(await marked(), ['T1', 'T2']);
    const name = await mark('T1 · Held').getAttribute('aria-label');
    assert.match(name, /^Claimed by \S.* \(Human\) · 2 items · lease until \S+/);
    assert.equal(await row('T1 · Held').locator('td.item-work').getAttribute('title'), name);
    assert.equal(navigations, loaded, 'the page must not reload');
  });
  await check('the item view shows the same line', async () => {
    await page.getByRole('button', {name: 'T1 · Held', exact: true}).click();
    const detail = page.locator('#detail-pane'); await detail.getByRole('heading', {name: 'T1 · Held', exact: true}).waitFor();
    const line = detail.locator('.work-meta'); await line.waitFor();
    assert.equal((await line.textContent()).trim(), await mark('T1 · Held').getAttribute('aria-label'));
    await page.getByRole('button', {name: 'T4 · Free', exact: true}).click(); await detail.getByRole('heading', {name: 'T4 · Free', exact: true}).waitFor();
    assert.equal(await detail.locator('.work-meta').isVisible(), false);
    await page.getByRole('button', {name: 'Close item view', exact: true}).click();
  });
  await check('a claim on another item marks its row and leaves the open item view, its scroll position and focus alone', async () => {
    await page.getByRole('button', {name: 'T4 · Free', exact: true}).click();
    const detail = page.locator('#detail-pane'); await detail.getByRole('heading', {name: 'T4 · Free', exact: true}).waitFor();
    await detail.getByRole('heading', {name: 'Usage · T4', exact: true}).waitFor(); await idle();
    await detail.getByRole('button', {name: 'History', exact: true}).focus();
    assert.equal(await detail.evaluate(pane => { pane.scrollTop = 120; window.opened = [pane.querySelector('h2'), document.activeElement]; return pane.scrollTop; }), 120, 'the item view must scroll');
    const view = () => detail.evaluate(pane => ({same: pane.querySelector('h2') === window.opened[0], focused: document.activeElement === window.opened[1], scroll: pane.scrollTop}));
    let sent = calls.length;
    const other = await claim({Acquire: {id: id(), members: [created[2]], durationMillis: '300000'}});
    await mark('T3 · Short lease').waitFor(); await idle();
    assert.deepEqual(await view(), {same: true, focused: true, scroll: 120});
    assert.deepEqual([...new Set(calls.slice(sent))], ['Browse'], 'a claim reads the rows only');
    // A claim on the open item changes its work line in place; the new line moves the content, so the scroll position is not compared.
    sent = calls.length;
    const own = await claim({Acquire: {id: id(), members: [created[3]], durationMillis: '300000'}});
    const line = detail.locator('.work-meta'); await line.waitFor(); await idle();
    assert.equal((await line.textContent()).trim(), await mark('T4 · Free').getAttribute('aria-label'));
    assert.deepEqual((({same, focused}) => ({same, focused}))(await view()), {same: true, focused: true});
    assert.deepEqual([...new Set(calls.slice(sent))], ['Browse'], 'a claim reads the rows only');
    await claim({Release: {fence: other.fence}}); await claim({Release: {fence: own.fence}});
    await line.waitFor({state: 'hidden'}); await mark('T3 · Short lease').waitFor({state: 'detached'}); await idle();
    assert.deepEqual(await marked(), ['T1', 'T2']);
    assert.deepEqual((({same, focused}) => ({same, focused}))(await view()), {same: true, focused: true});
    await page.getByRole('button', {name: 'Close item view', exact: true}).click();
  });
  await check('wip:true and wip:false filter by the mark, and the editor completes the attribute values', async () => {
    await search('wip:true'); await shows(['T1', 'T2']);
    await search('wip:false'); await shows(['T3', 'T4']);
    await search('ledger:Tasks -wip:true'); await shows(['T3', 'T4']);
    const input = page.getByLabel('Search query', {exact: true}); await input.fill('wip:');
    await page.getByRole('option', {name: 'true · Value', exact: true}).waitFor(); await page.getByRole('option', {name: 'false · Value', exact: true}).waitFor();
    await input.press('Escape');
  });
  await check('a short lease is marked and expires live while the filter is applied', async () => {
    await search('wip:true'); await shows(['T1', 'T2']);
    await claim({Acquire: {id: id(), members: [created[2]], durationMillis: String(SHORT_LEASE_MS)}});
    await shows(['T1', 'T2', 'T3']);
    assert.match(await mark('T3 · Short lease').getAttribute('aria-label'), / · 1 item · lease until /);
    await row('T3 · Short lease').waitFor({state: 'detached', timeout: SHORT_LEASE_MS + LIVE_MS});
    assert.deepEqual(await listed(), ['T1', 'T2']);
  });
  await check('a child attempt of the claim owner switches the mark to the running state and back, live', async () => {
    await search(''); await shows(['T1', 'T2', 'T3', 'T4']);
    const host = operation => post('/api/usage', {project, operation});
    const assignment = {id: id(), project, attribution: 'Direct', members: [created[0]], cohort: null, evaluation: null};
    const attempt = {id: id(), assignment: assignment.id, parent: null, session: {value: session}, role: 'Worker', harness: 'Codex',
      provider: 'controlled-browser-fixture', model: 'no-model-call', collector: 'fixture', startedAt: String(Date.now()), phase: 'Work', effort: null};
    const state = name => row(name).locator('td.item-work').getAttribute('data-work');
    assert.equal(await state('T1 · Held'), 'claimed');
    const lock = await mark('T1 · Held').locator('path').getAttribute('d');
    await host({Assign: {value: assignment}}); await host({Start: {value: attempt}});
    await page.waitForFunction(() => document.querySelector('.items-table tbody td.item-work[data-work=running]') !== null);
    assert.equal(await state('T1 · Held'), 'running'); assert.equal(await state('T2 · Also held'), 'claimed');
    assert.match(await mark('T1 · Held').getAttribute('aria-label'), /^Claimed by \S.* \(Human\) · 2 items · Worker on Codex since \S+/);
    assert.notEqual(await mark('T1 · Held').locator('path').getAttribute('d'), lock);
    await page.screenshot({path: `${evidence}/wip.png`});
    await host({Finish: {value: {request: id(), attempt: attempt.id, state: 'Completed', finishedAt: String(Date.now() + 1000), gaps: [], supersedes: null}}});
    await page.waitForFunction(() => document.querySelector('.items-table tbody td.item-work[data-work=running]') === null);
    assert.equal(await state('T1 · Held'), 'claimed'); assert.match(await mark('T1 · Held').getAttribute('aria-label'), / · lease until /);
    assert.equal(navigations, loaded, 'the page must not reload');
  });
  await check('a renewal keeps the mark and a release removes it live', async () => {
    held = await claim({Renew: {fence: held.fence, durationMillis: '600000'}});
    assert.deepEqual(await marked(), ['T1', 'T2']);
    await claim({Release: {fence: held.fence}});
    await mark('T1 · Held').waitFor({state: 'detached'}); await mark('T2 · Also held').waitFor({state: 'detached'});
    assert.deepEqual(await marked(), []); assert.deepEqual(await listed(), ['T1', 'T2', 'T3', 'T4']);
    assert.equal(navigations, loaded, 'the page must not reload');
  });
  assert.deepEqual(errors, []); assert.deepEqual(failures, []);
} finally {
  await writeFile(`${evidence}/wip-results.json`, JSON.stringify({cases, failures, errors}, null, 2) + '\n');
  await context.tracing.stop({path: `${evidence}/wip-trace.zip`}); await browser.close();
}
console.log('Chromium work in progress: claims mark items live, wip: filters them, release and expiry clear the mark');
