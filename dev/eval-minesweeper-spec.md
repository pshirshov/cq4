# TUI minesweeper consumer, specification version 1

Default consumer project of the [evaluation protocol](../docs/evaluation-protocol.md). The same text is given to every harness. A change to this file changes the version number in the title; a run report names the version it used.

## Product

Implement a terminal minesweeper in Python 3 using only the standard library (`curses` for the screen).

- `python3 -m minesweeper` starts a game on a 9×9 board with 10 mines. `--width W`, `--height H` and `--mines M` select another board: W and H are integers from 5 through 30, M is an integer from 1 through W×H−9. `--seed N` (a non-negative integer) makes the mine layout reproducible.
- The first revealed cell and its neighbours never hold a mine: mines are placed after the first reveal.
- Arrow keys move the cursor. Space reveals the cell under the cursor, `f` toggles a flag, `r` starts a new game with the same options, `q` quits with exit status 0.
- Revealing a cell with no adjacent mine reveals its neighbours recursively. Revealing a flagged cell does nothing. Revealing a mine loses the game and shows every mine. The game is won when every cell without a mine is revealed.
- The screen shows the board, the number of mines minus the number of flags, and the state (playing, won, lost). A terminal smaller than the board ends the program with exit status 1 and a one-line diagnostic on stderr, with the terminal restored.
- `--version` prints `minesweeper 1` and exits 0 without starting curses. `--help` prints usage on stdout and exits 0. An invalid, missing or out-of-range option value, an unknown option and a positional argument exit 2 with a non-empty diagnostic on stderr and nothing on stdout.
- The game rules live in a module that does not import `curses`, so that they are tested without a terminal.
- Provide automated tests for mine placement (count, first-reveal safety, seed reproducibility), neighbour counts, recursive reveal, flags, win and loss detection and argument errors, and a README with the exact run and test commands.

## Declared validation checks

The supervisor settings of every run declare these two checks; the host runs them on each candidate.

| Name | Command, from the repository root | Passes when |
| --- | --- | --- |
| `tests` | `python3 -m unittest discover -v` | Exit status 0 and at least one test ran. |
| `launch` | `python3 -m minesweeper --version` | Exit status 0. |

`unittest` exits 5 when it discovers no test (Python 3.12 and later); on an older Python the `tests` check passes with zero tests, and the run report says which Python ran it.

## Follow-up request

After the first version is integrated, the session is given this follow-up, unchanged:

> Add a `--difficulty` option with the values `beginner` (9×9, 10 mines), `intermediate` (16×16, 40 mines) and `expert` (30×16, 99 mines). It conflicts with `--width`, `--height` and `--mines`: giving both exits 2. Show the elapsed whole seconds since the first reveal on the screen; the clock stops when the game is won or lost. Add tests for both.

## Answers the operator-proxy gives

Preference Questions the session raises about this project are answered from this table and from the product section. A Question that neither decides is escalated to the operator.

| Subject | Answer |
| --- | --- |
| Language and dependencies | Python 3, standard library only. No third-party package, no build system. |
| Package layout | A package directory `minesweeper/` with `__main__.py`; tests under `tests/`. |
| Colours, mouse support, high scores, saved games | Not wanted. |
| Chording (revealing the neighbours of a satisfied number) | Not wanted. |
| Question mark flags | Not wanted. |
| Anything that enlarges the scope beyond the product section and the follow-up | Not wanted. |
