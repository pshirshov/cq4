import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { ConnectionManager, ConnectionStats } from './connection.js';
import { button, edit, element, Editor, Json } from './editor.js';
import { QueryEditor } from './query.js';
import { Workspace } from './workspace.js';

const CONTEXT = BaboonCodecContext.Default;
const PAGE_SIZE = 40;
const MAX_QUERY_CHARACTERS = 4096;
const COMPLETION_LIMIT = 30;
const PREFIX: Record<api.Ledger, string> = { Milestones: 'M', Ideas: 'I', Defects: 'D', Goals: 'G', Tasks: 'T', Researches: 'RS',
  Hypothesis: 'H', Questions: 'Q', Decisions: 'K', Reviews: 'R', Handoffs: 'HO', OperatorActions: 'OA', Memories: 'MEM', Upstream: 'U' };
function itemName(id: api.ItemId): string { return PREFIX[id.ledger] + id.number; }
function describe(value: unknown): string { return typeof value === 'string' ? value : JSON.stringify(value, null, 2); }
function readResult(result: api.Result): api.Result {
  if (result instanceof api.Result_Failed) throw new Error(describe(api.Fault_JsonCodec.instance.encode(CONTEXT, result.fault)));
  return result;
}

type Panel = 'detail' | 'history' | 'usage' | 'audit';
interface ResultRow { button: HTMLButtonElement; caption: HTMLSpanElement; status: HTMLSpanElement }

