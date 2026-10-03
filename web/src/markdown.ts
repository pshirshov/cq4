import { marked } from 'marked';
import DOMPurify from 'dompurify';

export function markdown(text: string, references: (text: string) => DocumentFragment): DocumentFragment {
  const fragment = DOMPurify.sanitize(marked.parse(text, { async: false }).trim(), { RETURN_DOM_FRAGMENT: true });
  const walker = document.createTreeWalker(fragment, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = [];
  while (walker.nextNode()) {
    const node = walker.currentNode as Text;
    if (node.parentElement === null || node.parentElement.closest('a, code, pre') === null) nodes.push(node);
  }
  for (const node of nodes) node.replaceWith(references(node.data));
  for (const link of fragment.querySelectorAll<HTMLAnchorElement>('a')) link.rel = 'noopener noreferrer';
  return fragment;
}
