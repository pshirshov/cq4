export async function trackProtocol(context) {
  await context.addInitScript(() => {
    window.cqBrowserProtocol = { pending: [], received: [] };
    const NativeSocket = window.WebSocket;
    window.WebSocket = class extends NativeSocket {
      constructor(...args) {
        super(...args);
        this.addEventListener('message', event => {
          const frame = JSON.parse(event.data);
          if (frame.Reply) {
            const id = frame.Reply.id.value;
            window.cqBrowserProtocol.received.push(id);
            window.cqBrowserProtocol.pending = window.cqBrowserProtocol.pending.filter(value => value !== id);
          }
        });
      }
      send(message) {
        const frame = JSON.parse(String(message));
        const request = frame.Call || frame.Subscribe;
        if (request) window.cqBrowserProtocol.pending.push(request.id.value);
        super.send(message);
      }
    };
  });
}

export async function receivedReply(page, id) {
  await page.waitForFunction(value => window.cqBrowserProtocol.received.includes(value), id);
}

export async function settledRequests(page) {
  await page.waitForFunction(() => window.cqBrowserProtocol.pending.length === 0);
}
