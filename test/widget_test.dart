import 'package:flutter_test/flutter_test.dart';

import 'package:bbreplace/main.dart';

void main() {
  testWidgets('renders home title', (tester) async {
    await tester.pumpWidget(const BabyRepeaterApp());

    expect(find.text('宝宝复读机'), findsOneWidget);
    expect(find.text('开始监听'), findsOneWidget);
  });
}
