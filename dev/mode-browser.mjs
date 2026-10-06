// Behavioral-Active Blackbox Good-Communication; I30 process mode of a project: the header shows the mode, the operator changes it in the
// Process mode dialog under revision comparison, and every label, hint and note comes from the typed ReadSelection.Catalog response.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {hold} from './hold.mjs';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function reply(command) {
  const response = await fetch(origin + '/api/call', {method: 'POST', headers, body: JSON.stringify(command)});
  assert.equal(response.status, 200, await response.clone().text()); return response.json();
}
async function call(command) { const result = await reply(command); assert.equal(result.Failed, undefined, JSON.stringify(result)); return result; }
const project = {value: randomUUID()}, other = {value: randomUUID()};
await call({Initialize: {config: {project, endpoint: origin, name: `Mode fixture ${project.value}`}}});
await call({Initialize: {config: {project: other, endpoint: origin, name: `Mode fixture, second project ${other.value}`}}});
const stored = async target => { const value = (await call({Mode: {input: {project: target, action: {Read: {}}}}})).Mode.value; return [value.mode, value.revision.value, value.selfReviewWithoutChecks]; };
const replace = (expected, mode, selfReviewWithoutChecks = false) => reply({Mode: {input: {project, action: {Replace: {expected: {value: String(expected)}, mode, selfReviewWithoutChecks}}}}});
const catalog = (await call({Read: {input: {project, selection: {Catalog: {}}}}})).Catalog.value;
const [rigorous, crossCutting, yolo] = catalog.modes;
assert.deepEqual(catalog.modes.map(mode => mode.mode), ['Rigorous', 'CrossCutting', 'Yolo']);
// A release either withholds the YOLO mode, with a note in the catalog, or delivers it; the page follows the catalog in both.
const delivered = (yolo.unavailable ?? null) === null;
assert.ok((delivered || yolo.unavailable.length > 0) && (rigorous.unavailable ?? null) === null && (crossCutting.unavailable ?? null) === null);
const exemption = yolo.description.split('\n\n').pop();
assert.ok(yolo.description.includes('\n\n') && exemption.includes('a change the governing session reviewed itself can be integrated although no check examined it'));
assert.deepEqual(await stored(project), ['Rigorous', '0', false]);

