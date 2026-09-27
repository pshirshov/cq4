# Shared query language

M3 implementation is in progress. The first increment introduces generated AST/diagnostic types, a bounded parser and shared text normalization. Repository compilation, client wiring, completion and query-plan measurements remain pending. The M2 search endpoint still uses its earlier ledger/archive filter until that wiring is complete. All contracts stay in the single mutable `cq.api` 0.1.0 model.

## Grammar and values

```text
query       := expression | empty
expression  := conjunction (OR conjunction)*
conjunction := unary ((AND)? unary)*
unary       := (NOT | -) unary | '(' expression ')' | atom
atom        := item-id | word | quoted-string | attribute ':' value
value       := word | quoted-string
```

`NOT`/`-` bind before conjunction, and conjunction binds before `OR`. Boolean keywords are uppercase; lowercase words are full-text terms. Adjacent terms imply `AND`. Double-quoted values use JSON string escaping; unquoted backslash escapes are rejected. Quotes distinguish a literal phrase such as `"T42"` from an exact item ID. Parentheses, colon, quote, whitespace and a leading minus delimit tokens; quote literal values containing these delimiters.

| Attribute | Meaning |
| --- | --- |
| `id:T42`, or bare `T42` | Exact canonical item ID in the externally selected project. Prefixes are case-insensitive; numbers are positive 64-bit integers without leading zeroes |
| `ledger:Tasks` | Exact generated ledger name, case-insensitive |
| `status:Done` | A known status name from the fixed ledgers, case-insensitive; combining a ledger with an inapplicable status produces no matches |
| `tag:"Needs review"` | Exact, case-sensitive label |
| `project:<uuid>` | Exact canonical project UUID, intersected with the externally authorized project |
| `archived:true`, `archived:false`, `archived:all` | Archived, active, or either state |
| `<relation>:T42` | Typed outgoing view of an item reference, including inverse views; names are generated camel-case names rendered in kebab-case, e.g. `blocked-by`, `produces`, `part-of`, `reviewed-by` |

Attribute names are case-insensitive. Unknown attributes and malformed typed values return diagnostics; they do not silently become text searches. Bare letter/number words are exact IDs only when their prefix belongs to the fixed ledger vocabulary. `version2` is ordinary full text; `id:version2` is invalid.

An empty query selects active items. If no archive attribute occurs anywhere in the expression, the parser adds `archived:false` to the entire expression. Any explicit archive attribute disables that implicit predicate, so `NOT archived:true` selects active items and `alpha OR archived:true` preserves its stated Boolean meaning. Fetch-by-ID/history/reference resolution remain able to retrieve archived records.

Examples:

```text
ledger:Tasks status:Ready "retry deadline" NOT tag:blocked
(ledger:Tasks OR ledger:Defects) blocked-by:D12 archived:all
T42 OR (produces:T51 AND status:Open)
```

Queries are bounded to 4,096 UTF-16 characters, 512 lexical tokens, 128 explicit AST nodes and 16 nested parentheses/negations. One text atom contains at most 64 normalized words; each searchable word is at most 512 UTF-8 bytes. Invalid Unicode and NUL are rejected. Diagnostics use half-open UTF-16 spans, matching JavaScript editor selection offsets, including zero-width spans for missing input at EOF. These are syntax/size limits, not measured latency claims.

## Full-text semantics and PostgreSQL plan

The shared tokenizer normalizes NFKC, lowercases with the root locale and identifies Unicode letter/number words with subsequent combining marks. Punctuation separates words. Search covers title followed by the common narrative body. Unquoted text requires every normalized word; a quoted phrase requires their contiguous sequence. There is no stemming, fuzzy match, substring match, relevance ranking or locale-dependent segmentation. Labels retain their separate exact-value semantics.

The intended PostgreSQL representation is a normalized word stream plus a GIN-indexed text array. Array containment supplies indexed word candidates; phrase predicates also check the normalized stream. The dummy evaluates the same normalized words and phrase rule. PostgreSQL documents GIN support for array containment and its set-like duplicate handling. [GIN operator classes](https://www.postgresql.org/docs/18/gin.html#GIN-BUILTIN-OPCLASSES), [array operators](https://www.postgresql.org/docs/18/functions-array.html).

This representation is selected to preserve phrases late in permitted narratives and after many repetitions: native `tsvector` positions are limited to 16,383, with at most 256 positions per lexeme. [PostgreSQL text-search limits](https://www.postgresql.org/docs/18/textsearch-limitations.html). A document word exceeding the searchable-word limit retains a non-queryable marker in the stream, preventing phrase matches from joining words across the excluded word. Queries containing oversized words fail explicitly.

The SQL compiler will emit only fixed operators/column names from the typed AST and bind all query values. Canonical/inverse relationship filters will use project-scoped indexed edge probes. Authorization remains an independent outer predicate. A project attribute cannot widen access, and item numbers never identify items outside the selected project.

Pagination keeps the existing `(ledger, number)` order and project change cursor. Any committed item/reference change invalidates continuation; clients restart on `Resync`. Pages retain existing count/byte bounds. Counts, relevance ordering and snippets are not implicit page work. Positive text/reference queries can use their indexes; broad negations and `archived:all` may inspect many project rows. M3 verification must measure actual plans and affected-row write behavior at increasing unrelated sizes before claiming access-cost bounds. Updating search data must remain local to each changed item.

## Client integration still required

MCP, CLI and browser will submit the same query string to the shared parser. Cursor-aware completion will use this field/status/relation catalog, source spans and bounded item-reference lookup under the same project scope. Invalid queries will preserve the entered text and report diagnostics. The full editor interaction belongs to M5; M3 provides its parser/analysis contract and one query path.
