import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { Dialog } from './dialog.js';
import { button, element } from './editor.js';
import { itemName } from './items.js';
import { faultMessage } from './faults.js';
import { uuidV4 } from './uuid.js';

const CONTEXT = BaboonCodecContext.Default;
const BATCH_LIMIT = 100;
interface QuestionEffects {
  call(command: api.Command): Promise<api.Result>;
  view(item: api.Item): HTMLElement;
  committed(project: api.ProjectId, ack: api.ChangeAck): Promise<void>;
}

export class QuestionBatch {
  readonly dialog = new Dialog('large', () => { this.generation++; this.record = null; });
  private generation = 0;
  private queue: api.ItemId[] = [];
  private index = 0;
  private completed = new Set<string>();
  private record: api.BrowserDraft | null = null;
  private readonly answer = element('textarea', '');
  private readonly save = button('Save answer and next', () => this.action(() => this.submit()));
  private readonly previous = button('Previous question', () => this.move(-1));
  private readonly next = button('Skip / next question', () => this.move(1));
  private readonly busy = new Set<string>();
  private choices: HTMLButtonElement[] = [];
  private more = false;

  constructor(private readonly effects: QuestionEffects, private readonly storage: Storage) {
    this.answer.setAttribute('aria-label', 'Answer'); this.answer.rows = 4;
    this.answer.addEventListener('input', () => this.action(async () => {
      const record = this.record; if (record === null || record.pending !== undefined) return;
      if (!(record.value.content instanceof api.Content_Question)) throw new Error('Question draft has another content type');
      const value = record.value.with({ content: record.value.content.with({answer: this.answer.value}) });
      this.record = record.with({value}); this.store(this.record);
    }));
    this.dialog.element.addEventListener('keydown', event => {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && !event.isComposing) { event.preventDefault(); this.action(() => this.submit()); }
    });
    this.save.title = 'Ctrl+Enter / ⌘+Enter'; this.save.setAttribute('aria-keyshortcuts', 'Control+Enter Meta+Enter');
  }
  reset(): void { this.generation++; this.record = null; this.dialog.close(); }
  private key(id: api.ItemId): string { return `cq-question-draft:${id.project.value}:${itemName(id)}`; }
  private encode(record: api.BrowserDraft): string { return JSON.stringify(api.BrowserDraft_JsonCodec.instance.encode(CONTEXT, record)); }
  private store(record: api.BrowserDraft): void {
    if (record.item === undefined) throw new Error('Question draft has no item identity');
    this.storage.setItem(this.key(record.item.id), this.encode(record));
  }
  private read(id: api.ItemId): api.BrowserDraft | null {
    const saved = this.storage.getItem(this.key(id)); if (saved === null) return null;
    const record = api.BrowserDraft_JsonCodec.instance.decode(CONTEXT, JSON.parse(saved));
    if (record.project.value !== id.project.value || record.item === undefined || record.item.id.project.value !== id.project.value ||
      record.item.id.ledger !== api.Ledger.Questions || itemName(record.item.id) !== itemName(id) || !(record.value.content instanceof api.Content_Question))
      throw new Error('Stored question draft identity is invalid');
    if (record.pending !== undefined) {
      const mutations = record.pending.mutations; const mutation = mutations[0];
      if (mutations.length !== 1 || !(mutation instanceof api.Mutation_Replace) || mutation.id.project.value !== id.project.value ||
        itemName(mutation.id) !== itemName(id) || mutation.expected.value !== record.item.revision.value ||
        !(mutation.draft.content instanceof api.Content_Question) || mutation.draft.content.status !== api.QuestionStatus.Answered)
        throw new Error('Stored question request is invalid');
    }
    return record;
  }
  private action(effect: () => Promise<void>): void {
    const generation = this.generation;
    void effect().catch(error => { if (generation === this.generation) this.error(error); });
  }
  private error(error: unknown): void { this.dialog.error.textContent = String(error); this.dialog.error.hidden = false; }
  private async call(command: api.Command): Promise<api.Result> {
    const result = await this.effects.call(command);
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    return result;
  }
  open(project: api.ProjectId): void {
    this.reset(); this.dialog.open('Answer questions'); this.dialog.body.replaceChildren(element('p', 'Loading open questions…'));
    const generation = this.generation;
    this.action(async () => {
      const result = await this.call(new api.Command_Search(new api.SearchInput(project, 'ledger:Questions status:Open', undefined, undefined, BATCH_LIMIT)));
      if (generation !== this.generation) return;
      if (!(result instanceof api.Result_Found)) throw new Error('Unexpected question list response');
      const pending: api.ItemId[] = [];
      const prefix = `cq-question-draft:${project.value}:`;
      for (let index = 0; index < this.storage.length; index++) {
        const key = this.storage.key(index); if (key === null || !key.startsWith(prefix)) continue;
        const value = this.storage.getItem(key); if (value === null) continue;
        const record = api.BrowserDraft_JsonCodec.instance.decode(CONTEXT, JSON.parse(value));
        if (record.item === undefined || this.key(record.item.id) !== key) throw new Error('Stored question key is invalid');
        const verified = this.read(record.item.id);
        if (verified !== null && verified.pending !== undefined) pending.push(record.item.id);
      }
      const members = new Map([...pending, ...result.page.items.map(item => item.id)].map(id => [itemName(id), id]));
      this.queue = [...members.values()]; this.completed = new Set(); this.more = result.page.hasMore;
      await this.load(0);
    });
  }
  private async load(index: number): Promise<void> {
    const generation = ++this.generation; this.index = index; this.record = null; this.dialog.error.hidden = true;
    const id = this.queue[index];
    if (id === undefined) { this.dialog.body.replaceChildren(element('p', 'No open questions to answer.')); return; }
    this.dialog.body.replaceChildren(element('p', `Loading ${itemName(id)}…`));
    try {
      const result = await this.call(new api.Command_Read(new api.ReadInput(id.project, new api.ReadSelection_ItemDetail(id))));
      if (generation !== this.generation) return;
      if (!(result instanceof api.Result_Detail) || !(result.view.item.draft.content instanceof api.Content_Question)) throw new Error('Expected a Question item');
      const item = result.view.item;
      const saved = this.read(id);
      const record = saved === null ? new api.BrowserDraft(id.project, new api.ItemRevision(id, item.revision), item.draft, undefined) : saved;
      this.record = record; this.render(item);
    } catch (error) {
      if (generation === this.generation) {
        this.dialog.body.replaceChildren(element('p', `${itemName(id)} could not be loaded. Retained answers have not been discarded.`),
          button('Retry loading question', () => this.action(() => this.load(index))));
        this.error(error);
      }
    }
  }
  private render(item: api.Item): void {
    const record = this.record;
    if (record === null || record.item === undefined || !(record.value.content instanceof api.Content_Question) || !(item.draft.content instanceof api.Content_Question)) throw new Error('Missing question draft');
    const pending = record.pending !== undefined;
    const stale = record.item.revision.value !== item.revision.value;
    const unavailable = item.draft.content.status !== api.QuestionStatus.Open || item.draft.archived;
    this.answer.value = record.value.content.answer === undefined ? '' : record.value.content.answer;
    this.answer.disabled = pending || unavailable; this.save.disabled = this.busy.has(this.key(item.id)) || (!pending && (stale || unavailable));
    this.save.textContent = pending ? 'Retry exact answer' : 'Save answer and next';
    this.previous.disabled = this.nextIndex(-1) === null; this.next.disabled = this.nextIndex(1) === null;
    // Pick controls are placed before each rendered alternative so every alternative appears once (D69).
    const view = this.effects.view(item);
    const rows = view.querySelectorAll<HTMLLIElement>('section[data-field="alternatives"] > .field-value > ul > li');
    const alternatives = item.draft.content.alternatives;
    if (rows.length !== alternatives.length) throw new Error('Question alternatives were not rendered as a list');
    this.choices = alternatives.map((alternative, index) => {
      const choice = button('Pick', () => { this.answer.value = alternative; this.answer.dispatchEvent(new Event('input')); this.answer.focus(); });
      choice.className = 'pick-alternative'; choice.setAttribute('aria-label', `Pick alternative: ${alternative}`);
      choice.disabled = pending || unavailable; rows[index].classList.add('answer-alternative'); rows[index].prepend(choice);
      return choice;
    });
    const actions = element('div', ''); actions.className = 'actions'; actions.append(this.previous, this.next, this.save);
    const label = element('label', 'Your answer'); label.append(this.answer);
    this.dialog.body.replaceChildren(element('h3', `${itemName(item.id)} · ${item.draft.title}`),
      element('p', `Question ${this.index + 1} of ${this.queue.length} · ${this.completed.size} answered in this batch`),
      view, label, actions);
    if (this.more) this.dialog.body.append(element('p', `This batch includes the first ${BATCH_LIMIT} open questions and retained pending answers. More questions remain; reopen the dialog for the next batch.`));
    if (pending) this.dialog.body.prepend(element('p', 'The previous answer has an unresolved acknowledgement. Retry the exact retained request before changing it.'));
    else if (unavailable) this.dialog.body.prepend(element('p', 'This question is no longer open. Your draft is retained; skip to another question.'));
    else if (stale) this.dialog.body.prepend(element('p', 'This question changed since your draft. Review the current question below before keeping your answer.'),
      button('Use current question as base', () => {
        const value = item.draft.with({content: (item.draft.content as api.Content_Question).with({answer: this.answer.value})});
        this.record = new api.BrowserDraft(item.id.project, new api.ItemRevision(item.id, item.revision), value, undefined);
        this.store(this.record); this.render(item);
      }));
    this.answer.focus();
  }
  private nextIndex(direction: number): number | null {
    for (let index = this.index + direction; index >= 0 && index < this.queue.length; index += direction)
      if (!this.completed.has(itemName(this.queue[index]))) return index;
    return null;
  }
  private move(direction: number): void { const index = this.nextIndex(direction); if (index !== null) this.action(() => this.load(index)); }
  private async submit(): Promise<void> {
    let record = this.record;
    if (record === null || record.item === undefined || this.save.disabled) return;
    const id = record.item.id; const key = this.key(id); const generation = this.generation;
    if (this.busy.has(key)) return;
    if (record.pending === undefined) {
      if (!(record.value.content instanceof api.Content_Question)) throw new Error('Expected Question draft');
      if (this.answer.value.trim() === '') throw new Error('Enter an answer before saving.');
      const value = record.value.with({content: record.value.content.with({status: api.QuestionStatus.Answered, answer: this.answer.value})});
      record = record.with({value, pending: new api.ChangeRequest(new api.RequestId(uuidV4(crypto)),
        [new api.Mutation_Replace(id, record.item.revision, value)], [], 'Browser answer to human question')});
      this.store(record); this.record = record;
    }
    const pending = record.pending; if (pending === undefined) throw new Error('Missing prepared answer request');
    const submitted = this.encode(record);
    let failure: unknown = null;
    this.busy.add(key); this.answer.disabled = true; this.save.disabled = true;
    for (const choice of this.choices) choice.disabled = true;
    try {
      const result = await this.effects.call(new api.Command_Change(new api.ChangeInput(record.project, pending)));
      if (!(result instanceof api.Result_Changed || result instanceof api.Result_Failed)) throw new Error('Unexpected answer acknowledgement');
      if (result instanceof api.Result_Failed) {
        if (this.storage.getItem(key) === submitted) this.store(record.with({pending: undefined}));
        failure = faultMessage(result.fault);
        return;
      }
      if (this.storage.getItem(key) === submitted) this.storage.removeItem(key);
      await this.effects.committed(record.project, result.ack);
      if (generation !== this.generation) return;
      this.completed.add(itemName(id));
      const next = this.nextIndex(1); const remaining = this.queue.findIndex(member => !this.completed.has(itemName(member)));
      if (remaining < 0) { this.record = null; this.dialog.body.replaceChildren(element('p', 'All questions in this batch are answered.')); }
      else await this.load(next === null ? remaining : next);
    } catch (error) { failure = error; }
    finally {
      this.busy.delete(key);
      const displayed = this.record;
      if (displayed !== null && displayed.project.value === record.project.value && displayed.pending !== undefined && displayed.pending.request.value === pending.request.value) {
        const reload = this.load(this.index); const reloadGeneration = this.generation;
        await reload;
        if (reloadGeneration === this.generation && failure !== null) this.error(failure);
      }
    }
  }
}
