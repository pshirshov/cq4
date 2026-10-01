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
  const assignment = { id: id(), project, members: [member], attribution: 'Direct', cohort: null, evaluation: null };
  const attempt = { id: id(), assignment: assignment.id, parent: null, session: id(), role: 'Worker', harness: 'Codex',
    provider: 'controlled-browser-fixture', model: 'no-model-call', collector: 'fixture', startedAt: '1000' };
  await host({ Assign: { value: assignment } }); await host({ Start: { value: attempt } });
  await page.getByRole('button', { name: 'Project usage', exact: true }).click();
  await page.getByText('Attempt coverage: 1 running; 0 unknown outcomes; 0 with reported gaps.', { exact: true }).waitFor();
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
  await page.getByText('Attempt coverage: 0 running; 0 unknown outcomes; 1 with reported gaps.', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Attempts', exact: true }).click();
  await page.getByText('Attempt details', {exact: true}).click();
  await page.getByText('Final request usage unavailable', { exact: true }).waitFor();
  const completed = { ...outcome, request: id(), state: 'Completed', finishedAt: '2500', gaps: [], supersedes: outcome.request };
  await host({ Finish: { value: completed } });
  await page.getByText('Attempt coverage: 0 running; 0 unknown outcomes; 0 with reported gaps.', { exact: true }).waitFor();
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
  await page.getByRole('table', { name: 'Costs' }).getByRole('row').filter({ has: page.getByRole('cell', { name: 'price-200', exact: true }) }).getByRole('cell', { name: '0.0100', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Next cost page', exact: true }).count(), 0);
  await host({ Ingest: { value: { observation: { id: id(), attempt: attempt.id, source: 'fixture', position: '203', occurredAt: '5000', receivedAt: '0',
    scope: 'Increment', counters: counts(1), inputIncludesCache: true, outputIncludesReasoning: true,
    cost: { amount: { value: '0.02' }, currency: 'USD', basis: 'ProviderEstimate', pricingVersion: 'price-200' },
    completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: 'fixture', disposition: 'Contribution', detailReason: null } } });
  await page.getByRole('table', { name: 'Costs' }).getByRole('row').filter({ has: page.getByRole('cell', { name: 'price-200', exact: true }) }).getByRole('cell', { name: '0.0300', exact: true }).waitFor();
  assert.deepEqual(await detail(), before, 'Usage lifecycle writes must not revise the item');
  await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click();
  await query.fill(''); await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByText('Data: current', { exact: true }).waitFor();
  console.log('Chromium usage: live independent updates through invalid query, live attempts/outcomes/cost corrections, current audit labels, paginated exact costs and unchanged item revision passed');
}
