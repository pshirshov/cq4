import * as api from '../../generated/typescript/cq/api/index.js';

const PATHS: Readonly<Record<api.Ledger | 'All' | 'New' | 'Usage' | 'Close' | 'Work' | 'Claimed' | 'Running', string>> = {
  All: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  New: 'M12 4v16 M4 12h16', Usage: 'M4 20V10 M12 20V4 M20 20v-8', Close: 'M6 6l12 12 M18 6L6 18',
  Work: 'M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18 M12 7v5l3 3', Claimed: 'M6 11h12v10H6z M9 11V7a3 3 0 0 1 6 0v4',
  Running: 'M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18 M10 8l6 4-6 4z',
  Milestones: 'M5 21V3 M5 3h14l-3 5 3 5H5',
  Ideas: 'M9 18h6 M9 21h6 M8 14a6 6 0 1 1 8 0l-1 2H9z',
  Defects: 'M8 8h8v7a4 4 0 0 1-8 0z M9 8V5h6v3 M3 10h5 M16 10h5 M3 16h5 M16 16h5 M6 3l3 2 M18 3l-3 2 M12 8v11',
  Goals: 'M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20 M12 7a5 5 0 1 0 0 10 5 5 0 1 0 0-10 M12 11v2',
  Tasks: 'M4 4h16v16H4z M7 12l3 3 7-7',
  Researches: 'M5 3h14 M9 3v7L4 19v2h16v-2l-5-9V3 M7 15h10',
  Hypothesis: 'M4 18l8-14 8 14z M12 10v3 M12 15v1',
  Questions: 'M8 7a4 4 0 1 1 6 4l-2 2v2 M12 18v1',
  Decisions: 'M5 5h14v14H5z M8 11l3 3 5-6',
  Reviews: 'M4 3h12v8 M4 3v18h9 M7 7h6 M7 11h3 M14 16l3 3 5-7',
  Handoffs: 'M3 8h14l-4-4 M17 8l-4 4 M21 16H7l4 4 M7 16l4-4',
  OperatorActions: 'M12 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8 M4 21v-3a8 5 0 0 1 16 0v3',
  Memories: 'M4 4h12v17H4z M8 4v17 M16 8h4v13h-4',
  Upstream: 'M12 21V3 M5 10l7-7 7 7',
};

export function icon(kind: keyof typeof PATHS): SVGSVGElement {
  const node = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  node.setAttribute('viewBox', '0 0 24 24'); node.setAttribute('aria-hidden', 'true');
  node.setAttribute('fill', 'none'); node.setAttribute('stroke', 'currentColor'); node.setAttribute('stroke-width', '1.6');
  const path = document.createElementNS(node.namespaceURI, 'path'); path.setAttribute('d', PATHS[kind]); node.append(path); return node;
}
