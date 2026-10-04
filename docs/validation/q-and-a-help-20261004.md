# Q&A choices and complete Help commands, 2026-10-04

D134 and D136 are corrected and verified in source. This record does not establish installed or native release behavior. The live playground was not used as a test database. No model or schema change was needed; cq.api remains 0.1.0.

Evidence root: `/srv/nvme/tmp/cq4-d134-d136-evidence`.

## Reproductions and corrections

**D134:** The real QuestionBatch, itemView, ReferencePopup and generated API types were bundled into Chromium with the production stylesheet. The operator's long alternative was reproduced: Pick occupied y=297.91–323.41, the text y=338.41–353.41 and Recommended y=368.91–386.91. The test failed because Pick did not share the first text line (`d134-before.log`). Only the paragraph immediately after a Pick control now uses inline layout. Later Markdown paragraphs and lists retain block layout. The reproduction passes at widths 1366, 1280 and 640; it also checks preserved Markdown blocks and that Pick copies the complete alternative. It is registered in the existing configured UI component check. Existing actual-App Q&A checks pass all 11 cases (`final/question-results.json`), with no page errors. The actual dialog screenshot `final/question-alternatives.png` was inspected.

**D136:** CatalogReadLocal's new coverage test failed with `List() did not equal List("drive", "park")` (`d136-before.log`). Help now projects driver metadata from DriverAssets and argument definitions from DriverArguments alongside the unchanged workflow catalog. Existing driver prompt text moved into shared resources byte for byte. Claude/Codex alias bodies are the assets configuration writes. Pi aliases point to the actual `.pi/extensions/cq-host.js` extension and carry its actual source, using the same bounded loader as configuration; no Pi driver prompt files are invented. The four workflow request variants and workflow export behavior remain unchanged.

A separate native resource assertion failed because the new `cq/driver/drive.md` template had no native-image resource declaration (`native-metadata-before.log`). Both driver templates are now covered by explicit reachability metadata. This is a source packaging invariant check, not a native compilation or installed smoke test.

The actual Help browser check also reproduced incorrect argument syntax introduced by the previously workflow-only option formatter: `through= PHASE` and `workset= UUID` instead of `through=PHASE` and `workset=UUID` (`syntax-before/help-browser.log`). The formatter now handles positional and equals-bound options. The final real-App Help scenario validates all six commands and their three harness aliases, argument syntax and prompts, plus all nine agent entries, schemas/examples and harness tool permissions (`help-final/help-results.json`). It passes at 1366×768 and 640×720 without page errors. Its overflow fixture explicitly selects a long workflow prompt instead of assuming the last command always has long content. `help-final/help-commands-1366x768.png` was inspected.

## Focused verification

Each Scala suite ran in its own invocation and its test report was checked:

- CatalogReadLocal: **7 passed**, including driver coverage, native resource declarations and API JSON/UEBA/schema checks (`final/CatalogReadLocal.log`).
- WorkflowCatalogLocal: **5 passed** (`focused/WorkflowCatalogLocal.log`).
- AttachedAssetsLocal: **6 passed** (`focused/AttachedAssetsLocal.log`).
- DriverHookDummy: **6 passed** (`focused/DriverHookDummy.log`).
- TypeScript checking and web build passed (`help-final/typescript.log`, `help-final/web-build.log`).
- Markdown, requirements-save and choice layout component checks passed (`final/components.log`).
- Actual Help and Q&A browser fixtures passed against the source JVM and disposable PostgreSQL. Owned servers and databases shut down cleanly (`final/database-stop.log`, `help-final/database-stop.log`).

No configured fast/UI gate, full release gate, native build or operator updater was run. After local redeployment, restart the server and reload the browser, then verify the installed choices and six-command Help list before resolving D134/D136 and finalizing the dependent Help items.
