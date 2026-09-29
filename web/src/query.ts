import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';
import { localSuggestions, suggestionKey } from './query-local.js';

const COMPLETION_DELAY_MS = 180;
const POPUP_EDGE = 8;
const POPUP_GAP = 4;

export class QueryEditor {
  readonly element = element('form', '');
  readonly input = element('input', '');
  readonly resultCount = element('span', 'No project selected');
  private readonly popup = element('div', '');
  private readonly options = element('div', '');
  private readonly diagnostic = element('div', '');
  private readonly status = element('p', '');
  private suggestions: api.QuerySuggestion[] = [];
  private active = -1;
  private generation = 0;
  private timer: number | null = null;
  private composing = false;
  private caret = '';

  constructor(private readonly complete: (query: string, cursor: number) => Promise<api.QueryAnalysis>, submit: () => void) {
    this.element.className = 'query-editor';
    this.input.setAttribute('aria-label', 'Search query'); this.input.setAttribute('role', 'combobox');
    this.input.setAttribute('aria-autocomplete', 'list'); this.input.setAttribute('aria-expanded', 'false');
    this.input.setAttribute('aria-controls', 'query-suggestions'); this.input.setAttribute('aria-describedby', 'query-diagnostic');
    this.input.autocomplete = 'off'; this.input.spellcheck = false;
    this.popup.className = 'query-popup'; this.popup.hidden = true;
    this.options.id = 'query-suggestions'; this.options.className = 'query-suggestions'; this.options.setAttribute('role', 'listbox');
    this.options.setAttribute('aria-label', 'Query suggestions');
    this.diagnostic.setAttribute('role', 'status'); this.diagnostic.setAttribute('aria-atomic', 'true');
    this.diagnostic.id = 'query-diagnostic'; this.diagnostic.className = 'query-diagnostic'; this.diagnostic.hidden = true;
    this.status.setAttribute('role', 'status'); this.status.hidden = true;
    const search = element('button', 'Search'); search.type = 'submit';
    const field = element('div', ''); field.className = 'query-field';
    this.resultCount.className = 'query-result-count'; this.resultCount.setAttribute('role', 'status');
    const clear = button('×', () => {
      this.input.value = ''; this.input.focus(); this.invalidate(); this.showDiagnostic(undefined, '');
    });
    clear.className = 'query-clear'; clear.setAttribute('aria-label', 'Clear query'); clear.title = 'Clear query';
    clear.addEventListener('pointerdown', event => event.preventDefault());
    field.append(this.input, this.resultCount, clear);
    const row = element('div', ''); row.className = 'query-input'; row.append(field, search);
    this.popup.append(this.diagnostic, this.options, this.status); this.element.append(row, this.popup);
    this.element.addEventListener('submit', event => { event.preventDefault(); this.invalidate(); submit(); });
    this.input.addEventListener('input', () => { this.showDiagnostic(undefined, this.input.value); this.schedule(); });
    this.input.addEventListener('click', () => this.schedule());
    this.input.addEventListener('focus', () => this.schedule());
    this.input.addEventListener('blur', () => this.invalidate());
    this.input.addEventListener('compositionstart', () => { this.composing = true; this.schedule(); });
    this.input.addEventListener('compositionend', () => { this.composing = false; this.schedule(); });
    this.input.addEventListener('keydown', event => {
      if (event.isComposing || this.composing) return;
      if (event.key === 'Escape') { event.preventDefault(); this.invalidate(); }
      else if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && this.suggestions.length > 0) {
        event.preventDefault();
        this.active = this.active < 0 ? (event.key === 'ArrowDown' ? 0 : this.suggestions.length - 1)
          : (this.active + (event.key === 'ArrowDown' ? 1 : -1) + this.suggestions.length) % this.suggestions.length;
        this.highlight();
      } else if ((event.key === 'Enter' || event.key === 'Tab') && this.active >= 0) {
        if (event.key === 'Enter') event.preventDefault();
        this.accept(this.suggestions[this.active]);
      } else if (event.key === ' ' && event.ctrlKey) { event.preventDefault(); this.schedule(); }
      else if (event.key === 'Tab') this.invalidate();
    });
    this.input.addEventListener('keyup', event => {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) this.schedule();
    });
    this.input.addEventListener('scroll', () => this.position());
    this.input.ownerDocument.addEventListener('selectionchange', () => {
      if (document.activeElement === this.input && this.location() !== this.caret) this.schedule();
    });
    window.addEventListener('resize', () => this.position());
    window.addEventListener('scroll', () => this.position(), true);
  }

  private cancel(): void {
    this.generation++;
    if (this.timer !== null) { window.clearTimeout(this.timer); this.timer = null; }
    this.caret = this.location();
  }

  invalidate(): void {
    this.cancel();
    this.suggestions = []; this.active = -1; this.options.replaceChildren(); this.popup.hidden = true;
    this.input.setAttribute('aria-expanded', 'false'); this.input.removeAttribute('aria-activedescendant');
    this.status.hidden = true;
  }

  showDiagnostic(value: api.QueryDiagnostic | undefined, source: string): void {
    if (this.input.value !== source) return;
    this.diagnostic.replaceChildren(); this.diagnostic.hidden = value === undefined;
    if (value === undefined) { this.input.removeAttribute('aria-invalid'); this.renderPopup(); return; }
    this.input.setAttribute('aria-invalid', 'true');
    const preview = element('code', '');
    preview.append(document.createTextNode(source.slice(0, value.span.start)),
      element('mark', source.slice(value.span.start, value.span.end) || '▏'), document.createTextNode(source.slice(value.span.end)));
    const locate = button('Show query error', () => {
      this.input.focus(); this.input.setSelectionRange(value.span.start, value.span.end); this.invalidate(); this.showDiagnostic(value, source);
    });
    locate.addEventListener('pointerdown', event => event.preventDefault());
    this.diagnostic.append(element('p', `${value.message} (${value.span.start}–${value.span.end})`), preview, locate);
    this.renderPopup();
  }

  private renderPopup(): void {
    this.options.hidden = this.options.childElementCount === 0;
    this.popup.hidden = document.activeElement !== this.input || (this.options.hidden && this.diagnostic.hidden && this.status.hidden);
    this.input.setAttribute('aria-expanded', String(!this.popup.hidden && !this.options.hidden));
    this.position();
  }

  private schedule(): void {
    this.cancel();
    this.suggestions = []; this.active = -1; this.input.removeAttribute('aria-activedescendant');
    for (const option of this.options.querySelectorAll('button')) { option.disabled = true; option.setAttribute('aria-selected', 'false'); }
    this.popup.setAttribute('aria-busy', 'true');
    this.status.textContent = this.composing ? 'Composing query…' : 'Loading query suggestions…'; this.status.hidden = false;
    this.renderPopup();
    if (this.composing) return;
    const cursor = this.input.selectionStart;
    if (cursor === null) throw new Error('Query input has no text caret');
    const local = localSuggestions(this.input.value, cursor);
    if (local.length > 0) { this.setOptions(local); this.renderPopup(); }
    this.timer = window.setTimeout(() => { this.timer = null; void this.suggest(); }, COMPLETION_DELAY_MS);
  }

  private async suggest(): Promise<void> {
    const generation = this.generation; const source = this.input.value; const cursor = this.input.selectionStart;
    if (cursor === null) throw new Error('Query input has no text caret');
    const current = () => generation === this.generation && this.input.value === source && this.input.selectionStart === cursor && document.activeElement === this.input;
    this.status.textContent = 'Loading query suggestions…'; this.status.hidden = false; this.renderPopup();
    try {
      const result = await this.complete(source, cursor);
      if (!current()) return;
      const diagnostic = result.diagnostic;
      const completingError = diagnostic !== undefined && result.suggestions.some(suggestion =>
        suggestion.span.start <= diagnostic.span.start && suggestion.span.end >= diagnostic.span.end);
      this.showDiagnostic(completingError ? undefined : diagnostic, source);
      const merged = new Map(result.suggestions.map(value => [suggestionKey(value), value]));
      if (diagnostic === undefined || completingError) for (const value of localSuggestions(source, cursor)) {
        if (!merged.has(suggestionKey(value))) merged.set(suggestionKey(value), value);
      }
      this.setOptions([...merged.values()]); this.popup.setAttribute('aria-busy', 'false');
      this.status.textContent = result.hasMore ? 'More matches available; refine the query.' : 'No query suggestions.';
      this.status.hidden = !result.hasMore && (this.suggestions.length > 0 || !this.diagnostic.hidden); this.renderPopup();
    } catch (error) {
      if (current()) { this.setOptions(localSuggestions(source, cursor)); this.popup.setAttribute('aria-busy', 'false'); this.status.textContent = `Backend query suggestions unavailable: ${String(error)}`; this.status.hidden = false; this.renderPopup(); }
    }
  }

  private location(): string { return JSON.stringify([this.input.value, this.input.selectionStart, this.input.selectionEnd]); }

  private position(): void {
    if (this.popup.hidden) return;
    const input = this.input.getBoundingClientRect(); const style = getComputedStyle(this.input);
    const cursor = this.input.selectionStart;
    if (cursor === null) throw new Error('Query input has no text caret');
    const measure = element('span', this.input.value.slice(0, cursor));
    Object.assign(measure.style, {position: 'fixed', visibility: 'hidden', whiteSpace: 'pre', font: style.font, letterSpacing: style.letterSpacing});
    this.element.append(measure);
    const caret = input.left + parseFloat(style.borderLeftWidth) + parseFloat(style.paddingLeft) + measure.getBoundingClientRect().width - this.input.scrollLeft;
    measure.remove();
    const width = this.popup.getBoundingClientRect().width;
    this.popup.style.left = `${Math.max(POPUP_EDGE, Math.min(Math.max(input.left, Math.min(input.right, caret)), window.innerWidth - width - POPUP_EDGE))}px`;
    const top = input.bottom + POPUP_GAP;
    this.popup.style.top = `${top}px`; this.popup.style.maxHeight = `${Math.max(0, window.innerHeight - top - POPUP_EDGE)}px`;
  }

  private setOptions(values: api.QuerySuggestion[]): void {
    const selected = this.active < 0 ? null : suggestionKey(this.suggestions[this.active]);
    this.suggestions = values; this.options.replaceChildren();
    for (const [index, suggestion] of values.entries()) {
      const option = button(`${suggestion.label} · ${suggestion.kind}`, () => { if (this.suggestions.includes(suggestion)) this.accept(suggestion); });
      option.id = `query-suggestion-${index}`; option.setAttribute('role', 'option'); option.tabIndex = -1;
      option.addEventListener('pointerdown', event => event.preventDefault()); this.options.append(option);
    }
    this.active = values.findIndex(value => suggestionKey(value) === selected);
    if (this.active < 0 && values.length > 0) this.active = 0;
    if (this.active >= 0) this.highlight(); else this.input.removeAttribute('aria-activedescendant');
  }

  private highlight(): void {
    for (const [index, option] of Array.from(this.options.children).entries()) option.setAttribute('aria-selected', String(index === this.active));
    const option = this.options.children[this.active];
    this.input.setAttribute('aria-activedescendant', option.id); option.scrollIntoView({ block: 'nearest' });
  }

  private accept(suggestion: api.QuerySuggestion): void {
    this.input.setRangeText(suggestion.text, suggestion.span.start, suggestion.span.end, 'end');
    this.invalidate(); this.showDiagnostic(undefined, this.input.value); this.input.focus();
  }
}
