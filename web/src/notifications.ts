import { button, element } from './editor.js';

const SUCCESS_DURATION_MS = 8000;

export class Notifications {
  readonly element = element('aside', '');
  private readonly message = element('div', '');
  private timer: number | null = null;

  constructor() {
    this.element.className = 'notification-toast'; this.element.popover = 'manual';
    this.element.setAttribute('aria-label', 'Notification');
    this.element.append(this.message, button('Dismiss', () => this.clear()));
  }
  show(content: string | Node[], kind: 'success' | 'info' | 'error'): void {
    this.clear();
    this.message.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    this.message.replaceChildren(...(typeof content === 'string' ? [document.createTextNode(content)] : content));
    this.reveal();
    if (kind === 'success') this.timer = window.setTimeout(() => this.clear(), SUCCESS_DURATION_MS);
  }
  reveal(): void { if (this.element.isConnected && this.message.hasChildNodes()) this.element.showPopover(); }
  clear(): void {
    if (this.timer !== null) { window.clearTimeout(this.timer); this.timer = null; }
    if (this.element.matches(':popover-open')) this.element.hidePopover();
    this.message.replaceChildren();
  }
}
