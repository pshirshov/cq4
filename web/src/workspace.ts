import { button, element } from './editor.js';

const NAVIGATION_WIDTH = 200;
const RESULTS_MIN_WIDTH = 220;
const DETAIL_MIN_WIDTH = 300;
const SEPARATOR_WIDTH = 8;
const KEYBOARD_STEP = 16;
const NARROW_WIDTH = 920;
const DETAIL_MIN_HEIGHT = 180;
const RESULTS_MIN_HEIGHT = 160;
const STORAGE_KEY = 'cq-workspace-layout';
type Orientation = 'right' | 'bottom';
interface SavedLayout { orientation: Orientation; results: number; resultsHeight: number }

export class Workspace {
  readonly element = element('main', '');
  readonly navigation = element('nav', '');
  readonly results = element('section', '');
  readonly content = element('article', '');
  readonly toggle = button('▭', () => { this.orientation = this.orientation === 'right' ? 'bottom' : 'right'; this.fit(); this.publish(); this.persist(); });
  private orientation: Orientation = 'right';
  private detailOpen = true;
  private resultsHeight = 320;
  private resultsWidth = 340;
  private readonly separator: HTMLDivElement;

  constructor(root: HTMLElement, private readonly storage: Storage, private readonly warning: (message: string) => void) {
    this.restore();
    this.element.className = 'workspace'; this.element.dataset.detail = 'open';
    for (const [pane, id, label] of [[this.navigation, 'navigation-pane', 'Navigation'], [this.results, 'results-pane', 'Results'],
      [this.content, 'detail-pane', 'Item workspace']] as const) {
      pane.id = id; pane.className = 'pane'; pane.setAttribute('aria-label', label); pane.tabIndex = -1;
    }
    this.separator = this.splitter();
    this.element.append(this.navigation, this.results, this.separator, this.content);
    this.publish();
    window.addEventListener('resize', () => {
      this.fit();
    });
    root.addEventListener('keydown', event => {
      if (event.key !== 'F6') return;
      event.preventDefault();
      const panes = [this.navigation, this.results, this.content].filter(pane => pane !== this.content || this.detailOpen);
      const index = panes.findIndex(pane => pane.contains(document.activeElement));
      const next = index < 0 ? (event.shiftKey ? panes.length - 1 : 0) : (index + (event.shiftKey ? -1 : 1) + panes.length) % panes.length;
      panes[next].focus();
    });
  }

  get detailVisible(): boolean { return this.detailOpen; }
  setDetailOpen(open: boolean): void {
    if (this.detailOpen === open) return;
    this.detailOpen = open; this.content.hidden = !open; this.separator.hidden = !open;
    this.element.dataset.detail = open ? 'open' : 'closed'; this.fit();
  }

  fit(): void {
    if (window.innerWidth <= NARROW_WIDTH) return;
    this.resize(this.size());
  }

