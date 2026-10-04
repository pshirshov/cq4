# T59 driver evidence, sanitized projection

Governor-observed evidence from private fixtures: real Claude Code 2.1.285 sessions with the generated CQ assets against private CQ servers (package built from e5dba67) and private ledgers, 2026-10-04. These files are projections of private originals under `/srv/nvme/tmp/cq4-final-wave-20261004/evaluations/{claude-driver-cases,claude-cycles}`; they contain no credentials. A reviewer can recompute statements from these files but has not read the originals.

| File | Observed by | Content |
| --- | --- | --- |
| `isolation-evidence.json`, `isolation-bound-projection.json` | Codex governing session | Two concurrent sessions with distinct native session keys; A On with one managed child while B binds its own workset and stops Quiescent. |
| `skipped-result.json` | Codex governing session | Start directive not submitted: stop Failure "directive not started"; all item revisions and summaries unchanged. |
| `untracked-result.json` | Codex governing session | Workflow activated without a token: stop Failure "untracked activation"; ledger unchanged. |
| `out-of-set-result.json` | Codex governing session | Change outside the frozen set: stop Failure "out-of-set change"; ledger unchanged. |
| `park-both-on.json`, `park-a-only.json`, `park-both-off.json` | Codex governing session | Parking A leaves B On; parking B turns both Off. The requested hold never ran; state snapshots establish park isolation. |
| `cycle-resume-evidence.json` | Claude governing session (operator proxy) | Resume directives admitted and returning the existing run (drives 1 and 2); two consecutive cycles in one drive after an apply changed the advanceable set (drive 4); five Advance workflow records for five start activations, none for the two resumes; all children settled; nothing unsettled after upload. Its `limits` list states what was not shown. |

Recording digests (sha256): `claude-driver-cases/session-a-connected.cast` 283a3b2c697e57d4c00818fdabc74ad2f5d928cc0b35c4fd4a4e788e75dc9692; `claude-driver-cases/session-b-connected.cast` c1bd3dd8ac686139cfba2c13cbdc8764faeb1ed212fdc36e0f68363571c86696; `claude-cycles/session.cast` b11ae728aff93ad660c746db894b5e1521e8d91118086d8be23139ca849bd469.

Not shown by this evidence: Resume and consecutive cycles within one single drive; Pi against a real server; any behaviour of a package later than e5dba67.
