// Behavioral-Active Blackbox Good-Communication; Astra review ownership regressions.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {trackProtocol, receivedReply, settledRequests} from './browser-protocol.mjs';
const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function call(command) {
  const response = await fetch(origin + '/api/call', {method: 'POST', headers, body: JSON.stringify(command)});
  assert.equal(response.status, 200); const result = await response.json(); assert.equal(result.Failed, undefined); return result;
}
const draft = (kind, number) => ({title: `${kind} ${number}`, body: '', labels: [], archived: false, citations: [], content: kind === 'Question'
  ? {Question: {status: 'Open', prompt: `Choose ${number}`, context: 'Review race', alternatives: [], answer: null}}
  : {Task: {status: 'Ready', acceptance: ['Review race'], result: null, validation: []}}});
const change = (project, mutations) => call({Change: {input: {project, change: {request: {value: randomUUID()}, mutations, fences: [], reason: 'Race fixture'}}}});
const browser = await chromium.launch({headless: true}); const cases = [], failures = [];
try {
  for (const scenario of ['question-settled-revisit', 'question-transport-revisit', 'question-obsolete-error', 'graph-obsolete-ack']) {
    const kind = scenario.startsWith('question') ? 'Question' : 'Task';
    const projects = [{value: randomUUID()}, {value: randomUUID()}];
    for (const project of projects) {
      await call({Initialize: {config: {project, endpoint: origin, name: `${scenario} ${project.value}`}}});
      await change(project, [1, 2].map(number => ({Create: {draft: draft(kind, number)}})));
    }
    const context = await browser.newContext({viewport: {width: 1366, height: 768}}); await trackProtocol(context);
    let held = null;
    await context.routeWebSocket(/\/ws$/, route => {
      const server = route.connectToServer();
      route.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (held !== null && held.id === null && frame.Call && held.matches(frame.Call.command)) held.id = frame.Call.id.value;
        server.send(message);
      });
      server.onMessage(message => {
        const frame = JSON.parse(String(message));
        if (held !== null && frame.Reply?.id.value === held.id) {held.release = () => route.send(message); held.disconnect = () => route.close({code: 1000, reason: 'Question retry fixture'}); held.resolve();}
        else route.send(message);
      });
    });
    function hold(matches) {
      let resolve; const captured = new Promise(done => {resolve = done;}); held = {id: null, matches, resolve, release: null};
      return async () => {
        let timer; try {await Promise.race([captured, new Promise((_, reject) => {timer = setTimeout(() => reject(new Error('Expected held response')), 6000);})]);}
        finally {clearTimeout(timer);}
      };
    }
    const page = await context.newPage(); page.setDefaultTimeout(6000);
    const batch = () => page.getByRole('dialog', {name: 'Answer open questions', exact: true});
    const graph = () => page.getByRole('dialog', {name: 'Graph change', exact: true});
    const choose = project => page.getByLabel('Project', {exact: true}).selectOption(project.value);
    const open = () => page.getByRole('button', {name: 'Answer open questions', exact: true}).click();
    async function preview() {
      await page.getByRole('button', {name: 'T1 · Task 1', exact: true}).click();
      await page.getByRole('heading', {name: 'T1 · Task 1', exact: true}).waitFor();
      await page.getByLabel('Relationship', {exact: true}).selectOption('RelatesTo');
      await page.getByLabel('Target item', {exact: true}).fill('T2');
      await page.getByRole('button', {name: 'Preview relationship', exact: true}).click();
      await graph().getByRole('button', {name: 'Confirm graph change', exact: true}).waitFor();
    }
    try {
      await page.goto(origin); await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
      await page.getByRole('button', {name: 'Sign in', exact: true}).click(); await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
      await choose(projects[0]); await page.getByText('Data: current', {exact: true}).waitFor();
      if (kind === 'Question') {
        await open(); await batch().getByLabel('Answer', {exact: true}).fill('Owned answer');
        if (scenario === 'question-obsolete-error') {
          await change(projects[0], [{Replace: {id: {project: projects[0], ledger: 'Questions', number: '1'}, expected: {value: '1'}, draft: {...draft(kind, 1), body: 'New question context'}}}]);
        }
        const captured = hold(command => scenario === 'question-obsolete-error'
          ? command.Read?.input.selection.ItemDetail?.id.number === '1' : Boolean(command.Change));
        await batch().getByRole('button', {name: 'Save answer and next', exact: true}).click(); await captured();
        if (scenario === 'question-settled-revisit' || scenario === 'question-transport-revisit') {
          await batch().getByRole('button', {name: 'Skip / next question', exact: true}).click();
          await batch().getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
          await batch().getByRole('button', {name: 'Previous question', exact: true}).click();
          await batch().getByRole('button', {name: 'Retry exact answer', exact: true}).waitFor();
        } else {
          await batch().getByRole('button', {name: 'Close', exact: true}).click(); await choose(projects[1]); await open();
          await batch().getByLabel('Answer', {exact: true}).fill('New project draft');
        }
        if (scenario === 'question-transport-revisit') {
          await held.disconnect(); await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
          const retryLoad = batch().getByRole('button', {name: 'Retry loading question', exact: true});
          await retryLoad.waitFor(); await retryLoad.click();
          await batch().getByRole('button', {name: 'Retry exact answer', exact: true}).click();
          await batch().getByRole('heading', {name: 'Q2 · Question 2', exact: true}).waitFor();
          const view = (await call({Read: {input: {project: projects[0], selection: {ItemDetail: {id: {project: projects[0], ledger: 'Questions', number: '1'}}}}}})).Detail.view;
          assert.equal(view.item.revision.value, '2');
        } else {
          held.release(); await receivedReply(page, held.id); await settledRequests(page);
        }
        if (scenario === 'question-settled-revisit') {
          await batch().getByText('This question is no longer open. Your draft is retained; skip to another question.', {exact: true}).waitFor();
          assert.equal(await batch().getByRole('button', {name: 'Retry exact answer', exact: true}).count(), 0);
        } else if (scenario === 'question-obsolete-error') {
          assert.equal(await batch().getByRole('alert').isVisible(), false, 'Old question fault must not enter the new project dialog');
          assert.equal(await batch().getByLabel('Answer', {exact: true}).inputValue(), 'New project draft');
        }
      } else {
        await preview(); const captured = hold(command => Boolean(command.Change));
        await graph().getByRole('button', {name: 'Confirm graph change', exact: true}).click(); await captured();
        await graph().getByRole('button', {name: 'Close', exact: true}).click(); await choose(projects[1]); await preview();
        held.release(); await receivedReply(page, held.id); await settledRequests(page);
        assert.equal(await graph().isVisible(), true, 'Old project acknowledgement must not close the newer preview');
        assert.equal(await graph().getByRole('button', {name: 'Confirm graph change', exact: true}).isEnabled(), true);
      }
      cases.push(scenario);
    } catch (error) {failures.push({scenario, error: String(error)});}
    finally {await page.screenshot({path: `${evidence}/${scenario}.png`, fullPage: true}); await context.close();}
  }
} finally {
  await writeFile(evidence + '/dialog-races-results.json', JSON.stringify({cases, failures}, null, 2)); await browser.close();
}
assert.deepEqual(failures, []);
