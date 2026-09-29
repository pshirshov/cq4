import { element } from './editor.js';

const STORAGE_KEY = 'cq-item-column-widths';
const MIN_WIDTH = 30;
const MAX_WIDTH = 1200;
const STEP = 16;
const INITIAL_WIDTHS = [50, 38, 180, 82, 70, 130];

export class TableColumns {
  constructor(table: HTMLTableElement, headers: HTMLTableCellElement[], storage: Storage, warning: (message: string) => void) {
    let widths = [...INITIAL_WIDTHS];
    try {
      const saved = storage.getItem(STORAGE_KEY);
      if (saved !== null) {
        const value: unknown = JSON.parse(saved);
        if (!Array.isArray(value) || value.length !== headers.length || !value.every(size => typeof size === 'number' && Number.isFinite(size) && size >= MIN_WIDTH && size <= MAX_WIDTH))
          throw new Error('Invalid column widths');
        widths = value;
      }
    } catch { warning('Saved table widths could not be read; using initial widths.'); }
    const group = element('colgroup', '');
    const columns = headers.map(() => element('col', '')); group.append(...columns); table.prepend(group);
    const handles = headers.map((header, index) => {
      const handle = element('div', ''); handle.className = 'column-resizer'; handle.tabIndex = 0;
      handle.setAttribute('role', 'separator'); handle.setAttribute('aria-orientation', 'vertical');
      handle.setAttribute('aria-label', `Resize ${header.textContent} column`);
      handle.setAttribute('aria-valuemin', String(MIN_WIDTH)); handle.setAttribute('aria-valuemax', String(MAX_WIDTH));
      const resize = (width: number): void => { widths[index] = Math.round(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width))); publish(); };
      let drag: { pointer: number; x: number; width: number } | null = null;
      handle.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        event.preventDefault(); event.stopPropagation(); handle.focus(); handle.setPointerCapture(event.pointerId);
        drag = { pointer: event.pointerId, x: event.clientX, width: widths[index] };
      });
      handle.addEventListener('pointermove', event => { if (drag !== null && drag.pointer === event.pointerId) resize(drag.width + event.clientX - drag.x); });
      handle.addEventListener('pointerup', event => { if (drag !== null && drag.pointer === event.pointerId) handle.releasePointerCapture(event.pointerId); });
      handle.addEventListener('lostpointercapture', () => { drag = null; persist(); });
      handle.addEventListener('keydown', event => {
        const width = event.key === 'ArrowLeft' ? widths[index] - STEP : event.key === 'ArrowRight' ? widths[index] + STEP
          : event.key === 'Home' ? MIN_WIDTH : event.key === 'End' ? MAX_WIDTH : null;
        if (width !== null) { event.preventDefault(); resize(width); persist(); }
      });
      header.append(handle); return handle;
    });
    const persist = (): void => {
      try { storage.setItem(STORAGE_KEY, JSON.stringify(widths)); }
      catch { warning('Table widths could not be saved in this browser.'); }
    };
    const publish = (): void => {
      table.style.width = `${widths.reduce((a, b) => a + b, 0)}px`;
      for (const [index, column] of columns.entries()) {
        column.style.width = `${widths[index]}px`; handles[index].setAttribute('aria-valuenow', String(widths[index]));
      }
    };
    publish();
  }
}
