import * as api from '../../generated/typescript/cq/api/index.js';
import { button, element } from './editor.js';
import { Dialog } from './dialog.js';
import { faultMessage } from './faults.js';

interface RequirementsEffects {
  call(command: api.Command): Promise<api.Result>;
  saved(value: api.ProjectRequirements): void;
}

function provenance(value: api.ProjectRequirements): string {
  return value.change === undefined ? 'No standing requirements have been saved for this project.'
    : `Revision ${value.revision.value} · ${value.change.actor.subject} · ${new Date(Number(value.change.at)).toLocaleString()}`;
}

/** Edits the project's standing requirements, the text the host delivers to every Planner, Worker and reviewer of every session. */
export class RequirementsDialog {
  private generation = 0;
  private readonly dialog = new Dialog('standard', () => { this.generation++; });
  readonly element = this.dialog.element;
  private readonly text = element('textarea', '');
  private readonly metadata = element('p', '');
  private readonly conflict = element('section', '');
  private readonly save = button('Save requirements', () => this.action(() => this.submit()));
  // The revision the text in the editor was started from; a dirty text survives closing and reopening the dialog.
  private base: api.ProjectRequirements | null = null;
  private busy = false;

  constructor(private readonly effects: RequirementsEffects) {
    this.text.setAttribute('aria-label', 'Standing requirements'); this.text.rows = 16;
    this.metadata.className = 'revision-meta'; this.conflict.hidden = true;
    this.dialog.body.append(
      element('p', 'The host delivers this text to every Planner, Worker and reviewer of every session of this project, beside the session\'s own request. An empty text means none.'),
      this.text, this.metadata, this.save, this.conflict);
  }
  private action(effect: () => Promise<void>): void {
    const generation = this.generation;
    void effect().catch(error => {
      if (generation === this.generation) { this.dialog.error.textContent = String(error); this.dialog.error.hidden = false; }
    });
  }
  private async read(project: api.ProjectId): Promise<api.ProjectRequirements> {
    const result = await this.effects.call(new api.Command_Requirements(new api.RequirementsInput(project, new api.RequirementsAction_Read())));
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    if (!(result instanceof api.Result_Requirements)) throw new Error('Unexpected standing requirements response');
    return result.value;
  }
  private adopt(value: api.ProjectRequirements): void {
    this.base = value; this.metadata.textContent = provenance(value); this.conflict.hidden = true; this.conflict.replaceChildren();
  }
  open(project: api.ProjectId): void {
    const dirty = this.base !== null && this.base.project.value === project.value && this.text.value !== this.base.text;
    this.dialog.open('Standing requirements');
    if (dirty) return;
    this.base = null; this.text.value = ''; this.text.disabled = true; this.save.disabled = true; this.metadata.textContent = 'Loading…';
    this.conflict.hidden = true; this.conflict.replaceChildren();
    const generation = this.generation;
    this.action(async () => {
      const value = await this.read(project);
      if (generation !== this.generation) return;
      this.adopt(value); this.text.value = value.text; this.text.disabled = false; this.save.disabled = false;
    });
  }
  private async submit(): Promise<void> {
    const base = this.base; if (base === null || this.busy) return;
    const generation = this.generation;
    this.dialog.error.hidden = true; this.busy = true; this.save.disabled = true;
    try {
      const result = await this.effects.call(new api.Command_Requirements(new api.RequirementsInput(base.project,
        new api.RequirementsAction_Replace(base.revision, this.text.value))));
      if (result instanceof api.Result_Requirements) {
        if (generation === this.generation) this.adopt(result.value); else this.base = result.value;
        this.effects.saved(result.value);
      } else if (result instanceof api.Result_Failed && result.fault instanceof api.Fault_Conflict) {
        const current = await this.read(base.project);
        if (generation === this.generation && this.base === base) this.showConflict(base, current);
      } else if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
      else throw new Error('Unexpected standing requirements response');
    } finally { this.busy = false; this.save.disabled = false; }
  }
  private showConflict(base: api.ProjectRequirements, current: api.ProjectRequirements): void {
    const text = element('pre', current.text); text.setAttribute('aria-label', 'Current standing requirements');
    this.conflict.replaceChildren(
      element('h3', 'Edit conflict'),
      element('p', `Your text is based on revision ${base.revision.value}; the current revision is ${current.revision.value}. ${provenance(current)}`),
      element('p', 'Inspect the current text below and your text above. Changing the base keeps your text; a later save replaces the current text.'),
      text,
      button('Use current revision as base', () => { if (this.base === base) this.adopt(current); }));
    this.conflict.hidden = false;
  }
}
