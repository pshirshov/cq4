import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [phase, file] = process.argv.slice(2);
const origin = process.env.CQ_ORIGIN;
const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'Content-Type': 'application/json',
  'CQ-Protocol-Version': '0.1.0', 'CQ-Session': process.env.CQ_SESSION };
const id = () => ({ value: randomUUID() });
const draft = title => ({ title, body: '', labels: [], archived: false, citations: [],
  content: { Task: { status: 'Ready', acceptance: ['Durable across restart'], result: null, validation: [] } } });
async function post(path, body) {
  const response = await fetch(origin + path, { method: 'POST', headers, body: JSON.stringify(body) });
  assert.equal(response.status, 200, await response.clone().text());
  const result = await response.json();
  assert.equal(result.Failed, undefined, JSON.stringify(result));
  return result;
}
const call = command => post('/api/call', command);
const change = (project, mutations, fences) => ({ Change: { input: { project,
  change: { request: id(), mutations, fences, reason: 'Crash persistence check' } } } });
const create = (project, title) => change(project, [{ Create: { draft: draft(title) } }], []);
const read = item => call({ Read: { input: { project: item.project, selection: { ItemDetail: { id: item } } } } });
const counter = n => ({ value: String(n), measurement: 'Observed' });
const counts = n => ({ input: counter(n), output: counter(0), cacheRead: counter(0), cacheWrite: counter(0), reasoning: counter(0) });
const unknownCost = { amount: null, currency: null, basis: 'Unknown', pricingVersion: null };

if (phase === 'seed') {
  const project = id();
  await call({ Initialize: { config: { project, endpoint: origin, name: 'Crash persistence' } } });
  const concurrent = await Promise.all(Array.from({ length: 8 }, (_, n) => call(create(project, `Concurrent ${n}`))));
  assert.deepEqual(concurrent.map(r => Number(r.Changed.ack.items[0].id.number)).sort((a, b) => a - b), [1, 2, 3, 4, 5, 6, 7, 8]);
  const operation = create(project, 'Stable acknowledgement');
  const ack = await call(operation);
  const retries = await Promise.all(Array.from({ length: 4 }, () => call(operation)));
  assert.ok(retries.every(r => JSON.stringify(r) === JSON.stringify(ack)));
  const source = ack.Changed.ack.items[0];
  const target = concurrent[0].Changed.ack.items[0];
  await call(change(project, [{ Reference: { source: source.id, expectedSource: source.revision,
    relation: 'BlockedBy', target: target.id, expectedTarget: target.revision, present: true } }], []));
  const claimInput = { project, action: { Acquire: { id: id(), members: [source.id], durationMillis: '300000' } } };
  const claim = await call({ ClaimWork: { input: claimInput } });
  const renamed = await call({ RenameProject: { project, expected: { value: '1' }, name: 'Renamed before crash' } });
  const assignment = { id: id(), project, members: [source.id], attribution: 'Direct', cohort: null, evaluation: null };
  const attempt = { id: id(), assignment: assignment.id, parent: null, session: { value: process.env.CQ_SESSION },
    role: 'Worker', harness: 'Codex', provider: 'controlled-fixture', model: 'no-model-call', collector: 'restart-check', startedAt: '1000' };
  const meter = { key: 'restart', attempt: attempt.id, scope: 'Increment', baseline: counts(0), baselineCost: unknownCost };
  const upload = { observation: { id: id(), attempt: attempt.id, source: 'restart', position: '1', occurredAt: '2000', receivedAt: '0',
    scope: 'Increment', counters: counts(250), inputIncludesCache: true, outputIncludesReasoning: true, cost: unknownCost,
    completeness: 'Complete', gaps: [], evidence: null, supersedes: null }, meter: meter.key, disposition: 'Contribution', detailReason: null };
  for (const operation of [{ Assign: { value: assignment } }, { Start: { value: attempt } }, { Meter: { value: meter } }])
    await post('/api/usage', { project, operation });
  const artifact = { project, id: id(), attempt: attempt.id, kind: 'Result', mediaType: 'text/plain', body: 'Durable result λ😀\u0000' };
  const artifactMetadata = await post('/api/artifact', artifact);
  upload.observation.evidence = artifact.id;
  const receipt = await post('/api/usage', { project, operation: { Ingest: { value: upload } } });
  writeFileSync(file, JSON.stringify({ project, operation, ack, source, target, claimInput, claim, renamed, upload, receipt, artifact, artifactMetadata }, null, 2));
  console.log('Persisted concurrent counters, request acknowledgement, inverse edge, claim, renamed project and usage observation');
} else if (phase === 'verify') {
  const state = JSON.parse(readFileSync(file, 'utf8'));
  const { project, source, target } = state;
  assert.deepEqual(await call(state.operation), state.ack);
  assert.equal((await call(create(project, 'After crash'))).Changed.ack.items[0].id.number, '10');
  assert.deepEqual(await call({ Initialize: { config: { project, endpoint: origin, name: 'Reattached' } } }), state.renamed);
  assert.deepEqual(await call({ ClaimWork: { input: state.claimInput } }), state.claim);
  assert.deepEqual((await read(source.id)).Detail.view.refs, [{ relation: 'BlockedBy', target: target.id }]);
  assert.deepEqual((await read(target.id)).Detail.view.refs, [{ relation: 'Blocks', target: source.id }]);
  const history = await call({ Read: { input: { project, selection: { History: { id: source.id, before: { value: '9223372036854775807' }, limit: 20 } } } } });
  assert.deepEqual(history.History.page.entries.map(e => e.item.item.revision.value), ['2', '1']);
  assert.deepEqual(await post('/api/usage', { project, operation: { Ingest: { value: state.upload } } }), state.receipt);
  assert.deepEqual(await post('/api/artifact', state.artifact), state.artifactMetadata);
  const artifact = await call({ Read: { input: { project, selection: { ArtifactText: { id: state.artifact.id, offset: 0, limit: 8192 } } } } });
  assert.deepEqual(artifact.ArtifactText.page.metadata, state.artifactMetadata);
  assert.equal(artifact.ArtifactText.page.text, state.artifact.body);
  const usage = await call({ Usage: { input: { project, selection: { Summary: { filter: { ProjectAll: {} } } } } } });
  assert.equal(usage.UsageSummary.report.direct.total.known, '250');
  assert.equal(usage.UsageSummary.report.direct.unknownCosts, '1');
  const audit = await call({ Usage: { input: { project, selection: { Audit: { filter: { ProjectAll: {} }, after: '0', limit: 20 } } } } });
  assert.equal(audit.UsageAudit.page.entries.length, 1);
  console.log('Fresh server after SIGKILL preserved counters, idempotency, project identity, history, inverse references, claims, usage and artifact bytes/receipts');
} else throw new Error('Expected seed or verify');
