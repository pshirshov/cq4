import * as api from '../../generated/typescript/cq/api/index.js';
import { element } from './editor.js';

// Colouring of an agent model configuration. It is lexical: it follows the scalar, comment and flow rules of the server's reader
// (AgentYaml) and the reference syntax of AgentReferenceText, accepts every text and leaves what it does not know uncoloured.
type Kind = 'comment' | 'section' | 'harness' | 'governing' | 'role' | 'mode' | 'tier' | 'strategy' | 'panel' | 'provider' | 'model'
  | 'effort' | 'number' | 'string' | 'punctuation';
interface Token { kind: Kind | null; text: string }
interface Lexed { tokens: Token[]; state: number }

const lowered = (values: ReadonlyArray<string>): string[] => values.map(value => value.toLowerCase());
const GOVERNING = '$harness';
const FLOW_INDICATORS = ',[]{}';
const HARNESSES = new Set(lowered(api.Harness_values));
const TIERS = new Set(lowered(api.ModelTier_values));
const EFFORTS = new Set(lowered(api.Effort_values));
const modes = (role: api.AgentRole, values: ReadonlyArray<string>): [string, Set<string>] => [role.toLowerCase(), new Set(lowered(values))];
const MODES = new Map<string, Set<string>>([
  modes(api.AgentRole.Planner, []), modes(api.AgentRole.Worker, api.WorkerMode_values), modes(api.AgentRole.Explorer, api.ExplorerMode_values),
  modes(api.AgentRole.Reviewer, api.ReviewerMode_values),
]);
const KEYS = new Map<string, Kind>([
  ...['defaults', 'harnesses', 'roles', 'tiers'].map((key): [string, Kind] => [key, 'section']),
  ...['fallback', 'rr', 'first'].map((key): [string, Kind] => [key, 'strategy']),
  ...[...lowered(api.PanelMode_values), 'min'].map((key): [string, Kind] => [key, 'panel']),
  ...[...HARNESSES].map((key): [string, Kind] => [key, 'harness']),
  ...[...TIERS].map((key): [string, Kind] => [key, 'tier']),
]);
const WHOLE_NUMBER = /^-?[0-9]+$/;
const EFFORT = /^\?effort=(.*)$/;

// What a line takes over from the lines before it, packed into one number so that two states compare with ===:
// the depth of the open flow collections, whether the value of a min key is due, and whether scalars are entries of a tier list.
const MINIMUM = 1, TIER_ENTRIES = 2, DEPTH = 4;
const INITIAL = 0;

function space(character: string | undefined): boolean { return character === ' ' || character === '\t' || character === '\r'; }
function blank(line: string, index: number): boolean { return index >= line.length || space(line[index]); }
function name(raw: string, out: Token[]): void {
  const slash = raw.indexOf('/');
  if (slash < 0) out.push({ kind: 'model', text: raw });
  else out.push({ kind: 'provider', text: raw.slice(0, slash) }, { kind: 'punctuation', text: '/' }, { kind: 'model', text: raw.slice(slash + 1) });
}
/** A scalar that is a value: a model reference where roles are assigned, a model name in a tier list. */
function value(text: string, tiers: boolean, out: Token[]): void {
  const query = text.indexOf('?'); const body = query < 0 ? text : text.slice(0, query);
  const colon = body.indexOf(':'); const prefix = colon < 0 ? null : body.slice(0, colon);
  const selector = prefix !== null && (prefix === GOVERNING || HARNESSES.has(prefix)) ? prefix : null;
  if (tiers) { if (selector !== null || body.startsWith('@')) out.push({ kind: null, text: body }); else name(body, out); }
  else if (selector === null) out.push({ kind: null, text: body });
  else {
    const rest = body.slice(colon + 1);
    out.push({ kind: selector === GOVERNING ? 'governing' : 'harness', text: selector }, { kind: 'punctuation', text: ':' });
    if (rest.startsWith('@')) out.push({ kind: TIERS.has(rest.slice(1)) ? 'tier' : null, text: rest }); else name(rest, out);
  }
  if (query >= 0) { const effort = EFFORT.exec(text.slice(query)); out.push({ kind: effort !== null && EFFORTS.has(effort[1]) ? 'effort' : null, text: text.slice(query) }); }
}
function key(text: string, out: Token[]): void {
  const kind = KEYS.get(text); if (kind !== undefined) { out.push({ kind, text }); return; }
  const slash = text.indexOf('/'); const role = slash < 0 ? text : text.slice(0, slash); const modes = MODES.get(role);
  if (modes === undefined) { out.push({ kind: null, text }); return; }
  out.push({ kind: 'role', text: role });
  if (slash >= 0) out.push({ kind: 'punctuation', text: '/' }, { kind: modes.has(text.slice(slash + 1)) ? 'mode' : null, text: text.slice(slash + 1) });
}

