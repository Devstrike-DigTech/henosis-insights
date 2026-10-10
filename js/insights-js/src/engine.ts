import {
  callsEvent,
  deprecatedEvent,
  errorEvent,
  healthEvent,
  heartbeatEvent,
  initEvent,
  iso,
  type Json,
  LIMITS,
  utcDay,
} from './events.js';
import * as Names from './names.js';
import { sdkFrames } from './stack.js';
import { KEEPALIVE_MAX_BYTES, type Transport } from './transport.js';
import type { HealthCheckResult, InsightsConfig, InsightsStorage, PlatformInfo } from './types.js';

export const CLIENT_VERSION = '0.1.0';
export const DEFAULT_ENDPOINT = 'https://in.insights.henosis.dev/v1/batches';
export const QUEUE_CAP_BYTES = 1_000_000;
export const MAX_INTERVAL_MS = 6 * 60 * 60 * 1000;
export const RETRY_BACKOFF_MS = 5 * 60 * 1000;
const HOST_ENABLED_KEY = 'henosis-insights:host_enabled';

export interface EngineDeps {
  transport: Transport;
  platform: PlatformInfo;
  now: () => number;
  random: () => number;
  /** True when the host disabled Insights without code (env var / global flag). */
  envOptOut: () => boolean;
  /** Registers a page-hide hook; returns an unregister function. */
  onPageHide?: (cb: () => void) => (() => void) | undefined;
  /** When `auto` mode asks the native client whether it runs (wait, then probe). */
  probeDelays?: number[];
}

const DEFAULT_PROBE_DELAYS = [0, 500, 2000];
const BRIDGE_WRAPPER_KINDS = new Set(['flutter', 'react-native', 'web', 'node']);

/** In-memory storage used when the vendor supplies none. */
export class MemoryStorage implements InsightsStorage {
  readonly map = new Map<string, string>();
  getItem(key: string) {
    return this.map.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.map.set(key, value);
  }
  removeItem(key: string) {
    this.map.delete(key);
  }
}

interface State {
  queue: string[];
  calls: [string, number][];
  deprecated: [string, number][];
  windowFrom: number;
  lastUploadAt: number;
  heartbeatDay: string;
  sample?: number;
}

/**
 * The engine for one SDK. Every public method returns immediately and never throws or rejects.
 * Work runs after the stored state has loaded; calls made before that are replayed in order.
 */
export class Engine {
  readonly key: string;
  private readonly prefixes: string[];
  private readonly storage: InsightsStorage;
  private readonly customer: { key: string; ok: boolean; missing: boolean };
  private readonly scope: string;

  private active: 'direct' | 'bridged' | undefined;
  private bridgeCalls = new Map<string, number>();
  private bridgeDeprecated = new Map<string, number>();
  private bridgeEvents: Json[] = [];
  private forwardScheduled = false;
  private ready = false;
  private stopped = false;
  private enabled = false;
  private hostEnabled = true;
  private readonly pending: Array<() => void> = [];

  private queue: string[] = [];
  private queueBytes = 0;
  private calls = new Map<string, number>();
  private deprecated = new Map<string, number>();
  private windowFrom = 0;
  private lastUploadAt = 0;
  private nextAttemptAt = 0;
  private heartbeatDay = '';
  private sample: number | undefined;
  private uploading: Promise<void> | undefined;
  private saveScheduled = false;
  private unregisterPageHide: (() => void) | undefined;
  readonly loaded: Promise<void>;

  constructor(
    readonly config: InsightsConfig,
    private readonly deps: EngineDeps,
  ) {
    this.key = `${config.writeKey}|${config.sdkName}`;
    this.prefixes = (config.sdkPackagePrefixes ?? []).filter((p) => typeof p === 'string' && p);
    this.storage = config.storage ?? new MemoryStorage();
    this.customer = Names.customerKey(config.customerKey);
    this.scope = `henosis-insights:${Names.sdkName(config.sdkName)}:${String(config.writeKey).slice(-8)}`;
    this.loaded = this.load();
  }

  /** `direct` or `bridged` once decided; undefined while `auto` mode is still probing. */
  get activeMode(): 'direct' | 'bridged' | undefined {
    return this.active;
  }

