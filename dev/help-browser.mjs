// Behavioral-Active Blackbox Good-Communication; T6 catalog-driven Help dialog: a Help button at the right end of the top header opens
// the large (90% × 90%) dialog whose Commands, Agents and Modes tabs render exactly the typed ReadSelection.Catalog response.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {build} from 'esbuild';
import {chromium} from 'playwright';

const origin = process.env.CQ_ORIGIN, evidence = process.env.CQ_BROWSER_EVIDENCE;
const headers = {Authorization: `Bearer ${process.env.CQ_TOKEN}`, 'CQ-Session': randomUUID(), 'CQ-Protocol-Version': '0.1.0', 'Content-Type': 'application/json'};
async function call(command) {
  const response = await fetch(origin + '/api/call', {method: 'POST', headers, body: JSON.stringify(command)});
  assert.equal(response.status, 200, await response.clone().text()); const result = await response.json(); assert.equal(result.Failed, undefined, JSON.stringify(result)); return result;
}
const project = {value: randomUUID()};
await call({Initialize: {config: {project, endpoint: origin, name: 'Help catalog fixture'}}});
// The typed catalog supplies aliases, prompts, schemas and tool facts; command coverage and invocation syntax are asserted separately.
const catalog = (await call({Read: {input: {project, selection: {Catalog: {}}}}})).Catalog.value;
assert.deepEqual(catalog.commands.map(command => command.command), ["begin", "advance", "review", "upstream", "drive", "park"]);
assert.ok(catalog.commands.length > 0 && catalog.agents.length > 0, 'The fixture catalog must list commands and agents');
const NAMES = {Claude: 'Claude Code', Codex: 'Codex', Pi: 'Pi'};
const label = agent => agent.mode === undefined || agent.mode === null ? agent.role : `${agent.role} · ${agent.mode}`;
const parse = text => JSON.parse(text);
const access = value => value === 'Enabled' ? 'Enabled' : value === 'Denied' ? 'Disabled (denied)' : 'Disabled (not selected)';
// The browser codec omits absent optional fields where the server writes null; both decode to the same typed value.
const normal = value => Array.isArray(value) ? value.map(normal) : value !== null && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== null && entry !== undefined).map(([key, entry]) => [key, normal(entry)])) : value;
// The token spans of a pretty-printed JSON value in document order; indentation and line breaks are text between them.
function tokens(value) {
  const punctuation = text => ['json-punctuation', text], separated = (parts, index, all) => index < all.length - 1 ? [...parts, punctuation(',')] : parts;
  if (Array.isArray(value)) return value.length === 0 ? [punctuation('[]')] : [punctuation('['), ...value.map(tokens).flatMap(separated), punctuation(']')];
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value).map(([key, entry]) => [['json-key', JSON.stringify(key)], punctuation(':'), ...tokens(entry)]);
    return entries.length === 0 ? [punctuation('{}')] : [punctuation('{'), ...entries.flatMap(separated), punctuation('}')];
  }
  return [[value === null ? 'json-null' : `json-${typeof value}`, JSON.stringify(value)]];
}
/** A JSON block of the dialog: coloured token spans whose text is exactly the two-space pretty-printed value; returns that value. */
async function coloured(block, context) {
  const shown = await block.evaluate(node => ({view: node.classList.contains('json-view'), text: node.textContent,
    stray: [...node.childNodes].filter(child => child.nodeType === Node.TEXT_NODE ? /\S/.test(child.data) : child.nodeName !== 'SPAN' || child.childElementCount > 0).length,
    tokens: [...node.querySelectorAll('span')].map(span => [span.className, span.textContent])}));
  assert.ok(shown.view, `${context}: JSON is not syntax-coloured`);
  const value = parse(shown.text);
  assert.equal(shown.text, JSON.stringify(value, null, 2), `${context}: not pretty-printed with two-space indentation`);
  assert.ok(shown.text.includes('\n  '), `${context}: no indentation`);
  assert.equal(shown.stray, 0, `${context}: text other than indentation outside the token spans`);
  assert.deepEqual(shown.tokens, tokens(value), `${context}: token spans`);
  return value;
}
const WCAG_AA_TEXT_CONTRAST = 4.5;
function luminance(colour) {
  const [red, green, blue] = colour.match(/\d+(\.\d+)?/g).slice(0, 3).map(Number).map(channel => channel / 255)
    .map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}
