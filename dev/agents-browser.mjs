// Behavioral-Active Blackbox Good-Communication; I17 agent model configuration: the operator edits the server defaults and the project's
// override in the Agent models dialog under revision comparison; the problems and the resolved table of an unsaved text are the server's
// Preview reply. The server defaults belong to the installation, so the fixture puts back the text it found.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function reply(command) {
  const response = await fetch(origin + '/api/call', {method: 'POST', headers, body: JSON.stringify(command)});
  assert.equal(response.status, 200, await response.clone().text()); return response.json();
}
async function call(command) { const result = await reply(command); assert.equal(result.Failed, undefined, JSON.stringify(result)); return result; }
const project = {value: randomUUID()}, other = {value: randomUUID()};
await call({Initialize: {config: {project, endpoint: origin, name: `Agents fixture ${project.value}`}}});
await call({Initialize: {config: {project: other, endpoint: origin, name: `Agents fixture, second project ${other.value}`}}});
const agents = (target, action) => reply({Agents: {input: {project: target, action}}});
const stored = async target => (await call({Agents: {input: {project: target, action: {Read: {}}}}})).Agents.value;
const layer = async (target, name) => { const value = (await stored(target))[name]; return [value.text, value.revision.value]; };
const replace = (target, scope, expected, text) => agents(target, {Replace: {scope: {[scope]: {}}, expected: {value: String(expected)}, text}});

// The example of the approved design, with neutral model names: the text the help shows.
const EXAMPLE = `defaults:
  roles:
    planner:  $harness:@frontier
    worker:   { fallback: [$harness:@standard, pi:@standard] }
    explorer: $harness:@fast
    reviewer: { any: [claude:@standard, pi:@standard], min: 1 }
harnesses:
  claude: { tiers: { frontier: [opus], standard: [sonnet], fast: [haiku] } }
  codex:
    tiers: { frontier: [large-model?effort=xhigh], standard: [large-model], fast: [small-model?effort=low] }
    roles: { reviewer: { all: [claude:@standard, pi:@standard], min: 1 } }
  pi:
    tiers: { frontier: [provider-a/large-model?effort=xhigh], standard: [provider-a/model-a, provider-b/model-b], fast: [provider-b/model-b?effort=low] }
`;
const MISSPELT = EXAMPLE.replace('$harness:@frontier\n', '$harness:@frontierr\n');
const OVERRIDE = 'harnesses:\n  codex:\n    roles: { reviewer: $harness:@standard }\n';
const PI_ROUTES = ['pi:provider-a/model-a', 'pi:provider-b/model-b'];
const VIEWPORTS = [{width: 1440, height: 900}, {width: 1280, height: 720}];

const found = await stored(project);
// The fixture starts from a server without defaults, and ends with the defaults it found.
if (found.installation.text !== '') assert.equal((await replace(project, 'Installation', found.installation.revision.value, '')).Failed, undefined);
const start = Number((await layer(project, 'installation'))[1]);

/** The dialog of one page. */
function surface(page) {
  const dialog = page.getByRole('dialog', {name: 'Agent models', exact: true});
  const section = name => dialog.locator(`.agents-layer[data-layer=${name}]`);
  const preview = dialog.locator('.agents-preview');
  const tab = name => dialog.getByRole('tab', {name: new RegExp(`^${name === 'installation' ? 'Server defaults' : 'This project'} · `)});
  return {
    dialog, section, preview, tab,
    // One editor is shown at a time, in the tab of its layer: filling an editor selects its tab first, and reading one does not need it shown.
    editor: name => {
      const box = dialog.getByRole('textbox', {name: name === 'installation' ? 'Server defaults' : 'This project', exact: true, includeHidden: true});
      const fill = box.fill.bind(box); box.fill = async text => { await tab(name).click(); await fill(text); };
      return box;
    },
    save: name => dialog.getByRole('button', {name: name === 'installation' ? 'Save server defaults' : 'Save this project', exact: true}),
    problems: dialog.locator('.agents-problems'),
    cell: (role, harness) => dialog.locator(`.agents-table td[data-role=${role}][data-harness=${harness}]`),
    routes: (role, harness) => dialog.locator(`.agents-table td[data-role=${role}][data-harness=${harness}] .agents-route`).allTextContents(),
    state: value => dialog.locator(`.agents-preview[data-state=${value}]`).waitFor(),
    close: dialog.getByRole('button', {name: 'Close', exact: true}),
    choose: async target => { await page.getByLabel('Project', {exact: true}).selectOption(target.value); await page.getByText('Data: current', {exact: true}).waitFor(); },
    open: () => page.getByRole('navigation').getByRole('button', {name: 'Agent models', exact: true}).click(),
  };
}
/**
 * In the page: the tokens of a block of coloured text in the order of the text, each as `kind text`, the text that has no colour, and
 * a range for each token. A token is a range of the highlight registry over the one text node of its line.
 */
function colouring(root) {
  const lines = [...root.querySelectorAll('.agents-line')].map(line => line.firstChild).filter(node => node !== null);
  const found = new Map(lines.map(node => [node, []]));
  for (const [name, highlight] of CSS.highlights) for (const range of highlight) found.get(range.startContainer)?.push([range.startOffset, range.endOffset, name.replace('agents-token-', '')]);
  const tokens = [], plain = [], ranges = [];
  for (const [node, parts] of found) {
    let at = 0;
    for (const [start, end, kind] of parts.sort((left, right) => left[0] - right[0])) {
      plain.push(node.data.slice(at, start)); tokens.push(`${kind} ${node.data.slice(start, end)}`); at = end;
      const range = document.createRange(); range.setStart(node, start); range.setEnd(node, end); ranges.push(range);
    }
    plain.push(node.data.slice(at));
  }
  return {text: root.textContent, tokens, plain: plain.flatMap(part => part.split(/\s+/)).filter(part => part !== ''), ranges};
}
async function signIn(context) {
  const page = await context.newPage(); page.setDefaultTimeout(8000);
  await page.goto(origin);
  await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
  await page.getByText('Connection: ALIVE', {exact: true}).waitFor();
  return page;
}

