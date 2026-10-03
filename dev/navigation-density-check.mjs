// Behavioral Active Blackbox Good Communication: actual App shell and Chromium; controlled hello boundary, no database.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { chromium } from 'playwright';
import { densityChecks } from './density-browser.mjs';

const evidence = process.env.CQ_BROWSER_EVIDENCE;
assert.ok(evidence, 'Navigation density check needs an evidence directory');
const bundle = await build({ entryPoints: ['web/src/app.ts'], bundle: true, format: 'esm', write: false });
const assets = new Map([
  ['/', { type: 'text/html', body: await readFile('web/index.html', 'utf8') }],
  ['/app.js', { type: 'text/javascript', body: bundle.outputFiles[0].text }],
  ['/style.css', { type: 'text/css', body: await readFile('web/style.css', 'utf8') }],
  ['/api/hello', { type: 'application/json', body: JSON.stringify({ version: '0.1.0', supported: ['0.1.0'] }) }],
]);
const server = createServer((request, response) => {
  const asset = assets.get(request.url);
  response.writeHead(asset === undefined ? 404 : 200, { 'Content-Type': asset === undefined ? 'text/plain' : asset.type });
  response.end(asset === undefined ? 'Missing fixture route' : asset.body);
});
server.on('upgrade', (_request, socket) => socket.destroy());
server.listen(0, '127.0.0.1'); await once(server, 'listening');
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  const navigation = page.getByRole('navigation', { name: 'Navigation', exact: true });
  await navigation.waitFor();
  assert.ok(await navigation.getByRole('button', { name: 'Drivers and worksets', exact: true }).isVisible());
  assert.ok(await navigation.getByRole('button', { name: 'Upstream', exact: true }).isVisible());
  await densityChecks(page, evidence);
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
  await new Promise((resolve, reject) => server.close(error => error === undefined ? resolve() : reject(error)));
}
