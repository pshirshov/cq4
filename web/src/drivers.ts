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
  private readonly storedId = element('input', '');
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
    for (const [value, label] of [['ids', 'Explicit IDs'], ['query', 'Submitted query']]) {
      const option = element('option', label); option.value = value; this.mode.append(option);
    }
    this.targets.setAttribute('aria-label', 'Workset targets'); this.targets.rows = 3; this.targets.maxLength = 4096;
    this.phase.setAttribute('aria-label', 'Workset through phase');
    for (const value of api.WorkflowPhase_values) { const option = element('option', value); option.value = value; this.phase.append(option); }
    this.phase.value = api.WorkflowPhase.Integrate;
    this.storedId.setAttribute('aria-label', 'Stored workset ID'); this.storedId.placeholder = 'Workset UUID';
    const invalidate = () => this.invalidate();
    this.mode.addEventListener('change', invalidate); this.phase.addEventListener('change', invalidate); this.targets.addEventListener('input', invalidate);
    this.store.disabled = true; this.filter.disabled = true;
    this.dialog.body.append(this.freshness, this.drivers, element('h3', 'Define workset'),
      element('p', 'Enter explicit IDs separated by spaces or commas, or submit a query. Preview resolves the targets before storing them.'),
      this.mode, this.targets, this.phase, button('Preview targets', () => this.action(() => this.define())),
      this.storedId, button('Open stored workset', () => this.action(() => this.lookup())),
      this.previewPanel, this.store, this.filter);
  }
  reset(): void {
    this.generation++; this.project = null;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null; this.invalidate(); this.rowsIdentity = ''; this.drivers.replaceChildren();
  }
  private invalidate(): void {
    this.previewGeneration++; this.preview = null; this.stored = null; this.store.disabled = true; this.filter.disabled = true;
    this.previewPanel.replaceChildren();
  }
  open(project: api.ProjectId, selected: api.ItemId | null): void {
    this.reset(); this.project = project;
    this.mode.value = 'ids'; this.targets.value = selected === null ? '' : itemName(selected); this.storedId.value = '';
    this.dialog.open('Drivers and worksets'); this.freshness.textContent = 'Loading drivers…';
    this.action(() => this.refresh());
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
  private renderDrivers(values: api.DriverSummary[]): void {
    const table = element('table', ''); table.className = 'drivers-table'; table.setAttribute('aria-label', 'Drivers');
    const head = element('tr', '');
    for (const label of ['Session', 'State', 'Targets / phase', 'Active children', 'Stop / times', 'Controls']) head.append(element('th', label));
    table.append(head);
    for (const value of values) {
      const row = element('tr', ''); const controls = element('td', '');
      const targetText = [...value.targets].map(itemName).join(', ');
      controls.append(button('View targets', () => this.action(async () => {
        await this.showTarget(new api.WorksetTarget_Inline(value.targets, value.through), 'Current workset evaluation', undefined);
      })));
      const workset = value.workset;
      if (workset !== undefined) controls.append(button('Open workset', () => this.action(async () => { this.storedId.value = workset.value; await this.lookup(); })));
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
          if (generation === this.generation && this.dialog.element.open) { if (this.timer !== null) clearTimeout(this.timer); await this.refresh(); }
        } finally { park.disabled = value.state === api.DriverState.Off; }
      }));
      park.textContent = 'Park';
      park.disabled = value.state === api.DriverState.Off; controls.append(park);
      const time = (value: bigint) => new Date(Number(value)).toLocaleString();
      row.append(element('td', `${value.key.harness} ${value.key.session}${value.attached === undefined ? '' : ` · attached ${value.attached.value}`}`),
        element('td', value.state), element('td', `${targetText} through ${value.through}`), element('td', String(value.activeChildren)),
        element('td', `${value.stopped === undefined ? '' : `${value.stopped.reason}: ${value.stopped.detail} · `}Touched ${time(value.touchedAt)}${value.stoppedAt === undefined ? '' : ` · Stopped ${time(value.stoppedAt)}`}`), controls);
      table.append(row);
    }
    this.drivers.replaceChildren(element('h3', 'Drivers'), values.length === 0 ? element('p', 'No stored drivers in this project.') : table);
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
  }
  private async save(): Promise<void> {
    const preview = this.preview; const generation = this.generation; if (preview === null || this.busy) return;
    this.busy = true; this.store.disabled = true;
    try {
      const result = await this.call(new api.Command_Workset(new api.WorksetInput(this.currentProject(), new api.WorksetAction_StorePreview(preview))));
      if (generation !== this.generation || this.preview !== preview) return;
      if (!(result instanceof api.Result_WorksetStored)) throw new Error('Unexpected stored workset');
      this.stored = result.workset; this.storedId.value = result.workset.id.value; this.filter.disabled = false;
      this.previewPanel.prepend(element('p', `Stored workset ${result.workset.id.value}`));
    } finally { this.busy = false; this.store.disabled = this.preview === null; }
  }
  private async lookup(): Promise<void> {
    const generation = this.generation; this.invalidate(); const previewGeneration = this.previewGeneration;
    const result = await this.call(new api.Command_Workset(new api.WorksetInput(this.currentProject(), new api.WorksetAction_Lookup(new api.WorksetId(this.storedId.value)))));
    if (generation !== this.generation || previewGeneration !== this.previewGeneration) return;
    if (!(result instanceof api.Result_WorksetStored)) throw new Error('Unexpected stored workset');
    const loading = this.showTarget(new api.WorksetTarget_Stored(result.workset.id), 'Current stored workset evaluation', undefined);
    const loadingGeneration = this.previewGeneration; await loading;
    if (generation !== this.generation || loadingGeneration !== this.previewGeneration) return;
    this.stored = result.workset; this.filter.disabled = false;
  }
}
