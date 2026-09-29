import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, receivedReply } from './browser-protocol.mjs';

export async function selectionChecks(browser, storageState, origin, evidence) {
  const id = () => ({ value: randomUUID() });
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(path, body) {
    const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  async function project(name, titles) {
    const project = id();
    await post('/api/call', { Initialize: { config: { project, endpoint: origin, name } } });
    const mutations = titles.map(title => ({ Create: { draft: { title, body: 'Selection reply fixture', labels: [], archived: false, citations: [],
      content: { Task: { status: 'Ready', acceptance: ['Keep response ownership'], result: null, validation: [] } } } } }));
    const result = await post('/api/call', { Change: { input: { project,
      change: { request: id(), mutations, fences: [], reason: 'Selection fixture' } } } });
    return { project, members: result.Changed.ack.items.map(item => item.id) };
  }
  const first = await project(`Selection ${randomUUID()}`, ['Selection A', 'Selection B']);
  const second = await project(`Selection ${randomUUID()}`, ['Selection C']);
  for (const [member, amount] of [[first.members[0], 11], [first.members[1], 22], [second.members[0], 33]]) {
    const host = operation => post('/api/usage', { project: member.project, operation });
    const assignment = { id: id(), project: member.project, members: [member], attribution: 'Direct', cohort: null, evaluation: null };
    const attempt = { id: id(), assignment: assignment.id, parent: null, session: id(), role: 'Worker', harness: 'Codex',
      provider: 'controlled-browser-fixture', model: 'no-model-call', collector: 'fixture', startedAt: '1000' };
    const counter = value => ({ value: String(value), measurement: 'Observed' });
    const counts = input => ({ input: counter(input), output: counter(0), cacheRead: counter(0), cacheWrite: counter(0), reasoning: counter(0) });
    const cost = { amount: null, currency: null, basis: 'Unknown', pricingVersion: null };
    await host({ Assign: { value: assignment } });
    await host({ Start: { value: attempt } });
    await host({ Meter: { value: { key: 'selection', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: cost } } });
    await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: 'selection', position: '1', occurredAt: '2000', receivedAt: '0',
      scope: 'Increment', counters: counts(amount), inputIncludesCache: true, outputIncludesReasoning: true, cost,
      completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'selection', disposition: 'Contribution', detailReason: null } } });
  }
  const outcomes = [];
  for (const scenario of ['project-watch', 'project-list', 'detail', 'history', 'usage', 'project-history', 'project-usage', 'project-audit', 'audit', 'same-audit', 'same-detail']) {
    const context = await browser.newContext({ storageState });
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
    await trackProtocol(context);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    let predicate = null; let failWatch = false;
    let request = null;
    let held = null;
    let acknowledge;
    const captured = new Promise(resolve => { acknowledge = resolve; });
    const exchanges = [];
    await context.routeWebSocket(/\/ws$/, route => {
      const server = route.connectToServer();
      route.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (failWatch && frame.Watch && frame.Watch.scope.project !== null && frame.Watch.scope.project.value === first.project.value) {
          failWatch = false; request = frame.Watch.id.value; frame.Watch.scope.project.value = randomUUID();
          exchanges.push({ request, command: frame }); server.send(JSON.stringify(frame)); return;
        }
        if (predicate !== null && frame.Call && predicate(frame.Call.command)) {
          assert.equal(request, null); request = frame.Call.id.value; predicate = null;
          exchanges.push({ request, command: frame.Call.command });
        }
        server.send(message);
      });
      server.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (frame.Resync && frame.Resync.subscription.value === request) {
          assert.equal(held, null); held = { route, message };
          exchanges.push({ request, result: { Failed: { fault: frame.Resync.fault } } }); acknowledge();
        } else if (frame.Reply && frame.Reply.id.value === request) {
          assert.equal(held, null); held = { route, message };
          exchanges.push({ request, result: frame.Reply.result }); acknowledge();
        } else route.send(message);
      });
    });
    const row = name => page.getByRole('button', { name, exact: true });
    const heading = name => page.getByRole('heading', { name, exact: true });
    const usage = amount => page.getByRole('table', { name: 'Usage totals', includeHidden: true }).getByRole('row', { includeHidden: true }).filter({ has: page.getByRole('rowheader', { name: 'Direct', exact: true, includeHidden: true }) }).getByRole('cell', { name: String(amount), exact: true, includeHidden: true });
    async function capturedReply() {
      let timer;
      try {
        await Promise.race([captured, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Expected delayed server reply')), 10000); })]);
      } finally { clearTimeout(timer); }
    }
    try {
      await page.goto(origin);
      await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
      await page.getByLabel('Project', { exact: true }).selectOption(first.project.value);
      await row('T1 · Selection A').waitFor();
      await page.getByText('Data: current', { exact: true }).waitFor();
      if (scenario === 'project-watch') {
        await page.getByLabel('Project', { exact: true }).selectOption(second.project.value); await row('T1 · Selection C').waitFor();
        failWatch = true; await page.getByLabel('Project', { exact: true }).selectOption(first.project.value); await capturedReply();
        assert.ok(exchanges.at(-1).result.Failed, 'Hold an actual server rejection for an missing watched project');
        await page.getByLabel('Project', { exact: true }).selectOption(second.project.value);
        held.route.send(held.message); await row('T1 · Selection C').waitFor(); await page.getByText('Data: current', { exact: true }).waitFor();
        assert.equal(await page.getByRole('alert').count(), 0, 'An obsolete watch failure must not surface in the new project');
        assert.deepEqual(errors, []); outcomes.push({ scenario, status: 'passed', exchanges }); continue;
      }
      if (scenario === 'project-list') {
        predicate = command => command.Read && command.Read.input.selection.Browse && command.Read.input.project.value === second.project.value;
        await page.getByLabel('Project', { exact: true }).selectOption(second.project.value); await capturedReply();
        assert.equal(await row('T1 · Selection A').count(), 0, 'Old project rows cannot remain actionable while the new project loads');
        held.route.send(held.message); await row('T1 · Selection C').click(); await heading('T1 · Selection C').waitFor();
        assert.equal(await page.getByRole('alert').count(), 0); assert.deepEqual(errors, []);
        outcomes.push({ scenario, status: 'passed', exchanges }); continue;
      }
      if (scenario.endsWith('detail')) {
        predicate = command => command.Read && command.Read.input.selection.ItemDetail && command.Read.input.selection.ItemDetail.id.number === '1';
      } else if (scenario.endsWith('usage')) {
        predicate = command => command.Usage && command.Usage.input.selection.Summary && command.Usage.input.selection.Summary.filter.TaskOnly &&
          command.Usage.input.selection.Summary.filter.TaskOnly.item.number === '1';
      }
      await row('T1 · Selection A').click();
      if (scenario.endsWith('history')) {
        await usage(11).waitFor();
        await row('History').click();
        await heading('History · T1').waitFor();
        await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
        predicate = command => command.Read && command.Read.input.selection.History;
        await row('History').click();
      } else if (scenario.endsWith('audit')) {
        await usage(11).waitFor();
        predicate = command => command.Usage && command.Usage.input.selection.Audit;
        await row('Usage audit').click();
      }
      await capturedReply();
      if (scenario.endsWith('history')) await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
      const projectChange = scenario.startsWith('project-');
      const sameItem = scenario.startsWith('same-');
      const target = projectChange ? 'T1 · Selection C' : sameItem ? (scenario === 'same-detail' ? 'T1 · Selection A updated' : 'T1 · Selection A') : 'T2 · Selection B';
      if (scenario === 'same-detail') {
        const previous = await post('/api/call', { Read: { input: { project: first.project, selection: { ItemDetail: { id: first.members[0] } } } } });
        const item = previous.Detail.view.item;
        await post('/api/call', { Change: { input: { project: first.project, change: { request: id(), fences: [], reason: 'Concurrent selection update',
          mutations: [{ Replace: { id: item.id, expected: item.revision, draft: { ...item.draft, title: 'Selection A updated' } } }] } } } });
        await row(target).waitFor();
        await page.getByText('Data: current', { exact: true }).waitFor();
      }
      if (projectChange) {
        await page.getByLabel('Project', { exact: true }).selectOption(second.project.value);
        await row(target).waitFor();
        await page.getByText('Data: current', { exact: true }).waitFor();
      }
      await row(target).click();
      await heading(target).waitFor();
      const expectedUsage = projectChange ? 33 : sameItem ? 11 : 22;
      await usage(expectedUsage).waitFor();
      await heading(projectChange || sameItem ? 'Usage · T1' : 'Usage · T2').waitFor();
      const historyName = projectChange ? 'History · T1' : 'History · T2';
      if (scenario.endsWith('history')) {
        assert.equal(await heading('History · T1').count(), 0, 'Changing selection must clear the previous visible history');
        await row('History').click();
        await heading(historyName).waitFor();
      } else if (scenario.endsWith('audit') && !sameItem) {
        await row('Usage audit').click();
        await heading('Usage audit').waitFor();
      }
      assert.notEqual(held, null);
      held.route.send(held.message);
      await receivedReply(page, request);
      assert.equal(await page.getByRole('heading', { name: target, exact: true, includeHidden: true }).count(), 1, 'Late detail must not replace the newer selected item');
      assert.equal(await usage(expectedUsage).count(), 1, 'Late usage must not replace the newer scope');
      if (scenario.endsWith('history')) {
        assert.equal(await heading(historyName).count(), 1, 'Late history must not replace the newer scope');
        await page.getByRole('region', { name: 'Historical revision', exact: true }).getByRole('heading', { name: `${projectChange ? 'T1' : 'T2'} · Selection ${projectChange ? 'C' : 'B'} · revision 1`, exact: true }).waitFor();
      } else if (scenario.endsWith('audit')) {
        await heading('Usage audit').waitFor({ timeout: 5000 });
        const records = page.getByRole('table', { name: 'Usage audit', exact: true }).locator(':scope > tbody > tr > td:nth-child(4)');
        await records.first().waitFor();
        assert.deepEqual(await records.allTextContents(), [`${expectedUsage} · Observed`], 'Late audit must not replace the newer scope');
      }
      assert.deepEqual(errors, []);
      outcomes.push({ scenario, status: 'passed', exchanges });
    } catch (error) {
      outcomes.push({ scenario, status: 'failed', error: String(error), exchanges, pageErrors: errors });
    } finally {
      await page.screenshot({ path: `${evidence}/selection-${scenario}.png`, fullPage: true });
      await context.tracing.stop({ path: `${evidence}/selection-${scenario}.zip` });
      await context.close();
    }
  }
  await writeFile(`${evidence}/selection-results.json`, JSON.stringify(outcomes, null, 2));
  assert.deepEqual(outcomes.filter(value => value.status === 'failed').map(value => ({ scenario: value.scenario, error: value.error })), []);
  console.log('Chromium selection: delayed detail/history/usage replies preserve newer item and project ownership');
}
