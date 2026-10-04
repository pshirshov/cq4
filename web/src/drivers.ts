import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { holdButton } from './hold-button.js';
import { itemName, parseItem } from './items.js';
import { faultMessage } from './faults.js';

const POLL_MS = 5000;
const QUERY_PAGE_SIZE = 40;
const MAX_TARGETS = 64;
const SAVED_PAGE_SIZE = 8;
const LABEL_TARGETS = 3;
const CONTEXT = BaboonCodecContext.Default;
interface DriverEffects {
  call(command: api.Command): Promise<api.Result>;
  filter(workset: api.StoredWorkset): Promise<void>;
  view(id: api.ItemId): void;
}
function reason(value: api.WorksetReason): string {
  if (value instanceof api.WorksetReason_Blocked) return `Blocked by ${itemName(value.prerequisite)}`;
  if (value instanceof api.WorksetReason_Shared) return `Shared with ${itemName(value.producer)}`;
  if (value instanceof api.WorksetReason_Context) return `${value.relation} from ${itemName(value.source)}`;
  if (value instanceof api.WorksetReason_Archived) return 'Archived';
  if (value instanceof api.WorksetReason_Terminal) return 'Terminal';
  if (value instanceof api.WorksetReason_Settled) return 'Settled';
  throw new Error('Unknown workset readiness reason');
}

export class DriversDialog {
  private generation = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private readonly dialog = new Dialog('large', () => this.reset());
  readonly element = this.dialog.element;
  private project: api.ProjectId | null = null;
  private readonly freshness = element('p', '');
  private readonly drivers = element('section', '');
  private readonly mode = element('select', '');
  private readonly targets = element('textarea', '');
  private readonly phase = element('select', '');
  private readonly saved = element('section', '');
  private readonly savedRows = element('div', '');
  private savedAfter: api.WorksetId | undefined;
  private savedGeneration = 0;
  private readonly moreSaved = button('More saved worksets', () => this.action(() => this.loadSaved(false)));
  private readonly search = element('input', '');
  private readonly matches = element('div', '');
  private searchGeneration = 0;
  private readonly previewPanel = element('section', '');
  private readonly store = button('Store previewed workset', () => this.action(() => this.save()));
  private readonly filter = button('Filter items to workset', () => this.action(async () => {
    const workset = this.stored;
    if (workset === null) throw new Error('Store or open a workset first');
    await this.effects.filter(workset); this.dialog.close();
  }));
  private preview: api.WorksetPreview | null = null;
  private stored: api.StoredWorkset | null = null;
  private previewGeneration = 0;
  private rowsIdentity = '';
  private busy = false;

