import 'package:insights_dart/insights_dart.dart';

/// Thrown when a document upload is rejected.
class UploadException implements Exception {
  UploadException(this.message);
  final String message;
}

abstract final class AcmeKyc {
  static const String version = '1.8.0';

  /// Public fixture key; a real SDK ships the write key from its Insights dashboard.
  static const String defaultWriteKey = 'hi_test_0123456789abcdef0123456789abcdef';

  static InsightsClient? _insights;
  static String? _apiKey;

  static void init({
    required String apiKey,
    String insightsWriteKey = defaultWriteKey,
    String insightsEndpoint = InsightsConfig.defaultEndpoint,
    InsightsStorage? storage,
  }) {
    _apiKey = apiKey;
    _insights = HenosisInsights.start(InsightsConfig(
      writeKey: insightsWriteKey,
      sdkName: 'acme_kyc',
      sdkVersion: version,
      // Never the raw key: a hash identifies the customer without exposing the key.
      customerKey: HenosisInsights.hashCustomerKey(apiKey),
      sdkPackagePrefixes: const ['package:acme_kyc/'],
      endpoint: insightsEndpoint,
      storage: storage,
    ));
  }

  static String startVerification() {
    _insights
      ?..countCall('startVerification')
      ..check(HealthCheck.initBeforeUse(_apiKey != null));
    return 'session-1';
  }

  static bool uploadDocument(String session, List<int> bytes) {
    _insights?.countCall('uploadDocument');
    try {
      _validate(bytes);
      return true;
    } catch (e, st) {
      // The exception text may describe the document; Insights only ever sends SDK frames.
      _insights?.reportError('uploadDocument', 'DOCUMENT_REJECTED', e, st);
      return false;
    }
  }

  @Deprecated('Use startVerification()')
  static String startLegacy() {
    _insights?.markDeprecated('startLegacy');
    return startVerification();
  }

  /// Sends whatever Insights has queued now.
  static Future<void> flushTelemetry() => _insights?.flush() ?? Future<void>.value();

  static void _validate(List<int> bytes) {
    if (bytes.isEmpty) throw UploadException('empty passport scan for applicant');
  }
}
