import rawSchemas from '../../generated/resources/cq-schemas.json';

export type Json = null | string | number | boolean | Json[] | { [key: string]: Json };
interface Schema {
  $ref?: string; type?: string; properties?: Partial<Record<string, Schema>>; required?: string[];
  oneOf?: Schema[]; enum?: string[]; items?: Schema; format?: string; pattern?: string;
}
export interface Editor { element: HTMLElement; read(): Json }
const schemas: Record<string, Schema> = rawSchemas;
export function element<K extends keyof HTMLElementTagNameMap>(tag: K, text: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag); node.textContent = text; return node;
}
export function button(text: string, action: () => void): HTMLButtonElement {
  const node = element('button', text); node.type = 'button'; node.addEventListener('click', action); return node;
}
function object(value: Json | undefined): { [key: string]: Json } {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : {};
}
function required<T>(value: T | undefined, name: string): T {
  if (value === undefined) throw new Error(`Missing generated schema ${name}`);
  return value;
}
function resolve(schema: Schema): Schema {
  return schema.$ref === undefined ? schema : required(schemas[schema.$ref.replace('#/$defs/', '')], schema.$ref);
}
export function edit(name: string, value: Json | undefined, label: string): Editor {
  return field(required(schemas[`cq_api_${name}`], name), value, label);
}
function field(unresolved: Schema, value: Json | undefined, label: string): Editor {
  const schema = resolve(unresolved);
  const container = element('fieldset', '');
  container.append(element('legend', label.replace(/([a-z])([A-Z])/g, '$1 $2')));
  if (schema.oneOf !== undefined) {
    const nullable = schema.oneOf.some(s => s.type === 'null');
    if (nullable) {
      const included = element('input', ''); included.type = 'checkbox'; included.checked = value !== undefined && value !== null;
      const text = element('label', 'Include ' + label); text.prepend(included);
      const inner = field(required(schema.oneOf.find(s => s.type !== 'null'), 'optional branch'), value, label);
      inner.element.hidden = !included.checked;
      included.addEventListener('change', () => { inner.element.hidden = !included.checked; });
      container.append(text, inner.element);
      return { element: container, read: () => included.checked ? inner.read() : null };
    }
    const branches = schema.oneOf.map(branch => {
      const name = required(branch.required, 'branch tag')[0];
      return { name, schema: required(required(branch.properties, 'branch fields')[name], name) };
    });
    const initial = branches.find(b => b.name in object(value));
    const selected = initial === undefined ? branches.find(b => b.name === 'Task') : initial;
    const choice = element('select', ''); choice.setAttribute('aria-label', label);
    for (const branch of branches) { const option = element('option', branch.name); option.value = branch.name; choice.append(option); }
    choice.value = selected === undefined ? branches[0].name : selected.name;
    const body = element('div', '');
    const build = (): Editor => {
      const branch = required(branches.find(b => b.name === choice.value), 'selected branch');
      return field(branch.schema, object(value)[branch.name], branch.name);
    };
    let inner = build(); body.append(inner.element);
    choice.addEventListener('change', () => { inner = build(); body.replaceChildren(inner.element); });
    container.append(choice, body);
    return { element: container, read: () => ({ [choice.value]: inner.read() }) };
  }
  if (schema.type === 'object') {
    const fields = Object.entries(schema.properties === undefined ? {} : schema.properties).map(([key, child]) => ({ key, editor: field(required(child, key), object(value)[key], key) }));
    for (const entry of fields) container.append(entry.editor.element);
    return { element: container, read: () => Object.fromEntries(fields.map(entry => [entry.key, entry.editor.read()])) };
  }
  if (schema.type === 'array') {
    const rows = element('div', ''); const entries: { row: HTMLElement; editor: Editor }[] = [];
    const add = (current: Json | undefined): void => {
      const row = element('div', ''); row.className = 'array-row';
      const editor = field(required(schema.items, 'array item'), current, label + ' entry');
      const entry = { row, editor }; entries.push(entry);
      row.append(editor.element, button('Remove', () => { entries.splice(entries.indexOf(entry), 1); row.remove(); })); rows.append(row);
    };
    if (Array.isArray(value)) for (const entry of value) add(entry);
    container.append(rows, button('Add ' + label, () => add(undefined)));
    return { element: container, read: () => entries.map(entry => entry.editor.read()) };
  }
  if (schema.enum !== undefined) {
    const select = element('select', ''); select.setAttribute('aria-label', label);
    for (const item of schema.enum) { const option = element('option', item); option.value = item; select.append(option); }
    if (typeof value === 'string') select.value = value;
    container.append(select); return { element: container, read: () => select.value };
  }
  if (schema.type === 'boolean') {
    const checkbox = element('input', ''); checkbox.type = 'checkbox'; checkbox.checked = value === true; checkbox.setAttribute('aria-label', label);
    container.append(checkbox); return { element: container, read: () => checkbox.checked };
  }
  if (schema.type === 'string' || schema.type === 'integer') {
    const input = element('textarea', ''); input.rows = ['body', 'description', 'result'].includes(label) ? 4 : 1;
    input.setAttribute('aria-label', label); input.value = value === undefined || value === null ? '' : String(value);
    container.append(input);
    return { element: container, read: () => schema.type === 'integer' ? Number(input.value) : input.value };
  }
  throw new Error(`Unhandled generated form type: ${schema.type}`);
}
