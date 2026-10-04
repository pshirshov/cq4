The CQ UserPromptSubmit hook handled this {{COMMAND}} for this session before you saw it. Its result is in this turn's context as a block that begins `{{DRIVE}}`. This {{KIND}} cannot start or park a driver, and neither can you: only the CQ hooks do.

1. Find that block. If it is absent, report that the CQ hooks are not active in this session ({{PRECONDITION}}) and stop.
2. If the block reports a rejection, show it to the user verbatim and stop.
3. Otherwise show the user the block's preview: the advanceable items, the context-only items and the readiness reasons.
4. Call the CQ `session` tool exactly once with the Bind request printed in the block, using the bind token of this turn only. Show the status line of its reply, or the refusal if the bind is refused.
5. End your turn without calling any other CQ tool. The CQ Stop hook then supplies each advance directive; run a directive only when the hook gives you one, exactly as given.
