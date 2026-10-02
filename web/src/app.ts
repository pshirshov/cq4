import { uuidV4 } from './uuid.js';
import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { ConnectionManager } from './connection.js';
import { ConnectionIndicator } from './connection-indicator.js';
import { button, editItem, element, ItemEditor, Json } from './editor.js';
import { QueryEditor } from './query.js';
import { Workspace } from './workspace.js';
import { GraphActions } from './graph.js';
import { itemName } from './items.js';
import { itemView } from './presentation.js';
import { Dialog } from './dialog.js';
import { holdButton } from './hold-button.js';
import { icon } from './icons.js';
import { ArchiveDialog } from './archive.js';
import { faultMessage } from './faults.js';
import { attemptsTable, outcomesTable, auditTable, phasesTable, sharedAssignmentsList } from './usage-view.js';
import { formatAmount, MoneyDigits } from './money.js';
import { TableColumn, TableColumns } from './table-columns.js';
import { ItemsView } from './items-view.js';
import { Notifications } from './notifications.js';
import { ReferencePopup } from './references.js';
import { QuestionBatch } from './questions.js';

const CONTEXT = BaboonCodecContext.Default;
const PAGE_SIZE = 40;
const LOAD_MORE_DISTANCE = 120;
const MAX_QUERY_CHARACTERS = 4096;
const COMPLETION_LIMIT = 30;
// The ID column carries the item type icon, so the separate Type column is not shown.
const SORT_COLUMNS = api.ItemOrderField_values.filter(field => field !== api.ItemOrderField.Type);
const ITEM_COLUMNS = SORT_COLUMNS.length + 1;
function readResult(result: api.Result): api.Result {
  if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
  return result;
}

type Panel = 'detail' | 'history' | 'usage' | 'audit';
type UsageScope = api.UsageFilter_ProjectAll | api.UsageFilter_TaskOnly | api.UsageFilter_CohortOnly | api.UsageFilter_SessionOnly;
interface UsageLoad { dirty: boolean }
type AuditView = api.UsageSelection_Costs | api.UsageSelection_Attempts | api.UsageSelection_Outcomes | api.UsageSelection_Audit;
interface ResultRow { element: HTMLTableRowElement; button: HTMLButtonElement; status: HTMLTableCellElement; severity: HTMLTableCellElement; milestone: HTMLTableCellElement; modified: HTMLTimeElement }