function lex(line: string, entering: number): Lexed {
  const tokens: Token[] = []; const length = line.length;
  let depth = Math.floor(entering / DEPTH), minimum = (entering & MINIMUM) !== 0, tiers = (entering & TIER_ENTRIES) !== 0, index = 0;
  // The colon that ends a key: followed by a blank or, in a flow collection, by a flow indicator.
  const ends = (at: number): boolean => line[at] === ':' && (blank(line, at + 1) || (depth > 0 && FLOW_INDICATORS.includes(line[at + 1])));
  const keyed = (from: number): number => { let at = from; while (at < length && space(line[at])) at++; return ends(at) ? at : -1; };
  const entry = (text: string, colon: number): void => {
    if (colon < 0) { if (minimum && WHOLE_NUMBER.test(text)) tokens.push({ kind: 'number', text }); else value(text, tiers, tokens); minimum = false; return; }
    key(text, tokens); minimum = text === 'min';
    if (text === 'tiers') tiers = true; else if (text === 'roles' || text === 'defaults' || HARNESSES.has(text)) tiers = false;
  };
  while (index < length) {
    const character = line[index];
    if (space(character)) { let end = index + 1; while (end < length && space(line[end])) end++; tokens.push({ kind: null, text: line.slice(index, end) }); index = end; }
    else if (character === '#' && (index === 0 || space(line[index - 1]))) { tokens.push({ kind: 'comment', text: line.slice(index) }); index = length; }
    else if (character === '[' || character === '{') { tokens.push({ kind: 'punctuation', text: character }); depth++; index++; }
    else if (depth > 0 && (character === ']' || character === '}')) { tokens.push({ kind: 'punctuation', text: character }); depth--; index++; }
    else if (depth > 0 && character === ',') { tokens.push({ kind: 'punctuation', text: character }); index++; }
    else if (character === '"') {
      let end = index + 1;
      while (end < length && line[end] !== '"') end += line[end] === '\\' ? 2 : 1;
      end = Math.min(end + 1, length); tokens.push({ kind: 'string', text: line.slice(index, end) }); index = end;
      const colon = keyed(index);
      if (colon >= 0) { if (colon > index) tokens.push({ kind: null, text: line.slice(index, colon) }); tokens.push({ kind: 'punctuation', text: ':' }); index = colon + 1; }
      minimum = false;
    } else {
      // A plain scalar, as the reader ends it; the spaces after its last character are not part of it.
      let at = index, end = index;
      while (at < length && !(depth > 0 && FLOW_INDICATORS.includes(line[at])) && !ends(at) && !(line[at] === '#' && at > index && space(line[at - 1]))) {
        if (!space(line[at])) end = at + 1;
        at++;
      }
      if (end === index) { tokens.push({ kind: null, text: character }); index++; continue; }
      const colon = keyed(end); entry(line.slice(index, end), colon);
      if (colon < 0) index = end;
      else { if (colon > end) tokens.push({ kind: null, text: line.slice(end, colon) }); tokens.push({ kind: 'punctuation', text: ':' }); index = colon + 1; }
    }
  }
  return { tokens, state: depth * DEPTH + (minimum ? MINIMUM : 0) + (tiers ? TIER_ENTRIES : 0) };
}

interface Line { text: string; entering: number; leaving: number; last: boolean; node: HTMLSpanElement; ranges: Array<[Kind, StaticRange]> }

