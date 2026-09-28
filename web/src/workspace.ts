import { element } from './editor.js';

type Pane = 'navigation' | 'results';
const MIN_WIDTH: Record<Pane, number> = { navigation: 160, results: 220 };
const DETAIL_MIN_WIDTH = 300;
const SEPARATOR_WIDTH = 8;
const KEYBOARD_STEP = 16;
const NARROW_WIDTH = 920;

export class Workspace {
  readonly element = element('main', '');
  readonly navigation = element('nav', '');
  readonly results = element('section', '');
  readonly content = element('article', '');
  private readonly widths: Record<Pane, number> = { navigation: 200, results: 340 };
  private readonly separators: Record<Pane, HTMLDivElement>;

  constructor(root: HTMLElement) {
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
    this.resize('navigation', this.widths.navigation); this.resize('results', this.widths.results);
  }

  private maximum(pane: Pane): number {
    const other = pane === 'navigation' ? 'results' : 'navigation';
    return Math.max(MIN_WIDTH[pane], this.element.clientWidth - this.widths[other] - DETAIL_MIN_WIDTH - SEPARATOR_WIDTH * 2);
  }
  private resize(pane: Pane, width: number): void {
    this.widths[pane] = Math.round(Math.max(MIN_WIDTH[pane], Math.min(this.maximum(pane), width))); this.publish();
  }
  private publish(): void {
    for (const pane of ['navigation', 'results'] as const) {
      this.element.style.setProperty(`--${pane}-width`, `${this.widths[pane]}px`);
      const separator = this.separators[pane];
      separator.setAttribute('aria-valuenow', String(this.widths[pane]));
      separator.setAttribute('aria-valuemax', String(this.maximum(pane)));
      separator.setAttribute('aria-valuetext', `${this.widths[pane]} pixels`);
    }
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
      drag = { pointer: event.pointerId, x: event.clientX, width: this.widths[pane] };
    });
    separator.addEventListener('pointermove', event => {
      if (drag !== null && drag.pointer === event.pointerId) this.resize(pane, drag.width + event.clientX - drag.x);
    });
    separator.addEventListener('lostpointercapture', () => { drag = null; });
    separator.addEventListener('keydown', event => {
      const target = ([['ArrowLeft', this.widths[pane] - KEYBOARD_STEP], ['ArrowRight', this.widths[pane] + KEYBOARD_STEP],
        ['Home', MIN_WIDTH[pane]], ['End', this.maximum(pane)]] as const).find(([key]) => key === event.key);
      if (target !== undefined) { event.preventDefault(); this.resize(pane, target[1]); }
    });
    return separator;
  }
}