class App {
  private manager: ConnectionManager | null = null;
  private readonly projects = element('select', '');
  private readonly queryEditor = new QueryEditor(async (query, cursor) => {
    const result = await this.call(new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_QueryComplete(query, cursor, COMPLETION_LIMIT))));
    if (!(result instanceof api.Result_QueryAnalyzed)) throw new Error('Unexpected query completion response');
    return result.analysis;
  }, () => this.action(() => this.search()));
  private readonly query = this.queryEditor.input;
  private activeQuery = '';
  private readonly items = element('div', '');
  private readonly rows = new Map<string, ResultRow>();
  private readonly emptyResults = element('p', 'No matching items.');
  private readonly resultStatus = element('p', 'No project selected');
  private readonly usageMetric = element('span', 'Usage: not loaded');
  private readonly usageFreshness = element('span', '');
  private usageObserved = 'No successful observation';
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
  private selection: api.ItemId | null = null;
  private selectionGeneration = 0;
  private readonly requests: Record<Panel, number> = { detail: 0, history: 0, usage: 0, audit: 0 };
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

  private async readPanel(panel: Panel, command: api.Command): Promise<api.Result | null> {
    const request = ++this.requests[panel]; const epoch = this.epoch; const selection = this.selectionGeneration;
    const current = () => request === this.requests[panel] && epoch === this.epoch && selection === this.selectionGeneration;
    try {
      const result = await this.connection().call(command);
      return current() ? readResult(result) : null;
    } catch (error) { if (current()) throw error; return null; }
  }
  private choose(id: api.ItemId | null): void {
    if (this.selection === null ? id === null : id !== null && this.selection.project.value === id.project.value && itemName(this.selection) === itemName(id)) return;
    this.selection = id; this.selectionGeneration++; this.selected = null;
    this.detail.replaceChildren(); this.historyPanel.replaceChildren(); this.usagePanel.replaceChildren(); this.auditPanel.replaceChildren();
    this.auditAfter = 0n; this.markSelection(); this.resetUsage();
  }

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
    const header = element('header', ''); const identity = element('div', ''); identity.className = 'top-identity';
    const health = element('details', ''); const summary = element('summary', '');
    summary.append(this.health, this.deadline); this.deadline.max = 100;
    health.append(summary, this.healthDetails, button('Retry connection', () => this.connection().retry()));
    const projectLabel = element('label', 'Project'); projectLabel.className = 'project-control'; projectLabel.append(this.projects);
    identity.append(element('h1', 'CQ'), projectLabel, health, this.sync);
    const metrics = element('div', ''); metrics.className = 'top-metrics'; metrics.setAttribute('role', 'region'); metrics.setAttribute('aria-label', 'Usage metrics');
    metrics.append(this.usageMetric, this.usageFreshness); header.append(identity, this.queryEditor.element, metrics);
    const workspace = new Workspace(this.root); const side = workspace.navigation; const list = workspace.results; const content = workspace.content;
    this.projects.setAttribute('aria-label', 'Project'); this.query.setAttribute('aria-label', 'Search query');
    this.projects.addEventListener('change', () => this.action(async () => { this.project = new api.ProjectId(this.projects.value); this.reset(); await this.refresh(); }));
    this.query.placeholder = 'ledger:Tasks status:Ready'; this.query.maxLength = MAX_QUERY_CHARACTERS;
    const newProject = element('form', ''); const name = element('input', ''); name.placeholder = 'New project name'; name.setAttribute('aria-label', 'New project name'); name.required = true;
    const add = element('button', 'Create project'); add.type = 'submit'; newProject.append(name, add);
    newProject.addEventListener('submit', event => { event.preventDefault(); this.action(async () => {
      const project = new api.ProjectId(crypto.randomUUID());
      await this.call(new api.Command_Initialize(new api.ProjectConfig(project, location.origin, name.value)));
      this.project = project; this.reset(); await this.loadProjects(); await this.refresh(); name.value = '';
    }); });
    const shortcuts = element('div', ''); shortcuts.className = 'query-shortcuts';
    for (const [label, query] of [['All items', ''], ...api.Ledger_values.map(ledger => [ledger, `ledger:${ledger}`])]) {
      shortcuts.append(button(label, () => this.action(async () => { this.queryEditor.invalidate(); this.query.value = query; await this.search(); })));
    }
    side.append(element('h2', 'Workspace'), button('New item', () => { this.openEditor(null); }),
      button('Project usage', () => this.action(async () => { this.choose(null); await this.loadUsage(); })),
      element('h3', 'Browse'), shortcuts, element('h3', 'Projects'), newProject,
      element('p', 'Ctrl+K: query · F6: next pane · Shift+F6: previous pane. Results: ↑/↓ to move, Enter to select, → for detail, Escape to return.'));
    this.items.tabIndex = -1; this.items.setAttribute('aria-label', 'Result items'); this.items.setAttribute('role', 'group');
    this.items.addEventListener('keydown', event => {
      const rows = Array.from(this.items.querySelectorAll<HTMLButtonElement>('button')); const index = rows.indexOf(document.activeElement as HTMLButtonElement);
      const target = event.key === 'ArrowDown' ? Math.min(rows.length - 1, index + 1) : event.key === 'ArrowUp' ? Math.max(0, index - 1)
        : event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1 : null;
      if (target !== null && rows.length > 0) { event.preventDefault(); rows[target].focus(); }
      else if (event.key === 'ArrowRight') { event.preventDefault(); content.focus(); }
    });
    content.addEventListener('keydown', event => {
      if (event.key === 'Escape') { event.preventDefault(); const row = this.items.querySelector<HTMLButtonElement>('[aria-current=true]'); (row === null ? this.items : row).focus(); }
    });
    this.root.addEventListener('keydown', event => { if (event.ctrlKey && event.key.toLowerCase() === 'k') { event.preventDefault(); this.query.focus(); } });
    const pages = element('div', ''); pages.className = 'actions';
    pages.append(button('First page', () => this.action(async () => { this.after = undefined; this.snapshot = undefined; await this.refresh(); })),
      button('Next page', () => this.action(async () => {
        if (this.page !== null && this.page.hasMore) { this.after = this.page.after; this.snapshot = this.page.cursor; await this.refresh(); }
      })));
    this.resultStatus.setAttribute('role', 'status');
    list.append(element('h2', 'Items'), this.resultStatus, this.items, pages);
    content.append(this.notice, this.detail, this.editorPanel, this.historyPanel, this.usagePanel, this.auditPanel);
    this.root.replaceChildren(header, workspace.element); workspace.fit();
    this.manager = new ConnectionManager(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`, {
      status: stats => this.connectionStatus(stats),
      active: () => this.action(async () => { await this.loadProjects(); this.after = undefined; this.snapshot = undefined; await this.refresh(); }),
      disconnected: () => { this.usageFreshness.textContent = `Stale · ${this.usageObserved}`; this.queryEditor.invalidate(); this.sync.textContent = 'Data: stale'; this.epoch++; this.subscription = null; },
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
  private async search(): Promise<void> {
    this.activeQuery = this.query.value; this.epoch++; this.subscription = null; this.after = undefined; this.snapshot = undefined;
    this.notice.textContent = ''; this.queryEditor.showDiagnostic(undefined, this.query.value); await this.refresh();
  }
  private reset(): void {
    this.queryEditor.invalidate(); this.queryEditor.showDiagnostic(undefined, this.query.value);
    this.epoch++; this.selectionGeneration++; this.selection = null; this.selected = null; this.editor = null; this.after = undefined; this.snapshot = undefined; this.subscription = null;
    this.detail.replaceChildren(); this.editorPanel.replaceChildren(); this.historyPanel.replaceChildren(); this.usagePanel.replaceChildren(); this.auditPanel.replaceChildren();
    this.auditAfter = 0n; this.notice.textContent = ''; this.resetUsage();
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
    this.sync.textContent = 'Data: synchronizing'; this.items.setAttribute('aria-busy', 'true');
    try {
      const input = new api.SearchInput(project, this.activeQuery, this.after, this.snapshot, PAGE_SIZE);
      const response = await this.connection().call(new api.Command_Search(input));
      if (epoch !== this.epoch || this.project.value !== project.value) return;
      if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_Resync) {
        this.after = undefined; this.snapshot = undefined; this.dirty = true; return;
      }
      if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_QuerySyntax) {
        const error = response.fault.diagnostic;
        this.sync.textContent = 'Data: invalid query'; this.queryEditor.showDiagnostic(error, this.activeQuery);
        this.showError(`${error.message} (${error.span.start}–${error.span.end})`); return;
      }
      const result = readResult(response);
      if (!(result instanceof api.Result_Found)) throw new Error('Unexpected item page');
      this.page = result.page;
      this.renderItems(result.page.items);
      this.resultStatus.textContent = `${result.page.items.length} items${result.page.hasMore ? ' · more available' : ''}`;
      const subscription = this.connection().subscribe(project, result.page.cursor); this.subscription = subscription.id.value;
      const replay = readResult(await subscription.result);
      if (epoch !== this.epoch) return;
      if (!(replay instanceof api.Result_Changes)) throw new Error('Unexpected subscription acknowledgement');
      if (replay.page.events.length > 0 || replay.page.hasMore) this.dirty = true;
      else this.sync.textContent = 'Data: current';
      if (this.selection !== null) await this.select(this.selection);
      await this.loadUsage();
    } catch (error) { this.sync.textContent = 'Data: stale'; throw error; }
    finally {
      this.refreshing = false; this.items.setAttribute('aria-busy', 'false');
      if (this.dirty) { this.dirty = false; this.action(() => this.refresh()); }
    }
  }
  private renderItems(items: api.ItemSummary[]): void {
    const focused = this.items.contains(document.activeElement) ? document.activeElement as HTMLElement : null;
    const retained = new Set<string>();
    this.emptyResults.remove();
    for (const [index, item] of items.entries()) {
      const key = `${item.id.project.value}-${itemName(item.id)}`; retained.add(key);
      let row = this.rows.get(key);
      if (row === undefined) {
        const node = button('', () => this.action(() => this.select(item.id))); node.className = 'item-row'; node.dataset.item = key;
        const caption = element('span', ''); const status = element('span', ''); status.className = 'item-status'; status.id = `status-${key}`;
        node.setAttribute('aria-describedby', status.id); node.append(caption, status); row = { button: node, caption, status }; this.rows.set(key, row);
      }
      const caption = `${itemName(item.id)} · ${item.title}${item.archived ? ' · archived' : ''}`;
      row.caption.textContent = caption; row.status.textContent = item.status; row.button.setAttribute('aria-label', caption);
      const before = this.items.children.item(index);
      if (before !== row.button) this.items.insertBefore(row.button, before);
    }
    for (const [key, row] of this.rows) if (!retained.has(key)) { row.button.remove(); this.rows.delete(key); }
    if (items.length === 0) this.items.append(this.emptyResults);
    this.markSelection();
    if (focused !== null && document.activeElement !== focused) (focused.isConnected ? focused : this.items).focus();
  }
  private markSelection(): void {
    const key = this.selection === null ? null : `${this.selection.project.value}-${itemName(this.selection)}`;
    for (const [id, row] of this.rows) row.button.setAttribute('aria-current', String(id === key));
  }
  private usageScope(): string { return this.selection === null ? 'project' : itemName(this.selection); }
  private resetUsage(): void {
    this.usageMetric.textContent = `Usage · ${this.usageScope()}: not loaded`; this.usageObserved = 'No successful observation'; this.usageFreshness.textContent = this.usageObserved;
  }
  private async select(id: api.ItemId): Promise<void> {
    this.choose(id);
    const result = await this.readPanel('detail', new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_ItemDetail(id))));
    if (result === null) return;
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
    await this.loadUsage();
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
    const result = await this.readPanel('history', new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_History(selected.item.id, this.historyBefore, 10))));
    if (result === null) return;
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
  private usageFilter(): api.UsageFilter { return this.selection === null ? new api.UsageFilter_ProjectAll() : new api.UsageFilter_TaskOnly(this.selection); }
  private async loadUsage(): Promise<void> {
    const filter = this.usageFilter(); this.usageFreshness.textContent = `Loading · ${this.usageObserved}`;
    let result: api.Result | null;
    try { result = await this.readPanel('usage', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Summary(filter)))); }
    catch (error) { this.usageFreshness.textContent = `Unavailable · ${this.usageObserved}`; throw error; }
    if (result === null) return;
    if (!(result instanceof api.Result_UsageSummary)) throw new Error('Unexpected usage response');
    this.usageMetric.textContent = `Usage · ${this.usageScope()}: ${result.report.direct.total.known} direct · ${result.report.shared.total.known} shared · ${result.report.unattributed.total.known} unattributed known tokens`;
    const totals = [result.report.direct.total, result.report.shared.total, result.report.unattributed.total];
    this.usageMetric.textContent += ` · ${totals.reduce((sum, value) => sum + value.unknown, 0n)} unknown measurements · ${totals.reduce((sum, value) => sum + value.estimated, 0n)} estimated measurements`;
    this.usageObserved = `Observed ${new Date().toLocaleTimeString()} · cursor ${result.report.cursor}`;
    this.usageFreshness.textContent = this.usageObserved;
    this.usagePanel.replaceChildren(element('h3', `Usage · ${this.selection === null ? 'project' : itemName(this.selection)}`));
    for (const [label, totals] of [['Direct', result.report.direct], ['Shared', result.report.shared], ['Unattributed', result.report.unattributed]] as const) {
      this.usagePanel.append(element('p', `${label}: ${totals.total.known} known tokens; ${totals.total.unknown} unknown measurements; ${totals.total.estimated} estimated measurements`));
      if (totals.unknownCosts > 0n) this.usagePanel.append(element('p', `${totals.unknownCosts} unknown costs`));
    }
    for (const cost of result.report.costs.entries) this.usagePanel.append(this.costRow(cost));
    if (result.report.costs.hasMore) this.usagePanel.append(button('More costs', () => this.action(() => this.loadCosts(result.report.costs.after, result.report.cursor))));
    this.usagePanel.append(element('p', `Shared work is counted once and is not divided among members. Incomplete meters: ${result.report.incompleteMeters}; attempts without measurements: ${result.report.attemptsWithoutMeters}.`),
      element('p', `Attempt coverage: ${result.report.attempts.running} running; ${result.report.attempts.unknown} unknown outcomes; ${result.report.attempts.withGaps} with reported gaps.`),
      button('Refresh usage', () => this.action(() => this.loadUsage())), button('Attempts', () => this.action(() => this.loadAttempts(undefined, undefined))), button('Usage audit', () => this.action(async () => { this.auditAfter = 0n; await this.loadAudit(); })));
  }
  private costRow(cost: api.CostTotal): HTMLElement {
    const group = cost.group;
    return element('p', `${group.attribution}: ${cost.amount.value} ${group.currency} · ${group.basis} · pricing ${group.pricingVersion === undefined ? 'unspecified' : group.pricingVersion} · ${cost.measurements} measurements`);
  }
  private async loadCosts(after: api.CostGroup | undefined, snapshot: bigint | undefined): Promise<void> {
    const result = await this.readPanel('audit', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Costs(this.usageFilter(), after, snapshot, 20))));
    if (result === null) return;
    if (!(result instanceof api.Result_UsageCosts)) throw new Error('Unexpected cost response');
    this.auditPanel.replaceChildren(element('h3', 'Cost breakdown'));
    for (const entry of result.page.entries) this.auditPanel.append(this.costRow(entry));
    if (result.page.hasMore) this.auditPanel.append(button('Next cost page', () => this.action(() => this.loadCosts(result.page.after, result.page.cursor))));
  }
  private async loadAttempts(after: api.AttemptId | undefined, snapshot: bigint | undefined): Promise<void> {
    const result = await this.readPanel('audit', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Attempts(this.usageFilter(), after, snapshot, 20))));
    if (result === null) return;
    if (!(result instanceof api.Result_UsageAttempts)) throw new Error('Unexpected attempt response');
    this.auditPanel.replaceChildren(element('h3', 'Attempts'));
    if (result.page.entries.length === 0) this.auditPanel.append(element('p', 'No attempts in this scope.'));
    for (const entry of result.page.entries) {
      const row = element('section', ''); const outcome = entry.outcome;
      row.append(element('h4', `${entry.attempt.harness} · ${entry.attempt.role} · ${outcome === undefined ? 'Running' : outcome.value.state}`),
        element('p', `Attempt ${entry.attempt.id.value} · ${entry.assignment.attribution}`));
      if (outcome !== undefined) for (const gap of outcome.value.gaps) row.append(element('p', gap));
      const details = element('details', ''); details.append(element('summary', 'Attempt details'), element('pre', describe(api.AttemptView_JsonCodec.instance.encode(CONTEXT, entry))));
      row.append(details, button('Outcome history', () => this.action(() => this.loadOutcomes(entry.attempt.id, 0n)))); this.auditPanel.append(row);
    }
    if (result.page.hasMore) this.auditPanel.append(button('Next attempt page', () => this.action(() => this.loadAttempts(result.page.after, result.page.cursor))));
  }
  private async loadOutcomes(attempt: api.AttemptId, after: bigint): Promise<void> {
    const result = await this.readPanel('audit', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Outcomes(attempt, after, 20))));
    if (result === null) return;
    if (!(result instanceof api.Result_UsageOutcomes)) throw new Error('Unexpected outcome response');
    this.auditPanel.replaceChildren(element('h3', 'Outcome history'));
    if (result.page.entries.length === 0) this.auditPanel.append(element('p', 'No outcome recorded yet.'));
    for (const entry of result.page.entries) {
      const row = element('details', ''); row.append(element('summary', `${entry.sequence} · ${entry.value.state}`),
        element('pre', describe(api.RecordedOutcome_JsonCodec.instance.encode(CONTEXT, entry))));
      this.auditPanel.append(row);
    }
    if (result.page.hasMore) this.auditPanel.append(button('Next outcome page', () => this.action(() => this.loadOutcomes(attempt, result.page.after))));
  }
  private async loadAudit(): Promise<void> {
    const result = await this.readPanel('audit', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Audit(this.usageFilter(), this.auditAfter, 20))));
    if (result === null) return;
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
