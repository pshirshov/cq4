import { button, element } from './editor.js';

/**
 * Dialog size variant, chosen explicitly by every caller.
 * - 'standard': the compact dialog (new project, edit conflict, history, graph change, item references, archive, standing requirements, process mode).
 * - 'large': a fixed 90% × 90% viewport dialog whose header stays in place while only the body scrolls, so the dialog
 *   does not resize as its content changes. Required for the question batch, project usage and New item dialogs,
 *   for the Agent models dialog (I17), and for the Help dialog (I10) and any Workset dialog when they are implemented.
 */
export type DialogSize = 'standard' | 'large';

export class Dialog {
  readonly element = element('dialog', '');
  readonly body = element('div', '');
  readonly error = element('p', '');
  readonly actions = element('div', '');
  private readonly heading = element('h2', '');
  private previous: HTMLElement | null = null;
  constructor(size: DialogSize, private readonly closed: () => void) {
    this.element.className = size === 'large' ? 'workspace-dialog large' : 'workspace-dialog'; this.body.className = 'dialog-body';
    const header = element('div', ''); header.className = 'dialog-header'; this.actions.className = 'dialog-actions';
    header.append(this.heading, this.actions, button('Close', () => this.close()));
    this.error.setAttribute('role', 'alert'); this.error.hidden = true; this.element.append(header, this.error, this.body);
    this.element.addEventListener('cancel', event => { event.preventDefault(); this.close(); });
    this.element.addEventListener('click', event => {
      const bounds = this.element.getBoundingClientRect();
      if (event.target === this.element && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) this.close();
    });
  }
  open(title: string): void {
    this.error.textContent = ''; this.error.hidden = true; this.heading.textContent = title; this.element.setAttribute('aria-label', title);
    if (!this.element.open) { this.previous = document.activeElement instanceof HTMLElement ? document.activeElement : null; this.element.showModal(); }
  }
  close(): void {
    if (!this.element.open) return;
    this.element.close(); this.closed();
    if (this.previous !== null && this.previous.isConnected) this.previous.focus();
  }
}
