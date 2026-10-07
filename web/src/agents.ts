import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { faultMessage } from './faults.js';
import { harnessName } from './help.js';
import { AGENTS_EXAMPLE, AGENTS_GRAMMAR } from './agents-help.js';

export type AgentsLayer = 'installation' | 'project';
interface AgentsEffects {
  call(command: api.Command): Promise<api.Result>;
  // `changed` is false when the saved text equals the stored one: the server then keeps the revision.
  saved(layer: AgentsLayer, value: api.AgentsDocument, changed: boolean): void;
}

const LAYERS: readonly AgentsLayer[] = ['installation', 'project'];
const PREVIEW_DELAY_MILLISECONDS = 300;
const EDITOR_ROWS = 14;
const TITLES: Record<AgentsLayer, string> = { installation: 'Server defaults', project: 'This project' };
const SAVED_NAMES: Record<AgentsLayer, string> = { installation: 'server defaults', project: 'text of this project' };
const PLACEHOLDERS: Record<AgentsLayer, string> = {
  installation: 'Empty: no role has a model on this server.\nWrite the defaults of every project here; “How to write it” below has an example.',
  project: 'Empty: this project inherits the server defaults. That is the normal case.\nWrite only what differs here, for example:\n\nharnesses:\n  codex:\n    roles: { reviewer: claude:@standard }',
};
const lower = (value: string): string => value.toLowerCase();
const other = (layer: AgentsLayer): AgentsLayer => layer === 'installation' ? 'project' : 'installation';
function scope(layer: AgentsLayer): api.AgentsScope { return layer === 'installation' ? new api.AgentsScope_Installation() : new api.AgentsScope_Project(); }
function layerOf(value: api.AgentLayer): AgentsLayer { return value === api.AgentLayer.Installation ? 'installation' : 'project'; }

// The reference syntax of a resolved model, as the server's text form writes it: the characters that end a name there are percent-encoded.
const RESERVED = '%?#,[]{} ';
function encoded(character: string): string { return `%${character.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')}`; }
function namePart(text: string, slash: boolean): string {
  return Array.from(text, character => RESERVED.includes(character) || (slash && character === '/') ? encoded(character) : character).join('');
}
export function routeText(route: api.ModelRoute): string {
  const body = route.provider === undefined ? namePart(route.model, true) : `${namePart(route.provider, true)}/${namePart(route.model, false)}`;
  const led = body.startsWith('@') ? encoded('@') + body.slice(1) : body;
  const name = led.endsWith(':') ? led.slice(0, -1) + encoded(':') : led;
  return `${lower(route.harness)}:${name}${route.effort === undefined ? '' : `?effort=${lower(route.effort)}`}`;
}
const STRATEGIES: Record<api.SeatStrategy, string> = { [api.SeatStrategy.Fallback]: 'fallback', [api.SeatStrategy.RoundRobin]: 'rr', [api.SeatStrategy.First]: 'first' };
const STRATEGY_HINTS: Record<api.SeatStrategy, string> = {
  [api.SeatStrategy.Fallback]: 'In this order: the next one when one abstains',
  [api.SeatStrategy.RoundRobin]: 'Round-robin across attempts, continuing with the next one when one abstains',
  [api.SeatStrategy.First]: 'Always this one; the seat abstains when it is unavailable',
};