// A token is coloured through the CSS highlight registry (::highlight(agents-token-KIND) in style.css) and not by an element of
// its own: every inline box is laid out at a whole layout unit, so a line cut into elements grows wider than the same line in a
// textarea, by a pixel and more at the end of a long line. A browser without the registry shows the text uncoloured.
const COLOURS = 'highlights' in CSS;
function registered(kind: Kind): Highlight {
  const name = `agents-token-${kind}`; const found = CSS.highlights.get(name); if (found !== undefined) return found;
  const created = new Highlight(); CSS.highlights.set(name, created); return created;
}

/**
 * Shows a text coloured, one block of one text node per line, inside `target`; the text content of `target` is exactly the text.
 * A later text replaces only the lines that changed or whose inherited state changed, so that typing in a long text costs one line.
 */
export class ColouredText {
  private lines: Line[] = [];
  constructor(private readonly target: HTMLElement) {}

  update(text: string): void {
    const texts = text.split('\n'); const before = this.lines; const shift = before.length - texts.length; const shared = Math.min(before.length, texts.length);
    // The lines that are the same text at the start and at the end keep their nodes when their inherited state is the same too.
    let same = 0; while (same < shared && before[same].text === texts[same]) same++;
    let trailing = 0; while (trailing < shared - same && before[before.length - 1 - trailing].text === texts[texts.length - 1 - trailing]) trailing++;
    const next: Line[] = []; let state = INITIAL;
    for (let index = 0; index < texts.length; index++) {
      const last = index === texts.length - 1;
      const kept = index < same ? before[index] : index >= texts.length - trailing ? before[index + shift] : undefined;
      const line = kept !== undefined && kept.entering === state && kept.last === last ? kept : this.line(texts[index], state, last);
      next.push(line); state = line.leaving;
    }
    let head = 0; while (head < next.length && head < before.length && next[head] === before[head]) head++;
    let tail = 0; while (tail < next.length - head && tail < before.length - head && next[next.length - 1 - tail] === before[before.length - 1 - tail]) tail++;
    for (const line of before.slice(head, before.length - tail)) { line.node.remove(); for (const [kind, range] of line.ranges) registered(kind).delete(range); }
    const anchor = tail === 0 ? null : before[before.length - tail].node;
    for (const line of next.slice(head, next.length - tail)) { this.target.insertBefore(line.node, anchor); for (const [kind, range] of line.ranges) registered(kind).add(range); }
    this.lines = next;
  }
  private line(text: string, entering: number, last: boolean): Line {
    const lexed = lex(text, entering); const node = element('span', last ? text : `${text}\n`); node.className = 'agents-line';
    const content = node.firstChild; const ranges: Array<[Kind, StaticRange]> = []; let offset = 0;
    for (const token of lexed.tokens) {
      const end = offset + token.text.length;
      if (COLOURS && content !== null && token.kind !== null && end > offset) ranges.push([token.kind, new StaticRange({ startContainer: content, startOffset: offset, endContainer: content, endOffset: end })]);
      offset = end;
    }
    return { text, entering, leaving: lexed.state, last, node, ranges };
  }
}

/**
 * A text control with coloured text: the textarea is the control, with transparent glyphs, and the coloured copy lies behind
 * it in the same font, padding and wrapping, moved by the textarea's scroll offsets.
 */
export class HighlightedEditor {
  readonly element = element('div', '');
  readonly text = element('textarea', '');
  private readonly content = element('code', '');
  private readonly coloured = new ColouredText(this.content);

  constructor() {
    const copy = element('pre', ''); copy.className = 'agents-highlight'; copy.setAttribute('aria-hidden', 'true'); copy.append(this.content);
    this.element.className = 'agents-editor'; this.text.className = 'agents-text'; this.text.spellcheck = false; this.text.wrap = 'off';
    this.element.append(copy, this.text);
    this.text.addEventListener('input', () => this.refresh());
    this.text.addEventListener('scroll', () => this.follow());
  }
  /** Sets the text from the program, which fires no input event. */
  set(text: string): void { this.text.value = text; this.refresh(); }
  private refresh(): void { this.coloured.update(this.text.value); this.follow(); }
  /** Puts the coloured copy at the scroll position of the textarea. */
  follow(): void { this.content.style.transform = `translate(${-this.text.scrollLeft}px, ${-this.text.scrollTop}px)`; }
}