const browser = await chromium.launch({headless: true});
const results = [];
try {
  for (const {width, height} of [{width: 1366, height: 768}, {width: 640, height: 720}]) {
    const viewport = `${width}x${height}`;
    const context = await browser.newContext({viewport: {width, height}});
    const page = await context.newPage(); page.setDefaultTimeout(8000);
    const cases = [], errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const indicator = page.locator('header').getByRole('button', {name: /^Process mode: /});
    const dialog = page.getByRole('dialog', {name: 'Process mode', exact: true});
    const group = dialog.getByRole('group', {name: 'Process mode', exact: true});
    const radio = mode => group.getByRole('radio', {name: mode.label, exact: true});
    const save = dialog.getByRole('button', {name: 'Save mode', exact: true});
    const close = dialog.getByRole('button', {name: 'Close', exact: true});
    const choose = async target => { await page.getByLabel('Project', {exact: true}).selectOption(target.value); await page.getByText('Data: current', {exact: true}).waitFor(); };
    const shown = () => indicator.evaluate(node => ({text: node.textContent, mode: node.dataset.mode, title: node.title, border: getComputedStyle(node).borderTopColor,
      background: getComputedStyle(node).backgroundColor, overflow: document.documentElement.scrollWidth > innerWidth,
      inHeader: node.closest('header') !== null, besideProject: node.closest('.project-control')?.querySelector('select[aria-label="Project"]') !== null}));
    try {
      // Each viewport starts from the mode a project has before its operator chose one.
      if ((await stored(project))[0] !== 'Rigorous') assert.equal((await replace((await stored(project))[1], 'Rigorous')).Failed, undefined);
      let start = Number((await stored(project))[1]);
      await page.goto(origin);
      await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
      await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
      await choose(project);
      await indicator.waitFor();
      const plain = await shown();
      assert.deepEqual([plain.text, plain.mode, plain.title, plain.overflow, plain.inHeader, plain.besideProject], [`Mode: ${rigorous.label}`, 'Rigorous', rigorous.hint, false, true, true], viewport);
      assert.equal(await page.locator('header .top-identity').evaluate(row => [...row.querySelectorAll('button, summary, select, input')].pop().textContent), 'Help', 'Help stays the last control of the header');
      cases.push('the header shows the mode of the selected project beside the project selector, with the catalog label and hint');

      await indicator.click(); await radio(rigorous).waitFor();
      assert.deepEqual(await group.getByRole('radio').evaluateAll(nodes => nodes.map(node => node.parentElement.textContent)), catalog.modes.map(mode => mode.label));
      for (const mode of catalog.modes) {
        const described = await radio(mode).evaluate(node => node.getAttribute('aria-describedby').split(' ').map(id => document.getElementById(id).textContent));
        assert.deepEqual(described, (mode.unavailable ?? null) === null ? [mode.hint] : [mode.hint, mode.unavailable], mode.label);
        for (const text of described) assert.ok(await group.getByText(text, {exact: true}).isVisible(), `${viewport}: ${text}`);
      }
      assert.deepEqual([await radio(rigorous).isChecked(), await radio(crossCutting).isChecked(), await radio(yolo).isChecked()], [true, false, false]);
      assert.deepEqual([await radio(rigorous).isDisabled(), await radio(crossCutting).isDisabled(), await radio(yolo).isDisabled()], [false, false, !delivered]);
      await dialog.getByText(catalog.modeEffect, {exact: true}).waitFor();
      if (start === 0) await dialog.getByText(`No process mode has been saved for this project: it is ${rigorous.label}.`, {exact: true}).waitFor();
      else await dialog.getByText(new RegExp(`^Revision ${start} · operator · `)).waitFor();
      assert.equal(await dialog.getByRole('button', {name: 'Switch to YOLO cross-cutting', exact: true}).isVisible(), false);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      assert.equal(await dialog.getByRole('group', {name: 'Self-review without checks', exact: true}).isVisible(), false);
      cases.push('the dialog offers the three modes with the catalog hint under each; a mode this release does not deliver is shown disabled with the catalog note');

      if (!delivered) {
        // The mode that cannot be chosen: the control refuses it, and so does the server, in the words the dialog shows.
        await radio(yolo).click({force: true}); assert.equal(await radio(yolo).isChecked(), false);
        const refused = await replace(start, 'Yolo');
        assert.deepEqual(refused.Failed?.fault, {Invalid: {message: yolo.unavailable}});
        assert.deepEqual(await stored(project), ['Rigorous', String(start), false]);
        cases.push('the unavailable mode cannot be chosen in the dialog, and the server refuses it with the note the dialog shows');
      } else {
        // The YOLO mode is saved by holding; its exemption from configured checks is a second decision with a hold of its own.
        const confirm = dialog.getByRole('button', {name: 'Switch to YOLO cross-cutting', exact: true});
        const group = dialog.getByRole('group', {name: 'Self-review without checks', exact: true});
        const allow = group.getByRole('button', {name: 'Allow self-review without checks', exact: true});
        await radio(yolo).check();
        assert.deepEqual([await save.isVisible(), await confirm.isVisible(), await group.isVisible(), await allow.isDisabled()], [false, true, true, true]);
        await group.getByText(exemption, {exact: true}).waitFor();
        await hold(page, confirm);
        await dialog.getByText(new RegExp(`^Revision ${start + 1} · operator · `)).waitFor();
        assert.deepEqual(await stored(project), ['Yolo', String(start + 1), false]);
        const loud = await shown();
        assert.deepEqual([loud.text, loud.mode, loud.title, loud.overflow], [`Mode: ${yolo.label}`, 'Yolo', yolo.hint, false], viewport);
        assert.ok(loud.background !== plain.background && loud.border !== plain.border, `${viewport}: the YOLO indicator is not distinct from the Rigorous one`);
        await group.getByText('Not allowed for this project.', {exact: true}).waitFor();
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
        await dialog.screenshot({path: `${evidence}/mode-dialog-yolo-${viewport}.png`});
        await hold(page, allow);
        await group.getByText('Allowed for this project.', {exact: true}).waitFor();
        assert.deepEqual(await stored(project), ['Yolo', String(start + 2), true]);
        await dialog.screenshot({path: `${evidence}/mode-dialog-yolo-exempted-${viewport}.png`});
        await page.locator('header').screenshot({path: `${evidence}/mode-header-yolo-${viewport}.png`});
        // The exemption belongs to the YOLO mode: the server stores it with no other, and the dialog's change to another mode stores none.
        const kept = await replace(start + 2, 'CrossCutting', true);
        assert.deepEqual(kept.Failed?.fault, {Invalid: {message: `Self-review without configured checks can be allowed only in the ${yolo.label} mode; the requested mode is ${crossCutting.label}`}});
        await radio(rigorous).check(); assert.equal(await group.isVisible(), false);
        await save.click(); await dialog.getByText(new RegExp(`^Revision ${start + 3} · operator · `)).waitFor();
        assert.deepEqual(await stored(project), ['Rigorous', String(start + 3), false]);
        start += 3;
        cases.push('the YOLO mode is saved by holding and marked in the header; its exemption from configured checks is allowed by a second hold, in the catalog words, and a change to another mode stores none');
      }

      await radio(crossCutting).check(); await save.click();
      await dialog.getByText(new RegExp(`^Revision ${start + 1} · operator · `)).waitFor();
      await page.getByText(`Process mode saved at revision ${start + 1}.`, {exact: true}).waitFor();
      assert.deepEqual(await stored(project), ['CrossCutting', String(start + 1), false]);
      const marked = await shown();
      assert.deepEqual([marked.text, marked.mode, marked.title, marked.overflow], [`Mode: ${crossCutting.label}`, 'CrossCutting', crossCutting.hint, false], viewport);
      assert.notEqual(marked.border, plain.border, `${viewport}: the Cross-cutting indicator is not distinct from the Rigorous one`);
      await dialog.screenshot({path: `${evidence}/mode-dialog-${viewport}.png`});
      await close.click(); await dialog.waitFor({state: 'hidden'});
      await page.locator('header').screenshot({path: `${evidence}/mode-header-${viewport}.png`});
      cases.push('Cross-cutting is saved with the ordinary save, at the next revision, and the header indicator changes to it and is marked');

      await page.reload(); await page.getByText('Connection: ALIVE', {exact: true}).waitFor(); await choose(project); await indicator.waitFor();
      assert.equal((await shown()).mode, 'CrossCutting');
      await page.getByRole('navigation').getByRole('button', {name: 'Process mode', exact: true}).click();
      await dialog.getByText(new RegExp(`^Revision ${start + 1} · operator · `)).waitFor();
      assert.equal(await radio(crossCutting).isChecked(), true);
      cases.push('a reload shows the saved mode in the header and in the dialog, which also opens from the navigation beside Standing requirements');

      // Changed elsewhere after the dialog was loaded: the save changes nothing and shows the current mode.
      assert.equal((await replace(start + 1, 'Rigorous')).Failed, undefined);
      await save.click();
      await dialog.getByRole('heading', {name: 'Edit conflict', exact: true}).waitFor();
      await dialog.getByText(`Your choice is based on revision ${start + 1}; the current revision is ${start + 2}, and the project is in the ${rigorous.label} mode.`, {exact: false}).waitFor();
      assert.deepEqual(await stored(project), ['Rigorous', String(start + 2), false]);
      assert.equal(await radio(crossCutting).isChecked(), true);
      await dialog.getByRole('button', {name: 'Use current revision as base', exact: true}).click();
      await dialog.getByText(new RegExp(`^Revision ${start + 2} · operator · `)).waitFor();
      assert.equal(await dialog.getByRole('heading', {name: 'Edit conflict', exact: true}).count(), 0);
      await save.click(); await dialog.getByText(new RegExp(`^Revision ${start + 3} · operator · `)).waitFor();
      assert.deepEqual(await stored(project), ['CrossCutting', String(start + 3), false]);
      cases.push('a save from a stale revision changes nothing and names the current mode; saving on the current revision replaces it');

      await save.click(); await page.getByText('Process mode unchanged: it equals the saved one.', {exact: true}).waitFor();
      assert.deepEqual(await stored(project), ['CrossCutting', String(start + 3), false]);
      cases.push('a save of the unchanged mode says that nothing changed and keeps the revision');

      await close.click(); await dialog.waitFor({state: 'hidden'});
      assert.ok(await indicator.evaluate(node => document.activeElement?.textContent === 'Process mode' || document.activeElement === node), 'Closing returns focus to the control that opened the dialog');
      await choose(other); await page.locator('header').getByRole('button', {name: `Process mode: ${rigorous.label}`, exact: true}).waitFor();
      assert.deepEqual(await stored(other), ['Rigorous', '0', false]);
      await choose(project); await page.locator('header').getByRole('button', {name: `Process mode: ${crossCutting.label}`, exact: true}).waitFor();
      cases.push('the mode belongs to its project: another project keeps its own, and the indicator follows the selected project');
      assert.deepEqual(errors, []);
    } finally {
      results.push({viewport, cases, errors});
      await page.screenshot({path: `${evidence}/mode-page-${viewport}.png`}); await context.close();
    }
  }
} finally {
  await writeFile(`${evidence}/mode-results.json`, JSON.stringify(results, null, 2) + '\n');
  await browser.close();
}
assert.ok(results.length === 2 && results.every(result => result.cases.length === 8), JSON.stringify(results.map(result => result.cases.length)));
console.log(`Chromium Process mode: header indicator, catalog-driven dialog, revision conflict and the ${delivered ? 'YOLO mode with its exemption' : 'unavailable mode'}`);
