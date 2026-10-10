/** Fixed values a health check may attach. No free text, so no end-user data can ride along. */
export type HealthDetail =
  | 'missing'
  | 'invalid_format'
  | 'not_initialized'
  | 'wrong_thread'
  | 'permission_missing'
  | 'unsupported_platform'
  | 'other';

export interface HealthCheckResult {
  readonly name: string;
  readonly ok: boolean;
  readonly detail?: HealthDetail;
}

export type WrapperKind = 'native' | 'flutter' | 'react-native' | 'web' | 'node' | 'other';
export type DeviceOs = 'android' | 'ios' | 'web' | 'macos' | 'windows' | 'linux' | 'node' | 'other';
export type DeviceClass = 'phone' | 'tablet' | 'desktop';

/** What the client reports about where it runs. Unknown fields stay undefined and are omitted. */
export interface PlatformInfo {
  wrapperKind: WrapperKind;
  os: DeviceOs;
  osVersion?: string | undefined;
  deviceClass?: DeviceClass | undefined;
  /** Bundle id / package name on mobile, hostname on web, package name on Node. */
  appId?: string | undefined;
  appVersion?: string | undefined;
}

/**
 * Pluggable persistence. `localStorage` (web) and React Native's AsyncStorage fit as-is.
 * Without one, the queue and state live in memory for the life of the process.
 */
export interface InsightsStorage {
  getItem(key: string): string | null | undefined | Promise<string | null | undefined>;
  setItem(key: string, value: string): void | Promise<void>;
  removeItem(key: string): void | Promise<void>;
}

/** How the client reports. See docs/bridge-protocol.md. */
export type InsightsMode = 'auto' | 'direct' | 'bridged';

/**
 * Supplied by a wrapper package (a React Native module around a native SDK) over its own
 * TurboModule. The native side calls `HenosisInsights.bridge.isRunning` / `.receive`.
 */
export interface BridgeTransport {
  /** Whether the native Insights client runs for this write key and SDK. */
  isNativeRunning(writeKey: string, sdkName: string): boolean | Promise<boolean>;
  /** Deliver one bridge message (JSON, schema/bridge-v1.json) to the native client. */
  send(message: string): void | Promise<void>;
}

export interface InsightsConfig {
  /** Identifies the vendor's SDK project (`hi_live_…` / `hi_test_…`). */
  writeKey: string;
  /** The SDK's package name as registered in the dashboard, e.g. `acme-chat`. */
  sdkName: string;
  sdkVersion: string;
  /** A HASH of the vendor's customer key, never the raw key. Non-hash values are hashed and flagged. */
  customerKey: string;
  /**
   * Path fragments that identify the SDK's own source in stack traces, e.g. `['@acme/chat/']`.
   * Only frames whose file path contains one of these are reported. Empty means no frames.
   */
  sdkPackagePrefixes?: string[];
  /** Version of the wrapper package; defaults to `sdkVersion` (for a pure package they are the same). */
  wrapperVersion?: string;
  /** Fraction of installs (or page loads without storage) that report, 0–1. */
  sampleRate?: number;
  endpoint?: string;
  storage?: InsightsStorage;
  /**
   * Override detected platform fields. Useful where detection is impossible, for example
   * `{ appId: 'com.example.app' }` from a React Native package that can read it natively.
   */
  platform?: Partial<PlatformInfo>;
  /** Log Insights' own failures to the console. Never logs payload contents. */
  debug?: boolean;
  /** `auto` by default: bridged inside a wrapper whose native SDK runs Insights, otherwise direct. */
  mode?: InsightsMode;
  /** The wrapper's channel to its native SDK. Only wrappers of a native SDK set this. */
  bridge?: BridgeTransport;
}
