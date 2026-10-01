# Shared query language

Generated AST/diagnostic types, a bounded parser and shared text normalization drive the single `SearchInput.query` operation. The CLI, MCP and browser submit the same query text. Cursor-aware completion metadata and [sampled query-plan measurements](../validation/m3-query-access.md) are implemented. All contracts stay in the single mutable `cq.api` 0.1.0 model; the earlier `ItemFilter` contract is removed.

## Grammar and values

```text
query       := expression | empty
expression  := conjunction (OR conjunction)*
conjunction := unary ((AND)? unary)*
unary       := (NOT | -) unary | '(' expression ')' | atom
atom        := item-id | word | quoted-string | attribute ':' value
value       := word | quoted-string
```

`NOT`/`-` bind before conjunction, and conjunction binds before `OR`. Boolean keywords are matched without regard to case (`not`, `Not` and `NOT` are the same operator); to search for the words themselves, quote them (`"not"`). Adjacent terms imply `AND`. Double-quoted values use JSON string escaping; unquoted backslash escapes are rejected. Quotes distinguish a literal phrase such as `"T42"` from an exact item ID. Parentheses, colon, quote, whitespace and a leading minus delimit tokens; quote literal values containing these delimiters.

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

PostgreSQL stores a normalized word stream plus a generated, GIN-indexed text array. Array containment supplies word candidates; phrase predicates also check the normalized stream. The dummy evaluates the same normalized words and phrase rule. PostgreSQL documents GIN support for array containment and its set-like duplicate handling. [GIN operator classes](https://www.postgresql.org/docs/18/gin.html#GIN-BUILTIN-OPCLASSES), [array operators](https://www.postgresql.org/docs/18/functions-array.html).

This representation is selected to preserve phrases late in permitted narratives and after many repetitions: native `tsvector` positions are limited to 16,383, with at most 256 positions per lexeme. [PostgreSQL text-search limits](https://www.postgresql.org/docs/18/textsearch-limitations.html). A document word exceeding the searchable-word limit retains a non-queryable marker in the stream, preventing phrase matches from joining words across the excluded word. Queries containing oversized words fail explicitly.

The SQL compiler emits only fixed operators/column names from the typed AST and binds all query values. Canonical/inverse relationship filters union matching endpoint identities from two project-bound edge queries; this also covers symmetric references. Authorization remains an independent outer predicate. A project attribute cannot widen access, and item numbers never identify items outside the selected project. Status values are stored lowercase for the existing project/status index. Exact tags use a GIN index over the summary's labels array. The retained access fixture measures these paths; its exact reference query starts from matching endpoints at the larger sizes, after reproducing and correcting a project-item scan in the earlier correlated form.

Pagination keeps the existing `(ledger, number)` order and project change cursor. Any committed item/reference change invalidates continuation; clients restart on `Resync`. Pages retain existing count/byte bounds. Counts, relevance ordering and snippets are not implicit page work. Positive text/reference queries can use their indexes; broad negations and arbitrary Boolean combinations may inspect many stored rows. [Access observations](../validation/m3-query-access.md) distinguish sampled bounds from such scans. Updating search data remains local to each changed item.

## Completion metadata

`ReadSelection.QueryComplete(query, cursor, limit)` uses the same parser and authorized project transaction. `QueryAnalysis` contains the ordinary full-query diagnostic, at most 50 suggestions and `hasMore`. Each suggestion supplies a kind, display label, insertion text and half-open UTF-16 replacement span. Cursor offsets outside the query or between surrogate halves are invalid. Incomplete quoted values may still have suggestions; syntax diagnostics remain visible independently.

The local lexical context selects field/relation names, Boolean operators, known ledger/status/archive values, the current project UUID, labels or item IDs. Replacements consume the entire token around the cursor, preserve an existing colon and JSON-escape labels. Label prefixes are case-sensitive; identifiers and fixed vocabularies are case-insensitive. Archived IDs remain available for reference resolution and are marked in display labels. Completion does not claim whole-expression satisfiability or filter references by endpoint policy.

PostgreSQL stores canonical display IDs with a project-scoped C-collated index. A project-scoped label catalog counts current item membership, including archived items. Edits update only removed/added labels within the item transaction; restore and rollback obey the same rules. Prefix lookups use a Unicode scalar successor range and `limit + 1`, without wildcard interpretation, full item bodies or total counts. The dummy uses matching UTF-8 byte ordering. The access fixture records the display-ID and label indexes in use across its three sizes. All persisted draft strings must be scalar Unicode without NUL, so stored values retain a lossless JSON/database representation.

## Clients and editor

MCP's `search` input accepts `query`; the administrative CLI accepts `cq query --query 'ledger:Tasks status:Ready'`; the browser has the [query editor](../validation/m5-query-editor.md) with completion popup, keyboard selection/dismissal and positioned diagnostics. All use the shared server parser. Invalid queries return `Fault.QuerySyntax` with a bounded diagnostic; the browser preserves the entered text and displays its span. CLI errors return nonzero. Existing count/byte bounds and snapshot continuation rules apply.

HTTP and the existing MCP `read` capability expose completion. The CLI accepts `cq query --query 'status:Re' --complete 9 --limit 20`; completion cannot be combined with page/snapshot continuation. Positioned diagnostics are metadata in this response; invalid request bounds remain errors. The verified M5 browser popup and keyboard interaction use this contract. [Verification and limits](../validation/m3-query-completion.md).
