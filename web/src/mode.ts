import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { faultMessage } from './faults.js';
import { holdButton } from './hold-button.js';

interface ModeEffects {
  call(command: api.Command): Promise<api.Result>;
  catalog(project: api.ProjectId): Promise<api.HelpCatalog>;
  // `changed` is false when the saved mode equals the stored one: the server then keeps the revision.
  saved(value: api.ProjectMode, changed: boolean): void;
}

function entry(catalog: api.HelpCatalog, mode: api.ProcessMode): api.CatalogMode {
  const found = catalog.modes.find(candidate => candidate.mode === mode);
  if (found === undefined) throw new Error(`The catalog does not describe the process mode ${mode}`);
  return found;
}
function provenance(value: api.ProjectMode, catalog: api.HelpCatalog): string {
  return value.change === undefined ? `No process mode has been saved for this project: it is ${entry(catalog, value.mode).label}.`
    : `Revision ${value.revision.value} · ${value.change.actor.subject} · ${new Date(Number(value.change.at)).toLocaleString()}`;
}
// The catalog carries no field for the exemption, so it states it as the last paragraph of the YOLO mode's description.
function exemptionHint(catalog: api.HelpCatalog): string {
  const paragraphs = entry(catalog, api.ProcessMode.Yolo).description.split('\n\n');
  if (paragraphs.length < 2) throw new Error('The catalog does not describe self-review without checks');
  return paragraphs[paragraphs.length - 1];
}
async function read(effects: ModeEffects, project: api.ProjectId): Promise<api.ProjectMode> {
  const result = await effects.call(new api.Command_Mode(new api.ModeInput(project, new api.ModeAction_Read())));
  if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
  if (!(result instanceof api.Result_Mode)) throw new Error('Unexpected process mode response');
  return result.value;
}

/**
 * The project's process mode in the header: always visible, and distinct for every mode other than the default
 * (`data-mode` in style.css). It opens the mode dialog. Labels and hints come from the typed catalog.
 */
export class ModeIndicator {
  private generation = 0;
  readonly element: HTMLButtonElement;
  constructor(private readonly effects: ModeEffects, open: () => void) {
    this.element = button('', open); this.element.className = 'mode-indicator'; this.element.setAttribute('aria-haspopup', 'dialog'); this.element.hidden = true;
  }
  show(value: api.ProjectMode, catalog: api.HelpCatalog): void {
    const described = entry(catalog, value.mode);
    this.generation++; this.element.textContent = `Mode: ${described.label}`; this.element.dataset.mode = value.mode;
    this.element.title = described.hint; this.element.setAttribute('aria-label', `Process mode: ${described.label}`); this.element.hidden = false;
  }
  /** Reads the mode of `project`; no other browser's change is pushed to this page, so it is read when the project is loaded. */
  async load(project: api.ProjectId | null): Promise<void> {
    const generation = ++this.generation; this.element.hidden = true;
    if (project === null) return;
    const [value, catalog] = await Promise.all([read(this.effects, project), this.effects.catalog(project)]);
    if (generation === this.generation) this.show(value, catalog);
  }
}

/** Chooses the project's process mode. Every label, hint and note is the catalog's; the dialog holds no description of a mode. */
export class ModeDialog {
  private generation = 0;
  private readonly dialog = new Dialog('standard', () => { this.generation++; });
  readonly element = this.dialog.element;
  private readonly effect = element('p', '');
  private readonly options = element('fieldset', '');
  private readonly metadata = element('p', '');
  private readonly conflict = element('section', '');
  private readonly controls = element('div', '');
  private readonly save = button('Save mode', () => this.action(() => this.submit(this.chosen, this.chosen === api.ProcessMode.Yolo && this.base?.selfReviewWithoutChecks === true)));
  // A mode whose change needs a deliberate press is saved by holding; the catalog decides which modes can be chosen at all.
  private readonly confirm = holdButton('Switch to YOLO cross-cutting', () => this.action(() => this.submit(api.ProcessMode.Yolo, false)));
  // The exemption of a YOLO project from the rule that a self-reviewed integration needs a configured check. It is a separate
  // decision with its own deliberate press, offered once the project is in that mode; withdrawing it tightens the process and is an ordinary press.
  private readonly exemption = element('fieldset', '');
  private readonly exemptionHint = element('p', '');
  private readonly exemptionState = element('p', '');
  private readonly allow = holdButton('Allow self-review without checks', () => this.action(() => this.submit(api.ProcessMode.Yolo, true)));
  private readonly withdraw = button('Require a check again', () => this.action(() => this.submit(api.ProcessMode.Yolo, false)));
  // The revision the choice is based on and the catalog it is rendered from, null while they are loading.
  private base: api.ProjectMode | null = null;
  private catalog: api.HelpCatalog | null = null;
  private chosen: api.ProcessMode | null = null;
  private busy = false;

