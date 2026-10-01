import { button, element } from './editor.js';

export const HOLD_MS = 1000;
// A longer gap between frames means the page was not rendering (hidden tab, suspended window): it counts as no held time.
const MAX_FRAME_MS = 250;
const HOLD_LABEL = 'Hold to confirm';
const HOLDING = 'Confirming. Escape cancels.';
const CONFIRMED = 'Confirmed.';
const RELEASED = 'Not confirmed.';
type HoldState = 'idle' | 'holding' | 'done';
function holdKey(event: KeyboardEvent): boolean { return event.key === ' ' || event.key === 'Enter'; }

/**
 * A button whose action needs a deliberate press: it runs once the primary pointer button, Space or Enter has been
 * held for HOLD_MS. Releasing, leaving the control, losing focus or pressing Escape earlier cancels. An ordinary click
 * does nothing. A click that no pointer produced (assistive-technology activation, reported with a zero click count)
 * starts the same timed countdown, which Escape or loss of focus cancels.
 *
 * A hold counts only time across rendered frames of a control that is enabled and rendered in a visible document:
 * closing its dialog, hiding or removing it, or hiding the page cancels the hold.
 */
export function holdButton(text: string, action: () => void): HTMLButtonElement {
  let held: number | null = null; let last = 0; let frame = 0;
  const node = button(text, event => { if (event.detail === 0) start(); });
  const progress = element('progress', ''); progress.max = 1; progress.setAttribute('aria-label', HOLD_LABEL);
  // The indicator's label would otherwise join the button's accessible name; the title keeps the instruction exposed.
  node.setAttribute('aria-label', text); node.title = HOLD_LABEL; node.append(progress);
  // A button's content is presentational to assistive technology, so the hold is announced by a polite live region
  // placed next to the control: inside the same modal dialog, which makes everything outside it inert.
  const status = element('span', ''); status.className = 'visually-hidden'; status.setAttribute('aria-live', 'polite');
  const show = (state: HoldState, value: number): void => { node.dataset.hold = state; progress.value = value; };
  // A closed dialog and a hidden panel keep their controls connected; neither renders them.
  const operable = (): boolean => !node.disabled && !document.hidden && node.checkVisibility();
  const stop = (): void => { cancelAnimationFrame(frame); held = null; document.removeEventListener('visibilitychange', hidden); };
  const release = (): void => { if (held !== null) status.textContent = RELEASED; stop(); show('idle', 0); };
  const hidden = (): void => { if (document.hidden) release(); };
  const tick = (): void => {
    if (held === null) return;
    if (!operable()) { release(); return; }
    const now = performance.now(); const elapsed = now - last; last = now;
    if (elapsed <= MAX_FRAME_MS) held += elapsed;
    if (held < HOLD_MS) {
      // The region is filled a frame after its insertion, so that the change is announced.
      if (status.textContent !== HOLDING) status.textContent = HOLDING;
      show('holding', held / HOLD_MS); frame = requestAnimationFrame(tick); return;
    }
    stop(); show('done', 1); status.textContent = CONFIRMED; action();
  };
  const start = (): void => {
    if (held !== null || !operable()) return;
    held = 0; last = performance.now(); show('holding', 0);
    if (status.previousSibling !== node) { status.textContent = ''; node.after(status); }
    document.addEventListener('visibilitychange', hidden); frame = requestAnimationFrame(tick);
  };
  show('idle', 0);
  node.addEventListener('pointerdown', event => { if (event.isPrimary && event.button === 0) start(); });
  for (const type of ['pointerup', 'pointerleave', 'pointercancel', 'blur']) node.addEventListener(type, release);
  node.addEventListener('keyup', event => { if (holdKey(event)) event.preventDefault(); release(); });
  node.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      // Escape cancels the hold only; without a hold it keeps its ordinary meaning, such as closing the dialog.
      if (node.dataset.hold !== 'idle') { event.preventDefault(); event.stopPropagation(); release(); }
      return;
    }
    if (!holdKey(event)) return;
    // Suppresses the click a button synthesizes for these keys, which would otherwise start a countdown on its own.
    event.preventDefault();
    if (!event.repeat) start();
  });
  return node;
}
