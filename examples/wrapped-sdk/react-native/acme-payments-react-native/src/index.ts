/**
 * React Native wrapper of the Acme Payments Android SDK.
 *
 * The native SDK already reports through Henosis Insights. This wrapper adds the TypeScript client
 * in `auto` mode with a bridge over its own TurboModule, so the app still sends exactly one stream
 * for `acme-payments`, tagged `wrapper.kind = react-native`, and errors caught here are tagged
 * `layer: wrapper`.
 */
import { type BridgeTransport, HenosisInsights, hashCustomerKey, type InsightsClient } from '@devstrike/insights-js';
import Native from './NativeAcmePayments';

/** This wrapper's version (reported as `wrapper.version`). */
export const VERSION = '0.9.4';
/** Version of the native Acme Payments SDK this wrapper ships with. */
export const NATIVE_VERSION = '4.2.0';
/** Public fixture key; a real SDK ships the write key from its Insights dashboard. */
export const DEFAULT_WRITE_KEY = 'hi_test_0123456789abcdef0123456789abcdef';

const bridge: BridgeTransport = {
  isNativeRunning: (writeKey, sdkName) => Native.insightsIsRunning(writeKey, sdkName),
  send: (message) => Native.insightsForward(message),
};

let insights: InsightsClient | undefined;

export const AcmePayments = {
  async init(options: { apiKey: string; insightsWriteKey?: string; insightsEndpoint?: string }): Promise<void> {
    const writeKey = options.insightsWriteKey ?? DEFAULT_WRITE_KEY;
    await Native.init(options.apiKey, writeKey, options.insightsEndpoint ?? '');
    // Started after the native SDK's init, so `auto` mode finds the native client and bridges.
    insights = HenosisInsights.start({
      writeKey,
      sdkName: 'acme-payments',
      sdkVersion: NATIVE_VERSION,
      wrapperVersion: VERSION,
      customerKey: hashCustomerKey(options.apiKey),
      sdkPackagePrefixes: ['@acme/payments-react-native/'],
      ...(options.insightsEndpoint ? { endpoint: options.insightsEndpoint } : {}),
      bridge,
    });
  },

  /** Validation happens here, in the wrapper; the payment itself in the native SDK. */
  async charge(amountMinor: number, currency: string): Promise<string> {
    if (!/^[A-Z]{3}$/.test(currency)) {
      insights?.reportError('charge', 'INVALID_CURRENCY', new Error(`currency must be ISO 4217, got ${currency}`));
      return 'invalid_currency';
    }
    return Native.charge(amountMinor, currency);
  },

  /** A JS-only helper the native SDK knows nothing about, so the wrapper counts it. */
  formatAmount(amountMinor: number, currency: string): string {
    insights?.countCall('formatAmount');
    return `${currency} ${(amountMinor / 100).toFixed(2)}`;
  },

  /** @deprecated Use charge(). The native SDK records the deprecated use. */
  chargeLegacy(amountMinor: number): Promise<string> {
    return Native.chargeLegacy(amountMinor);
  },

  /** Sends what is queued now, from both layers. */
  async flushTelemetry(): Promise<void> {
    await insights?.flush();
    Native.flush();
  },

  /** `bridged` once the TS client found the native one. */
  get insightsMode(): 'direct' | 'bridged' | undefined {
    return insights?.activeMode;
  },
};
