/**
 * Example app for the @acme/payments-react-native wrapper (Henosis Insights M4: bridged mode).
 * On launch it uses the wrapper once in every way that matters for telemetry, then flushes:
 * a native call, a native-layer error, a wrapper-layer error, a JS-only helper, a deprecated call.
 */
import { AcmePayments } from '@acme/payments-react-native';
import { useEffect, useState } from 'react';
import { ScrollView, StatusBar, StyleSheet, Text } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

// From the Android emulator, 10.0.2.2 is the host machine (the local ingest in the cloud repo).
const INGEST = 'http://10.0.2.2:8787/v1/batches';

function App() {
  const [log, setLog] = useState<string[]>(['starting…']);

  useEffect(() => {
    const say = (line: string) => setLog((l) => [...l, line]);
    (async () => {
      await AcmePayments.init({ apiKey: 'sk_test_rn_wrapped', insightsEndpoint: INGEST });
      say(`charge: ${await AcmePayments.charge(1500, 'NGN')}`);
      say(`declined: ${await AcmePayments.charge(-1, 'NGN')}`); // native-layer error
      say(`bad currency: ${await AcmePayments.charge(100, 'naira')}`); // wrapper-layer error
      say(AcmePayments.formatAmount(1500, 'NGN'));
      say(`legacy: ${await AcmePayments.chargeLegacy(200)}`);
      await AcmePayments.flushTelemetry();
      say(`insights mode: ${AcmePayments.insightsMode}`);
      say('telemetry flushed');
    })();
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.container}>
        <Text style={styles.title}>Acme Payments (React Native wrapper)</Text>
        <ScrollView>
          {log.map((l, i) => (
            <Text key={String(i)}>{l}</Text>
          ))}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 24 },
  title: { fontSize: 20, fontWeight: '600', marginBottom: 12 },
});

export default App;
