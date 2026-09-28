import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, receivedReply, settledRequests } from './browser-protocol.mjs';

export async function usageScopeChecks(browser, storageState, origin, evidence) {
  const id = () => ({ value: randomUUID() }); const project = id(); const cohort = randomUUID();
  const member = number => ({ project, ledger: 'Tasks', number: String(number) });
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(path, body) {
    const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const call = command => post('/api/call', command); const host = operation => post('/api/usage', { project, operation });
  await call({ Initialize: { config: { project, endpoint: origin, name: `Usage scopes ${project.value}` } } });
  await call({ Change: { input: { project, change: { request: id(), fences: [], reason: 'Usage scope fixture', mutations: ['A', 'B'].map(name => ({ Create: { draft: {
    title: `Scope ${name}`, body: 'Usage must preserve this item', labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Navigate actual accounting scopes'], result: null, validation: [] } },
  } } })) } } } });
  const detail = async number => (await call({ Read: { input: { project, selection: { ItemDetail: { id: member(number) } } } } })).Detail.view;
  const before = [await detail(1), await detail(2)];
  const zero = { value: '0', measurement: 'Observed' };
  const counts = n => ({ input: { value: String(n), measurement: 'Observed' }, output: zero, cacheRead: zero, cacheWrite: zero, reasoning: zero });
  const money = { amount: null, currency: null, basis: 'Unknown', pricingVersion: null }; const assignments = []; const attempts = [];
  for (const [attribution, members, execution, amount] of [['Shared', [member(1), member(2)], cohort, 100], ['Direct', [member(1)], cohort, 40], ['Unattributed', [], null, 7]]) {
    const assignment = { id: id(), project, attribution, members, cohort: execution, evaluation: null };
    const attempt = { id: id(), assignment: assignment.id, parent: null, session: id(), role: 'Worker', harness: 'Codex',
      provider: 'controlled-browser-fixture', model: 'no-model-call', collector: 'fixture', startedAt: '1000' };
    await host({ Assign: { value: assignment } }); await host({ Start: { value: attempt } });
    await host({ Meter: { value: { key: 'scope', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: money } } });
    await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: `${attribution} fixture`, position: '1', occurredAt: '2000', receivedAt: '0',
      scope: 'Increment', counters: counts(amount), inputIncludesCache: true, outputIncludesReasoning: true, cost: money,
      completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'scope', disposition: 'Contribution', detailReason: null } } });
    assignments.push(assignment); attempts.push(attempt);
  }
  const context = await browser.newContext({ storageState, viewport: { width: 1440, height: 1000 } });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true }); await trackProtocol(context);
  let holdNext = false; let held = null; let request = null; let capture;
  const captured = new Promise(resolve => { capture = resolve; });
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (holdNext && frame.Call && frame.Call.command.Usage && frame.Call.command.Usage.input.selection.Summary) {
        request = frame.Call.id.value; holdNext = false;
      }
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (frame.Reply && frame.Reply.id.value === request) { held = { route, message }; capture(); }
      else route.send(message);
    });
  });
  const page = await context.newPage(); const errors = []; const cases = [];
  page.on('pageerror', error => errors.push(String(error)));
  const click = name => page.getByRole('button', { name, exact: true }).click();
  async function summary(scope, direct, shared, unattributed) {
    await page.getByRole('heading', { name: `Usage · ${scope}`, exact: true }).waitFor(); await settledRequests(page);
    const metrics = page.getByRole('region', { name: 'Usage metrics', exact: true });
    assert.ok((await metrics.textContent()).includes(`Usage · ${scope}: ${direct} direct · ${shared} shared · ${unattributed} unattributed known tokens`));
    assert.ok((await metrics.textContent()).includes('cursor'));
  }
  try {
    await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await click('T1 · Scope A'); await summary('T1', 40, 100, 0);
    await click('Attempts'); await page.getByText(`Assignment ${assignments[0].id.value} · frozen members: T1, T2`, { exact: true }).waitFor();
    await click(`Session usage · ${attempts[0].session.value}`); await summary(`session ${attempts[0].session.value}`, 0, 100, 0);
    await click('Attempts'); await click(`Cohort usage · ${cohort}`); await summary(`cohort ${cohort}`, 40, 100, 0);
    await page.getByText(`Shared assignments: ${assignments[0].id.value}.`, { exact: true }).waitFor();
    await click('Attempts'); await click('Task usage · T2'); await summary('T2', 0, 100, 0);
    assert.equal(await page.getByRole('heading', { name: 'T1 · Scope A', exact: true }).count(), 1, 'Usage scope navigation retains the selected item');
    await click('Usage audit'); await page.getByText(/^\d+ · Shared fixture · Complete$/).waitFor();
    assert.equal(await page.getByText(/^\d+ · Direct fixture · Complete$/).count(), 0);
    await click('Selected item usage'); await summary('T1', 40, 100, 0);
    cases.push('session/cohort/member navigation, frozen membership and filtered audit preserve direct/shared attribution');
    await click('Project usage'); await summary('project', 40, 100, 7); await click('Attempts');
    holdNext = true; await click(`Session usage · ${attempts[0].session.value}`);
    let timer;
    try { await Promise.race([captured, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Missing held scope summary')), 10000); })]); }
    finally { clearTimeout(timer); }
    await click('Project usage'); await page.getByRole('heading', { name: 'Usage · project', exact: true }).waitFor();
    assert.notEqual(held, null); held.route.send(held.message); await receivedReply(page, request); await summary('project', 40, 100, 7);
    assert.equal(await page.getByRole('heading', { name: `Usage · session ${attempts[0].session.value}`, exact: true }).count(), 0);
    cases.push('delayed scope-only reply cannot replace a later project summary');
    assert.deepEqual([await detail(1), await detail(2)], before, 'Accounting and browsing do not revise items');
    assert.deepEqual(errors, []);
  } finally {
    await writeFile(`${evidence}/usage-scopes-results.json`, JSON.stringify({ cases, errors, project, cohort, assignments, attempts }, null, 2));
    await page.screenshot({ path: `${evidence}/usage-scopes.png`, fullPage: true });
    await context.tracing.stop({ path: `${evidence}/usage-scopes.zip` }); await context.close();
  }
  console.log('Chromium usage scopes: project/session/cohort/task links, frozen membership, shared accounting and obsolete summary ownership');
}
