import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

export async function usageChecks(page, origin, projectId) {
  const project = { value: projectId };
  const member = { project, ledger: 'Tasks', number: '1' };
  const id = () => ({ value: randomUUID() });
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(path, body) {
    const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const host = operation => post('/api/usage', { project, operation });
  const detail = () => post('/api/call', { Read: { input: { project, selection: { ItemDetail: { id: member } } } } });
  const before = await detail();
  const phases = page.getByRole('table', { name: 'Usage by phase', exact: true });
  const phaseTruncation = page.getByText('Per-phase costs are truncated', { exact: false });
  async function phaseRows(expected) {
    const read = () => phases.locator('tbody tr').evaluateAll(rows => rows.map(row => Array.from(row.querySelectorAll('th, td'), cell => cell.textContent)));
    const deadline = Date.now() + 5000;
    while (JSON.stringify(await read()) !== JSON.stringify(expected) && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
    assert.deepEqual(await read(), expected);
    assert.equal(await phases.count(), 1, 'Usage refresh keeps one phase table');
  }
  await page.getByRole('button', { name: 'Project usage', exact: true }).click();
  await page.getByText('Attempt coverage: 0 running; 0 open; 0 unknown outcomes; 0 with reported gaps.', { exact: true }).waitFor();
  assert.equal(await phases.count(), 0, 'An empty phase report renders no table');
  await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
  const assignment = { id: id(), project, members: [member], attribution: 'Direct', cohort: null, evaluation: null };
  const attempt = { id: id(), assignment: assignment.id, parent: null, session: id(), role: 'Worker', harness: 'Codex',
    provider: 'controlled-browser-fixture', model: 'no-model-call', collector: 'fixture', startedAt: '1000', phase: 'Work', effort: null };
  await host({ Assign: { value: assignment } }); await host({ Start: { value: attempt } });
  await page.getByRole('button', { name: 'Project usage', exact: true }).click();
  await page.getByText('Attempt coverage: 1 running; 0 open; 0 unknown outcomes; 0 with reported gaps.', { exact: true }).waitFor();
  await phaseRows([['Work', '0', '0', '0', '0', '—', '1', '1', '0', '0 s']]);
  // An attempt that has no measurement counts no tokens: the page says that this is not zero usage, and when that is known to be so.
  await page.getByText('attempts without measurements: 1. An attempt without a measurement counts no tokens here, which is not zero usage. ' +
    'A Governor\'s own work and its own review are never measured: their tokens are part of the governing session\'s.', { exact: false }).waitFor();
  await page.getByRole('button', { name: 'Attempts', exact: true }).click();
  await page.getByRole('table', {name: 'Attempts', exact: true}).getByRole('cell', {name: 'Running', exact: true}).waitFor();
  await page.getByText('Attempt details', {exact: true}).click();
  await page.getByRole('button', { name: 'Outcome history', exact: true }).click();
  await page.getByText('No outcome recorded yet.', { exact: true }).waitFor();
  await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
  const query = page.getByLabel('Search query', { exact: true });
  await query.fill('alpha AND'); await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByText('Data: invalid query', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Project usage', exact: true }).click();
  const counter = n => ({ value: String(n), measurement: 'Observed' });
  const counts = n => ({ input: counter(n), output: counter(0), cacheRead: counter(0), cacheWrite: counter(0), reasoning: counter(0) });
  const cost = { amount: null, currency: null, basis: 'Unknown', pricingVersion: null };
  await host({ Meter: { value: { key: 'fixture', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: cost } } });
  await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: 'fixture', position: '1', occurredAt: '2000', receivedAt: '0',
    scope: 'Increment', counters: counts(100), inputIncludesCache: true, outputIncludesReasoning: true, cost,
    completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'fixture', disposition: 'Contribution', detailReason: null } } });
  const outcome = { request: id(), attempt: attempt.id, state: 'Cancelled', finishedAt: '3000', gaps: ['Final request usage unavailable'], supersedes: null };
  await host({ Finish: { value: outcome } });
  await page.getByText('Attempt coverage: 0 running; 0 open; 0 unknown outcomes; 1 with reported gaps.', { exact: true }).waitFor();
  await phaseRows([['Work', '100', '0', '0', '1', 'Unknown', '1', '0', '0', '2 s']]);
  await phases.getByTitle('2000 ms', { exact: true }).waitFor();
  assert.equal(await phaseTruncation.count(), 0);
  await page.getByRole('button', { name: 'Attempts', exact: true }).click();
  await page.getByText('Attempt details', {exact: true}).click();
  await page.getByText('Final request usage unavailable', { exact: true }).waitFor();
  const completed = { ...outcome, request: id(), state: 'Completed', finishedAt: '2500', gaps: [], supersedes: outcome.request };
  await host({ Finish: { value: completed } });
  await page.getByText('Attempt coverage: 0 running; 0 open; 0 unknown outcomes; 0 with reported gaps.', { exact: true }).waitFor();
  await page.getByRole('table', {name: 'Attempts', exact: true}).getByRole('cell', {name: 'Completed', exact: true}).waitFor();
  await page.getByText('Attempt details', {exact: true}).click();
  await page.getByRole('button', { name: 'Outcome history', exact: true }).click();
  await page.getByRole('table', {name: 'Outcome history', exact: true}).getByRole('cell', {name: 'Cancelled', exact: true}).waitFor();
  await page.getByRole('table', {name: 'Outcome history', exact: true}).getByRole('cell', {name: 'Completed', exact: true}).waitFor();
  await host({ Finish: { value: { ...completed, request: id(), finishedAt: '2600', supersedes: completed.request } } });
  await page.getByRole('table', {name: 'Outcome history', exact: true}).getByRole('cell', {name: 'Completed', exact: true}).nth(1).waitFor();
  for (let index = 0; index < 201; index++) {
    await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: 'fixture', position: String(index + 2), occurredAt: '4000', receivedAt: '0',
      scope: 'Increment', counters: counts(1), inputIncludesCache: true, outputIncludesReasoning: true,
      cost: { amount: { value: '0.01' }, currency: 'USD', basis: 'ProviderEstimate', pricingVersion: `price-${String(index).padStart(3, '0')}` },
      completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'fixture', disposition: 'Contribution', detailReason: null } } });
  }
  await page.getByRole('table', { name: 'Usage totals' }).getByRole('row').filter({ has: page.getByRole('rowheader', { name: 'Direct', exact: true }) }).getByRole('cell', { name: '301', exact: true }).waitFor();
  await page.getByText(/^Snapshot cursor (\d+); latest observed usage cursor \1\.$/).waitFor();
  assert.equal(await page.getByText('Data: invalid query', { exact: true }).count(), 1, 'Usage refresh is independent of query validity');
  await page.getByRole('button', { name: 'More costs', exact: true }).click();
  await page.getByRole('heading', { name: 'Cost breakdown', exact: true }).waitFor();
  await page.getByRole('table', { name: 'Costs' }).getByRole('row').filter({ has: page.getByRole('cell', { name: 'price-200', exact: true }) }).getByRole('cell', { name: '0.0100 USD', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Next cost page', exact: true }).count(), 0);
  await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: 'fixture', position: '203', occurredAt: '5000', receivedAt: '0',
    scope: 'Increment', counters: counts(1), inputIncludesCache: true, outputIncludesReasoning: true,
    cost: { amount: { value: '0.02' }, currency: 'USD', basis: 'ProviderEstimate', pricingVersion: 'price-200' },
    completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'fixture', disposition: 'Contribution', detailReason: null } } });
  await page.getByRole('table', { name: 'Costs' }).getByRole('row').filter({ has: page.getByRole('cell', { name: 'price-200', exact: true }) }).getByRole('cell', { name: '0.0300 USD', exact: true }).waitFor();
  await phaseRows([['Work', '302', '0', '0', '1', '2.0000 USD', '1', '0', '0', '1 s']]);
  await phases.getByTitle('1600 ms', { exact: true }).waitFor();
  assert.equal(await phases.getByTitle('2', { exact: true }).textContent(), '2.0000 USD');
  await phases.locator('..').locator('..').getByText('Cost basis: ProviderEstimate.', { exact: true }).waitFor();
  await phaseTruncation.waitFor();
  const timed = [['Review', 'phase-review', 3720000], ['Plan', 'phase-plan', 200000], ['Probe', 'phase-probe', 45000]].map(([phase, model, finishedAt]) =>
    ({ attempt: { ...attempt, id: id(), session: id(), model, startedAt: '0', phase, effort: phase === 'Plan' ? 'XHigh' : null }, finishedAt }));
  for (const entry of timed) {
    await host({ Start: { value: entry.attempt } });
    await host({ Finish: { value: { request: id(), attempt: entry.attempt.id, state: 'Completed', finishedAt: String(entry.finishedAt), gaps: [], supersedes: null } } });
  }
  await host({ Start: { value: { ...attempt, id: id(), session: id(), phase: 'Review' } } });
  await phaseRows([['Probe', '0', '0', '0', '0', '—', '1', '0', '0', '45 s'], ['Plan', '0', '0', '0', '0', '—', '1', '0', '0', '3 min 20 s'],
    ['Work', '302', '0', '0', '1', '2.0000 USD', '1', '0', '0', '1 s'], ['Review', '0', '0', '0', '0', '—', '2', '1', '0', '1 h 02 min']]);
  await phases.getByTitle('3720000 ms', { exact: true }).waitFor();
  // D150: an attached session's governing attempt without an outcome is open, not running.
  const overhead = { id: id(), project, members: [], attribution: 'Unattributed', cohort: null, evaluation: null };
  const governing = { ...attempt, id: id(), assignment: overhead.id, session: id(), role: 'Governor', collector: 'CQ attached session; outer usage unavailable', model: 'phase-govern', phase: 'Govern' };
  await host({ Assign: { value: overhead } }); await host({ Start: { value: governing } });
  await page.getByText('Attempt coverage: 1 running; 1 open; 0 unknown outcomes; 0 with reported gaps.', { exact: true }).waitFor();
  await page.getByText('Open: governing attempts of attached sessions. No outcome delivered.', { exact: false }).waitFor();
  await phaseRows([['Govern', '0', '0', '0', '0', '—', '1', '0', '1', '0 s'], ['Probe', '0', '0', '0', '0', '—', '1', '0', '0', '45 s'], ['Plan', '0', '0', '0', '0', '—', '1', '0', '0', '3 min 20 s'],
    ['Work', '302', '0', '0', '1', '2.0000 USD', '1', '0', '0', '1 s'], ['Review', '0', '0', '0', '0', '—', '2', '1', '0', '1 h 02 min']]);
  await page.getByRole('button', { name: 'Attempts', exact: true }).click();
  await page.getByRole('table', { name: 'Attempts', exact: true }).getByRole('row').filter({ has: page.getByRole('cell', { name: 'controlled-browser-fixture / phase-govern', exact: true }) }).getByRole('cell', { name: 'Open', exact: true }).waitFor();
  // The effort an attempt was launched with stands beside its provider and model; an attempt that stated none shows none.
  await page.getByRole('table', { name: 'Attempts', exact: true }).getByRole('cell', { name: 'controlled-browser-fixture / phase-plan · effort xhigh', exact: true }).waitFor();
  await page.getByRole('table', { name: 'Attempts', exact: true }).getByRole('row').filter({ has: page.getByRole('cell', { name: 'controlled-browser-fixture / phase-probe', exact: true }) }).getByText('Attempt details', { exact: true }).click();
  await page.getByRole('button', { name: `Session usage · ${timed[2].attempt.session.value}`, exact: true }).click();
  await page.getByRole('heading', { name: `Usage · session ${timed[2].attempt.session.value}`, exact: true }).waitFor();
  await phaseRows([['Probe', '0', '0', '0', '0', '—', '1', '0', '0', '45 s']]);
  assert.equal(await phaseTruncation.count(), 0);
  assert.deepEqual(await detail(), before, 'Usage lifecycle writes must not revise the item');
  await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
  await query.fill(''); await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByText('Data: current', { exact: true }).waitFor();
  console.log('Chromium usage: live independent updates through invalid query, live attempts/outcomes/cost corrections, per-phase table (empty, live, truncated costs, session scope), current audit labels, paginated exact costs and unchanged item revision passed');
}
