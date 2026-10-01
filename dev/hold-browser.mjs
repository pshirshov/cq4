// Behavioral-Active Blackbox Good-Communication; I22 press-and-hold confirmation control.
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {hold} from './hold.mjs';
const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
// The page's content security policy admits no inline script, so the bundled module enters through the debugger.
const bundled = await build({entryPoints: ['web/src/hold-button.ts'], bundle: true, write: false, format: 'iife', globalName: 'holdModule'});
const browser = await chromium.launch({headless: true}); const page = await browser.newPage({viewport: {width: 1366, height: 768}}); page.setDefaultTimeout(4000);
const cases = [], failures = [];
async function check(name, body) {
  try {await body(); cases.push(name);} catch (error) {failures.push({name, error: String(error)});}
  finally {await page.mouse.up(); await page.keyboard.up('Space'); await page.keyboard.up('Enter'); await control.evaluate(node => {node.disabled = false; node.blur();}); await page.mouse.move(0, 0);}
}
const control = page.getByRole('button', {name: 'Hold fixture', exact: true});
const state = () => control.evaluate(node => ({hold: node.dataset.hold, value: node.querySelector('progress').value, ...window.holdFixture}));
const holding = minimum => page.waitForFunction(value => {
  const node = document.getElementById('hold-fixture'); return node.dataset.hold === 'holding' && node.querySelector('progress').value >= value;
}, minimum);
const settled = holdMs => page.waitForTimeout(holdMs + 400);
async function unchanged(holdMs, before) {
  await settled(holdMs); const after = await state();
  assert.deepEqual({hold: after.hold, value: after.value, count: after.count}, {hold: 'idle', value: 0, count: before.count});
}
try {
  await page.goto(origin); await page.getByLabel('Operator token').waitFor();
  await page.evaluate(new TextDecoder().decode(bundled.outputFiles[0].contents) + '\nwindow.holdModule = holdModule;');
  const holdMs = await page.evaluate(() => {
    window.holdFixture = {count: 0, pressed: 0, fired: 0, samples: []};
    const node = window.holdModule.holdButton('Hold fixture', () => {window.holdFixture.count++; window.holdFixture.fired = performance.now();});
    node.id = 'hold-fixture';
    for (const type of ['pointerdown', 'keydown']) node.addEventListener(type, event => {if (!event.repeat) window.holdFixture.pressed = performance.now();}, {capture: true});
    const progress = node.querySelector('progress');
    new MutationObserver(() => {if (node.dataset.hold === 'holding') window.holdFixture.samples.push(progress.value);}).observe(node, {attributeFilter: ['data-hold']});
    document.body.append(node); return window.holdModule.HOLD_MS;
  });
  await check('control is a labelled button with an idle progress indicator', async () => {
    assert.equal(holdMs, 1000);
    assert.deepEqual(await control.evaluate(node => [node.tagName, node.type, node.textContent, node.title]), ['BUTTON', 'button', 'Hold fixture', 'Hold to confirm']);
    const progress = control.locator('progress');
    assert.deepEqual(await progress.evaluate(node => [node.getAttribute('aria-label'), node.max, node.value]), ['Hold to confirm', 1, 0]);
    assert.equal((await state()).hold, 'idle'); assert.equal(await progress.isVisible(), true);
  });
  await check('plain click does not invoke the action', async () => {
    const before = await state(); await control.click(); await unchanged(holdMs, before);
  });
  await check('hold of HOLD_MS invokes the action exactly once with rising progress', async () => {
    const before = await state(); await control.evaluate(() => {window.holdFixture.samples = [];});
    await hold(page, control); await settled(holdMs); const after = await state();
    assert.equal(after.count, before.count + 1); assert.ok(after.fired - after.pressed >= holdMs, `Fired after ${after.fired - after.pressed} ms`);
    assert.equal(after.hold, 'idle'); assert.equal(after.value, 0);
    assert.ok(after.samples.length >= 3, `Only ${after.samples.length} progress samples`);
    assert.deepEqual(after.samples, [...after.samples].sort((left, right) => left - right), 'Progress must not fall during a hold');
    assert.ok(after.samples[0] < 0.5 && after.samples.at(-1) > 0.5 && after.samples.at(-1) < 1, JSON.stringify([after.samples[0], after.samples.at(-1)]));
  });
  await check('pointer hold stays done until release and does not repeat', async () => {
    const before = await state(); await control.hover(); await page.mouse.down();
    await page.waitForFunction(() => document.getElementById('hold-fixture').dataset.hold === 'done'); await settled(holdMs);
    const held = await state(); assert.deepEqual([held.hold, held.value, held.count], ['done', 1, before.count + 1]);
    await page.mouse.up(); const after = await state(); assert.deepEqual([after.hold, after.value, after.count], ['idle', 0, before.count + 1]);
    await unchanged(holdMs, after);
  });
  await check('releasing at about half resets progress and state', async () => {
    const before = await state(); await control.hover(); await page.mouse.down(); await holding(0.4); await page.mouse.up();
    const after = await state(); assert.deepEqual([after.hold, after.value], ['idle', 0]); await unchanged(holdMs, before);
  });
  await check('leaving the control cancels', async () => {
    const before = await state(); await control.hover(); await page.mouse.down(); await holding(0.2); await page.mouse.move(0, 0);
    assert.equal((await state()).hold, 'idle'); await unchanged(holdMs, before);
  });
  await check('secondary pointer button does not start', async () => {
    const before = await state(); await control.dispatchEvent('pointerdown', {isPrimary: true, button: 2}); await unchanged(holdMs, before);
  });
  for (const key of ['Space', 'Enter']) {
    await check(`holding ${key} fires once despite key auto-repeat`, async () => {
      const before = await state(); await control.focus(); await page.keyboard.down(key);
      for (let repeat = 0; repeat < 30; repeat++) {await page.waitForTimeout(50); await page.keyboard.down(key);}
      const held = await state(); assert.equal(held.count, before.count + 1); assert.ok(held.fired - held.pressed >= holdMs);
      await page.keyboard.up(key); const after = await state(); assert.deepEqual([after.hold, after.value], ['idle', 0]); await unchanged(holdMs, after);
    });
    await check(`releasing ${key} early cancels and synthesizes no click`, async () => {
      const before = await state(); await control.focus(); await page.keyboard.down(key); await holding(0.3); await page.keyboard.up(key);
      await unchanged(holdMs, before);
    });
  }
  await check('Escape cancels a held key', async () => {
    const before = await state(); await control.focus(); await page.keyboard.down('Enter'); await holding(0.3); await page.keyboard.press('Escape');
    assert.equal((await state()).hold, 'idle');
    for (let repeat = 0; repeat < 10; repeat++) {await page.waitForTimeout(50); await page.keyboard.down('Enter');}
    await unchanged(holdMs, before);
  });
  await check('blur cancels a held pointer', async () => {
    const before = await state(); await control.hover(); await page.mouse.down(); await holding(0.3); await control.evaluate(node => node.blur());
    assert.equal((await state()).hold, 'idle'); await unchanged(holdMs, before);
  });
  await check('disabled control never starts', async () => {
    const before = await state(); await control.evaluate(node => {node.disabled = true;});
    const box = await control.boundingBox(); await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.down();
    await control.dispatchEvent('pointerdown', {isPrimary: true, button: 0}); await control.dispatchEvent('keydown', {key: 'Enter'}); await control.dispatchEvent('click');
    assert.equal((await state()).hold, 'idle'); await unchanged(holdMs, before);
  });
  await check('disabling during a hold cancels it', async () => {
    const before = await state(); await control.hover(); await page.mouse.down(); await holding(0.3); await control.evaluate(node => {node.disabled = true;});
    await unchanged(holdMs, before);
  });
  await check('press-free click runs the timed countdown once', async () => {
    const before = await state(); await control.evaluate(node => {window.holdFixture.pressed = performance.now(); node.click();});
    assert.equal((await state()).hold, 'holding'); await settled(holdMs); const after = await state();
    assert.equal(after.count, before.count + 1); assert.ok(after.fired - after.pressed >= holdMs);
  });
  for (const cancel of ['Escape', 'blur']) await check(`${cancel} cancels a press-free countdown`, async () => {
    const before = await state(); await control.focus(); await control.evaluate(node => node.click()); await holding(0.3);
    if (cancel === 'Escape') await page.keyboard.press('Escape'); else await control.evaluate(node => node.blur());
    assert.equal((await state()).hold, 'idle'); await unchanged(holdMs, before);
  });
  await page.screenshot({path: evidence + '/hold.png', fullPage: true});
  await writeFile(evidence + '/hold-results.json', JSON.stringify({cases, failures}, null, 2) + '\n'); assert.deepEqual(failures, []);
  console.log('Chromium hold control:', cases.length, 'cases');
} finally {await browser.close();}
