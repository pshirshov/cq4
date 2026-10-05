// Behavioral-Active Blackbox Good-Communication; T6 catalog-driven Help dialog: a Help button at the right end of the top header opens
// the large (90% × 90%) dialog whose Commands and Agents tabs render exactly the typed ReadSelection.Catalog response.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
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
const catalog = (await call({Read: {input: {project, selection: {Catalog: {part: {All: {}}}}}}})).Catalog.value;
assert.deepEqual(catalog.commands.map(command => command.command), ["begin", "advance", "review", "upstream", "drive", "park"]);
assert.ok(catalog.commands.length > 0 && catalog.agents.length > 0, 'The fixture catalog must list commands and agents');
const NAMES = {Claude: 'Claude Code', Codex: 'Codex', Pi: 'Pi'};
const label = agent => agent.mode === undefined || agent.mode === null ? agent.role : `${agent.role} · ${agent.mode}`;
const parse = text => JSON.parse(text);
const access = value => value === 'Enabled' ? 'Enabled' : value === 'Denied' ? 'Disabled (denied)' : 'Disabled (not selected)';
// The browser codec omits absent optional fields where the server writes null; both decode to the same typed value.
const normal = value => Array.isArray(value) ? value.map(normal) : value !== null && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).filter(([, entry]) => entry !== null && entry !== undefined).map(([key, entry]) => [key, normal(entry)])) : value;

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
        assert.deepEqual(await view.getByRole('button').allTextContents(), ['Canonical', ...agent.harnesses.map(harness => NAMES[harness.harness])]);
        for (const harness of agent.harnesses) {
          const name = NAMES[harness.harness];
          await view.getByRole('button', {name, exact: true}).click();
          assert.equal(await view.getByRole('button', {name, exact: true}).getAttribute('aria-pressed'), 'true');
          assert.equal(await detail.getByLabel(`${name} effective prompt`, {exact: true}).textContent(), harness.prompt);
          assert.deepEqual(parse(await detail.getByLabel(`${name} effective output schema`, {exact: true}).textContent()), parse(harness.outputSchema));
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
console.log('Chromium Help: catalog-driven Commands and Agents tabs in the large dialog');
