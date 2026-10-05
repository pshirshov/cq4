import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

const origin = process.env.CQ_ORIGIN;
const evidence = process.env.CQ_BROWSER_EVIDENCE;
const project = { value: randomUUID() };
const id = number => ({ project, ledger: 'Tasks', number: String(number) });
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
async function post(command) {
  const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
  assert.equal(response.status, 200, await response.clone().text());
  const result = await response.json(); assert.equal(result.Failed, undefined, JSON.stringify(result)); return result;
}
const change = mutations => post({ Change: { input: { project, change: { request: { value: randomUUID() }, mutations, fences: [], reason: 'Relationship graph fixture' } } } });
const detail = async number => (await post({ Read: { input: { project, selection: { ItemDetail: { id: id(number) } } } } })).Detail.view;
// I32: titles of the length real items have, so that the layout is measured with wrapped and clamped labels.
const ROOT_TITLE = 'Selected work whose title is long enough to need several lines in the centre of its relationship graph';
const LONG_SUFFIX = ', with a title long enough to wrap over more lines than a graph node shows';
await post({ Initialize: { config: { project, endpoint: origin, name: `Graph navigation ${project.value}` } } });
await change(Array.from({ length: 19 }, (_, index) => ({ Create: { draft: {
  title: index === 0 ? ROOT_TITLE : index === 1 ? 'Prerequisite <script>literal</script>'
    : `Related work ${index + 1}${index >= 4 && index < 12 ? LONG_SUFFIX : ''}`,
  body: 'Graph fixture', labels: [], archived: false, citations: [],
  content: { Task: { status: 'Ready', acceptance: ['Read-only graph'], result: null, validation: [] } },
} } })));
for (let number = 2; number <= 17; number++) {
  const root = await detail(1); const target = await detail(number);
  await change([{ Reference: { source: id(1), expectedSource: root.item.revision, relation: number <= 3 ? 'BlockedBy' : 'Blocks',
    target: id(number), expectedTarget: target.item.revision, present: true } }]);
}
let root = await detail(1); const neighbor = await detail(2);
await change([{ Reference: { source: id(1), expectedSource: root.item.revision, relation: 'RelatesTo', target: id(2), expectedTarget: neighbor.item.revision, present: true } }]);
const lone = await detail(18); const partner = await detail(19);
await change([{ Reference: { source: id(18), expectedSource: lone.item.revision, relation: 'RelatesTo', target: id(19), expectedTarget: partner.item.revision, present: true } }]);
const before = await detail(1);
// I32: the results table keeps this width for its title column while the item pane is open.
const MIN_TITLE_WIDTH = 200;
// I32: a neighbour narrower than this cannot show an identity and a title legibly.
const MIN_NODE_WIDTH = 180;
const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1280, height: 720 }];
// Wider than the phone layout and narrower than a ring of four columns of MIN_NODE_WIDTH needs.
const NARROW = { width: 800, height: 900 };
const browser = await chromium.launch({ headless: true });
const errors = []; const cases = []; const failures = [];
const check = async (name, body) => { try { await body(); cases.push(name); } catch (error) { failures.push(`${name}: ${String(error.message ?? error)}`); } };
try {
  const context = await browser.newContext({ viewport: VIEWPORTS[0] });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  let armed = false; let held = null; let capture;
  const captured = new Promise(resolve => { capture = resolve; });
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer(); let request = null;
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (armed && frame.Call && frame.Call.command.Search && frame.Call.command.Search.input.query.endsWith('archived:all')) {
        armed = false; request = frame.Call.id.value;
      }
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (request !== null && frame.Reply && frame.Reply.id.value === request) {
        held = () => route.send(message); request = null; capture();
      } else route.send(message);
    });
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(String(error)));
  await page.goto(origin); await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click(); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  await page.getByLabel('Project', { exact: true }).selectOption(project.value);
  await page.getByRole('button', { name: `T1 · ${ROOT_TITLE}`, exact: true }).click();
  await page.getByRole('heading', { name: `T1 · ${ROOT_TITLE}`, exact: true }).waitFor();
  for (const viewport of VIEWPORTS) await check(`title column keeps ${MIN_TITLE_WIDTH}px beside the open item pane at ${viewport.width}×${viewport.height}`, async () => {
    await page.setViewportSize(viewport);
    const title = page.getByRole('table', { name: 'Items', exact: true }).getByRole('columnheader', { name: 'Title' });
    const width = async () => (await title.boundingBox()).width;
    const deadline = Date.now() + 2000;
    while (await width() < MIN_TITLE_WIDTH && Date.now() < deadline) await page.waitForTimeout(50);
    assert.ok(await width() >= MIN_TITLE_WIDTH, `title column is ${await width()}px wide`);
    await page.screenshot({ path: `${evidence}/relationship-graph-table-${viewport.width}x${viewport.height}.png` });
  });
  await page.setViewportSize(VIEWPORTS[0]);
  await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Relationships · T1', exact: true });
  const filter = dialog.getByRole('combobox', { name: 'Graph relationships', exact: true });
  await check('the graph opens on all relationships', async () => {
    await dialog.getByText('Edges 1–12 of 17', { exact: true }).waitFor({ timeout: 5000 });
    assert.equal(await filter.inputValue(), 'all');
  });
  await filter.selectOption('all');
  await dialog.getByText('Edges 1–12 of 17', { exact: true }).waitFor();
  const boxes = () => dialog.locator('.relationship-graph').evaluate(canvas => {
    const box = node => { const bounds = node.getBoundingClientRect(); return { left: bounds.left, top: bounds.top, right: bounds.right, bottom: bounds.bottom }; };
    return { canvas: box(canvas), root: box(canvas.querySelector('.graph-root')),
      nodes: Array.from(canvas.querySelectorAll('.graph-node'), node => ({ name: node.textContent, centre: node.classList.contains('graph-root'), ...box(node) })) };
  });
  for (const viewport of VIEWPORTS) await check(`12 neighbours surround a centred item without overlap at ${viewport.width}×${viewport.height}`, async () => {
    await page.setViewportSize(viewport); await page.waitForTimeout(200);
    const { canvas, root, nodes } = await boxes();
    await page.screenshot({ path: `${evidence}/relationship-graph-${viewport.width}x${viewport.height}.png` });
    assert.equal(nodes.length, 13);
    const third = { x: (canvas.right - canvas.left) / 3, y: (canvas.bottom - canvas.top) / 3 };
    assert.ok(root.left >= canvas.left + third.x - 1 && root.right <= canvas.right - third.x + 1,
      `centre spans x ${root.left - canvas.left}–${root.right - canvas.left} of ${canvas.right - canvas.left}, outside the middle third`);
    assert.ok(root.top >= canvas.top + third.y - 1 && root.bottom <= canvas.bottom - third.y + 1,
      `centre spans y ${root.top - canvas.top}–${root.bottom - canvas.top} of ${canvas.bottom - canvas.top}, outside the middle third`);
    for (const [index, a] of nodes.entries()) {
      assert.ok(a.left >= canvas.left - 1 && a.right <= canvas.right + 1 && a.top >= canvas.top - 1 && a.bottom <= canvas.bottom + 1, `${a.name} leaves the graph area`);
      if (!a.centre) assert.ok(a.right - a.left >= MIN_NODE_WIDTH, `${a.name} is ${a.right - a.left}px wide`);
      for (const b of nodes.slice(index + 1))
        assert.ok(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top, `${a.name} overlaps ${b.name}`);
    }
    const body = await dialog.locator('.dialog-body').evaluate(node => ({ client: node.clientHeight, scroll: node.scrollHeight }));
    assert.ok(body.scroll <= body.client + 1, `the graph needs ${body.scroll}px of ${body.client}px and scrolls`);
  });
  await check(`neighbours keep ${MIN_NODE_WIDTH}px without overlap where the ring no longer fits, at ${NARROW.width}×${NARROW.height}`, async () => {
    await page.setViewportSize(NARROW); await page.waitForTimeout(200);
    const { nodes } = await boxes();
    await page.screenshot({ path: `${evidence}/relationship-graph-${NARROW.width}x${NARROW.height}.png` });
    assert.equal(nodes.length, 13);
    for (const [index, a] of nodes.entries()) {
      if (!a.centre) assert.ok(a.right - a.left >= MIN_NODE_WIDTH, `${a.name} is ${a.right - a.left}px wide`);
      for (const b of nodes.slice(index + 1))
        assert.ok(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top, `${a.name} overlaps ${b.name}`);
    }
  });
  await page.setViewportSize(VIEWPORTS[0]); await page.waitForTimeout(200);
  // The focus stops are the centre and each neighbour's button; an arrow key moves to the nearest stop, by the distance between node
  // centres, among those within 45° of the key's direction.
  const stops = () => dialog.locator('.relationship-graph').evaluate(canvas => {
    const found = Array.from(canvas.querySelectorAll('.graph-root, .graph-node button'));
    return { focused: found.indexOf(document.activeElement), centres: found.map(stop => {
      const bounds = stop.closest('.graph-node').getBoundingClientRect(); return [bounds.left + bounds.width / 2, bounds.top + bounds.height / 2];
    }) };
  });
  const DIRECTIONS = { ArrowRight: [1, 0], ArrowLeft: [-1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
  const focusStop = index => dialog.locator('.relationship-graph').evaluate((canvas, at) => canvas.querySelectorAll('.graph-root, .graph-node button')[at].focus(), index);
  await check('an arrow key is consumed only when it moves focus', async () => {
    const outcomes = await dialog.locator('.relationship-graph').evaluate(canvas => {
      const found = Array.from(canvas.querySelectorAll('.graph-root, .graph-node button'));
      return found.flatMap(stop => ['ArrowRight', 'ArrowLeft', 'ArrowUp', 'ArrowDown'].map(key => {
        stop.focus();
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
        stop.dispatchEvent(event);
        return { moved: document.activeElement !== stop, consumed: event.defaultPrevented };
      }));
    });
    assert.ok(outcomes.some(outcome => !outcome.moved) && outcomes.some(outcome => outcome.moved), 'both a key with and a key without a node in its direction were pressed');
    assert.deepEqual(outcomes.filter(outcome => outcome.moved !== outcome.consumed), []);
  });
  await check('arrow keys move focus to the nearest node in their direction', async () => {
    await dialog.locator('.graph-root').focus();
    const initial = await stops(); const root = initial.focused;
    assert.ok(root >= 0, 'the centre node takes focus');
    let moves = 0;
    for (const start of initial.centres.keys()) {
      for (const [key, [ux, uy]] of Object.entries(DIRECTIONS)) {
        await focusStop(start);
        const { centres } = await stops(); const [x, y] = centres[start];
        const cone = centres.map(([cx, cy], index) => ({ index, along: (cx - x) * ux + (cy - y) * uy, across: Math.abs((cx - x) * uy - (cy - y) * ux), distance: Math.hypot(cx - x, cy - y) }))
          .filter(entry => entry.index !== start && entry.along > 0 && entry.across <= entry.along);
        await page.keyboard.press(key);
        const after = (await stops()).focused;
        if (cone.length === 0) continue;
        moves++;
        const nearest = Math.min(...cone.map(entry => entry.distance));
        const reached = cone.find(entry => entry.index === after);
        assert.ok(reached !== undefined && reached.distance <= nearest + 1,
          `${key} from stop ${start} reached stop ${after}; the nearest in that direction is ${cone.find(entry => entry.distance === nearest).index}`);
      }
    }
    assert.ok(moves >= 4 * 4, `${moves} moves were checked`);
    await focusStop(root); await page.keyboard.press('ArrowRight');
    const ring = await dialog.locator('.relationship-graph').evaluate(() => { const style = getComputedStyle(document.activeElement); return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) }; });
    assert.ok(ring.style !== 'none' && ring.width >= 2, `the focused node has outline ${ring.style} ${ring.width}px`);
    assert.equal(await dialog.locator('.graph-node button:focus').count(), 1, 'a neighbour is a focused button');
    await page.screenshot({ path: `${evidence}/relationship-graph-focus.png` });
    for (const name of await dialog.locator('.graph-node button').evaluateAll(nodes => nodes.map(node => node.textContent)))
      assert.match(name, /^T\d+ · \S/, 'every neighbour is a button named by its identity and title');
    assert.equal(await dialog.getByRole('button', { name: 'T4 · Related work 4', exact: true }).count(), 1);
    assert.equal(await dialog.getByRole('button', { name: `T5 · Related work 5${LONG_SUFFIX}`, exact: true }).count(), 1, 'a clamped title keeps its full accessible name');
  });
  await filter.selectOption('dependencies');
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  assert.equal(await dialog.getByText('T2 Blocks T1', { exact: true }).count(), 1);
  assert.equal(await dialog.getByText('T1 Blocks T4', { exact: true }).count(), 1);
  assert.equal(await dialog.locator('svg line[marker-end]').count(), 12);
  assert.equal(await dialog.locator('.graph-node button').count(), 12);
  assert.equal(await dialog.locator('script').count(), 0);
  assert.equal((await dialog.innerText()).includes(project.value), false);
  cases.push('prerequisites and dependents use readable identities, correct blocks arrows and escaped titles');
  await page.screenshot({ path: `${evidence}/relationship-graph.png` });
  await dialog.getByRole('button', { name: 'Next relationships', exact: true }).click();
  await dialog.getByText('Edges 13–16 of 16', { exact: true }).waitFor();
  assert.equal(await dialog.locator('.graph-node button').count(), 4);
  assert.equal(await dialog.getByRole('button', { name: 'Next relationships', exact: true }).isDisabled(), true);
  await dialog.getByRole('button', { name: 'Previous relationships', exact: true }).click();
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  cases.push('bounded edge pages cover all neighbors');
  const prerequisite = dialog.getByRole('button', { name: 'T2 · Prerequisite <script>literal</script>', exact: true });
  await prerequisite.focus(); await page.keyboard.press('Enter');
  const recentered = page.getByRole('dialog', { name: 'Relationships · T2', exact: true });
  await recentered.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
  assert.equal(await recentered.getByText('T2 Blocks T1', { exact: true }).count(), 1);
  await recentered.getByRole('button', { name: 'Back in graph', exact: true }).click();
  await dialog.getByText('Edges 1–12 of 16', { exact: true }).waitFor();
  await filter.selectOption('all');
  await dialog.getByText('Edges 1–12 of 17', { exact: true }).waitFor();
  assert.equal(await dialog.getByText('T1 RelatesTo T2', { exact: true }).count(), 1);
  assert.equal(await dialog.locator('svg line:not([marker-end])').count(), 1, 'symmetric relationships have no directed arrow');
  cases.push('keyboard recentering, back navigation and labelled symmetric relationships');
  await page.setViewportSize({ width: 640, height: 800 });
  const layout = await dialog.locator('.dialog-body').evaluate(node => ({ width: node.clientWidth, scroll: node.scrollWidth }));
  assert.ok(layout.scroll <= layout.width + 1, 'graph fits a narrow viewport');
  await page.screenshot({ path: `${evidence}/relationship-graph-narrow.png` });
  await dialog.getByRole('button', { name: 'T4 · Related work 4', exact: true }).click();
  const dependent = page.getByRole('dialog', { name: 'Relationships · T4', exact: true });
  await dependent.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
  await dependent.getByRole('button', { name: 'Open item', exact: true }).click();
  await page.getByRole('heading', { name: 'T4 · Related work 4', exact: true }).waitFor();
  await dependent.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
  await dependent.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
  armed = true; await dependent.getByRole('button', { name: 'Refresh graph', exact: true }).click();
  let timeout;
  try { await Promise.race([captured, new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Missing held graph reply')), 10000); })]); }
  finally { clearTimeout(timeout); }
  await dependent.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: `T1 · ${ROOT_TITLE}`, exact: true }).click();
  await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
  await dialog.getByText('Edges 1–12 of 17', { exact: true }).waitFor();
  held();
  // The socket delivers in order: once the reply to a refresh requested after the late reply has redrawn the graph, the late one was handled.
  await dialog.locator('.graph-root').evaluate(node => { node.dataset.beforeRefresh = ''; });
  await dialog.getByRole('button', { name: 'Refresh graph', exact: true }).click();
  await dialog.locator('[data-before-refresh]').waitFor({ state: 'detached' });
  await dialog.getByText('Edges 1–12 of 17', { exact: true }).waitFor();
  assert.equal(await dialog.getByText('Edges 1–12 of 17', { exact: true }).count(), 1);
  assert.equal(await dialog.locator('[role=alert]').isVisible(), false);
  cases.push('a late reply from a closed graph cannot overwrite a newly centered graph');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.setViewportSize(VIEWPORTS[0]);
  await check('a view without edges names the relationships of the other view and switches to it', async () => {
    await page.getByRole('button', { name: 'T18 · Related work 18', exact: true }).click();
    await page.getByRole('heading', { name: 'T18 · Related work 18', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Relationship graph', exact: true }).click();
    const related = page.getByRole('dialog', { name: 'Relationships · T18', exact: true });
    const view = related.getByRole('combobox', { name: 'Graph relationships', exact: true });
    await related.getByText('Edges 1–1 of 1', { exact: true }).waitFor({ timeout: 5000 });
    assert.equal(await related.getByText('All relationships has', { exact: false }).count(), 0, 'no hint while the view has edges');
    await view.selectOption('dependencies');
    await related.getByText('No edges', { exact: true }).waitFor();
    await related.getByText('This item has no dependency edges. All relationships has 1 edge.', { exact: true }).waitFor({ timeout: 5000 });
    await page.screenshot({ path: `${evidence}/relationship-graph-hint.png` });
    await related.getByRole('button', { name: 'Show all relationships', exact: true }).click();
    await related.getByText('Edges 1–1 of 1', { exact: true }).waitFor();
    assert.equal(await view.inputValue(), 'all');
    assert.equal(await related.getByText('T18 RelatesTo T19', { exact: true }).count(), 1);
    await related.getByRole('button', { name: 'Close', exact: true }).click();
  });
  assert.deepEqual(await detail(1), before, 'graph reads must preserve ledger revisions and references');
  assert.deepEqual(errors, []);
  cases.push('narrow layout, open main item and read-only ledger behavior');
  assert.deepEqual(failures, []);
  await context.tracing.stop({ path: `${evidence}/relationship-graph-trace.zip` });
  await writeFile(`${evidence}/relationship-graph-results.json`, JSON.stringify({ cases, errors }, null, 2));
  console.log(JSON.stringify({ cases, errors })); await context.close();
} catch (error) {
  if (failures.length > 0) console.error(JSON.stringify({ failures }, null, 2));
  throw error;
} finally { await browser.close(); }
