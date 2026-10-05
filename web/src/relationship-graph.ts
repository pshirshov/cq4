import * as api from '../../generated/typescript/cq/api/index.js';
import { Dialog } from './dialog.js';
import { button, element } from './editor.js';
import { itemName } from './items.js';
import { faultMessage } from './faults.js';
import { uuidV4 } from './uuid.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const INCOMING: Partial<Record<api.Relation, api.Relation>> = {
  BlockedBy: api.Relation.Blocks, DerivedFrom: api.Relation.Produces, ReviewedBy: api.Relation.Reviews, SupportedBy: api.Relation.Supports,
  ContradictedBy: api.Relation.Contradicts, SupersededBy: api.Relation.Supersedes,
};
// The neighbours of a page stand on the perimeter of a 4×4 grid whose middle 2×2 block holds the centre item, so every edge runs from
// the centre to its neighbour without passing another node. Each side lists its [column, row] cells from the top to the bottom.
type Slot = readonly [number, number];
const WEST: readonly Slot[] = [[2, 1], [1, 1], [1, 2], [1, 3], [1, 4], [2, 4]];
const EAST: readonly Slot[] = [[3, 1], [4, 1], [4, 2], [4, 3], [4, 4], [3, 4]];
const PAGE_SIZE = WEST.length + EAST.length;
const ARROWS: Record<string, readonly [number, number]> = { ArrowRight: [1, 0], ArrowLeft: [-1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
const FILTERS = [['all', 'All relationships'], ['dependencies', 'Dependencies and dependents']] as const;
interface GraphEffects {
  call(command: api.Command): Promise<api.Result>;
  select(id: api.ItemId): Promise<void>;
}
interface DrawnEdge { node: HTMLElement; incoming: boolean; symmetric: boolean }

/**
 * The cells of `count` neighbours whose side is `own`, from the top, while `opposite` neighbours stand on `other`. A group of at most
 * one side is centred on it. A larger group keeps its side and continues along the top and the bottom into the cells of the other
 * side nearest to it, which the smaller group leaves free.
 */
function cells(own: readonly Slot[], other: readonly Slot[], count: number, opposite: number): Slot[] {
  const over = Math.max(0, count - own.length);
  if (over > 0) return [...other.slice(0, Math.ceil(over / 2)).reverse(), ...own, ...other.slice(other.length - Math.floor(over / 2)).reverse()];
  const lost = Math.max(0, opposite - other.length);
  const free = own.slice(Math.ceil(lost / 2), own.length - Math.floor(lost / 2));
  const start = Math.floor((free.length - count) / 2);
  return free.slice(start, start + count);
}

export class RelationshipGraph {
  readonly dialog = new Dialog('large', () => { this.generation++; this.observer.disconnect(); this.history = []; });
  private readonly filter = element('select', '');
  private readonly canvas = element('div', '');
  private readonly observed = element('p', '');
  private readonly hint = element('p', '');
  private readonly paging = element('nav', '');
  private readonly back = button('Back in graph', () => this.action(async () => {
    const id = this.history.pop(); if (id !== undefined) await this.load(id, false);
  }));
  private readonly observer = new ResizeObserver(() => this.draw());
  private readonly marker = `relationship-arrow-${uuidV4(crypto)}`;
  private view: api.ItemView | null = null;
  private history: api.ItemId[] = [];
  private generation = 0;
  private offset = 0;
  private root: HTMLElement | null = null;
  private edges: DrawnEdge[] = [];
  private svg: SVGSVGElement | null = null;

  constructor(private readonly effects: GraphEffects) {
    this.canvas.className = 'relationship-graph'; this.canvas.setAttribute('aria-label', 'Directed item relationships');
    this.canvas.addEventListener('keydown', event => this.move(event));
    this.observed.setAttribute('role', 'status'); this.paging.setAttribute('aria-label', 'Relationship pages');
    this.hint.className = 'graph-hint'; this.hint.hidden = true;
    const label = element('label', 'Show '); this.filter.setAttribute('aria-label', 'Graph relationships');
    for (const [value, text] of FILTERS) {
      const option = element('option', text); option.value = value; this.filter.append(option);
    }
    label.append(this.filter);
    this.filter.addEventListener('change', () => { this.offset = 0; this.action(() => this.render()); });
    this.dialog.actions.append(this.back, button('Refresh graph', () => this.action(async () => {
      if (this.view !== null) await this.load(this.view.item.id, false);
    })), button('Open item', () => this.action(async () => {
      if (this.view === null) return;
      const id = this.view.item.id; this.dialog.close(); await this.effects.select(id);
    })));
    const controls = element('div', ''); controls.className = 'graph-controls';
    controls.append(label, element('p', 'Arrows follow the named relationship; Blocks points from prerequisite to dependent. Incoming relationships (bar on the left edge) stand to the left ' +
      'of the item, outgoing ones (bar on the right edge) to the right. Select a node to center its graph; arrow keys move between nodes.'));
    this.dialog.body.classList.add('graph-body');
    this.dialog.body.append(controls, this.observed, this.hint, this.canvas, this.paging);
  }
  open(id: api.ItemId): void {
    this.history = []; this.view = null; this.filter.value = FILTERS[0][0]; this.offset = 0;
    this.dialog.open(`Relationships · ${itemName(id)}`); this.action(() => this.load(id, false));
  }
  private action(effect: () => Promise<void>): void {
    void effect().catch(error => { if (this.dialog.element.open) { this.dialog.error.textContent = String(error); this.dialog.error.hidden = false; } });
  }
  private async request(command: api.Command, generation: number): Promise<api.Result | null> {
    try {
      const result = await this.effects.call(command);
      return generation === this.generation && this.dialog.element.open ? result : null;
    } catch (error) {
      if (generation === this.generation && this.dialog.element.open) throw error;
      return null;
    }
  }
  private clear(): void {
    this.dialog.error.hidden = true; this.hint.hidden = true; this.hint.replaceChildren();
    this.canvas.replaceChildren(); this.paging.replaceChildren(); this.observer.disconnect(); this.root = null;
  }
  private async load(id: api.ItemId, remember: boolean): Promise<void> {
    const generation = ++this.generation;
    this.clear(); this.observed.textContent = `Loading ${itemName(id)}…`;
    const result = await this.request(new api.Command_Read(new api.ReadInput(id.project, new api.ReadSelection_ItemDetail(id))), generation);
    if (result === null) return;
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    if (!(result instanceof api.Result_Detail)) throw new Error('Unexpected graph item response');
    if (remember && this.view !== null) this.history.push(this.view.item.id);
    this.view = result.view; this.offset = 0; this.back.disabled = this.history.length === 0;
    this.dialog.open(`Relationships · ${itemName(id)}`); await this.render();
  }
  private async render(): Promise<void> {
    const view = this.view; if (view === null) return;
    const generation = ++this.generation;
    this.clear();
    const dependencies = this.filter.value === 'dependencies';
    const refs = view.refs.filter(ref => !dependencies || ref.relation === 'Blocks' || ref.relation === 'BlockedBy')
      .sort((a, b) => itemName(a.target).localeCompare(itemName(b.target), undefined, { numeric: true }) || a.relation.localeCompare(b.relation));
    const page = refs.slice(this.offset, this.offset + PAGE_SIZE);
    const titles = new Map<string, api.ItemSummary>();
    if (page.length > 0) {
      const ids = [...new Set(page.map(ref => itemName(ref.target)))];
      const result = await this.request(new api.Command_Search(new api.SearchInput(view.item.id.project,
        `(${ids.join(' OR ')}) archived:all`, undefined, undefined, PAGE_SIZE)), generation);
      if (result === null) return;
      if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      if (!(result instanceof api.Result_Found)) throw new Error('Unexpected graph labels response');
      if (result.page.hasMore) throw new Error('Graph labels exceeded the response limit. Refresh after reducing item title sizes.');
      for (const item of result.page.items) titles.set(itemName(item.id), item);
    }
    this.observed.textContent = `${itemName(view.item.id)} · revision ${view.item.revision.value}. ${refs.length} ${dependencies ? 'dependency' : 'relationship'} edges. Refresh to read newer relationships.`;
    if (refs.length === 0 && view.refs.length > 0) {
      this.hint.append(element('span', `This item has no dependency edges. ${FILTERS[0][1]} has ${view.refs.length} ${view.refs.length === 1 ? 'edge' : 'edges'}.`),
        button(`Show ${FILTERS[0][1].toLowerCase()}`, () => { this.filter.value = FILTERS[0][0]; this.offset = 0; this.action(() => this.render()); }));
      this.hint.hidden = false;
    }
    this.root = element('article', ''); this.root.className = 'graph-node graph-root'; this.root.tabIndex = -1;
    const title = element('h3', `${itemName(view.item.id)} · ${view.item.draft.title}`); title.title = title.textContent; this.root.append(title);
    // Within a page the neighbours of one relation stand together; the page itself is cut from the order by identity.
    const grouped = (incoming: boolean) => page.filter(ref => (INCOMING[ref.relation] !== undefined) === incoming)
      .sort((a, b) => a.relation.localeCompare(b.relation));
    const draw = (refs: api.ItemRef[], incoming: boolean, slots: Slot[]): HTMLElement[] => refs.map((ref, index) => {
      const name = itemName(ref.target); const summary = titles.get(name);
      const inverse = INCOMING[ref.relation];
      const node = element('article', ''); node.className = 'graph-node'; node.dataset.direction = incoming ? 'incoming' : ref.relation === 'RelatesTo' ? 'related' : 'outgoing';
      node.style.setProperty('--graph-column', String(slots[index][0])); node.style.setProperty('--graph-row', String(slots[index][1]));
      const relation = element('p', ''); relation.className = 'graph-relation';
      relation.append(element('span', inverse !== undefined ? `${name} ${inverse} ${itemName(view.item.id)}` : `${itemName(view.item.id)} ${ref.relation} ${name}`));
      node.append(relation);
      if (summary === undefined) node.append(element('p', `${name} · unavailable in the current search observation`));
      else {
        const open = button('', () => this.action(() => this.load(ref.target, true)));
        const text = element('span', `${name} · ${summary.title}`); text.className = 'graph-title'; open.append(text); open.title = text.textContent;
        relation.append(element('span', `${summary.status}${summary.archived ? ' · archived' : ''}`)); node.append(open);
      }
      this.edges.push({ node, incoming, symmetric: ref.relation === 'RelatesTo' });
      return node;
    });
    this.edges = [];
    const incoming = grouped(true); const outgoing = grouped(false);
    this.svg = document.createElementNS(SVG_NS, 'svg'); this.svg.setAttribute('aria-hidden', 'true'); this.svg.classList.add('graph-arrows');
    this.canvas.append(this.svg, ...draw(incoming, true, cells(WEST, EAST, incoming.length, outgoing.length)), this.root,
      ...draw(outgoing, false, cells(EAST, WEST, outgoing.length, incoming.length)));
    const previous = button('Previous relationships', () => { this.offset -= PAGE_SIZE; this.action(() => this.render()); }); previous.disabled = this.offset === 0;
    const next = button('Next relationships', () => { this.offset += PAGE_SIZE; this.action(() => this.render()); }); next.disabled = this.offset + PAGE_SIZE >= refs.length;
    this.paging.append(previous, element('span', refs.length === 0 ? 'No edges' : `Edges ${this.offset + 1}–${Math.min(this.offset + PAGE_SIZE, refs.length)} of ${refs.length}`), next);
    this.observer.observe(this.canvas); for (const edge of this.edges) this.observer.observe(edge.node);
    this.observer.observe(this.root); this.draw(); this.root.focus();
  }
  /** An arrow key moves focus from a node to the nearest node within 45° of the key's direction, by the distance between node centres. */
  private move(event: KeyboardEvent): void {
    const direction = ARROWS[event.key];
    if (direction === undefined || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const stops = [...this.canvas.querySelectorAll<HTMLElement>('.graph-root, .graph-node button')];
    const current = stops.find(stop => stop === document.activeElement); if (current === undefined) return;
    const centre = (stop: HTMLElement): [number, number] => {
      const bounds = (stop.closest('.graph-node') as HTMLElement).getBoundingClientRect(); return [bounds.left + bounds.width / 2, bounds.top + bounds.height / 2];
    };
    const [x, y] = centre(current); let nearest: HTMLElement | null = null; let least = Infinity;
    for (const stop of stops) {
      if (stop === current) continue;
      const [dx, dy] = [centre(stop)[0] - x, centre(stop)[1] - y];
      const along = dx * direction[0] + dy * direction[1]; const across = Math.abs(dx * direction[1] - dy * direction[0]);
      const distance = Math.hypot(dx, dy);
      if (along > 0 && across <= along && distance < least) { nearest = stop; least = distance; }
    }
    if (nearest !== null) { event.preventDefault(); nearest.focus(); }
  }
  private draw(): void {
    const root = this.root; const svg = this.svg;
    if (root === null || svg === null || !root.isConnected) return;
    const bounds = this.canvas.getBoundingClientRect(); const rootBounds = root.getBoundingClientRect();
    svg.setAttribute('viewBox', `0 0 ${bounds.width} ${bounds.height}`); svg.replaceChildren();
    const defs = document.createElementNS(SVG_NS, 'defs'); const marker = document.createElementNS(SVG_NS, 'marker');
    marker.id = this.marker; marker.setAttribute('viewBox', '0 0 10 10'); marker.setAttribute('refX', '9'); marker.setAttribute('refY', '5');
    marker.setAttribute('markerWidth', '7'); marker.setAttribute('markerHeight', '7'); marker.setAttribute('orient', 'auto-start-reverse');
    const arrow = document.createElementNS(SVG_NS, 'path'); arrow.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z'); marker.append(arrow); defs.append(marker); svg.append(defs);
    const centre = (box: DOMRect): [number, number] => [box.left + box.width / 2, box.top + box.height / 2];
    // The point where the segment from the centre of `box` towards `target` leaves the box.
    const edge = (box: DOMRect, target: [number, number]): [number, number] => {
      const [x, y] = centre(box); const [dx, dy] = [target[0] - x, target[1] - y];
      const scale = Math.min(dx === 0 ? Infinity : box.width / 2 / Math.abs(dx), dy === 0 ? Infinity : box.height / 2 / Math.abs(dy));
      return [x + dx * scale, y + dy * scale];
    };
    for (const drawn of this.edges) {
      const neighbor = drawn.node.getBoundingClientRect();
      if (centre(neighbor).every((value, index) => value === centre(rootBounds)[index])) continue;
      const rootPoint = edge(rootBounds, centre(neighbor)); const nodePoint = edge(neighbor, centre(rootBounds));
      const [source, target] = drawn.incoming ? [nodePoint, rootPoint] : [rootPoint, nodePoint];
      const line = document.createElementNS(SVG_NS, 'line');
      line.setAttribute('x1', String(source[0] - bounds.left)); line.setAttribute('y1', String(source[1] - bounds.top));
      line.setAttribute('x2', String(target[0] - bounds.left)); line.setAttribute('y2', String(target[1] - bounds.top));
      if (!drawn.symmetric) line.setAttribute('marker-end', `url(#${this.marker})`);
      svg.append(line);
    }
  }
}
