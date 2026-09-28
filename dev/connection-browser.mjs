import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

export async function connectionChecks(browser, storage, origin, evidence) {
  const context = await browser.newContext({ storageState: storage });
  const page = await context.newPage();
  await context.tracing.start({ screenshots: true, snapshots: true });
  const routes = [];
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  let permissive = false;
  await page.routeWebSocket(/\/ws$/, route => {
    const index = routes.length;
    const server = route.connectToServer();
    const state = { route, server, pongs: 0 };
    routes.push(state);
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (frame.Pong) {
        state.pongs++;
        if (!permissive && (index > 0 || state.pongs === 2)) {
          route.send(JSON.stringify({ Pong: { nonce: 'unsolicited-nonce' } }));
          return;
        }
      }
      route.send(message);
    });
  });
  try {
    await page.goto(origin);
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    assert.equal(routes.length, 1, 'The heartbeat fixture must intercept the actual socket');
    await page.getByText('Connection: STALE', { exact: true }).waitFor();
    assert.equal(routes.length, 2, 'Stale connection must start one bounded replacement');
    await page.locator('header summary').click();
    const diagnostics = page.getByRole('region', { name: 'Connection diagnostics', exact: true });
    async function unobscured() {
      const result = await diagnostics.evaluate(node => {
        const bounds = node.getBoundingClientRect(); const header = document.querySelector('header').getBoundingClientRect();
        const overview = node.querySelector('.connection-overview').getBoundingClientRect();
        return { belowHeader: bounds.top >= header.bottom, withinViewport: bounds.left >= 0 && bounds.right <= innerWidth && bounds.bottom <= innerHeight,
          visibleText: node.contains(document.elementFromPoint(overview.left + 10, overview.top + 10)) };
      });
      assert.deepEqual(result, { belowHeader: true, withinViewport: true, visibleText: true }, 'Diagnostics must remain readable below the top bar');
    }
    await unobscured();
    await diagnostics.getByText(/Deadline misses: 50.0%/).waitFor();
    const phase = page.getByRole('progressbar', { name: /^Grace:/ });
    assert.match(await phase.getAttribute('aria-valuetext'), /15.0s$/);
    await page.waitForTimeout(1000);
    const fraction = await phase.evaluate(node => node.value);
    assert.ok(fraction > 0.8 && fraction < 1, `Grace progress uses its 15-second budget: ${fraction}`);
    assert.equal(await page.locator('.connection-card').count(), 2);
    assert.equal(await page.locator('.connection-active').count(), 1);
    await page.screenshot({ path: `${evidence}/connection-stale.png`, fullPage: true });
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.waitForTimeout(1500);
    assert.equal(await page.getByText('Connection: ALIVE', { exact: true }).count(), 1,
      'A newer matching pong must retire older lost heartbeat deadlines');
    assert.equal(routes.length, 2, 'Recovery must not start a repeated replacement loop');
    await page.getByText('Data: current', { exact: true }).waitFor();
    await routes[0].route.close({ code: 1007, reason: 'Permanent protocol failure fixture' });
    await page.getByText('Connection: STOPPED', { exact: true }).waitFor();
    await diagnostics.getByText(/Last peer close: #1 · 1007 · Permanent protocol failure fixture/).waitFor();
    assert.equal(await page.getByRole('progressbar').count(), 0, 'Stopped state has no live countdown');
    const stoppedCount = routes.length;
    await page.waitForTimeout(1000);
    assert.equal(routes.length, stoppedCount, 'Permanent close must stop automatic retries');
    permissive = true;
    await page.getByRole('button', { name: 'Retry connection', exact: true }).click();
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Retry connection', exact: true }).evaluate(node => node === document.activeElement), true);
    await page.screenshot({ path: `${evidence}/connection-diagnostics.png`, fullPage: true });
    const restored = routes.length;
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
    await page.getByText('Connection: SUSPENDED', { exact: true }).waitFor();
    await page.waitForTimeout(500); assert.equal(routes.length, restored);
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
    await page.waitForTimeout(750); assert.equal(routes.length, restored + 1);
    await page.getByText('Data: current', { exact: true }).waitFor();
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await routes.at(-1).route.close({ code: 1011, reason: 'Retry while hidden fixture' });
    await page.getByText('Connection: DEFERRED', { exact: true }).waitFor();
    const hiddenCount = routes.length; await page.waitForTimeout(750); assert.equal(routes.length, hiddenCount);
    await page.evaluate(() => { delete document.visibilityState; document.dispatchEvent(new Event('visibilitychange')); });
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    assert.equal(routes.length, hiddenCount + 1);
    await page.evaluate(() => document.dispatchEvent(new Event('freeze')));
    await page.waitForTimeout(300); assert.equal(routes.length, hiddenCount + 1);
    await page.evaluate(() => document.dispatchEvent(new Event('resume')));
    await page.waitForTimeout(500); assert.equal(routes.length, hiddenCount + 2);
    await page.getByText('Data: current', { exact: true }).waitFor();
    assert.equal(await page.title(), 'CQ — ALIVE');
    await page.setViewportSize({ width: 390, height: 844 });
    const bounds = await diagnostics.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390);
    await unobscured();
    await page.screenshot({ path: `${evidence}/connection-narrow.png`, fullPage: true });
    assert.deepEqual(errors, []);
    console.log('Browser connection: actual heartbeat loss/recovery, phase budget, pool/RTT diagnostics, stable keyboard focus, permanent close/manual retry, pagehide/pageshow, hidden retry, freeze/resume and narrow layout passed');
  } finally {
    await writeFile(`${evidence}/connection-state.txt`, await page.locator('header').textContent());
    await writeFile(`${evidence}/connection-routes.json`, JSON.stringify(routes.map(route => ({ pongs: route.pongs })), null, 2));
    await writeFile(`${evidence}/connection-errors.json`, JSON.stringify(errors, null, 2));
    await context.tracing.stop({ path: `${evidence}/connection-trace.zip` });
    await context.close();
  }
}
