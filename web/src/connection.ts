import { uuidV4 } from './uuid.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';
import {
  ClientFrame, ClientFrame_Call, ClientFrame_Watch, ClientFrame_Ping, ClientFrame_Pong, ClientFrame_JsonCodec,
  ServerFrame, ServerFrame_Reply, ServerFrame_Ping, ServerFrame_Pong, ServerFrame_JsonCodec,
  Command, Result, RequestId, LiveScope,
} from '../../generated/typescript/cq/api/index.js';

type State = 'NEW' | 'ALIVE' | 'STALE' | 'DEAD';
interface Ping { sent: number; deadline: number; missed: boolean }
interface RttSample { at: number; value: number }
interface Connection {
  id: number; socket: WebSocket; state: State; created: number; staleAt: number | null;
  lastPing: number; pings: Map<string, Ping>; verified: number | null;
  samples: RttSample[]; sent: number; timely: number; missed: number;
}
interface Pending { connection: number; deadline: number; resolve: (result: Result) => void; reject: (error: Error) => void }
export interface ConnectionPhase { kind: 'Connect' | 'Pong' | 'Grace' | 'Backoff'; started: number; duration: number }
export interface RttWindow { seconds: number; count: number; min: number | null; median: number | null; max: number | null }
export interface PoolEntry {
  id: number; state: State; active: boolean; created: number; verified: number | null;
  pending: number; sent: number; timely: number; missed: number; rtt: readonly RttWindow[];
}
export interface ConnectionStats {
  state: State | 'STOPPED' | 'DEFERRED' | 'SUSPENDED'; active: number | null; connections: number; attempts: number;
  maxAttempts: number; nextAttempt: number | null; phase: ConnectionPhase | null; pool: readonly PoolEntry[];
  lastClose: { connection: number; code: number; reason: string; at: number } | null;
  reason: string; events: readonly string[];
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
const MAX_SAMPLES = 512;
const RTT_WINDOWS = [30, 60, 300];
const MAX_BACKOFF = 30000;
const BACKOFF_BASE = 500;
const JUMP_THRESHOLD = 1000;
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
  private backoff: ConnectionPhase | null = null;
  private lastClose: ConnectionStats['lastClose'] = null;
  private terminal = false;
  private destroyed = false;
  private deferred = false;
  private suspended = false;
  private lastTick = Date.now();
  private reason = '';

