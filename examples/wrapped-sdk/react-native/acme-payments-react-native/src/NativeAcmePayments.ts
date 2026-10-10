// TurboModule spec. React Native codegen turns this into NativeAcmePaymentsSpec on Android.
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  init(apiKey: string, insightsWriteKey: string, insightsEndpoint: string): Promise<void>;
  charge(amountMinor: number, currency: string): Promise<string>;
  chargeLegacy(amountMinor: number): Promise<string>;
  flush(): void;
  // Henosis Insights bridge (docs/bridge-protocol.md)
  insightsIsRunning(writeKey: string, sdkName: string): boolean;
  insightsForward(message: string): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('AcmePayments');
