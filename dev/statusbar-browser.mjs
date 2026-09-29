// Behavioral-Active Blackbox Good-Communication; regression D50.
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';

export async function statusBarChecks(page, evidence) {
  const metrics = page.getByRole('region', {name: 'Usage metrics', exact: true});
  await metrics.getByText(/^Observed /).waitFor();
  assert.equal(await metrics.evaluate(node => node.closest('header') !== null), false, 'Status must not occupy the search header');
  const footer = page.getByRole('contentinfo', {name: 'Workspace status', exact: true});
  assert.equal(await footer.getByRole('region', {name: 'Usage metrics', exact: true}).count(), 1);
  const original = page.viewportSize(); const cases = [];
  try {
    for (const size of [{width: 1366, height: 768}, {width: 1280, height: 720}, {width: 390, height: 844}]) {
      await page.setViewportSize(size);
      const geometry = await footer.evaluate(node => ({top: node.getBoundingClientRect().top,
        bottom: node.getBoundingClientRect().bottom, height: node.getBoundingClientRect().height,
        pageWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth, viewportHeight: innerHeight}));
      assert.ok(geometry.pageWidth <= geometry.viewportWidth, 'Status must not cause horizontal page overflow');
      assert.ok(geometry.height <= 48, 'The status bar must remain compact');
      if (size.width > 920) assert.equal(geometry.bottom, geometry.viewportHeight, 'Status bar must sit at the viewport bottom');
      const spans = await metrics.locator('span').evaluateAll(nodes => nodes.map(node => ({text: node.textContent,
        width: node.clientWidth, title: node.title})));
      assert.equal(spans.length, 3); assert.ok(spans.every(span => span.width > 0));
      assert.match(spans[0].text, /^Data: /); assert.match(spans[1].text, /^Usage · /);
      assert.equal(spans[1].title, spans[1].text, 'Truncated usage must retain its complete hover text');
      assert.match(spans[2].text, /^Observed .*cursor /);
      cases.push({size, geometry}); await page.screenshot({path: `${evidence}/status-${size.width}.png`, fullPage: true});
    }
    await writeFile(`${evidence}/statusbar-results.json`, JSON.stringify({cases}, null, 2));
  } finally { await page.setViewportSize(original); }
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  const {chromium} = await import('playwright');
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage({viewport: {width: 1366, height: 768}});
  try {
    await page.goto(process.env.CQ_ORIGIN);
    await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
    await page.getByText('Data: current', {exact: true}).waitFor();
    await statusBarChecks(page, process.env.CQ_BROWSER_EVIDENCE);
    const metrics = page.getByRole('region', {name: 'Usage metrics', exact: true});
    await page.context().setOffline(true);
    await metrics.getByText('Data: stale', {exact: true}).waitFor();
    await metrics.getByText(/^Stale · Observed /).waitFor();
    await page.context().setOffline(false);
    await metrics.getByText('Data: current', {exact: true}).waitFor();
    await metrics.getByText(/^Observed /).waitFor();
    console.log('Status bar layout and stale/reconnect updates passed');
  } finally { await browser.close(); }
}