/** One line for a problem, without its position. The wording follows the server's, which is the one a refused save shows. */
function problemText(problem: api.AgentProblem): string {
  if (problem instanceof api.AgentProblem_Syntax) return problem.message;
  if (problem instanceof api.AgentProblem_UnknownKey) return `unknown key '${problem.key}'`;
  if (problem instanceof api.AgentProblem_EmptyList) return 'the list is empty';
  if (problem instanceof api.AgentProblem_InvalidMinimum) return `min is ${problem.min}, and a panel of ${problem.seats} seats takes a min from 1 to ${problem.seats}`;
  if (problem instanceof api.AgentProblem_PanelNotAllowed) return `the ${lower(problem.role)} role takes a model reference or a strategy; only the reviewer role takes a panel`;
  if (problem instanceof api.AgentProblem_ProviderRequired) return `a ${lower(problem.harness)} model is written provider/model`;
  if (problem instanceof api.AgentProblem_ProviderNotAllowed) return `a ${lower(problem.harness)} model is written without a provider`;
  if (problem instanceof api.AgentProblem_EffortUnsupported) return `${lower(problem.harness)} does not take effort ${lower(problem.effort)}`;
  if (problem instanceof api.AgentProblem_ModelAmbiguous)
    return `${lower(problem.harness)} reads the ending of the model name '${problem.model}' as a thinking level, so the name selects no one model; a level is written ?effort=…`;
  if (problem instanceof api.AgentProblem_RoleUnassigned) return `no layer assigns the ${lower(problem.role)} role when ${lower(problem.harness)} governs`;
  if (problem instanceof api.AgentProblem_TierUndefined) return `the ${lower(problem.role)} role refers to the ${lower(problem.tier)} tier of ${lower(problem.harness)}, which no layer defines`;
  throw new Error('Unknown agent configuration problem');
}
function problemPosition(problem: api.AgentProblem): api.TextPosition | null {
  return problem instanceof api.AgentProblem_RoleUnassigned || problem instanceof api.AgentProblem_TierUndefined ? null : problem.at;
}
/** Where a role was found: the layer and the part of its text. */
function sourceText(origin: api.RoleOrigin, governing: api.Harness): string {
  const layer = origin.layer === api.AgentLayer.Installation ? 'server' : 'project';
  return origin.source === api.RoleSource.HarnessRoles ? `${layer} harnesses.${lower(governing)}.roles` : `${layer} defaults.roles`;
}
// The modes of each role, in the order of the dispatch model; a key of a roles mapping names a role or one of its modes.
const MODES: Record<api.AgentRole, ReadonlyArray<string>> = {
  [api.AgentRole.Planner]: [], [api.AgentRole.Worker]: api.WorkerMode_values, [api.AgentRole.Explorer]: api.ExplorerMode_values, [api.AgentRole.Reviewer]: api.ReviewerMode_values,
};
function keyRole(key: api.RoleKey): api.AgentRole {
  if (key instanceof api.RoleKey_Plain) return key.role;
  if (key instanceof api.RoleKey_Explorer) return api.AgentRole.Explorer;
  if (key instanceof api.RoleKey_Worker) return api.AgentRole.Worker;
  return api.AgentRole.Reviewer;
}
function keyMode(key: api.RoleKey): string | null { return key instanceof api.RoleKey_Plain ? null : key.mode; }
/** The key as a configuration writes it: reviewer, or reviewer/plan for one mode. */
function keyText(key: api.RoleKey): string {
  const mode = keyMode(key);
  return lower(keyRole(key)) + (mode === null ? '' : `/${lower(mode)}`);
}

interface LayerView {
  section: HTMLElement; text: HTMLTextAreaElement; metadata: HTMLParagraphElement; save: HTMLButtonElement; error: HTMLParagraphElement; conflict: HTMLElement;
  // The revision the text in the editor was started from, null while it is loading.
  base: api.AgentsDocument | null; busy: boolean;
}
interface Preview { state: 'pending' | 'current' | 'invalid' | 'error'; note: string; assignments: api.ResolvedAssignment[] }

/**
 * Edits the agent model configuration: the server's defaults and this project's override, each a text under revision comparison.
 * The server parses and resolves; the dialog shows the problems and the resolved table of an unsaved text from its Preview reply.
 */
