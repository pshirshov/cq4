import { uuidV4 } from './uuid.js';
import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { button, element } from './editor.js';
import { itemName, parseItem } from './items.js';
import { Dialog } from './dialog.js';
import { holdButton } from './hold-button.js';
import { faultMessage } from './faults.js';

const CONTEXT = BaboonCodecContext.Default;
interface Preview { input: api.ChangeInput; description: HTMLElement[]; destructive: boolean }
interface GraphEffects {
  call(command: api.Command): Promise<api.Result>;
  select(id: api.ItemId): Promise<void>;
  committed(project: api.ProjectId, ack: api.ChangeAck): Promise<void>;
  error(error: unknown): void;
  view(item: api.Item): HTMLElement;
}

export class GraphActions {
  // Closing discards an unconfirmed preview, so a confirmation still counting down cannot act on it afterwards.
  readonly dialog = new Dialog('standard', () => { this.shown = null; this.generation++; this.preview = null; this.renderPreview(); });
  private shown: { project: string; request: string } | null = null;
  readonly element = element('section', '');
  private readonly references = element('div', '');
  private readonly form = element('form', '');
  private readonly relation = element('select', '');
  private readonly target = element('input', '');
  private readonly matches = element('div', '');
  private targetGeneration = 0;
  private readonly previewPanel = element('section', '');
  private readonly review = button('Review graph change', () => this.focusPreview());
  private project: api.ProjectId | null = null;
  private view: api.ItemView | null = null;
  private generation = 0;
  private preview: Preview | null = null;
  private rendered: Preview | null = null;
  private pending: api.ChangeInput | null = null;
  private readonly busy = new Set<string>();

