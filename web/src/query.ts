import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';

const COMPLETION_DELAY_MS = 180;

export class QueryEditor {
  readonly element = element('form', '');
  readonly input = element('input', '');
  private readonly popup = element('div', '');
  private readonly diagnostic = element('div', '');
  private readonly status = element('p', '');
  private suggestions: api.QuerySuggestion[] = [];
  private active = -1;
  private generation = 0;
  private timer: number | null = null;
  private composing = false;

  constructor(private readonly complete: (query: string, cursor: number) => Promise<api.QueryAnalysis>, submit: () => void) {
    this.element.className = 'query-editor';
    this.input.setAttribute('aria-label', 'Search query'); this.input.setAttribute('role', 'combobox');
    this.input.setAttribute('aria-autocomplete', 'list'); this.input.setAttribute('aria-expanded', 'false');
    this.input.setAttribute('aria-controls', 'query-suggestions'); this.input.setAttribute('aria-describedby', 'query-diagnostic query-completion-status');
    this.input.autocomplete = 'off'; this.input.spellcheck = false;
    this.popup.id = 'query-suggestions'; this.popup.className = 'query-suggestions'; this.popup.setAttribute('role', 'listbox');
    this.popup.setAttribute('aria-label', 'Query suggestions'); this.popup.hidden = true;
    this.diagnostic.setAttribute('role', 'status'); this.diagnostic.setAttribute('aria-atomic', 'true');
    this.diagnostic.id = 'query-diagnostic'; this.diagnostic.className = 'query-diagnostic'; this.diagnostic.hidden = true;
    this.status.id = 'query-completion-status'; this.status.setAttribute('role', 'status'); this.status.hidden = true;
    const search = element('button', 'Search'); search.type = 'submit';
    const row = element('div', ''); row.className = 'query-input'; row.append(this.input, search);
    this.element.append(row, this.popup, this.diagnostic, this.status);
    this.element.addEventListener('submit', event => { event.preventDefault(); this.invalidate(); submit(); });
    this.input.addEventListener('input', () => { this.showDiagnostic(undefined, this.input.value); this.schedule(); });
    this.input.addEventListener('click', () => this.schedule());
    this.input.addEventListener('focus', () => this.schedule());
    this.input.addEventListener('blur', () => this.invalidate());
    this.input.addEventListener('compositionstart', () => { this.composing = true; this.invalidate(); });
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
  }

  invalidate(): void {
    this.generation++;
    if (this.timer !== null) { window.clearTimeout(this.timer); this.timer = null; }
    this.suggestions = []; this.active = -1; this.popup.replaceChildren(); this.popup.hidden = true;
    this.input.setAttribute('aria-expanded', 'false'); this.input.removeAttribute('aria-activedescendant');
    this.status.hidden = true;
  }

  showDiagnostic(value: api.QueryDiagnostic | undefined, source: string): void {
    if (this.input.value !== source) return;
    this.diagnostic.replaceChildren(); this.diagnostic.hidden = value === undefined;
    if (value === undefined) { this.input.removeAttribute('aria-invalid'); return; }
    this.input.setAttribute('aria-invalid', 'true');
    const preview = element('code', '');
    preview.append(document.createTextNode(source.slice(0, value.span.start)),
      element('mark', source.slice(value.span.start, value.span.end) || '▏'), document.createTextNode(source.slice(value.span.end)));
    const locate = button('Show query error', () => {
      this.input.focus(); this.input.setSelectionRange(value.span.start, value.span.end); this.invalidate();
    });
    this.diagnostic.append(element('p', `${value.message} (${value.span.start}–${value.span.end})`), preview, locate);
  }

  private schedule(): void {
    this.invalidate();
    if (this.composing) return;
    this.timer = window.setTimeout(() => { this.timer = null; void this.suggest(); }, COMPLETION_DELAY_MS);
  }

  private async suggest(): Promise<void> {
    const generation = this.generation; const source = this.input.value; const cursor = this.input.selectionStart;
    if (cursor === null) throw new Error('Query input has no text caret');
    const current = () => generation === this.generation && this.input.value === source && this.input.selectionStart === cursor && document.activeElement === this.input;
    this.status.textContent = 'Loading query suggestions…'; this.status.hidden = false;
    try {
      const result = await this.complete(source, cursor);
      if (!current()) return;
      this.showDiagnostic(result.diagnostic, source);
      this.suggestions = result.suggestions;
      for (const [index, suggestion] of this.suggestions.entries()) {
        const option = button(`${suggestion.label} · ${suggestion.kind}`, () => this.accept(suggestion));
        option.id = `query-suggestion-${index}`; option.setAttribute('role', 'option'); option.setAttribute('aria-selected', 'false'); option.tabIndex = -1;
        option.addEventListener('pointerdown', event => event.preventDefault()); this.popup.append(option);
      }
      this.popup.hidden = this.suggestions.length === 0; this.input.setAttribute('aria-expanded', String(!this.popup.hidden));
      this.status.textContent = result.hasMore ? 'More matches available; refine the query.' : `${this.suggestions.length} query suggestions`;
    } catch (error) {
      if (current()) { this.status.textContent = `Query suggestions unavailable: ${String(error)}`; this.status.hidden = false; }
    }
  }

  private highlight(): void {
    for (const [index, option] of Array.from(this.popup.children).entries()) option.setAttribute('aria-selected', String(index === this.active));
    const option = this.popup.children[this.active];
    this.input.setAttribute('aria-activedescendant', option.id); option.scrollIntoView({ block: 'nearest' });
  }

  private accept(suggestion: api.QuerySuggestion): void {
    this.input.setRangeText(suggestion.text, suggestion.span.start, suggestion.span.end, 'end');
    this.invalidate(); this.showDiagnostic(undefined, this.input.value); this.input.focus();
  }
}