function contrast(first, second) { const [light, dark] = [luminance(first), luminance(second)].sort((a, b) => b - a); return (light + 0.05) / (dark + 0.05); }
const SAMPLE = {key: 'text', number: -1.5, flag: true, none: null, empty: [], nested: {deep: [1, 'two', false, {}]}};
const NOT_JSON = '{"unterminated": <b>markup</b>';

const browser = await chromium.launch({headless: true});
const results = [];
try {
  for (const {width, height, full} of [{width: 1366, height: 768, full: true}, {width: 640, height: 720, full: false}]) {
    const viewport = `${width}x${height}`;
    const context = await browser.newContext({viewport: {width, height}});
    const page = await context.newPage(); page.setDefaultTimeout(8000);
    const cases = [], errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await context.tracing.start({screenshots: true, snapshots: true, sources: true});
    const reads = [];
    page.on('websocket', socket => socket.on('framesent', frame => { if (String(frame.payload).includes('"Catalog"')) reads.push(frame.payload); }));
    try {
      await page.goto(origin);
      await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN); await page.getByRole('button', {name: 'Sign in', exact: true}).click();
      await page.getByText('Connection: ALIVE', {exact: true}).waitFor();

      const help = page.locator('header').getByRole('button', {name: 'Help', exact: true});
      const placement = await help.evaluate(node => {
        const header = node.closest('header').getBoundingClientRect(), bounds = node.getBoundingClientRect();
        const row = node.closest('.top-identity'), last = [...row.querySelectorAll('button, summary, select, input')].pop();
        const indicator = document.querySelector('.connection-indicator').getBoundingClientRect();
        return {right: header.right - bounds.right, last: last === node, top: bounds.top - header.top, rightOfIndicator: bounds.left >= indicator.right,
          overflow: document.documentElement.scrollWidth > innerWidth};
      });
      assert.ok(placement.right <= 14 && placement.last && placement.rightOfIndicator && placement.top < 20 && !placement.overflow,
        `${viewport}: Help must be the last control at the right end of the top header: ${JSON.stringify(placement)}`);
      cases.push('Help is the last control at the right end of the top header, right of the connection indicator');

      // No project is selected yet: Help still reads the catalog.
      await help.click();
      const dialog = page.getByRole('dialog', {name: 'Help', exact: true});
      const tabs = dialog.getByRole('tablist', {name: 'Help sections', exact: true});
      const commandsTab = tabs.getByRole('tab', {name: 'Commands', exact: true}), agentsTab = tabs.getByRole('tab', {name: 'Agents', exact: true});
      const commandList = dialog.getByRole('navigation', {name: 'Commands', exact: true});
      await commandList.getByRole('button').first().waitFor();
      assert.equal(await commandsTab.getAttribute('aria-selected'), 'true'); assert.equal(await agentsTab.getAttribute('aria-selected'), 'false');
      const rect = await dialog.evaluate(node => { const bounds = node.getBoundingClientRect(); return {width: bounds.width, height: bounds.height, innerWidth, innerHeight, large: node.classList.contains('large')}; });
      assert.ok(rect.large && Math.abs(rect.width - 0.9 * rect.innerWidth) <= 2 && Math.abs(rect.height - 0.9 * rect.innerHeight) <= 2, `${viewport}: Help is not 90%×90%: ${JSON.stringify(rect)}`);
      cases.push('Help opens without a selected project as the large 90%×90% dialog with Commands and Agents tabs');

      assert.deepEqual(await commandList.getByRole('button').allTextContents(), catalog.commands.map(command => `${command.command} — ${command.description}`));
      const commands = full ? catalog.commands : catalog.commands.slice(0, 1);
      for (const [index, command] of commands.entries()) {
        const entry = commandList.getByRole('button').nth(index); await entry.click();
        assert.equal(await entry.getAttribute('aria-current'), 'true');
        const detail = dialog.getByRole('article', {name: `Command ${command.command}`, exact: true}); await detail.waitFor();
        await detail.getByRole('heading', {name: `${command.command} · ${command.variant}`, exact: true}).waitFor();
        await detail.getByText(command.description, {exact: true}).waitFor();
        const aliases = detail.getByRole('table', {name: `Aliases of ${command.command}`, exact: true});
        const aliasRows = await aliases.locator('tbody tr').evaluateAll(rows => rows.map(row => [...row.cells].map(cell => cell.textContent)));
        assert.deepEqual(aliasRows, command.aliases.map(alias => [NAMES[alias.harness], alias.alias, alias.path]));
        assert.deepEqual(new Set(command.aliases.map(alias => alias.harness)), new Set(['Claude', 'Codex', 'Pi']), 'Every command has a Claude Code, Codex and Pi alias');
        for (const alias of command.aliases)
          assert.equal(await detail.getByLabel(`${NAMES[alias.harness]} alias file of ${command.command}`, {exact: true}).textContent(), alias.body);
        if (command.parameters.length === 0) await detail.getByText('Takes no arguments.', {exact: true}).waitFor();
        else {
          const rows = await detail.getByRole('table', {name: `Arguments of ${command.command}`, exact: true}).locator('tbody tr')
            .evaluateAll(rows => rows.map(row => [...row.cells].map(cell => cell.textContent)));
          const syntax = command.command === 'drive' ? ['IDS', 'through=PHASE', 'workset=UUID']
            : command.parameters.map(argument => `${argument.flag} ${argument.value}`);
          assert.deepEqual(rows, command.parameters.map((argument, index) => [argument.field, syntax[index], argument.required ? 'required' : 'optional',
            argument.summary, argument.choices.join(', '), argument.note ?? '']));
        }
        for (const prompt of [command.template, ...command.instructions])
          assert.equal(await detail.getByLabel(`Prompt ${prompt.resource}`, {exact: true}).first().textContent(), prompt.text);
      }
      cases.push(`Commands lists ${catalog.commands.length} commands with descriptions; ${commands.length} checked for harness aliases, alias files, arguments and prompts`);

      // Overflowing content scrolls inside the body while the header and Close stay in place.
      await commandList.getByRole('button').first().click();
      const firstPrompt = dialog.getByRole('article').locator('details.help-block').last();
      await firstPrompt.locator('summary').click();
      const before = await dialog.evaluate(node => { const body = node.querySelector(':scope > .dialog-body'); body.scrollTop = 0;
        return {overflow: body.scrollHeight - body.clientHeight, header: node.querySelector(':scope > .dialog-header').getBoundingClientRect().top}; });
      assert.ok(before.overflow > 50, `${viewport}: the expanded prompt must overflow the Help body (${before.overflow})`);
      const box = await dialog.locator(':scope > .dialog-body').boundingBox(); await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.wheel(0, 900);
      await page.waitForFunction(node => node.scrollTop > 0, await dialog.locator(':scope > .dialog-body').elementHandle());
      const after = await dialog.evaluate(node => ({dialogScroll: node.scrollTop, header: node.querySelector(':scope > .dialog-header').getBoundingClientRect().top,
        width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height}));
      assert.equal(after.dialogScroll, 0); assert.ok(Math.abs(after.header - before.header) <= 0.5, `${viewport}: Help header moved`);
      assert.ok(Math.abs(after.width - rect.width) <= 0.5 && Math.abs(after.height - rect.height) <= 0.5, `${viewport}: Help resized with its content`);
      assert.ok(await dialog.locator(':scope > .dialog-header').getByRole('button', {name: 'Close', exact: true}).isVisible());
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      cases.push('overflowing content scrolls inside the fixed-size body while the header and Close stay visible');
      await dialog.screenshot({path: `${evidence}/help-commands-${viewport}.png`});

      await commandsTab.focus(); await page.keyboard.press('ArrowRight');
      assert.equal(await agentsTab.getAttribute('aria-selected'), 'true'); assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Agents');
      assert.equal(await commandList.isVisible(), false);
      const agentList = dialog.getByRole('navigation', {name: 'Agents', exact: true});
      assert.deepEqual(await agentList.getByRole('button').allTextContents(), catalog.agents.map(label));
      assert.equal(new Set(catalog.agents.map(label)).size, catalog.agents.length, 'One flat entry per dispatch mode');
      const agents = full ? catalog.agents : catalog.agents.slice(-1);
      for (const agent of agents) {
        const index = catalog.agents.indexOf(agent);
        await agentList.getByRole('button').nth(index).click();
        const detail = dialog.getByRole('article', {name: `Agent ${label(agent)}`, exact: true}); await detail.waitFor();
        const view = detail.getByRole('group', {name: 'Prompt and schema view', exact: true});
        await view.getByRole('button', {name: 'Canonical', exact: true}).click();
        assert.equal(await view.getByRole('button', {name: 'Canonical', exact: true}).getAttribute('aria-pressed'), 'true');
        assert.equal(await detail.getByLabel('Canonical prompt', {exact: true}).textContent(), agent.prompt.text);
        await detail.getByText(`Prompt template · ${agent.prompt.resource}`, {exact: true}).waitFor();
        assert.deepEqual(parse(await detail.getByLabel('Canonical input schema', {exact: true}).textContent()), parse(agent.inputSchema));
        assert.deepEqual(parse(await detail.getByLabel('Canonical output schema', {exact: true}).textContent()), parse(agent.outputSchema));
        assert.deepEqual(parse(await detail.getByLabel('Workspace tool schema', {exact: true}).textContent()), parse(agent.workspaceSchema));
        assert.deepEqual(normal(parse(await detail.getByLabel('Input example', {exact: true}).textContent())), normal(agent.inputExample));
        assert.deepEqual(normal(parse(await detail.getByLabel('Output example', {exact: true}).textContent())), normal(agent.outputExample));
        for (const [name, expected] of [['Canonical input schema', agent.inputSchema], ['Canonical output schema', agent.outputSchema], ['Workspace tool schema', agent.workspaceSchema]])
          assert.deepEqual(await coloured(detail.getByLabel(name, {exact: true}), `${viewport} ${label(agent)} ${name}`), parse(expected));
        for (const [name, expected] of [['Input example', agent.inputExample], ['Output example', agent.outputExample]])
          assert.deepEqual(normal(await coloured(detail.getByLabel(name, {exact: true}), `${viewport} ${label(agent)} ${name}`)), normal(expected));
        assert.deepEqual(await view.getByRole('button').allTextContents(), ['Canonical', ...agent.harnesses.map(harness => NAMES[harness.harness])]);
        for (const harness of agent.harnesses) {
          const name = NAMES[harness.harness];
          await view.getByRole('button', {name, exact: true}).click();
          assert.equal(await view.getByRole('button', {name, exact: true}).getAttribute('aria-pressed'), 'true');
          assert.equal(await detail.getByLabel(`${name} effective prompt`, {exact: true}).textContent(), harness.prompt);
          assert.deepEqual(await coloured(detail.getByLabel(`${name} effective output schema`, {exact: true}), `${viewport} ${label(agent)} ${name} effective output schema`), parse(harness.outputSchema));
          assert.equal(await detail.getByLabel('Canonical prompt', {exact: true}).count(), 0);
          // Tools are shown for every harness whatever the prompt view.
          for (const target of agent.harnesses) {
            const tools = detail.getByRole('table', {name: `${NAMES[target.harness]} tools`, exact: true});
            const rows = await tools.locator('tbody tr').evaluateAll(rows => rows.map(row => [...row.cells].map(cell => cell.textContent)));
            assert.deepEqual(rows, [
              ...target.tools.mcp.flatMap(set => set.tools.map(tool => [tool.name, `MCP · ${set.server} (${set.target})`, access(tool.access)])),
              ...target.tools.builtin.map(tool => [tool.name, 'Built-in', access(tool.access)])]);
            assert.ok(rows.some(row => row[2] === 'Enabled'), `${label(agent)} on ${target.harness} enables some tool`);
            await detail.getByRole('region', {name: NAMES[target.harness], exact: true})
              .getByText(`Workspace edits: ${target.tools.edits ? 'allowed' : 'not allowed'} · Workspace check: ${target.tools.workspaceCheck ? 'available' : 'not available'}`, {exact: true}).waitFor();
          }
        }
        assert.ok(catalog.agents.flatMap(entry => entry.harnesses).flatMap(harness => [...harness.tools.mcp.flatMap(set => set.tools), ...harness.tools.builtin])
          .some(tool => tool.access !== 'Enabled'), 'The catalog lists disabled tools');
      }
      cases.push(`Agents lists ${catalog.agents.length} flat role-mode entries; ${agents.length} checked for canonical prompt, schemas and typed examples, per-harness effective prompt and schema, and enabled/disabled MCP and built-in tools of every harness`);
      await dialog.screenshot({path: `${evidence}/help-agents-${viewport}.png`});

      // D152: a long JSON document scrolls inside its block, copies as the JSON it shows, and its token colours are distinct and legible.
      const shownAgent = agents.at(-1), shown = dialog.getByRole('article', {name: `Agent ${label(shownAgent)}`, exact: true});
      await shown.getByRole('group', {name: 'Prompt and schema view', exact: true}).getByRole('button', {name: 'Canonical', exact: true}).click();
      await shown.getByText(`Prompt template · ${shownAgent.prompt.resource}`, {exact: true}).click();
      await shown.getByText(`Output schema · ${shownAgent.report}`, {exact: true}).click();
      const schema = shown.getByLabel('Canonical output schema', {exact: true});
      const contained = await schema.evaluate(node => {
        const range = document.createRange(); range.selectNodeContents(node);
        const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
        const selected = selection.toString(); selection.removeAllRanges();
        const style = getComputedStyle(node), dialog = node.closest('dialog').getBoundingClientRect();
        return {selected, height: node.getBoundingClientRect().height, limit: 0.6 * innerHeight, overflow: node.scrollHeight - node.clientHeight, overflowY: style.overflowY,
          userSelect: style.userSelect, dialog: {width: dialog.width, height: dialog.height}};
      });
      assert.deepEqual(parse(contained.selected), parse(shownAgent.outputSchema), `${viewport}: the selected schema text is not the catalog's JSON`);
      assert.notEqual(contained.userSelect, 'none');
      assert.ok(contained.overflow > 0 && contained.overflowY === 'auto' && contained.height <= contained.limit + 1, `${viewport}: a long schema must scroll inside its block: ${JSON.stringify({...contained, selected: undefined})}`);
      assert.ok(Math.abs(contained.dialog.width - rect.width) <= 0.5 && Math.abs(contained.dialog.height - rect.height) <= 0.5, `${viewport}: Help resized with a JSON block`);
      await shown.getByText(`Output schema · ${shownAgent.report}`, {exact: true}).click();
      await shown.getByText(`Input schema · ${shownAgent.inputType}`, {exact: true}).click();
      await shown.getByText(`Input example · ${shownAgent.inputType}`, {exact: true}).click();
      await shown.getByLabel('Canonical input schema', {exact: true}).scrollIntoViewIfNeeded();
      await dialog.screenshot({path: `${evidence}/help-json-schema-${viewport}.png`});
      await shown.getByLabel('Input example', {exact: true}).scrollIntoViewIfNeeded();
      await dialog.screenshot({path: `${evidence}/help-json-example-${viewport}.png`});

      // The shared formatter on values the catalog does not contain: every token kind, structured and textual input, and text that is not JSON.
      const formatter = await build({stdin: {contents: `export * from './web/src/json-view.js';`, resolveDir: process.cwd()}, bundle: true, format: 'iife', globalName: 'CQJson', write: false});
      // The page's content security policy admits no inline script element; the bundle is evaluated through the debugging protocol.
      await page.evaluate(formatter.outputFiles[0].text);
      const samples = await shown.evaluate((node, [sample, invalid]) => {
        const structured = CQJson.jsonView(sample), textual = CQJson.jsonTextView(JSON.stringify(sample)), plain = CQJson.jsonTextView(invalid);
        const holder = document.createElement('details'); holder.className = 'help-block'; holder.open = true; holder.id = 'json-samples';
        holder.append(document.createElement('summary'), structured, textual, plain); node.append(holder);
        let backdrop = structured; while (getComputedStyle(backdrop).backgroundColor === 'rgba(0, 0, 0, 0)') backdrop = backdrop.parentElement;
        const colour = kind => getComputedStyle(structured.querySelector(`.json-${kind}`)).color;
        return {background: getComputedStyle(backdrop).backgroundColor, plainColour: getComputedStyle(plain).color,
          colours: Object.fromEntries(['key', 'string', 'number', 'boolean', 'null', 'punctuation'].map(kind => [kind, colour(kind)])),
          plain: {text: plain.textContent, elements: plain.childElementCount, view: plain.classList.contains('json-view'), tag: plain.tagName}};
      }, [SAMPLE, NOT_JSON]);
      for (const index of [0, 1]) assert.deepEqual(await coloured(shown.locator('#json-samples pre').nth(index), `${viewport} sample ${index}`), SAMPLE);
      assert.deepEqual(samples.plain, {text: NOT_JSON, elements: 0, view: false, tag: 'PRE'}, 'Text that is not JSON is shown as it is, uncoloured');
      assert.equal(new Set(Object.values(samples.colours)).size, 6, `${viewport}: token colours are not distinct: ${JSON.stringify(samples.colours)}`);
      for (const [kind, colour] of Object.entries(samples.colours))
        assert.ok(contrast(colour, samples.background) >= WCAG_AA_TEXT_CONTRAST, `${viewport}: ${kind} ${colour} on ${samples.background} has contrast ${contrast(colour, samples.background).toFixed(2)}`);
      assert.ok(contrast(samples.colours.punctuation, samples.background) < contrast(samples.plainColour, samples.background), `${viewport}: punctuation is not subdued`);
      await shown.locator('#json-samples').screenshot({path: `${evidence}/help-json-tokens-${viewport}.png`});
      await shown.locator('#json-samples').evaluate(node => node.remove());
      cases.push('JSON schemas and examples are pretty-printed and syntax-coloured, copy as valid JSON, scroll inside their block; text that is not JSON stays plain');

      // I30: the Modes tab shows each process mode as the catalog describes it, with the section a governing session receives for it.
      const modesTab = tabs.getByRole('tab', {name: 'Modes', exact: true});
      assert.deepEqual(await tabs.getByRole('tab').allTextContents(), ['Commands', 'Agents', 'Modes']);
      await agentsTab.focus(); await page.keyboard.press('ArrowRight');
      assert.equal(await modesTab.getAttribute('aria-selected'), 'true'); assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Modes');
      await page.keyboard.press('ArrowRight'); assert.equal(await commandsTab.getAttribute('aria-selected'), 'true');
      await page.keyboard.press('End'); assert.equal(await modesTab.getAttribute('aria-selected'), 'true');
      const modeList = dialog.getByRole('navigation', {name: 'Modes', exact: true});
      assert.deepEqual(catalog.modes.map(mode => mode.mode), ['Rigorous', 'CrossCutting', 'Yolo']);
      assert.deepEqual(await modeList.getByRole('button').allTextContents(), catalog.modes.map(mode => mode.label));
      for (const [index, mode] of catalog.modes.entries()) {
        const entry = modeList.getByRole('button').nth(index); await entry.click();
        assert.equal(await entry.getAttribute('aria-current'), 'true');
        const detail = dialog.getByRole('article', {name: `Mode ${mode.label}`, exact: true}); await detail.waitFor();
        await detail.getByRole('heading', {name: mode.label, exact: true}).waitFor();
        await detail.getByText(mode.hint, {exact: true}).waitFor(); for (const paragraph of mode.description.split('\n\n')) await detail.getByText(paragraph, {exact: true}).waitFor();
        await detail.getByText(catalog.modeEffect, {exact: true}).waitFor();
        assert.equal(await detail.locator('.mode-note').count(), mode.unavailable === null || mode.unavailable === undefined ? 0 : 1, mode.label);
        if (mode.unavailable !== null && mode.unavailable !== undefined) assert.equal(await detail.locator('.mode-note').textContent(), mode.unavailable);
        const instructions = detail.getByLabel(`Prompt ${mode.instructions.resource}`, {exact: true});
        assert.equal(await instructions.textContent(), mode.instructions.text);
        assert.ok(mode.instructions.text.startsWith(`Process mode of this project: ${mode.label}.`), mode.label);
        assert.ok(await instructions.isVisible(), `${viewport}: the instructions of ${mode.label} are collapsed`);
      }
      // A release either withholds the YOLO mode, with a note, or delivers it; no other mode is ever withheld.
      assert.ok(catalog.modes.filter(mode => mode.unavailable !== null && mode.unavailable !== undefined).every(mode => mode.mode === 'Yolo'));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await modeList.getByRole('button').nth(1).click(); await dialog.getByRole('article', {name: `Mode ${catalog.modes[1].label}`, exact: true}).waitFor();
      await dialog.screenshot({path: `${evidence}/help-modes-${viewport}.png`});
      cases.push('the Modes tab lists the three process modes with the catalog hint, description, availability note and governing instructions of each');
      await agentsTab.click(); await dialog.getByRole('article', {name: `Agent ${label(agents.at(-1))}`, exact: true}).waitFor();

      await page.keyboard.press('Escape'); await dialog.waitFor({state: 'hidden'});
      assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Help', 'Closing returns focus to the Help button');
      await page.getByLabel('Project', {exact: true}).selectOption(project.value); await page.getByText('Data: current', {exact: true}).waitFor();
      const sent = reads.length;
      await help.click(); await dialog.getByRole('article', {name: `Agent ${label(agents.at(-1))}`, exact: true}).waitFor();
      assert.equal(reads.length, sent, 'Reopening reuses the catalog that was read');
      await dialog.getByRole('button', {name: 'Close', exact: true}).click(); await dialog.waitFor({state: 'hidden'});
      assert.equal(sent, 1, `Exactly one catalog read: ${sent}`);
      cases.push('Escape closes Help and returns focus; reopening keeps the last view and does not read the catalog again');
      assert.deepEqual(errors, []);
    } finally {
      results.push({viewport, cases, errors});
      await context.tracing.stop({path: `${evidence}/help-${viewport}.zip`}); await context.close();
    }
  }
} finally {
  await writeFile(`${evidence}/help-results.json`, JSON.stringify(results, null, 2) + '\n');
  await browser.close();
}
console.log('Chromium Help: catalog-driven Commands, Agents and Modes tabs in the large dialog');
