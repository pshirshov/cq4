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
const cases = [], failures = [], errors = [];
page.on('pageerror', error => errors.push(String(error)));
async function check(name, body) {
  try {await body(); cases.push(name);} catch (error) {failures.push({name, error: String(error)});}
  finally {await page.mouse.up(); await page.keyboard.up('Space'); await page.keyboard.up('Enter'); await control.evaluate(node => {node.disabled = false; node.blur();}); await page.mouse.move(0, 0);}
}
const control = page.getByRole('button', {name: 'Hold fixture', exact: true});
const state = () => control.evaluate(node => ({hold: node.dataset.hold, value: Number(node.style.getPropertyValue('--hold')), ...window.holdFixture}));
// The fill is the control's ::before box: its width as a fraction of the control's padding box, next to the fraction that drives it.
const fill = () => control.evaluate(node => ({value: Number(node.style.getPropertyValue('--hold')), drawn: parseFloat(getComputedStyle(node, '::before').width) / node.clientWidth}));
const FILL_TOLERANCE = 0.02;
const holding = minimum => page.waitForFunction(value => {
  const node = document.getElementById('hold-fixture'); return node.dataset.hold === 'holding' && Number(node.style.getPropertyValue('--hold')) >= value;
}, minimum);
const settled = holdMs => page.waitForTimeout(holdMs + 400);
async function unchanged(holdMs, before) {
  await settled(holdMs); const after = await state();
  assert.deepEqual({hold: after.hold, value: after.value, count: after.count}, {hold: 'idle', value: 0, count: before.count});
}
// Delays every animation frame by `gap` ms, as a display that renders fewer than four frames a second does.
async function slowFrames(gap, body) {
  await page.evaluate(gap => {
    const request = window.requestAnimationFrame, cancel = window.cancelAnimationFrame, pending = new Map(); let next = 0;
    window.holdSlowFrames = () => {window.requestAnimationFrame = request; window.cancelAnimationFrame = cancel; delete window.holdSlowFrames;};
    window.requestAnimationFrame = callback => {
      const handle = ++next, entry = {frame: 0, timer: 0};
      entry.timer = setTimeout(() => {entry.frame = request.call(window, time => {pending.delete(handle); callback(time);});}, gap);
      pending.set(handle, entry); return handle;
    };
    window.cancelAnimationFrame = handle => {
      const entry = pending.get(handle); if (!entry) return;
      clearTimeout(entry.timer); cancel.call(window, entry.frame); pending.delete(handle);
    };
  }, gap);
  try {await body();} finally {await page.evaluate(() => window.holdSlowFrames());}
}
// Removes Element.checkVisibility() for the page, as in a browser that does not implement it.
async function withoutCheckVisibility(body) {
  await page.evaluate(() => {
    const descriptor = Object.getOwnPropertyDescriptor(Element.prototype, 'checkVisibility');
    window.holdCheckVisibility = () => {Object.defineProperty(Element.prototype, 'checkVisibility', descriptor); delete window.holdCheckVisibility;};
    delete Element.prototype.checkVisibility;
  });
  try {
    assert.equal(await control.evaluate(node => typeof node.checkVisibility), 'undefined');
    const before = errors.length; await body(); assert.deepEqual(errors.slice(before), [], 'Uncaught page errors');
  } finally {await page.evaluate(() => window.holdCheckVisibility());}
}
// A second control with its own action counter, placed by `place` in a container the case hides, shows or closes.
const enclose = place => page.evaluate(place => {
  window.holdFixture.enclosed = 0;
  const node = window.holdModule.holdButton('Enclosed hold', () => {window.holdFixture.enclosed++;}); node.id = 'enclosed-hold';
  const container = document.createElement(place.tag); container.id = 'hold-container'; container.style.display = place.display;
  container.append(node); document.body.append(container);
}, place);
const enclosed = page.locator('#enclosed-hold');
const enclosedState = () => enclosed.evaluate(node => [node.dataset.hold, Number(node.style.getPropertyValue('--hold')), window.holdFixture.enclosed]);
try {
  await page.goto(origin); await page.getByLabel('Operator token').waitFor();
  await page.evaluate(new TextDecoder().decode(bundled.outputFiles[0].contents) + '\nwindow.holdModule = holdModule;');
  const holdMs = await page.evaluate(() => {
    window.holdFixture = {count: 0, pressed: 0, fired: 0, samples: [], times: []};
    const node = window.holdModule.holdButton('Hold fixture', () => {window.holdFixture.count++; window.holdFixture.fired = performance.now();});
    node.id = 'hold-fixture';
    for (const type of ['pointerdown', 'keydown']) node.addEventListener(type, event => {if (!event.repeat) window.holdFixture.pressed = performance.now();}, {capture: true});
    new MutationObserver(() => {if (node.dataset.hold === 'holding') {window.holdFixture.samples.push(Number(node.style.getPropertyValue('--hold'))); window.holdFixture.times.push(performance.now());}}).observe(node, {attributeFilter: ['data-hold']});
    document.body.append(node); return window.holdModule.HOLD_MS;
  });
  await check('control is a labelled button marked by its border, with an empty fill and no indicator element', async () => {
    assert.equal(holdMs, 1000);
    assert.deepEqual(await control.evaluate(node => [node.tagName, node.type, node.textContent, node.title]), ['BUTTON', 'button', 'Hold fixture', 'Hold to confirm']);
    assert.equal(await control.evaluate(node => node.childElementCount), 0);
    assert.equal((await state()).hold, 'idle'); assert.deepEqual(await fill(), {value: 0, drawn: 0});
    const plain = await page.evaluate(() => {
      const node = document.createElement('button'); node.textContent = 'Plain fixture'; document.body.append(node);
      const found = {border: getComputedStyle(node).borderTopColor, pseudo: getComputedStyle(node, '::before').content}; node.remove(); return found;
    });
    const guarded = await control.evaluate(node => [...new Set(['Top', 'Right', 'Bottom', 'Left'].map(side => getComputedStyle(node)[`border${side}Color`]))]);
    assert.deepEqual(guarded, ['rgb(224, 163, 65)']); assert.notEqual(plain.border, guarded[0]); assert.equal(plain.pseudo, 'none');
    await control.screenshot({path: evidence + '/hold-idle.png'});
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
    const full = await fill(); assert.ok(full.value === 1 && Math.abs(full.drawn - 1) <= FILL_TOLERANCE, JSON.stringify(full));
    await page.mouse.up(); const after = await state(); assert.deepEqual([after.hold, after.value, after.count], ['idle', 0, before.count + 1]);
    await unchanged(holdMs, after);
  });
  await check('releasing at about half resets progress and state', async () => {
    const before = await state(); await control.hover(); await page.mouse.down(); await holding(0.4);
    const drawn = await fill(); assert.ok(drawn.value >= 0.4 && drawn.value < 1 && Math.abs(drawn.drawn - drawn.value) <= FILL_TOLERANCE, JSON.stringify(drawn));
    await control.screenshot({path: evidence + '/hold-holding.png'}); await page.mouse.up();
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
  await check('closing its dialog cancels a press-free countdown on an unfocused control', async () => {
    await page.evaluate(() => {
      const dialog = document.createElement('dialog'); dialog.id = 'hold-dialog';
      const first = document.createElement('button'); first.textContent = 'Close fixture';
      window.holdFixture.enclosed = 0;
      const node = window.holdModule.holdButton('Enclosed hold', () => {window.holdFixture.enclosed++;}); node.id = 'enclosed-hold';
      dialog.append(first, node); document.body.append(dialog); dialog.showModal();
    });
    const enclosed = page.locator('#enclosed-hold');
    assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Close fixture');
    await enclosed.evaluate(node => node.click());
    await page.waitForFunction(() => document.getElementById('enclosed-hold').dataset.hold === 'holding');
    await page.evaluate(() => document.getElementById('hold-dialog').close());
    await settled(holdMs);
    assert.deepEqual(await enclosed.evaluate(node => [node.dataset.hold, window.holdFixture.enclosed]), ['idle', 0]);
    await page.evaluate(() => document.getElementById('hold-dialog').showModal()); await enclosed.evaluate(node => node.click());
    await page.waitForFunction(() => window.holdFixture.enclosed === 1);
    await page.evaluate(() => document.getElementById('hold-dialog').remove());
  });
  await check('hiding the document cancels a countdown', async () => {
    const before = await state(); await control.evaluate(node => node.click()); await holding(0.2);
    try {
      await page.evaluate(() => {
        Object.defineProperty(document, 'hidden', {configurable: true, get: () => true});
        document.dispatchEvent(new Event('visibilitychange'));
      });
      assert.equal((await state()).hold, 'idle');
    } finally {await page.evaluate(() => {delete document.hidden; document.dispatchEvent(new Event('visibilitychange'));});}
    await unchanged(holdMs, before);
  });
  await check('time that passed without rendered frames does not complete a hold', async () => {
    const before = await state(); await control.focus(); await control.evaluate(node => node.click()); await holding(0.2);
    try {
      // A suspended page renders no frame while its clock advances: the next frame must not treat the gap as held time.
      const after = await control.evaluate(node => new Promise(resolve => {
        const now = performance.now.bind(performance); performance.now = () => now() + 10000;
        requestAnimationFrame(() => requestAnimationFrame(() => resolve({hold: node.dataset.hold, value: Number(node.style.getPropertyValue('--hold')), count: window.holdFixture.count})));
      }));
      assert.equal(after.count, before.count); assert.equal(after.hold, 'holding'); assert.ok(after.value < 1, `Progress ${after.value}`);
    } finally {await page.evaluate(() => {delete performance.now;}); await page.keyboard.press('Escape');}
    await unchanged(holdMs, before);
  });
  await check('one gap without rendered frames credits exactly the frame cap', async () => {
    const before = await state();
    try {
      const after = await control.evaluate(node => new Promise(resolve => {
        node.click(); const now = performance.now.bind(performance); performance.now = () => now() + 10000;
        requestAnimationFrame(() => resolve({hold: node.dataset.hold, value: Number(node.style.getPropertyValue('--hold')), count: window.holdFixture.count}));
      }));
      assert.deepEqual(after, {hold: 'holding', value: 0.25, count: before.count});
    } finally {await page.evaluate(() => {delete performance.now;}); await control.focus(); await page.keyboard.press('Escape');}
    await unchanged(holdMs, before);
  });
  await check('a hold whose every frame gap exceeds the frame cap completes once with rising progress', async () => {
    const before = await state(); await control.hover();
    await slowFrames(300, async () => {
      await control.evaluate(() => {window.holdFixture.samples = []; window.holdFixture.times = [];});
      await hold(page, control);
    });
    await settled(holdMs); const after = await state();
    assert.equal(after.count, before.count + 1); assert.ok(after.fired - after.pressed >= holdMs, `Fired after ${after.fired - after.pressed} ms`);
    assert.deepEqual([after.hold, after.value], ['idle', 0]);
    const gaps = after.times.slice(1).map((time, index) => time - after.times[index]);
    assert.ok(gaps.length >= 3 && gaps.every(gap => gap > 250), `Frame gaps ${JSON.stringify(gaps)}`);
    // Each gap is credited with the cap, so progress rises by a quarter per frame and the fourth frame confirms.
    assert.deepEqual(after.samples, [0, 0.25, 0.5, 0.75]);
    assert.equal(typeof await page.evaluate(() => window.holdSlowFrames), 'undefined');
  });
  await check('releasing between slow frames cancels the hold', async () => {
    const before = await state(); await control.hover();
    await slowFrames(300, async () => {
      await page.mouse.down(); await holding(0.5); await page.mouse.up();
      assert.deepEqual([(await state()).hold, (await state()).value], ['idle', 0]); await unchanged(holdMs, before);
    });
  });
  await check('without checkVisibility a pointer hold completes once', async () => {
    const before = await state();
    await withoutCheckVisibility(async () => {await hold(page, control); await settled(holdMs);});
    const after = await state(); assert.equal(after.count, before.count + 1); assert.ok(after.fired - after.pressed >= holdMs);
  });
  for (const key of ['Space', 'Enter']) await check(`without checkVisibility holding ${key} completes once`, async () => {
    const before = await state();
    await withoutCheckVisibility(async () => {
      await control.focus(); await page.keyboard.down(key);
      await page.waitForFunction(() => document.getElementById('hold-fixture').dataset.hold === 'done'); await settled(holdMs);
      await page.keyboard.up(key);
    });
    const after = await state(); assert.equal(after.count, before.count + 1); assert.ok(after.fired - after.pressed >= holdMs);
  });
  await check('without checkVisibility a press-free click runs the countdown once', async () => {
    const before = await state();
    await withoutCheckVisibility(async () => {
      await control.evaluate(node => {window.holdFixture.pressed = performance.now(); node.click();});
      assert.equal((await state()).hold, 'holding'); await settled(holdMs);
    });
    const after = await state(); assert.equal(after.count, before.count + 1); assert.ok(after.fired - after.pressed >= holdMs);
  });
  for (const place of [{name: 'in a closed dialog', tag: 'dialog', display: ''}, {name: 'under a display:none ancestor', tag: 'div', display: 'none'}]) {
    await check(`without checkVisibility a control ${place.name} does not start a hold`, async () => {
      await enclose(place);
      try {
        await withoutCheckVisibility(async () => {
          await enclosed.evaluate(node => node.click()); await enclosed.dispatchEvent('pointerdown', {isPrimary: true, button: 0});
          await enclosed.dispatchEvent('keydown', {key: 'Enter'});
          assert.deepEqual(await enclosedState(), ['idle', 0, 0]); await settled(holdMs); assert.deepEqual(await enclosedState(), ['idle', 0, 0]);
          // The same control runs once it is rendered, so the fallback does not merely refuse every control.
          await page.evaluate(() => {const container = document.getElementById('hold-container'); if (container.tagName === 'DIALOG') container.showModal(); else container.style.display = '';});
          await enclosed.evaluate(node => node.click()); await page.waitForFunction(() => window.holdFixture.enclosed === 1);
        });
      } finally {await page.evaluate(() => document.getElementById('hold-container').remove());}
    });
    await check(`without checkVisibility a countdown is cancelled when its control ends up ${place.name}`, async () => {
      await enclose({...place, display: ''});
      try {
        await withoutCheckVisibility(async () => {
          await page.evaluate(() => {const container = document.getElementById('hold-container'); if (container.tagName === 'DIALOG') container.showModal();});
          await enclosed.evaluate(node => node.click()); await page.waitForFunction(() => document.getElementById('enclosed-hold').dataset.hold === 'holding');
          await page.evaluate(() => {const container = document.getElementById('hold-container'); if (container.tagName === 'DIALOG') container.close(); else container.style.display = 'none';});
          await settled(holdMs); assert.deepEqual(await enclosedState(), ['idle', 0, 0]);
        });
      } finally {await page.evaluate(() => document.getElementById('hold-container').remove());}
    });
  }
  await check('a polite live region next to the control reports the hold without changing its accessible name', async () => {
    const status = page.locator('#hold-fixture + [aria-live=polite]');
    const before = await state(); await control.focus(); await control.evaluate(node => node.click()); await holding(0.2);
    assert.equal(await status.textContent(), 'Confirming. Escape cancels.');
    await page.keyboard.press('Escape'); assert.equal(await status.textContent(), 'Not confirmed.');
    await control.evaluate(node => node.click()); await page.waitForFunction(count => window.holdFixture.count === count + 1, before.count);
    assert.equal(await status.textContent(), 'Confirmed.');
    assert.deepEqual(await control.evaluate(node => [node.getAttribute('aria-label'), node.textContent, node.title]), ['Hold fixture', 'Hold fixture', 'Hold to confirm']);
    assert.equal(await page.getByRole('button', {name: 'Hold fixture', exact: true}).count(), 1);
  });
  await page.screenshot({path: evidence + '/hold.png', fullPage: true});
  await writeFile(evidence + '/hold-results.json', JSON.stringify({cases, failures}, null, 2) + '\n'); assert.deepEqual(failures, []);
  console.log('Chromium hold control:', cases.length, 'cases');
} finally {await browser.close();}
