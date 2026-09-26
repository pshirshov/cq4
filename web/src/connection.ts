import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import {
  ClientFrame, ClientFrame_Call, ClientFrame_Subscribe, ClientFrame_Ping, ClientFrame_Pong, ClientFrame_JsonCodec,
  ServerFrame, ServerFrame_Reply, ServerFrame_Ping, ServerFrame_Pong, ServerFrame_JsonCodec,
  Command, Result, RequestId, ProjectId, ChangeCursor,
} from '../../generated/typescript/cq/api/index.js';

type State = 'NEW' | 'ALIVE' | 'STALE' | 'DEAD';
interface Ping { sent: number; deadline: number }
interface Connection {
  id: number; socket: WebSocket; state: State; created: number; staleAt: number | null;
  lastPing: number; pings: Map<string, Ping>; rtt: number | null;
}
interface Pending { connection: number; deadline: number; resolve: (result: Result) => void; reject: (error: Error) => void }
export interface ConnectionStats {
  state: State | 'STOPPED' | 'DEFERRED'; active: number | null; connections: number; attempts: number;
  nextAttempt: number | null; deadline: number | null; rtt: number | null; reason: string; events: readonly string[];
}
export interface ConnectionListener {
  status(stats: ConnectionStats): void;
  active(): void;
  event(frame: ServerFrame): void;
  disconnected(): void;
}
const HEARTBEAT = 10000;
const PONG_TIMEOUT = 5000;
const CONNECT_TIMEOUT = 10000;
const STALE_GRACE = 15000;
const REQUEST_TIMEOUT = 30000;
const MAX_ATTEMPTS = 12;
const MAX_CONNECTIONS = 2;
const TICK = 250;
const MAX_EVENTS = 100;
const PERMANENT = new Set([1002, 1003, 1007, 1008, 1009, 1010, 1015]);

export class ConnectionManager {
  private readonly connections = new Map<number, Connection>();
  private readonly pending = new Map<string, Pending>();
  private readonly events: string[] = [];
  private readonly lifecycle = new AbortController();
  private readonly timer: ReturnType<typeof setInterval>;
  private nextId = 0;
  private activeId: number | null = null;
  private attempts = 0;
  private nextAttempt: number | null = null;
  private terminal = false;
  private destroyed = false;
  private deferred = false;
  private suspended = false;
  private lastTick = Date.now();
  private reason = '';

