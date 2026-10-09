import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';
import { itemName } from './items.js';
import { formatAmount, MoneyDigits, sumAmounts } from './money.js';

type Cell = string | HTMLElement;
// label: the row's name on the shared grid; text: one line; prose: free text that may wrap; number: right-aligned on the shared grid; tally: a right-aligned small count.
type ColumnKind = 'label' | 'text' | 'prose' | 'number' | 'tally';
interface Column { heading: string; kind: ColumnKind }
interface Layout { label: string; columns: Column[]; rows: Cell[][]; total: Cell[] | undefined }
const column = (kind: ColumnKind) => (heading: string): Column => ({ heading, kind });
const rowLabel = column('label'); const text = column('text'); const prose = column('prose'); const number = column('number'); const tally = column('tally');
const GROUP_SEPARATOR = ' ';
const NO_VALUE = '—';
function cell<K extends 'td' | 'th'>(tag: K, kind: ColumnKind, value: Cell): HTMLElementTagNameMap[K] {
  const result = element(tag, ''); result.className = `usage-${kind}`; result.append(value);
  if ((kind === 'number' || kind === 'tally') && result.textContent === '0') result.classList.add('usage-zero');
  return result;
}
function tableRow(columns: Column[], values: Cell[]): HTMLTableRowElement {
  const row = element('tr', '');
  values.forEach((value, index) => {
    const kind = (columns[index] as Column).kind;
    if (kind === 'label') { const header = cell('th', kind, value); header.scope = 'row'; row.append(header); } else row.append(cell('td', kind, value));
  });
  return row;
}
function table(layout: Layout): HTMLTableElement {
  const result = element('table', ''); result.className = 'usage-table'; result.setAttribute('aria-label', layout.label);
  const head = element('thead', ''); const titles = element('tr', '');
  for (const { heading, kind } of layout.columns) { const title = cell('th', kind, heading); title.scope = 'col'; titles.append(title); }
  head.append(titles); const body = element('tbody', '');
  for (const values of layout.rows) {
    const row = tableRow(layout.columns, values);
    const expanded: HTMLTableRowElement[] = [];
    for (const value of values) {
      if (value instanceof HTMLDetailsElement) {
        const extra = element('tr', ''); extra.className = 'usage-expansion'; extra.hidden = true; const content = element('td', ''); content.colSpan = layout.columns.length;
        content.append(...Array.from(value.children).slice(1)); extra.append(content); expanded.push(extra);
        value.addEventListener('toggle', () => { extra.hidden = !value.open; });
      }
    }
    body.append(row, ...expanded);
  }
  result.append(head, body);
  if (layout.total !== undefined) { const foot = element('tfoot', ''); foot.append(tableRow(layout.columns, layout.total)); result.append(foot); }
  return result;
}
// A usage table with its heading and note; the table scrolls horizontally inside the section instead of wrapping its values.
function section(heading: string | undefined, note: string | undefined, content: HTMLTableElement): HTMLElement {
  const result = element('div', ''); result.className = 'usage-section';
  if (heading !== undefined) result.append(element('h4', heading));
  if (note !== undefined) { const caption = element('p', note); caption.className = 'usage-note'; result.append(caption); }
  const scroll = element('div', ''); scroll.className = 'usage-scroll'; scroll.append(content); result.append(scroll); return result;
}
function quiet(value: string): HTMLElement { const result = element('span', value); result.className = 'usage-quiet'; return result; }
function qualified(qualifier: string, value: Cell): HTMLElement { const result = element('span', ''); result.append(quiet(qualifier), ' ', value); return result; }
function fields(values: [string, string][]): HTMLDListElement {
  const result = element('dl', ''); result.className = 'usage-fields';
  for (const [label, value] of values) result.append(element('dt', label), element('dd', value));
  return result;
}
function details(label: string, ...content: HTMLElement[]): HTMLDetailsElement {
  const result = element('details', ''); result.append(element('summary', label), ...content); return result;
}
function time(value: bigint): string { return new Date(Number(value)).toLocaleString(); }
function count(value: bigint): string { return value.toString().replace(/\B(?=(\d{3})+$)/g, GROUP_SEPARATOR); }
function sum(values: readonly bigint[]): bigint { return values.reduce((total, value) => total + value, 0n); }
function counter(value: api.Counter): HTMLElement { return qualified(value.measurement, value.value === undefined ? 'Unknown' : count(value.value)); }
function amount(value: string, currency: string | undefined): HTMLElement {
  const result = element('span', currency === undefined ? formatAmount(value, MoneyDigits) : `${formatAmount(value, MoneyDigits)} ${currency}`);
  result.title = value; return result;
}
// The basis is stated once for a table whose amounts share it and per amount otherwise.
function mixedBases(bases: readonly api.CostBasis[]): boolean { return new Set(bases).size > 1; }
function basisNote(bases: readonly api.CostBasis[]): string | undefined {
  return bases.length > 0 && !mixedBases(bases) ? `Cost basis: ${bases[0]}.` : undefined;
}
function money(value: api.Money): Cell { return value.amount === undefined ? 'Unknown' : amount(value.amount.value, value.currency); }
function tokens(label: string, counts: api.TokenCounts): HTMLElement {
  const row = (name: string, value: api.Counter): Cell[] => [name, value.value === undefined ? 'Unknown' : count(value.value), value.measurement];
  return section(label, undefined, table({ label, columns: [text('Counter'), number('Value'), text('Measurement')], total: undefined,
    rows: [row('Input', counts.input), row('Output', counts.output), row('Cache read', counts.cacheRead), row('Cache write', counts.cacheWrite), row('Reasoning', counts.reasoning)] }));
}
// Truncated to whole seconds, and to whole minutes from one hour on: `45 s`, `3 min 20 s`, `1 h 02 min`.
function duration(millis: bigint): HTMLElement {
  const seconds = millis / 1000n; const minutes = seconds / 60n; const hours = minutes / 60n;
  const padded = (value: bigint) => value.toString().padStart(2, '0');
  const result = element('span', hours > 0n ? `${count(hours)} h ${padded(minutes % 60n)} min` : minutes > 0n ? `${minutes} min ${padded(seconds % 60n)} s` : `${seconds} s`);
  result.title = `${millis} ms`; return result;
}
// Amounts are added per currency and basis, so estimates and billing stay apart; without an amount the cost is unknown when
// a measurement lacks one and absent otherwise, never zero.
function costSum(costs: readonly api.CostTotal[], unknownCosts: bigint, bases: readonly api.CostBasis[]): Cell {
  const groups = new Map<string, { currency: string; basis: api.CostBasis; amounts: string[] }>();
  for (const cost of costs) {
    const key = `${cost.group.currency} · ${cost.group.basis}`; const group = groups.get(key);
    if (group === undefined) groups.set(key, { currency: cost.group.currency, basis: cost.group.basis, amounts: [cost.amount.value] }); else group.amounts.push(cost.amount.value);
  }
  if (groups.size === 0) return unknownCosts > 0n ? 'Unknown' : quiet(NO_VALUE);
  const mixed = mixedBases(bases);
  const result = element('div', '');
  for (const group of groups.values()) {
    const value = amount(sumAmounts(group.amounts), group.currency); const line = element('div', '');
    line.append(mixed ? qualified(group.basis, value) : value); result.append(line);
  }
  return result;
}
export function totalsTable(report: api.UsageReport): HTMLElement {
  const values = (totals: api.UsageTotals) => [totals.total.known, totals.total.unknown, totals.total.estimated, totals.unknownCosts];
  const rows = ([['Direct', report.direct], ['Shared', report.shared], ['Unattributed', report.unattributed]] as const).map(([name, totals]) => ({ name, values: values(totals) }));
  return section('Tokens by attribution', undefined, table({ label: 'Usage totals',
    columns: [rowLabel('Attribution'), number('Known tokens'), number('Unknown measurements'), number('Estimated measurements'), number('Unknown costs')],
    rows: rows.map(row => [row.name, ...row.values.map(count)]),
    total: ['Total', ...[0, 1, 2, 3].map(index => count(sum(rows.map(row => row.values[index] as bigint))))] }));
}
// `complete` states that the entries are every cost group of the scope, so their total is the scope's.
export function costsTable(heading: string | undefined, costs: readonly api.CostTotal[], complete: boolean): HTMLElement {
  const bases = costs.map(cost => cost.group.basis); const mixed = mixedBases(bases);
  const basis = mixed ? [text('Basis')] : [];
  return section(heading, basisNote(bases), table({ label: 'Costs',
    columns: [rowLabel('Attribution'), number('Amount'), number('Measurements'), ...basis, text('Pricing')],
    rows: costs.map(({ group, amount: value, measurements }) => [group.attribution, amount(value.value, group.currency), count(measurements),
      ...(mixed ? [group.basis] : []), group.pricingVersion === undefined ? quiet('unspecified') : group.pricingVersion]),
    total: complete && costs.length > 1 ? ['Total', costSum(costs, 0n, bases), count(sum(costs.map(cost => cost.measurements))), ...basis.map(() => ''), ''] : undefined }));
}
export const phaseOverlapNote = 'Phase wall times overlap (the governor\'s attempt spans the session, a reviewer waits for its checks, an integration contains the checks of its commit), so the phase rows must not be added up.';
export function phasesTable(phases: readonly api.PhaseUsage[]): HTMLElement {
  const costs = phases.flatMap(entry => entry.costs); const bases = costs.map(cost => cost.group.basis);
  const values = (entry: api.PhaseUsage) => [entry.totals.total.known, entry.totals.total.unknown, entry.totals.total.estimated, entry.totals.unknownCosts];
  const total = (value: (entry: api.PhaseUsage) => bigint) => sum(phases.map(value));
  return section('By phase', [phaseOverlapNote, basisNote(bases)].filter(note => note !== undefined).join(' '), table({ label: 'Usage by phase',
    columns: [rowLabel('Phase'), number('Known tokens'), number('Unknown measurements'), number('Estimated measurements'), number('Unknown costs'),
      number('Cost'), tally('Attempts'), tally('Running'), tally('Open'), number('Busy wall time')],
    rows: phases.map(entry => [entry.phase, ...values(entry).map(count), costSum(entry.costs, entry.totals.unknownCosts, bases),
      count(entry.attempts), count(entry.running), count(entry.open), duration(entry.wallMillis)]),
    total: phases.length > 1 ? ['Total', ...[0, 1, 2, 3].map(index => count(total(entry => values(entry)[index] as bigint))),
      costSum(costs, total(entry => entry.totals.unknownCosts), bases), count(total(entry => entry.attempts)), count(total(entry => entry.running)),
      count(total(entry => entry.open)),
      duration(total(entry => entry.wallMillis))] : undefined }));
}
export const unnamedCheck = '(unnamed)';
/** One row per configured check name: runs in total and per outcome and their summed wall time. Runs recorded without a name are one row. */
export function checksTable(report: api.CheckReport): HTMLElement {
  const names: (string | undefined)[] = []; const byName = new Map<string | undefined, api.CheckUsage[]>();
  for (const entry of report.checks) {
    const group = byName.get(entry.check);
    if (group === undefined) { names.push(entry.check); byName.set(entry.check, [entry]); } else group.push(entry);
  }
  const runs = (entries: api.CheckUsage[], state?: api.AttemptState) => sum(entries.filter(entry => state === undefined || entry.state === state).map(entry => entry.runs));
  return section('By check', 'Wall time sums the runs of a check from start to finish; runs of different checks may overlap, so the rows must not be added up. ' +
      `Runs recorded without a check name are the ${unnamedCheck} row.`, table({ label: 'Usage by check',
    columns: [rowLabel('Check'), tally('Runs'), tally('Completed'), tally('Failed'), tally('Cancelled'), tally('Unknown'), number('Busy wall time')],
    rows: names.map(name => {
      const entries = byName.get(name) as api.CheckUsage[];
      return [name === undefined ? unnamedCheck : name, count(runs(entries)), count(runs(entries, api.AttemptState.Completed)), count(runs(entries, api.AttemptState.Failed)),
        count(runs(entries, api.AttemptState.Cancelled)), count(runs(entries, api.AttemptState.Unknown)), duration(sum(entries.map(entry => entry.wallMillis)))];
    }), total: undefined }));
}
export const openAttemptNote = 'No outcome delivered. CQ does not observe an attached session\'s own harness, so the session may have ended; ' +
  'cq job upload --session DIR over its retained session directory delivers the outcome.';