  private get bridged(): boolean {
    return this.active === 'bridged';
  }

  // ---- public entry points --------------------------------------------------------------------

  countCall(m: string): void {
    this.run(() => {
      const name = Names.method(m);
      if (this.bridged) return this.bridgeCount(this.bridgeCalls, name);
      if (!this.calls.has(name) && this.calls.size >= LIMITS.counts) return;
      if (!this.windowFrom) this.windowFrom = this.deps.now();
      this.calls.set(name, (this.calls.get(name) ?? 0) + 1);
      this.onActivity();
    });
  }

  reportError(m: string, c: string, error?: unknown): void {
    this.run(() => {
      // Inside a wrapper of a native SDK, this client's errors come from the wrapper layer.
      const event = errorEvent(
        m,
        c,
        sdkFrames(error, this.prefixes),
        this.bridged ? 'wrapper' : 'native',
      );
      if (this.bridged) return this.bridgeEvent(event);
      this.enqueue(event);
      this.onActivity();
    });
  }

  check(result: HealthCheckResult): void {
    this.run(() => {
      if (!result || typeof result !== 'object') return;
      if (this.bridged) return this.bridgeEvent(healthEvent(result));
      this.enqueue(healthEvent(result));
      this.onActivity();
    });
  }

  markDeprecated(m: string): void {
    this.run(() => {
      const name = Names.method(m);
      if (this.bridged) return this.bridgeCount(this.bridgeDeprecated, name);
      if (!this.deprecated.has(name) && this.deprecated.size >= LIMITS.counts) return;
      this.deprecated.set(name, (this.deprecated.get(name) ?? 0) + 1);
      this.onActivity();
    });
  }

  /** Upload now regardless of the 6-hour policy. Resolves when the attempt finishes; never rejects. */
  flush(options: { keepalive?: boolean } = {}): Promise<void> {
    return new Promise<void>((resolve) => {
      this.run(
        () => {
          if (this.bridged) {
            this.forward().then(resolve, resolve);
            return;
          }
          this.heartbeatIfDue();
          this.maybeUpload(true, options.keepalive ?? false).then(resolve, resolve);
        },
        () => resolve(),
      );
    });
  }

  /** Host opt-out, shared by every SDK. `false` deletes everything queued, immediately. */
  setHostEnabled(value: boolean): void {
    this.hostEnabled = value;
    this.safe(() => this.storage.setItem(HOST_ENABLED_KEY, String(value)));
    this.run(
      () => {
        const was = this.enabled;
        this.enabled = this.computeEnabled();
        if (was && !this.enabled) {
          this.queue = [];
          this.queueBytes = 0;
          this.calls.clear();
          this.deprecated.clear();
          this.windowFrom = 0;
          this.bridgeCalls.clear();
          this.bridgeDeprecated.clear();
          this.bridgeEvents = [];
        }
        if (!this.bridged) this.scheduleSave();
      },
      undefined,
      true,
    );
  }

  isEnabled(): boolean {
    return this.enabled && !this.stopped;
  }

  stop(): void {
    this.run(() => this.scheduleSave());
    this.stopped = true;
    this.unregisterPageHide?.();
  }

  log(message: string, error?: unknown): void {
    if (!this.config.debug) return;
    try {
      console.warn(`[HenosisInsights] ${message}`, error ?? '');
    } catch {}
  }

  // ---- internals -------------------------------------------------------------------------------

  private safe(fn: () => unknown): void {
    try {
      const r = fn();
      if (r && typeof (r as Promise<unknown>).then === 'function') {
        (r as Promise<unknown>).then(undefined, (e) => this.log('storage write failed', e));
      }
    } catch (e) {
      this.log('Insights call failed', e);
    }
  }

  /** Run `op` once state is loaded (or now, if it already is). Swallows everything. */
  private run(op: () => void, onDropped?: () => void, always = false): void {
    if (this.stopped) {
      onDropped?.();
      return;
    }
    const wrapped = () => {
      try {
        if (this.enabled || always) op();
        else onDropped?.();
      } catch (e) {
        this.log('Insights call failed', e);
        onDropped?.();
      }
    };
    if (this.ready) wrapped();
    else if (this.pending.length < 10_000) this.pending.push(wrapped);
    else onDropped?.();
  }

