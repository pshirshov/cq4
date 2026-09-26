# Development version policy

User instruction, 2026-09-26: keep ONE version and bump it only when explicitly requested by the user. Breaking changes are authorized during current development.

- Keep all CQ contracts in `models/cq-api.baboon` at `cq.api` version `0.1.0`.
- Edit the current model and database schema in place. Do not add historical schema copies, conversion fixtures, compatibility adapters or upgrade paths during this development phase.
- Regenerate codecs and the current signature after schema edits. The signature records the current model; it does not freeze its shape.
- Earlier design/plan requirements for historical decoding and schema evolution are superseded by this instruction. Preserve historical verification evidence as a record of what was actually run.