export class AgentsDialog {
  private generation = 0;
  private readonly dialog = new Dialog('large', () => { this.generation++; for (const layer of LAYERS) this.cancel(layer); });
  readonly element = this.dialog.element;
  private readonly layers: Record<AgentsLayer, LayerView> = { installation: this.layer('installation'), project: this.layer('project') };
  private readonly problemsPanel = element('section', '');
  private readonly previewPanel = element('section', '');
  private project: api.ProjectId | null = null;
  // Dirty project texts of the projects the dialog was opened for since, by project.
  private readonly drafts = new Map<string, { base: api.AgentsDocument; text: string }>();
  // The problems of each text as typed, from the latest Preview reply for it.
  private readonly problems: Record<AgentsLayer, api.AgentProblem[]> = { installation: [], project: [] };
  private preview: Preview = { state: 'pending', note: '', assignments: [] };
  // The layer edited last: the table shows its unsaved text against the other layer as saved.
  private active: AgentsLayer = 'project';
  // Request ordering: a Preview reply is used for its layer only when no later one was sent for that layer, and for the table
  // only when no later one was sent at all.
  private sequence = 0;
  private readonly latest: Record<AgentsLayer, number> = { installation: 0, project: 0 };
  private readonly timers: Record<AgentsLayer, number | null> = { installation: null, project: null };

  constructor(private readonly effects: AgentsEffects) {
    const editors = element('div', ''); editors.className = 'agents-editors'; editors.append(this.layers.installation.section, this.layers.project.section);
    const help = element('details', ''); help.className = 'help-block agents-help';
    const grammar = element('pre', AGENTS_GRAMMAR); grammar.setAttribute('aria-label', 'How an agent model configuration is written'); grammar.tabIndex = 0;
    const example = element('pre', AGENTS_EXAMPLE); example.setAttribute('aria-label', 'Example of server defaults'); example.tabIndex = 0;
    help.append(element('summary', 'How to write it'), grammar, element('h4', 'Example of server defaults'), example);
    this.problemsPanel.className = 'agents-problems'; this.problemsPanel.setAttribute('aria-label', 'Problems');
    this.previewPanel.className = 'agents-preview'; this.previewPanel.setAttribute('aria-label', 'Preview');
    this.dialog.body.classList.add('agents-body');
    this.dialog.body.append(
      element('p', 'Which models run the planner, worker, explorer and reviewer children of a session, by the harness that governs it. The server defaults hold for every project; this project\'s text overrides them key by key. A saved text applies to the next child that starts.'),
      editors, this.problemsPanel, this.previewPanel, help);
  }

