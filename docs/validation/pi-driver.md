# Pi extension driver

Recorded 2026-10-01 for T10 on Pi 0.99.1 (`/nix/store/6v3ax4h8wsnmn0nhz4c62hbahmmj39mf-pi-coding-agent-0.99.1/bin/pi`, provider `openai-codex`, model `gpt-5.5`). Evidence root: `/srv/nvme/tmp/cq4-cross-cut/t10-probe`.

## Interactive probe with a stub backend

Two interactive Pi sessions ran concurrently in a private tmux server in one scratch project. `cq configure pi` generated the project's assets; only `command` and `args` of `cq-host.json` were then pointed at `stub-host.mjs`, a scripted `cq/driver` backend with a minimal `session` tool. **No CQ server or attached host took part**, so the host-side acceptance of tokens is not part of this record; the contract tests and `dev/pi-driver-session.mjs` cover it.

| Evidence | Observed result |
| --- | --- |
| `screens/a-01-start.txt` | Pi loads `cq-host.js` and the four prompts without a shortcut diagnostic; the footer reads `CQ driver off`. |
| `screens/a-02-autocomplete.txt`, `a-03-hotkeys.txt` | `/cq:` completes `cq:drive` and `cq:park` beside the four prompts; `/hotkeys` lists `Ctrl+Alt+A` as the driver toggle. |
| `screens/a-04-…txt` | `/cq:drive through=explore` posts the host rejection as an error; the toggle key without a workset posts the notice asking for `/cq:drive`; the footer stays off. |
| `screens/a-05-drive-started.txt` | `/cq:drive T1 through=explore` posts the host message and the three preview groups, the footer shows the on line, and the submitted directive is expanded by Pi into the `/cq:advance` workflow prompt. |
| `screens/a-06-…txt`, `analysis.json` | Three directives over two cycles (start, resume, start) and the quiescent stop with its notice and footer line. In each of the six activations of both sessions the model sent the roots, phase and token of the directive forwarded before it. |
| `screens/a-07-…txt` | The toggle key restarts the last workset; pressed again mid-turn it parks, the turn finishes and no continuation follows. |
| `screens/a-08-escape-parks.txt` | `escape` aborts the driven turn; the extension posts the outcome and parks. |
| `screens/b-01-drive-park.txt`, `log/` | The second session drives and parks with `/cq:park` while the first runs a manual turn. Each host log carries one session key in all of its driver and usage requests (15 and 7 model turns); the two keys differ. Quitting Pi with the driver on sends `Park`. |

`analyze.py` derives `analysis.json` from the stub logs. A print-mode run (`pi -p`) with the driver off made no driver request.

## Simulated session on a real host

`dev/pi-driver-session.mjs`, run against a private PostgreSQL-backed server (`/srv/nvme/tmp/cq4-cross-cut/t10-run/session1`), loads the generated extension in a stand-in Pi runtime and connects it to real `cq host pi` processes. The model is simulated. It failed against the previous extension (`cq:drive` not registered) and passes with the driver.