  private maximum(): number {
    if (!this.detailOpen) return this.orientation === 'bottom' ? Math.max(RESULTS_MIN_HEIGHT, this.element.clientHeight) : Math.max(RESULTS_MIN_WIDTH, this.element.clientWidth - NAVIGATION_WIDTH);
    return this.orientation === 'bottom'
      ? Math.max(RESULTS_MIN_HEIGHT, this.element.clientHeight - DETAIL_MIN_HEIGHT - SEPARATOR_WIDTH)
      : Math.max(RESULTS_MIN_WIDTH, this.element.clientWidth - NAVIGATION_WIDTH - DETAIL_MIN_WIDTH - SEPARATOR_WIDTH);
  }
  private resize(size: number): void {
    if (this.orientation === 'bottom') this.resultsHeight = Math.round(Math.max(RESULTS_MIN_HEIGHT, Math.min(this.maximum(), size)));
    else this.resultsWidth = Math.round(Math.max(RESULTS_MIN_WIDTH, Math.min(this.maximum(), size)));
    this.publish();
  }
  private size(): number { return this.orientation === 'bottom' ? this.resultsHeight : this.resultsWidth; }
  private publish(): void {
    this.element.dataset.orientation = this.orientation; this.element.style.setProperty('--results-height', `${this.resultsHeight}px`);
    this.element.style.setProperty('--navigation-width', `${NAVIGATION_WIDTH}px`);
    this.element.style.setProperty('--results-width', `${this.resultsWidth}px`);
    this.toggle.setAttribute('aria-label', this.orientation === 'right' ? 'Dock detail below' : 'Dock detail right');
    this.toggle.title = this.toggle.getAttribute('aria-label') as string; this.toggle.textContent = this.orientation === 'right' ? '▭' : '▯'; this.toggle.className = 'dock-detail';
    this.separator.setAttribute('aria-orientation', this.orientation === 'bottom' ? 'horizontal' : 'vertical');
    this.separator.setAttribute('aria-valuemin', String(this.orientation === 'bottom' ? RESULTS_MIN_HEIGHT : RESULTS_MIN_WIDTH));
    this.separator.setAttribute('aria-valuenow', String(this.size()));
    this.separator.setAttribute('aria-valuemax', String(this.maximum()));
    this.separator.setAttribute('aria-valuetext', `${this.size()} pixels`);
  }
  private restore(): void {
    try {
      const raw = this.storage.getItem(STORAGE_KEY); if (raw === null) return;
      const value: Partial<SavedLayout> = JSON.parse(raw);
      if (value === null || (value.orientation !== 'right' && value.orientation !== 'bottom') ||
        ![value.results, value.resultsHeight].every(size => typeof size === 'number' && Number.isFinite(size) && size > 0))
        throw new Error('Invalid saved layout');
      this.orientation = value.orientation; this.resultsWidth = value.results as number; this.resultsHeight = value.resultsHeight as number;
    } catch { this.warning('Saved pane layout could not be read; using the default layout.'); }
  }
  private persist(): void {
    const value: SavedLayout = { orientation: this.orientation, results: this.resultsWidth, resultsHeight: this.resultsHeight };
    try { this.storage.setItem(STORAGE_KEY, JSON.stringify(value)); }
    catch { this.warning('Pane layout could not be saved in this browser.'); }
  }
  private splitter(): HTMLDivElement {
    const separator = element('div', ''); separator.className = 'pane-separator'; separator.tabIndex = 0;
    separator.setAttribute('role', 'separator'); separator.setAttribute('aria-orientation', 'vertical');
    separator.setAttribute('aria-label', 'Resize results'); separator.setAttribute('aria-controls', this.results.id);
    separator.setAttribute('aria-valuemin', String(RESULTS_MIN_WIDTH));
    let drag: { pointer: number; x: number; width: number } | null = null;
    separator.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      event.preventDefault(); separator.focus(); separator.setPointerCapture(event.pointerId);
      drag = { pointer: event.pointerId, x: this.orientation === 'bottom' ? event.clientY : event.clientX, width: this.size() };
    });
    separator.addEventListener('pointermove', event => {
      if (drag !== null && drag.pointer === event.pointerId) this.resize(drag.width + (this.orientation === 'bottom' ? event.clientY : event.clientX) - drag.x);
    });
    separator.addEventListener('lostpointercapture', () => { drag = null; this.persist(); });
    separator.addEventListener('keydown', event => {
      const horizontal = this.orientation === 'bottom';
      const target = ([[horizontal ? 'ArrowUp' : 'ArrowLeft', this.size() - KEYBOARD_STEP], [horizontal ? 'ArrowDown' : 'ArrowRight', this.size() + KEYBOARD_STEP],
        ['Home', horizontal ? RESULTS_MIN_HEIGHT : RESULTS_MIN_WIDTH], ['End', this.maximum()]] as const).find(([key]) => key === event.key);
      if (target !== undefined) { event.preventDefault(); this.resize(target[1]); this.persist(); }
    });
    return separator;
  }
}