  private layer(layer: AgentsLayer): LayerView {
    const section = element('section', ''); section.className = 'agents-layer'; section.dataset.layer = layer;
    const heading = element('h3', TITLES[layer]); heading.id = `agents-${layer}`; section.setAttribute('aria-labelledby', heading.id);
    const text = element('textarea', ''); text.setAttribute('aria-label', TITLES[layer]); text.rows = EDITOR_ROWS; text.spellcheck = false; text.wrap = 'off';
    text.placeholder = PLACEHOLDERS[layer]; text.className = 'agents-text';
    const metadata = element('p', ''); metadata.className = 'revision-meta';
    const error = element('p', ''); error.setAttribute('role', 'alert'); error.hidden = true;
    const conflict = element('section', ''); conflict.hidden = true;
    const save = button(layer === 'installation' ? 'Save server defaults' : 'Save this project', () => this.action(layer, () => this.submit(layer)));
    text.addEventListener('input', () => { this.active = layer; this.schedule(layer); });
    section.append(heading, text, metadata, save, error, conflict);
    return { section, text, metadata, save, error, conflict, base: null, busy: false };
  }
  private provenance(layer: AgentsLayer, value: api.AgentsDocument): string {
    return value.change !== undefined ? `Revision ${value.revision.value} · ${value.change.actor.subject} · ${new Date(Number(value.change.at)).toLocaleString()}`
      : layer === 'installation' ? 'No server defaults have been saved.' : 'No text has been saved for this project: it inherits the server defaults.';
  }
  /** An error of one layer's action is shown beside its editor; the text in the editor is kept. */
  private action(layer: AgentsLayer | null, effect: () => Promise<void>): void {
    const generation = this.generation;
    void effect().catch(error => {
      if (generation !== this.generation) return;
      const target = layer === null ? this.dialog.error : this.layers[layer].error;
      target.textContent = error instanceof Error ? error.message : String(error); target.hidden = false;
    });
  }
  private dirty(layer: AgentsLayer): boolean { const view = this.layers[layer]; return view.base !== null && view.text.value !== view.base.text; }
  private enable(layer: AgentsLayer): void {
    const view = this.layers[layer]; view.text.disabled = view.base === null; view.save.disabled = view.base === null || view.busy;
  }
  private adopt(layer: AgentsLayer, value: api.AgentsDocument): void {
    const view = this.layers[layer];
    view.base = value; view.metadata.textContent = this.provenance(layer, value); view.conflict.hidden = true; view.conflict.replaceChildren(); this.enable(layer);
  }
  private unload(layer: AgentsLayer): void {
    const view = this.layers[layer];
    view.base = null; view.text.value = ''; view.metadata.textContent = 'Loading…'; view.error.hidden = true; view.conflict.hidden = true; view.conflict.replaceChildren();
    this.problems[layer] = []; this.cancel(layer); this.enable(layer);
  }
  private cancel(layer: AgentsLayer): void {
    const timer = this.timers[layer]; if (timer !== null) { window.clearTimeout(timer); this.timers[layer] = null; }
  }
  private async request(project: api.ProjectId, action: api.AgentsAction): Promise<api.Result> {
    return this.effects.call(new api.Command_Agents(new api.AgentsInput(project, action)));
  }
  private async read(project: api.ProjectId): Promise<api.AgentsView> {
    const result = await this.request(project, new api.AgentsAction_Read());
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    if (!(result instanceof api.Result_Agents)) throw new Error('Unexpected agent configuration response');
    return result.value;
  }

  open(project: api.ProjectId): void {
    const shown = this.project; const override = this.layers.project;
    if (shown !== null && shown.value !== project.value) {
      if (override.base !== null && this.dirty('project')) this.drafts.set(shown.value, { base: override.base, text: override.text.value });
      this.unload('project');
    }
    this.project = project;
    const kept = this.drafts.get(project.value);
    if (kept !== undefined) { this.drafts.delete(project.value); this.adopt('project', kept.base); override.text.value = kept.text; }
    this.dialog.open('Agent models');
    // An unsaved text survives closing and reopening the dialog; a layer without one is read again.
    for (const layer of LAYERS) if (!this.dirty(layer)) this.unload(layer);
    this.preview = { state: 'pending', note: 'Loading…', assignments: [] }; this.render();
    const generation = this.generation;
    this.action(null, async () => {
      const view = await this.read(project);
      if (generation !== this.generation) return;
      for (const layer of LAYERS) if (this.layers[layer].base === null) { this.adopt(layer, view[layer]); this.layers[layer].text.value = view[layer].text; }
      this.settle(view);
    });
  }

