# Governing-turn hook captures (Task 91)

Hook invocations of a **real Claude Code 2.1.295** process (`claude --version` of the Nix package
`claude-code-no-autoupdate`, executable `/nix/store/56vld046mr6h92aik233d13wagl9a0ld-claude-code-2.1.295/libexec/claude-code/claude`),
captured on 2026-10-09 on one machine. **No real model took part and no login was read:** the provider was `stub.py`, a local imitation
of the Anthropic Messages API that scripts the model's replies from `[stub:...]` markers in the prompt. **Every reply is invented.** The
harness, its hook invocations and its tool runs (the 2 s `sleep`, the background command, the subagent) are real. The process ran with a
private empty `HOME` and `CLAUDE_CONFIG_DIR` and a wrong API key. The analysis is in
[`docs/validation/governing-turn-hooks.md`](../../../../../docs/validation/governing-turn-hooks.md).

One clock: every time is `time.time() * 1000` (CLOCK_REALTIME of this machine, epoch milliseconds), written by `hook.py` and `stub.py`.

## Scripts

| File | Purpose |
|---|---|
| `capture.py` | Runs the cases. `capture.py --out DIR` uses the stub and the private directories; `capture.py --real --out DIR` keeps the caller's environment and login and starts no stub (the one-command repeat for the operator). `--cases a,b,c,d,e,ei,f,g`, `--modes print,interactive`. |
| `stub.py` | The invented provider. Logs one `arrive` and one `done` line per request. |
| `hook.py` | The hook command of every event: appends `{"t_ms","pid","event","stdin"}` to `hooks.jsonl`; with `--block-once` its Stop answers `{"decision":"block","reason":...}` once (case c). Not CQ's hook program. |
| `timeline.py CASEDIR` | Prints hooks and stub requests of one case on one time line. |
| `intervals.py CASEDIR...` | Prints, per turn, the interval UserPromptSubmit to last Stop and which requests lie in it. |

## Case directories

`<mode>-<case>/` with mode `print` (`claude -p --output-format stream-json --verbose`; cases d and f with `--input-format stream-json`)
or `interactive` (pseudo-terminal, Python `pty`). Cases: `a` one prompt; `b` tool call `sleep 2`; `c` Stop hook blocks once; `d` two
prompts; `e` SIGTERM (print) / Escape (interactive) while the stub holds the reply 25 s; `ei` SIGINT (print only); `f` background
command exits while idle; `g` subagent (Task/Agent tool).

Each directory holds: `settings.json` (the hook settings, passed with `--settings`; all 33 events this version offers), `hooks.jsonl`
(every hook invocation: unchanged stdin as a string and its time), `stub.jsonl` (the provider's request log), `command.txt`, `exit`,
`notes.txt`, and `stdout.jsonl` (print: native stream-json transcript, `stderr.txt` its standard error) or `transcript.raw`,
`transcript.times` (byte counts with arrival times) and `transcript.txt` (escape sequences stripped) in interactive mode.

Cases captured: `print-{a,b,c,d,e,ei,f,g}`, `interactive-{a,b,c,d,e,f,g}`. No case failed to capture. `interactive-ei` (SIGINT to a
pseudo-terminal process) and interactive SIGTERM were not run; the `/exit` that ends each interactive run is the only exit path there.
Directories created by the run (`home`, `config`, `work`) were removed; paths in the files still name `/tmp/gt/cap/<case>/`.

`hooks.jsonl` and `stub.jsonl` line numbers are what the document cites. The side effects that a real login would add (a title request
to the small model, an auto-mode classifier) show as extra stub requests; the stub answers them with plain text.
