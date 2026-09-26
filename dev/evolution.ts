import { Note } from '../generated/fixtures/typescript/cq/fixture/v0_1_0/Note.js';
import { convert__note__from__0_1_0 } from '../generated/fixtures/typescript/cq/fixture/from_0_1_0_note.js';

export function checkEvolution(): void {
  const value = convert__note__from__0_1_0(new Note(9007199254740993n, 'historical'));
  if (value.revision !== 9007199254740993n || value.text !== 'historical' || value.annotation !== undefined) {
    throw new Error('Evolution changed existing values or failed to initialize the optional field');
  }
}
