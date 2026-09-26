import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { ConnectionManager, ConnectionStats } from './connection.js';
import { button, edit, element, Editor, Json } from './editor.js';

const CONTEXT = BaboonCodecContext.Default;
const PAGE_SIZE = 40;
const PREFIX: Record<api.Ledger, string> = { Milestones: 'M', Ideas: 'I', Defects: 'D', Goals: 'G', Tasks: 'T', Researches: 'RS',
  Hypothesis: 'H', Questions: 'Q', Decisions: 'K', Reviews: 'R', Handoffs: 'HO', OperatorActions: 'OA', Memories: 'MEM', Upstream: 'U' };
function itemName(id: api.ItemId): string { return PREFIX[id.ledger] + id.number; }
function describe(value: unknown): string { return typeof value === 'string' ? value : JSON.stringify(value, null, 2); }
function readResult(result: api.Result): api.Result {
  if (result instanceof api.Result_Failed) throw new Error(describe(api.Fault_JsonCodec.instance.encode(CONTEXT, result.fault)));
  return result;
}

class App {
  private manager: ConnectionManager | null = null;
  private readonly projects = element('select', '');
  private readonly ledger = element('select', '');
  private readonly archived = element('select', '');
  private readonly items = element('div', '');
  private readonly detail = element('section', '');
  private readonly editorPanel = element('section', '');
  private readonly historyPanel = element('section', '');
  private readonly usagePanel = element('section', '');
  private readonly auditPanel = element('section', '');
  private readonly notice = element('p', '');
  private readonly sync = element('span', 'No project selected');
  private readonly health = element('span', 'Connecting');
  private readonly healthDetails = element('pre', '');
  private readonly deadline = element('progress', '');
  private project: api.ProjectId | null = null;
  private selected: api.ItemView | null = null;
  private after: api.ItemId | undefined;
  private snapshot: api.ChangeCursor | undefined;
  private page: api.ItemPage | null = null;
  private subscription: string | null = null;
  private epoch = 0;
  private refreshing = false;
  private dirty = false;
  private historyBefore = new api.Revision(9223372036854775807n);
  private auditAfter = 0n;
  private editor: { form: Editor; record: api.BrowserDraft; key: string; discard: HTMLButtonElement; busy: boolean } | null = null;

  constructor(private readonly root: HTMLElement) { void this.start(); }
  private showError(error: unknown): void { this.notice.textContent = String(error); this.notice.setAttribute('role', 'alert'); }
  private action(effect: () => Promise<void>): void { void effect().catch(error => this.showError(error)); }
  private connection(): ConnectionManager {
    if (this.manager === null) throw new Error('Connection not initialized'); return this.manager;
  }
  private currentProject(): api.ProjectId { if (this.project === null) throw new Error('Select a project'); return this.project; }
  private async call(command: api.Command): Promise<api.Result> { return readResult(await this.connection().call(command)); }

