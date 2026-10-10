declare module '@acme/chat' {
  import type { InsightsStorage, PlatformInfo } from '@devstrike/insights-js';
  export const AcmeChat: {
    VERSION: string;
    init(options: {
      apiKey: string;
      insightsWriteKey?: string;
      insightsEndpoint?: string;
      storage?: InsightsStorage;
      platform?: Partial<PlatformInfo>;
    }): void;
    connect(): boolean;
    sendMessage(room: string, text: string): boolean;
    sendMessageSync(room: string, text: string): boolean;
    flushTelemetry(): Promise<void>;
  };
}
