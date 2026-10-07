// How an agent model configuration is written, for the operator who types it. The typed help catalog (HelpCatalog) has no field
// that carries this text, so it is kept here, in one place, until the catalog does.

export const AGENTS_GRAMMAR = `A text has these parts, all optional:
  defaults.roles         the models of each role, whichever harness governs
  harnesses.H.tiers      what frontier, standard and fast mean for harness H: lists of its models
  harnesses.H.roles      the models of a role when H governs
H is claude, codex or pi. Roles: planner, worker, explorer, reviewer. Tiers: frontier, standard, fast.

Roles key                a role, or role/mode for one mode of it:    reviewer/plan: codex:@frontier
                         worker/implement, worker/probe, worker/resolveconflict; explorer/investigate, explorer/research;
                         reviewer/candidate, reviewer/plan, reviewer/audit. The key of a role holds for its modes without a key.

Model reference          harness:model    harness:provider/model    harness:@tier
                         $harness stands for the governing harness; ?effort=LEVEL may follow (off, minimal, low, medium, high, xhigh, max, ultra)
                         a Pi model is written provider/model, a Claude model without a provider
Tier list                the harness's own models, without the harness:    standard: [zai/glm-5.3, other/model?effort=high]

A role takes one of:
  a reference            claude:@standard                 a tier of several models is tried in order
  a strategy             { fallback: [a, b] }             the next one when one abstains; a model listed twice is tried once
                         { rr: [a, b] }                   round-robin across attempts; a model listed twice takes two turns
                         { first: [a, b] }                always the first; abstains when it is unavailable
  a panel (reviewer)     { all: [seat, seat], min: 1 }    every seat reviews; min of them must deliver
                         { any: [seat, seat], min: 1 }    in the listed order, as many as min needs
                         a seat is a reference or a strategy

A role is looked up in this order, and the first place that assigns it decides it whole:
  this project's harnesses.H.roles, this project's defaults.roles, the server's harnesses.H.roles, the server's defaults.roles.
A place assigns a role to work of one mode by the key of that mode or, without it, by the key of the role: reviewer in an earlier
place decides before reviewer/plan in a later one.
A tier list of this project replaces the server's list of that tier. A # starts a comment.`;

export const AGENTS_EXAMPLE = `defaults:
  roles:
    planner:  $harness:@frontier
    worker:   { fallback: [$harness:@standard, pi:@standard] }
    explorer: $harness:@fast
    reviewer: { any: [claude:@standard, pi:@standard], min: 1 }
harnesses:
  claude: { tiers: { frontier: [opus], standard: [sonnet], fast: [haiku] } }
  codex:
    tiers: { frontier: [gpt-6.1-sol?effort=xhigh], standard: [gpt-6.1-sol], fast: [gpt-6-luna?effort=low] }
    roles: { reviewer: { all: [claude:@standard, pi:@standard], min: 1 } }
  pi:
    tiers: { frontier: [openai-codex/gpt-6.1-sol?effort=xhigh], standard: [zai/glm-5.3, xiaomi-token-plan-ams/mimo-v2.6-pro], fast: [xiaomi-token-plan-ams/mimo-v2.6-pro?effort=low] }
`;
