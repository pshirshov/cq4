import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { Dialog } from './dialog.js';
import { button, element } from './editor.js';
import { icon } from './icons.js';
import { itemName, parseItem } from './items.js';
import { faultMessage } from './faults.js';
import { itemView } from './presentation.js';

export class ReferencePopup {
  readonly dialog = new Dialog('standard', () => { this.generation++; });
  private generation = 0;
  private trail: api.ItemId[] = [];

  constructor(private readonly call: (command: api.Command) => Promise<api.Result>) {}

  show(id: api.ItemId): void { void this.open(id, false); }

  reset(): void { this.generation++; this.trail = []; this.dialog.close(); }

  render(project: api.ProjectId, text: string): DocumentFragment {
    const fragment = document.createDocumentFragment();
    const tokens = /https?:\/\/\S+|`[^`]*`|[A-Z]+[0-9]+/g;
    let end = 0;
    for (const match of text.matchAll(tokens)) {
      const start = match.index; const after = start + match[0].length;
      if (match[0].startsWith('http') || match[0].startsWith('`') ||
        (start > 0 && /[A-Za-z0-9_/:\\.-]/.test(text[start - 1])) ||
        (after < text.length && /[A-Za-z0-9_/\\:-]/.test(text[after])) ||
        (text[after] === '.' && after + 1 < text.length && /[A-Za-z0-9_]/.test(text[after + 1]))) continue;
      let id: api.ItemId;
      try { id = parseItem(project, match[0]); } catch { continue; }
      fragment.append(document.createTextNode(text.slice(end, start)));
      const link = button(match[0], () => { void this.open(id, false); }); link.className = 'item-reference'; link.prepend(icon(id.ledger));
      link.setAttribute('aria-label', `View ${match[0]}`); link.setAttribute('aria-haspopup', 'dialog');
      fragment.append(link); end = after;
    }
    fragment.append(document.createTextNode(text.slice(end))); return fragment;
  }

  private async open(id: api.ItemId, back: boolean): Promise<void> {
    if (!this.dialog.element.open) this.trail = [];
    if (!back) this.trail.push(id);
    const generation = ++this.generation;
    this.dialog.open(`Item reference · ${itemName(id)}`);
    this.dialog.body.replaceChildren(element('p', `Loading ${itemName(id)}…`));
    this.dialog.actions.replaceChildren();
    if (this.trail.length > 1) this.dialog.actions.append(button('Back', () => {
      this.trail.pop(); const previous = this.trail[this.trail.length - 1];
      void this.open(previous, true);
    }));
    try {
      const result = await this.call(new api.Command_Read(new api.ReadInput(id.project, new api.ReadSelection_ItemDetail(id))));
      if (generation !== this.generation) return;
      if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      if (!(result instanceof api.Result_Detail)) throw new Error('Unexpected reference response');
      const item = result.view.item;
      this.dialog.body.replaceChildren(element('h3', `${itemName(item.id)} · ${item.draft.title}`),
        element('p', `Revision ${item.revision.value}`), itemView(item.draft, text => this.render(id.project, text)));
    } catch (error) {
      if (generation === this.generation) {
        this.dialog.body.replaceChildren(); this.dialog.error.textContent = `Cannot open ${itemName(id)}: ${String(error)}`; this.dialog.error.hidden = false;
      }
    }
  }
}
