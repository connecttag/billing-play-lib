# billing-play-lib

مكتبة أندرويد عامة ومستقلة لإدارة وتجريد عمليات الفوترة والاشتراكات عبر **Google Play Billing Library (8.3.0 / 7.x)** باستخدام Kotlin Coroutines و StateFlow.

## المميزات
- تغليف كامل وآمن لـ `BillingClient` بأحدث المعايير المتوافقة مع Google Play (دعم One-time Products والاشتراكات).
- تجريد كامل لعمليات الاتصال المتزامنة عبر Kotlin `Mutex` والـ Coroutines.
- واجهات عامة (`BillingEngine`) ونماذج بيانات نظيفة مستقلة تماماً عن أي تطبيق.
- محرك محاكاة كامل (`FakeBillingEngine`) للاختبارات والمطورين.
- دعم التحقق المخصص وتأكيد الشراء التلقائي عبر `PurchaseVerificationHandler`.
