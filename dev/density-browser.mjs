import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

export async function densityChecks(page, evidence) {
  const previous = page.viewportSize();
  const observations = [];
  try {
    for (const viewport of [{ width: 1366, height: 768 }, { width: 1280, height: 720 }]) {
      await page.setViewportSize(viewport);
      const navigation = page.getByRole('navigation', { name: 'Navigation', exact: true });
      await navigation.evaluate(node => { node.scrollTop = 0; });
      const controls = await navigation.getByRole('button').evaluateAll(nodes => nodes.map(node => {
        const bounds = node.getBoundingClientRect();
        return { label: node.textContent, top: bounds.top, bottom: bounds.bottom, height: bounds.height };
      }));
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
      observations.push({ viewport, controls, overflow });
      await page.screenshot({ path: `${evidence}/density-${viewport.width}.png` });
    }
    await writeFile(`${evidence}/density-results.json`, JSON.stringify(observations, null, 2));
    for (const { viewport, controls, overflow } of observations) {
      assert.equal(overflow, false, `Horizontal page overflow at ${viewport.width}`);
      assert.ok(controls.length > 0);
      const clipped = controls.filter(control => control.top < 0 || control.bottom > viewport.height);
      assert.deepEqual(clipped, [], `Navigation controls require scrolling at ${viewport.width}×${viewport.height}`);
    }
    console.log('Laptop density: all navigation buttons fit at 1366×768 and 1280×720 without horizontal page overflow');
  } finally {
    if (previous !== null) await page.setViewportSize(previous);
  }
}