  /** Shows what a reply says of the saved texts, and asks for a preview of every unsaved one. */
  private settle(view: api.AgentsView): void {
    const unsaved = LAYERS.filter(layer => this.dirty(layer));
    // A Preview reply still under way for a text that is now the saved one is out of date.
    for (const layer of LAYERS) if (!unsaved.includes(layer)) { this.cancel(layer); this.latest[layer] = ++this.sequence; this.problems[layer] = view[layer].problems; }
    if (unsaved.length === 0) this.show(view, 'The preview shows the saved texts.');
    // The layer edited last is asked last, so that the table is its reply's.
    else for (const layer of [...unsaved.filter(layer => layer !== this.active), ...unsaved.filter(layer => layer === this.active)]) this.send(layer);
    this.render();
  }
  private show(view: api.AgentsView, note: string): void {
    this.preview = view.assignments.length > 0 ? { state: 'current', note, assignments: view.assignments }
      : { state: 'invalid', note: 'The preview needs a valid text: correct the problems listed above.', assignments: [] };
  }
  private schedule(layer: AgentsLayer): void {
    this.cancel(layer); this.layers[layer].error.hidden = true;
    // A reply for an earlier text of this layer is out of date from now on.
    this.latest[layer] = ++this.sequence; this.preview = { ...this.preview, state: 'pending' }; this.render();
    this.timers[layer] = window.setTimeout(() => { this.timers[layer] = null; this.send(layer); }, PREVIEW_DELAY_MILLISECONDS);
  }
  private send(layer: AgentsLayer): void {
    const project = this.project; if (project === null) return;
    const ticket = ++this.sequence; this.latest[layer] = ticket; const generation = this.generation;
    this.preview = { ...this.preview, state: 'pending' };
    const current = () => generation === this.generation && ticket === this.latest[layer];
    void this.request(project, new api.AgentsAction_Preview(scope(layer), this.layers[layer].text.value)).then(result => {
      if (!current()) return;
      if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      if (!(result instanceof api.Result_Agents)) throw new Error('Unexpected agent configuration response');
      const view = result.value; const rest = other(layer);
      this.problems[layer] = view[layer].problems;
      if (!this.dirty(rest)) this.problems[rest] = view[rest].problems;
      if (ticket === this.sequence) this.show(view, !this.dirty(layer) ? 'The preview shows the saved texts.'
        : this.dirty(rest) ? `Both texts have unsaved changes: the preview shows the unsaved ${TITLES[layer]} with the saved ${SAVED_NAMES[rest]}. Save one of them to see both together.`
        : `The preview shows the unsaved ${TITLES[layer]} with the saved ${SAVED_NAMES[rest]}.`);
      this.render();
    }).catch(error => {
      if (!current()) return;
      this.problems[layer] = [];
      if (ticket === this.sequence) this.preview = { state: 'error', note: `No preview of ${TITLES[layer]}: ${error instanceof Error ? error.message : String(error)}`, assignments: [] };
      this.render();
    });
  }

  private async submit(layer: AgentsLayer): Promise<void> {
    const view = this.layers[layer]; const base = view.base; const project = this.project;
    if (base === null || project === null || view.busy) return;
    const generation = this.generation; const text = view.text.value;
    view.error.hidden = true; view.busy = true; this.enable(layer);
    try {
      const result = await this.request(project, new api.AgentsAction_Replace(scope(layer), base.revision, text));
      if (result instanceof api.Result_Agents) {
        const saved = result.value[layer];
        // A reply that arrives after the dialog was reopened for a fresh load or for another project's text must not become its base.
        if (view.base === base) this.adopt(layer, saved);
        const kept = layer === 'project' ? this.drafts.get(project.value) : undefined;
        if (kept !== undefined && kept.base === base) {
          if (kept.text === saved.text) this.drafts.delete(project.value); else this.drafts.set(project.value, { base: saved, text: kept.text });
        }
        this.effects.saved(layer, saved, saved.revision.value !== base.revision.value);
        if (generation === this.generation && this.project === project) this.settle(result.value);
      } else if (result instanceof api.Result_Failed && result.fault instanceof api.Fault_Conflict) {
        const current = await this.read(project);
        if (generation === this.generation && view.base === base) this.showConflict(layer, base, current[layer]);
      } else if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      else throw new Error('Unexpected agent configuration response');
    } finally { view.busy = false; this.enable(layer); }
  }
  private showConflict(layer: AgentsLayer, base: api.AgentsDocument, current: api.AgentsDocument): void {
    const view = this.layers[layer];
    const text = element('pre', current.text); text.setAttribute('aria-label', `Current ${SAVED_NAMES[layer]}`); text.tabIndex = 0;
    const actions = element('div', ''); actions.className = 'agents-conflict-actions';
    actions.append(
      button('Use current revision as base', () => { if (view.base === base) { this.adopt(layer, current); this.send(layer); this.render(); } }),
      button('Discard my text and take the current one', () => {
        if (view.base !== base) return;
        this.adopt(layer, current); view.text.value = current.text; this.send(layer); this.render();
      }));
    view.conflict.replaceChildren(
      element('h4', 'Edit conflict'),
      element('p', `Your text is based on revision ${base.revision.value}; the current revision is ${current.revision.value}. ${this.provenance(layer, current)}`),
      element('p', 'The current text is below and your text is above. Changing the base keeps your text; a later save replaces the current text.'),
      text, actions);
    view.conflict.hidden = false;
  }

