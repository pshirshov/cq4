// A stand-in for Pi's extension runtime: the registrations, footer, notices and submitted user messages of one Pi session.
export function runtime(id) {
  const handlers = new Map();
  const tools = new Map();
  const commands = new Map();
  const shortcuts = new Map();
  const footer = new Map();
  const notices = [];
  const sent = [];
  const state = { idle: true };
  const context = {
    ui: { setStatus: (key, text) => footer.set(key, text), notify: (message, type) => notices.push({ message, type }) },
    sessionManager: { getSessionId: () => id }, isIdle: () => state.idle,
  };
  const pi = {
    on: (name, handler) => handlers.set(name, handler), registerTool: tool => tools.set(tool.name, tool),
    registerCommand: (name, options) => commands.set(name, options), registerShortcut: (key, options) => shortcuts.set(key, options),
    sendUserMessage: (content, options) => sent.push({ content, options }),
  };
  const fire = (name, event) => handlers.get(name)(event, context);
  return {
    id, pi, tools, commands, shortcuts, notices, sent, state, fire,
    footer: () => footer.get("cq-driver"),
    drive: input => commands.get("cq:drive").handler(input, context),
    park: () => commands.get("cq:park").handler("", context),
    toggle: () => shortcuts.get("ctrl+alt+a").handler(context),
    // One finished agent run: Pi ends its last turn, then settles.
    async settle(outcome) {
      await fire("turn_end", { type: "turn_end", outcome });
      await fire("agent_settled", { type: "agent_settled" });
    },
    start: () => fire("session_start", { type: "session_start", reason: "startup" }),
    stop: () => fire("session_shutdown", { type: "session_shutdown", reason: "quit" }),
  };
}
