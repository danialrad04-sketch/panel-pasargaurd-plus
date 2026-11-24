---
name: billing-integrity
description: "Develop or review deposit management, manual receipt review, billing orders, renewals, and Android-to-panel payment integration."
---

# Billing Integrity

Start from `android-client/API_CONTRACT.md`, `BillingBackendContract.kt`, dependency injection, and registered FastAPI routes. Identify which flows use `FakePaymentRepository` and which have server implementations. Record missing endpoints or panel screens as implementation gaps.

Check server-owned amount and currency, customer ownership, admin permissions, and receipt handling. Verify durable idempotency within the applicable account scope, atomic approval and entitlement updates, and audit records for reviewer actions. Cover duplicate submissions, concurrent reviews, conflicting retries, and invalid transitions with synthetic receipts and accounts. Verify sensitive receipt data stays out of logs, screenshots, and PRs. Report frontend, backend, and admin coverage based on what was exercised.
