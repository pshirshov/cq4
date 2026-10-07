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
  return {
    dialog, section, preview,
    editor: name => dialog.getByRole('textbox', {name: name === 'installation' ? 'Server defaults' : 'This project', exact: true}),
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
    await ui.section('project').getByText('No text has been saved for this project: it inherits the server defaults.', {exact: true}).waitFor();
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
  assert.deepEqual(errors, []);
} finally {
  const last = await stored(project);
  if (last.installation.text !== found.installation.text) await replace(project, 'Installation', last.installation.revision.value, found.installation.text);
  await writeFile(`${evidence}/agents-results.json`, JSON.stringify({cases, errors, screenshots}, null, 2) + '\n');
  await browser.close();
}
assert.equal(cases.length, 12, JSON.stringify(cases));
console.log('Chromium Agent models: two layer editors under revision comparison, server-side problems and preview, project override, unresolved role, a key of one mode and stale preview replies');