  private async read(key: string): Promise<string | undefined> {
    try {
      return (await this.storage.getItem(key)) ?? undefined;
    } catch (e) {
      this.log('storage read failed', e);
      return undefined;
    }
  }

  private async load(): Promise<void> {
    try {
      const host = await this.read(HOST_ENABLED_KEY);
      if (host === 'false') this.hostEnabled = false;
      this.active = await this.resolveMode();
      if (this.bridged) {
        // The native client owns storage, sampling, init and heartbeat. Register the wrapper now.
        this.enabled = this.config.bridge != null && this.computeEnabled();
        if (this.enabled) void this.forward();
        return;
      }
      const state = await this.read(`${this.scope}:state`);
      if (state) this.restore(state);
      this.enabled = this.computeEnabled();
      if (this.enabled) this.startSession();
      else this.scheduleSave();
    } catch (e) {
      this.log('Insights start failed', e);
    } finally {
      this.ready = true;
      const ops = this.pending.splice(0);
      for (const op of ops) op();
      if (this.enabled && !this.stopped && !this.bridged) {
        this.unregisterPageHide = this.deps.onPageHide?.(() => {
          void this.flush({ keepalive: true });
        });
      }
    }
  }

  private async resolveMode(): Promise<'direct' | 'bridged'> {
    const mode = this.config.mode ?? 'auto';
    const bridge = this.config.bridge;
    if (mode === 'direct') return 'direct';
    if (mode === 'bridged') return 'bridged';
    if (!bridge) return 'direct';
    for (const delay of this.deps.probeDelays ?? DEFAULT_PROBE_DELAYS) {
      if (delay > 0) await new Promise((r) => setTimeout(r, delay));
      if (await this.probe(bridge)) return 'bridged';
    }
    return 'direct';
  }

