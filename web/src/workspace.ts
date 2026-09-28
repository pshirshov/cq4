import { button, element } from './editor.js';

type Pane = 'navigation' | 'results';
const MIN_WIDTH: Record<Pane, number> = { navigation: 160, results: 220 };
const DETAIL_MIN_WIDTH = 300;
const SEPARATOR_WIDTH = 8;
const KEYBOARD_STEP = 16;
const NARROW_WIDTH = 920;
const DETAIL_MIN_HEIGHT = 180;
const RESULTS_MIN_HEIGHT = 160;
const STORAGE_KEY = 'cq-workspace-layout';
type Orientation = 'right' | 'bottom';
interface SavedLayout { orientation: Orientation; navigation: number; results: number; resultsHeight: number }

export class Workspace {
  readonly element = element('main', '');
  readonly navigation = element('nav', '');
  readonly results = element('section', '');
  readonly content = element('article', '');
  readonly toggle = button('▭', () => { this.orientation = this.orientation === 'right' ? 'bottom' : 'right'; this.fit(); this.publish(); this.persist(); });
  private orientation: Orientation = 'right';
  private resultsHeight = 320;
  private readonly widths: Record<Pane, number> = { navigation: 200, results: 340 };
  private readonly separators: Record<Pane, HTMLDivElement>;

  constructor(root: HTMLElement, private readonly storage: Storage, private readonly warning: (message: string) => void) {
    this.restore();
    this.element.className = 'workspace';
    for (const [pane, id, label] of [[this.navigation, 'navigation-pane', 'Navigation'], [this.results, 'results-pane', 'Results'],
      [this.content, 'detail-pane', 'Item workspace']] as const) {
      pane.id = id; pane.className = 'pane'; pane.setAttribute('aria-label', label); pane.tabIndex = -1;
    }
    this.separators = { navigation: this.splitter('navigation', this.navigation), results: this.splitter('results', this.results) };
    this.element.append(this.navigation, this.separators.navigation, this.results, this.separators.results, this.content);
    this.publish();
    window.addEventListener('resize', () => {
      this.fit();
    });
    root.addEventListener('keydown', event => {
      if (event.key !== 'F6') return;
      event.preventDefault();
      const panes = [this.navigation, this.results, this.content];
      const index = panes.findIndex(pane => pane.contains(document.activeElement));
      const next = index < 0 ? (event.shiftKey ? panes.length - 1 : 0) : (index + (event.shiftKey ? -1 : 1) + panes.length) % panes.length;
      panes[next].focus();
    });
  }

  fit(): void {
    if (window.innerWidth <= NARROW_WIDTH) return;
    this.resize('navigation', this.widths.navigation); this.resize('results', this.orientation === 'right' ? this.widths.results : this.resultsHeight);
  }

