import * as api from '../../generated/typescript/cq/api/index.js';

export function localSuggestions(source: string, cursor: number): api.QuerySuggestion[] {
  const before = source.slice(0, cursor);
  const match = /(?:^|[\s(])(?:-)?(ledger|status|archived)\s*:\s*([a-z]*)$/i.exec(before);
  if (match === null) return [];
  let quoted = false;
  for (let index = 0; index < match.index; index++) {
    if (quoted && source[index] === '\\') index++;
    else if (source[index] === '"') quoted = !quoted;
  }
  if (quoted) return [];
  const prefix = match[2].toLowerCase(); const start = cursor - prefix.length;
  let end = cursor;
  if (prefix.length === 0) while (end < source.length && /\s/.test(source[end])) end++;
  if (source[end] === '"') return [];
  while (end < source.length && !/[\s():"]/.test(source[end])) end++;
  let values: string[];
  switch (match[1].toLowerCase()) {
    case 'ledger': values = [...api.Ledger_values]; break;
    case 'archived': values = ['all', 'false', 'true']; break;
    case 'status': values = [...api.MilestoneStatus_values, ...api.IdeaStatus_values, ...api.DefectStatus_values, ...api.GoalStatus_values,
      ...api.TaskStatus_values, ...api.ResearchStatus_values, ...api.HypothesisStatus_values, ...api.QuestionStatus_values,
      ...api.DecisionStatus_values, ...api.ReviewStatus_values, ...api.HandoffStatus_values, ...api.OperatorActionStatus_values,
      ...api.MemoryStatus_values, ...api.UpstreamStatus_values]; break;
    default: throw new Error('Unexpected local completion field');
  }
  return [...new Set(values.map(value => value.toLowerCase()))].filter(value => value.startsWith(prefix)).sort()
    .map(value => new api.QuerySuggestion(api.QuerySuggestionKind.Value, new api.QuerySpan(start, end), value, value));
}

export function suggestionKey(value: api.QuerySuggestion): string { return JSON.stringify([value.kind, value.span.start, value.span.end, value.text]); }