  constructor(private readonly effects: ModeEffects) {
    this.options.className = 'mode-options'; this.metadata.className = 'revision-meta'; this.conflict.hidden = true; this.controls.className = 'mode-actions';
    this.controls.append(this.save, this.confirm);
    this.exemption.className = 'mode-exemption'; this.exemptionHint.className = 'mode-hint'; this.exemptionHint.id = 'mode-exemption-hint';
    this.exemptionState.className = 'mode-exemption-state'; this.exemptionState.id = 'mode-exemption-state';
    for (const control of [this.allow, this.withdraw]) control.setAttribute('aria-describedby', `${this.exemptionHint.id} ${this.exemptionState.id}`);
    this.exemption.append(element('legend', 'Self-review without checks'), this.exemptionHint, this.exemptionState, this.allow, this.withdraw);
    const stale = element('p', 'A change made in another browser is shown here when the project is loaded again or this dialog is opened.'); stale.className = 'revision-meta';
    this.dialog.body.append(this.effect, this.options, this.metadata, this.controls, this.exemption, this.conflict, stale);
    this.enable();
  }
  private action(effect: () => Promise<void>): void {
    const generation = this.generation;
    void effect().catch(error => {
      if (generation === this.generation) { this.dialog.error.textContent = String(error); this.dialog.error.hidden = false; }
    });
  }
  private adopt(value: api.ProjectMode, catalog: api.HelpCatalog): void {
    this.base = value; this.catalog = catalog; this.metadata.textContent = provenance(value, catalog); this.conflict.hidden = true; this.conflict.replaceChildren();
  }
  private enable(): void {
    const ready = this.base !== null && !this.busy;
    const guarded = this.chosen === api.ProcessMode.Yolo && this.base?.mode !== api.ProcessMode.Yolo;
    this.save.hidden = guarded; this.confirm.hidden = !guarded; this.save.disabled = !ready; this.confirm.disabled = !ready;
    const stored = this.base?.mode === api.ProcessMode.Yolo; const allowed = stored && this.base?.selfReviewWithoutChecks === true;
    this.exemption.hidden = this.chosen !== api.ProcessMode.Yolo || this.base === null;
    this.allow.hidden = allowed; this.withdraw.hidden = !allowed; this.allow.disabled = !ready || !stored; this.withdraw.disabled = !ready;
    this.exemption.dataset.allowed = String(allowed);
    this.exemptionState.textContent = allowed ? 'Allowed for this project.'
      : stored ? 'Not allowed for this project.' : 'Not allowed. It can be allowed once the project is in this mode.';
  }
  private render(catalog: api.HelpCatalog): void {
    const legend = element('legend', 'Process mode');
    const choices = catalog.modes.map((mode, index) => {
      const row = element('div', ''); row.className = 'mode-option';
      const label = element('label', ''); const input = element('input', ''); input.type = 'radio'; input.name = 'process-mode'; input.value = mode.mode;
      const hint = element('p', mode.hint); hint.id = `mode-hint-${index}`; hint.className = 'mode-hint';
      input.checked = mode.mode === this.chosen; input.disabled = mode.unavailable !== undefined && mode.mode !== this.chosen;
      input.setAttribute('aria-describedby', hint.id);
      input.addEventListener('change', () => { if (input.checked) { this.chosen = mode.mode; this.enable(); } });
      label.append(input, element('span', mode.label)); row.append(label, hint);
      if (mode.unavailable !== undefined) {
        const note = element('p', mode.unavailable); note.id = `mode-note-${index}`; note.className = 'mode-note';
        input.setAttribute('aria-describedby', `${hint.id} ${note.id}`); row.append(note);
      }
      return row;
    });
    this.options.replaceChildren(legend, ...choices); this.effect.textContent = catalog.modeEffect;
    this.exemptionHint.textContent = exemptionHint(catalog);
  }
  open(project: api.ProjectId): void {
    this.dialog.open('Process mode');
    this.base = null; this.chosen = null; this.options.replaceChildren(); this.effect.textContent = ''; this.metadata.textContent = 'Loading…';
    this.conflict.hidden = true; this.conflict.replaceChildren(); this.enable();
    const generation = this.generation;
    this.action(async () => {
      const [value, catalog] = await Promise.all([read(this.effects, project), this.effects.catalog(project)]);
      if (generation !== this.generation) return;
      this.adopt(value, catalog); this.chosen = value.mode; this.render(catalog); this.enable();
    });
  }
  // The exemption belongs to the YOLO mode only: a change to another mode stores none.
  private async submit(chosen: api.ProcessMode | null, selfReviewWithoutChecks: boolean): Promise<void> {
    const base = this.base; const catalog = this.catalog;
    if (base === null || catalog === null || chosen === null || this.busy) return;
    const generation = this.generation;
    this.dialog.error.hidden = true; this.busy = true; this.enable();
    try {
      const result = await this.effects.call(new api.Command_Mode(new api.ModeInput(base.project,
        new api.ModeAction_Replace(base.revision, chosen, selfReviewWithoutChecks))));
      if (result instanceof api.Result_Mode) {
        // A reply that arrives after the dialog was reopened for a fresh load must not become its base.
        if (this.base === base) { this.adopt(result.value, catalog); this.chosen = result.value.mode; this.render(catalog); }
        this.effects.saved(result.value, result.value.revision.value !== base.revision.value);
      } else if (result instanceof api.Result_Failed && result.fault instanceof api.Fault_Conflict) {
        const current = await read(this.effects, base.project);
        if (generation === this.generation && this.base === base) this.showConflict(base, current, catalog);
      } else if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      else throw new Error('Unexpected process mode response');
    } finally { this.busy = false; this.enable(); }
  }
  private showConflict(base: api.ProjectMode, current: api.ProjectMode, catalog: api.HelpCatalog): void {
    this.conflict.replaceChildren(
      element('h3', 'Edit conflict'),
      element('p', `Your choice is based on revision ${base.revision.value}; the current revision is ${current.revision.value}, and the project is in the ${entry(catalog, current.mode).label} mode. ${provenance(current, catalog)}`),
      element('p', 'Changing the base keeps your choice; a later save replaces the current mode.'),
      button('Use current revision as base', () => { if (this.base === base) { this.adopt(current, catalog); this.enable(); } }));
    this.conflict.hidden = false;
  }
}
