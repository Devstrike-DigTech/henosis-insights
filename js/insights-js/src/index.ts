/**
 * Henosis Insights for JavaScript SDKs: integration telemetry with no end-user data.
 *
 * A vendor SDK calls `HenosisInsights.start(config)` once and keeps the returned client. Every call
 * returns immediately and never throws or rejects; an Insights failure can never break the SDK or
 * the app. Hosts opt out with `HenosisInsights.setEnabled(false)`, `HENOSIS_INSIGHTS_DISABLED=1`
 * (Node) or `globalThis.HENOSIS_INSIGHTS_DISABLED = true` (web), for every SDK in the app.
 */
import { CLIENT_VERSION, Engine, type EngineDeps } from './engine.js';
import { detectPlatform, envOptOut, onPageHide } from './platform.js';
import { fetchTransport } from './transport.js';
import type { HealthCheckResult, InsightsConfig } from './types.js';

export { HealthCheck } from './health.js';

import { sha256Hex } from './sha256.js';

/**
 * SHA-256 hex of a customer's API key, for `customerKey`. Synchronous and dependency-free, so it
 * also works where `crypto.subtle` does not (React Native / Hermes). The raw key never leaves the device.
 */
export function hashCustomerKey(rawKey: string): string {
  return sha256Hex(String(rawKey ?? ''));
}
export type {
  BridgeTransport,
  DeviceClass,
  DeviceOs,
  HealthCheckResult,
  HealthDetail,
  InsightsConfig,
  InsightsMode,
  InsightsStorage,
  PlatformInfo,
  WrapperKind,
} from './types.js';

/** Handle for one SDK's stream. Prefer it over the static calls when your SDK may share an app. */
export class InsightsClient {
  /** @internal */
  constructor(private readonly engine: Engine | undefined) {}

  countCall(method: string): void {
    guard(() => this.engine?.countCall(method));
  }

  /** Only frames from `sdkPackagePrefixes` are kept; the error's message is never sent. */
  reportError(method: string, code: string, error?: unknown): void {
    guard(() => this.engine?.reportError(method, code, error));
  }

  check(result: HealthCheckResult): void {
    guard(() => this.engine?.check(result));
  }

  markDeprecated(method: string): void {
    guard(() => this.engine?.markDeprecated(method));
  }

  /** Upload now. Resolves when the attempt finishes; never rejects. */
  flush(): Promise<void> {
    try {
      return this.engine?.flush().catch(() => undefined) ?? Promise.resolve();
    } catch {
      return Promise.resolve();
    }
  }

  isEnabled(): boolean {
    try {
      return this.engine?.isEnabled() ?? false;
    } catch {
      return false;
    }
  }

  /** `direct` or `bridged` once decided (undefined while `auto` mode is still asking the native client). */
  get activeMode(): 'direct' | 'bridged' | undefined {
    try {
      return this.engine?.activeMode;
    } catch {
      return undefined;
    }
  }

  /** @internal */
  get _engine(): Engine | undefined {
    return this.engine;
  }
}

function guard(fn: () => void): void {
  try {
    fn();
  } catch {}
}

const NOOP = new InsightsClient(undefined);

interface Registry {
  clients: Map<string, InsightsClient>;
  hostEnabled: boolean | undefined;
}

// Shared through globalThis so two bundled copies of this package agree on routing and opt-out.
function registry(): Registry {
  const g = globalThis as unknown as Record<symbol, Registry | undefined>;
  const key = Symbol.for('com.devstrike.henosis.insights.registry');
  let r = g[key];
  if (!r) {
    r = { clients: new Map(), hostEnabled: undefined };
    g[key] = r;
  }
  return r;
}

function single(): InsightsClient {
  try {
    const { clients } = registry();
    return clients.size === 1 ? (clients.values().next().value ?? NOOP) : NOOP;
  } catch {
    return NOOP;
  }
}

/** @internal Tests inject a transport, clock and platform. */
export function startWith(config: InsightsConfig, deps: Partial<EngineDeps> = {}): InsightsClient {
  try {
    const r = registry();
    const key = `${config?.writeKey}|${config?.sdkName}`;
    const existing = r.clients.get(key);
    if (existing) return existing;
    const engine = new Engine(config, {
      transport: deps.transport ?? fetchTransport,
      platform: deps.platform ?? detectPlatform(),
      now: deps.now ?? Date.now,
      random: deps.random ?? Math.random,
      envOptOut: deps.envOptOut ?? envOptOut,
      onPageHide: deps.onPageHide ?? onPageHide,
      ...(deps.probeDelays ? { probeDelays: deps.probeDelays } : {}),
    });
    if (r.hostEnabled !== undefined) engine.setHostEnabled(r.hostEnabled);
    const client = new InsightsClient(engine);
    r.clients.set(key, client);
    return client;
  } catch {
    return NOOP;
  }
}

export const HenosisInsights = {
  VERSION: CLIENT_VERSION,

  /** Start reporting for one SDK and return its handle. Same write key + SDK name returns the same handle. */
  start(config: InsightsConfig): InsightsClient {
    return startWith(config);
  },

  countCall: (method: string): void => single().countCall(method),
  reportError: (method: string, code: string, error?: unknown): void =>
    single().reportError(method, code, error),
  check: (result: HealthCheckResult): void => single().check(result),
  markDeprecated: (method: string): void => single().markDeprecated(method),
  flush: (): Promise<void> => single().flush(),
  /** False when no SDK, or more than one, is running (use the handle then). */
  isEnabled: (): boolean => single().isEnabled(),

  /** Host-app opt-out for every SDK in the app; `false` deletes anything queued. Remembered via storage. */
  setEnabled(enabled: boolean): void {
    guard(() => {
      const r = registry();
      r.hostEnabled = !!enabled;
      for (const c of r.clients.values()) c._engine?.setHostEnabled(!!enabled);
    });
  },

  /** Stop every stream (tests, explicit shutdown). Queued events stay in storage. */
  stop(): void {
    guard(() => {
      const r = registry();
      for (const c of r.clients.values()) c._engine?.stop();
      r.clients.clear();
    });
  },
} as const;

/** @internal */
export function _resetForTests(): void {
  HenosisInsights.stop();
  registry().hostEnabled = undefined;
}