  constructor(private readonly effects: GraphEffects) {
    this.element.setAttribute('aria-label', 'Relationships and restore');
    this.form.className = 'graph-form';
    this.previewPanel.className = 'graph-preview'; this.previewPanel.tabIndex = -1;
    this.previewPanel.setAttribute('aria-label', 'Graph change preview');
    const relationLabel = element('label', 'This item…'); this.relation.setAttribute('aria-label', 'Relationship'); relationLabel.append(this.relation);
    for (const value of api.Relation_values) this.relation.append(element('option', value));
    const targetLabel = element('label', 'Related item'); targetLabel.append(this.target);
    this.target.setAttribute('aria-label', 'Target item'); this.target.setAttribute('role', 'combobox'); this.target.setAttribute('aria-autocomplete', 'list');
    this.matches.id = `relationship-matches-${uuidV4(crypto)}`; this.matches.setAttribute('role', 'listbox'); this.matches.setAttribute('aria-label', 'Matching items'); this.matches.hidden = true;
    this.target.setAttribute('aria-controls', this.matches.id); this.target.setAttribute('aria-expanded', 'false');
    this.target.required = true; this.target.placeholder = 'Search by title or enter T2'; this.target.maxLength = 300;
    const picker = element('div', ''); picker.className = 'relationship-picker'; picker.append(targetLabel, this.matches);
    this.target.addEventListener('input', () => { const generation = ++this.targetGeneration; this.action(() => this.searchTargets(this.target.value, generation)); });
    this.target.addEventListener('keydown', event => {
      if (event.key === 'ArrowDown' && this.matches.childElementCount > 0) { event.preventDefault(); (this.matches.firstElementChild as HTMLElement).focus(); }
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); this.clearMatches(); }
    });
    picker.addEventListener('focusout', event => { if (!(event.relatedTarget instanceof Node) || !picker.contains(event.relatedTarget)) this.clearMatches(); });
    const submit = element('button', 'Preview relationship'); submit.type = 'submit';
    this.form.append(relationLabel, picker, submit);
    this.form.addEventListener('submit', event => { event.preventDefault(); this.action(async () => {
      const view = this.view; if (view === null) return;
      const relation = api.Relation_values.find(value => value === this.relation.value);
      if (relation === undefined) throw new Error('Select a relationship');
      await this.previewReference(view, relation, parseItem(view.item.id.project, this.target.value), true);
    }); });
    this.dialog.body.append(this.previewPanel);
    this.element.append(element('h3', 'Relationships'), this.references, this.form, this.review);
    this.setScope(null, null);
  }

  private clearMatches(): void {
    this.targetGeneration++; this.matches.replaceChildren(); this.matches.hidden = true; this.target.setAttribute('aria-expanded', 'false');
  }
  private async searchTargets(text: string, generation: number): Promise<void> {
    const project = this.project;
    if (project === null || text.trim() === '') { this.clearMatches(); return; }
    try {
      const result = await this.effects.call(new api.Command_Search(new api.SearchInput(project, JSON.stringify(text.trim()), undefined, undefined, 20)));
      if (generation !== this.targetGeneration || project !== this.project) return;
      if (result instanceof api.Result_Failed) throw new Error('Relationship search failed; enter an exact item ID or retry.');
      if (!(result instanceof api.Result_Found)) throw new Error('Unexpected relationship search response');
      this.matches.replaceChildren();
      for (const item of result.page.items) {
        if (this.view !== null && itemName(item.id) === itemName(this.view.item.id)) continue;
        const option = button(`${itemName(item.id)} · ${item.title}`, () => { this.target.value = itemName(item.id); this.clearMatches(); this.target.focus(); });
        option.setAttribute('role', 'option'); option.setAttribute('aria-selected', 'false');
        option.addEventListener('keydown', event => {
          const next = event.key === 'ArrowDown' ? option.nextElementSibling : event.key === 'ArrowUp' ? option.previousElementSibling : null;
          if (next instanceof HTMLElement) { event.preventDefault(); next.focus(); }
          if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); this.clearMatches(); this.target.focus(); }
        });
        this.matches.append(option);
      }
      this.matches.hidden = this.matches.childElementCount === 0; this.target.setAttribute('aria-expanded', String(!this.matches.hidden));
    } catch (error) { if (generation === this.targetGeneration && project === this.project) throw error; }
  }

  private action(effect: () => Promise<void>): void { void effect().catch(error => this.effects.error(error)); }
  private key(project: api.ProjectId): string { return `cq-graph-change:${project.value}`; }
  private encode(input: api.ChangeInput): string { return JSON.stringify(api.ChangeInput_JsonCodec.instance.encode(CONTEXT, input)); }
  private request(project: api.ProjectId, mutation: api.Mutation, reason: string): api.ChangeInput {
    return new api.ChangeInput(project, new api.ChangeRequest(new api.RequestId(uuidV4(crypto)), [mutation], [], reason));
  }
  private async detail(id: api.ItemId, generation: number): Promise<api.ItemView | null> {
    try {
      const result = await this.effects.call(new api.Command_Read(new api.ReadInput(id.project, new api.ReadSelection_ItemDetail(id))));
      if (generation !== this.generation) return null;
      if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      if (!(result instanceof api.Result_Detail)) throw new Error('Unexpected relationship detail response');
      return result.view;
    } catch (error) { if (generation === this.generation) throw error; return null; }
  }
  setScope(project: api.ProjectId | null, view: api.ItemView | null): void {
    const changedProject = this.project === null ? project !== null : project === null || this.project.value !== project.value;
    const changedItem = this.view === null ? view !== null : view === null || itemName(this.view.item.id) !== itemName(view.item.id);
    if (changedProject || changedItem) {
      this.dialog.close();
      this.generation++; this.preview = null; this.target.value = ''; this.clearMatches();
    }
    this.project = project; this.view = view;
    if (changedProject) {
      this.pending = null;
      if (project !== null) {
        const saved = localStorage.getItem(this.key(project));
        if (saved !== null) {
          const input = api.ChangeInput_JsonCodec.instance.decode(CONTEXT, JSON.parse(saved));
          if (input.project.value !== project.value) throw new Error('Stored graph change belongs to another project');
          this.pending = input;
        }
      }
    }
    this.references.replaceChildren();
    if (view !== null) {
      for (const ref of view.refs) {
        const row = element('p', `${ref.relation} `);
        row.append(button(`Open ${itemName(ref.target)}`, () => this.action(() => this.effects.select(ref.target))),
          button(`Remove ${ref.relation} ${itemName(ref.target)}`, () => this.action(() => this.previewReference(view, ref.relation, ref.target, false))));
        this.references.append(row);
      }
      if (view.refs.length === 0) this.references.append(element('p', 'No relationships.'));
    }
    this.form.hidden = view === null;
    this.element.hidden = view === null && this.pending === null;
    this.renderPreview();
    if (changedProject && this.pending !== null) this.focusPreview();
  }
  private async previewReference(view: api.ItemView, relation: api.Relation, target: api.ItemId, present: boolean): Promise<void> {
    if (this.pending !== null) throw new Error('Resolve the pending graph change before preparing another.');
    const generation = ++this.generation; this.preview = null; this.renderPreview();
    const neighbor = await this.detail(target, generation); if (neighbor === null) return;
    const source = view.item;
    this.preview = { input: this.request(source.id.project, new api.Mutation_Reference(source.id, source.revision, relation, target, neighbor.item.revision, present), 'Browser relationship'),
      description: [element('p', `${present ? 'Add' : 'Remove'} ${itemName(source.id)} ${relation} ${itemName(target)}.`),
        element('p', `Expected revisions: ${itemName(source.id)} @ ${source.revision.value}; ${itemName(target)} @ ${neighbor.item.revision.value}. Both endpoints receive a new revision if the relationship changes.`)], destructive: !present };
    this.renderPreview(); this.focusPreview();
  }
  async restore(historical: api.ItemView): Promise<void> {
    if (this.pending !== null) throw new Error('Resolve the pending graph change before preparing another.');
    const view = this.view;
    if (view === null || this.project === null || historical.item.id.project.value !== this.project.value || itemName(view.item.id) !== itemName(historical.item.id)) return;
    const generation = ++this.generation; this.preview = null; this.renderPreview();
    const refKey = (ref: api.ItemRef) => `${ref.relation}:${itemName(ref.target)}`;
    const current = new Set(view.refs.map(refKey)); const old = new Set(historical.refs.map(refKey));
    const removed = view.refs.filter(ref => !old.has(refKey(ref))); const added = historical.refs.filter(ref => !current.has(refKey(ref)));
    const ids = new Map([...removed, ...added].map(ref => [itemName(ref.target), ref.target]));
    const neighbors: api.ItemRevision[] = [];
    for (const id of ids.values()) {
      const neighbor = await this.detail(id, generation); if (neighbor === null) return;
      neighbors.push(new api.ItemRevision(id, neighbor.item.revision));
    }
    this.preview = { input: this.request(view.item.id.project, new api.Mutation_Restore(view.item.id, view.item.revision, historical.item.revision, neighbors), 'Browser restore'),
      description: [element('p', `Restore ${itemName(view.item.id)} from historical revision ${historical.item.revision.value}. Expected current revision: ${view.item.revision.value}. This creates a new revision.`),
        ...removed.map(ref => element('p', `Remove ${ref.relation} ${itemName(ref.target)}`)), ...added.map(ref => element('p', `Add ${ref.relation} ${itemName(ref.target)}`)),
        element('p', neighbors.length === 0 ? 'No relationships change.' : `Neighbors receiving new revisions: ${neighbors.map(value => `${itemName(value.id)} @ ${value.revision.value}`).join('; ')}. Their content is preserved.`),
        element('h4', 'Current content'), this.effects.view(view.item),
        element('h4', 'Content to restore'), this.effects.view(historical.item)], destructive: true };
    this.renderPreview(); this.focusPreview();
  }
  private focusPreview(): void {
    const input = this.pending !== null ? this.pending : this.preview === null ? null : this.preview.input;
    if (input === null) return;
    this.shown = { project: input.project.value, request: input.change.request.value };
    this.dialog.open('Graph change'); this.previewPanel.focus();
  }
  private renderPreview(): void {
    this.review.hidden = this.pending === null && this.preview === null;
    for (const control of this.form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>('input,select,button')) control.disabled = this.pending !== null;
    for (const control of this.references.querySelectorAll<HTMLButtonElement>('button')) if (control.textContent !== null && control.textContent.startsWith('Remove ')) control.disabled = this.pending !== null;
    // A live refresh renders the same preview again; replacing its controls would cancel a hold in progress.
    const shown = this.pending === null ? this.preview : null;
    if (shown !== null && shown === this.rendered) return;
    this.rendered = shown; this.previewPanel.replaceChildren();
    if (this.pending !== null) {
      const input = this.pending;
      const retry = button('Retry exact graph change', () => this.action(() => this.submit(input)));
      retry.disabled = this.busy.has(input.project.value);
      this.previewPanel.append(element('h3', 'Pending graph change'), element('p', `Project ${input.project.value}. Its acknowledgement is unresolved. Retry this exact request before preparing another relationship or restore change.`),
        element('p', `Request ${input.change.request.value}`), retry);
    } else if (this.preview !== null) {
      const preview = this.preview;
      this.previewPanel.append(element('h3', 'Graph change preview'), ...preview.description,
        element('p', 'Confirmation uses these exact revisions. Concurrent edits reject the entire change; prepare a fresh preview after inspecting them.'),
        (preview.destructive ? holdButton : button)('Confirm graph change', () => this.action(async () => {
          if (this.preview !== preview || this.pending !== null) return;
          const key = this.key(preview.input.project);
          if (localStorage.getItem(key) !== null) throw new Error('Another graph change is stored for this project; reload to resolve it.');
          localStorage.setItem(key, this.encode(preview.input));
          this.pending = preview.input; this.preview = null; await this.submit(preview.input);
        })), button('Cancel graph preview', () => this.dialog.close()));
    }
  }
  private async submit(input: api.ChangeInput): Promise<void> {
    const project = input.project.value; if (this.busy.has(project)) return;
    const key = this.key(input.project); const submitted = this.encode(input);
    if (localStorage.getItem(key) !== submitted) throw new Error('Stored graph change changed; reload before retrying.');
    this.busy.add(project); this.renderPreview();
    try {
      const result = await this.effects.call(new api.Command_Change(input));
      if (!(result instanceof api.Result_Changed) && !(result instanceof api.Result_Failed)) throw new Error('Unexpected graph change acknowledgement');
      if (localStorage.getItem(key) === submitted) localStorage.removeItem(key);
      if (this.pending !== null && this.pending.change.request.value === input.change.request.value) { this.pending = null; this.renderPreview(); }
      if (result instanceof api.Result_Failed) throw new Error(`Graph change rejected. Inspect the current records and prepare a new preview. ${faultMessage(result.fault)}`);
      if (this.shown !== null && this.shown.project === project && this.shown.request === input.change.request.value) this.dialog.close();
      await this.effects.committed(input.project, result.ack);
    } finally { this.busy.delete(project); this.renderPreview(); }
  }
}
