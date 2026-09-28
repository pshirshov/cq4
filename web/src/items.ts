import * as api from '../../generated/typescript/cq/api/index.js';

const PREFIX: Record<api.Ledger, string> = { Milestones: 'M', Ideas: 'I', Defects: 'D', Goals: 'G', Tasks: 'T', Researches: 'RS',
  Hypothesis: 'H', Questions: 'Q', Decisions: 'K', Reviews: 'R', Handoffs: 'HO', OperatorActions: 'OA', Memories: 'MEM', Upstream: 'U' };
const MAX_ITEM_NUMBER = 9223372036854775807n;

export function itemName(id: api.ItemId): string { return PREFIX[id.ledger] + id.number; }
export function parseItem(project: api.ProjectId, text: string): api.ItemId {
  const match = /^([A-Z]+)([1-9][0-9]*)$/.exec(text.trim());
  if (match === null) throw new Error('Enter an exact item ID, such as T1 or RS2.');
  const ledger = api.Ledger_values.find(value => PREFIX[value] === match[1]);
  const number = BigInt(match[2]);
  if (ledger === undefined || number > MAX_ITEM_NUMBER) throw new Error('Unknown item prefix or out-of-range item number.');
  return new api.ItemId(project, ledger, number);
}