  private maximum(pane: Pane): number {
    if (this.orientation === 'bottom') return pane === 'results'
      ? Math.max(RESULTS_MIN_HEIGHT, this.element.clientHeight - DETAIL_MIN_HEIGHT - SEPARATOR_WIDTH)
      : Math.max(MIN_WIDTH.navigation, this.element.clientWidth - DETAIL_MIN_WIDTH - SEPARATOR_WIDTH);
    const other = pane === 'navigation' ? 'results' : 'navigation';
    return Math.max(MIN_WIDTH[pane], this.element.clientWidth - this.widths[other] - DETAIL_MIN_WIDTH - SEPARATOR_WIDTH * 2);
  }
  private resize(pane: Pane, width: number): void {
    if (pane === 'results' && this.orientation === 'bottom') this.resultsHeight = Math.round(Math.max(RESULTS_MIN_HEIGHT, Math.min(this.maximum(pane), width)));
    else this.widths[pane] = Math.round(Math.max(MIN_WIDTH[pane], Math.min(this.maximum(pane), width)));
    this.publish();
  }
  private size(pane: Pane): number { return pane === 'results' && this.orientation === 'bottom' ? this.resultsHeight : this.widths[pane]; }
  private publish(): void {
    this.element.dataset.orientation = this.orientation; this.element.style.setProperty('--results-height', `${this.resultsHeight}px`);
    this.toggle.setAttribute('aria-label', this.orientation === 'right' ? 'Dock detail below' : 'Dock detail right');
    this.toggle.title = this.toggle.getAttribute('aria-label') as string; this.toggle.textContent = this.orientation === 'right' ? '▭' : '▯'; this.toggle.className = 'dock-detail';
    for (const pane of ['navigation', 'results'] as const) {
      this.element.style.setProperty(`--${pane}-width`, `${this.widths[pane]}px`);
      const separator = this.separators[pane];
      separator.setAttribute('aria-orientation', pane === 'results' && this.orientation === 'bottom' ? 'horizontal' : 'vertical');
      separator.setAttribute('aria-valuemin', String(pane === 'results' && this.orientation === 'bottom' ? RESULTS_MIN_HEIGHT : MIN_WIDTH[pane]));
      separator.setAttribute('aria-valuenow', String(this.size(pane)));
      separator.setAttribute('aria-valuemax', String(this.maximum(pane)));
      separator.setAttribute('aria-valuetext', `${this.size(pane)} pixels`);
    }
  }
  private restore(): void {
    try {
      const raw = this.storage.getItem(STORAGE_KEY); if (raw === null) return;
      const value: Partial<SavedLayout> = JSON.parse(raw);
      if (value === null || (value.orientation !== 'right' && value.orientation !== 'bottom') ||
        ![value.navigation, value.results, value.resultsHeight].every(size => typeof size === 'number' && Number.isFinite(size) && size > 0))
        throw new Error('Invalid saved layout');
      this.orientation = value.orientation; this.widths.navigation = value.navigation as number; this.widths.results = value.results as number; this.resultsHeight = value.resultsHeight as number;
    } catch { this.warning('Saved pane layout could not be read; using the default layout.'); }
  }
  private persist(): void {
    const value: SavedLayout = { orientation: this.orientation, navigation: this.widths.navigation, results: this.widths.results, resultsHeight: this.resultsHeight };
    try { this.storage.setItem(STORAGE_KEY, JSON.stringify(value)); }
    catch { this.warning('Pane layout could not be saved in this browser.'); }
  }
  private splitter(pane: Pane, controlled: HTMLElement): HTMLDivElement {
    const separator = element('div', ''); separator.className = 'pane-separator'; separator.tabIndex = 0;
    separator.setAttribute('role', 'separator'); separator.setAttribute('aria-orientation', 'vertical');
    separator.setAttribute('aria-label', `Resize ${pane}`); separator.setAttribute('aria-controls', controlled.id);
    separator.setAttribute('aria-valuemin', String(MIN_WIDTH[pane]));
    let drag: { pointer: number; x: number; width: number } | null = null;
    separator.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      event.preventDefault(); separator.focus(); separator.setPointerCapture(event.pointerId);
      drag = { pointer: event.pointerId, x: pane === 'results' && this.orientation === 'bottom' ? event.clientY : event.clientX, width: this.size(pane) };
    });
    separator.addEventListener('pointermove', event => {
      if (drag !== null && drag.pointer === event.pointerId) this.resize(pane, drag.width + (pane === 'results' && this.orientation === 'bottom' ? event.clientY : event.clientX) - drag.x);
    });
    separator.addEventListener('lostpointercapture', () => { drag = null; this.persist(); });
    separator.addEventListener('keydown', event => {
      const horizontal = pane === 'results' && this.orientation === 'bottom';
      const target = ([[horizontal ? 'ArrowUp' : 'ArrowLeft', this.size(pane) - KEYBOARD_STEP], [horizontal ? 'ArrowDown' : 'ArrowRight', this.size(pane) + KEYBOARD_STEP],
        ['Home', horizontal ? RESULTS_MIN_HEIGHT : MIN_WIDTH[pane]], ['End', this.maximum(pane)]] as const).find(([key]) => key === event.key);
      if (target !== undefined) { event.preventDefault(); this.resize(pane, target[1]); this.persist(); }
    });
    return separator;
  }
}
