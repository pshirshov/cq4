import * as api from '../../generated/typescript/cq/api/index.js';
import { Dialog } from './dialog.js';
import { button, element } from './editor.js';
import { itemName } from './items.js';
import { faultMessage } from './faults.js';
import { uuidV4 } from './uuid.js';

const PAGE_SIZE = 12;
const SVG_NS = 'http://www.w3.org/2000/svg';
const INCOMING: Partial<Record<api.Relation, api.Relation>> = {
  BlockedBy: api.Relation.Blocks, DerivedFrom: api.Relation.Produces, ReviewedBy: api.Relation.Reviews, SupportedBy: api.Relation.Supports,
  ContradictedBy: api.Relation.Contradicts, SupersededBy: api.Relation.Supersedes,
};
interface GraphEffects {
  call(command: api.Command): Promise<api.Result>;
  select(id: api.ItemId): Promise<void>;
}
interface DrawnEdge { node: HTMLElement; incoming: boolean; symmetric: boolean }

export class RelationshipGraph {
  readonly dialog = new Dialog('large', () => { this.generation++; this.observer.disconnect(); this.history = []; });
  private readonly filter = element('select', '');
  private readonly canvas = element('div', '');
  private readonly observed = element('p', '');
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
    this.observed.setAttribute('role', 'status'); this.paging.setAttribute('aria-label', 'Relationship pages');
    const label = element('label', 'Show '); this.filter.setAttribute('aria-label', 'Graph relationships');
    for (const [value, text] of [['dependencies', 'Dependencies and dependents'], ['all', 'All relationships']]) {
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
    this.dialog.body.append(label, element('p', 'Arrows follow the named relationship. Blocks points from prerequisite to dependent. Select a node to center its graph.'),
      this.observed, this.canvas, this.paging);
  }
  open(id: api.ItemId): void {
    this.history = []; this.view = null; this.filter.value = 'dependencies'; this.offset = 0;
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
  private async load(id: api.ItemId, remember: boolean): Promise<void> {
    const generation = ++this.generation;
    this.dialog.error.hidden = true; this.observed.textContent = `Loading ${itemName(id)}…`;
    this.canvas.replaceChildren(); this.paging.replaceChildren(); this.observer.disconnect(); this.root = null;
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
    this.dialog.error.hidden = true; this.canvas.replaceChildren(); this.paging.replaceChildren(); this.observer.disconnect(); this.root = null;
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
    const left = element('section', ''); const right = element('section', ''); const center = element('section', '');
    left.className = 'graph-neighbors'; right.className = 'graph-neighbors'; center.className = 'graph-center';
    left.append(element('h3', dependencies ? 'Prerequisites' : 'Incoming')); right.append(element('h3', dependencies ? 'Dependents' : 'Outgoing / related'));
    this.root = element('article', ''); this.root.className = 'graph-node graph-root'; this.root.tabIndex = -1;
    this.root.append(element('h3', `${itemName(view.item.id)} · ${view.item.draft.title}`)); center.append(this.root);
    this.edges = [];
    for (const ref of page) {
      const name = itemName(ref.target); const summary = titles.get(name);
      const inverse = INCOMING[ref.relation]; const incoming = inverse !== undefined;
      const text = incoming ? `${name} ${inverse} ${itemName(view.item.id)}` : `${itemName(view.item.id)} ${ref.relation} ${name}`;
      const node = element('article', ''); node.className = 'graph-node';
      node.append(element('p', text));
      if (summary === undefined) node.append(element('p', `${name} · unavailable in the current search observation`));
      else {
        node.append(button(`${name} · ${summary.title}`, () => this.action(() => this.load(ref.target, true))),
          element('p', `${summary.status}${summary.archived ? ' · archived' : ''}`));
      }
      (incoming ? left : right).append(node); this.edges.push({ node, incoming, symmetric: ref.relation === 'RelatesTo' });
    }
    if (left.childElementCount === 1) left.append(element('p', 'None on this page.'));
    if (right.childElementCount === 1) right.append(element('p', 'None on this page.'));
    this.svg = document.createElementNS(SVG_NS, 'svg'); this.svg.setAttribute('aria-hidden', 'true'); this.svg.classList.add('graph-arrows');
    this.canvas.append(this.svg, left, center, right);
    const previous = button('Previous relationships', () => { this.offset -= PAGE_SIZE; this.action(() => this.render()); }); previous.disabled = this.offset === 0;
    const next = button('Next relationships', () => { this.offset += PAGE_SIZE; this.action(() => this.render()); }); next.disabled = this.offset + PAGE_SIZE >= refs.length;
    this.paging.append(previous, element('span', refs.length === 0 ? 'No edges' : `Edges ${this.offset + 1}–${Math.min(this.offset + PAGE_SIZE, refs.length)} of ${refs.length}`), next);
    this.observer.observe(this.canvas); for (const edge of this.edges) this.observer.observe(edge.node);
    this.observer.observe(this.root); this.draw(); this.root.focus();
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
    for (const edge of this.edges) {
      const neighbor = edge.node.getBoundingClientRect(); const stacked = neighbor.right > rootBounds.left && neighbor.left < rootBounds.right;
      const rootPoint = stacked ? [rootBounds.left + rootBounds.width / 2, neighbor.top < rootBounds.top ? rootBounds.top : rootBounds.bottom]
        : [edge.incoming ? rootBounds.left : rootBounds.right, rootBounds.top + rootBounds.height / 2];
      const nodePoint = stacked ? [neighbor.left + neighbor.width / 2, neighbor.top < rootBounds.top ? neighbor.bottom : neighbor.top]
        : [edge.incoming ? neighbor.right : neighbor.left, neighbor.top + neighbor.height / 2];
      const [source, target] = edge.incoming ? [nodePoint, rootPoint] : [rootPoint, nodePoint];
      const line = document.createElementNS(SVG_NS, 'line');
      line.setAttribute('x1', String(source[0] - bounds.left)); line.setAttribute('y1', String(source[1] - bounds.top));
      line.setAttribute('x2', String(target[0] - bounds.left)); line.setAttribute('y2', String(target[1] - bounds.top));
      if (!edge.symmetric) line.setAttribute('marker-end', `url(#${this.marker})`);
      svg.append(line);
    }
  }
}
