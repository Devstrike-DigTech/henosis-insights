import 'package:acme_payments_flutter_example/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('builds', (tester) async {
    await tester.pumpWidget(const ShopApp());
    expect(find.byType(ShopApp), findsOneWidget);
  });
}
