// Example app for the acme_payments_flutter wrapper (Henosis Insights M4: bridged mode).
// On launch it uses the wrapper once in every way that matters for telemetry, then flushes:
// a native call, a native-layer error, a wrapper-layer error, a Dart-only helper, a deprecated call.
import 'package:acme_payments_flutter/acme_payments_flutter.dart';
import 'package:flutter/material.dart';

// The local ingest from the cloud repo, as seen from the Android emulator.
const _endpoint = String.fromEnvironment('INSIGHTS_ENDPOINT', defaultValue: 'http://10.0.2.2:8787/v1/batches');
const _apiKey = String.fromEnvironment('ACME_API_KEY', defaultValue: 'sk_test_flutter_wrapped');

void main() => runApp(const ShopApp());

class ShopApp extends StatefulWidget {
  const ShopApp({super.key});

  @override
  State<ShopApp> createState() => _ShopAppState();
}

class _ShopAppState extends State<ShopApp> {
  final List<String> _log = ['starting…'];

  @override
  void initState() {
    super.initState();
    _run();
  }

  void _say(String line) => setState(() => _log.add(line));

  Future<void> _run() async {
    await AcmePaymentsFlutter.init(apiKey: _apiKey, insightsEndpoint: _endpoint);
    _say('charge: ${await AcmePaymentsFlutter.charge(1500, 'NGN')}');
    _say('declined: ${await AcmePaymentsFlutter.charge(-1, 'NGN')}'); // native-layer error
    _say('bad currency: ${await AcmePaymentsFlutter.charge(100, 'naira')}'); // wrapper-layer error
    _say(AcmePaymentsFlutter.formatAmount(1500, 'NGN'));
    // ignore: deprecated_member_use
    _say('legacy: ${await AcmePaymentsFlutter.chargeLegacy(200)}');
    await AcmePaymentsFlutter.flushTelemetry();
    _say('insights mode: ${AcmePaymentsFlutter.insightsMode?.name}');
    _say('telemetry flushed');
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Shop',
        home: Scaffold(
          appBar: AppBar(title: const Text('Acme Payments (Flutter wrapper)')),
          body: ListView(padding: const EdgeInsets.all(16), children: [for (final l in _log) Text(l)]),
        ),
      );
}
