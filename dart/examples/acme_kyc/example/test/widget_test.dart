import 'package:acme_kyc_example/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows a status line', (tester) async {
    await tester.pumpWidget(const VerifyApp());
    expect(find.byType(VerifyApp), findsOneWidget);
  });
}