  constructor(private readonly url: string, private readonly listener: ConnectionListener) {
    const signal = this.lifecycle.signal;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') { const replace = this.deferred; this.deferred = false; this.resume(replace); }
    }, { signal });
    document.addEventListener('freeze', () => { this.log('Page frozen; monitoring resumes with the page'); this.publish(); }, { signal });
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
      this.suspended = true; this.nextAttempt = null; this.backoff = null;
      for (const connection of [...this.connections.values()]) this.close(connection, 'Page hidden for navigation');
      this.publish();
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
    const focus = active === undefined ? live.find(c => c.state === 'STALE') ?? live[0] : active;
    const state = this.terminal ? 'STOPPED' : this.suspended ? 'SUSPENDED' : this.deferred ? 'DEFERRED' : focus === undefined ? 'DEAD' : focus.state;
    const now = Date.now();
    this.listener.status({ state, active: this.activeId, connections: live.length, attempts: this.attempts,
      maxAttempts: MAX_ATTEMPTS, nextAttempt: this.nextAttempt,
      phase: this.terminal || this.suspended || this.deferred ? null : this.phase(focus),
      pool: live.map(connection => {
        connection.samples = connection.samples.filter(sample => now - sample.at <= RTT_WINDOWS[RTT_WINDOWS.length - 1] * 1000);
        return { id: connection.id, state: connection.state, active: connection.id === this.activeId,
          created: connection.created, verified: connection.verified, pending: connection.pings.size,
          sent: connection.sent, timely: connection.timely, missed: connection.missed,
          rtt: RTT_WINDOWS.map(seconds => {
            const values = connection.samples.filter(sample => now - sample.at <= seconds * 1000).map(sample => sample.value).sort((a, b) => a - b);
            const middle = Math.floor(values.length / 2);
            return { seconds, count: values.length, min: values.length === 0 ? null : values[0],
              median: values.length === 0 ? null : values.length % 2 === 0 ? (values[middle - 1] + values[middle]) / 2 : values[middle],
              max: values.length === 0 ? null : values[values.length - 1] };
          }) };
      }), lastClose: this.lastClose, reason: this.reason, events: [...this.events] });
  }
  private phase(connection: Connection | undefined): ConnectionPhase | null {
    if (connection !== undefined) {
      if (connection.staleAt !== null) return { kind: 'Grace', started: connection.staleAt, duration: STALE_GRACE };
      if (connection.socket.readyState === WebSocket.CONNECTING) return { kind: 'Connect', started: connection.created, duration: CONNECT_TIMEOUT };
      const first = connection.pings.values().next().value;
      if (first !== undefined) return { kind: 'Pong', started: first.sent, duration: PONG_TIMEOUT };
    }
    return this.backoff;
  }
  private connect(): void {
    if (this.destroyed || this.terminal || this.suspended || this.connections.size >= MAX_CONNECTIONS) return;
    if (document.visibilityState === 'hidden') {
      this.deferred = true; this.nextAttempt = null; this.backoff = null;
      this.log('Replacement deferred until tab is visible'); this.publish(); return;
    }
    if (this.attempts >= MAX_ATTEMPTS) { this.terminal = true; this.log('Retry limit reached; retry manually'); this.publish(); return; }
    this.attempts++;
    this.nextAttempt = null; this.backoff = null; this.deferred = false;
    const socket = new WebSocket(this.url);
    const connection: Connection = { id: ++this.nextId, socket, state: 'NEW', created: Date.now(), staleAt: null,
      lastPing: 0, pings: new Map(), verified: null, samples: [], sent: 0, timely: 0, missed: 0 };
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
            this.expirePings(connection, Date.now());
            if (!ping.missed) connection.timely++;
            for (const [nonce, pending] of connection.pings) if (pending.sent <= ping.sent) connection.pings.delete(nonce);
            connection.samples.push({ at: Date.now(), value: Math.max(0, Date.now() - ping.sent) });
            if (connection.samples.length > MAX_SAMPLES) connection.samples.shift();
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
      this.lastClose = { connection: connection.id, code: event.code, reason: event.reason, at: Date.now() };
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
    const nonce = uuidV4(crypto);
    const now = Date.now();
    connection.pings.set(nonce, { sent: now, deadline: now + PONG_TIMEOUT, missed: false });
    connection.lastPing = now; connection.sent++;
    this.send(connection, new ClientFrame_Ping(nonce));
    this.publish();
  }
  private promote(connection: Connection): void {
    if (!this.current(connection)) return;
    const changed = this.activeId !== connection.id;
    const recovered = connection.state !== 'ALIVE';
    connection.state = 'ALIVE'; connection.staleAt = null;
    if (connection.verified === null) connection.verified = Date.now();
    this.activeId = connection.id; this.attempts = 0; this.nextAttempt = null; this.backoff = null; this.deferred = false;
    if (changed || recovered) {
      for (const other of [...this.connections.values()]) if (other !== connection) this.close(other, 'Superseded');
      this.log(`Connection ${connection.id} verified by heartbeat`); this.listener.active();
    }
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
    this.backoff = { kind: 'Backoff', started: Date.now(), duration: Math.min(MAX_BACKOFF, BACKOFF_BASE * 2 ** this.attempts) * (0.5 + Math.random() * 0.5) };
    this.nextAttempt = this.backoff.started + this.backoff.duration;
    this.publish();
  }
  private resume(replace: boolean): void {
    if (this.destroyed || this.terminal || this.suspended) return;
    this.lastTick = Date.now();
    this.log('Checking connection after resume');
    for (const connection of this.connections.values()) this.ping(connection);
    if (replace || this.activeId === null) this.connect();
  }
  private expirePings(connection: Connection, now: number): void {
    for (const ping of connection.pings.values()) if (!ping.missed && now >= ping.deadline) { ping.missed = true; connection.missed++; }
  }
  private tick(): void {
    if (this.destroyed || this.suspended) return;
    const now = Date.now();
    const gap = now - this.lastTick - TICK;
    if (gap > JUMP_THRESHOLD) this.resume(gap >= PONG_TIMEOUT);
    this.lastTick = now;
    for (const connection of [...this.connections.values()]) {
      this.expirePings(connection, now);
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
  private exchange(frame: ClientFrame_Call): Promise<Result> {
    const connection = this.activeConnection();
    if (connection === undefined || connection.state !== 'ALIVE') return Promise.reject(new Error('No verified connection'));
    return new Promise((resolve, reject) => {
      this.pending.set(frame.id.value, { connection: connection.id, deadline: Date.now() + REQUEST_TIMEOUT, resolve, reject });
      this.send(connection, frame);
    });
  }
  call(command: Command): Promise<Result> { return this.exchange(new ClientFrame_Call(new RequestId(uuidV4(crypto)), command)); }
  watch(scope: LiveScope): RequestId {
    const connection = this.activeConnection();
    if (connection === undefined || connection.state !== 'ALIVE') throw new Error('No verified connection');
    const id = new RequestId(uuidV4(crypto));
    this.send(connection, new ClientFrame_Watch(id, scope)); return id;
  }
  retry(): void {
    if (this.destroyed) return;
    this.terminal = false; this.attempts = 0; this.nextAttempt = null; this.backoff = null; this.deferred = false;
    this.connect();
  }
  stop(reason: string): void {
    if (this.destroyed) return;
    this.terminal = true; this.nextAttempt = null; this.backoff = null;
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
