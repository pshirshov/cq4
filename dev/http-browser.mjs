import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { writeFile } from 'node:fs/promises';

const origin = process.env.CQ_ORIGIN;
const evidence = process.env.CQ_BROWSER_EVIDENCE;
const browser = await chromium.launch({ headless: true, args: ['--host-resolver-rules=MAP cq-http.test 127.0.0.1', '--no-proxy-server'] });
const page = await browser.newPage();
page.setDefaultTimeout(10000);
const errors = [];
const frames = [];
page.on('pageerror', error => errors.push(String(error)));
page.on('websocket', socket => socket.on('framesent', frame => frames.push(JSON.parse(String(frame.payload)))));
try {
  await page.goto(origin);
  const capabilities = await page.evaluate(() => ({ secure: isSecureContext, uuid: typeof crypto.randomUUID, random: typeof crypto.getRandomValues }));
  await writeFile(`${evidence}/http-capabilities.json`, JSON.stringify(capabilities));
  assert.deepEqual(capabilities, { secure: false, uuid: 'undefined', random: 'function' });
  await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  const name = `HTTP project ${Date.now()}`;
  await page.getByRole('button', { name: 'New project', exact: true }).click();
  await page.getByLabel('New project name').fill(name);
  await page.getByRole('button', { name: 'Create project', exact: true }).click();
  await page.getByLabel('Project', { exact: true }).getByRole('option', { name, exact: true }).waitFor({ state: 'attached' });
  await page.getByText('Data: current', { exact: true }).waitFor();
  for (const title of ['HTTP A', 'HTTP B']) {
    await page.getByRole('button', { name: 'New item', exact: true }).click();
    await page.getByLabel('title', { exact: true }).fill(title);
    await page.getByLabel('acceptance entry', { exact: true }).fill('Works over ordinary HTTP.');
    await page.getByRole('button', { name: 'Save item', exact: true }).click();
    await page.getByRole('heading', { name: `${title === 'HTTP A' ? 'T1' : 'T2'} · ${title}`, exact: true }).waitFor();
  }
  await page.getByRole('combobox', { name: 'Relationship', exact: true }).selectOption('RelatesTo');
  await page.getByLabel('Target item', { exact: true }).fill('T1');
  await page.getByRole('button', { name: 'Preview relationship', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm graph change', exact: true }).click();
  await page.getByRole('status').filter({ hasText: 'Graph change saved' }).waitFor();
  await page.getByRole('button', { name: 'Open T1', exact: true }).waitFor();
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const identifiers = [await page.evaluate(() => localStorage.getItem('cq-browser-session')),
    await page.getByLabel('Project', { exact: true }).inputValue(),
    ...frames.flatMap(frame => Object.values(frame).flatMap(body => body.id ? [body.id.value] : []))];
  assert.ok(identifiers.length >= 8);
  assert.ok(identifiers.every(id => uuid.test(id)), JSON.stringify(identifiers));
  assert.deepEqual(errors, []);
  await page.screenshot({ path: `${evidence}/http-workspace.png` });
  console.log('HTTP browser: unavailable randomUUID, login, WebSocket, project/items, graph mutation and UUID v4 identifiers passed');
} finally {
  await writeFile(`${evidence}/http-browser-observation.json`, JSON.stringify({ errors, text: await page.locator('body').innerText(), frames }, null, 2));
  await browser.close();
}
