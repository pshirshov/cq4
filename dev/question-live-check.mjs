// Behavioral Active Blackbox Communication: actual item pane, server and WebSocket reply ordering.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';

async function main() {
  const origin = process.env.CQ_ORIGIN;
  const evidence = process.env.CQ_BROWSER_EVIDENCE;
  assert.ok(origin && evidence && process.env.CQ_TOKEN, 'Question live fixture needs origin, token and evidence directory');
  const headers = { Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json' };
  const call = async command => {
    const response = await fetch(origin + '/api/call', { method: 'POST', headers, body: JSON.stringify(command) });
    assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
  };
  const project = { value: randomUUID() };
  await call({ Initialize: { config: { project, endpoint: origin, name: 'Question live ordering' } } });
  const draft = number => ({ title: `Live question ${number}`, body: 'Original text', labels: [], archived: false, citations: [],
    content: { Question: { status: 'Open', prompt: `Choose ${number}`, context: 'Live ordering', alternatives: ['Yes', 'No'], recommendation: null, answer: null } } });
  const change = mutations => call({ Change: { input: { project, change: { request: { value: randomUUID() }, mutations, fences: [], reason: 'Question live ordering fixture' } } } });
  const created = await change([1, 2, 3].map(number => ({ Create: { draft: draft(number) } })));
  const ids = created.Changed.ack.items.map(item => item.id);
  const detail = async id => (await call({ Read: { input: { project, selection: { ItemDetail: { id } } } } })).Detail.view.item;
  const replace = async (id, edit) => {
    const item = await detail(id); await change([{ Replace: { id, expected: item.revision, draft: edit(item.draft) } }]);
  };
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1366, height: 768 } });
  const page = await context.newPage(); page.setDefaultTimeout(6000);
  const cases = [], errors = [], events = [];
  const transport = { hold: null, dropUpdates: false, dropped: 0, sockets: 0, onDropped: () => {} };
  page.on('pageerror', error => errors.push(String(error)));
  await context.routeWebSocket(/\/ws$/, route => {
    transport.sockets++;
    const socket = transport.sockets;
    const server = route.connectToServer();
    route.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (frame.Watch) events.push({ socket, kind: 'Watch', id: frame.Watch.id });
      const selection = frame.Call?.command.Read?.input.selection.ItemDetail;
      if (selection) events.push({ socket, kind: 'ItemDetailCall', id: frame.Call.id.value, number: selection.id.number });
      if (transport.hold !== null && transport.hold.id === null && selection?.id.ledger === 'Questions' && selection.id.number === transport.hold.number)
        transport.hold.id = frame.Call.id.value;
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message));
      if (frame.Updated) events.push({ socket, kind: 'Updated', subscription: frame.Updated.subscription, revision: frame.Updated.revision, dropped: transport.dropUpdates });
      if (frame.Reply?.result.Detail) events.push({ socket, kind: 'DetailReply', id: frame.Reply.id.value, number: frame.Reply.result.Detail.view.item.id.number, status: frame.Reply.result.Detail.view.item.draft.content.Question?.status });
      if (frame.Reply?.result.Browsed) events.push({ socket, kind: 'BrowseReply', cursor: frame.Reply.result.Browsed.page.cursor });
      if (transport.hold !== null && frame.Reply?.id.value === transport.hold.id) {
        transport.hold.result = frame.Reply.result;
        transport.hold.release = () => route.send(message);
        transport.hold.resolve();
      } else if (transport.dropUpdates && frame.Updated) { transport.dropped++; transport.onDropped(); }
      else route.send(message);
    });
  });
  const holdDetail = number => {
    let resolve;
    const ready = new Promise(done => { resolve = done; });
    const hold = { number, id: null, result: null, release: null, resolve, ready };
    transport.hold = hold; return hold;
  };
  const captured = async hold => {
    let timer;
    try { await Promise.race([hold.ready, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('No detail reply captured')), 6000); })]); }
    finally { clearTimeout(timer); }
    assert.equal(hold.result.Detail.view.item.draft.content.Question.status, 'Open');
  };
  const status = (number, value) => page.waitForFunction(([number, value, project]) =>
    document.querySelector('#detail-pane h2')?.textContent === `Q${number} · Live question ${number}` &&
    document.querySelector('#detail-pane .item-metadata .badge:nth-child(2)')?.textContent === value &&
    document.getElementById(`status-${project}-Q${number}`)?.textContent === value,
    [number, value, project.value]);
  const select = number => page.getByRole('button', { name: `Q${number} · Live question ${number}`, exact: true }).click();
  const answer = id => replace(id, item => ({ ...item, content: { Question: { ...item.content.Question, status: 'Answered', answer: 'Retained answer' } } }));
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  try {
    await page.goto(origin);
    await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
    await page.getByLabel('Project', { exact: true }).selectOption(project.value);
    await page.getByText('Data: current', { exact: true }).waitFor();
    await select(1); await status(1, 'Open');
    const delayed = holdDetail('1');
    await replace(ids[0], item => ({ ...item, body: 'Clarification before answering' }));
    await captured(delayed);
    await answer(ids[0]);
    delayed.release(); transport.hold = null;
    await status(1, 'Answered');
    await page.locator('#detail-pane section[data-field=answer]').getByText('Retained answer', { exact: true }).waitFor();
    cases.push('An answer committed during a held older item-detail reply reaches the main pane and result row');

    const replaced = holdDetail('2');
    await select(2); await captured(replaced);
    await answer(ids[1]);
    const before = transport.sockets;
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await status(2, 'Answered'); assert.ok(transport.sockets > before);
    transport.hold = null;
    cases.push('Connection replacement during a held item-detail read loads the newly answered selected Question');

    await select(3); await status(3, 'Open');
    const missed = new Promise(resolve => { transport.onDropped = resolve; });
    transport.dropUpdates = true;
    await answer(ids[2]);
    let missedTimer;
    try { await Promise.race([missed, new Promise((_, reject) => { missedTimer = setTimeout(() => reject(new Error('No Updated notification captured')), 6000); })]); }
    finally { clearTimeout(missedTimer); }
    assert.ok(transport.dropped > 0);
    assert.equal(await page.locator('#detail-pane .item-metadata .badge').nth(1).textContent(), 'Open');
    transport.dropUpdates = false;
    const oldSockets = transport.sockets;
    await page.evaluate(() => document.dispatchEvent(new Event('resume')));
    await status(3, 'Answered'); assert.ok(transport.sockets > oldSockets);
    await page.getByText('Data: current', { exact: true }).waitFor();
    cases.push('Resume after a missed notification refreshes the answered Question in the main pane and row');
    assert.deepEqual(errors, []);
    await page.screenshot({ path: evidence + '/question-live-ordering.png', fullPage: true });
    console.log(JSON.stringify({ cases, errors }, null, 2));
  } catch (failure) {
    const observed = await page.evaluate(() => ({ heading: document.querySelector('#detail-pane h2')?.textContent,
      badges: [...document.querySelectorAll('#detail-pane .item-metadata .badge')].map(node => node.textContent),
      statuses: [...document.querySelectorAll('td[id^=status-]')].map(node => ({ id: node.id, text: node.textContent })),
      footer: document.querySelector('footer')?.textContent, bodyTail: document.body.textContent.slice(-1300) }));
    await writeFile(evidence + '/question-live-failure.json', JSON.stringify({ observed, events }, null, 2));
    await page.screenshot({ path: evidence + '/question-live-failure.png', fullPage: true });
    throw failure;
  } finally {
    await writeFile(evidence + '/question-live-results.json', JSON.stringify({ cases, errors }, null, 2));
    await context.tracing.stop({ path: evidence + '/question-live-trace.zip' });
    await browser.close();
  }
}
await main();