  /** Moves the caret of a layer's editor to a 1-based line and column, which counts UTF-16 code units as the server does. */
  private reveal(layer: AgentsLayer, at: api.TextPosition): void {
    const text = this.layers[layer].text; const lines = text.value.split('\n');
    const line = Math.min(Math.max(at.line, 1), lines.length);
    const offset = lines.slice(0, line - 1).reduce((sum, value) => sum + value.length + 1, 0) + Math.min(Math.max(at.column - 1, 0), lines[line - 1].length);
    text.focus(); text.setSelectionRange(offset, Math.min(offset + 1, text.value.length));
  }
  private position(layer: AgentsLayer, problem: api.AgentProblem): Node {
    const at = problemPosition(problem); if (at === null) return document.createTextNode('');
    const jump = button(`${at.line}:${at.column}`, () => this.reveal(layer, at)); jump.className = 'agents-position';
    jump.setAttribute('aria-label', `${TITLES[layer]}, line ${at.line}, column ${at.column}: show in the editor`);
    return jump;
  }
  private render(): void {
    const listed = LAYERS.flatMap(layer => this.problems[layer].map(problem => ({ layer, problem })));
    const heading = element('h3', listed.length === 0 ? 'Problems: none' : `Problems: ${listed.length}`);
    if (listed.length === 0) this.problemsPanel.replaceChildren(heading);
    else {
      const list = element('ul', '');
      for (const { layer, problem } of listed) {
        const item = element('li', ''); item.dataset.layer = layer;
        const name = element('span', TITLES[layer]); name.className = 'agents-problem-layer';
        item.append(name, this.position(layer, problem), element('span', problemText(problem))); list.append(item);
      }
      this.problemsPanel.replaceChildren(heading, list);
    }
    const preview = this.preview;
    const note = element('p', preview.note); note.className = preview.state === 'current' || preview.state === 'pending' ? 'revision-meta' : 'agents-unresolved';
    if (preview.state === 'error') note.setAttribute('role', 'alert'); else note.setAttribute('role', 'status');
    this.previewPanel.dataset.state = preview.state;
    this.previewPanel.replaceChildren(element('h3', 'Preview: who runs each role'), note, ...(preview.assignments.length === 0 ? [] : [this.table(preview.assignments)]));
  }
  /**
   * Roles down, governing harnesses across: each cell with its seats, where the role was found and whether it is a self-review. A mode
   * that a key of its own decides under some harness has a row after the row of its role, which then stands for the other modes; a
   * harness without that key shows there what its key of the role assigns, and says so in the source.
   */
  private table(assignments: api.ResolvedAssignment[]): HTMLTableElement {
    const table = element('table', ''); table.className = 'agents-table'; table.setAttribute('aria-label', 'Resolved models by governing harness and role');
    const head = element('thead', ''); const headings = element('tr', ''); const corner = element('th', 'Role'); corner.scope = 'col'; headings.append(corner);
    for (const harness of api.Harness_values) { const cell = element('th', `${harnessName(harness)} governs`); cell.scope = 'col'; headings.append(cell); }
    head.append(headings); const body = element('tbody', '');
    for (const role of api.AgentRole_values) {
      const assigned = assignments.filter(candidate => keyRole(candidate.key) === role);
      const modes = MODES[role].filter(mode => assigned.some(candidate => keyMode(candidate.key) === mode));
      const rows: Array<string | null> = assigned.length === 0 || assigned.some(candidate => keyMode(candidate.key) === null) ? [null, ...modes] : modes;
      for (const mode of rows) {
        const line = element('tr', ''); const name = element('th', mode === null ? role : `${role}/${mode}`); name.scope = 'row';
        if (mode === null && modes.length > 0) { const rest = element('span', 'other modes'); rest.className = 'agents-other-modes'; name.append(' ', rest); }
        line.append(name);
        for (const harness of api.Harness_values) {
          const under = assigned.filter(candidate => candidate.harness === harness);
          const assignment = under.find(candidate => keyMode(candidate.key) === mode) ?? under.find(candidate => keyMode(candidate.key) === null);
          const cell = element('td', ''); cell.dataset.harness = harness; cell.dataset.role = role;
          if (mode !== null) cell.dataset.mode = mode;
          if (assignment === undefined) cell.append('—'); else this.cell(cell, assignment, mode === null ? null : assignment.key);
          line.append(cell);
        }
        body.append(line);
      }
    }
    table.append(head, body); return table;
  }
  // `key`: the key the source names. The row of a mode names it, because either the key of the mode or the key of its role decides there.
  private cell(cell: HTMLTableCellElement, assignment: api.ResolvedAssignment, key: api.RoleKey | null): void {
    const resolution = assignment.resolution;
    if (resolution instanceof api.RoleResolution_Unresolved) {
      cell.className = 'agents-unresolved'; cell.dataset.state = 'unresolved';
      const list = element('ul', '');
      for (const problem of resolution.problems) {
        const item = element('li', '');
        if (resolution.origin !== undefined) item.append(this.position(layerOf(resolution.origin.layer), problem));
        item.append(element('span', problemText(problem))); list.append(item);
      }
      cell.append(element('strong', 'Not resolved'), list);
      if (resolution.origin !== undefined) cell.append(this.source(resolution.origin, assignment.harness, key));
      return;
    }
    const plan = resolution.plan; cell.dataset.state = 'resolved';
    const single = plan.mode === api.PanelMode.All && plan.min === 1 && plan.seats.length === 1;
    if (!single) {
      const panel = element('p', `${lower(plan.mode)} panel · min ${plan.min} of ${plan.seats.length}`); panel.className = 'agents-panel';
      panel.title = plan.mode === api.PanelMode.All ? 'Every seat reviews; at least min of them must deliver' : 'Seats run in this order, only as many as min needs; the next one starts when one abstains or fails';
      cell.append(panel);
    }
    const seats = element(single ? 'div' : 'ol', ''); seats.className = 'agents-seats';
    for (const seat of plan.seats) {
      const item = element(single ? 'div' : 'li', '');
      if (seat.candidates.length > 1 || seat.strategy !== api.SeatStrategy.Fallback) {
        const strategy = element('span', `${STRATEGIES[seat.strategy]}:`); strategy.className = 'agents-strategy'; strategy.title = STRATEGY_HINTS[seat.strategy]; item.append(strategy);
      }
      for (const route of seat.candidates) { const model = element('code', routeText(route)); model.className = 'agents-route'; item.append(model); }
      seats.append(item);
    }
    cell.append(seats, this.source(plan.origin, assignment.harness, key));
    if (plan.selfReview.length > 0) {
      const note = element('p', `Self-review${single ? '' : ` (seat ${plan.selfReview.map(index => index + 1).join(', ')})`}: a model of ${harnessName(assignment.harness)}, the governing harness, can review its own work.`);
      note.className = 'agents-self-review'; cell.append(note);
    }
  }
  private source(origin: api.RoleOrigin, governing: api.Harness, key: api.RoleKey | null): HTMLParagraphElement {
    const source = element('p', `from ${sourceText(origin, governing)}${key === null ? '' : `.${keyText(key)}`}`); source.className = 'agents-source';
    source.dataset.layer = layerOf(origin.layer); source.dataset.source = origin.source;
    return source;
  }
}