const browser = await chromium.launch({headless: true});
const cases = [], errors = [], screenshots = [];
try {
  const context = await browser.newContext({viewport: VIEWPORTS[0]});
  // `hold()` keeps back the reply to the page's next Preview call and resolves to the function that delivers it.
  const holds = [];
  await context.routeWebSocket(/\/ws$/, route => {
    const server = route.connectToServer();
    route.onMessage(message => {
      const action = JSON.parse(String(message)).Call?.command.Agents?.input.action;
      const held = action?.Preview === undefined ? undefined : holds.find(entry => entry.id === null);
      if (held !== undefined) held.id = JSON.parse(String(message)).Call.id.value;
      server.send(message);
    });
    server.onMessage(message => {
      const frame = JSON.parse(String(message)); const held = holds.find(entry => entry.id !== null && entry.id === frame.Reply?.id.value);
      if (held === undefined) route.send(message); else { holds.splice(holds.indexOf(held), 1); held.resolve(() => route.send(message)); }
    });
  });
  function hold() { let resolve; const captured = new Promise(done => { resolve = done; }); holds.push({id: null, resolve}); return captured; }
  const page = await signIn(context); page.on('pageerror', error => errors.push(String(error)));
  const ui = surface(page);
  const revision = (name, value) => ui.section(name).getByText(new RegExp(`^Revision ${value} · operator · `)).waitFor();
  try {
    await ui.choose(project);
    const entries = await page.getByRole('navigation').getByRole('button').evaluateAll(nodes => nodes.map(node => node.textContent));
    assert.deepEqual(entries.slice(entries.indexOf('Standing requirements'), entries.indexOf('Standing requirements') + 3), ['Standing requirements', 'Process mode', 'Agent models']);
    await ui.open(); await ui.state('current');
    const bounds = await ui.dialog.evaluate(node => { const box = node.getBoundingClientRect(); return [Math.round(box.width / innerWidth * 100), Math.round(box.height / innerHeight * 100)]; });
    assert.deepEqual(bounds, [90, 90], 'The dialog is the large variant');
    assert.deepEqual([await ui.editor('installation').inputValue(), await ui.editor('project').inputValue()], ['', '']);
    await ui.tab('project').click();
    await ui.section('project').getByText('No text has been saved for this project: it inherits the server defaults.', {exact: true}).waitFor();
    await ui.tab('installation').click();
    if (start === 0) await ui.section('installation').getByText('No server defaults have been saved.', {exact: true}).waitFor();
    assert.match(await ui.editor('project').getAttribute('placeholder'), /^Empty: this project inherits the server defaults\. That is the normal case\./);
    assert.equal(await ui.problems.getByRole('heading').textContent(), 'Problems: none');
    // Without any text no role has a model: every cell says so in the error style.
    assert.equal(await ui.dialog.locator('.agents-table td[data-state=unresolved]').count(), 12);
    assert.equal(await ui.cell('Planner', 'Codex').textContent(), 'Not resolvedno layer assigns the planner role when codex governs');
    const help = ui.dialog.locator('details.agents-help');
    assert.equal(await help.evaluate(node => node.open), false);
    await help.getByText('How to write it', {exact: true}).click();
    assert.equal(await help.getByLabel('Example of server defaults', {exact: true}).textContent(), EXAMPLE);
    const grammar = await help.getByLabel('How an agent model configuration is written', {exact: true}).textContent();
    for (const word of ['defaults.roles', 'harnesses.H.tiers', 'harness:@tier', '$harness', '{ fallback: [a, b] }', '{ rr: [a, b] }', '{ first: [a, b] }', '{ all: [seat, seat], min: 1 }', '{ any: [seat, seat], min: 1 }'])
      assert.ok(grammar.includes(word), `The help does not show ${word}`);
    await help.getByText('How to write it', {exact: true}).click();
    cases.push('the dialog opens beside Process mode as the large variant, with both texts empty, the placeholder that an empty project text inherits, every role unresolved, and a collapsed help with the grammar and the example');

    await ui.editor('installation').fill(EXAMPLE); await ui.state('current');
    await ui.preview.getByText('The preview shows the unsaved Server defaults with the saved text of this project.', {exact: true}).waitFor();
    assert.equal(await ui.dialog.locator('.agents-table td[data-state=resolved]').count(), 12);
    assert.deepEqual(await ui.dialog.locator('.agents-table thead th').allTextContents(), ['Role', 'Claude Code governs', 'Codex governs', 'Pi governs']);
    assert.deepEqual(await ui.dialog.locator('.agents-table tbody th').allTextContents(), ['Planner', 'Worker', 'Explorer', 'Reviewer']);
    assert.deepEqual(await ui.routes('Planner', 'Codex'), ['codex:large-model?effort=xhigh']);
    assert.deepEqual(await ui.routes('Planner', 'Pi'), ['pi:provider-a/large-model?effort=xhigh']);
    assert.deepEqual(await ui.routes('Explorer', 'Claude'), ['claude:haiku']);
    assert.deepEqual(await ui.routes('Worker', 'Codex'), ['codex:large-model', ...PI_ROUTES]);
    assert.equal(await ui.cell('Worker', 'Codex').locator('.agents-strategy').textContent(), 'fallback:');
    assert.equal(await ui.cell('Planner', 'Codex').locator('.agents-source').textContent(), 'from server defaults.roles');
    const panel = ui.cell('Reviewer', 'Codex');
    assert.equal(await panel.locator('.agents-panel').textContent(), 'all panel · min 1 of 2');
    assert.deepEqual(await panel.locator('ol.agents-seats > li').evaluateAll(seats => seats.map(seat => [...seat.children].map(child => child.textContent))),
      [['claude:sonnet'], ['fallback:', ...PI_ROUTES]]);
    assert.equal(await panel.locator('.agents-source').textContent(), 'from server harnesses.codex.roles');
    assert.equal(await panel.locator('.agents-self-review').count(), 0);
    assert.equal(await ui.cell('Reviewer', 'Claude').locator('.agents-panel').textContent(), 'any panel · min 1 of 2');
    assert.equal(await ui.cell('Reviewer', 'Claude').locator('.agents-self-review').textContent(), 'Self-review (seat 1): a model of Claude Code, the governing harness, can review its own work.');
    assert.equal(await ui.cell('Reviewer', 'Pi').locator('.agents-self-review').textContent(), 'Self-review (seat 2): a model of Pi, the governing harness, can review its own work.');
    assert.equal(await ui.dialog.locator('.agents-self-review').count(), 2);
    assert.deepEqual(await layer(project, 'installation'), ['', String(start)]);
    cases.push('the approved example typed as server defaults fills the twelve cells with seats in reference syntax, their source, panels with mode and min, and the self-review note, before anything is saved');

    await ui.editor('installation').fill(MISSPELT); await ui.state('invalid');
    assert.equal(await ui.problems.getByRole('heading').textContent(), 'Problems: 1');
    const problem = ui.problems.getByRole('listitem');
    assert.equal(await problem.textContent(), "Server defaults3:15unknown tier 'frontierr'; expected one of frontier, standard, fast");
    await ui.preview.getByText('The preview needs a valid text: correct the problems listed above.', {exact: true}).waitFor();
    assert.equal(await ui.dialog.locator('.agents-table').count(), 0);
    await problem.getByRole('button', {name: 'Server defaults, line 3, column 15: show in the editor', exact: true}).click();
    const caret = await ui.editor('installation').evaluate(node => [document.activeElement === node, node.selectionStart]);
    assert.deepEqual(caret, [true, MISSPELT.indexOf('$harness:@frontierr')]);
    await ui.save('installation').click();
    const refusal = ui.section('installation').getByRole('alert');
    await refusal.getByText("Agent configuration has problems: 3:15: unknown tier 'frontierr'; expected one of frontier, standard, fast", {exact: true}).waitFor();
    assert.equal(await ui.editor('installation').inputValue(), MISSPELT);
    assert.deepEqual(await layer(project, 'installation'), ['', String(start)]);
    cases.push('a misspelt tier is listed with its layer and line:column, the position moves the caret there, the preview asks for a valid text, and a save is refused beside the editor in the server\'s words without losing the text');

    await ui.editor('installation').fill(EXAMPLE); await ui.state('current');
    assert.equal(await refusal.isVisible(), false);
    await ui.save('installation').click(); await revision('installation', start + 1);
    await page.getByText(`Server default agent models saved at revision ${start + 1}.`, {exact: true}).waitFor();
    await ui.preview.getByText('The preview shows the saved texts.', {exact: true}).waitFor();
    assert.deepEqual(await layer(project, 'installation'), [EXAMPLE, String(start + 1)]);
    await ui.save('installation').click();
    await page.getByText('Server default agent models unchanged: the text equals the saved one.', {exact: true}).waitFor();
    assert.deepEqual(await layer(project, 'installation'), [EXAMPLE, String(start + 1)]);
    await page.reload(); await page.getByText('Connection: ALIVE', {exact: true}).waitFor(); await ui.choose(project);
    await ui.open(); await ui.state('current'); await revision('installation', start + 1);
    assert.equal(await ui.editor('installation').inputValue(), EXAMPLE);
    assert.deepEqual(await ui.routes('Worker', 'Codex'), ['codex:large-model', ...PI_ROUTES]);
    cases.push('the corrected text is saved at the next revision, a second save says that nothing changed, and a reload shows the saved text, its revision and the table');

    // Another operator's browser saves the server defaults while this one holds an unsaved text.
    const second = await browser.newContext({viewport: VIEWPORTS[1]});
    try {
      const elsewhere = await signIn(second); const theirs = surface(elsewhere);
      await theirs.choose(other); await theirs.open(); await theirs.state('current');
      await theirs.editor('installation').fill(EXAMPLE + '# theirs\n'); await theirs.state('current');
      await theirs.save('installation').click();
      await theirs.section('installation').getByText(new RegExp(`^Revision ${start + 2} · operator · `)).waitFor();
    } finally { await second.close(); }
    await ui.editor('installation').fill(EXAMPLE + '# mine\n'); await ui.state('current');
    await ui.save('installation').click();
    const conflict = ui.section('installation');
    await conflict.getByRole('heading', {name: 'Edit conflict', exact: true}).waitFor();
    await conflict.getByText(`Your text is based on revision ${start + 1}; the current revision is ${start + 2}.`, {exact: false}).waitFor();
    assert.equal(await conflict.getByLabel('Current server defaults', {exact: true}).textContent(), EXAMPLE + '# theirs\n');
    assert.equal(await ui.editor('installation').inputValue(), EXAMPLE + '# mine\n');
    assert.deepEqual(await layer(project, 'installation'), [EXAMPLE + '# theirs\n', String(start + 2)]);
    await ui.close.click(); await ui.dialog.waitFor({state: 'hidden'}); await ui.open(); await ui.state('current');
    assert.equal(await ui.editor('installation').inputValue(), EXAMPLE + '# mine\n', 'The unsaved text survives closing the dialog');
    await conflict.getByRole('button', {name: 'Use current revision as base', exact: true}).click(); await revision('installation', start + 2);
    assert.equal(await conflict.getByRole('heading', {name: 'Edit conflict', exact: true}).count(), 0);
    await ui.save('installation').click(); await revision('installation', start + 3);
    assert.deepEqual(await layer(project, 'installation'), [EXAMPLE + '# mine\n', String(start + 3)]);
    assert.equal((await replace(project, 'Installation', start + 3, EXAMPLE)).Failed, undefined);
    await ui.editor('installation').fill('# abandoned\n'); await ui.state('current'); await ui.save('installation').click();
    await conflict.getByRole('button', {name: 'Discard my text and take the current one', exact: true}).click(); await revision('installation', start + 4);
    await ui.state('current');
    assert.equal(await ui.editor('installation').inputValue(), EXAMPLE);
    assert.deepEqual(await layer(project, 'installation'), [EXAMPLE, String(start + 4)]);
    cases.push('a save over another browser\'s save changes nothing and shows their text beside the kept one, which survives closing the dialog; the operator saves it on the current revision, or takes theirs');

    await ui.editor('project').fill(OVERRIDE); await ui.state('current');
    await ui.preview.getByText('The preview shows the unsaved This project with the saved server defaults.', {exact: true}).waitFor();
    assert.deepEqual(await ui.routes('Reviewer', 'Codex'), ['codex:large-model']);
    assert.equal(await ui.cell('Reviewer', 'Codex').locator('.agents-source').textContent(), 'from project harnesses.codex.roles');
    assert.equal(await ui.cell('Reviewer', 'Codex').locator('.agents-self-review').textContent(), 'Self-review: a model of Codex, the governing harness, can review its own work.');
    assert.equal(await ui.cell('Reviewer', 'Codex').locator('.agents-panel').count(), 0);
    assert.deepEqual(await ui.dialog.locator('.agents-source').evaluateAll(nodes => nodes.filter(node => node.dataset.layer === 'project').map(node => [node.closest('td').dataset.role, node.closest('td').dataset.harness])),
      [['Reviewer', 'Codex']]);
    assert.equal(await ui.cell('Reviewer', 'Claude').locator('.agents-source').textContent(), 'from server defaults.roles');
    await ui.save('project').click(); await revision('project', 1);
    await page.getByText('Agent models of this project saved at revision 1.', {exact: true}).waitFor();
    assert.deepEqual(await layer(project, 'project'), [OVERRIDE, '1']);
    assert.deepEqual(await layer(other, 'project'), ['', '0']);
    const route = (await call({Agents: {input: {project, action: {Resolve: {harness: 'Codex', work: {Reviewer: {mode: 'Candidate'}}}}}}})).AgentRoute.value.resolution.Resolved.plan;
    assert.deepEqual([route.origin, route.seats.map(seat => seat.candidates.map(candidate => candidate.model)), route.selfReview], [{layer: 'Project', source: 'HarnessRoles'}, [['large-model']], [0]]);
    cases.push('a project override changes one cell and its source label, marks the self-review, and is saved for this project only; the server resolves the role the same way');

    // A reference that one governing harness cannot run leaves that cell unresolved; the other two resolve it.
    const broken = 'defaults:\n  roles:\n    explorer: $harness:mini\n';
    await ui.editor('project').fill(broken); await ui.state('current');
    assert.deepEqual([await ui.routes('Explorer', 'Claude'), await ui.routes('Explorer', 'Codex')], [['claude:mini'], ['codex:mini']]);
    const unresolved = ui.cell('Explorer', 'Pi');
    assert.equal(await unresolved.getAttribute('data-state'), 'unresolved');
    assert.equal(await unresolved.textContent(), 'Not resolved3:15a pi model is written provider/modelfrom project defaults.roles');
    const colours = await ui.dialog.locator('.agents-table td').evaluateAll(cells => cells.map(cell => [cell.dataset.state, getComputedStyle(cell).backgroundColor]));
    assert.equal(new Set(colours.filter(([state]) => state === 'resolved').map(([, colour]) => colour)).size, 1);
    assert.ok(colours.filter(([state]) => state === 'unresolved').every(([, colour]) => colour !== colours.find(([state]) => state === 'resolved')[1]), 'An unresolved cell is not marked');
    assert.equal(colours.filter(([state]) => state === 'unresolved').length, 1);
    await unresolved.getByRole('button', {name: 'This project, line 3, column 15: show in the editor', exact: true}).click();
    assert.deepEqual(await ui.editor('project').evaluate(node => [document.activeElement === node, node.selectionStart]), [true, broken.indexOf('$harness:mini')]);
    assert.equal(await ui.problems.getByRole('heading').textContent(), 'Problems: none');
    cases.push('a role that one governing harness cannot run is shown unresolved in the error style with the problem, its position in the layer that wrote it and that layer as the source');

    // A key of one mode of a role decides that mode where it stands; the mode gets a row under the row of its role.
    const moded = 'harnesses:\n  codex:\n    roles: { reviewer/plan: $harness:@frontier }\n';
    await ui.editor('project').fill(moded); await ui.state('current');
    assert.equal(await ui.problems.getByRole('heading').textContent(), 'Problems: none');
    assert.deepEqual(await ui.dialog.locator('.agents-table tbody th').allTextContents(), ['Planner', 'Worker', 'Explorer', 'Reviewer other modes', 'Reviewer/Plan']);
    const plan = harness => ui.dialog.locator(`.agents-table td[data-role=Reviewer][data-mode=Plan][data-harness=${harness}]`);
    const rest = harness => ui.dialog.locator(`.agents-table td[data-role=Reviewer][data-harness=${harness}]:not([data-mode])`);
    assert.deepEqual(await plan('Codex').locator('.agents-route').allTextContents(), ['codex:large-model?effort=xhigh']);
    assert.equal(await plan('Codex').locator('.agents-source').textContent(), 'from project harnesses.codex.roles.reviewer/plan');
    assert.equal(await plan('Codex').locator('.agents-self-review').textContent(), 'Self-review: a model of Codex, the governing harness, can review its own work.');
    assert.equal(await rest('Codex').locator('.agents-panel').textContent(), 'all panel · min 1 of 2');
    assert.equal(await rest('Codex').locator('.agents-source').textContent(), 'from server harnesses.codex.roles');
    // A harness without the key of the mode runs the mode as its role, and the row says which key decided.
    assert.deepEqual([await plan('Claude').locator('.agents-route').allTextContents(), await rest('Claude').locator('.agents-route').allTextContents()],
      [['claude:sonnet', ...PI_ROUTES], ['claude:sonnet', ...PI_ROUTES]]);
    assert.equal(await plan('Claude').locator('.agents-source').textContent(), 'from server defaults.roles.reviewer');
    assert.equal(await rest('Claude').locator('.agents-source').textContent(), 'from server defaults.roles');
    const planned = (await call({Agents: {input: {project, action: {Preview: {scope: {Project: {}}, text: moded}}}}})).Agents.value.assignments
      .filter(value => value.harness === 'Codex' && value.key.Reviewer !== undefined);
    assert.deepEqual(planned.map(value => [value.key, value.resolution.Resolved.plan.origin]), [[{Reviewer: {mode: 'Plan'}}, {layer: 'Project', source: 'HarnessRoles'}]]);
    // A key of one mode that an earlier place hides is noted under the table; the text has no problem and the table is shown.
    assert.equal(await ui.dialog.locator('.agents-notes').count(), 0);
    await ui.editor('project').fill('defaults:\n  roles:\n    reviewer/plan: claude:opus\n'); await ui.state('current');
    assert.equal(await ui.problems.getByRole('heading').textContent(), 'Problems: none');
    assert.deepEqual(await ui.dialog.locator('.agents-notes li').allTextContents(), []);
    await ui.editor('project').fill('defaults:\n  roles:\n    reviewer/plan: claude:opus\nharnesses:\n  codex:\n    roles:\n      reviewer: claude:sonnet\n'); await ui.state('current');
    assert.equal(await ui.problems.getByRole('heading').textContent(), 'Problems: none');
    assert.deepEqual(await ui.dialog.locator('.agents-notes li').allTextContents(), [
      'Note: when codex governs, defaults.roles.reviewer/plan of the project override (3:5) never decides: harnesses.codex.roles.reviewer of the project override (7:7) is found first and decides every mode of the reviewer role.']);
    // The table agrees with the note: under Codex the row of the mode is decided by the key of the role.
    assert.deepEqual(await plan('Codex').locator('.agents-route').allTextContents(), ['claude:sonnet']);
    assert.equal(await plan('Codex').locator('.agents-source').textContent(), 'from project harnesses.codex.roles.reviewer');
    assert.deepEqual(await plan('Claude').locator('.agents-route').allTextContents(), ['claude:opus']);
    cases.push('a key of one mode that the key of its role hides in an earlier place is noted under the table with both keys, their positions and the harness, and is no problem');

    await ui.editor('project').fill('defaults:\n  roles:\n    reviewer/draft: claude:opus\n'); await ui.state('invalid');
    assert.equal(await ui.problems.getByRole('listitem').textContent(), "This project3:5the key 'reviewer/draft' names no mode of the reviewer role; its modes are candidate, plan, audit");
    await ui.editor('project').fill(broken); await ui.state('current');
    cases.push('a key of one mode of a role is shown in a row of its own with the key that decided each cell, and a key with a mode its role does not have is a problem that names the modes');

    // The unsaved project text stays with its project while the dialog is opened for another one.
    await ui.close.click(); await ui.choose(other); await ui.open(); await ui.state('current');
    assert.equal(await ui.editor('project').inputValue(), '');
    await ui.tab('project').click();
    await ui.section('project').getByText('No text has been saved for this project: it inherits the server defaults.', {exact: true}).waitFor();
    assert.equal(await ui.cell('Reviewer', 'Codex').locator('.agents-panel').textContent(), 'all panel · min 1 of 2');
    assert.equal(await ui.editor('installation').inputValue(), EXAMPLE);
    await ui.close.click(); await ui.choose(project); await ui.open(); await ui.state('current'); await revision('project', 1);
    assert.equal(await ui.editor('project').inputValue(), broken);
    assert.equal(await ui.cell('Explorer', 'Pi').getAttribute('data-state'), 'unresolved');
    await ui.editor('project').fill(OVERRIDE); await ui.state('current');
    await ui.preview.getByText('The preview shows the saved texts.', {exact: true}).waitFor();
    cases.push('another project shows its own empty text over the same server defaults, and the unsaved text of the first project is there on return');

    // A Preview reply that arrives after a later text was previewed does not replace the later table.
    const first = hold();
    await ui.editor('installation').fill(EXAMPLE.replace('[opus]', '[earlier-model]')); const deliver = await first;
    await ui.editor('installation').fill(EXAMPLE.replace('[opus]', '[later-model]')); await ui.state('current');
    assert.deepEqual(await ui.routes('Planner', 'Claude'), ['claude:later-model']);
    deliver(); await page.waitForTimeout(500);
    assert.deepEqual(await ui.routes('Planner', 'Claude'), ['claude:later-model']);
    assert.equal(await ui.preview.getAttribute('data-state'), 'current');
    await ui.editor('installation').fill(EXAMPLE); await ui.state('current');
    assert.deepEqual(await ui.routes('Planner', 'Claude'), ['claude:opus']);
    cases.push('the reply to an earlier preview that arrives after a later one is ignored');
    await ui.close.click();
    assert.deepEqual(errors, []);
  } finally { await page.screenshot({path: `${evidence}/agents-page.png`}); await context.close(); }

  // The dialog with the example and with a problem, at the two laptop sizes: the table stays inside the dialog and no cell is clipped.
  for (const viewport of VIEWPORTS) {
    const name = `${viewport.width}x${viewport.height}`;
    const context = await browser.newContext({viewport});
    try {
      const page = await signIn(context); page.on('pageerror', error => errors.push(String(error)));
      const ui = surface(page);
      await ui.choose(other); await ui.open(); await ui.state('current');
      const shot = async label => { const path = `${evidence}/agents-${label}-${name}.png`; await ui.dialog.screenshot({path}); screenshots.push(path); };
      await shot('example');
      await ui.preview.evaluate(node => node.scrollIntoView({block: 'start'})); await shot('example-table');
      const layout = await ui.dialog.evaluate(node => {
        const body = node.querySelector('.dialog-body'), table = node.querySelector('.agents-table');
        return {page: document.documentElement.scrollWidth > innerWidth, body: body.scrollWidth > body.clientWidth, table: table.getBoundingClientRect().right <= body.getBoundingClientRect().right,
          clipped: [...table.querySelectorAll('td, th')].filter(cell => cell.scrollWidth > cell.clientWidth).length,
          // A heading broken inside a word is on two lines.
          broken: [...table.querySelectorAll('th')].filter(cell => { const range = document.createRange(); range.selectNodeContents(cell); return range.getClientRects().length !== 1; }).map(cell => cell.textContent),
          columns: [...table.querySelectorAll('thead th')].slice(1).map(cell => Math.round(cell.getBoundingClientRect().width))};
      });
      assert.deepEqual([layout.page, layout.body, layout.table, layout.clipped, layout.broken], [false, false, true, 0, []], `${name}: ${JSON.stringify(layout)}`);
      assert.ok(Math.max(...layout.columns) - Math.min(...layout.columns) <= 1, `${name}: the harness columns differ in width: ${layout.columns}`);
      // An unsaved project text with an override and a reference that Pi cannot run.
      await ui.editor('project').fill('defaults:\n  roles:\n    explorer: $harness:mini\n' + OVERRIDE); await ui.state('current');
      assert.equal(await ui.dialog.locator('.agents-table td[data-state=unresolved]').count(), 1);
      await ui.preview.evaluate(node => node.scrollIntoView({block: 'start'})); await shot('override-table');
      await ui.editor('project').fill(''); await ui.state('current');
      await ui.dialog.locator('.dialog-body').evaluate(node => { node.scrollTop = 0; });
      await ui.editor('installation').fill(MISSPELT); await ui.state('invalid'); await ui.save('installation').click();
      await ui.section('installation').getByRole('alert').getByText(/^Agent configuration has problems: 3:15: /).waitFor();
      await shot('error');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${name}: horizontal page overflow`);
      assert.deepEqual(await layer(other, 'installation'), [EXAMPLE, String(start + 4)]);
    } finally { await context.close(); }
  }
  cases.push('at 1440×900 and 1280×720 the table of the example stays inside the dialog with equal harness columns and no clipped cell');

  // The two texts as tabs, and the colouring of the editors. Nothing here is saved.
  {
    const context = await browser.newContext({viewport: VIEWPORTS[0]});
    try {
      const page = await signIn(context); page.on('pageerror', error => errors.push(String(error)));
      const ui = surface(page);
      const labels = () => ui.dialog.getByRole('tab').allTextContents();
      const shown = () => ui.dialog.evaluate(node => [node.querySelector('[role=tab][aria-selected=true]').id.replace('agents-tab-', ''),
        [...node.querySelectorAll('[role=tab]')].map(tab => tab.tabIndex), [...node.querySelectorAll('.agents-layer')].filter(panel => !panel.hidden).map(panel => panel.dataset.layer)]);
      const saved = start + 4;
      // The project with an override opens on its tab.
      await ui.choose(project); await ui.open(); await ui.state('current');
      assert.equal(await ui.dialog.getByRole('tablist', {name: 'Texts', exact: true}).count(), 1);
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved}`, 'This project · revision 1']);
      assert.deepEqual(await shown(), ['project', [-1, 0], ['project']]);
      assert.deepEqual(await ui.dialog.getByRole('tab').evaluateAll(tabs => tabs.map(tab => {
        const panel = document.getElementById(tab.getAttribute('aria-controls'));
        return [panel.getAttribute('role'), panel.getAttribute('aria-labelledby') === tab.id, panel.dataset.layer, panel.querySelectorAll('textarea').length];
      })), [['tabpanel', true, 'installation', 1], ['tabpanel', true, 'project', 1]]);
      assert.deepEqual([await ui.dialog.getByRole('tabpanel').count(), await ui.dialog.getByRole('textbox').count()], [1, 1]);
      // Save, the revision line and the editor are in the panel of their layer; the problems, the preview and the help are outside both.
      assert.deepEqual(await ui.dialog.evaluate(node => ['.agents-problems', '.agents-preview', '.agents-help', '.agents-layer[data-layer=project] .revision-meta', '.agents-layer[data-layer=project] > button']
        .map(selector => { const found = node.querySelector(selector); return [found.closest('[role=tabpanel]') !== null, found.getClientRects().length > 0]; })),
        [[false, true], [false, true], [false, true], [true, true], [true, true]]);
      const widths = await ui.section('project').evaluate(node => {
        const body = node.closest('.dialog-body'), style = getComputedStyle(body);
        return [node.querySelector('textarea').getBoundingClientRect().width, body.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)];
      });
      assert.ok(Math.abs(widths[0] - widths[1]) < 1, `The editor is not as wide as the dialog: ${widths}`);
      await ui.tab('project').focus();
      for (const [key, expected] of [['ArrowLeft', 'installation'], ['ArrowRight', 'project'], ['ArrowRight', 'installation'], ['End', 'project'], ['Home', 'installation'], ['ArrowLeft', 'project']]) {
        await page.keyboard.press(key);
        assert.deepEqual([await shown(), await page.evaluate(() => document.activeElement.id)],
          [[expected, expected === 'project' ? [-1, 0] : [0, -1], [expected]], `agents-tab-${expected}`], `After ${key}`);
      }
      await page.keyboard.press('Tab');
      assert.equal(await ui.editor('project').evaluate(node => document.activeElement === node), true, 'Tab moves from the selected tab into its editor');
      // A project without an override opens on the server defaults.
      await ui.close.click(); await ui.choose(other); await ui.open(); await ui.state('current');
      assert.deepEqual(await shown(), ['installation', [0, -1], ['installation']]);
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved}`, 'This project · revision 0']);
      cases.push('the two texts are tabs in the pattern of the Help dialog: one panel and one editor shown at the width of the dialog, arrow, Home and End keys, the project tab first when the project has a text and the server defaults otherwise');

      const LONG = EXAMPLE + Array.from({length: 80}, (_, index) => `# line ${index}\n`).join('');
      await ui.editor('installation').fill(LONG); await ui.state('current');
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved} · unsaved`, 'This project · revision 0']);
      await ui.editor('installation').evaluate(node => { node.setSelectionRange(40, 47); node.scrollTop = 300; });
      await ui.editor('project').fill(OVERRIDE); await ui.state('current');
      await ui.editor('project').evaluate(node => node.setSelectionRange(5, 5));
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved} · unsaved`, 'This project · revision 0 · unsaved']);
      const kept = name => ui.editor(name).evaluate(node => [node.value, node.selectionStart, node.selectionEnd, node.scrollTop, node.parentElement.querySelector('code').style.transform]);
      await ui.tab('installation').click();
      assert.deepEqual(await kept('installation'), [LONG, 40, 47, 300, 'translate(0px, -300px)']);
      await ui.tab('project').click();
      assert.deepEqual(await kept('project'), [OVERRIDE, 5, 5, 0, 'translate(0px, 0px)']);
      await ui.close.click(); await ui.open(); await ui.state('current');
      assert.deepEqual([await shown(), await labels(), await ui.editor('installation').inputValue(), await ui.editor('project').inputValue()],
        [['project', [-1, 0], ['project']], [`Server defaults · revision ${saved} · unsaved`, 'This project · revision 0 · unsaved'], LONG, OVERRIDE]);
      cases.push('an unsaved text, its caret and its scroll position survive showing the other tab, and each tab says that its text is unsaved');

      // A problem of the text that is not shown: its tab counts it, and its position shows that tab with the caret there.
      const DEEP = Array.from({length: 60}, (_, index) => `# line ${index}\n`).join('') + MISSPELT;
      await ui.editor('installation').fill(DEEP); await ui.state('invalid');
      await ui.editor('installation').evaluate(node => { node.scrollTop = 0; });
      await ui.tab('project').click();
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved} · unsaved · 1 problem`, 'This project · revision 0 · unsaved']);
      assert.equal(await ui.problems.getByRole('listitem').textContent(), "Server defaults63:15unknown tier 'frontierr'; expected one of frontier, standard, fast");
      await ui.problems.getByRole('button', {name: 'Server defaults, line 63, column 15: show in the editor', exact: true}).click();
      assert.deepEqual(await shown(), ['installation', [0, -1], ['installation']]);
      const placed = await ui.editor('installation').evaluate(node => {
        const height = parseFloat(getComputedStyle(node).lineHeight), top = 62 * height;
        return [document.activeElement === node, node.selectionStart, top >= node.scrollTop && top + height <= node.scrollTop + node.clientHeight,
          node.parentElement.querySelector('code').style.transform === `translate(${-node.scrollLeft}px, ${-node.scrollTop}px)`];
      });
      assert.deepEqual(placed, [true, DEEP.indexOf('$harness:@frontierr'), true, true]);
      // A note of the preview names positions too.
      const SHADOWED = EXAMPLE.replace('    explorer: $harness:@fast\n', '    explorer: $harness:@fast\n    reviewer/plan: claude:opus\n');
      await ui.editor('installation').fill(SHADOWED); await ui.editor('project').fill(''); await ui.state('current');
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved} · unsaved`, 'This project · revision 0']);
      const note = ui.dialog.locator('.agents-notes li');
      assert.equal(await note.textContent(), 'Note: when codex governs, defaults.roles.reviewer/plan of the server defaults (6:5) never decides: harnesses.codex.roles.reviewer of the server defaults (12:14) is found first and decides every mode of the reviewer role.');
      await note.getByRole('button', {name: 'Server defaults, line 6, column 5: show in the editor', exact: true}).click();
      assert.deepEqual([await shown(), await ui.editor('installation').evaluate(node => [document.activeElement === node, node.selectionStart])],
        [['installation', [0, -1], ['installation']], [true, SHADOWED.indexOf('reviewer/plan')]]);
      cases.push('a problem of the text that is not shown is counted on its tab, and the position of a problem or of a note shows the tab of its text with the caret at the position and the line in view');

      // One token of each kind, and text that is none of them.
      const TOKENS = `# models of this project
defaults:
  roles:
    planner: $harness:@frontier
    worker/implement: { fallback: [codex:big?effort=high, pi:provider-a/model-a] }
    explorer: { rr: [claude:haiku], first: [claude:@fast] }
    reviewer: { any: [claude:@standard, "pi:@fast"], min: 1 }
    reviewer/plan: { all: [claude:opus], min: 1 }
    worker/unknown: pi:@nowhere?effort=never
    nonsense: zzz
harnesses:
  pi:
    tiers: { fast: [provider-b/model-b?effort=low] }
`;
      await ui.editor('project').fill(TOKENS);
      const coloured = await ui.section('project').locator('.agents-highlight').evaluate((node, colouring) => {
        const {text, tokens, plain} = new Function(`return ${colouring}`)()(node);
        return {text, tokens, plain, hidden: node.getAttribute('aria-hidden'), elements: node.querySelectorAll('.agents-line *').length};
      }, String(colouring));
      assert.deepEqual([coloured.text, coloured.hidden, coloured.elements, await ui.editor('project').inputValue()], [TOKENS, 'true', 0, TOKENS]);
      for (const token of ['comment # models of this project', 'section defaults', 'section roles', 'section harnesses', 'section tiers', 'harness pi', 'harness codex', 'harness claude',
        'governing $harness', 'role planner', 'role worker', 'role reviewer', 'mode implement', 'mode plan', 'tier fast', 'tier @frontier', 'tier @standard', 'strategy fallback', 'strategy rr',
        'strategy first', 'panel any', 'panel all', 'panel min', 'provider provider-a', 'provider provider-b', 'model model-a', 'model big', 'model haiku', 'effort ?effort=high', 'effort ?effort=low',
        'number 1', 'string "pi:@fast"', 'punctuation :', 'punctuation {', 'punctuation }', 'punctuation [', 'punctuation ]', 'punctuation ,', 'punctuation /'])
        assert.ok(coloured.tokens.includes(token), `No token '${token}' in ${JSON.stringify(coloured.tokens)}`);
      assert.deepEqual(coloured.plain, ['unknown', '@nowhere?effort=never', 'nonsense', 'zzz'], 'Text that is no known token stays uncoloured');
      // Every token colour has the contrast of normal text against the background of the editor and of the help.
      const contrasts = await ui.section('project').locator('.agents-editor').evaluate((node, kinds) => {
        const channel = value => { const part = value / 255; return part <= 0.03928 ? part / 12.92 : ((part + 0.055) / 1.055) ** 2.4; };
        const luminance = colour => { const [red, green, blue] = colour.match(/[\d.]+/g).map(Number); return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue); };
        const grounds = [getComputedStyle(node).backgroundColor, getComputedStyle(node.closest('dialog')).backgroundColor].map(luminance);
        return kinds.map(kind => {
          const colour = luminance(getComputedStyle(node.querySelector('pre'), `::highlight(agents-token-${kind})`).color);
          return [kind, Math.min(...grounds.map(ground => (Math.max(colour, ground) + 0.05) / (Math.min(colour, ground) + 0.05)))];
        });
      }, [...new Set(coloured.tokens.map(token => token.split(' ')[0]))]);
      assert.equal(contrasts.length, 15, JSON.stringify(contrasts));
      assert.deepEqual(contrasts.filter(([, ratio]) => ratio < 4.5), []);
      const example = ui.dialog.locator('details.agents-help');
      await example.getByText('How to write it', {exact: true}).click();
      const kinds = await example.getByLabel('Example of server defaults', {exact: true}).evaluate((node, colouring) => {
        const {text, tokens} = new Function(`return ${colouring}`)()(node);
        return [text, [...new Set(tokens.map(token => token.split(' ')[0]))].sort(), tokens.slice(0, 6)];
      }, String(colouring));
      assert.deepEqual(kinds, [EXAMPLE, ['effort', 'governing', 'harness', 'model', 'number', 'panel', 'provider', 'punctuation', 'role', 'section', 'strategy', 'tier'],
        ['section defaults', 'punctuation :', 'section roles', 'punctuation :', 'role planner', 'punctuation :']]);
      await example.getByText('How to write it', {exact: true}).click();
      cases.push('the editor colours comments, section, harness, role, mode and tier keys, strategy and panel keywords, the parts of a model reference, the effort suffix, $harness, numbers and punctuation, leaves the rest uncoloured, and the example of the help is coloured the same way');

      // The editor is the textarea: keys typed into it arrive unchanged, with a tab, text outside ASCII and a last line break, and undo is the browser's.
      await ui.editor('project').fill('');
      assert.equal(await ui.editor('project').isEditable(), true);
      await ui.editor('project').click();
      await page.keyboard.type('defaults:\n  roles:\n    planner: ');
      await page.keyboard.insertText('\tclaude:модель-模型-🙂?effort=high # naïve\n');
      await page.keyboard.type('x');
      const TYPED = 'defaults:\n  roles:\n    planner: \tclaude:модель-模型-🙂?effort=high # naïve\nx';
      assert.deepEqual([await ui.editor('project').inputValue(), await ui.section('project').locator('.agents-highlight').textContent()], [TYPED, TYPED]);
      await page.keyboard.press('Control+z');
      const undone = await ui.editor('project').inputValue();
      assert.ok(undone.length < TYPED.length && TYPED.startsWith(undone), `Undo left ${JSON.stringify(undone)}`);
      assert.equal(await ui.section('project').locator('.agents-highlight').textContent(), undone);
      cases.push('text typed into the editor is read back from the textarea unchanged and the coloured copy has the same text, also after the browser\'s undo');

      // A text of 500 lines: a keystroke colours one line again, not the text.
      const BULK = EXAMPLE.replace('standard: [provider-a/model-a, provider-b/model-b]',
        `standard: [\n${Array.from({length: 490}, (_, index) => `        provider-a/model-${index},\n`).join('')}        provider-b/model-b]`);
      assert.ok(BULK.split('\n').length > 500);
      await ui.editor('installation').fill(EXAMPLE);
      await ui.editor('installation').evaluate(node => {
        const samples = [], changes = []; let started = 0; window.agentsTiming = {samples, changes};
        window.addEventListener('input', () => { started = performance.now(); }, true);
        // After the handlers of the dialog: the time they took, then the time with the layout they made necessary.
        node.closest('dialog').addEventListener('input', () => {
          const handled = performance.now(); node.parentElement.getBoundingClientRect(); const sample = [handled - started, performance.now() - started, 0]; samples.push(sample);
          // The rest of the frame that shows the keystroke: from its animation frame callbacks to the task after it was painted.
          requestAnimationFrame(() => { const begun = performance.now(), painted = new MessageChannel(); painted.port1.onmessage = () => { sample[2] = performance.now() - begun; }; painted.port2.postMessage(null); });
        });
        new MutationObserver(records => changes.push(records.reduce((sum, record) => sum + record.addedNodes.length, 0))).observe(node.parentElement.querySelector('code'), {childList: true});
      });
      await ui.editor('installation').fill(BULK);
      // Filling inserts the text as an edit, which the browser reports line by line: the events of the whole text are summed, then set aside.
      const whole = await ui.editor('installation').evaluate((node, offset) => {
        const {samples, changes} = window.agentsTiming, filled = {events: samples.length, milliseconds: samples.reduce((sum, sample) => sum + sample[1], 0)};
        samples.length = 0; changes.length = 0;
        node.focus(); node.setSelectionRange(offset, offset); node.scrollTop = 245 * parseFloat(getComputedStyle(node).lineHeight);
        return filled;
      }, BULK.indexOf('model-245,') + 'model-245'.length);
      const KEYS = 'abcdefghij0123456789abcdefghij0123456789';
      for (const character of KEYS) { await page.keyboard.type(character); await page.evaluate(() => new Promise(done => requestAnimationFrame(() => setTimeout(done, 0)))); }
      assert.equal(await ui.editor('installation').inputValue(), BULK.replace('model-245,', `model-245${KEYS},`));
      const timing = await page.evaluate(() => window.agentsTiming);
      const sorted = index => timing.samples.map(sample => sample[index]).sort((left, right) => left - right);
      const summary = {lines: BULK.split('\n').length, keystrokes: KEYS.length,
        wholeText: whole,
        handlersMilliseconds: {median: sorted(0)[KEYS.length / 2], worst: sorted(0)[KEYS.length - 1]},
        handlersAndLayoutMilliseconds: {median: sorted(1)[KEYS.length / 2], worst: sorted(1)[KEYS.length - 1]},
        paintMilliseconds: {median: sorted(2)[KEYS.length / 2], worst: sorted(2)[KEYS.length - 1]},
        linesReplaced: timing.changes};
      await writeFile(`${evidence}/agents-timing.json`, JSON.stringify(summary, null, 2) + '\n');
      console.log(`Agent models editor, ${summary.lines} lines: ${JSON.stringify(summary.handlersAndLayoutMilliseconds)} ms per keystroke with layout, handlers alone ${JSON.stringify(summary.handlersMilliseconds)}, the rest of the frame ${JSON.stringify(summary.paintMilliseconds)}, whole text ${whole.milliseconds.toFixed(1)} ms in ${whole.events} input events`);
      assert.equal(timing.samples.length, KEYS.length);
      assert.deepEqual(timing.changes, Array.from(KEYS, () => 1), 'A keystroke replaces the one line it changed');
      assert.ok(summary.handlersAndLayoutMilliseconds.median + summary.paintMilliseconds.median < 50, JSON.stringify(summary));
      cases.push('in a text of 500 lines a keystroke replaces the one coloured line it changed, and the frame that shows it takes less than is felt as a delay');

      await ui.editor('installation').fill(EXAMPLE); await ui.editor('project').fill(''); await ui.state('current');
      assert.deepEqual(await labels(), [`Server defaults · revision ${saved}`, 'This project · revision 0']);
      await ui.close.click();
      assert.deepEqual(await layer(other, 'installation'), [EXAMPLE, String(saved)]);
    } finally { await context.close(); }
  }

  // The coloured copy lies exactly under the text of the textarea: where the copy shows a token, the textarea has the characters of that token.
  const ALIGNED = ['defaults:', '  roles:', '\t\tplanner: $harness:@frontier # after two tabs',
    `    worker: { fallback: [${Array.from({length: 40}, () => 'claude:sonnet').join(', ')}, pi:provider-z/model-z] }`,
    '    explorer: claude:héllo-модель-模型-🙂-naïve?effort=high # 注释 ünïcödé',
    ...Array.from({length: 50}, (_, index) => `    worker/probe: pi:provider-${index}/m # 日本語 ✓ 🙂`), 'harnesses:', '  codex:', '    roles: { reviewer: $harness:@standard }', ''].join('\n');
  for (const viewport of VIEWPORTS) {
    const name = `${viewport.width}x${viewport.height}`;
    const context = await browser.newContext({viewport});
    try {
      const page = await signIn(context); page.on('pageerror', error => errors.push(String(error)));
      const ui = surface(page);
      await ui.choose(project); await ui.open(); await ui.state('current');
      const shot = async label => { const path = `${evidence}/agents-tab-${label}-${name}.png`; await ui.dialog.screenshot({path}); screenshots.push(path); };
      const frame = () => page.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))));
      const scroll = async (top, left) => { await ui.editor('project').evaluate((node, [top, left]) => { node.scrollTop = top; node.scrollLeft = left; }, [top, left]); await frame(); };
      /** The offsets and lines the textarea has at the edges of a token of the coloured copy. */
      const probe = (kind, text) => ui.section('project').locator('.agents-editor').evaluate((root, [kind, text, colouring]) => {
        const area = root.querySelector('textarea'), code = root.querySelector('code');
        const {tokens, ranges} = new Function(`return ${colouring}`)()(code), token = ranges[tokens.indexOf(`${kind} ${text}`)];
        const before = document.createRange(); before.setStart(code, 0); before.setEnd(token.startContainer, token.startOffset);
        const start = before.toString().length, box = token.getBoundingClientRect(), line = token.startContainer.parentElement.getBoundingClientRect();
        const width = box.width / text.length, middle = (line.top + line.bottom) / 2;
        const at = (x, y) => { const caret = document.caretPositionFromPoint(x, y); return caret !== null && caret.offsetNode === area ? caret.offset : null; };
        const row = offset => offset === null ? null : area.value.slice(0, offset).split('\n').length;
        const inside = area.getBoundingClientRect();
        return {start, row: row(start), rows: area.value.split('\n').length,
          visible: box.left >= inside.left && box.right <= inside.right && line.top >= inside.top && line.bottom <= inside.bottom,
          // Either side of the middle of the first and of the last character: the caret goes before or after it.
          offsets: [at(box.left + width * 0.4, middle), at(box.left + width * 0.6, middle), at(box.right - width * 0.6, middle), at(box.right - width * 0.4, middle)],
          // Just inside and just outside the top and the bottom of the line.
          lines: [row(at(box.left + 1, line.top + 1.5)), row(at(box.left + 1, line.bottom - 1.5)), row(at(box.left + 1, line.top - 1.5)), row(at(box.left + 1, line.bottom + 1.5))]};
      }, [kind, text, String(colouring)]);
      const aligned = async (kind, text, where) => {
        const found = await probe(kind, text); const end = found.start + text.length;
        assert.equal(ALIGNED.slice(found.start, end), text);
        assert.deepEqual([found.visible, found.offsets, found.lines],
          [true, [found.start, found.start + 1, end - 1, end], [found.row, found.row, Math.max(found.row - 1, 1), Math.min(found.row + 1, found.rows)]], `${name}, ${where}: ${kind} ${text}: ${JSON.stringify(found)}`);
      };
      await shot('project-saved');
      await ui.tab('installation').click(); await shot('installation');
      await ui.editor('project').fill(ALIGNED); await ui.state('invalid');
      const extent = await ui.editor('project').evaluate(node => [node.scrollWidth > node.clientWidth, node.scrollHeight > node.clientHeight, getComputedStyle(node).whiteSpace, getComputedStyle(node.parentElement.querySelector('pre')).whiteSpace]);
      assert.deepEqual(extent, [true, true, 'pre', 'pre'], 'Neither layer wraps a long line');
      await scroll(0, 0); await shot('project');
      await aligned('section', 'defaults', 'top'); await aligned('role', 'planner', 'top, after tabs'); await aligned('governing', '$harness', 'top, after tabs');
      await aligned('effort', '?effort=high', 'top, after text outside ASCII');
      // The screenshots show the selection of a token, which the textarea draws, over the glyphs of that token, which the copy draws.
      const select = text => ui.editor('project').evaluate((node, [start, end]) => node.setSelectionRange(start, end), [ALIGNED.lastIndexOf(text), ALIGNED.lastIndexOf(text) + text.length]);
      await scroll(0, 100000); await select('provider-z'); await shot('project-right');
      await aligned('provider', 'provider-z', 'end of the long line'); await aligned('model', 'model-z', 'end of the long line');
      await scroll(100000, 0); await select('@standard'); await shot('project-bottom');
      await aligned('section', 'harnesses', 'bottom'); await aligned('harness', 'codex', 'bottom'); await aligned('tier', '@standard', 'bottom');
      // The middle of the text, reached by the wheel as an operator does.
      await scroll(0, 0); await ui.editor('project').hover(); await page.mouse.wheel(0, 333);
      await ui.editor('project').evaluate(node => new Promise(done => { let last = 0; const settled = () => { if (node.scrollTop === last && last > 0) done(); else { last = node.scrollTop; setTimeout(settled, 100); } }; settled(); }));
      await frame();
      // The filler line two lines under the first one that is shown whole; the fillers start at the sixth line.
      const middle = await ui.editor('project').evaluate(node => Math.ceil(node.scrollTop / parseFloat(getComputedStyle(node).lineHeight)) + 2 - 5);
      assert.ok(middle > 5, `The wheel did not scroll the editor: filler ${middle}`);
      await aligned('provider', `provider-${middle}`, 'scrolled by the wheel');
      const layout = await ui.dialog.evaluate(node => { const body = node.querySelector('.dialog-body'); return [document.documentElement.scrollWidth > innerWidth, body.scrollWidth > body.clientWidth]; });
      assert.deepEqual(layout, [false, false], `${name}: a long line widens the page or the dialog`);
      await ui.editor('project').fill(OVERRIDE); await ui.state('current'); await ui.close.click();
    } finally { await context.close(); }
  }
  cases.push('at 1440×900 and 1280×720 the coloured copy and the textarea place the same characters in the same place: at the top, after tabs and text outside ASCII, at the end of a long line, at the bottom and after scrolling by the wheel, and a long line scrolls inside the editor');
  assert.deepEqual(errors, []);
} finally {
  const last = await stored(project);
  if (last.installation.text !== found.installation.text) await replace(project, 'Installation', last.installation.revision.value, found.installation.text);
  await writeFile(`${evidence}/agents-results.json`, JSON.stringify({cases, errors, screenshots}, null, 2) + '\n');
  await browser.close();
}
assert.equal(cases.length, 19, JSON.stringify(cases));
console.log('Chromium Agent models: two layer editors under revision comparison, server-side problems and preview, project override, unresolved role, a key of one mode and stale preview replies');