class App {
  private readonly notifications = new Notifications();
  private readonly references = new ReferencePopup(command => this.connection().call(command));
  private readonly questions = new QuestionBatch({
    call: command => this.connection().call(command), view: item => this.itemDocument(item),
    committed: async (project, ack) => {
      this.notifications.show(`Answer saved: ${ack.items.map(item => itemName(item.id)).join(', ')}.`, 'success');
      if (this.project !== null && this.project.value === project.value) await this.refresh();
    },
  }, localStorage);
  private mounted = false;
  private tableColumns: TableColumns | null = null;
  private manager: ConnectionManager | null = null;
  private readonly projects = element('select', '');
  private readonly queryEditor = new QueryEditor(async (query, cursor) => {
    const result = await this.call(new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_QueryComplete(query, cursor, COMPLETION_LIMIT))));
    if (!(result instanceof api.Result_QueryAnalyzed)) throw new Error('Unexpected query completion response');
    return result.analysis;
  }, () => this.action(() => this.search()),
    query => this.project !== null && (this.queryInvalid || query.trim() !== this.activeQuery.trim()));
  private readonly query = this.queryEditor.input;
  private activeQuery = '';
  private readonly items = element('tbody', '');
  private readonly sortHeaders = new Map<api.ItemOrderField, HTMLTableCellElement>();
  private readonly itemsView = new ItemsView(localStorage, message => this.showError(message));
  private order = this.itemsView.load();
  private readonly navigationCounts = new Map<api.Ledger | 'All', HTMLSpanElement>();
  private countsLoad: UsageLoad | null = null;
  private countsSnapshot: bigint | null = null;
  private readonly rows = new Map<string, ResultRow>();
  private readonly groupRows = new Map<string, HTMLTableRowElement>();
  private loadedItems: api.BrowseItem[] = [];
  private resultsPane: HTMLElement | null = null;
  private workspace: Workspace | null = null;
  private readonly emptyResults = element('tr', '');
  private readonly resultStatus = this.queryEditor.resultCount;
  private readonly usageMetric = element('span', 'Usage: not loaded');
  private readonly usageFreshness = element('span', '');
  private usageObserved = 'No successful observation';
  private usageSelection: UsageScope = new api.UsageFilter_ProjectAll();
  private liveSubscription: string | null = null;
  private liveGeneration = 0;
  private catalogueLoad: UsageLoad | null = null;
  private catalogueCursor: bigint | null = null;
  private catalogueSnapshot: bigint | null = null;
  private itemCursor: bigint | null = null;
  private queryInvalid = false;
  private usageCursor: bigint | null = null;
  private usageSnapshot: bigint | null = null;
  private updatesRejected = false;
  private usageLoad: UsageLoad | null = null;
  private readonly detail = element('section', '');
  private readonly editorPanel = element('section', '');
  private readonly conflictPanel = element('section', '');
  private readonly graph = new GraphActions({
    call: command => this.connection().call(command), select: id => this.select(id), error: error => this.showError(error),
    view: item => this.itemDocument(item),
    committed: async (project, ack) => {
      this.notifications.show(`Graph change saved in project ${project.value}: ${ack.items.length === 0 ? 'no revision changes' : ack.items.map(item => `${itemName(item.id)} @ ${item.revision.value}`).join('; ')}.`, 'success');
      if (this.project !== null && this.project.value === project.value) { this.after = undefined; this.snapshot = undefined; await this.refresh(); }
    },
  });
  private readonly archive = new ArchiveDialog({
    call: command => this.connection().call(command),
    committed: async (project, acknowledgement) => {
      if (this.project === null || this.project.value !== project.value) return;
      this.notifications.show(`Archived ${acknowledgement.items.length} items.`, 'success');
      this.after = undefined; this.snapshot = undefined; await this.refresh();
    },
  }, localStorage);
  private readonly historyPanel = element('section', '');
  private readonly projectDialog = new Dialog('standard', () => {});
  private readonly createDialog = new Dialog('large', () => this.closeEditor());
  private readonly conflictDialog = new Dialog('standard', () => {});
  private readonly historyDialog = new Dialog('standard', () => { this.requests.history++; });
  private readonly usageDialog = new Dialog('large', () => {
    const pane = document.getElementById('detail-pane');
    if (pane !== null) pane.append(this.usagePanel, this.auditPanel);
    this.action(() => this.selectUsage(this.selection === null ? new api.UsageFilter_ProjectAll() : new api.UsageFilter_TaskOnly(this.selection), false));
  });
  private readonly usagePanel = element('section', '');
  private readonly auditPanel = element('section', '');
  private readonly auditFreshness = element('p', '');
  private auditCursor: bigint | null = null;
  private readonly notice = element('p', '');
  private readonly sync = element('span', 'No project selected');
  private readonly health = new ConnectionIndicator(() => this.connection().retry());
  private project: api.ProjectId | null = null;
  private selected: api.ItemView | null = null;
  private selection: api.ItemId | null = null;
  private selectionGeneration = 0;
  private readonly requests: Record<Panel, number> = { detail: 0, history: 0, usage: 0, audit: 0 };
  private after: api.ItemId | undefined;
  private snapshot: api.ChangeCursor | undefined;
  private page: api.BrowsePage | null = null;
  private epoch = 0;
  private refreshing = false;
  private dirty = false;
  private historyBefore = new api.Revision(9223372036854775807n);
  private auditView: AuditView | null = null;
  private auditLoad: UsageLoad | null = null;
  private editor: { form: ItemEditor; record: api.BrowserDraft; key: string; discard: HTMLButtonElement; quick: HTMLButtonElement[]; busy: boolean; next: boolean } | null = null;

  constructor(private readonly root: HTMLElement) { void this.start(); }
  private showError(error: unknown): void {
    const dialog = [this.conflictDialog, this.graph.dialog, this.projectDialog, this.createDialog, this.historyDialog, this.usageDialog].find(dialog => dialog.element.open);
    if (dialog !== undefined) { dialog.error.textContent = String(error); dialog.error.hidden = false; }
    else if (this.mounted) this.notifications.show(String(error), 'error');
    else { this.notice.textContent = String(error); this.notice.setAttribute('role', 'alert'); }
  }
  private action(effect: () => Promise<void>): void { void effect().catch(error => this.showError(error)); }
  private connection(): ConnectionManager {
    if (this.manager === null) throw new Error('Connection not initialized'); return this.manager;
  }
  private currentProject(): api.ProjectId { if (this.project === null) throw new Error('Select a project'); return this.project; }
  private async call(command: api.Command): Promise<api.Result> { return readResult(await this.connection().call(command)); }

  private async readPanel(panel: Panel, command: api.Command): Promise<api.Result | null> {
    const request = ++this.requests[panel]; const epoch = this.epoch; const selection = this.selectionGeneration; const project = this.project;
    const current = () => request === this.requests[panel] && project === this.project && selection === this.selectionGeneration &&
      (panel === 'usage' || panel === 'audit' || epoch === this.epoch);
    try {
      const result = await this.connection().call(command);
      return current() ? readResult(result) : null;
    } catch (error) { if (current()) throw error; return null; }
  }
  private choose(id: api.ItemId | null): void {
    if (this.selection === null ? id === null : id !== null && this.selection.project.value === id.project.value && itemName(this.selection) === itemName(id)) return;
    this.historyDialog.close(); this.usageDialog.close(); this.closeEditor();
    this.selection = id; this.selectionGeneration++; this.selected = null;
    this.graph.setScope(this.project, null);
    this.detail.replaceChildren(); this.historyPanel.replaceChildren(); this.usagePanel.replaceChildren(); this.auditPanel.replaceChildren();
    this.markSelection(); this.setUsageScope(id === null ? new api.UsageFilter_ProjectAll() : new api.UsageFilter_TaskOnly(id));
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
      if (session === null) { session = uuidV4(crypto); localStorage.setItem('cq-browser-session', session); }
      const response = await fetch('/api/login', { method: 'POST', headers: { Authorization: `Bearer ${token.value}`, 'CQ-Session': session } });
      if (!response.ok) throw new Error(`Sign in failed (${response.status})`);
      token.value = ''; this.notice.textContent = ''; await this.start();
    }); });
    this.root.replaceChildren(form);
  }
  private mount(): void {
    this.mounted = true;
    const header = element('header', ''); const identity = element('div', ''); identity.className = 'top-identity';
    const projectControl = element('div', ''); projectControl.className = 'project-control';
    const projectLabel = element('label', 'Project'); projectLabel.append(this.projects); projectControl.append(projectLabel);
    const createProject = button('+', () => { this.projectDialog.open('New project'); }); createProject.setAttribute('aria-label', 'New project'); createProject.title = 'New project';
    projectControl.append(createProject); identity.append(element('h1', 'CQ'), projectControl, this.health.element);
    const metrics = element('div', ''); metrics.className = 'status-metrics'; metrics.setAttribute('role', 'region'); metrics.setAttribute('aria-label', 'Usage metrics');
    metrics.append(this.sync, this.usageMetric, this.usageFreshness); header.append(identity, this.queryEditor.element);
    const status = element('footer', ''); status.className = 'status-bar'; status.setAttribute('aria-label', 'Workspace status'); status.append(metrics);
    const guidance = element('div', ''); guidance.className = 'keyboard-guidance'; guidance.setAttribute('role', 'group'); guidance.setAttribute('aria-label', 'Keyboard shortcuts');
    for (const [keys, spoken, action] of [['Ctrl+K', 'Ctrl+K', 'query'], ['F6', 'F6', 'next pane'], ['Shift+F6', 'Shift+F6', 'previous pane'],
      ['↑/↓', 'Up or Down arrow', 'move in results'], ['↵', 'Enter', 'select'], ['→', 'Right arrow', 'detail'], ['Esc', 'Escape', 'return; again closes item']] as const) {
      // Generic elements cannot carry an accessible name, so the key name is hidden visually rather than labelled.
      const hint = element('span', ''); const key = element('kbd', ''); const glyph = element('span', keys);
      glyph.setAttribute('aria-hidden', 'true'); const name = element('span', spoken); name.className = 'visually-hidden';
      key.append(glyph, name); hint.append(key, ` ${action}`); guidance.append(hint);
    }
    status.append(guidance);
    this.usageMetric.className = 'status-usage'; this.usageFreshness.className = 'status-freshness';
    const workspace = new Workspace(this.root, localStorage, error => this.showError(error)); const side = workspace.navigation; const list = workspace.results; const content = workspace.content;
    this.resultsPane = list; this.workspace = workspace;
    list.addEventListener('scroll', () => this.loadMore());
    window.addEventListener('resize', () => this.loadMore());
    this.projects.setAttribute('aria-label', 'Project'); this.query.setAttribute('aria-label', 'Search query');
    this.projects.addEventListener('change', () => this.action(async () => { this.project = new api.ProjectId(this.projects.value); this.reset(); await this.refresh(); }));
    this.query.addEventListener('input', () => this.archive.invalidate());
    this.query.placeholder = 'ledger:Tasks status:Ready'; this.query.maxLength = MAX_QUERY_CHARACTERS;
    const newProject = element('form', ''); const name = element('input', ''); name.placeholder = 'New project name'; name.setAttribute('aria-label', 'New project name'); name.required = true;
    const add = element('button', 'Create project'); add.type = 'submit'; newProject.append(name, add);
    newProject.addEventListener('submit', event => { event.preventDefault(); this.action(async () => {
      const project = new api.ProjectId(uuidV4(crypto));
      await this.call(new api.Command_Initialize(new api.ProjectConfig(project, location.origin, name.value)));
      this.project = project; this.reset(); await this.loadProjects(); await this.refresh(); name.value = ''; this.projectDialog.close();
    }); });
    this.projectDialog.body.append(newProject); this.historyDialog.body.append(this.historyPanel);
    this.conflictDialog.body.append(this.conflictPanel);
    this.createDialog.element.addEventListener('keydown', event => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && !event.isComposing) {
        event.preventDefault(); this.action(() => this.save(true));
      }
    });
    const shortcuts = element('div', ''); shortcuts.className = 'query-shortcuts';
    for (const ledger of ['All' as const, ...api.Ledger_values]) {
      const label = ledger === 'All' ? 'All items' : ledger;
      const query = ledger === 'All' ? '' : `ledger:${ledger}`;
      const entry = button('', () => this.action(async () => { this.queryEditor.invalidate(); this.query.value = query; await this.search(); }));
      entry.className = 'navigation-entry'; entry.setAttribute('aria-label', label);
      entry.title = 'Unarchived items in this project, independent of the search query';
      const glyph = icon(ledger); glyph.classList.add('navigation-icon');
      const count = element('span', '—'); count.className = 'navigation-count'; count.id = `count-${ledger}`;
      entry.setAttribute('aria-describedby', count.id); this.navigationCounts.set(ledger, count);
      entry.append(glyph, element('span', label), count); shortcuts.append(entry);
    }
    const create = button('New item', () => { this.openEditor(null); }); create.className = 'navigation-entry'; create.prepend(icon('New'));
    const usage = button('Project usage', () => this.action(() => this.selectUsage(new api.UsageFilter_ProjectAll(), true))); usage.className = 'navigation-entry'; usage.prepend(icon('Usage'));
    const archive = button('Archive terminal items', () => this.action(async () => this.archive.open(this.currentProject(), this.activeQuery, this.order))); archive.className = 'navigation-entry';
    const questions = button('Answer questions', () => this.action(async () => this.questions.open(this.currentProject()))); questions.className = 'navigation-entry'; questions.prepend(icon(api.Ledger.Questions));
    side.append(create, questions, usage, archive, element('h3', 'Browse'), shortcuts);
    const table = element('table', ''); table.className = 'items-table'; table.setAttribute('aria-label', 'Items');
    const head = element('thead', ''); const headings = element('tr', '');
    const columns: TableColumn[] = [];
    for (const field of SORT_COLUMNS) {
      const cell = element('th', ''); cell.scope = 'col'; this.sortHeaders.set(field, cell);
      const label = field === 'Id' ? 'ID' : field === 'Modified' ? 'Last modified' : field;
      const control = button(label, () => this.action(async () => {
        this.order = new api.ItemOrder(field, this.order.field === field && this.order.direction === 'Ascending' ? api.SortDirection.Descending : api.SortDirection.Ascending, this.order.grouped);
        this.itemsView.store(this.order); this.updateSort(); await this.search();
      }));
      control.setAttribute('aria-label', `Sort by ${field === 'Id' ? 'ID' : label.toLowerCase()}`); cell.append(control); headings.append(cell);
      columns.push({ header: cell, label });
      if (field === api.ItemOrderField.Severity) {
        // The operator asked for the milestone icon instead of the word; the name is exposed to assistive technology only.
        const milestone = element('th', ''); milestone.scope = 'col'; milestone.className = 'milestone-heading';
        const flag = icon(api.Ledger.Milestones); flag.removeAttribute('aria-hidden'); flag.setAttribute('role', 'img'); flag.setAttribute('aria-label', 'Milestone');
        const grouping = element('input', ''); grouping.type = 'checkbox'; grouping.checked = this.order.grouped;
        grouping.setAttribute('aria-label', 'Group by milestone'); grouping.title = 'Group rows by this column';
        grouping.addEventListener('change', () => this.action(async () => {
          this.order = new api.ItemOrder(this.order.field, this.order.direction, grouping.checked);
          this.itemsView.store(this.order); await this.search();
        }));
        milestone.append(flag, grouping); headings.append(milestone); columns.push({ header: milestone, label: 'Milestone' });
      }
    }
    head.append(headings); table.append(head, this.items); this.updateSort();
    if (this.tableColumns !== null) this.tableColumns.destroy();
    this.tableColumns = new TableColumns(table, columns, workspace.results, this.items);
    const empty = element('td', 'No matching items.'); empty.colSpan = ITEM_COLUMNS; this.emptyResults.append(empty);
    this.items.tabIndex = -1; this.items.setAttribute('aria-label', 'Result items');
    this.items.addEventListener('keydown', event => {
      const rows = Array.from(this.items.querySelectorAll<HTMLButtonElement>('button')); const index = rows.indexOf(document.activeElement as HTMLButtonElement);
      const target = event.key === 'ArrowDown' ? Math.min(rows.length - 1, index + 1) : event.key === 'ArrowUp' ? Math.max(0, index - 1)
        : event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1 : null;
      if (target !== null && rows.length > 0) { event.preventDefault(); rows[target].focus(); }
      else if (event.key === 'ArrowRight' && workspace.detailVisible) { event.preventDefault(); content.focus(); }
      else if (event.key === 'Escape' && this.selection !== null && workspace.detailVisible) { event.preventDefault(); this.closeItem(); }
    });
    content.addEventListener('keydown', event => {
      if (event.key === 'Escape') { event.preventDefault(); const row = this.items.querySelector<HTMLButtonElement>('[aria-current=true]'); (row === null ? this.items : row).focus(); }
    });
    this.root.addEventListener('keydown', event => { if (event.ctrlKey && event.key.toLowerCase() === 'k') { event.preventDefault(); this.query.focus(); } });
    this.resultStatus.setAttribute('role', 'status');
    list.append(table);
    content.append(workspace.toggle, this.detail, this.editorPanel, this.graph.element, this.usagePanel, this.auditPanel);
    this.root.replaceChildren(header, workspace.element, status, this.projectDialog.element, this.createDialog.element, this.conflictDialog.element,
      this.historyDialog.element, this.usageDialog.element, this.archive.element, this.graph.dialog.element, this.references.dialog.element, this.questions.dialog.element, this.notifications.element);
    this.notifications.reveal(); workspace.fit();
    this.manager = new ConnectionManager(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`, {
      status: stats => this.health.update(stats),
      active: () => this.action(async () => {
        this.resetLiveWatch(); this.watch(); this.requests.usage++; this.requests.audit++; this.updateAuditFreshness();
        await this.loadProjects(); this.after = undefined; this.snapshot = undefined; await this.refresh();
      }),
      disconnected: () => {
        this.resetLiveWatch(); this.requests.usage++; this.requests.audit++;
        this.usageFreshness.textContent = `Stale · ${this.usageObserved}`; this.updateAuditFreshness();
        this.queryEditor.invalidate(); this.sync.textContent = 'Data: stale'; this.epoch++;
      },
      event: frame => {
        if (frame instanceof api.ServerFrame_Updated && frame.subscription.value === this.liveSubscription) {
          const catalogue = frame.revision.catalogue;
          if (catalogue !== undefined) {
            if (this.catalogueCursor === null || catalogue.value > this.catalogueCursor) this.catalogueCursor = catalogue.value;
            if (this.catalogueSnapshot === null || this.catalogueCursor > this.catalogueSnapshot) this.action(() => this.loadProjects());
          }
          const project = frame.revision.project;
          if (project !== undefined && this.project !== null && project.project.value === this.project.value) {
            if (this.itemCursor === null || project.items.value > this.itemCursor) this.itemCursor = project.items.value;
            if (this.countsSnapshot === null || this.itemCursor > this.countsSnapshot) this.action(() => this.loadCounts());
            if (!this.queryInvalid && (this.page === null || this.itemCursor > this.page.cursor.value)) {
              this.after = undefined; this.snapshot = undefined; this.action(() => this.refresh());
            }
            if (this.usageCursor === null || project.usage > this.usageCursor) this.usageCursor = project.usage;
            this.updateAuditFreshness();
            if (this.usageSnapshot === null || this.usageCursor > this.usageSnapshot) this.action(() => this.loadUsage());
            if (this.auditCursor === null || this.usageCursor > this.auditCursor) this.action(() => this.refreshAudit());
          }
        } else if (frame instanceof api.ServerFrame_Resync && frame.subscription.value === this.liveSubscription) {
          this.liveSubscription = null; this.updatesRejected = true;
          this.sync.textContent = 'Data: updates unavailable';
          this.usageFreshness.textContent = `Unavailable · ${this.usageObserved}`;
          this.showError(faultMessage(frame.fault));
        }
      },
    });
  }
  private async search(): Promise<void> {
    this.queryEditor.cancelLive(); this.archive.invalidate();
    this.activeQuery = this.query.value; this.queryInvalid = false; this.epoch++; this.after = undefined; this.snapshot = undefined;
    this.loadedItems = []; this.page = null;
    if (this.resultsPane !== null) this.resultsPane.scrollTop = 0;
    this.queryEditor.showDiagnostic(undefined, this.query.value); await this.refresh();
  }
  private reset(): void {
    this.questions.reset(); this.references.reset();
    this.archive.invalidate();
    this.createDialog.close(); this.historyDialog.close(); this.usageDialog.close(); this.closeEditor();
    this.queryEditor.invalidate(); this.queryEditor.showDiagnostic(undefined, this.query.value);
    this.epoch++; this.selectionGeneration++; this.selection = null; this.selected = null; this.editor = null; this.after = undefined; this.snapshot = undefined;
    this.queryInvalid = false; this.itemCursor = null; this.resetCounts(); this.resetUsageWatch(); this.watch();
    this.graph.setScope(this.project, null);
    this.rows.clear(); this.groupRows.clear(); this.items.replaceChildren(); this.loadedItems = []; this.page = null; this.resultStatus.textContent = 'Loading items…';
    if (this.resultsPane !== null) this.resultsPane.scrollTop = 0;
    this.detail.replaceChildren(); this.editorPanel.replaceChildren(); this.conflictPanel.replaceChildren(); this.historyPanel.replaceChildren(); this.usagePanel.replaceChildren(); this.auditPanel.replaceChildren();
    this.notifications.clear(); this.setUsageScope(new api.UsageFilter_ProjectAll());
  }
  private watch(): void {
    this.liveSubscription = this.connection().watch(new api.LiveScope(true, this.project === null ? undefined : this.project)).value;
  }
  private resetLiveWatch(): void {
    this.liveGeneration++; this.liveSubscription = null; this.catalogueLoad = null; this.catalogueCursor = null;
    this.catalogueSnapshot = null; this.itemCursor = null; this.resetCounts(); this.auditLoad = null; this.resetUsageWatch();
  }
  private async loadProjects(): Promise<void> {
    if (this.catalogueLoad !== null) { this.catalogueLoad.dirty = true; return; }
    const load: UsageLoad = { dirty: true }; this.catalogueLoad = load;
    const generation = this.liveGeneration;
    const current = () => this.catalogueLoad === load && generation === this.liveGeneration;
    try {
      while (current() && load.dirty) {
        load.dirty = false;
        const projects: api.Project[] = [];
        let after: api.ProjectId | undefined; let snapshot: api.CatalogueCursor | undefined;
        do {
          const response = await this.connection().call(new api.Command_Projects(after, snapshot, 200));
          if (!current()) return;
          if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_Resync) { load.dirty = true; break; }
          const result = readResult(response);
          if (!(result instanceof api.Result_Projects)) throw new Error('Unexpected project response');
          projects.push(...result.page.projects); after = result.page.hasMore ? result.page.after : undefined; snapshot = result.page.cursor;
        } while (after !== undefined && projects.length < 1000);
        if (load.dirty) continue;
        if (snapshot === undefined) throw new Error('Missing catalogue snapshot');
        this.catalogueSnapshot = snapshot.value;
        if (this.catalogueCursor === null || snapshot.value > this.catalogueCursor) this.catalogueCursor = snapshot.value;
        this.projects.replaceChildren();
        for (const project of projects) { const option = element('option', project.name); option.value = project.id.value; this.projects.append(option); }
        if (this.project === null && projects.length > 0) {
          this.project = projects[0].id; this.watch(); this.action(() => this.refresh());
        }
        if (this.project !== null) this.projects.value = this.project.value;
        this.graph.setScope(this.project, this.selected);
        if (after !== undefined) this.showError('Project selector reached 1,000 entries; use CLI for additional projects');
        load.dirty = this.catalogueCursor > snapshot.value;
      }
    } finally { if (current()) this.catalogueLoad = null; }
  }
  private loadMore(): void {
    const pane = this.resultsPane;
    if (pane === null || this.refreshing || this.page === null || !this.page.hasMore || this.project === null) return;
    if (pane.scrollHeight - pane.scrollTop - pane.clientHeight > LOAD_MORE_DISTANCE) return;
    this.after = this.page.after; this.snapshot = this.page.cursor;
    this.action(() => this.refresh());
  }
  private async refresh(): Promise<void> {
    if (this.project === null) return;
    this.action(() => this.loadCounts());
    if (this.refreshing) { this.dirty = true; return; }
    this.refreshing = true; this.dirty = false;
    const project = this.project; const epoch = this.epoch;
    const current = () => epoch === this.epoch && this.project === project;
    const append = this.after !== undefined;
    const items = append ? [...this.loadedItems] : [];
    const target = append ? items.length + PAGE_SIZE : Math.max(PAGE_SIZE, this.loadedItems.length);
    let after = this.after; let snapshot = this.snapshot; let completed = false;
    this.after = undefined; this.snapshot = undefined;
    this.sync.textContent = 'Data: synchronizing'; this.items.setAttribute('aria-busy', 'true');
    try {
      let page: api.BrowsePage;
      do {
        const selection = new api.ReadSelection_Browse(this.activeQuery, this.order, after, snapshot, PAGE_SIZE);
        const response = await this.connection().call(new api.Command_Read(new api.ReadInput(project, selection)));
        if (!current()) return;
        if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_Resync) { this.dirty = true; return; }
        if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_QuerySyntax) {
          const error = response.fault.diagnostic; this.page = null; this.queryInvalid = true;
          this.sync.textContent = 'Data: invalid query'; this.queryEditor.showDiagnostic(error, this.activeQuery);
          this.showError(`${error.message} (${error.span.start}–${error.span.end})`); return;
        }
        const result = readResult(response);
        if (!(result instanceof api.Result_Browsed)) throw new Error('Unexpected item page');
        page = result.page; items.push(...page.items); after = page.after; snapshot = page.cursor;
      } while (page.hasMore && items.length < target);
      this.page = page; this.loadedItems = items;
      this.renderItems(items);
      this.resultStatus.textContent = `${items.length} items${page.hasMore ? ' · more available' : ''}`;
      this.dirty = this.dirty || (this.itemCursor !== null && this.itemCursor > page.cursor.value);
      this.sync.textContent = this.updatesRejected ? 'Data: updates unavailable' : 'Data: current';
      if (this.selection !== null) {
        // D72/Decision 8: a fully loaded result without the selected item closes the item view; a later page may still hold it.
        const selected = this.selection;
        const listed = items.some(({ summary }) => summary.id.project.value === selected.project.value && itemName(summary.id) === itemName(selected));
        // An open editor or item dialog keeps its item: a live change must not discard what the operator is working on.
        const engaged = this.editor !== null || this.historyDialog.element.open || this.usageDialog.element.open || this.graph.dialog.element.open;
        if (listed || page.hasMore || engaged) await this.select(selected); else this.hideItem();
      }
      await this.loadUsage();
      completed = true;
    } catch (error) { if (current()) { this.sync.textContent = 'Data: stale'; throw error; } }
    finally {
      this.refreshing = false; this.items.setAttribute('aria-busy', 'false');
      if (this.dirty) { this.dirty = false; this.action(() => this.refresh()); }
      else if (completed && current()) requestAnimationFrame(() => this.loadMore());
    }
  }
  private updateSort(): void {
    for (const [field, header] of this.sortHeaders) {
      const active = field === this.order.field;
      header.setAttribute('aria-sort', active ? this.order.direction.toLowerCase() : 'none');
      header.title = `Sort ${active && this.order.direction === api.SortDirection.Ascending ? 'descending' : 'ascending'}`;
    }
  }
  private resetCounts(): void {
    this.countsLoad = null; this.countsSnapshot = null;
    for (const count of this.navigationCounts.values()) count.textContent = '—';
  }
  private async loadCounts(): Promise<void> {
    if (this.project === null) return;
    if (this.countsLoad !== null) { this.countsLoad.dirty = true; return; }
    const project = this.project; const generation = this.liveGeneration;
    const load: UsageLoad = { dirty: true }; this.countsLoad = load;
    const current = () => this.countsLoad === load && this.project === project && this.liveGeneration === generation;
    try {
      while (current() && load.dirty) {
        load.dirty = false;
        const response = await this.connection().call(new api.Command_Read(new api.ReadInput(project, new api.ReadSelection_Counts())));
        if (!current()) return;
        const result = readResult(response);
        if (!(result instanceof api.Result_Counts)) throw new Error('Unexpected navigation counts');
        this.countsSnapshot = result.report.cursor.value;
        let total = 0n;
        for (const entry of result.report.entries) {
          const badge = this.navigationCounts.get(entry.ledger);
          if (badge === undefined) throw new Error('Missing navigation ledger');
          badge.textContent = entry.count.toLocaleString(); total += entry.count;
        }
        const all = this.navigationCounts.get('All');
        if (all === undefined) throw new Error('Missing all-items navigation');
        all.textContent = total.toLocaleString();
        load.dirty = load.dirty || (this.itemCursor !== null && this.itemCursor > this.countsSnapshot);
      }
    } catch (error) { if (current()) throw error; }
    finally { if (current()) this.countsLoad = null; }
  }
  private groupRow(key: string, milestone: api.ItemId | undefined): HTMLTableRowElement {
    let row = this.groupRows.get(key);
    if (row === undefined) {
      row = element('tr', ''); row.className = 'item-group';
      const cell = element('td', ''); cell.colSpan = ITEM_COLUMNS;
      if (milestone === undefined) { cell.append(icon(api.Ledger.Milestones), '—'); cell.setAttribute('aria-label', 'No milestone'); } else cell.append(icon(api.Ledger.Milestones), key);
      row.append(cell); this.groupRows.set(key, row);
    }
    return row;
  }
  private renderItems(items: api.BrowseItem[]): void {
    const focused = this.items.contains(document.activeElement) ? document.activeElement as HTMLElement : null;
    const retained = new Set<string>(); const groups = new Set<string>(); this.emptyResults.remove();
    let position = 0; let group: string | null = null;
    const place = (line: HTMLTableRowElement): void => {
      const before = this.items.children.item(position++);
      if (before !== line) this.items.insertBefore(line, before);
    };
    for (const entry of items) {
      const item = entry.summary;
      if (this.order.grouped) {
        const name = entry.milestone === undefined ? '' : itemName(entry.milestone);
        if (name !== group) { group = name; groups.add(name); place(this.groupRow(name, entry.milestone)); }
      }
      const key = `${item.id.project.value}-${itemName(item.id)}`; retained.add(key);
      let row = this.rows.get(key);
      if (row === undefined) {
        const line = element('tr', ''); line.className = 'item-row'; line.dataset.item = key;
        const node = button('', () => this.action(() => this.select(item.id))); node.className = 'item-title';
        line.addEventListener('click', event => {
          if (event.target instanceof Node && !node.contains(event.target)) { node.focus(); this.action(() => this.select(item.id)); }
        });
        const id = element('td', ''); id.className = 'item-id'; id.title = item.id.ledger; id.append(icon(item.id.ledger), itemName(item.id));
        const title = element('td', ''); title.append(node);
        const status = element('td', ''); status.className = 'item-status'; status.id = `status-${key}`;
        const severity = element('td', ''); severity.className = 'item-severity';
        const milestone = element('td', ''); milestone.className = 'item-milestone';
        const modified = element('time', ''); const timestamp = element('td', ''); timestamp.className = 'item-modified'; timestamp.append(modified);
        node.setAttribute('aria-describedby', status.id); line.append(id, title, status, severity, milestone, timestamp);
        row = { element: line, button: node, status, severity, milestone, modified }; this.rows.set(key, row);
      }
      const caption = `${itemName(item.id)} · ${item.title}${item.archived ? ' · archived' : ''}`;
      row.button.textContent = item.title + (item.archived ? ' · archived' : ''); row.button.setAttribute('aria-label', caption);
      row.status.textContent = item.status; row.severity.textContent = entry.severity === undefined ? '—' : entry.severity;
      row.milestone.textContent = entry.milestone === undefined ? '' : itemName(entry.milestone);
      const date = new Date(Number(item.updatedAt)); row.modified.dateTime = date.toISOString(); row.modified.title = date.toLocaleString();
      row.modified.replaceChildren(element('span', date.toLocaleDateString(undefined, { dateStyle: 'short' })), element('span', date.toLocaleTimeString(undefined, { timeStyle: 'short' })));
      place(row.element);
    }
    for (const [key, row] of this.rows) if (!retained.has(key)) { row.element.remove(); this.rows.delete(key); }
    for (const [key, row] of this.groupRows) if (!groups.has(key)) { row.remove(); this.groupRows.delete(key); }
    if (items.length === 0) this.items.append(this.emptyResults);
    this.markSelection();
    if (focused !== null && document.activeElement !== focused) (focused.isConnected ? focused : this.items).focus();
  }
  private markSelection(): void {
    const key = this.selection === null ? null : `${this.selection.project.value}-${itemName(this.selection)}`;
    for (const [id, row] of this.rows) {
      row.button.setAttribute('aria-current', String(id === key)); row.element.classList.toggle('selected', id === key);
      row.element.setAttribute('aria-selected', String(id === key));
    }
  }
  private usageScope(): string {
    const scope = this.usageSelection;
    if (scope instanceof api.UsageFilter_ProjectAll) return 'project';
    if (scope instanceof api.UsageFilter_TaskOnly) return itemName(scope.item);
    if (scope instanceof api.UsageFilter_CohortOnly) return `cohort ${scope.execution}`;
    return `session ${scope.id.value}`;
  }
  private setUsageScope(scope: UsageScope): void {
    this.usageSelection = scope; this.requests.usage++; this.requests.audit++;
    this.usageSnapshot = null; this.auditCursor = null; this.usageLoad = null; this.auditView = null; this.auditLoad = null;
    this.usagePanel.replaceChildren(); this.auditPanel.replaceChildren(); this.resetUsage();
  }
  private resetUsageWatch(): void {
    this.usageCursor = null; this.usageSnapshot = null; this.updatesRejected = false; this.usageLoad = null;
  }
  private async selectUsage(scope: UsageScope, modal: boolean): Promise<void> {
    if (modal) {
      this.usageDialog.body.append(this.usagePanel, this.auditPanel);
      this.usageDialog.open(scope instanceof api.UsageFilter_ProjectAll ? 'Project usage' : 'Usage details');
    }
    this.setUsageScope(scope); await this.loadUsage();
  }
  private resetUsage(): void {
    this.usageMetric.textContent = `Usage · ${this.usageScope()}: not loaded`; this.usageObserved = 'No successful observation'; this.usageFreshness.textContent = this.usageObserved;
    this.usageMetric.title = this.usageMetric.textContent;
  }
  private itemDocument(item: api.Item): HTMLElement { return itemView(item.draft, text => this.references.render(item.id.project, text)); }
  private closeItem(): void {
    const row = this.items.querySelector<HTMLButtonElement>('[aria-current=true]');
    this.hideItem();
    (row !== null && row.isConnected ? row : this.items).focus();
  }
  // D71 close path without moving focus, shared with refreshes that drop the selection (D72).
  private hideItem(): void {
    const inside = this.workspace !== null && this.workspace.content.contains(document.activeElement);
    this.choose(null); if (this.workspace !== null) this.workspace.setDetailOpen(false);
    if (inside) this.items.focus();
  }
  private async select(id: api.ItemId): Promise<void> {
    this.choose(id); if (this.workspace !== null) this.workspace.setDetailOpen(true);
    const result = await this.readPanel('detail', new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_ItemDetail(id))));
    if (result === null) return;
    if (!(result instanceof api.Result_Detail)) throw new Error('Unexpected item response');
    this.selected = result.view;
    const item = result.view.item;
    const title = element('h2', `${itemName(item.id)} · ${item.draft.title}`);
    const actions = element('div', ''); actions.className = 'actions document-actions';
    const close = button('Close', () => this.closeItem()); close.setAttribute('aria-label', 'Close item view'); close.title = 'Close item view (Esc from results)';
    actions.append(close, button('Edit current revision', () => this.openEditor(result.view)),
      button('History', () => this.action(async () => { this.historyBefore = new api.Revision(9223372036854775807n);
        // Drop the previous item's or visit's revisions so stale rows cannot be activated while the fresh page loads.
        this.historyPanel.replaceChildren(element('p', 'Loading history…')); this.historyDialog.open(`History · ${itemName(item.id)}`); await this.loadHistory(); })));
    const metadata = element('p', `Revision ${item.revision.value} · ${item.provenance.actor.subject} · ${new Date(Number(item.updatedAt)).toLocaleString()}`); metadata.className = 'revision-meta';
    this.detail.replaceChildren(title, metadata, actions, this.itemDocument(item));
    this.detail.hidden = this.editor !== null && this.editor.record.item !== undefined;
    this.graph.setScope(this.project, result.view);
    await this.loadUsage();
  }
  private storeDraft(editor: NonNullable<App['editor']>): void {
    localStorage.setItem(editor.key, JSON.stringify(api.BrowserDraft_JsonCodec.instance.encode(CONTEXT, editor.record)));
  }
  private lockDraft(editor: NonNullable<App['editor']>): void {
    const pending = editor.record.pending !== undefined;
    for (const input of editor.form.element.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | HTMLButtonElement>('input,select,textarea,button')) input.disabled = pending;
    editor.discard.disabled = pending;
    for (const control of editor.quick) control.disabled = pending;
  }
  private closeEditor(): void {
    this.conflictDialog.close();
    this.editor = null; this.editorPanel.replaceChildren(); this.conflictPanel.replaceChildren(); this.detail.hidden = false;
    const pane = document.getElementById('detail-pane');
    if (pane !== null) this.detail.after(this.editorPanel);
  }
  private openEditor(base: api.ItemView | null): void {
    this.conflictPanel.replaceChildren();
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
    }
    const caption = record.item === undefined ? 'New item' : `Edit ${itemName(record.item.id)} from revision ${record.item.revision.value}`;
    const form = editItem(api.ItemDraft_JsonCodec.instance.encode(CONTEXT, record.value) as Json, caption);
    const discard = holdButton('Discard local draft', () => {
      if (this.editor !== editor || editor.record.pending !== undefined) return;
      localStorage.removeItem(key); localStorage.removeItem(key + ':next'); this.closeEditor(); this.createDialog.close();
    });
    const quick = (['Idea', 'Goal', 'Defect'] as const).map(kind => {
      const control = button(kind, () => { if (editor.record.pending === undefined) form.selectKind(kind); });
      control.setAttribute('aria-label', `Create ${kind}`); return control;
    });
    const editor = { form, record, key, discard, quick, busy: false, next: localStorage.getItem(key + ':next') === 'true' };
    this.editor = editor;
    const markKind = (): void => { quick.forEach(control => control.setAttribute('aria-pressed', String(control.textContent === form.kind()))); };
    markKind();
    for (const event of ['input', 'change', 'click']) form.element.addEventListener(event, () => {
      if (editor.record.pending !== undefined) return;
      try {
        editor.record = new api.BrowserDraft(project, editor.record.item, api.ItemDraft_JsonCodec.instance.decode(CONTEXT, form.read()), undefined);
        this.storeDraft(editor);
        markKind();
      } catch (error) { this.showError(`Draft storage failed: ${String(error)}`); }
    });
    this.lockDraft(editor);
    const actions = element('div', ''); actions.className = 'actions editor-actions';
    actions.append(button('Save item', () => this.action(() => this.save(false))));
    if (base === null) {
      const next = button('Save item and create next', () => this.action(() => this.save(true)));
      next.title = 'Ctrl+Enter / ⌘+Enter'; next.setAttribute('aria-keyshortcuts', 'Control+Enter Meta+Enter');
      next.setAttribute('aria-label', 'Save item and create next');
      const shortcut = element('kbd', 'Ctrl/⌘+Enter'); shortcut.className = 'button-shortcut'; shortcut.setAttribute('aria-hidden', 'true');
      next.append(shortcut); actions.append(next);
    }
    actions.append(button('Cancel edit', () => { this.closeEditor(); this.createDialog.close(); }), discard);
    this.editorPanel.replaceChildren(form.element, actions);
    if (base === null) { this.createDialog.actions.replaceChildren(...quick); this.createDialog.body.replaceChildren(this.editorPanel); this.createDialog.open('New item'); }
    else { this.detail.hidden = true; this.editorPanel.scrollIntoView({ block: 'start' }); }
    if (saved !== null) this.notifications.show(record.pending === undefined ? `${caption}: restored your local draft with its original base revision.`
      : `${caption}: a previous save is unresolved. Save item retries that exact request before further editing.`, 'info');

  }
  private async save(next: boolean): Promise<void> {
    const editor = this.editor; if (editor === null || editor.busy) return;
    const navigation = this.selectionGeneration;
    if (editor.record.pending === undefined) {
      editor.next = next && editor.record.item === undefined;
      localStorage.setItem(editor.key + ':next', String(editor.next));
      const draft = api.ItemDraft_JsonCodec.instance.decode(CONTEXT, editor.form.read());
      const base = editor.record.item;
      const mutation = base === undefined ? new api.Mutation_Create(draft) : new api.Mutation_Replace(base.id, base.revision, draft);
      const pending = new api.ChangeRequest(new api.RequestId(uuidV4(crypto)), [mutation], [], 'Browser edit');
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
        this.storeDraft(editor); this.lockDraft(editor);
        if (result.fault instanceof api.Fault_Conflict) await this.showConflict(editor);
        readResult(result);
      }
      if (!(result instanceof api.Result_Changed)) throw new Error('Unexpected change acknowledgement');
      if (localStorage.getItem(editor.key) === submitted) { localStorage.removeItem(editor.key); localStorage.removeItem(editor.key + ':next'); }
      const ownsEditor = this.editor === editor;
      if (ownsEditor) { this.closeEditor(); this.createDialog.close(); }
      const saved = result.ack.items[0];
      this.notifications.show([element('span', 'Saved'), document.createTextNode(` ${itemName(saved.id)} in project ${editor.record.project.value}.`)], 'success');
      if (this.project === null || this.project.value !== editor.record.project.value) return;
      this.after = undefined; this.snapshot = undefined; await this.refresh();
      if (ownsEditor && navigation === this.selectionGeneration) {
        const selection = this.select(saved.id); const generation = this.selectionGeneration;
        await selection;
        if (editor.next && generation === this.selectionGeneration && this.project.value === editor.record.project.value && this.editor === null && localStorage.getItem(editor.key) === null) {
          this.openEditor(null);
          const fresh = this.editor as App['editor'];
          if (fresh === null) throw new Error('New item editor was not opened');
          fresh.form.selectKind(editor.form.kind());
          const title = fresh.form.element.querySelector<HTMLTextAreaElement>('textarea[aria-label=title]');
          if (title === null) throw new Error('New item editor has no title field');
          title.focus();
        }
      }
    } finally { editor.busy = false; }
  }
  private async showConflict(editor: NonNullable<App['editor']>): Promise<void> {
    const base = editor.record.item; if (base === undefined || this.editor !== editor) return;
    const result = await this.call(new api.Command_Read(new api.ReadInput(editor.record.project, new api.ReadSelection_ItemDetail(base.id))));
    if (this.editor !== editor || editor.record.pending !== undefined) return;
    if (!(result instanceof api.Result_Detail)) throw new Error('Unexpected conflict comparison response');
    const current = result.view;
    this.conflictPanel.replaceChildren(
      element('p', `Your draft is based on revision ${base.revision.value}; the current revision is ${current.item.revision.value}.`),
      element('p', 'Inspect the current content below and your draft above. Changing the base keeps your draft; a later save replaces the current content.'),
      this.itemDocument(current.item),
      button('Use current revision as draft base', () => {
        if (this.editor !== editor || editor.record.pending !== undefined) return;
        editor.record = new api.BrowserDraft(editor.record.project, new api.ItemRevision(base.id, current.item.revision), editor.record.value, undefined);
        this.storeDraft(editor); this.openEditor(current);
        this.conflictDialog.close();
      }));
    this.conflictDialog.open(`Edit conflict · ${itemName(base.id)}`);
  }
  private async loadHistory(): Promise<void> {
    const selected = this.selected; if (selected === null) return;
    const result = await this.readPanel('history', new api.Command_Read(new api.ReadInput(this.currentProject(), new api.ReadSelection_History(selected.item.id, this.historyBefore, 10))));
    if (result === null) return;
    if (!(result instanceof api.Result_History)) throw new Error('Unexpected history response');
    const table = element('table', ''); table.setAttribute('aria-label', 'Revisions');
    const head = element('thead', ''); const headings = element('tr', '');
    for (const name of ['Revision', 'Changed', 'By', 'Reason']) headings.append(element('th', name)); head.append(headings); table.append(head);
    const rows = element('tbody', ''); const viewer = element('section', ''); viewer.setAttribute('aria-label', 'Historical revision');
    const show = (entry: api.HistoryEntry): void => {
      const item = entry.item.item;
      viewer.replaceChildren(element('h3', `${itemName(item.id)} · ${item.draft.title} · revision ${item.revision.value}`), this.itemDocument(item),
        element('h3', 'Relationships'), element('p', entry.item.refs.length === 0 ? 'No relationships.' : entry.item.refs.map(ref => `${ref.relation} ${itemName(ref.target)}`).join(' · ')),
        button(`Preview restore revision ${item.revision.value}`, () => this.action(async () => { this.historyDialog.close(); await this.graph.restore(entry.item); })));
      for (const row of rows.querySelectorAll('tr')) row.setAttribute('aria-selected', String(row.dataset.revision === String(item.revision.value)));
    };
    for (const entry of result.page.entries) {
      const item = entry.item.item; const row = element('tr', ''); row.dataset.revision = String(item.revision.value);
      const revision = element('td', ''); revision.append(button(`View revision ${item.revision.value}`, () => show(entry)));
      row.append(revision, element('td', new Date(Number(item.updatedAt)).toLocaleString()), element('td', item.provenance.actor.subject), element('td', entry.reason)); rows.append(row);
    }
    table.append(rows); this.historyPanel.replaceChildren(table, viewer);
    if (result.page.entries.length > 0) show(result.page.entries[0]);
    if (result.page.hasMore) this.historyPanel.append(button('Older history', () => this.action(async () => {
      this.historyBefore = result.page.entries[result.page.entries.length - 1].item.item.revision; await this.loadHistory();
    })));
  }
  private usageFilter(): api.UsageFilter { return this.usageSelection; }
  private async loadUsage(): Promise<void> {
    if (this.usageLoad !== null) { this.usageLoad.dirty = true; return; }
    const load: UsageLoad = { dirty: true }; this.usageLoad = load;
    try {
      while (load === this.usageLoad && load.dirty) {
        load.dirty = false;
        this.usageFreshness.textContent = `Loading · ${this.usageObserved}`;
        const result = await this.readPanel('usage', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Summary(this.usageFilter()))));
        if (load !== this.usageLoad || result === null) continue;
        if (!(result instanceof api.Result_UsageSummary)) throw new Error('Unexpected usage response');
        const phases = await this.readPanel('usage', new api.Command_Usage(new api.UsageInput(this.currentProject(), new api.UsageSelection_Phases(this.usageFilter()))));
        if (load !== this.usageLoad || phases === null) continue;
        if (!(phases instanceof api.Result_UsagePhases)) throw new Error('Unexpected usage phases response');
        this.usageSnapshot = result.report.cursor;
        // The phase report is read after the summary; a later cursor leaves the load dirty, so both are read again.
        if (this.usageCursor === null || phases.report.cursor > this.usageCursor) this.usageCursor = phases.report.cursor;
        this.renderUsage(result.report, phases.report); this.updateAuditFreshness();
        load.dirty = load.dirty || this.usageCursor > result.report.cursor;
      }
    } catch (error) {
      if (load === this.usageLoad) { this.usageFreshness.textContent = `Unavailable · ${this.usageObserved}`; throw error; }
    } finally { if (load === this.usageLoad) this.usageLoad = null; }
  }
  private renderUsage(report: api.UsageReport, phases: api.PhaseReport): void {
    this.usageMetric.textContent = `Usage · ${this.usageScope()}: ${report.direct.total.known} direct · ${report.shared.total.known} shared · ${report.unattributed.total.known} unattributed known tokens`;
    const totals = [report.direct.total, report.shared.total, report.unattributed.total];
    this.usageMetric.textContent += ` · ${totals.reduce((sum, value) => sum + value.unknown, 0n)} unknown measurements · ${totals.reduce((sum, value) => sum + value.estimated, 0n)} estimated measurements`;
    this.usageMetric.title = this.usageMetric.textContent;
    this.usageObserved = `Observed ${new Date().toLocaleTimeString()} · cursor ${report.cursor}`;
    this.usageFreshness.textContent = this.updatesRejected ? `Updates unavailable · ${this.usageObserved}` : this.usageObserved;
    const previousShared = this.usagePanel.querySelector<HTMLDetailsElement>('details.usage-shared');
    const sharedOpen = previousShared !== null && previousShared.open;
    this.usagePanel.replaceChildren(element('h3', `Usage · ${this.usageScope()}`));
    const table = element('table', ''); table.setAttribute('aria-label', 'Usage totals');
    const head = element('tr', ''); for (const label of ['Attribution', 'Known tokens', 'Unknown measurements', 'Estimated measurements', 'Unknown costs']) head.append(element('th', label));
    const headings = element('thead', ''); headings.append(head); const body = element('tbody', ''); table.append(headings, body);
    for (const [label, totals] of [['Direct', report.direct], ['Shared', report.shared], ['Unattributed', report.unattributed]] as const) {
      const row = element('tr', ''); row.append(element('th', label));
      for (const value of [totals.total.known, totals.total.unknown, totals.total.estimated, totals.unknownCosts]) row.append(element('td', String(value)));
      body.append(row);
    }
    this.usagePanel.append(table);
    if (report.costs.entries.length > 0) this.usagePanel.append(this.costTable(report.costs.entries));
    if (report.costs.hasMore) this.usagePanel.append(button('More costs', () => this.action(() => this.loadCosts(report.costs.after, report.cursor))));
    if (phases.phases.length > 0) this.usagePanel.append(phasesTable(phases.phases));
    if (phases.costsTruncated) this.usagePanel.append(element('p', 'Per-phase costs are truncated; the amounts shown are lower bounds. The cost breakdown lists every cost group.'));
    this.usagePanel.append(element('p', `Shared work is counted once and is not divided among members. Incomplete meters: ${report.incompleteMeters}; attempts without measurements: ${report.attemptsWithoutMeters}.`),
      element('p', `Attempt coverage: ${report.attempts.running} running; ${report.attempts.unknown} unknown outcomes; ${report.attempts.withGaps} with reported gaps.`),
      button('Attempts', () => this.action(() => this.loadAttempts(undefined, undefined))), button('Usage audit', () => this.action(() => this.loadAudit(0n))));
    if (report.sharedAssignments.length > 0) this.usagePanel.append(sharedAssignmentsList(report.sharedAssignments, sharedOpen));
    if (report.sharedAssignmentsTruncated) this.usagePanel.append(element('p', 'The shared-assignment list is truncated. Browse attempts for further assignments and their frozen membership.'));
  }
  private auditHeader(title: string, cursor: bigint): void {
    this.auditCursor = cursor;
    this.auditPanel.replaceChildren(element('h3', title), this.auditFreshness); this.updateAuditFreshness();
  }
  private updateAuditFreshness(): void {
    if (this.auditCursor === null) return;
    const latest = this.usageCursor;
    this.auditFreshness.textContent = latest === null ? `Snapshot cursor ${this.auditCursor}; freshness unconfirmed.`
      : `${latest > this.auditCursor ? 'Stale snapshot' : 'Snapshot'} cursor ${this.auditCursor}; latest observed usage cursor ${latest}.`;
  }
  private costTable(costs: readonly api.CostTotal[]): HTMLElement {
    const table = element('table', ''); table.setAttribute('aria-label', 'Costs');
    const header = element('thead', ''); const headings = element('tr', '');
    for (const label of ['Attribution', 'Amount', 'Currency', 'Basis', 'Pricing', 'Measurements']) headings.append(element('th', label));
    header.append(headings); table.append(header); const body = element('tbody', '');
    for (const cost of costs) {
      const group = cost.group; const row = element('tr', '');
      const amount = element('td', formatAmount(cost.amount.value, MoneyDigits)); amount.title = cost.amount.value;
      row.append(element('td', group.attribution), amount);
      for (const value of [group.currency, group.basis, group.pricingVersion === undefined ? 'unspecified' : group.pricingVersion, String(cost.measurements)]) row.append(element('td', value));
      body.append(row);
    }
    table.append(body); return table;
  }
  private loadCosts(after: api.CostGroup | undefined, snapshot: bigint | undefined): Promise<void> {
    return this.openAudit(new api.UsageSelection_Costs(this.usageFilter(), after, snapshot, 20));
  }
  private loadAttempts(after: api.AttemptId | undefined, snapshot: bigint | undefined): Promise<void> {
    return this.openAudit(new api.UsageSelection_Attempts(this.usageFilter(), after, snapshot, 20));
  }
  private loadOutcomes(attempt: api.AttemptId, after: bigint): Promise<void> {
    return this.openAudit(new api.UsageSelection_Outcomes(attempt, after, 20));
  }
  private loadAudit(after: bigint): Promise<void> {
    return this.openAudit(new api.UsageSelection_Audit(this.usageFilter(), after, 20));
  }
  private async openAudit(view: AuditView): Promise<void> {
    this.auditView = view; this.auditLoad = null; this.auditCursor = null; this.requests.audit++;
    await this.refreshAudit();
  }
  private async refreshAudit(): Promise<void> {
    const view = this.auditView; const project = this.project;
    if (view === null || project === null) return;
    if (this.auditLoad !== null) { this.auditLoad.dirty = true; return; }
    const load: UsageLoad = { dirty: true }; this.auditLoad = load;
    const request = ++this.requests.audit; const generation = this.selectionGeneration;
    const current = () => this.auditLoad === load && this.auditView === view && this.project === project &&
      this.requests.audit === request && this.selectionGeneration === generation;
    let snapshot = this.usageCursor === null ? undefined : this.usageCursor;
    let resnapshot = false;
    try {
      while (current() && load.dirty) {
        load.dirty = false;
        if (resnapshot && (view instanceof api.UsageSelection_Costs || view instanceof api.UsageSelection_Attempts)) {
          const first = view instanceof api.UsageSelection_Costs ? new api.UsageSelection_Costs(view.filter, undefined, undefined, 1)
            : new api.UsageSelection_Attempts(view.filter, undefined, undefined, 1);
          const result = await this.connection().call(new api.Command_Usage(new api.UsageInput(project, first)));
          if (!current()) return;
          readResult(result);
          if (!(result instanceof api.Result_UsageCosts || result instanceof api.Result_UsageAttempts)) throw new Error('Unexpected usage snapshot response');
          snapshot = result.page.cursor; resnapshot = false;
        }
        const selection = view instanceof api.UsageSelection_Costs ? new api.UsageSelection_Costs(view.filter, view.after, view.after === undefined ? undefined : snapshot, view.limit)
          : view instanceof api.UsageSelection_Attempts ? new api.UsageSelection_Attempts(view.filter, view.after, view.after === undefined ? undefined : snapshot, view.limit) : view;
        const response = await this.connection().call(new api.Command_Usage(new api.UsageInput(project, selection)));
        if (!current()) return;
        if (response instanceof api.Result_Failed && response.fault instanceof api.Fault_Resync) {
          resnapshot = true; load.dirty = true; continue;
        }
        const result = readResult(response);
        this.renderAudit(view, result);
        if (this.auditCursor === null) throw new Error('Audit renderer omitted snapshot cursor');
        load.dirty = this.usageCursor !== null && this.usageCursor > this.auditCursor;
      }
    } catch (error) {
      if (current()) { this.auditFreshness.textContent = 'Usage view unavailable; waiting for the next update or reconnection.'; throw error; }
    } finally { if (this.auditLoad === load) this.auditLoad = null; }
  }
  private renderAudit(view: AuditView, result: api.Result): void {
    if (result instanceof api.Result_UsageCosts && view instanceof api.UsageSelection_Costs) {
      this.auditHeader('Cost breakdown', result.page.cursor);
      this.auditPanel.append(this.costTable(result.page.entries));
      if (result.page.hasMore) this.auditPanel.append(button('Next cost page', () => this.action(() => this.loadCosts(result.page.after, result.page.cursor))));
      return;
    }
    if (result instanceof api.Result_UsageAttempts && view instanceof api.UsageSelection_Attempts) {
      this.auditHeader('Attempts', result.page.cursor);
      if (result.page.entries.length === 0) this.auditPanel.append(element('p', 'No attempts in this scope.'));
      this.auditPanel.append(attemptsTable(result.page.entries, {
        scope: filter => this.action(() => this.selectUsage(filter, true)),
        outcomes: attempt => this.action(() => this.loadOutcomes(attempt, 0n)),
      }));
      if (result.page.hasMore) this.auditPanel.append(button('Next attempt page', () => this.action(() => this.loadAttempts(result.page.after, result.page.cursor))));
      return;
    }
    if (result instanceof api.Result_UsageOutcomes && view instanceof api.UsageSelection_Outcomes) {
      this.auditHeader('Outcome history', result.page.cursor);
      if (result.page.entries.length === 0) this.auditPanel.append(element('p', 'No outcome recorded yet.'));
      this.auditPanel.append(outcomesTable(result.page.entries));
      if (result.page.hasMore) this.auditPanel.append(button('Next outcome page', () => this.action(() => this.loadOutcomes(view.attempt, result.page.after))));
      return;
    }
    if (result instanceof api.Result_UsageAudit && view instanceof api.UsageSelection_Audit) {
      this.auditHeader('Usage audit', result.page.cursor);
      if (result.page.entries.length === 0) this.auditPanel.append(element('p', 'No usage observations in this scope.'));
      this.auditPanel.append(auditTable(result.page.entries));
      if (result.page.hasMore) this.auditPanel.append(button('Next audit page', () => this.action(() => this.loadAudit(result.page.after))));
      return;
    }
    throw new Error('Unexpected usage view response');
  }
}

const root = document.getElementById('app');
if (root === null) throw new Error('Missing application root');
new App(root);
