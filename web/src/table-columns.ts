import { element } from './editor.js';

const TITLE = 1;
const TITLE_NEIGHBOR = 2;
// Wide enough for about five words a line beside an open item pane; the other columns keep their minima and the pane scrolls to them.
const MIN_TITLE = 200;
const MAX_WIDTH = 1200;
const STEP = 16;

export interface TableColumn { header: HTMLTableCellElement; label: string }

export class TableColumns {
  private readonly columns: HTMLTableColElement[];
  private readonly handles: HTMLDivElement[];
  private readonly overrides: Array<number | null>;
  private minima: number[];
  private widths: number[];
  private readonly resizeObserver: ResizeObserver;
  private readonly mutationObserver: MutationObserver;
  private frame: number | null = null;
  private destroyed = false;
  private readonly refresh = (): void => {
    if (this.destroyed || this.frame !== null) return;
    this.frame = requestAnimationFrame(() => { this.frame = null; this.measure(); });
  };

  constructor(private readonly table: HTMLTableElement, private readonly headers: TableColumn[], private readonly pane: HTMLElement, body: HTMLTableSectionElement) {
    this.overrides = headers.map(() => null); this.minima = headers.map(() => 0); this.widths = headers.map(() => 0);
    const group = element('colgroup', ''); this.columns = headers.map(() => element('col', '')); group.append(...this.columns); table.prepend(group);
    this.handles = headers.map(({ header, label }, index) => {
      const handle = element('div', ''); handle.className = 'column-resizer'; handle.tabIndex = 0;
      handle.setAttribute('role', 'separator'); handle.setAttribute('aria-orientation', 'vertical');
      handle.setAttribute('aria-label', `Resize ${label} column`);
      const resize = (width: number): void => {
        if (index === TITLE) this.overrides[TITLE_NEIGHBOR] = Math.max(this.minima[TITLE_NEIGHBOR], this.widths[TITLE_NEIGHBOR] + this.widths[TITLE] - Math.max(MIN_TITLE, width));
        else this.overrides[index] = Math.min(MAX_WIDTH, Math.max(this.minima[index], width));
        this.publish();
      };
      let drag: { pointer: number; x: number; width: number } | null = null;
      handle.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        event.preventDefault(); event.stopPropagation(); handle.focus(); handle.setPointerCapture(event.pointerId);
        drag = { pointer: event.pointerId, x: event.clientX, width: this.widths[index] };
      });
      handle.addEventListener('pointermove', event => { if (drag !== null && drag.pointer === event.pointerId) resize(drag.width + event.clientX - drag.x); });
      handle.addEventListener('pointerup', event => { if (drag !== null && drag.pointer === event.pointerId) handle.releasePointerCapture(event.pointerId); });
      handle.addEventListener('lostpointercapture', () => { drag = null; });
      handle.addEventListener('keydown', event => {
        const width = event.key === 'ArrowLeft' ? this.widths[index] - STEP : event.key === 'ArrowRight' ? this.widths[index] + STEP
          : event.key === 'Home' ? this.minima[index] : event.key === 'End' ? Number(handle.getAttribute('aria-valuemax')) : null;
        if (width !== null) { event.preventDefault(); resize(width); }
      });
      header.append(handle); return handle;
    });
    this.resizeObserver = new ResizeObserver(this.refresh); this.resizeObserver.observe(pane);
    this.mutationObserver = new MutationObserver(this.refresh); this.mutationObserver.observe(body, {childList: true, subtree: true, characterData: true});
    table.ownerDocument.fonts.addEventListener('loadingdone', this.refresh);
    void table.ownerDocument.fonts.ready.then(this.refresh); this.refresh();
  }

  destroy(): void {
    this.destroyed = true; this.resizeObserver.disconnect(); this.mutationObserver.disconnect();
    this.table.ownerDocument.fonts.removeEventListener('loadingdone', this.refresh);
    if (this.frame !== null) cancelAnimationFrame(this.frame);
  }

  private measure(): void {
    if (!this.table.isConnected) return;
    const sample = this.table.cloneNode(true) as HTMLTableElement;
    sample.removeAttribute('aria-label'); sample.setAttribute('aria-hidden', 'true'); sample.inert = true;
    sample.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
    sample.querySelectorAll('colgroup, .column-resizer').forEach(node => node.remove());
    sample.querySelectorAll('tbody tr').forEach(row => {
      const cells = row.children;
      if (cells.length === this.headers.length) cells[TITLE].replaceChildren(); else row.remove();
    });
    Object.assign(sample.style, {position: 'fixed', visibility: 'hidden', pointerEvents: 'none', left: '0', top: '0', tableLayout: 'auto', width: 'max-content'});
    this.pane.append(sample);
    this.minima = [...sample.querySelectorAll('th')].map(cell => Math.ceil(cell.getBoundingClientRect().width));
    sample.remove(); this.minima[TITLE] = Math.max(MIN_TITLE, this.minima[TITLE]); this.publish();
  }

  private publish(): void {
    const minimum = this.minima.reduce((sum, value) => sum + value, 0);
    const available = Math.max(this.pane.clientWidth, minimum);
    const extra = this.overrides.map((value, index) => index === TITLE || value === null ? 0 : Math.max(0, value - this.minima[index]));
    const requested = extra.reduce((sum, value) => sum + value, 0);
    const scale = requested === 0 ? 1 : Math.min(1, (available - minimum) / requested);
    this.widths = this.minima.map((value, index) => value + extra[index] * scale);
    this.widths[TITLE] += available - this.widths.reduce((sum, value) => sum + value, 0);
    this.table.style.width = `${available}px`;
    for (const [index, column] of this.columns.entries()) {
      column.style.width = `${this.widths[index]}px`;
      this.handles[index].setAttribute('aria-valuemin', String(this.minima[index]));
      this.handles[index].setAttribute('aria-valuemax', String(index === TITLE ? this.widths[TITLE] + this.widths[TITLE_NEIGHBOR] - this.minima[TITLE_NEIGHBOR] : Math.min(MAX_WIDTH, this.widths[index] + this.widths[TITLE] - this.minima[TITLE])));
      this.handles[index].setAttribute('aria-valuenow', String(this.widths[index]));
    }
  }
}
