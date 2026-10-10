/**
 * Example React Native app using the pure-JS @acme/chat SDK, which reports through Henosis Insights.
 * On launch it runs a short scenario and flushes, so the events can be checked in ClickHouse.
 * The app itself knows nothing about Insights.
 */
import { AcmeChat } from '@acme/chat';
import { useEffect, useState } from 'react';
import { StatusBar, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

// From the Android emulator, 10.0.2.2 is the host machine (the local ingest in the cloud repo).
const INGEST = 'http://10.0.2.2:8787/v1/batches';

function App() {
  const [status, setStatus] = useState('starting…');

  useEffect(() => {
    AcmeChat.init({ apiKey: 'sk_test_rn_messenger', insightsEndpoint: INGEST });
    AcmeChat.connect();
    AcmeChat.sendMessage('general', 'hello from React Native');
    AcmeChat.sendMessage('', 'this text must never reach Insights');
    AcmeChat.sendMessageSync('general', 'legacy');
    AcmeChat.flushTelemetry().then(() => setStatus('telemetry flushed'));
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.container}>
        <View>
          <Text style={styles.title}>Acme Chat example</Text>
          <Text testID="status">{status}</Text>
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 24 },
  title: { fontSize: 22, fontWeight: '600', marginBottom: 12 },
});

export default App;
