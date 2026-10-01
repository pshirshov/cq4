import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';
import { itemName } from './items.js';
import { formatAmount, MoneyDigits } from './money.js';

type Cell = string | HTMLElement;
function table(label: string, headings: string[], rows: Cell[][]): HTMLTableElement {
  const result = element('table', ''); result.className = 'usage-table'; result.setAttribute('aria-label', label);
  const head = element('thead', ''); const titles = element('tr', '');
  for (const heading of headings) { const cell = element('th', heading); cell.scope = 'col'; titles.append(cell); }
  head.append(titles); const body = element('tbody', '');
  for (const values of rows) {
    const row = element('tr', '');
    const expanded: HTMLTableRowElement[] = [];
    for (const value of values) {
      const cell = element('td', ''); cell.append(value); row.append(cell);
      if (value instanceof HTMLDetailsElement) {
        const extra = element('tr', ''); extra.hidden = true; const content = element('td', ''); content.colSpan = headings.length;
        content.append(...Array.from(value.children).slice(1)); extra.append(content); expanded.push(extra);
        value.addEventListener('toggle', () => { extra.hidden = !value.open; });
      }
    }
    body.append(row, ...expanded);
  }
  result.append(head, body); return result;
}
function fields(values: [string, string][]): HTMLDListElement {
  const result = element('dl', ''); result.className = 'usage-fields';
  for (const [label, value] of values) result.append(element('dt', label), element('dd', value));
  return result;
}
function details(label: string, ...content: HTMLElement[]): HTMLDetailsElement {
  const result = element('details', ''); result.append(element('summary', label), ...content); return result;
}
function time(value: bigint): string { return new Date(Number(value)).toLocaleString(); }
function counter(value: api.Counter): string { return `${value.value === undefined ? 'Unknown' : value.value.toLocaleString()} · ${value.measurement}`; }
function money(value: api.Money): HTMLElement {
  const amount = value.amount === undefined ? 'Unknown' : formatAmount(value.amount.value, MoneyDigits);
  const result = element('span', `${amount} ${value.currency === undefined ? '' : value.currency} · ${value.basis}`.trim());
  if (value.amount !== undefined) result.title = value.amount.value;
  return result;
}
function tokens(label: string, counts: api.TokenCounts): HTMLTableElement {
  return table(label, ['Counter', 'Value / measurement'], [['Input', counter(counts.input)], ['Output', counter(counts.output)],
    ['Cache read', counter(counts.cacheRead)], ['Cache write', counter(counts.cacheWrite)], ['Reasoning', counter(counts.reasoning)]]);
}
interface AttemptActions {
  scope(filter: api.UsageFilter_ProjectAll | api.UsageFilter_TaskOnly | api.UsageFilter_CohortOnly | api.UsageFilter_SessionOnly): void;
  outcomes(attempt: api.AttemptId): void;
}
export function attemptsTable(entries: api.AttemptView[], actions: AttemptActions): HTMLTableElement {
  return table('Attempts', ['Started', 'Harness / role', 'Model', 'State', 'Attribution / members', 'Details'], entries.map(entry => {
    const {attempt, assignment, outcome} = entry;
    const scopes = element('div', ''); scopes.className = 'usage-scope-actions';
    scopes.append(button(`Session usage · ${attempt.session.value}`, () => actions.scope(new api.UsageFilter_SessionOnly(attempt.session))));
    const cohort = assignment.cohort;
    if (cohort !== undefined) scopes.append(button(`Cohort usage · ${cohort}`, () => actions.scope(new api.UsageFilter_CohortOnly(cohort))));
    for (const member of assignment.members) scopes.append(button(`Task usage · ${itemName(member)}`, () => actions.scope(new api.UsageFilter_TaskOnly(member))));
    const metadata = fields([['Attempt', attempt.id.value], ['Assignment', assignment.id.value], ['Session', attempt.session.value],
      ['Parent attempt', attempt.parent === undefined ? 'None' : attempt.parent.value], ['Collector', attempt.collector],
      ['Finished', outcome === undefined ? 'No outcome recorded' : time(outcome.value.finishedAt)],
      ['Gaps', outcome === undefined ? 'No outcome recorded' : outcome.value.gaps.join('; ') || 'None recorded']]);
    const evaluation = assignment.evaluation;
    if (evaluation !== undefined) metadata.append(element('dt', 'Evaluation'), element('dd', `${evaluation.run} · ${evaluation.scenario} · ${evaluation.assessor ? 'Assessor' : 'Consumer'}`));
    return [time(attempt.startedAt), `${attempt.harness} · ${attempt.role}`, `${attempt.provider} / ${attempt.model}`,
      outcome === undefined ? 'Running' : outcome.value.state,
      `${assignment.attribution} · ${[...assignment.members].map(itemName).join(', ') || 'No assigned items'}`,
      details('Attempt details', metadata, scopes, button('Outcome history', () => actions.outcomes(attempt.id)))];
  }));
}
export function sharedAssignmentsList(assignments: readonly api.Assignment[], open: boolean): HTMLDetailsElement {
  const list = element('ul', ''); list.setAttribute('aria-label', 'Shared assignments');
  for (const assignment of assignments) {
    const scope = [[...assignment.members].map(itemName).join(', ') || 'No assigned items'];
    if (assignment.cohort !== undefined) scope.push(`cohort ${assignment.cohort}`);
    const evaluation = assignment.evaluation;
    if (evaluation !== undefined) scope.push(`evaluation ${evaluation.run} · ${evaluation.scenario} · ${evaluation.assessor ? 'Assessor' : 'Consumer'}`);
    const entry = element('li', scope.join(' · ')); entry.title = `Assignment ${assignment.id.value}`; list.append(entry);
  }
  const count = assignments.length;
  const result = details(`${count} shared ${count === 1 ? 'assignment' : 'assignments'}`, list);
  result.className = 'usage-shared'; result.open = open; return result;
}
export function outcomesTable(entries: api.RecordedOutcome[]): HTMLTableElement {
  return table('Outcome history', ['Sequence', 'State', 'Finished', 'Gaps', 'Provenance'], entries.map(entry => [
    entry.sequence.toString(), entry.value.state, time(entry.value.finishedAt), entry.value.gaps.join('; ') || 'None recorded',
    details('Outcome details', fields([['Attempt', entry.value.attempt.value], ['Request', entry.value.request.value],
      ['Supersedes', entry.value.supersedes === undefined ? 'None' : entry.value.supersedes.value],
      ['Received', time(entry.receivedAt)], ['Actor', `${entry.actor.subject} · ${entry.actor.role}`], ['Session', entry.actor.session.value]])),
  ]));
}
export function auditTable(entries: api.RecordedUsage[]): HTMLTableElement {
  return table('Usage audit', ['Sequence / time', 'Source / coverage', 'Contribution', 'Input', 'Output', 'Cost', 'Details'], entries.map(entry => {
    const {observation, meter, disposition, detailReason} = entry.upload;
    const metadata = fields([['Observation', observation.id.value], ['Attempt', observation.attempt.value], ['Meter', meter],
      ['Position / scope', `${observation.position} / ${observation.scope}`], ['Received', time(observation.receivedAt)],
      ['Supersedes', observation.supersedes === undefined ? 'None' : observation.supersedes.value],
      ['Evidence', observation.evidence === undefined ? 'None recorded' : observation.evidence.value],
      ['Detail reason', detailReason === undefined ? 'Not a detail observation' : detailReason],
      ['Gaps', observation.gaps.join('; ') || 'None recorded'], ['Pricing version', observation.cost.pricingVersion === undefined ? 'Unknown' : observation.cost.pricingVersion],
      ['Input includes cache', observation.inputIncludesCache ? 'Yes' : 'No'], ['Output includes reasoning', observation.outputIncludesReasoning ? 'Yes' : 'No'],
      ['Actor', `${entry.actor.subject} · ${entry.actor.role}`], ['Session', entry.actor.session.value]]);
    return [`${entry.sequence} · ${time(observation.occurredAt)}`, `${observation.source} · ${observation.completeness}`, disposition,
      counter(entry.normalized.input), counter(entry.normalized.output), money(observation.cost),
      details('Observation details', metadata, tokens('Normalized counters', entry.normalized), tokens('Reported counters', observation.counters))];
  }));
}