  private async probe(bridge: NonNullable<InsightsConfig['bridge']>): Promise<boolean> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const timeout = new Promise<boolean>((r) => {
        timer = setTimeout(() => r(false), 2000);
      });
      return (
        (await Promise.race([
          Promise.resolve().then(() =>
            bridge.isNativeRunning(this.config.writeKey, this.config.sdkName),
          ),
          timeout,
        ])) === true
      );
    } catch (e) {
      this.log('bridge probe failed', e);
      return false;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  private bridgeCount(into: Map<string, number>, name: string): void {
    if (!into.has(name) && into.size >= LIMITS.counts) return;
    into.set(name, Math.min(1_000_000, (into.get(name) ?? 0) + 1));
    this.scheduleForward();
  }

  private bridgeEvent(event: Json): void {
    if (this.bridgeEvents.length >= LIMITS.eventsPerBatch) this.bridgeEvents.shift();
    this.bridgeEvents.push(event);
    this.scheduleForward();
  }

  private scheduleForward(): void {
    if (this.forwardScheduled) return;
    this.forwardScheduled = true;
    setTimeout(() => {
      this.forwardScheduled = false;
      void this.forward();
    }, 0);
  }

  /** Sends what has accumulated (or just the wrapper registration) to the native client. */
  private async forward(): Promise<void> {
    const bridge = this.config.bridge;
    if (!bridge || !this.enabled || this.stopped) return;
    const kind = { ...this.deps.platform, ...(this.config.platform ?? {}) }.wrapperKind;
    const version = Names.version(this.config.wrapperVersion ?? this.config.sdkVersion);
    const message = JSON.stringify({
      bridge: 1,
      writeKey: this.config.writeKey,
      sdkName: this.config.sdkName,
      wrapper: {
        kind: BRIDGE_WRAPPER_KINDS.has(kind) ? kind : 'other',
        ...(version ? { version } : {}),
      },
      client: { lang: 'ts', version: CLIENT_VERSION },
      calls: Object.fromEntries(this.bridgeCalls),
      deprecated: Object.fromEntries(this.bridgeDeprecated),
      events: this.bridgeEvents,
    });
    this.bridgeCalls = new Map();
    this.bridgeDeprecated = new Map();
    this.bridgeEvents = [];
    try {
      await bridge.send(message);
    } catch (e) {
      // Never fall back to sending directly: that would make a second stream.
      this.log('bridge send failed', e);
    }
  }

  private restore(raw: string): void {
    try {
      const s = JSON.parse(raw) as Partial<State>;
      for (const e of Array.isArray(s.queue) ? s.queue : [])
        if (typeof e === 'string') this.offer(e);
      this.calls = new Map(
        (s.calls ?? []).filter((x) => typeof x?.[0] === 'string' && Number.isFinite(x[1])),
      );
      this.deprecated = new Map(
        (s.deprecated ?? []).filter((x) => typeof x?.[0] === 'string' && Number.isFinite(x[1])),
      );
      this.windowFrom = Number(s.windowFrom) || 0;
      this.lastUploadAt = Number(s.lastUploadAt) || 0;
      this.heartbeatDay = typeof s.heartbeatDay === 'string' ? s.heartbeatDay : '';
      this.sample = typeof s.sample === 'number' ? s.sample : undefined;
    } catch (e) {
      this.log('stored state unreadable; starting fresh', e);
    }
  }

  private computeEnabled(): boolean {
    if (!this.hostEnabled || this.deps.envOptOut()) return false;
    if (this.bridged) return true; // sampling is the native client's decision
    const rate = Math.min(1, Math.max(0, Number(this.config.sampleRate ?? 1)));
    if (!(rate > 0)) return false;
    if (rate >= 1) return true;
    if (this.sample === undefined) {
      this.sample = this.deps.random();
      this.scheduleSave();
    }
    return this.sample < rate;
  }

  private startSession(): void {
    const checks: HealthCheckResult[] = [
      this.customer.missing
        ? { name: 'customer_key_format', ok: false, detail: 'missing' }
        : {
            name: 'customer_key_format',
            ok: this.customer.ok,
            ...(this.customer.ok ? {} : { detail: 'invalid_format' as const }),
          },
    ];
    this.enqueue(initEvent(checks));
    for (const c of checks) if (!c.ok) this.enqueue(healthEvent(c));
    this.heartbeatIfDue();
    void this.maybeUpload(false, false);
    this.scheduleSave();
  }

  private offer(json: string): void {
    const cost = json.length + 1;
    if (cost > QUEUE_CAP_BYTES) return;
    while (this.queueBytes + cost > QUEUE_CAP_BYTES && this.queue.length) {
      this.queueBytes -= (this.queue.shift() as string).length + 1;
    }
    this.queue.push(json);
    this.queueBytes += cost;
  }

  private enqueue(event: Json): void {
    this.offer(JSON.stringify(event));
  }

  private heartbeatIfDue(): void {
    const today = utcDay(this.deps.now());
    if (this.heartbeatDay === today) return;
    this.enqueue(heartbeatEvent());
    this.heartbeatDay = today;
  }

  private onActivity(): void {
    this.heartbeatIfDue();
    void this.maybeUpload(false, false);
    this.scheduleSave();
  }

  private hasPending(): boolean {
    return this.queue.length > 0 || this.calls.size > 0 || this.deprecated.size > 0;
  }

  private shouldUpload(now: number, force: boolean): boolean {
    if (!this.hasPending() || now < this.nextAttemptAt) return false;
    return force || now - this.lastUploadAt >= MAX_INTERVAL_MS;
  }

  private flushCounters(now: number): void {
    if (this.calls.size) {
      this.enqueue(callsEvent(this.windowFrom || now, now, this.calls));
      this.calls = new Map();
      this.windowFrom = 0;
    }
    if (this.deprecated.size) {
      for (const [m, n] of this.deprecated) this.enqueue(deprecatedEvent(m, n));
      this.deprecated = new Map();
    }
  }

  private envelope(sentAt: number, events: string[]): string {
    const p = { ...this.deps.platform, ...(this.config.platform ?? {}) };
    const app: Json = {};
    const appId = Names.appId(p.appId);
    if (appId) app.id = appId;
    const appVersion = Names.version(p.appVersion);
    if (appVersion) app.version = appVersion;
    const device: Json = { os: p.os };
    const osVersion = Names.version(p.osVersion);
    if (osVersion) device.osVersion = osVersion;
    if (p.deviceClass) device.class = p.deviceClass;
    const wrapper: Json = { kind: p.wrapperKind };
    const wrapperVersion = Names.version(this.config.wrapperVersion ?? this.config.sdkVersion);
    if (wrapperVersion && p.wrapperKind !== 'native') wrapper.version = wrapperVersion;
    const head = JSON.stringify({
      schema: 1,
      writeKey: this.config.writeKey,
      client: { lang: 'ts', version: CLIENT_VERSION, mode: 'direct' },
      sdk: {
        name: Names.sdkName(this.config.sdkName),
        version: Names.version(this.config.sdkVersion) ?? '0',
      },
      wrapper,
      customerKey: this.customer.key,
      app,
      device,
      sentAt: iso(sentAt),
    });
    // Events are already serialised; splice them in rather than re-parsing.
    return `${head.slice(0, -1)},"events":[${events.join(',')}]}`;
  }

  private maybeUpload(force: boolean, keepalive: boolean): Promise<void> {
    if (this.uploading)
      return force ? this.uploading.then(() => this.maybeUpload(true, keepalive)) : this.uploading;
    const now = this.deps.now();
    if (!this.shouldUpload(now, force)) return Promise.resolve();
    this.flushCounters(now);
    this.uploading = this.uploadLoop(now, keepalive).finally(() => {
      this.uploading = undefined;
      this.scheduleSave();
    });
    return this.uploading;
  }

  private async uploadLoop(now: number, keepalive: boolean): Promise<void> {
    try {
      while (this.queue.length && this.enabled && !this.stopped) {
        let take = Math.min(this.queue.length, LIMITS.eventsPerBatch);
        let batch = this.envelope(now, this.queue.slice(0, take));
        // A page-hide upload must fit the browser's keepalive limit; send what fits.
        while (keepalive && batch.length > KEEPALIVE_MAX_BYTES && take > 1) {
          take = Math.max(1, Math.floor(take / 2));
          batch = this.envelope(now, this.queue.slice(0, take));
        }
        const sent = this.queue.slice(0, take);
        const result = await this.deps.transport.send(
          this.config.endpoint ?? DEFAULT_ENDPOINT,
          this.config.writeKey,
          batch,
          { keepalive },
        );
        if (result.kind === 'retry') {
          this.nextAttemptAt = this.deps.now() + RETRY_BACKOFF_MS;
          this.log(`upload deferred (${result.status ?? 'network'})`);
          return;
        }
        // Accepted, or rejected (the ingest will never take these): either way they leave the queue.
        if (this.queue[0] === sent[0]) {
          this.queue.splice(0, take);
          this.queueBytes = this.queue.reduce((n, e) => n + e.length + 1, 0);
        }
        if (result.kind === 'accepted') {
          this.lastUploadAt = now;
          this.nextAttemptAt = 0;
        } else {
          this.log(`batch rejected with ${result.status}; ${take} events dropped`);
        }
      }
    } catch (e) {
      this.nextAttemptAt = this.deps.now() + RETRY_BACKOFF_MS;
      this.log('upload failed', e);
    }
  }

  private scheduleSave(): void {
    if (this.saveScheduled) return;
    this.saveScheduled = true;
    Promise.resolve().then(() => {
      this.saveScheduled = false;
      const key = `${this.scope}:state`;
      if (!this.enabled) {
        // Keep nothing but the sampling draw, so a sampled-out install stays sampled out.
        const sample = this.sample;
        this.safe(() =>
          sample === undefined
            ? this.storage.removeItem(key)
            : this.storage.setItem(key, JSON.stringify({ sample })),
        );
        return;
      }
      const state: State = {
        queue: this.queue,
        calls: [...this.calls],
        deprecated: [...this.deprecated],
        windowFrom: this.windowFrom,
        lastUploadAt: this.lastUploadAt,
        heartbeatDay: this.heartbeatDay,
        ...(this.sample === undefined ? {} : { sample: this.sample }),
      };
      this.safe(() => this.storage.setItem(key, JSON.stringify(state)));
    });
  }
}
