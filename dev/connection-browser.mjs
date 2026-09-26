import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';

export async function connectionChecks(browser, storage, origin, evidence) {
  const context = await browser.newContext({ storageState: storage });
  const page = await context.newPage();
  await context.tracing.start({ screenshots: true, snapshots: true });
  const routes = [];
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
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.waitForTimeout(1500);
    assert.equal(await page.locator('[aria-label^="Connection "]').textContent(), 'Connection: ALIVE',
      'A newer matching pong must retire older lost heartbeat deadlines');
    assert.equal(routes.length, 2, 'Recovery must not start a repeated replacement loop');
    await page.getByText('Data: current', { exact: true }).waitFor();
    await routes[0].route.close({ code: 1007, reason: 'Permanent protocol failure fixture' });
    await page.getByText('Connection: STOPPED', { exact: true }).waitFor();
    const stoppedCount = routes.length;
    await page.waitForTimeout(1000);
    assert.equal(routes.length, stoppedCount, 'Permanent close must stop automatic retries');
    permissive = true;
    await page.locator('header summary').click();
    await page.getByRole('button', { name: 'Retry connection', exact: true }).click();
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByText('Data: current', { exact: true }).waitFor();
    console.log('Browser connection: dropped/unsolicited pong, bounded overlap, newer-pong recovery, permanent close and manual recovery passed');
  } finally {
    await writeFile(`${evidence}/connection-state.txt`, await page.locator('header').textContent());
    await writeFile(`${evidence}/connection-routes.json`, JSON.stringify(routes.map(route => ({ pongs: route.pongs })), null, 2));
    await context.tracing.stop({ path: `${evidence}/connection-trace.zip` });
    await context.close();
  }
}
