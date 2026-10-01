import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { trackProtocol, receivedReply, settledRequests } from './browser-protocol.mjs';
import { hold, HOLD_SETTLE_MS } from './hold.mjs';

export async function graphChecks(browser, storageState, origin, evidence) {
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(),
    'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  async function post(command) {
    const response = await fetch(`${origin}/api/call`, { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  }
  const draft = (title, body) => ({ title, body, labels: [], archived: false, citations: [],
    content: { Task: { status: 'Ready', acceptance: ['Review graph effects'], result: null, validation: [] } } });
  const change = (project, mutations) => post({ Change: { input: { project, change: {
    request: { value: randomUUID() }, mutations, fences: [], reason: 'Graph fixture' } } } });
  const detail = async id => (await post({ Read: { input: { project: id.project, selection: { ItemDetail: { id } } } } })).Detail.view;
  for (const scenario of ['relationships-restore', 'uncertain-relationship', 'uncertain-restore', 'obsolete-preview', 'obsolete-search', 'obsolete-search-disconnect']) {
    const project = { value: randomUUID() }; const item = number => ({ project, ledger: 'Tasks', number: String(number) });
    await post({ Initialize: { config: { project, endpoint: origin, name: `Graph ${scenario} ${project.value}` } } });
    await change(project, ['A', 'B'].map(name => ({ Create: { draft: draft(`Graph ${name}`, `Original ${name}`) } })));
    if (scenario === 'uncertain-restore') await change(project, [{ Replace: { id: item(1), expected: { value: '1' }, draft: draft('Graph A', 'Later A') } }]);
    const context = await browser.newContext({ storageState, viewport: { width: 1440, height: 1000 } });
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true }); await trackProtocol(context);
    const pendingCalls = new Set(); const requests = []; let dropped = false; let dropReply; let held = null;
    const committed = new Promise(resolve => { dropReply = resolve; });
    if (scenario !== 'relationships-restore') await context.routeWebSocket(/\/ws$/, route => {
      const server = route.connectToServer(); let heldId = null;
      route.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (frame.Call) pendingCalls.add(frame.Call.id.value);
        if (frame.Call && frame.Call.command.Change) {
          requests.push(frame.Call.command.Change.input);
          if (!dropped) heldId = frame.Call.id.value;
        }
        if (frame.Call && frame.Call.command.Read && frame.Call.command.Read.input.selection.ItemDetail && frame.Call.command.Read.input.selection.ItemDetail.id.number === '999') heldId = frame.Call.id.value;
        if (scenario.startsWith('obsolete-search') && frame.Call && frame.Call.command.Search && frame.Call.command.Search.input.query === '"Neighbor"') {
          heldId = frame.Call.id.value; frame.Call.command.Search.input.query = 'alpha AND'; server.send(JSON.stringify(frame)); return;
        }
        server.send(message);
      });
      server.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (!dropped && frame.Reply && frame.Reply.id.value === heldId) {
          if (scenario === 'obsolete-preview' || scenario.startsWith('obsolete-search')) { assert.ok(frame.Reply.result.Failed); held = { route, message, id: heldId }; }
          else assert.ok(frame.Reply.result.Changed, 'Suppress an actual committed acknowledgement');
          dropped = true; dropReply();
        } else { if (frame.Reply) pendingCalls.delete(frame.Reply.id.value); route.send(message); }
      });
    });
    const page = await context.newPage(); const errors = []; const cases = [];
    page.on('pageerror', error => errors.push(String(error)));
    const click = name => page.getByRole('button', { name, exact: true }).click();
    async function enter() {
      await page.goto(origin); await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
      await page.getByLabel('Project', { exact: true }).selectOption(project.value);
      const recovery = page.getByRole('dialog', { name: 'Graph change', exact: true });
      const pending = await recovery.isVisible();
      if (pending) await recovery.getByRole('button', { name: 'Close', exact: true }).click();
      await click('T1 · Graph A'); await page.getByRole('heading', { name: 'T1 · Graph A', exact: true }).waitFor();
      if (pending) await click('Review graph change');
    }
    async function previewRelation() {
      await page.getByRole('combobox', { name: 'Relationship', exact: true }).selectOption('RelatesTo');
      await page.getByLabel('Target item', { exact: true }).fill('T2'); await click('Preview relationship');
      await page.getByRole('heading', { name: 'Graph change preview', exact: true }).waitFor();
    }
    async function previewRestore(revision) {
      await click('History'); await click(`View revision ${revision}`); await click(`Preview restore revision ${revision}`);
      await page.getByRole('heading', { name: 'Graph change preview', exact: true }).waitFor();
    }
    const confirmation = page.getByRole('button', { name: 'Confirm graph change', exact: true });
    async function confirm(destructive) {
      if (destructive) await hold(page, confirmation); else await confirmation.click();
      await page.getByRole('status').filter({ hasText: 'Graph change saved' }).waitFor();
      await settledRequests(page);
    }
    async function clickKeeps(id, revision) {
      await confirmation.click(); await page.waitForTimeout(HOLD_SETTLE_MS);
      assert.equal((await detail(id)).item.revision.value, revision, 'A plain click must not confirm a destructive graph change');
      assert.equal(await confirmation.getAttribute('data-hold'), 'idle');
    }
    async function captured() {
      let timeout;
      try { await Promise.race([committed, new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Missing captured graph reply')), 10000); })]); }
      finally { clearTimeout(timeout); }
    }
    try {
      await enter();
      if (scenario === 'relationships-restore') {
        await previewRelation(); assert.equal(await confirmation.getAttribute('data-hold'), null, 'Adding a relationship stays a plain click'); await confirm(false);
        let a = await detail(item(1)); let b = await detail(item(2));
        assert.equal(a.item.revision.value, '2'); assert.equal(b.item.revision.value, '2');
        assert.deepEqual(a.refs, [{ relation: 'RelatesTo', target: item(2) }]);
        await click('Open T2'); await page.getByRole('heading', { name: 'T2 · Graph B', exact: true }).waitFor();
        await click('Open T1'); await click('Remove RelatesTo T2'); await clickKeeps(item(1), '2');
        const rendered = await confirmation.elementHandle();
        await change(project, [{ Create: { draft: draft('Graph C', 'Unrelated live change') } }]);
        await page.getByText('Graph C', { exact: true }).waitFor(); await settledRequests(page);
        assert.equal(await rendered.evaluate(node => node.isConnected), true, 'A live refresh must not replace the confirmation of an unchanged preview, which would cancel a hold');
        await confirm(true);
        a = await detail(item(1)); b = await detail(item(2));
        assert.equal(a.item.revision.value, '3'); assert.equal(b.item.revision.value, '3'); assert.deepEqual(a.refs, []);
        cases.push('add, navigate inverse reference and remove both endpoints');
        await change(project, [{ Replace: { id: item(1), expected: a.item.revision, draft: draft('Graph A', 'Later A') } }]);
        await page.getByText('Later A', { exact: true }).waitFor();
        await click('History');
        await click('View revision 2'); await click('Preview restore revision 2');
        await page.getByText('Neighbors receiving new revisions: T2 @ 3. Their content is preserved.', { exact: true }).waitFor();
        const previewHeading = page.getByRole('heading', { name: 'Graph change preview', exact: true });
        const previewBounds = await previewHeading.boundingBox(); assert.notEqual(previewBounds, null);
        const headerBounds = await page.locator('header').boundingBox(); assert.notEqual(headerBounds, null);
        assert.ok(previewBounds.y >= headerBounds.y + headerBounds.height && previewBounds.y + previewBounds.height <= page.viewportSize().height,
          `Requested preview must be visible in the detail viewport: ${JSON.stringify(previewBounds)}`);
        assert.equal(await previewHeading.evaluate(node => node.parentElement === document.activeElement), true, 'Requested preview receives keyboard focus');
        await page.screenshot({ path: `${evidence}/graph-restore-preview.png`, fullPage: true }); await clickKeeps(item(1), '4'); await confirm(true);
        a = await detail(item(1)); b = await detail(item(2));
        assert.equal(a.item.revision.value, '5'); assert.equal(a.item.draft.body, 'Original A');
        assert.equal(b.item.revision.value, '4'); assert.equal(b.item.draft.body, 'Original B'); assert.equal(a.refs.length, 1);
        cases.push('restore content and historical relationship with exact neighbor revision');
        await click('History');
        await click('View revision 3'); await click('Preview restore revision 3');
        await page.getByText('Neighbors receiving new revisions: T2 @ 4. Their content is preserved.', { exact: true }).waitFor();
        await change(project, [{ Replace: { id: item(2), expected: b.item.revision, draft: draft('Graph B', 'Concurrent B') } }]);
        await hold(page, confirmation); await page.getByRole('alert').filter({ hasText: 'Graph change rejected' }).waitFor();
        a = await detail(item(1)); b = await detail(item(2));
        assert.equal(a.item.revision.value, '5'); assert.equal(a.refs.length, 1);
        assert.equal(b.item.revision.value, '5'); assert.equal(b.item.draft.body, 'Concurrent B');
        assert.equal(await page.evaluate(key => localStorage.getItem(key), `cq-graph-change:${project.value}`), null);
        cases.push('stale restore neighbor rejects atomically and preserves concurrent content');
      } else if (scenario.startsWith('obsolete-search')) {
        await page.getByLabel('Target item', { exact: true }).fill('Neighbor'); await captured();
        await click('T2 · Graph B'); await page.getByRole('heading', { name: 'T2 · Graph B', exact: true }).waitFor();
        if (scenario === 'obsolete-search-disconnect') {
          await page.getByRole('heading', { name: 'Usage · T2', exact: true }).waitFor();
          const deadline = Date.now() + 10000;
          while (pendingCalls.size !== 1 || !pendingCalls.has(held.id)) {
            assert.ok(Date.now() < deadline, 'Only the withheld target search may remain pending');
            await new Promise(resolve => setTimeout(resolve, 20));
          }
          await held.route.close({ code: 1000, reason: 'Fixture connection replacement' });
          await page.locator('header summary').hover();
          await page.waitForFunction(() => document.body.textContent.includes('Closed 1000: Fixture connection replacement'));
        } else { held.route.send(held.message); await receivedReply(page, held.id); await settledRequests(page); }
        assert.equal(await page.getByRole('alert').count(), 0, 'An obsolete target search failure must not surface after navigation');
        cases.push('late failed target search is ignored after navigation');
      } else if (scenario === 'obsolete-preview') {
        await page.getByLabel('Target item', { exact: true }).fill('T999'); await click('Preview relationship'); await captured();
        await click('T2 · Graph B'); await page.getByRole('heading', { name: 'T2 · Graph B', exact: true }).waitFor();
        assert.notEqual(held, null); held.route.send(held.message);
        await receivedReply(page, held.id); await settledRequests(page);
        assert.equal(await page.getByRole('alert').count(), 0, 'An obsolete preview lookup must not report an error after navigation');
        cases.push('delayed failed preview lookup is ignored after navigation');
      } else {
        if (scenario === 'uncertain-relationship') { await previewRelation(); await confirmation.click(); }
        else { await previewRestore(1); await hold(page, confirmation); }
        await captured();
        const stored = await page.evaluate(key => JSON.parse(localStorage.getItem(key)), `cq-graph-change:${project.value}`);
        assert.deepEqual(stored, requests[0]);
        await enter();
        await page.getByRole('heading', { name: 'Pending graph change', exact: true }).waitFor();
        assert.equal(await page.getByRole('button', { name: 'Preview relationship', exact: true }).isDisabled(), true);
        await click('Retry exact graph change'); await page.getByRole('status').filter({ hasText: 'Graph change saved' }).waitFor();
        await settledRequests(page); assert.equal(requests.length, 2); assert.deepEqual(requests[0], requests[1]);
        const a = await detail(item(1)); const b = await detail(item(2));
        assert.equal(a.item.revision.value, scenario === 'uncertain-relationship' ? '2' : '3');
        assert.equal(b.item.revision.value, scenario === 'uncertain-relationship' ? '2' : '1');
        assert.equal(a.item.draft.body, 'Original A');
        assert.equal(await page.evaluate(key => localStorage.getItem(key), `cq-graph-change:${project.value}`), null);
        cases.push('actual committed acknowledgement loss, reload and exact retry without duplicate revisions');
      }
      assert.deepEqual(errors, []);
    } finally {
      await writeFile(`${evidence}/graph-${scenario}-results.json`, JSON.stringify({ cases, errors, requests, heldRequest: held === null ? null : held.id, pendingCalls: [...pendingCalls] }, null, 2));
      await page.screenshot({ path: `${evidence}/graph-${scenario}.png`, fullPage: true });
      await context.tracing.stop({ path: `${evidence}/graph-${scenario}.zip` }); await context.close();
    }
  }
  console.log('Chromium graph actions: relationship navigation/mutation, content/edge restore, stale neighbor rejection and durable exact retries');
}
