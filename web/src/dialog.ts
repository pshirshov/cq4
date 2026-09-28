import { button, element } from './editor.js';

export class Dialog {
  readonly element = element('dialog', '');
  readonly body = element('div', '');
  readonly error = element('p', '');
  private readonly heading = element('h2', '');
  private previous: HTMLElement | null = null;
  constructor(private readonly closed: () => void) {
    this.element.className = 'workspace-dialog'; this.body.className = 'dialog-body';
    const header = element('div', ''); header.className = 'dialog-header'; header.append(this.heading, button('Close', () => this.close()));
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
