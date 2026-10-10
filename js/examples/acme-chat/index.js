// @acme/chat: a fictional vendor SDK. Pure JavaScript, no native code, so it runs unchanged in
// React Native apps, web pages and Node services. It shows the three things a vendor adds:
// one `start` in init, a `countCall` per public method, and `reportError` / `check` where the SDK
// already detects problems.
import { HealthCheck, HenosisInsights, hashCustomerKey } from '@devstrike/insights-js';

export const VERSION = '0.9.4';
// Public fixture key; a real SDK ships the write key from its Insights dashboard.
export const DEFAULT_WRITE_KEY = 'hi_test_0123456789abcdef0123456789abcdef';

let insights;
let apiKey;

export const AcmeChat = {
  VERSION,

  /**
   * @param {{ apiKey: string, insightsWriteKey?: string, insightsEndpoint?: string,
   *           storage?: import('@devstrike/insights-js').InsightsStorage,
   *           platform?: Partial<import('@devstrike/insights-js').PlatformInfo> }} options
   */
  init(options) {
    apiKey = options.apiKey;
    insights = HenosisInsights.start({
      writeKey: options.insightsWriteKey ?? DEFAULT_WRITE_KEY,
      sdkName: 'acme-chat',
      sdkVersion: VERSION,
      // Never the raw key: a hash identifies the customer without exposing the key.
      customerKey: hashCustomerKey(options.apiKey),
      // Installed: node_modules/@acme/chat/...; in this repo: examples/acme-chat/...
      sdkPackagePrefixes: ['@acme/chat/', 'acme-chat/'],
      ...(options.insightsEndpoint ? { endpoint: options.insightsEndpoint } : {}),
      ...(options.storage ? { storage: options.storage } : {}),
      ...(options.platform ? { platform: options.platform } : {}),
    });
  },

  connect() {
    insights?.countCall('connect');
    insights?.check(HealthCheck.initBeforeUse(apiKey !== undefined));
    return apiKey !== undefined;
  },

  sendMessage(room, text) {
    insights?.countCall('sendMessage');
    try {
      return deliver(room, text);
    } catch (error) {
      // The message text is in the error, but Insights never sends messages, only SDK frames.
      insights?.reportError('sendMessage', 'ROOM_NOT_FOUND', error);
      return false;
    }
  },

  /** @deprecated Use sendMessage(). */
  sendMessageSync(room, text) {
    insights?.markDeprecated('sendMessageSync');
    return AcmeChat.sendMessage(room, text);
  },

  /** Sends whatever Insights has queued now. */
  flushTelemetry() {
    return insights?.flush() ?? Promise.resolve();
  },
};

function deliver(room, text) {
  if (!room) throw new Error(`room is required to send "${text}"`);
  return true;
}
