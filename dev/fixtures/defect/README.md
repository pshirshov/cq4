# Synthetic token-counting consumer

The command reads UTF-8 stdin and writes lowercase ASCII words and counts as tab-separated lines, sorted by word. A word is a maximal `[A-Za-z]+` match. Digits, underscores and all other characters delimit words. Empty input produces no output and exits successfully.

Run `python3 count_words.py`; verify with `python3 -m unittest -v`.

`synthetic_tokens.py` is an explicitly local synthetic dependency. There is no actual external upstream or submission endpoint.
