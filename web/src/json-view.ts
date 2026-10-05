import { element } from './editor.js';

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type Token = 'key' | 'string' | 'number' | 'boolean' | 'null' | 'punctuation';

const INDENT = '  ';

function token(kind: Token, text: string): HTMLSpanElement {
  const node = element('span', text); node.className = `json-${kind}`; return node;
}
/** Appends `entries` between `open` and `close`, one per line at `depth + 1`; only indentation and line breaks lie outside the token spans. */
function enclosed(target: HTMLElement, open: string, close: string, depth: number, entries: (() => void)[]): void {
  if (entries.length === 0) { target.append(token('punctuation', open + close)); return; }
  target.append(token('punctuation', open));
  entries.forEach((entry, index) => {
    target.append(`\n${INDENT.repeat(depth + 1)}`); entry();
    if (index < entries.length - 1) target.append(token('punctuation', ','));
  });
  target.append(`\n${INDENT.repeat(depth)}`, token('punctuation', close));
}
function write(target: HTMLElement, value: Json, depth: number): void {
  if (value === null) target.append(token('null', 'null'));
  else if (Array.isArray(value)) enclosed(target, '[', ']', depth, value.map(entry => () => write(target, entry, depth + 1)));
  else if (typeof value === 'object') enclosed(target, '{', '}', depth, Object.entries(value).map(([key, entry]) => () => {
    target.append(token('key', JSON.stringify(key)), token('punctuation', ':'), ' '); write(target, entry, depth + 1);
  }));
  else target.append(token(typeof value === 'string' ? 'string' : typeof value === 'number' ? 'number' : 'boolean', JSON.stringify(value)));
}

/**
 * A block showing text that holds a JSON document, pretty-printed with a class per token kind; its text content is exactly
 * `JSON.stringify(document, null, 2)`. Text that is not JSON is shown as it is, uncoloured.
 */
export function jsonTextView(text: string): HTMLPreElement {
  let value: Json;
  try { value = JSON.parse(text); } catch { return element('pre', text); }
  const node = element('pre', ''); node.className = 'json-view'; write(node, value, 0); return node;
}
/** The same block for a value that is already structured, such as the output of a generated JSON codec. */
export function jsonView(value: unknown): HTMLPreElement { return jsonTextView(JSON.stringify(value)); }