/** What the tokens of an attempt without a measurement are: unknown, and for a Governor's own work known to be elsewhere. */
export const unmeasuredNote = 'An attempt without a measurement counts no tokens here, which is not zero usage. ' +
  'A Governor\'s own work and its own review are never measured: their tokens are part of the governing session\'s.';
const ownWorkMeter = 'None: the work was done in the governing session, whose usage is that session\'s own';
/** An attempt of the Governor role under a governing attempt is that session's own work or review, not a child. */
function ownWork(attempt: api.Attempt): boolean { return attempt.role === api.Role.Governor && attempt.parent !== undefined; }
interface AttemptActions {
  scope(filter: api.UsageFilter_ProjectAll | api.UsageFilter_TaskOnly | api.UsageFilter_CohortOnly | api.UsageFilter_SessionOnly): void;
  outcomes(attempt: api.AttemptId): void;
}
export function attemptsTable(entries: api.AttemptView[], actions: AttemptActions): HTMLElement {
  const columns = [text('Started'), text('Harness / role'), text('Model'), text('State'), prose('Attribution / members'), text('Details')];
  return section(undefined, undefined, table({ label: 'Attempts', columns, total: undefined, rows: entries.map(entry => {
    const {attempt, assignment, outcome} = entry;
    const scopes = element('div', ''); scopes.className = 'usage-scope-actions';
    scopes.append(button(`Session usage · ${attempt.session.value}`, () => actions.scope(new api.UsageFilter_SessionOnly(attempt.session))));
    const cohort = assignment.cohort;
    if (cohort !== undefined) scopes.append(button(`Cohort usage · ${cohort}`, () => actions.scope(new api.UsageFilter_CohortOnly(cohort))));
    for (const member of assignment.members) scopes.append(button(`Task usage · ${itemName(member)}`, () => actions.scope(new api.UsageFilter_TaskOnly(member))));
    const metadata = fields([['Attempt', attempt.id.value], ['Assignment', assignment.id.value], ['Session', attempt.session.value],
      ['Parent attempt', attempt.parent === undefined ? 'None' : attempt.parent.value], ['Collector', attempt.collector],
      ...(ownWork(attempt) ? [['Meter', ownWorkMeter] as [string, string]] : []),
      ['Finished', outcome === undefined ? entry.observed ? 'No outcome recorded' : openAttemptNote : time(outcome.value.finishedAt)],
      ['Gaps', outcome === undefined ? 'No outcome recorded' : outcome.value.gaps.join('; ') || 'None recorded']]);
    const evaluation = assignment.evaluation;
    if (evaluation !== undefined) metadata.append(element('dt', 'Evaluation'), element('dd', `${evaluation.run} · ${evaluation.scenario} · ${evaluation.assessor ? 'Assessor' : 'Consumer'}`));
    return [time(attempt.startedAt), `${attempt.harness} · ${attempt.role}${ownWork(attempt) ? ` · own ${attempt.phase.toLowerCase()}` : ''}`,
      ownWork(attempt) ? 'The governing session' : `${attempt.provider} / ${attempt.model}${attempt.effort === undefined ? '' : ` · effort ${attempt.effort.toLowerCase()}`}`,
      outcome === undefined ? entry.observed ? 'Running' : 'Open' : outcome.value.state,
      `${assignment.attribution} · ${[...assignment.members].map(itemName).join(', ') || 'No assigned items'}`,
      details('Attempt details', metadata, scopes, button('Outcome history', () => actions.outcomes(attempt.id)))];
  }) }));
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
export function outcomesTable(entries: api.RecordedOutcome[]): HTMLElement {
  const columns = [tally('Sequence'), text('State'), text('Finished'), prose('Gaps'), text('Provenance')];
  return section(undefined, undefined, table({ label: 'Outcome history', columns, total: undefined, rows: entries.map(entry => [
    count(entry.sequence), entry.value.state, time(entry.value.finishedAt), entry.value.gaps.join('; ') || 'None recorded',
    details('Outcome details', fields([['Attempt', entry.value.attempt.value], ['Request', entry.value.request.value],
      ['Supersedes', entry.value.supersedes === undefined ? 'None' : entry.value.supersedes.value],
      ['Received', time(entry.receivedAt)], ['Actor', `${entry.actor.subject} · ${entry.actor.role}`], ['Session', entry.actor.session.value]])),
  ]) }));
}
export function auditTable(entries: api.RecordedUsage[]): HTMLElement {
  // An observation without an amount shows its cost as unknown and does not decide how the basis of the others is stated.
  const bases = entries.map(entry => entry.upload.observation.cost).filter(cost => cost.amount !== undefined).map(cost => cost.basis); const mixed = mixedBases(bases);
  const columns = [text('Sequence / time'), text('Source / coverage'), text('Contribution'), number('Input'), number('Output'), number('Cost'),
    ...(mixed ? [text('Basis')] : []), text('Details')];
  return section(undefined, basisNote(bases), table({ label: 'Usage audit', columns, total: undefined, rows: entries.map(entry => {
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
      counter(entry.normalized.input), counter(entry.normalized.output), money(observation.cost), ...(mixed ? [observation.cost.basis] : []),
      details('Observation details', metadata, tokens('Normalized counters', entry.normalized), tokens('Reported counters', observation.counters))];
  }) }));
}
