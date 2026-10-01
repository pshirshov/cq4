const HOLD_DEADLINE_MS = 5000;
// Longer than the control's HOLD_MS: by then a click mistaken for a hold would have run the action.
export const HOLD_SETTLE_MS = 1500;
// Presses and holds a hold-to-confirm control (web/src/hold-button.ts) with the mouse until its action has run.
export async function hold(page, locator) {
  const outcome = await locator.evaluateHandle((node, deadline) => {
    if (node.dataset.hold !== 'idle') throw new Error(`Expected an idle hold control, found data-hold=${node.dataset.hold}`);
    return {completed: new Promise((resolve, reject) => {
      setTimeout(() => reject(new Error(`Hold did not complete within ${deadline} ms`)), deadline);
      new MutationObserver((records, observer) => {
        // The action may release the control synchronously, so a completed hold can already read idle here.
        if (node.dataset.hold === 'done' || records.some(record => record.oldValue === 'done')) {observer.disconnect(); resolve();}
        else if (node.dataset.hold === 'idle') {observer.disconnect(); reject(new Error('Hold was cancelled before completion'));}
      }).observe(node, {attributeFilter: ['data-hold'], attributeOldValue: true});
    })};
  }, HOLD_DEADLINE_MS);
  await locator.hover(); await page.mouse.down();
  try {await outcome.evaluate(value => value.completed);} finally {await page.mouse.up();}
}
