import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { itemName } from './items.js';
import { element, type Json } from './editor.js';

export const CONTENT_FIELDS = {
  Milestone: ['objective'], Idea: ['outcome', 'motivation'], Defect: ['observed', 'expected', 'reproduction', 'cause', 'resolution'],
  Goal: ['outcome', 'scope', 'acceptance'], Task: ['acceptance', 'result', 'validation'], Research: ['question', 'findings', 'conclusion', 'recommendation'],
  Hypothesis: ['claim', 'rationale', 'evidence', 'adjudication'], Question: ['prompt', 'context', 'alternatives', 'answer'],
  Decision: ['choice', 'rationale', 'alternatives'], Review: ['subjects', 'candidate', 'findings', 'summary'],
  Handoff: ['outcome', 'remaining', 'blockers'], OperatorAction: ['action', 'expectedEvidence', 'confirmation', 'observedEvidence'],
  Memory: ['knowledge', 'applicability', 'evidence'], Upstream: ['component', 'version', 'reproduction', 'report', 'outcome'],
} as const;
export type ItemKind = keyof typeof CONTENT_FIELDS;
export const FIELD_LABELS: Record<string, string> = {
  body: 'Description', content: 'Item type', status: 'Status', severity: 'Severity', title: 'Title', labels: 'Labels', archived: 'Archived',
  acceptance: 'Acceptance criteria', observed: 'Observed behavior', expected: 'Expected behavior', reproduction: 'Reproduction',
  cause: 'Cause', resolution: 'Resolution evidence', validation: 'Validation evidence', expectedEvidence: 'Expected evidence',
  observedEvidence: 'Observed evidence', subjects: 'Reviewed item revisions', candidate: 'Candidate commit', citations: 'Sources',
  origin: 'Evidence source', description: 'Observation', remaining: 'Remaining work', report: 'Upstream report',
};
export function fieldLabel(name: string): string { return FIELD_LABELS[name] === undefined ? (name[0].toUpperCase() + name.slice(1)).replace(/([a-z])([A-Z])/g, '$1 $2') : FIELD_LABELS[name]; }
export function jsonObject(value: Json | undefined): { [key: string]: Json } {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : {};
}
function source(value: Json): HTMLElement {
  const entry = jsonObject(value); const node = element('span', ''); node.className = 'source-reference';
  if ('Url' in entry) {
    const address = String(jsonObject(entry.Url).address); let safe = false;
    try { safe = ['http:', 'https:'].includes(new URL(address).protocol); } catch { /* Keep invalid addresses visible as text. */ }
    if (safe) { const link = element('a', address); link.href = address; link.target = '_blank'; link.rel = 'noopener noreferrer'; node.append(link); }
    else node.textContent = address;
  } else if ('File' in entry) {
    const file = jsonObject(entry.File); node.append(element('code', String(file.path)));
    if (file.revision !== null && file.revision !== undefined) node.append(document.createTextNode(` @ ${file.revision}`));
  } else if ('Commit' in entry) {
    const commit = jsonObject(entry.Commit); node.append(element('span', String(commit.repository)), document.createTextNode(' · '), element('code', String(commit.hash)));
  } else if ('Artifact' in entry) node.append(document.createTextNode('Artifact '), element('code', String(jsonObject(jsonObject(entry.Artifact).id).value)));
  return node;
}
type TextRenderer = (text: string) => Node;
export function renderValue(name: string, value: Json, renderText: TextRenderer): HTMLElement {
  const node = element('div', ''); node.className = 'field-value';
  if (Array.isArray(value)) {
    if (value.length === 0) return element('p', 'None recorded.');
    const list = element('ul', ''); list.className = 'semantic-list';
    for (const entry of value) {
      const row = element('li', ''); const data = jsonObject(entry);
      if (typeof entry === 'string') row.append(renderText(entry));
      else if ('description' in data) {
        row.className = 'evidence-entry'; const description = element('p', ''); description.append(renderText(String(data.description)));
        row.append(description, element('span', fieldLabel(String(data.origin))));
        if (Array.isArray(data.citations) && data.citations.length > 0) row.append(renderValue('citations', data.citations, renderText));
      } else if (name === 'subjects') {
        const item = api.ItemId_JsonCodec.instance.decode(BaboonCodecContext.Default, data.item); row.append(renderText(`${itemName(item)} · revision ${jsonObject(data.revision).value}`));
      } else row.append(source(entry));
      list.append(row);
    }
    node.append(list);
  } else if (typeof value === 'object' && value !== null) node.append(source(name === 'candidate' ? { Commit: value } : name === 'report' ? { Url: value } : value));
  else node.append(renderText(String(value)));
  return node;
}
export function itemView(draft: api.ItemDraft, renderText: TextRenderer): HTMLElement {
  const node = element('div', ''); node.className = 'item-document';
  const encoded = api.Content_JsonCodec.instance.encode(BaboonCodecContext.Default, draft.content) as Json;
  const [kind, content] = Object.entries(jsonObject(encoded))[0]; const values = jsonObject(content);
  const meta = element('div', ''); meta.className = 'item-metadata';
  for (const text of [kind, String(values.status), ...(values.severity === undefined ? [] : [String(values.severity)]), ...(draft.archived ? ['Archived'] : [])]) {
    const badge = element('span', text); badge.className = 'badge'; meta.append(badge);
  }
  for (const label of draft.labels) { const badge = element('span', label); badge.className = 'label-badge'; meta.append(badge); }
  node.append(meta);
  const section = (name: string, value: Json): void => {
    if (value === null || value === '' || (Array.isArray(value) && value.length === 0)) return;
    const block = element('section', ''); block.className = 'document-field'; block.dataset.field = name; block.append(element('h3', fieldLabel(name)), renderValue(name, value, renderText)); node.append(block);
  };
  section('body', draft.body);
  for (const name of CONTENT_FIELDS[kind as ItemKind]) if (values[name] !== undefined) section(name, values[name]);
  section('citations', [...draft.citations].map(value => api.Citation_JsonCodec.instance.encode(BaboonCodecContext.Default, value) as Json));
  return node;
}