  private async start(): Promise<void> {
    try {
      const response = await fetch('/api/hello');
      if (response.status === 401) { this.login(); return; }
      if (!response.ok) throw new Error(`Server returned ${response.status}`);
      const hello = api.ProtocolHello_JsonCodec.instance.decode(CONTEXT, await response.json());
      if (hello.version !== api.Command.BaboonDomainVersion) throw new Error('Client/server version mismatch; reload the matching client build');
      this.mount();
    } catch (error) { this.root.replaceChildren(element('h1', 'CQ'), this.notice, button('Retry', () => this.action(() => this.start()))); this.showError(error); }
  }
  private login(): void {
    const form = element('form', ''); form.className = 'login';
    const label = element('label', 'Operator token');
    const token = element('input', ''); token.type = 'password'; token.required = true; token.autocomplete = 'off'; label.append(token);
    const submit = element('button', 'Sign in'); submit.type = 'submit';
    form.append(element('h1', 'CQ'), element('p', 'Connect to your project workspace.'), label, submit, this.notice);
    form.addEventListener('submit', event => { event.preventDefault(); this.action(async () => {
      let session = localStorage.getItem('cq-browser-session');
      if (session === null) { session = crypto.randomUUID(); localStorage.setItem('cq-browser-session', session); }
      const response = await fetch('/api/login', { method: 'POST', headers: { Authorization: `Bearer ${token.value}`, 'CQ-Session': session } });
      if (!response.ok) throw new Error(`Sign in failed (${response.status})`);
      token.value = ''; this.notice.textContent = ''; await this.start();
    }); });
    this.root.replaceChildren(form);
  }
  private mount(): void {
    const header = element('header', '');
    const health = element('details', ''); const summary = element('summary', '');
    summary.append(this.health, this.deadline); this.deadline.max = 100;
    health.append(summary, this.healthDetails, button('Retry connection', () => this.connection().retry()));
    header.append(element('h1', 'CQ'), health, this.sync);
    const main = element('main', ''); const side = element('nav', ''); const list = element('section', ''); const content = element('article', '');
    this.projects.setAttribute('aria-label', 'Project'); this.ledger.setAttribute('aria-label', 'Ledger'); this.archived.setAttribute('aria-label', 'Archive filter');
    this.projects.addEventListener('change', () => this.action(async () => { this.project = new api.ProjectId(this.projects.value); this.reset(); await this.refresh(); }));
    this.ledger.append(element('option', 'All ledgers')); this.ledger.options[0].value = '';
    for (const ledger of Object.values(api.Ledger)) { const option = element('option', ledger); option.value = ledger; this.ledger.append(option); }
    for (const value of Object.values(api.ArchiveFilter)) { const option = element('option', value); option.value = value; this.archived.append(option); }
    this.archived.value = api.ArchiveFilter.Active;
    for (const control of [this.ledger, this.archived]) control.addEventListener('change', () => this.action(async () => { this.after = undefined; this.snapshot = undefined; await this.refresh(); }));
    const newProject = element('form', ''); const name = element('input', ''); name.placeholder = 'New project name'; name.setAttribute('aria-label', 'New project name'); name.required = true;
    const add = element('button', 'Create project'); add.type = 'submit'; newProject.append(name, add);
    newProject.addEventListener('submit', event => { event.preventDefault(); this.action(async () => {
      const project = new api.ProjectId(crypto.randomUUID());
      await this.call(new api.Command_Initialize(new api.ProjectConfig(project, location.origin, name.value)));
      this.project = project; this.reset(); await this.loadProjects(); await this.refresh(); name.value = '';
    }); });
    side.append(element('h2', 'Workspace'), this.projects, newProject, this.ledger, this.archived,
      button('New item', () => { this.openEditor(null); }), button('Project usage', () => this.action(async () => { this.selected = null; this.auditAfter = 0n; await this.loadUsage(); })));
    const pages = element('div', ''); pages.className = 'actions';
    pages.append(button('First page', () => this.action(async () => { this.after = undefined; this.snapshot = undefined; await this.refresh(); })),
      button('Next page', () => this.action(async () => {
        if (this.page !== null && this.page.hasMore) { this.after = this.page.after; this.snapshot = this.page.cursor; await this.refresh(); }
      })));
    list.append(element('h2', 'Items'), this.items, pages);
    content.append(this.notice, this.detail, this.editorPanel, this.historyPanel, this.usagePanel, this.auditPanel);
    main.append(side, list, content); this.root.replaceChildren(header, main);
    this.manager = new ConnectionManager(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`, {
      status: stats => this.connectionStatus(stats),
      active: () => this.action(async () => { await this.loadProjects(); this.after = undefined; this.snapshot = undefined; await this.refresh(); }),
      disconnected: () => { this.sync.textContent = 'Data: stale'; this.epoch++; this.subscription = null; },
      event: frame => {
        if (frame instanceof api.ServerFrame_Changes && frame.subscription.value === this.subscription) {
          if (frame.page.events.length > 0) { this.after = undefined; this.snapshot = undefined; this.action(() => this.refresh()); }
          else if (!this.refreshing) this.sync.textContent = 'Data: current';
        } else if (frame instanceof api.ServerFrame_Resync && frame.subscription.value === this.subscription) {
          this.sync.textContent = 'Data: resynchronizing'; this.after = undefined; this.snapshot = undefined; this.action(() => this.refresh());
        }
      },
    });
  }
  private connectionStatus(stats: ConnectionStats): void {
    this.health.textContent = `Connection: ${stats.state}`; this.health.dataset.state = stats.state;
    this.health.setAttribute('aria-label', `Connection ${stats.state}`);
    this.deadline.value = stats.deadline === null ? 100 : Math.max(0, Math.min(100, (stats.deadline - Date.now()) / 100));
    this.healthDetails.textContent = `Connections: ${stats.connections}; active: ${stats.active}\nRTT: ${stats.rtt === null ? 'unknown' : stats.rtt + ' ms'}\nRetry: ${stats.attempts}/12\n${stats.reason}\n\n${stats.events.join('\n')}`;
    document.title = `CQ — ${stats.state}`;
  }
  private reset(): void {
    this.epoch++; this.selected = null; this.editor = null; this.after = undefined; this.snapshot = undefined; this.subscription = null;
    this.detail.replaceChildren(); this.editorPanel.replaceChildren(); this.historyPanel.replaceChildren(); this.auditPanel.replaceChildren();
    this.auditAfter = 0n; this.notice.textContent = '';
  }
  private async loadProjects(): Promise<void> {
    const projects: api.Project[] = [];
    let after: api.ProjectId | undefined;
    do {
      const result = await this.call(new api.Command_Projects(after, 200));
      if (!(result instanceof api.Result_Projects)) throw new Error('Unexpected project response');
      projects.push(...result.page.projects); after = result.page.hasMore ? result.page.after : undefined;
    } while (after !== undefined && projects.length < 1000);
    this.projects.replaceChildren();
    for (const project of projects) { const option = element('option', project.name); option.value = project.id.value; this.projects.append(option); }
    if (this.project === null && projects.length > 0) this.project = projects[0].id;
    if (this.project !== null) this.projects.value = this.project.value;
    if (after !== undefined) this.showError('Project selector reached 1,000 entries; use CLI for additional projects');
  }
  private async refresh(): Promise<void> {
    if (this.project === null) return;
    if (this.refreshing) { this.dirty = true; return; }
    this.refreshing = true; this.dirty = false;
    const project = this.project; const epoch = this.epoch;
    this.sync.textContent = 'Data: synchronizing';
    try {
      const input = new api.SearchInput(project, new api.ItemFilter(this.ledger.value === '' ? undefined : this.ledger.value as api.Ledger,
        this.archived.value as api.ArchiveFilter), this.after, this.snapshot, PAGE_SIZE);
      const response = await this.connection().call(new api.Command_Search(input));
      if (epoch !== this.epoch || this.project.value !== project.value) return;
      if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_Resync) {
        this.after = undefined; this.snapshot = undefined; this.dirty = true; return;
      }
      const result = readResult(response);
      if (!(result instanceof api.Result_Found)) throw new Error('Unexpected item page');
      this.page = result.page;
      this.items.replaceChildren();
      if (result.page.items.length === 0) this.items.append(element('p', 'No matching items.'));
      for (const item of result.page.items) {
        const row = button(`${itemName(item.id)} · ${item.title}${item.archived ? ' · archived' : ''}`, () => this.action(() => this.select(item.id)));
        row.className = 'item-row'; this.items.append(row);
      }
      const subscription = this.connection().subscribe(project, result.page.cursor); this.subscription = subscription.id.value;
      const replay = readResult(await subscription.result);
      if (epoch !== this.epoch) return;
      if (!(replay instanceof api.Result_Changes)) throw new Error('Unexpected subscription acknowledgement');
      if (replay.page.events.length > 0 || replay.page.hasMore) this.dirty = true;
      else this.sync.textContent = 'Data: current';
      if (this.selected !== null) await this.select(this.selected.item.id);
      await this.loadUsage();
    } catch (error) { this.sync.textContent = 'Data: stale'; throw error; }
    finally {
      this.refreshing = false;
      if (this.dirty) { this.dirty = false; this.action(() => this.refresh()); }
    }
  }
  private async select(id: api.ItemId): Promise<void> {
    const epoch = this.epoch;
    const result = await this.call(new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_ItemDetail(id))));
    if (epoch !== this.epoch) return;
    if (!(result instanceof api.Result_Detail)) throw new Error('Unexpected item response');
    this.selected = result.view;
    const item = result.view.item;
    this.detail.replaceChildren(element('h2', `${itemName(item.id)} · ${item.draft.title}`), element('p', `Revision ${item.revision.value} · ${item.provenance.actor.subject} · ${new Date(Number(item.updatedAt)).toLocaleString()}`),
      element('pre', item.draft.body), button('Edit current revision', () => this.openEditor(result.view)),
      button('History', () => this.action(async () => { this.historyBefore = new api.Revision(9223372036854775807n); await this.loadHistory(); })));
    const fields = element('dl', '');
    const encoded = api.Content_JsonCodec.instance.encode(CONTEXT, item.draft.content) as Record<string, Record<string, unknown>>;
    for (const [kind, values] of Object.entries(encoded)) {
      fields.append(element('dt', 'Ledger'), element('dd', kind));
      for (const [key, value] of Object.entries(values)) if (value !== null) fields.append(element('dt', key), element('dd', describe(value)));
    }
    this.detail.append(fields, element('p', result.view.refs.map(ref => `${ref.relation} ${itemName(ref.target)}`).join(' · ')));
    this.auditAfter = 0n; this.auditPanel.replaceChildren(); await this.loadUsage();
  }
  private storeDraft(editor: NonNullable<App['editor']>): void {
    localStorage.setItem(editor.key, JSON.stringify(api.BrowserDraft_JsonCodec.instance.encode(CONTEXT, editor.record)));
  }
  private lockDraft(editor: NonNullable<App['editor']>): void {
    const pending = editor.record.pending !== undefined;
    for (const input of editor.form.element.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | HTMLButtonElement>('input,select,textarea,button')) input.disabled = pending;
    editor.discard.disabled = pending;
  }
  private openEditor(base: api.ItemView | null): void {
    const project = this.currentProject();
    const key = `cq-draft:${project.value}:${base === null ? 'new' : itemName(base.item.id)}`;
    const initial = base === null ? api.ItemDraft_JsonCodec.instance.decode(CONTEXT, { title: '', body: '', labels: [], archived: false,
      content: { Task: { status: 'Ready', acceptance: [''], result: null, validation: [] } }, citations: [] }) : base.item.draft;
    let record = new api.BrowserDraft(project, base === null ? undefined : new api.ItemRevision(base.item.id, base.item.revision), initial, undefined);
    const saved = localStorage.getItem(key);
    if (saved !== null) {
      record = api.BrowserDraft_JsonCodec.instance.decode(CONTEXT, JSON.parse(saved));
      if (record.project.value !== project.value || (base === null ? record.item !== undefined : record.item === undefined || itemName(record.item.id) !== itemName(base.item.id)))
        throw new Error('Stored draft identity differs from the selected item');
      this.notice.textContent = record.pending === undefined ? 'Restored your local draft with its original base revision.' : 'A previous save is unresolved. Save item retries that exact request before further editing.';
    }
    const caption = record.item === undefined ? 'New item' : `Edit ${itemName(record.item.id)} from revision ${record.item.revision.value}`;
    const form = edit('ItemDraft', api.ItemDraft_JsonCodec.instance.encode(CONTEXT, record.value) as Json, caption);
    const discard = button('Discard local draft', () => {
      if (this.editor !== editor || editor.record.pending !== undefined) return;
      localStorage.removeItem(key); this.editor = null; this.editorPanel.replaceChildren();
    });
    const editor = { form, record, key, discard, busy: false };
    this.editor = editor;
    for (const event of ['input', 'change', 'click']) form.element.addEventListener(event, () => {
      if (editor.record.pending !== undefined) return;
      try {
        editor.record = new api.BrowserDraft(project, editor.record.item, api.ItemDraft_JsonCodec.instance.decode(CONTEXT, form.read()), undefined);
        this.storeDraft(editor);
      } catch (error) { this.showError(`Draft storage failed: ${String(error)}`); }
    });
    this.lockDraft(editor);
    this.editorPanel.replaceChildren(form.element, button('Save item', () => this.action(() => this.save())), discard);
  }
  private async save(): Promise<void> {
    const editor = this.editor; if (editor === null || editor.busy) return;
    if (editor.record.pending === undefined) {
      const draft = api.ItemDraft_JsonCodec.instance.decode(CONTEXT, editor.form.read());
      const base = editor.record.item;
      const mutation = base === undefined ? new api.Mutation_Create(draft) : new api.Mutation_Replace(base.id, base.revision, draft);
      const pending = new api.ChangeRequest(new api.RequestId(crypto.randomUUID()), [mutation], [], 'Browser edit');
      editor.record = new api.BrowserDraft(editor.record.project, base, draft, pending);
    }
    this.storeDraft(editor);
    this.lockDraft(editor);
    const submitted = localStorage.getItem(editor.key);
    const pending = editor.record.pending;
    if (pending === undefined) throw new Error('Missing prepared draft request');
    editor.busy = true;
    try {
      const result = await this.connection().call(new api.Command_Change(new api.ChangeInput(editor.record.project, pending)));
      if (result instanceof api.Result_Failed) {
        editor.record = new api.BrowserDraft(editor.record.project, editor.record.item, editor.record.value, undefined);
        this.storeDraft(editor); this.lockDraft(editor); readResult(result);
      }
      if (!(result instanceof api.Result_Changed)) throw new Error('Unexpected change acknowledgement');
      if (localStorage.getItem(editor.key) === submitted) localStorage.removeItem(editor.key);
      if (this.editor !== editor) return;
      this.editor = null; this.editorPanel.replaceChildren(); this.notice.textContent = 'Saved';
      this.after = undefined; this.snapshot = undefined; await this.refresh(); await this.select(result.ack.items[0].id);
    } finally { editor.busy = false; }
  }
  private async loadHistory(): Promise<void> {
    const selected = this.selected; if (selected === null) return;
    const result = await this.call(new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_History(selected.item.id, this.historyBefore, 10))));
    if (!(result instanceof api.Result_History)) throw new Error('Unexpected history response');
    this.historyPanel.replaceChildren(element('h3', `History · ${itemName(selected.item.id)}`));
    for (const entry of result.page.entries) {
      const item = entry.item.item; const row = element('details', '');
      row.append(element('summary', `Revision ${item.revision.value} · ${entry.reason}`), element('pre', describe(api.ItemView_JsonCodec.instance.encode(CONTEXT, entry.item))));
      this.historyPanel.append(row);
    }
    if (result.page.hasMore) this.historyPanel.append(button('Older history', () => this.action(async () => {
      this.historyBefore = result.page.entries[result.page.entries.length - 1].item.item.revision; await this.loadHistory();
    })));
  }
  private usageFilter(): api.UsageFilter { return this.selected === null ? new api.UsageFilter_ProjectAll() : new api.UsageFilter_TaskOnly(this.selected.item.id); }
  private async loadUsage(): Promise<void> {
    const epoch = this.epoch; const filter = this.usageFilter();
    const result = await this.call(new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Summary(filter))));
    if (epoch !== this.epoch) return;
    if (!(result instanceof api.Result_UsageSummary)) throw new Error('Unexpected usage response');
    this.usagePanel.replaceChildren(element('h3', `Usage · ${this.selected === null ? 'project' : itemName(this.selected.item.id)}`));
    for (const [label, totals] of [['Direct', result.report.direct], ['Shared', result.report.shared], ['Unattributed', result.report.unattributed]] as const) {
      this.usagePanel.append(element('p', `${label}: ${totals.total.known} known tokens; ${totals.total.unknown} unknown measurements; ${totals.total.estimated} estimated measurements`));
      for (const cost of totals.costs) this.usagePanel.append(element('p', `${cost.amount.value} ${cost.currency} · ${cost.basis} · pricing ${cost.pricingVersion === undefined ? 'unspecified' : cost.pricingVersion}`));
      if (totals.unknownCosts > 0n) this.usagePanel.append(element('p', `${totals.unknownCosts} unknown costs`));
    }
    this.usagePanel.append(element('p', `Shared work is counted once and is not divided among members. Incomplete meters: ${result.report.incompleteMeters}; attempts without measurements: ${result.report.attemptsWithoutMeters}.`),
      button('Refresh usage', () => this.action(() => this.loadUsage())), button('Usage audit', () => this.action(async () => { this.auditAfter = 0n; await this.loadAudit(); })));
  }
  private async loadAudit(): Promise<void> {
    const result = await this.call(new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Audit(this.usageFilter(), this.auditAfter, 20))));
    if (!(result instanceof api.Result_UsageAudit)) throw new Error('Unexpected audit response');
    this.auditPanel.replaceChildren(element('h3', 'Usage audit'));
    if (result.page.entries.length === 0) this.auditPanel.append(element('p', 'No usage observations in this scope.'));
    for (const entry of result.page.entries) {
      const row = element('details', ''); row.append(element('summary', `${entry.sequence} · ${entry.upload.observation.source} · ${entry.upload.observation.completeness}`),
        element('pre', describe(api.RecordedUsage_JsonCodec.instance.encode(CONTEXT, entry)))); this.auditPanel.append(row);
    }
    if (result.page.hasMore) this.auditPanel.append(button('Next audit page', () => this.action(async () => { this.auditAfter = result.page.after; await this.loadAudit(); })));
  }
}

const root = document.getElementById('app');
if (root === null) throw new Error('Missing application root');
new App(root);
