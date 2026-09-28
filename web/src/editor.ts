import { CONTENT_FIELDS, fieldLabel, jsonObject, type ItemKind } from './presentation.js';
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
function required<T>(value: T | undefined, name: string): T {
  if (value === undefined) throw new Error(`Missing generated schema ${name}`);
  return value;
}
function resolve(schema: Schema): Schema {
  return schema.$ref === undefined ? schema : required(schemas[schema.$ref.replace('#/$defs/', '')], schema.$ref);
}
export function edit(name: string, value: Json | undefined, caption: string): Editor {
  if (name !== 'ItemDraft') return field(required(schemas[`cq_api_${name}`], name), value, caption, null);
  const current = jsonObject(value); const schema = required(schemas.cq_api_ItemDraft, name);
  const properties = required(schema.properties, 'item fields');
  const container = element('div', ''); container.className = 'item-document item-form'; container.append(element('h2', caption));
  const body = field(required(properties.body, 'body'), current.body, 'body', null);
  const fields = ['title', 'content', 'labels', 'citations', 'archived'].map(key => ({ key, editor: field(required(properties[key], key), current[key], key, key === 'content' ? body.element : null) }));
  for (const entry of fields) container.append(entry.editor.element);
  fields.push({ key: 'body', editor: body });
  return { element: container, read: () => Object.fromEntries(fields.map(entry => [entry.key, entry.editor.read()])) };
}
function field(unresolved: Schema, value: Json | undefined, label: string, description: HTMLElement | null): Editor {
  const schema = resolve(unresolved);
  const container = element('div', ''); container.className = 'form-field'; container.dataset.field = label;
  if (schema.oneOf !== undefined) {
    const nullable = schema.oneOf.some(s => s.type === 'null');
    if (nullable && schema.oneOf.some(s => resolve(s).type === 'string')) {
      const inner = field(required(schema.oneOf.find(s => s.type !== 'null'), 'optional branch'), value, label, null);
      return { element: inner.element, read: () => inner.read() === '' ? null : inner.read() };
    }
    if (nullable) {
      const included = element('input', ''); included.type = 'checkbox'; included.checked = value !== undefined && value !== null;
      const text = element('label', 'Add ' + fieldLabel(label).toLowerCase()); text.prepend(included);
      const inner = field(required(schema.oneOf.find(s => s.type !== 'null'), 'optional branch'), value, label, null);
      inner.element.hidden = !included.checked;
      included.addEventListener('change', () => { inner.element.hidden = !included.checked; });
      container.append(text, inner.element);
      return { element: container, read: () => included.checked ? inner.read() : null };
    }
    const branches = schema.oneOf.map(branch => {
      const name = required(branch.required, 'branch tag')[0];
      return { name, schema: required(required(branch.properties, 'branch fields')[name], name) };
    });
    const initial = branches.find(b => b.name in jsonObject(value));
    const selected = initial === undefined ? branches.find(b => b.name === 'Task') : initial;
    const choice = element('select', ''); choice.setAttribute('aria-label', label);
    const caption = element('label', label === 'content' ? 'Item type' : 'Source type'); caption.append(choice);
    for (const branch of branches) { const option = element('option', branch.name); option.value = branch.name; choice.append(option); }
    choice.value = selected === undefined ? branches[0].name : selected.name;
    const body = element('div', '');
    let branchName = choice.value;
    const build = (): Editor => {
      const branch = required(branches.find(b => b.name === branchName), 'selected branch');
      return field(branch.schema, jsonObject(value)[branch.name], branch.name, description);
    };
    let inner = build(); body.append(inner.element);
    const select = (): void => {
      if (branchName === choice.value) return;
      branchName = choice.value; inner = build(); body.replaceChildren(inner.element);
    };
    choice.addEventListener('input', select); choice.addEventListener('change', select);
    container.classList.add(label === 'content' ? 'content-fields' : 'source-fields'); container.append(caption, body);
    return { element: container, read: () => ({ [branchName]: inner.read() }) };
  }
  if (schema.type === 'object') {
    const properties = schema.properties === undefined ? {} : schema.properties;
    const order = label in CONTENT_FIELDS ? ['status', ...(label === 'Defect' ? ['severity'] : []), ...CONTENT_FIELDS[label as ItemKind]]
      : unresolved.$ref === '#/$defs/cq_api_Evidence' ? ['description', 'origin', 'citations'] : Object.keys(properties);
    const fields = order.map(key => ({ key, editor: field(required(properties[key], key), jsonObject(value)[key], key, null) }));
    const metadata = element('div', ''); metadata.className = 'form-metadata';
    for (const entry of fields) {
      if (['status', 'severity'].includes(entry.key)) metadata.append(entry.editor.element);
      else container.append(entry.editor.element);
    }
    if (metadata.childElementCount > 0) container.prepend(metadata);
    if (description !== null) metadata.after(description);
    return { element: container, read: () => Object.fromEntries(fields.map(entry => [entry.key, entry.editor.read()])) };
  }
  if (schema.type === 'array') {
    container.append(element('h3', fieldLabel(label)));
    const rows = element('div', ''); const entries: { row: HTMLElement; editor: Editor }[] = [];
    const add = (current: Json | undefined): void => {
      const row = element('div', ''); row.className = 'array-row';
      const editor = field(required(schema.items, 'array item'), current, label + ' entry', null);
      const entry = { row, editor }; entries.push(entry);
      row.append(editor.element, button('Remove', () => { entries.splice(entries.indexOf(entry), 1); row.remove(); })); rows.append(row);
    };
    if (Array.isArray(value)) for (const entry of value) add(entry);
    container.append(rows, button('Add ' + fieldLabel(label).toLowerCase(), () => add(undefined)));
    return { element: container, read: () => entries.map(entry => entry.editor.read()) };
  }
  if (schema.enum !== undefined) {
    const select = element('select', ''); select.setAttribute('aria-label', label);
    for (const item of schema.enum) { const option = element('option', fieldLabel(item)); option.value = item; select.append(option); }
    if (typeof value === 'string') select.value = value;
    const caption = element('label', fieldLabel(label)); caption.append(select); container.append(caption); return { element: container, read: () => select.value };
  }
  if (schema.type === 'boolean') {
    const checkbox = element('input', ''); checkbox.type = 'checkbox'; checkbox.checked = value === true; checkbox.setAttribute('aria-label', label);
    const caption = element('label', fieldLabel(label)); caption.prepend(checkbox); container.append(caption); return { element: container, read: () => checkbox.checked };
  }
  if (schema.type === 'string' || schema.type === 'integer') {
    const input = element('textarea', ''); input.rows = label === 'title' || ['value', 'path', 'hash', 'repository', 'address', 'version', 'component', 'revision'].includes(label) ? 1 : 3;
    input.setAttribute('aria-label', label); input.value = value === undefined || value === null ? '' : String(value);
    const caption = element('label', fieldLabel(label)); caption.append(input); container.append(caption);
    return { element: container, read: () => schema.type === 'integer' ? Number(input.value) : input.value };
  }
  throw new Error(`Unhandled generated form type: ${schema.type}`);
}
