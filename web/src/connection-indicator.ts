import { ConnectionStats } from './connection.js';
import { button, element } from './editor.js';

const RENDER_INTERVAL = 100;
function seconds(ms: number): string { return `${Math.max(0, ms / 1000).toFixed(1)}s`; }

export class ConnectionIndicator {
  readonly element = element('details', '');
  private readonly health = element('span', 'Connecting');
  private readonly deadline = element('progress', '');
  private readonly overview = element('pre', '');
  private readonly pool = element('div', '');
  private readonly log = element('pre', '');
  private readonly cards = new Map<number, HTMLElement>();
  private readonly lifecycle = new AbortController();
  private stats: ConnectionStats | null = null;
  private animation: number | null = null;
  private lastRender = 0;
  private destroyed = false;

  constructor(retry: () => void) {
    this.element.className = 'connection-indicator';
    const summary = element('summary', ''); summary.append(this.health, this.deadline); this.deadline.max = 1;
    const details = element('section', ''); details.className = 'connection-diagnostics';
    details.setAttribute('aria-label', 'Connection diagnostics');
    this.overview.className = 'connection-overview'; this.log.className = 'connection-log';
    details.append(this.overview, button('Retry connection', retry), this.pool,
      element('p', 'Heartbeats run while the page can execute. Browser suspension delays detection. Heartbeat deadline misses are not a measurement of network packet loss. RTT windows retain at most 512 samples per connection.'),
      element('h3', 'Connection events'), this.log);
    this.element.append(summary, details);
    this.element.addEventListener('toggle', () => this.render(Date.now()));
    window.addEventListener('pagehide', () => this.cancel(), { signal: this.lifecycle.signal });
    window.addEventListener('pageshow', () => this.animate(), { signal: this.lifecycle.signal });
  }
  update(stats: ConnectionStats): void {
    if (this.destroyed) return;
    this.stats = stats;
    this.health.textContent = `Connection: ${stats.state}`; this.health.dataset.state = stats.state;
    this.health.setAttribute('aria-label', `Connection ${stats.state}`);
    document.title = `CQ — ${stats.state}`;
    this.render(Date.now()); this.animate();
  }
  private animate(): void {
    if (this.destroyed || this.animation !== null || this.stats === null) return;
    this.animation = requestAnimationFrame(() => {
      this.animation = null; const now = Date.now();
      if (now - this.lastRender >= RENDER_INTERVAL) this.render(now);
      this.animate();
    });
  }
  private render(now: number): void {
    const stats = this.stats; if (this.destroyed || stats === null) return;
    this.lastRender = now;
    const phase = stats.phase;
    const wait = phase === null ? 'No active deadline' : `${phase.kind}: ${seconds(phase.started + phase.duration - now)} remaining / ${seconds(phase.duration)}`;
    this.deadline.hidden = phase === null;
    this.deadline.value = phase === null ? 0 : Math.max(0, Math.min(1, (phase.started + phase.duration - now) / phase.duration));
    this.deadline.setAttribute('aria-label', wait); this.deadline.setAttribute('aria-valuetext', wait); this.deadline.title = wait;
    if (!this.element.open) return;
    const backoff = stats.state === 'STOPPED' ? 'Stopped; manual retry required' : stats.state === 'DEFERRED' ? 'Deferred until tab is visible' :
      stats.state === 'SUSPENDED' ? 'Suspended for page navigation' : stats.nextAttempt === null ? 'No retry scheduled' : `Next retry in ${seconds(stats.nextAttempt - now)}`;
    const last = stats.lastClose;
    this.overview.textContent = `Connections: ${stats.connections}/2; active: ${stats.active === null ? 'none' : stats.active}\n${wait}\nAttempts since last verified connection: ${stats.attempts}/${stats.maxAttempts}\n${backoff}\nLast peer close: ${last === null ? 'none' : `#${last.connection} · ${last.code} · ${last.reason || '(no reason)'} · ${new Date(last.at).toLocaleTimeString()}`}\n${stats.reason}`;
    for (const [id, card] of this.cards) if (!stats.pool.some(connection => connection.id === id)) { card.remove(); this.cards.delete(id); }
    for (const connection of stats.pool) {
      let card = this.cards.get(connection.id);
      if (card === undefined) { card = element('pre', ''); this.cards.set(connection.id, card); this.pool.append(card); }
      card.className = connection.active ? 'connection-card connection-active' : 'connection-card';
      card.dataset.connection = String(connection.id);
      const settled = connection.timely + connection.missed;
      const loss = settled === 0 ? 'unknown (no settled probes)' : `${(connection.missed / settled * 100).toFixed(1)}% (${connection.missed}/${settled} settled probes)`;
      card.textContent = `#${connection.id} · ${connection.active ? 'ACTIVE' : 'BACKGROUND'} · ${connection.state}\nConnection age: ${seconds(now - connection.created)}; first verified: ${connection.verified === null ? 'never' : seconds(now - connection.verified) + ' ago'}\nHeartbeats: ${connection.pending} in flight; ${connection.sent} sent\nDeadline misses: ${loss}\nRTT (min / median / max ms; count):\n` +
        connection.rtt.map(window => `${window.seconds}s: ${window.count === 0 ? 'unknown; 0' : `${window.min} / ${window.median} / ${window.max}; ${window.count}`}`).join('\n');
    }
    this.log.textContent = stats.events.join('\n');
  }
  private cancel(): void { if (this.animation !== null) cancelAnimationFrame(this.animation); this.animation = null; }
  destroy(): void { this.destroyed = true; this.lifecycle.abort(); this.cancel(); }
}
