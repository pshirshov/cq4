import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { faultMessage } from './faults.js';

const CONTEXT = BaboonCodecContext.Default;

interface HelpEffects { call(command: api.Command): Promise<api.Result> }

type Tab = 'commands' | 'agents';
/** The prompt and schema view of an agent: the canonical form or the effective form one harness receives (Q8). */
type View = 'canonical' | api.Harness;

// Display names of the generated Harness enumeration; every harness, alias, prompt and tool shown comes from the typed catalog.
const HARNESS_NAMES: Record<api.Harness, string> = { [api.Harness.Claude]: 'Claude Code', [api.Harness.Codex]: 'Codex', [api.Harness.Pi]: 'Pi' };
export function harnessName(harness: api.Harness): string { return HARNESS_NAMES[harness]; }
/** Flat role-mode label of a dispatch agent (Q7), for example "Worker · Implement" or "Planner". */
export function agentLabel(agent: api.CatalogAgent): string { return agent.mode === undefined ? agent.role : `${agent.role} · ${agent.mode}`; }

function pretty(text: string): string {
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
}
function block(title: string, text: string, label: string, open = false): HTMLDetailsElement {
  const details = element('details', ''); details.className = 'help-block'; details.open = open;
  const summary = element('summary', title); const content = element('pre', text); content.setAttribute('aria-label', label); content.tabIndex = 0;
  details.append(summary, content); return details;
}
function table(label: string, headings: string[], rows: (string | Node)[][]): HTMLTableElement {
  const node = element('table', ''); node.className = 'help-table'; node.setAttribute('aria-label', label);
  const head = element('thead', ''); const line = element('tr', '');
  for (const heading of headings) { const cell = element('th', heading); cell.scope = 'col'; line.append(cell); }
  head.append(line); const body = element('tbody', '');
  for (const row of rows) {
    const line = element('tr', '');
    row.forEach((value, index) => {
      const cell = element(index === 0 ? 'th' : 'td', ''); if (cell instanceof HTMLTableCellElement && index === 0) cell.scope = 'row';
      cell.append(value); line.append(cell);
    });
    body.append(line);
  }
  node.append(head, body); return node;
}
function section(title: string, ...content: (string | Node)[]): HTMLElement {
  const node = element('section', ''); node.className = 'help-section';
  const heading = element('h3', title); const id = `help-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  heading.id = id; node.setAttribute('aria-labelledby', id); node.append(heading, ...content); return node;
}

/**
 * The Help dialog: the large dialog variant with a Commands and an Agents tab, rendered only from the typed `ReadSelection.Catalog`
 * response. The web client holds no command, alias, argument, prompt, schema, example or tool facts of its own.
 */
export class HelpDialog {
  readonly dialog = new Dialog('large', () => { this.generation++; });
  readonly element = this.dialog.element;
  private generation = 0;
  private catalog: api.HelpCatalog | null = null;
  private loading: Promise<void> | null = null;
  private tab: Tab = 'commands';
  private command = 0;
  private agent = 0;
  private view: View = 'canonical';
  private readonly tabs = new Map<Tab, HTMLButtonElement>();
  private readonly panels = new Map<Tab, HTMLElement>();
  private readonly status = element('p', '');

  constructor(private readonly effects: HelpEffects) {
    const list = element('div', ''); list.className = 'help-tabs'; list.setAttribute('role', 'tablist'); list.setAttribute('aria-label', 'Help sections');
    for (const [tab, title] of [['commands', 'Commands'], ['agents', 'Agents']] as const) {
      const control = button(title, () => this.select(tab, false)); control.id = `help-tab-${tab}`;
      control.setAttribute('role', 'tab'); control.setAttribute('aria-controls', `help-panel-${tab}`);
      const panel = element('div', ''); panel.id = `help-panel-${tab}`; panel.className = 'help-panel';
      panel.setAttribute('role', 'tabpanel'); panel.setAttribute('aria-labelledby', control.id);
      this.tabs.set(tab, control); this.panels.set(tab, panel); list.append(control);
    }
    list.addEventListener('keydown', event => {
      const order: Tab[] = ['commands', 'agents']; const index = order.indexOf(this.tab);
      const next = event.key === 'ArrowRight' ? order[(index + 1) % order.length] : event.key === 'ArrowLeft' ? order[(index + order.length - 1) % order.length]
        : event.key === 'Home' ? order[0] : event.key === 'End' ? order[order.length - 1] : null;
      if (next !== null) { event.preventDefault(); this.select(next, true); }
    });
    this.status.setAttribute('role', 'status'); this.status.className = 'revision-meta';
    this.dialog.body.classList.add('help-body');
    this.dialog.body.append(list, this.status, ...this.panels.values());
    this.select('commands', false);
  }

  /** Opens the dialog and reads the catalog once; a failed read is retried on the next opening. */
  open(project: api.ProjectId): void {
    this.dialog.open('Help');
    if (this.catalog !== null) { this.render(); return; }
    const generation = this.generation;
    this.status.textContent = 'Loading catalog…'; this.status.hidden = false;
    for (const panel of this.panels.values()) panel.replaceChildren();
    this.loading ??= this.read(project).finally(() => { this.loading = null; });
    void this.loading.then(() => { if (generation === this.generation) this.render(); }, error => {
      if (generation !== this.generation) return;
      this.status.textContent = 'The catalog could not be read.'; this.dialog.error.textContent = String(error); this.dialog.error.hidden = false;
    });
  }
  private async read(project: api.ProjectId): Promise<void> {
    const result = await this.effects.call(new api.Command_Read(new api.ReadInput(project, new api.ReadSelection_Catalog())));
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    if (!(result instanceof api.Result_Catalog)) throw new Error('Unexpected help catalog response');
    this.catalog = result.value;
  }
  private select(tab: Tab, focus: boolean): void {
    this.tab = tab;
    for (const [key, control] of this.tabs) {
      const selected = key === tab; control.setAttribute('aria-selected', String(selected)); control.tabIndex = selected ? 0 : -1;
      const panel = this.panels.get(key); if (panel !== undefined) panel.hidden = !selected;
    }
    if (focus) this.tabs.get(tab)?.focus();
  }
  private render(): void {
    const catalog = this.catalog; if (catalog === null) return;
    this.status.textContent = ''; this.status.hidden = true;
    this.renderCommands(catalog); this.renderAgents(catalog);
  }
  /** A master list of entries beside the detail of the chosen one; the list keeps its place while the detail scrolls with the body. */
  private layout(tab: Tab, label: string, names: string[], current: number, choose: (index: number) => void, detail: HTMLElement): void {
    const panel = this.panels.get(tab); if (panel === undefined) return;
    const list = element('nav', ''); list.className = 'help-list'; list.setAttribute('aria-label', label);
    names.forEach((name, index) => {
      const entry = button(name, () => choose(index)); entry.className = 'navigation-entry';
      if (index === current) entry.setAttribute('aria-current', 'true');
      list.append(entry);
    });
    detail.className = 'help-detail';
    const layout = element('div', ''); layout.className = 'help-layout'; layout.append(list, detail);
    panel.replaceChildren(layout);
  }
  private refocus(tab: Tab, label: string): void {
    this.panels.get(tab)?.querySelector<HTMLButtonElement>(`nav[aria-label="${label}"] [aria-current=true]`)?.focus();
  }

  private renderCommands(catalog: api.HelpCatalog): void {
    const command = catalog.commands[Math.min(this.command, catalog.commands.length - 1)];
    const detail = element('article', '');
    if (command !== undefined) {
      detail.setAttribute('aria-label', `Command ${command.command}`);
      const aliases = table(`Aliases of ${command.command}`, ['Harness', 'Alias', 'Installed file'],
        command.aliases.map(alias => [harnessName(alias.harness), alias.alias, alias.path]));
      const arguments_ = command.parameters.length === 0 ? element('p', 'Takes no arguments.')
        : table(`Arguments of ${command.command}`, ['Field', 'Option', 'Required', 'Description', 'Choices', 'Note'],
          command.parameters.map(argument => [argument.field, `${argument.flag} ${argument.value}`, argument.required ? 'required' : 'optional',
            argument.summary, argument.choices.join(', '), argument.note ?? '']));
      detail.append(
        element('h2', `${command.command} · ${command.variant}`), element('p', command.description),
        section('Aliases', aliases, ...command.aliases.map(alias => block(`${harnessName(alias.harness)} · ${alias.path}`, alias.body,
          `${harnessName(alias.harness)} alias file of ${command.command}`))),
        section('Arguments', arguments_),
        section('Prompts', block(`Template · ${command.template.resource}`, command.template.text, `Prompt ${command.template.resource}`),
          ...command.instructions.map(prompt => block(`Instructions · ${prompt.resource}`, prompt.text, `Prompt ${prompt.resource}`))));
    } else detail.append(element('p', 'The catalog lists no commands.'));
    this.layout('commands', 'Commands', catalog.commands.map(entry => `${entry.command} — ${entry.description}`), this.command, index => {
      this.command = index; this.renderCommands(catalog); this.refocus('commands', 'Commands');
    }, detail);
  }

  private renderAgents(catalog: api.HelpCatalog): void {
    const agent = catalog.agents[Math.min(this.agent, catalog.agents.length - 1)];
    const detail = element('article', '');
    if (agent !== undefined) {
      const label = agentLabel(agent);
      detail.setAttribute('aria-label', `Agent ${label}`);
      const facts = element('dl', ''); facts.className = 'help-facts';
      for (const [term, value] of [['Role', agent.role], ['Mode', agent.mode ?? '—'], ['Input', agent.inputType], ['Report', agent.report]] as const)
        facts.append(element('dt', term), element('dd', value));
      detail.append(element('h2', label), facts, this.promptView(agent), this.examples(agent), this.tools(agent));
    } else detail.append(element('p', 'The catalog lists no agents.'));
    this.layout('agents', 'Agents', catalog.agents.map(agentLabel), this.agent, index => {
      this.agent = index; this.renderAgents(catalog); this.refocus('agents', 'Agents');
    }, detail);
  }
  /** Canonical prompt template and schemas, or the effective prompt and output schema of the chosen harness (Q8). */
  private promptView(agent: api.CatalogAgent): HTMLElement {
    const toggle = element('div', ''); toggle.className = 'help-toggle'; toggle.setAttribute('role', 'group'); toggle.setAttribute('aria-label', 'Prompt and schema view');
    const views: View[] = ['canonical', ...agent.harnesses.map(harness => harness.harness)];
    if (!views.includes(this.view)) this.view = 'canonical';
    for (const view of views) {
      const control = button(view === 'canonical' ? 'Canonical' : harnessName(view), () => {
        this.view = view; const catalog = this.catalog; if (catalog === null) return;
        this.renderAgents(catalog); this.panels.get('agents')?.querySelector<HTMLButtonElement>('[aria-label="Prompt and schema view"] [aria-pressed=true]')?.focus();
      });
      control.setAttribute('aria-pressed', String(view === this.view)); toggle.append(control);
    }
    const harness = this.view === 'canonical' ? undefined : agent.harnesses.find(entry => entry.harness === this.view);
    const name = harness === undefined ? 'canonical' : harnessName(harness.harness);
    const content: Node[] = harness === undefined ? [
      block(`Prompt template · ${agent.prompt.resource}`, agent.prompt.text, 'Canonical prompt', true),
      block(`Input schema · ${agent.inputType}`, pretty(agent.inputSchema), 'Canonical input schema'),
      block(`Output schema · ${agent.report}`, pretty(agent.outputSchema), 'Canonical output schema'),
      block('Workspace tool schema', pretty(agent.workspaceSchema), 'Workspace tool schema'),
    ] : [
      element('p', `${name} receives this system prompt and output schema; its input is the canonical ${agent.inputType}.`),
      block(`Effective prompt · ${name}`, harness.prompt, `${name} effective prompt`, true),
      block(`Effective output schema · ${name}`, pretty(harness.outputSchema), `${name} effective output schema`),
      block(`Input schema · ${agent.inputType}`, pretty(agent.inputSchema), 'Canonical input schema'),
    ];
    return section('Prompts and schemas', toggle, ...content);
  }
  private examples(agent: api.CatalogAgent): HTMLElement {
    const input = JSON.stringify(api.ChildExecutionInput_JsonCodec.instance.encode(CONTEXT, agent.inputExample), null, 2);
    const output = JSON.stringify(api.ChildReport_JsonCodec.instance.encode(CONTEXT, agent.outputExample), null, 2);
    return section('Examples', block(`Input example · ${agent.inputType}`, input, 'Input example'), block(`Output example · ${agent.report}`, output, 'Output example'));
  }
  /** Effective tools of every harness: MCP tools by server and built-in tools, each enabled or disabled (unselected or denied). */
  private tools(agent: api.CatalogAgent): HTMLElement {
    const access = (value: api.CatalogToolAccess): string => value === api.CatalogToolAccess.Enabled ? 'Enabled'
      : value === api.CatalogToolAccess.Denied ? 'Disabled (denied)' : 'Disabled (not selected)';
    const harnesses = agent.harnesses.map(harness => {
      const name = harnessName(harness.harness);
      const rows = [
        ...harness.tools.mcp.flatMap(set => set.tools.map(tool => [tool.name, `MCP · ${set.server} (${set.target})`, access(tool.access)])),
        ...harness.tools.builtin.map(tool => [tool.name, 'Built-in', access(tool.access)]),
      ];
      const node = element('section', ''); node.className = 'help-harness-tools';
      const heading = element('h4', name); heading.id = `help-tools-${harness.harness}`; node.setAttribute('aria-labelledby', heading.id);
      node.append(heading,
        element('p', `Workspace edits: ${harness.tools.edits ? 'allowed' : 'not allowed'} · Workspace check: ${harness.tools.workspaceCheck ? 'available' : 'not available'}`),
        table(`${name} tools`, ['Tool', 'Kind', 'Access'], rows));
      return node;
    });
    return section('Tools by harness', ...harnesses);
  }
}
