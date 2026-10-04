The CQ UserPromptSubmit hook handled this {{COMMAND}} for this session before you saw it. Its result is in this turn's context as a block that begins `{{PARK}}`. This {{KIND}} cannot start or park a driver, and neither can you: only the CQ hooks do.

1. Find that block. If it is absent, report that the CQ hooks are not active in this session ({{PRECONDITION}}) and stop.
2. Show the block to the user verbatim.
3. Call the CQ `session` tool with `{"Driver":{}}` and show the status line of its reply, then end your turn.
