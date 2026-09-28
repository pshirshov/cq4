# Compact laptop UI and permanent launcher

Source correction: `b551e0b`; model stays `0.1.0`.

## Reproduction and scope

Actual Chromium on the preceding `cq-release-http-ui` native package measured navigation controls at 1366×768 and 1280×720. Buttons were 34px high; “Create project” ended at 1065.875px. Six controls were below the first viewport and seven below the second. The new behavior check failed before the CSS correction. Evidence: `/srv/nvme/tmp/cq4-compact-ui-20260928/baseline`.

The first attempt against the user's inactive port failed with connection refusal; that is environmental evidence in `before/`, not the density reproduction. The subsequent reproduction used a private, owned scratch instance and made no user-project changes.

The runtime change is solely `web/style.css`: 13px base font, 24px minimum button height, smaller control/pane spacing, and query completion positioned relative to its control. The original reference UI's 13px base font was inspected; reference files remain unchanged. In the corrected browser, “Create project” ends at 672px and all navigation buttons fit both laptop viewports without horizontal page overflow. Zoom and other viewport sizes retain normal scrolling; this is not a claim that every possible content pane fits without scrolling.

## Verification

- `baseline/`: failing original native reproduction and screenshots.
- `preview/`: passing CSS route override for visual inspection; not a packaged proof.
- `20260928T112045-ui`: complete focused browser gate passes, including laptop density, workspace behavior and plain-HTTP login.
- `20260928T112548-native`: 26 commands pass: fresh native build plus complete native runtime/browser corpus with explicit trace reuse.
- `20260928T113259-installed`: 25 root commands pass on the relocated package with source/classpath hidden, runtime import, complete runtime/browser corpus, restart and settled backup/restore.
- `delivery/result.json`: absolute `run-local.sh` invocation from an unrelated directory, default hostname origin, plain-HTTP browser login, both laptop sizes, project/credential persistence across restart, duplicate-launch rejection and owned SIGTERM/Ctrl-C cleanup all pass. Private scratch state only.

All paths above are under `/srv/nvme/tmp/cq4-compact-ui-20260928`. `dev/density-browser.mjs` is called from the normal browser fixture, so native and installed checks also exercise both laptop sizes.

The scoped native build uses `build-native.py` in that evidence root. It rejects any runtime delta beyond `web/style.css`, requires the unchanged tracing runner, verifies every immutable snapshot against the prior passing `20260928T102507-native` inventory, copies and remerges metadata, and rebuilds the executable with freshly generated assets. Fresh native runtime/browser checks run against that executable. `trace-reuse.json` explicitly distinguishes old tracing provenance from new build/runtime evidence. No new JVM tracing is claimed.

Astra's source review approves this scope and the trace reuse conditions. Final independent Astra delivery review approves the correction, provenance, installed package and wrapper smoke with no blocking or major findings (`final-review.json` in the evidence root). The paid three-harness corpus is unchanged; executions retain their original package identities and usage gaps. No model calls or efficiency claims are added.

## Permanent launch

`run-local.sh` resolves the repository from its own location, enters the pinned Nix environment, and selects `.local/release`. It defaults to the existing `/srv/nvme/tmp/cq4-playground` state and `http://vm.home.7mind.io:8080` origin. CQ binds `0.0.0.0`; PostgreSQL remains on loopback. No release path argument is needed. The package directory is ignored by Git and remains on disk.

See the [quickstart](../quickstart.md) for the launch command, token location, small-project walkthrough and optional overrides. Existing state is preserved; stop the previous launcher before restarting and reload the browser. Human M2/M6 acceptance remains pending.

## Verification scope correction

The full native runtime and installed suites started for this CSS-only change exceeded the affected behavior. The user corrected this during verification. Future simple frontend changes use focused frontend checks and a delivered-asset launch/browser smoke test; embedding assets in a native executable alone does not justify full backend gates. The already-started checks here are retained as observations, not a required procedure for CSS changes.

Current package: `/home/pavel/work/safe/cq4/cq4/.local/release`. Manifest SHA-256: `b41ebd161f73d0036edf66aefcb29220b557db8e7b7b8e9e94ab32cab3ae43ba`; executable SHA-256: `2597ad8a0207a54a62444cf5cc410b65c82ffd1cb9dad90c21f0ad4efcc64ea7`. The release evidence manifest binds exact gate, trace provenance and delivery hashes.