  constructor(private readonly url: string, private readonly listener: ConnectionListener) {
    const signal = this.lifecycle.signal;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') { this.deferred = false; this.resume(false); }
    }, { signal });
    document.addEventListener('freeze', () => this.log('Page frozen'), { signal });
    document.addEventListener('resume', () => this.resume(true), { signal });
    window.addEventListener('online', () => this.resume(true), { signal });
    window.addEventListener('offline', () => {
      this.log('Network offline');
      for (const connection of this.connections.values()) if (connection.state === 'ALIVE') {
        connection.state = 'STALE'; connection.staleAt = Date.now();
      }
      this.listener.disconnected(); this.publish();
    }, { signal });
    window.addEventListener('pagehide', () => {
      this.suspended = true;
      for (const connection of [...this.connections.values()]) this.close(connection, 'Page hidden for navigation');
    }, { signal });
    window.addEventListener('pageshow', () => { if (this.suspended) { this.suspended = false; this.resume(true); } }, { signal });
    if ('connection' in navigator) {
      const network = navigator.connection;
      if (network instanceof EventTarget) network.addEventListener('change', () => this.resume(true), { signal });
    }
    this.timer = setInterval(() => this.tick(), TICK);
    this.connect();
  }
  private log(message: string): void {
    this.reason = message;
    this.events.push(`${new Date().toLocaleTimeString()} ${message}`);
    if (this.events.length > MAX_EVENTS) this.events.shift();
  }
  private activeConnection(): Connection | undefined { return this.activeId === null ? undefined : this.connections.get(this.activeId); }
  private publish(): void {
    const active = this.activeConnection();
    const live = [...this.connections.values()];
    const pendingDeadline = active === undefined ? null : Math.min(...[...active.pings.values()].map(p => p.deadline));
    const deadline = pendingDeadline !== null && Number.isFinite(pendingDeadline) ? pendingDeadline :
      live.length > 0 && live[0].state === 'NEW' ? live[0].created + CONNECT_TIMEOUT : this.nextAttempt;
    this.listener.status({ state: this.terminal ? 'STOPPED' : this.deferred ? 'DEFERRED' : active === undefined ?
      live.some(c => c.state === 'NEW') ? 'NEW' : 'DEAD' : active.state, active: this.activeId,
      connections: live.length, attempts: this.attempts, nextAttempt: this.nextAttempt, deadline,
      rtt: active === undefined ? null : active.rtt, reason: this.reason, events: [...this.events] });
  }
  private connect(): void {
    if (this.destroyed || this.terminal || this.suspended || this.connections.size >= MAX_CONNECTIONS) return;
    if (document.visibilityState === 'hidden') { this.deferred = true; this.publish(); return; }
    if (this.attempts >= MAX_ATTEMPTS) { this.terminal = true; this.log('Retry limit reached; retry manually'); this.publish(); return; }
    this.attempts++;
    this.nextAttempt = null;
    const socket = new WebSocket(this.url);
    const connection: Connection = { id: ++this.nextId, socket, state: 'NEW', created: Date.now(), staleAt: null,
      lastPing: 0, pings: new Map(), rtt: null };
    this.connections.set(connection.id, connection);
    this.log(`Connecting ${connection.id}`);
    socket.addEventListener('open', () => { if (this.current(connection)) this.ping(connection); });
    socket.addEventListener('message', event => {
      if (!this.current(connection)) return;
      try {
        const frame = ServerFrame_JsonCodec.instance.decode(BaboonCodecContext.Default, JSON.parse(String(event.data)));
        if (frame instanceof ServerFrame_Ping) this.send(connection, new ClientFrame_Pong(frame.nonce));
        else if (frame instanceof ServerFrame_Pong) {
          const ping = connection.pings.get(frame.nonce);
          if (ping !== undefined) {
            for (const [nonce, pending] of connection.pings) if (pending.sent <= ping.sent) connection.pings.delete(nonce);
            connection.rtt = Date.now() - ping.sent;
            this.promote(connection);
          }
        } else if (this.activeId === connection.id) {
          if (frame instanceof ServerFrame_Reply) {
            const request = this.pending.get(frame.id.value);
            if (request !== undefined && request.connection === connection.id) {
              this.pending.delete(frame.id.value); request.resolve(frame.result);
            }
          } else this.listener.event(frame);
        }
      } catch (error) { this.stop(`Invalid server frame: ${String(error)}`); }
    });
    socket.addEventListener('close', event => {
      if (!this.current(connection)) return;
      this.close(connection, `Closed ${event.code}: ${event.reason}`);
      if (PERMANENT.has(event.code)) this.stop(`Connection stopped: ${event.code} ${event.reason}`);
      else this.schedule();
    });
    socket.addEventListener('error', () => { if (this.current(connection)) this.log(`Transport error on ${connection.id}`); });
    this.publish();
  }
  private current(connection: Connection): boolean { return !this.destroyed && this.connections.get(connection.id) === connection; }
  private send(connection: Connection, frame: ClientFrame): void {
    connection.socket.send(JSON.stringify(ClientFrame_JsonCodec.instance.encode(BaboonCodecContext.Default, frame)));
  }
  private ping(connection: Connection): void {
    if (connection.socket.readyState !== WebSocket.OPEN || connection.pings.size >= 3) return;
    const nonce = crypto.randomUUID();
    const now = Date.now();
    connection.pings.set(nonce, { sent: now, deadline: now + PONG_TIMEOUT });
    connection.lastPing = now;
    this.send(connection, new ClientFrame_Ping(nonce));
  }
  private promote(connection: Connection): void {
    if (!this.current(connection)) return;
    const changed = this.activeId !== connection.id;
    const recovered = connection.state !== 'ALIVE';
    connection.state = 'ALIVE'; connection.staleAt = null;
    this.activeId = connection.id; this.attempts = 0; this.nextAttempt = null;
    for (const other of [...this.connections.values()]) if (other !== connection) this.close(other, 'Superseded');
    if (changed || recovered) { this.log(`Connection ${connection.id} verified by heartbeat`); this.listener.active(); }
    this.publish();
  }
  private close(connection: Connection, reason: string): void {
    if (!this.connections.has(connection.id)) return;
    connection.state = 'DEAD'; this.connections.delete(connection.id);
    if (this.activeId === connection.id) { this.activeId = null; this.listener.disconnected(); }
    for (const [key, request] of this.pending) if (request.connection === connection.id) {
      this.pending.delete(key); request.reject(new Error('Connection replaced; refresh state before retrying unacknowledged changes'));
    }
    this.log(reason);
    connection.socket.close(1000, reason.slice(0, 80));
    this.publish();
  }
  private schedule(): void {
    if (this.destroyed || this.terminal || this.suspended || this.nextAttempt !== null) return;
    this.nextAttempt = Date.now() + Math.min(30000, 500 * 2 ** this.attempts) * (0.5 + Math.random() * 0.5);
    this.publish();
  }
  private resume(replace: boolean): void {
    if (this.destroyed || this.terminal || this.suspended) return;
    this.log('Checking connection after resume');
    for (const connection of this.connections.values()) this.ping(connection);
    if (replace || this.activeId === null) this.connect();
  }
  private tick(): void {
    if (this.destroyed || this.suspended) return;
    const now = Date.now();
    if (now - this.lastTick > TICK + PONG_TIMEOUT) this.resume(true);
    this.lastTick = now;
    for (const connection of [...this.connections.values()]) {
      if (connection.state === 'NEW' && now - connection.created >= CONNECT_TIMEOUT && connection.socket.readyState === WebSocket.CONNECTING) {
        this.close(connection, 'Connect timeout'); this.schedule(); continue;
      }
      if (connection.state !== 'STALE' && [...connection.pings.values()].some(p => now >= p.deadline)) {
        connection.state = 'STALE'; connection.staleAt = now; this.log(`Heartbeat overdue on ${connection.id}`);
        if (this.activeId === connection.id) this.listener.disconnected();
        this.connect();
      }
      if (connection.staleAt !== null && now - connection.staleAt >= STALE_GRACE) { this.close(connection, 'Stale grace expired'); this.schedule(); }
      else if (now - connection.lastPing >= HEARTBEAT) this.ping(connection);
    }
    for (const [key, request] of this.pending) if (now >= request.deadline) {
      this.pending.delete(key); request.reject(new Error('Request deadline exceeded; outcome may be unacknowledged'));
    }
    if (this.nextAttempt !== null && now >= this.nextAttempt) this.connect();
    this.publish();
  }
  private exchange(frame: ClientFrame_Call | ClientFrame_Subscribe): Promise<Result> {
    const connection = this.activeConnection();
    if (connection === undefined || connection.state !== 'ALIVE') return Promise.reject(new Error('No verified connection'));
    return new Promise((resolve, reject) => {
      this.pending.set(frame.id.value, { connection: connection.id, deadline: Date.now() + REQUEST_TIMEOUT, resolve, reject });
      this.send(connection, frame);
    });
  }
  call(command: Command): Promise<Result> { return this.exchange(new ClientFrame_Call(new RequestId(crypto.randomUUID()), command)); }
  subscribe(project: ProjectId, after: ChangeCursor): { id: RequestId; result: Promise<Result> } {
    const id = new RequestId(crypto.randomUUID());
    return { id, result: this.exchange(new ClientFrame_Subscribe(id, project, after)) };
  }
  retry(): void {
    if (this.destroyed) return;
    this.terminal = false; this.attempts = 0; this.nextAttempt = null; this.deferred = false;
    this.connect();
  }
  stop(reason: string): void {
    this.terminal = true; this.nextAttempt = null;
    for (const connection of [...this.connections.values()]) this.close(connection, reason);
    this.log(reason); this.publish();
  }
  destroy(): void {
    this.destroyed = true;
    this.lifecycle.abort(); clearInterval(this.timer); this.nextAttempt = null;
    for (const connection of [...this.connections.values()]) this.close(connection, 'Destroyed');
    clearInterval(this.timer);
  }
}
