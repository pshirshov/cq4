import { button, element } from './editor.js';

export const HOLD_MS = 1000;
const HOLD_LABEL = 'Hold to confirm';
type HoldState = 'idle' | 'holding' | 'done';
function holdKey(event: KeyboardEvent): boolean { return event.key === ' ' || event.key === 'Enter'; }

/**
 * A button whose action needs a deliberate press: it runs once the primary pointer button, Space or Enter has been
 * held for HOLD_MS. Releasing, leaving the control, losing focus or pressing Escape earlier cancels. An ordinary click
 * does nothing. A click that no pointer produced (assistive-technology activation, reported with a zero click count)
 * starts the same timed countdown, which Escape or loss of focus cancels.
 */
export function holdButton(text: string, action: () => void): HTMLButtonElement {
  let started: number | null = null; let frame = 0;
  const node = button(text, event => { if (event.detail === 0) start(); });
  const progress = element('progress', ''); progress.max = 1; progress.setAttribute('aria-label', HOLD_LABEL);
  // The indicator's label would otherwise join the button's accessible name; the title keeps the instruction exposed.
  node.setAttribute('aria-label', text); node.title = HOLD_LABEL; node.append(progress);
  const show = (state: HoldState, value: number): void => { node.dataset.hold = state; progress.value = value; };
  const release = (): void => { cancelAnimationFrame(frame); started = null; show('idle', 0); };
  const tick = (): void => {
    if (started === null) return;
    if (node.disabled || !node.isConnected) { release(); return; }
    const elapsed = performance.now() - started;
    if (elapsed < HOLD_MS) { show('holding', elapsed / HOLD_MS); frame = requestAnimationFrame(tick); return; }
    started = null; show('done', 1); action();
  };
  const start = (): void => {
    if (node.disabled || started !== null) return;
    started = performance.now(); show('holding', 0); frame = requestAnimationFrame(tick);
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
