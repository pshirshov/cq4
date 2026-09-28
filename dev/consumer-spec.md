# Word-frequency consumer

Implement a small command-line application using only the standard library. The assigned language and launch command are specified in the evaluation request.

- Read UTF-8 text from standard input. Words are maximal runs matching ASCII `[A-Za-z]+`; every other character is a separator. Normalize words to lowercase ASCII.
- Count words and print one `word<TAB>count<NEWLINE>` record per word, sorted by descending count and then ascending word. Empty input or input without ASCII words produces empty output.
- Accept optional `--top N`, where N is an integer from 1 through 1000 inclusive, to emit at most N records. With no option, emit all records.
- Missing/invalid/out-of-range N, unknown options and positional arguments must exit 2, emit a nonempty diagnostic on stderr and emit no stdout. Argument errors take precedence over `--help`, regardless of argument order: validate the entire argument list before returning help.
- Standalone `--help` must exit 0, print usage on stdout and emit no stderr. Successful normal processing exits 0 and emits no stderr.
- Provide automated tests covering tokenization, normalization, sorting, top limits, empty input and argument errors, plus a short README with exact run/test commands.

For Python, `python -m wordfreq` must work from the repository root, and `python -m unittest discover -v` must discover and pass the consumer's tests. For Go, `go run .` must work from the root, and `go test ./...` must run and pass the consumer's tests. Include a Go module when using Go. The host will separately validate the candidate with configured checks.
