// Example Flutter app using the pure-Dart acme_kyc SDK, which reports through Henosis Insights.
// On launch it runs a short scenario and flushes, so the events can be checked in ClickHouse.
// The app itself knows nothing about Insights.
import 'package:acme_kyc/acme_kyc.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

// The local ingest from the cloud repo. From the Android emulator the host is 10.0.2.2.
const _endpoint = String.fromEnvironment(
  'INSIGHTS_ENDPOINT',
  defaultValue: kIsWeb ? 'http://127.0.0.1:8787/v1/batches' : 'http://10.0.2.2:8787/v1/batches',
);
const _apiKey = String.fromEnvironment('ACME_API_KEY', defaultValue: 'sk_test_verify_app');

void main() => runApp(const VerifyApp());

class VerifyApp extends StatefulWidget {
  const VerifyApp({super.key});

  @override
  State<VerifyApp> createState() => _VerifyAppState();
}

class _VerifyAppState extends State<VerifyApp> {
  String _status = 'starting…';

  @override
  void initState() {
    super.initState();
    _run();
  }

  Future<void> _run() async {
    AcmeKyc.init(apiKey: _apiKey, insightsEndpoint: _endpoint);
    final session = AcmeKyc.startVerification();
    AcmeKyc.uploadDocument(session, [1, 2, 3]);
    AcmeKyc.uploadDocument(session, const []); // rejected: reported with SDK frames only
    // ignore: deprecated_member_use
    AcmeKyc.startLegacy();
    await AcmeKyc.flushTelemetry();
    if (mounted) setState(() => _status = 'telemetry flushed');
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Verify',
        home: Scaffold(
          appBar: AppBar(title: const Text('Acme KYC example')),
          body: Center(child: Text(_status, key: const Key('status'))),
        ),
      );
}
