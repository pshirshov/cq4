import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { itemName } from './items.js';
import { faultMessage } from './faults.js';
import { uuidV4 } from './uuid.js';

const CONTEXT = BaboonCodecContext.Default;
const MAX_MEMBERS = 512;
const MAX_SCANNED = 5000;
interface ArchiveEffects {
  call(command: api.Command): Promise<api.Result>;
  committed(project: api.ProjectId, acknowledgement: api.ChangeAck): Promise<void>;
}

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
    const preview = await this.collect(project, query, current);
    if (preview === null || !current()) return;
    this.render(project, query, preview, generation);
  }
  private async collect(project: api.ProjectId, query: string, current: () => boolean): Promise<api.ArchivePlan | null> {
    const result = await this.effects.call(new api.Command_Read(new api.ReadInput(project, new api.ReadSelection_ArchivePreview(query, MAX_MEMBERS))));
    if (!current()) return null;
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    if (!(result instanceof api.Result_ArchivePreview)) throw new Error('Unexpected archive preview response');
    return result.plan;
  }
  private render(project: api.ProjectId, query: string, preview: api.ArchivePlan, generation: number): void {
    const panel = this.dialog.body;
    panel.replaceChildren(element('p', `Filter: ${query || 'All items'}. ${preview.scanned} matching items examined.`),
      element('p', `${preview.members.length} unarchived terminal items selected. Their status, content and history are preserved.`));
    if (preview.retained.length > 0) {
      panel.append(element('p', `${preview.retained.length} terminal items are kept because related items are still open:`));
      const kept = element('ul', ''); kept.setAttribute('aria-label', 'Retained items');
      for (const retention of preview.retained) kept.append(element('li', `${itemName(retention.item.id)} · ${retention.item.title} · open: ${retention.open.map(itemName).join(', ')}`));
      panel.append(kept);
    }
    if (preview.limited) panel.append(element('p', `Limited preview: at most ${MAX_MEMBERS} terminal items and ${MAX_SCANNED} examined matches per operation. Only the items below will be archived. Refine the filter or repeat after this batch.`));
    if (preview.members.length === 0) { panel.append(element('p', 'No terminal items selected.')); return; }
    const table = element('table', ''); table.setAttribute('aria-label', 'Archive selection');
    const head = element('thead', ''); const headings = element('tr', '');
    for (const label of ['ID', 'Title', 'Status']) headings.append(element('th', label));
    head.append(headings); table.append(head); const body = element('tbody', '');
    for (const item of preview.members) {
      const row = element('tr', ''); for (const value of [itemName(item.id), item.title, item.status]) row.append(element('td', value)); body.append(row);
    }
    table.append(body); panel.append(table);
    const input = new api.ChangeInput(project, new api.ChangeRequest(new api.RequestId(uuidV4(crypto)),
      [new api.Mutation_Archive(preview.members.map(item => new api.ItemRevision(item.id, item.revision)))], [], 'Browser archive of previewed terminal items'));
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
        throw new Error(faultMessage(result.fault));
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