  constructor(private readonly effects: DriverEffects) {
    this.mode.setAttribute('aria-label', 'Workset target mode');
    for (const [value, label] of [['ids', 'Item IDs'], ['query', 'Search query']]) {
      const option = element('option', label); option.value = value; this.mode.append(option);
    }
    this.targets.setAttribute('aria-label', 'Workset targets'); this.targets.rows = 3; this.targets.maxLength = 4096;
    this.phase.setAttribute('aria-label', 'Workset through phase');
    for (const value of api.WorkflowPhase_values) { const option = element('option', value); option.value = value; this.phase.append(option); }
    this.phase.value = api.WorkflowPhase.Integrate;
    this.targets.placeholder = 'For example: D70 T56 G1';
    this.search.setAttribute('aria-label', 'Find items for workset'); this.search.placeholder = 'Find an item by title or ID';
    this.search.maxLength = 300;
    this.matches.className = 'workset-matches';
    this.search.addEventListener('input', () => { const generation = ++this.searchGeneration; this.action(() => this.findTargets(generation)); });
    const invalidate = () => this.invalidate();
    this.mode.addEventListener('change', invalidate); this.phase.addEventListener('change', invalidate); this.targets.addEventListener('input', invalidate);
    this.mode.addEventListener('change', () => { this.search.hidden = this.mode.value === 'query'; this.matches.replaceChildren(); this.searchGeneration++; });
    this.store.disabled = true; this.filter.disabled = true;
    const create = element('section', ''); create.className = 'workset-create';
    const fields = element('div', ''); fields.className = 'workset-fields';
    for (const [caption, control] of [['Select targets using', this.mode], ['Continue through', this.phase]] as const) {
      const label = element('label', caption); label.append(control); fields.append(label);
    }
    create.append(element('h3', 'Create workset'),
      element('p', 'A workset saves which items to advance and how far. Start its drive from your harness using cq drive.'),
      fields, this.search, this.matches, element('p', 'Targets: item IDs separated by spaces or commas, or the query selected above.'), this.targets, button('Preview targets', () => this.action(() => this.define())));
    this.saved.append(element('h3', 'Saved worksets'), element('p', 'Choose a saved scope to inspect it or filter the item list.'),
      button('Refresh saved worksets', () => this.action(() => this.loadSaved(true))), this.savedRows, this.moreSaved);
    this.moreSaved.hidden = true;
    this.dialog.body.classList.add('drivers-layout');
    const navigation = element('nav', ''); navigation.className = 'workset-navigation'; navigation.setAttribute('aria-label', 'Drivers and worksets sections');
    for (const [label, section] of [['View drives', this.drivers], ['Choose saved scope', this.saved], ['Create workset', create]] as const)
      navigation.append(button(label, () => { section.scrollIntoView({ block: 'start' }); section.tabIndex = -1; section.focus({ preventScroll: true }); }));
    this.dialog.body.append(navigation, this.freshness, this.drivers, this.saved, create, this.previewPanel);
    const actions = element('div', ''); actions.className = 'actions'; actions.append(this.store, this.filter); this.previewPanel.after(actions);
  }
  reset(): void {
    this.generation++; this.project = null;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null; this.invalidate(); this.rowsIdentity = ''; this.drivers.replaceChildren();
    this.savedGeneration++; this.searchGeneration++; this.search.value = ''; this.matches.replaceChildren(); this.savedRows.replaceChildren(); this.savedAfter = undefined; this.moreSaved.hidden = true;
  }
  private invalidate(): void {
    this.previewGeneration++; this.preview = null; this.stored = null; this.store.disabled = true; this.filter.disabled = true;
    this.previewPanel.replaceChildren();
  }
  open(project: api.ProjectId, selected: api.ItemId | null): void {
    this.reset(); this.project = project;
    this.mode.value = 'ids'; this.search.hidden = false; this.targets.value = selected === null ? '' : itemName(selected);
    this.dialog.open('Drivers and worksets'); this.freshness.textContent = 'Loading drivers…';
    this.action(() => this.refresh()); this.action(() => this.loadSaved(true));
  }
  private currentProject(): api.ProjectId { if (this.project === null) throw new Error('Driver dialog has no project'); return this.project; }
  private action(effect: () => Promise<void>): void {
    const generation = this.generation;
    void effect().catch(error => {
      if (generation === this.generation) { this.dialog.error.hidden = false; this.dialog.error.textContent = String(error); }
    });
  }
  private async call(command: api.Command): Promise<api.Result> {
    const result = await this.effects.call(command);
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    return result;
  }
  private async refresh(): Promise<void> {
    const generation = this.generation; const project = this.currentProject();
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    try {
      const result = await this.call(new api.Command_Driver(new api.DriverInput(project, new api.DriverRequest_Summaries())));
      if (generation !== this.generation) return;
      if (!(result instanceof api.Result_Driver) || !(result.reply instanceof api.DriverReply_Listed)) throw new Error('Unexpected driver list');
      const identity = JSON.stringify(result.reply.drivers.map(value => api.DriverSummary_JsonCodec.instance.encode(CONTEXT, value)));
      if (identity !== this.rowsIdentity) { this.rowsIdentity = identity; this.renderDrivers(result.reply.drivers); }
      this.freshness.textContent = `Drivers observed ${new Date().toLocaleTimeString()}`;
    } catch (error) {
      if (generation !== this.generation) return;
      this.freshness.textContent = 'Driver list is stale; refresh failed.';
      this.dialog.error.hidden = false; this.dialog.error.textContent = String(error);
    } finally {
      if (generation === this.generation && this.dialog.element.open) this.timer = setTimeout(() => this.action(() => this.refresh()), POLL_MS);
    }
  }
  private scopeLabel(targets: Set<api.ItemId>): string { return [...targets].map(itemName).join(', '); }
  private async titleScope(node: HTMLElement, targets: Set<api.ItemId>, generation: number): Promise<void> {
    const names = [...targets].slice(0, LABEL_TARGETS).map(itemName);
    const result = await this.call(new api.Command_Search(new api.SearchInput(this.currentProject(), `(${names.join(' OR ')}) archived:all`, undefined, undefined, LABEL_TARGETS)));
    if (generation !== this.generation || !node.isConnected) return;
    if (!(result instanceof api.Result_Found)) throw new Error('Unexpected scope titles');
    const titles = new Map(result.page.items.map(value => [itemName(value.id), value.title]));
    node.textContent = names.map(name => `${name} · ${titles.has(name) ? titles.get(name) : 'Item unavailable'}`).join('; ') +
      (targets.size > LABEL_TARGETS ? `; and ${targets.size - LABEL_TARGETS} more` : '');
  }
  private renderDrivers(values: api.DriverSummary[]): void {
    this.drivers.replaceChildren(element('h3', 'Running drives'),
      element('p', 'Inspect the scope and current state of each harness. Hold Park to stop automatic continuation.'));
    if (values.length === 0) this.drivers.append(element('p', 'No drives have been started in this project.'));
    for (const value of [...values].sort((a, b) => Number(a.state === api.DriverState.Off) - Number(b.state === api.DriverState.Off))) {
      const card = element('article', ''); card.className = 'drive-card'; card.setAttribute('aria-label', `${value.key.harness} drive ${value.key.session}`);
      const title = element('h4', this.scopeLabel(value.targets)); const state = element('span', value.state); state.className = 'badge';
      const status = element('p', `${value.key.harness} · through ${value.through} · ${value.activeChildren} active children `); status.append(state);
      const controls = element('div', ''); controls.className = 'actions';
      controls.append(button('Inspect scope', () => this.action(() => this.showTarget(new api.WorksetTarget_Inline(value.targets, value.through), 'Current workset evaluation', undefined))));
      if (value.workset !== undefined) { const id = value.workset; controls.append(button('Open saved scope', () => this.action(() => this.lookup(id)))); }
      if (value.cycle !== undefined) controls.append(button('View cycle snapshot', () => this.action(async () => {
        const generation = this.generation; this.invalidate(); const previewGeneration = this.previewGeneration;
        const result = await this.call(new api.Command_Driver(new api.DriverInput(this.currentProject(), new api.DriverRequest_Snapshot(value.key, value.revision))));
        if (generation !== this.generation || previewGeneration !== this.previewGeneration) return;
        if (!(result instanceof api.Result_Driver) || !(result.reply instanceof api.DriverReply_Snapshot)) throw new Error('Unexpected cycle snapshot');
        this.showPreview(result.reply.preview, 'Frozen cycle snapshot');
      })));
      const park = holdButton(`Park ${value.key.harness} ${value.key.session}`, () => this.action(async () => {
        const generation = this.generation; park.disabled = true;
        try {
          await this.call(new api.Command_Driver(new api.DriverInput(this.currentProject(), new api.DriverRequest_Park(value.key, value.revision))));
          if (generation === this.generation && this.dialog.element.open) await this.refresh();
        } finally { park.disabled = value.state === api.DriverState.Off; }
      }));
      park.textContent = 'Hold to park'; park.disabled = value.state === api.DriverState.Off; controls.append(park);
      card.append(title, status);
      if (value.state === api.DriverState.Binding) card.append(element('p', 'Waiting for the harness to connect and accept this scope.'));
      if (value.state === api.DriverState.On) card.append(element('p', 'Automatic continuation is enabled.'));
      if (value.stopped !== undefined) card.append(element('p', `Stopped: ${value.stopped.reason} · ${value.stopped.detail}`));
      const details = element('details', ''); details.append(element('summary', 'Session details'),
        element('p', `Session: ${value.key.session}`), element('p', `Last activity: ${new Date(Number(value.touchedAt)).toLocaleString()}`));
      if (value.attached !== undefined) details.append(element('p', `Attached session: ${value.attached.value}`));
      card.append(controls, details); this.drivers.append(card);
      this.action(() => this.titleScope(title, value.targets, this.generation));
    }
  }
  private async loadSaved(reset: boolean): Promise<void> {
    const generation = this.generation;
    const savedGeneration = ++this.savedGeneration;
    this.moreSaved.disabled = true;
    const result = await this.call(new api.Command_Workset(new api.WorksetInput(this.currentProject(), new api.WorksetAction_BrowseSaved(reset ? undefined : this.savedAfter, SAVED_PAGE_SIZE))));
    if (generation !== this.generation || savedGeneration !== this.savedGeneration) return;
    if (!(result instanceof api.Result_WorksetsListed)) throw new Error('Unexpected saved worksets');
    if (reset) this.savedRows.replaceChildren();
    for (const value of result.page.entries) {
      const card = element('article', ''); card.className = 'workset-card';
      const open = button(this.scopeLabel(value.targets), () => this.action(() => this.lookup(value.id)));
      card.append(open, element('p', `Through ${value.through} · saved ${new Date(Number(value.createdAt)).toLocaleString()}`));
      this.savedRows.append(card); this.action(() => this.titleScope(open, value.targets, generation));
    }
    if (reset && result.page.entries.length === 0) this.savedRows.append(element('p', 'No saved worksets yet. Create one below.'));
    this.savedAfter = result.page.after; this.moreSaved.hidden = !result.page.hasMore; this.moreSaved.disabled = false;
    if (result.page.hasMore && this.savedAfter === undefined) throw new Error('Saved workset continuation is missing');
  }
  private async findTargets(generation: number): Promise<void> {
    const project = this.currentProject(); const text = this.search.value.trim(); this.matches.replaceChildren();
    if (text === '') return;
    const query = /^[A-Z]+[1-9][0-9]*$/.test(text) ? text : JSON.stringify(text);
    const result = await this.call(new api.Command_Search(new api.SearchInput(project, query, undefined, undefined, 20)));
    if (generation !== this.searchGeneration || this.project !== project) return;
    if (!(result instanceof api.Result_Found)) throw new Error('Unexpected item search');
    for (const value of result.page.items) this.matches.append(button(`${itemName(value.id)} · ${value.title}`, () => {
      this.mode.value = 'ids'; this.targets.value = `${this.targets.value} ${itemName(value.id)}`.trim(); this.invalidate();
      this.search.value = ''; this.matches.replaceChildren(); this.search.focus();
    }));
    if (result.page.items.length === 0) this.matches.append(element('p', 'No matching items.'));
  }
  private async define(): Promise<void> {
    const generation = this.generation; const project = this.currentProject(); const phase = this.phase.value as api.WorkflowPhase;
    const mode = this.mode.value; const input = this.targets.value;
    this.invalidate(); const previewGeneration = this.previewGeneration;
    let targets: Set<api.ItemId>; let querySnapshot: api.ChangeCursor | undefined;
    if (mode === 'ids') {
      const byName = new Map(input.split(/[\s,]+/).filter(Boolean).map(text => { const id = parseItem(project, text); return [itemName(id), id] as const; }));
      targets = new Set(byName.values());
    } else {
      const found = new Map<string, api.ItemId>(); let after: api.ItemId | undefined; let snapshot: api.ChangeCursor | undefined;
      while (true) {
        const result = await this.call(new api.Command_Search(new api.SearchInput(project, input, after, snapshot, QUERY_PAGE_SIZE)));
        if (generation !== this.generation || previewGeneration !== this.previewGeneration) return;
        if (!(result instanceof api.Result_Found)) throw new Error('Unexpected workset query result');
        for (const item of result.page.items) found.set(itemName(item.id), item.id);
        if (found.size > MAX_TARGETS) throw new Error(`Choose a query with at most ${MAX_TARGETS} targets`);
        snapshot = result.page.cursor; after = result.page.after;
        if (!result.page.hasMore) break;
        if (after === undefined) throw new Error('Query continuation is missing');
      }
      targets = new Set(found.values()); querySnapshot = snapshot;
    }
    if (generation !== this.generation || previewGeneration !== this.previewGeneration) return;
    if (targets.size > MAX_TARGETS) throw new Error(`Choose at most ${MAX_TARGETS} targets`);
    if (targets.size === 0) throw new Error('Workset targets must be non-empty');
    await this.showTarget(new api.WorksetTarget_Inline(targets, phase), 'Current workset evaluation', querySnapshot);
  }
  private async showTarget(target: api.WorksetTarget, label: string, expectedSnapshot: api.ChangeCursor | undefined): Promise<void> {
    const generation = this.generation; this.invalidate(); const previewGeneration = this.previewGeneration;
    const result = await this.call(new api.Command_Workset(new api.WorksetInput(this.currentProject(), new api.WorksetAction_Preview(target))));
    if (generation !== this.generation || previewGeneration !== this.previewGeneration) return;
    if (!(result instanceof api.Result_WorksetPreviewed)) throw new Error('Unexpected workset preview');
    if (expectedSnapshot !== undefined && result.preview.snapshot.cursor.value !== expectedSnapshot.value) throw new Error('Query snapshot changed; submit the query again');
    this.showPreview(result.preview, label);
  }
  private showPreview(preview: api.WorksetPreview, label: string): void {
    this.preview = preview; this.stored = null; this.store.disabled = false; this.filter.disabled = true;
    const table = element('table', ''); table.setAttribute('aria-label', 'Workset members');
    const head = element('tr', ''); for (const text of ['Role', 'Item', 'Readiness / context']) head.append(element('th', text)); table.append(head);
    const add = (role: string, item: api.ItemSummary, reasons: string) => {
      const row = element('tr', ''); const cell = element('td', ''); cell.append(button(`${itemName(item.id)} ${item.title}`, () => this.effects.view(item.id)));
      row.append(element('td', role), cell, element('td', reasons)); table.append(row);
    };
    for (const member of preview.advanceable) {
      const readiness = preview.readiness.find(value => itemName(value.item) === itemName(member.item.id));
      if (readiness === undefined) throw new Error('Workset member has no readiness');
      add('Advanceable', member.item, readiness.ready ? 'Ready' : readiness.reasons.map(reason).join('; '));
    }
    for (const member of preview.context) add('Context', member.item, member.reasons.map(reason).join('; '));
    const awaiting = [...preview.advanceable.map(value => value.item), ...preview.context.map(value => value.item)].filter(item =>
      !item.archived && ((item.id.ledger === api.Ledger.Questions && item.status === api.QuestionStatus.Open) ||
        (item.id.ledger === api.Ledger.OperatorActions && item.status === api.OperatorActionStatus.Requested)));
    this.previewPanel.replaceChildren(element('h3', label), element('p', `${[...preview.targets].map(itemName).join(', ')} through ${preview.through} · Snapshot ${preview.snapshot.cursor.value}`),
      element('p', `Awaiting operator: ${awaiting.length === 0 ? 'none' : awaiting.map(item => itemName(item.id)).join(', ')}`), table);
    this.previewPanel.scrollIntoView({ block: 'nearest' });
  }
  private async save(): Promise<void> {
    const preview = this.preview; const generation = this.generation; if (preview === null || this.busy) return;
    this.busy = true; this.store.disabled = true;
    try {
      const result = await this.call(new api.Command_Workset(new api.WorksetInput(this.currentProject(), new api.WorksetAction_StorePreview(preview))));
      if (generation !== this.generation || this.preview !== preview) return;
      if (!(result instanceof api.Result_WorksetStored)) throw new Error('Unexpected stored workset');
      this.stored = result.workset; this.filter.disabled = false; this.action(() => this.loadSaved(true));
      this.previewPanel.prepend(element('p', 'Workset saved. Choose it again under Saved worksets.'));
    } finally { this.busy = false; this.store.disabled = this.preview === null; }
  }
  private async lookup(id: api.WorksetId): Promise<void> {
    const generation = this.generation; this.invalidate(); const previewGeneration = this.previewGeneration;
    const result = await this.call(new api.Command_Workset(new api.WorksetInput(this.currentProject(), new api.WorksetAction_Lookup(id))));
    if (generation !== this.generation || previewGeneration !== this.previewGeneration) return;
    if (!(result instanceof api.Result_WorksetStored)) throw new Error('Unexpected stored workset');
    const loading = this.showTarget(new api.WorksetTarget_Stored(result.workset.id), 'Current stored workset evaluation', undefined);
    const loadingGeneration = this.previewGeneration; await loading;
    if (generation !== this.generation || loadingGeneration !== this.previewGeneration) return;
    this.stored = result.workset; this.filter.disabled = false;
  }
}
