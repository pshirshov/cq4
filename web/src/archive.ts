import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { itemName } from './items.js';
import { uuidV4 } from './uuid.js';

const CONTEXT = BaboonCodecContext.Default;
const PAGE_SIZE = 200;
const MAX_MEMBERS = 512;
const MAX_SCANNED = 5000;
const SNAPSHOT_ATTEMPTS = 3;
interface ArchiveEffects {
  call(command: api.Command): Promise<api.Result>;
  committed(project: api.ProjectId, acknowledgement: api.ChangeAck): Promise<void>;
}
interface Preview { items: api.ItemSummary[]; scanned: number; limited: boolean }

export class ArchiveDialog {
  private generation = 0;
  private readonly busy = new Set<string>();
  private readonly dialog = new Dialog(() => { this.generation++; });
  readonly element = this.dialog.element;
  constructor(private readonly effects: ArchiveEffects, private readonly storage: Storage) {}

  invalidate(): void { this.generation++; this.dialog.close(); }
  private prefix(project: api.ProjectId): string { return `cq-archive-change:${project.value}:`; }
  private encode(input: api.ChangeInput): string { return JSON.stringify(api.ChangeInput_JsonCodec.instance.encode(CONTEXT, input)); }
  private pending(project: api.ProjectId): api.ChangeInput[] {
    const result: api.ChangeInput[] = [];
    for (let index = 0; index < this.storage.length; index++) {
      const key = this.storage.key(index);
      if (key === null || !key.startsWith(this.prefix(project))) continue;
      const stored = this.storage.getItem(key);
      if (stored === null) continue;
      const input = api.ChangeInput_JsonCodec.instance.decode(CONTEXT, JSON.parse(stored));
      if (input.project.value !== project.value || key !== this.prefix(project) + input.change.request.value ||
        input.change.mutations.length !== 1 || !(input.change.mutations[0] instanceof api.Mutation_Archive)) throw new Error('Invalid retained archival request');
      result.push(input);
    }
    return result;
  }
  private action(generation: number, effect: () => Promise<void>): void {
    void effect().catch(error => {
      if (generation === this.generation) { this.dialog.error.textContent = String(error); this.dialog.error.hidden = false; }
    });
  }
  open(project: api.ProjectId, query: string, order: api.ItemOrder): void {
    this.invalidate(); this.dialog.open('Archive terminal items');
    this.action(this.generation, () => this.prepare(project, query, order));
  }
  private async prepare(project: api.ProjectId, query: string, order: api.ItemOrder): Promise<void> {
    const generation = this.generation;
    const current = () => generation === this.generation;
    const pending = this.pending(project);
    if (pending.length > 0) { this.renderPending(project, pending, generation); return; }
    this.dialog.body.replaceChildren(element('p', 'Preparing a snapshot of the current filter…'));
    for (let attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
      const preview = await this.collect(project, query, order, current);
      if (!current()) return;
      if (preview === null) { this.dialog.body.replaceChildren(element('p', 'Items changed while preparing the preview; restarting it…')); continue; }
      this.render(project, query, preview, generation); return;
    }
    throw new Error('Items kept changing during preview. Open a fresh archive preview when changes settle.');
  }
  private async collect(project: api.ProjectId, query: string, order: api.ItemOrder, current: () => boolean): Promise<Preview | null> {
    const items: api.ItemSummary[] = []; let scanned = 0;
    let after: api.ItemId | undefined; let snapshot: api.ChangeCursor | undefined;
    while (current()) {
      const result = await this.effects.call(new api.Command_Read(new api.ReadInput(project,
        new api.ReadSelection_Browse(query, order, after, snapshot, PAGE_SIZE))));
      if (!current()) return null;
      if (result instanceof api.Result_Failed && result.fault instanceof api.Fault_Resync) return null;
      if (result instanceof api.Result_Failed) throw new Error(JSON.stringify(api.Fault_JsonCodec.instance.encode(CONTEXT, result.fault)));
      if (!(result instanceof api.Result_Browsed)) throw new Error('Unexpected archive preview response');
      const page = result.page; snapshot = page.cursor; scanned += page.items.length;
      const eligible = page.items.map(entry => entry.summary).filter(item => !item.archived && item.outcome.terminal);
      items.push(...eligible);
      if (items.length >= MAX_MEMBERS || scanned >= MAX_SCANNED || !page.hasMore)
        return { items: items.slice(0, MAX_MEMBERS), scanned, limited: items.length > MAX_MEMBERS || page.hasMore };
      if (page.after === undefined) throw new Error('Archive preview continuation is missing');
      after = page.after;
    }
    return null;
  }
  private render(project: api.ProjectId, query: string, preview: Preview, generation: number): void {
    const panel = this.dialog.body;
    panel.replaceChildren(element('p', `Filter: ${query || 'All items'}. ${preview.scanned} matching items examined.`),
      element('p', `${preview.items.length} unarchived terminal items selected. Their status, content and history are preserved.`));
    if (preview.limited) panel.append(element('p', `Limited preview: at most ${MAX_MEMBERS} terminal items and ${MAX_SCANNED} examined matches per operation. Only the items below will be archived. Refine the filter or repeat after this batch.`));
    if (preview.items.length === 0) { panel.append(element('p', 'No terminal items selected.')); return; }
    const table = element('table', ''); table.setAttribute('aria-label', 'Archive selection');
    const head = element('thead', ''); const headings = element('tr', '');
    for (const label of ['ID', 'Title', 'Status']) headings.append(element('th', label));
    head.append(headings); table.append(head); const body = element('tbody', '');
    for (const item of preview.items) {
      const row = element('tr', ''); for (const value of [itemName(item.id), item.title, item.status]) row.append(element('td', value)); body.append(row);
    }
    table.append(body); panel.append(table);
    const input = new api.ChangeInput(project, new api.ChangeRequest(new api.RequestId(uuidV4(crypto)),
      [new api.Mutation_Archive(preview.items.map(item => new api.ItemRevision(item.id, item.revision)))], [], 'Browser archive of previewed terminal items'));
    panel.append(element('p', 'Confirmation uses these exact revisions. If any selected item changed or cannot be archived, the entire operation is rejected.'),
      button('Confirm archive', () => this.action(generation, async () => {
        if (generation !== this.generation) return;
        if (this.pending(project).length > 0) throw new Error('Another archival awaits acknowledgement. Reopen this dialog to resolve it first.');
        this.storage.setItem(this.prefix(project) + input.change.request.value, this.encode(input));
        await this.submit(input, generation);
      })));
  }
  private renderPending(project: api.ProjectId, pending: api.ChangeInput[], generation: number): void {
    this.dialog.body.replaceChildren(element('p', `Project ${project.value}. These confirmed archival requests have unresolved acknowledgements. Retry the retained requests to determine their outcomes.`));
    for (const input of pending) {
      const mutation = input.change.mutations[0];
      if (!(mutation instanceof api.Mutation_Archive)) throw new Error('Retained request is not an archival');
      const retry = button('Retry exact archive', () => this.action(generation, () => this.submit(input, generation)));
      retry.disabled = this.busy.has(input.change.request.value);
      this.dialog.body.append(element('p', `Request ${input.change.request.value}: ${mutation.members.map(member => itemName(member.id)).join(', ')}`), retry);
    }
  }
  private async submit(input: api.ChangeInput, generation: number): Promise<void> {
    if (generation !== this.generation) return;
    this.dialog.error.hidden = true;
    const id = input.change.request.value; if (this.busy.has(id)) return;
    const key = this.prefix(input.project) + id; const encoded = this.encode(input);
    if (this.storage.getItem(key) !== encoded) throw new Error('Retained archival request changed; reopen the dialog before retrying.');
    this.busy.add(id); this.renderPending(input.project, this.pending(input.project), generation);
    try {
      const result = await this.effects.call(new api.Command_Change(input));
      if (!(result instanceof api.Result_Changed || result instanceof api.Result_Failed)) throw new Error('Unexpected archival acknowledgement');
      if (this.storage.getItem(key) === encoded) this.storage.removeItem(key);
      if (result instanceof api.Result_Failed) {
        if (generation === this.generation) this.dialog.body.replaceChildren(element('p', 'Archival rejected. Close this dialog and prepare a fresh preview.'));
        throw new Error(JSON.stringify(api.Fault_JsonCodec.instance.encode(CONTEXT, result.fault)));
      }
      if (generation === this.generation) this.dialog.close();
      await this.effects.committed(input.project, result.ack);
    } finally {
      this.busy.delete(id);
      if (generation === this.generation) {
        const pending = this.pending(input.project);
        if (pending.length > 0) this.renderPending(input.project, pending, generation);
      }
    }
  }
}
