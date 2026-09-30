import * as api from '../../generated/typescript/cq/api/index.js';

// Renders a server fault as text for operators; the JSON encoding is not shown.
export function faultMessage(fault: api.Fault): string {
  if (fault instanceof api.Fault_QuerySyntax) return `Query syntax: ${fault.diagnostic.message}`;
  if (fault instanceof api.Fault_IntegrationPending) return `Integration pending: ${fault.id.value}`;
  if (fault instanceof api.Fault_Denied) return `Denied: ${fault.message}`;
  if (fault instanceof api.Fault_Missing) return `Missing: ${fault.message}`;
  if (fault instanceof api.Fault_Conflict) return `Conflict: ${fault.message}`;
  if (fault instanceof api.Fault_StaleFence) return `Stale claim: ${fault.message}`;
  if (fault instanceof api.Fault_Resync) return `Out of date: ${fault.message}`;
  if (fault instanceof api.Fault_Limit) return `Limit: ${fault.message}`;
  if (fault instanceof api.Fault_Invalid) return fault.message;
  throw new Error('Unknown fault kind');
}
