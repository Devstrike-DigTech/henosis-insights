// Compiled to JavaScript by tool/check_size.dart to measure what the client adds to a web app.
import 'package:insights_dart/insights_dart.dart';

void main() {
  final insights = HenosisInsights.start(const InsightsConfig(
    writeKey: 'hi_test_0123456789abcdef0123456789abcdef',
    sdkName: 'probe',
    sdkVersion: '1.0.0',
    customerKey: 'probe-customer-key-hash',
  ));
  insights
    ..countCall('a')
    ..reportError('a', 'B', StateError('x'), StackTrace.current)
    ..check(HealthCheck.initBeforeUse(true))
    ..markDeprecated('c');
  insights.flush();
}
