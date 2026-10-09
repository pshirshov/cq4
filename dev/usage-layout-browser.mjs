import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';

// D121: the usage tables keep every value on one line, right-align grouped numbers, carry a heading each and say "Unknown" for an unknown cost.
export async function usageLayoutChecks(browser, storageState, origin, evidence) {
  const id = () => ({ value: randomUUID() }); const project = id();
  const member = number => ({ project, ledger: 'Tasks', number: String(number) });
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(path, body) {
    const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const call = command => post('/api/call', command); const host = operation => post('/api/usage', { project, operation });
  await call({ Initialize: { config: { project, endpoint: origin, name: `Usage layout ${project.value}` } } });
  await call({ Change: { input: { project, change: { request: id(), fences: [], reason: 'Usage layout fixture', mutations: ['A', 'B', 'C'].map(name => ({ Create: { draft: {
    title: `Layout ${name}`, body: 'Usage layout fixture', labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Read the usage tables'], result: null, validation: [] } },
  } } })) } } } });
  const observed = n => ({ value: String(n), measurement: 'Observed' });
  const counts = n => ({ input: observed(n), output: observed(0), cacheRead: observed(0), cacheWrite: observed(0), reasoning: observed(0) });
  const unknownCost = { amount: null, currency: null, basis: 'Unknown', pricingVersion: null };
  const estimate = value => ({ amount: { value }, currency: 'USD', basis: 'ProviderEstimate', pricingVersion: null });
  const assign = async (attribution, members, cohort) => {
    const assignment = { id: id(), project, members, attribution, cohort, evaluation: null };
    await host({ Assign: { value: assignment } }); return assignment;
  };
  const direct = await assign('Direct', [member(1)], null);
  const shared = await assign('Shared', [member(1), member(2), member(3)], randomUUID());
  const review = await assign('Shared', [member(2), member(3)], randomUUID());
  const unattributed = await assign('Unattributed', [], null);
  // [assignment, phase, role, model, busy milliseconds or null while running, tokens or null without a meter, cost]
  const runs = [
    [unattributed, 'Govern', 'Governor', 'claude-opus-5-5', null, 3663474, unknownCost],
    [direct, 'Explore', 'Worker', 'gpt-5.4-codex', 1292000, 7450699, estimate('7.155')],
    [direct, 'Plan', 'Planner', 'claude-opus-5-5', 6960000, 18453939, estimate('35.911')],
    [direct, 'Work', 'Worker', 'gpt-5.4-codex', 74640000, 205344009, estimate('102.898')],
    [shared, 'Work', 'Worker', 'gpt-5.4-codex', 3600000, 72012800, estimate('32.4402')],
    [direct, 'Check', 'Worker', 'gpt-5.4-codex', 14040000, null, null],
    [review, 'Review', 'Reviewer', 'claude-opus-5-5', 23400000, 22610792, estimate('17.0945')],
  ];
  for (const [assignment, phase, role, model, busy, tokens, cost] of runs) {
    const attempt = { id: id(), assignment: assignment.id, parent: null, session: id(), role, harness: model.startsWith('claude') ? 'Claude' : 'Codex',
      provider: model.startsWith('claude') ? 'anthropic' : 'openai', model, collector: 'fixture', startedAt: '1000', phase, effort: null };
    await host({ Start: { value: attempt } });
    if (tokens !== null) {
      await host({ Meter: { value: { key: 'layout', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: unknownCost } } });
      await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: `${phase} fixture`, position: '1', occurredAt: '2000', receivedAt: '0',
        scope: 'Increment', counters: counts(tokens), inputIncludesCache: true, outputIncludesReasoning: true, cost,
        completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'layout', disposition: 'Contribution', detailReason: null } } });
    }
    if (busy !== null) await host({ Finish: { value: { request: id(), attempt: attempt.id, state: 'Completed', finishedAt: String(1000 + busy), gaps: [], supersedes: null } } });
  }
  const NUMERIC = {
    'Usage totals': ['Known tokens', 'Unknown measurements', 'Estimated measurements', 'Unknown costs'],
    'Costs': ['Amount', 'Measurements'],
    'Usage by phase': ['Attempts', 'Running', 'Open', 'Busy wall time', 'Known tokens', 'Unknown measurements', 'Estimated measurements', 'Unknown costs', 'Cost'],
    'Attempts': [], 'Outcome history': ['Sequence'], 'Usage audit': ['Input', 'Output', 'Cost'],
  };
  const HEADINGS = { 'Usage totals': 'Tokens by attribution', 'Costs': 'Cost by attribution', 'Usage by phase': 'By phase' };
  const context = await browser.newContext({ storageState, viewport: { width: 1366, height: 768 } });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  const page = await context.newPage(); const errors = []; const problems = [];
  page.on('pageerror', error => errors.push(String(error)));
  // One entry per cell of the table's own rows; the rows that hold an expanded details element are left out.
  const measure = table => table.evaluate(node => {
    const scroll = node.closest('.usage-scroll'); const section = scroll === null ? null : scroll.parentElement;
    const heading = section === null ? null : section.querySelector('h4'); const note = section === null ? null : section.querySelector('p');
    const names = Array.from(node.querySelectorAll(':scope > thead th'), cell => cell.textContent);
    const cells = [];
    for (const row of node.querySelectorAll(':scope > thead > tr, :scope > tbody > tr:not(.usage-expansion), :scope > tfoot > tr')) {
      if (row.hidden) continue;
      Array.from(row.children).forEach((cell, index) => {
        const style = getComputedStyle(cell); const range = document.createRange(); range.selectNodeContents(cell);
        const titled = cell.querySelector('[title]');
        cells.push({ part: row.parentElement.localName, row: row.children[0].textContent, column: names[index], text: cell.textContent,
          title: titled === null ? cell.title : titled.title, align: style.textAlign, numeric: style.fontVariantNumeric, color: style.color,
          lines: range.getBoundingClientRect().height / parseFloat(style.fontSize) });
      });
    }
    const container = node.closest('.dialog-body, .pane');
    return { heading: heading === null ? null : heading.textContent, note: note === null ? null : note.textContent, cells,
      width: node.getBoundingClientRect().width, container: container.clientWidth,
      overflow: scroll === null ? null : scroll.scrollWidth - scroll.clientWidth };
  });
  const TWO_LINES = 1.9;
  async function layout(surface, scope, name, { fits }) {
    const table = scope.getByRole('table', { name, exact: true }); await table.waitFor();
    const result = await measure(table); const where = `${surface} · ${name}`;
    const check = (condition, message) => { if (!condition) problems.push(`${where}: ${message}`); };
    if (name in HEADINGS) check(result.heading === HEADINGS[name], `heading is ${JSON.stringify(result.heading)}, expected ${JSON.stringify(HEADINGS[name])}`);
    check(result.overflow !== null, 'the table has no horizontal scroll container');
    if (fits) check(result.overflow === 0, `the table overflows its section by ${result.overflow}px`);
    for (const cell of result.cells) {
      const label = `${cell.part} ${JSON.stringify(cell.row)} / ${JSON.stringify(cell.column)} ${JSON.stringify(cell.text)}`;
      if (cell.part !== 'thead') check(cell.lines < TWO_LINES, `${label} wraps (${cell.lines.toFixed(2)} lines)`);
      const numeric = NUMERIC[name].includes(cell.column);
      check(cell.align === (numeric ? 'right' : 'left') || (!numeric && cell.align === 'start'), `${label} has text-align ${cell.align}`);
      if (numeric) check(cell.numeric.includes('tabular-nums'), `${label} has font-variant-numeric ${cell.numeric}`);
    }
    const cell = (row, column) => result.cells.find(entry => entry.part !== 'thead' && entry.row === row && entry.column === column);
    return { ...result, cell, check, text: (row, column, expected) => {
      const found = cell(row, column);
      check(found !== undefined && found.text === expected, `${JSON.stringify(row)} / ${JSON.stringify(column)} is ${JSON.stringify(found === undefined ? null : found.text)}, expected ${JSON.stringify(expected)}`);
    } };
  }
  const shot = (name, width) => page.screenshot({ path: `${evidence}/usage-layout-${name}-${width}.png` });
  const settle = () => page.getByText(/^Observed /).first().waitFor();
  // The opened row appears on the details element's toggle event, so wait for one of its fields.
  const expand = async (scope, summary, field) => {
    const first = scope.getByText(summary, { exact: true }).first(); await first.scrollIntoViewIfNeeded(); await first.click();
    await scope.getByText(field, { exact: true }).first().waitFor();
  };
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByRole('button', { name: 'T1 · Layout A', exact: true }).waitFor(); await page.getByText('Data: current', { exact: true }).waitFor();
    for (const [width, height] of [[1366, 768], [1920, 1080]]) {
      await page.setViewportSize({ width, height });
      await page.getByRole('button', { name: 'Project usage', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Project usage', exact: true });
      await dialog.getByRole('table', { name: 'Usage by phase', exact: true }).waitFor(); await settle();
      await shot('project', width);
      const surface = `project usage ${width}`;
      const totals = await layout(surface, dialog, 'Usage totals', { fits: true });
      totals.text('Direct', 'Known tokens', '231 248 647'); totals.text('Shared', 'Known tokens', '94 623 592');
      totals.text('Unattributed', 'Known tokens', '3 663 474'); totals.text('Unattributed', 'Unknown costs', '1');
      totals.text('Total', 'Known tokens', '329 535 713');
      totals.check(totals.width < totals.container * 0.8, `the table is ${totals.width}px wide in a ${totals.container}px container`);
      const zero = totals.cell('Direct', 'Unknown costs'); const known = totals.cell('Direct', 'Known tokens');
      totals.check(zero !== undefined && known !== undefined && zero.text === '0' && zero.color !== known.color, 'a zero value is not dimmed');
      const costs = await layout(surface, dialog, 'Costs', { fits: true });
      costs.text('Direct', 'Amount', '145.9640 USD'); costs.text('Shared', 'Amount', '49.5347 USD'); costs.text('Total', 'Amount', '195.4987 USD');
      const amount = costs.cell('Direct', 'Amount'); costs.check(amount !== undefined && amount.title === '145.964', 'the amount does not carry its exact value as title');
      costs.check(costs.note === 'Cost basis: ProviderEstimate.', `the basis note is ${JSON.stringify(costs.note)}`);
      const phases = await layout(surface, dialog, 'Usage by phase', { fits: true });
      phases.text('Work', 'Known tokens', '277 356 809'); phases.text('Work', 'Busy wall time', '21 h 44 min'); phases.text('Work', 'Cost', '135.3382 USD');
      phases.text('Govern', 'Cost', 'Unknown'); phases.text('Check', 'Cost', '—'); phases.text('Total', 'Known tokens', '329 535 713');
      phases.text('Total', 'Cost', '195.4987 USD');
      phases.check(phases.note === "Phase wall times overlap (the governor's attempt spans the session, a reviewer waits for its checks, an integration contains the checks of its commit), so the phase rows must not be added up. Cost basis: ProviderEstimate.", `the basis note is ${JSON.stringify(phases.note)}`);
      for (const result of [costs, phases]) result.check(result.cells.every(entry => !entry.text.includes('ProviderEstimate')), 'a cell repeats the cost basis');
      await expand(dialog, '2 shared assignments', /^T2, T3 · cohort /); await shot('project-shared', width);
      await dialog.getByRole('button', { name: 'Attempts', exact: true }).click();
      await dialog.getByRole('table', { name: 'Attempts', exact: true }).scrollIntoViewIfNeeded();
      await layout(`attempts ${width}`, dialog, 'Attempts', { fits: true }); await shot('attempts', width);
      await expand(dialog.getByRole('row').filter({ has: page.getByRole('cell', { name: 'Claude · Reviewer', exact: true }) }), 'Attempt details', 'Attempt details');
      await dialog.getByRole('button', { name: 'Outcome history', exact: true }).waitFor(); await shot('attempts-details', width);
      await dialog.getByRole('button', { name: 'Outcome history', exact: true }).click();
      await layout(`outcomes ${width}`, dialog, 'Outcome history', { fits: true }); await shot('outcomes', width);
      await dialog.getByRole('button', { name: 'Usage audit', exact: true }).click();
      await dialog.getByRole('table', { name: 'Usage audit', exact: true }).scrollIntoViewIfNeeded();
      const audit = await layout(`audit ${width}`, dialog, 'Usage audit', { fits: true }); await shot('audit', width);
      const auditCosts = audit.cells.filter(entry => entry.part === 'tbody' && entry.column === 'Cost').map(entry => entry.text).sort();
      audit.check(audit.note === 'Cost basis: ProviderEstimate.', `the basis note is ${JSON.stringify(audit.note)}`);
      audit.check(audit.cells.every(entry => !entry.text.includes('ProviderEstimate')), 'a cell repeats the cost basis');
      audit.check(JSON.stringify(auditCosts) === JSON.stringify(['102.8980 USD', '17.0945 USD', '32.4402 USD', '35.9110 USD', '7.1550 USD', 'Unknown']), `costs are ${JSON.stringify(auditCosts)}`);
      await expand(dialog, 'Observation details', 'Meter'); await shot('audit-details', width);
      await dialog.getByRole('button', { name: 'Close', exact: true }).click();
      await page.getByRole('button', { name: 'T1 · Layout A', exact: true }).click();
      await page.getByRole('heading', { name: 'T1 · Layout A', exact: true }).waitFor(); await page.getByRole('heading', { name: 'Usage · T1', exact: true }).waitFor();
      await settle(); await shot('item-header', width);
      const pane = page.locator('#detail-pane');
      await pane.getByRole('button', { name: 'Attempts', exact: true }).click();
      const attempts = pane.getByRole('table', { name: 'Attempts', exact: true }); await attempts.waitFor();
      await pane.getByRole('heading', { name: 'Usage · T1', exact: true }).scrollIntoViewIfNeeded(); await shot('item-pane', width);
      for (const name of ['Usage totals', 'Costs', 'Usage by phase']) await layout(`item pane ${width}`, pane, name, { fits: false });
      await expand(pane, 'Attempt details', 'Collector'); await pane.getByRole('button', { name: 'Task usage · T1', exact: true }).first().click();
      const scoped = page.getByRole('dialog', { name: 'Usage details', exact: true });
      await scoped.getByRole('table', { name: 'Usage by phase', exact: true }).waitFor(); await settle(); await shot('item', width);
      for (const name of ['Usage totals', 'Costs', 'Usage by phase']) await layout(`usage details ${width}`, scoped, name, { fits: true });
      await scoped.getByRole('button', { name: 'Close', exact: true }).click();
      await page.getByRole('button', { name: 'Close item view', exact: true }).click();
    }
    await page.setViewportSize({ width: 700, height: 768 });
    await page.getByRole('button', { name: 'Project usage', exact: true }).click();
    const narrow = page.getByRole('dialog', { name: 'Project usage', exact: true });
    await narrow.getByRole('table', { name: 'Usage by phase', exact: true }).waitFor(); await settle(); await shot('project', 700);
    const phases = await layout('project usage 700', narrow, 'Usage by phase', { fits: false });
    phases.check(phases.overflow !== null && phases.overflow > 0, 'the phase table does not scroll horizontally in its section');
    phases.check(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), 'the page overflows horizontally');
    await narrow.getByRole('button', { name: 'Close', exact: true }).click();
    assert.deepEqual(errors, []); assert.deepEqual(problems, []);
  } finally {
    await writeFile(`${evidence}/usage-layout-results.json`, JSON.stringify({ problems, errors, project }, null, 2));
    await context.tracing.stop({ path: `${evidence}/usage-layout.zip` }); await context.close();
  }
  console.log('Chromium usage layout: headings, one-line values, right-aligned grouped numbers, explicit unknown cost, content-sized tables at 1366 and 1920, horizontal scroll at 700');
}
