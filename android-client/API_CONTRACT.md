# PasarGuard VPN Client — Billing & Customer Account Backend API Contract

This document defines the HTTP API contract expected by the Android client (`BillingBackendApi` in `com.pasarguard.vpn.data.api.BillingBackendContract.kt`) so you can implement the production billing/payment-verification microservice without changing the Android UI.

---

## 1. Security & Authentication Model

- **NEVER embed PasarGuard panel admin credentials or admin tokens in the Android app.**
- All customer-scoped requests authenticate using **either**:
  - `X-Subscription-Token: <user_sub_token>` (signed/opaque PasarGuard subscription token), **or**
  - `Authorization: Bearer <customer_jwt>` issued by your billing service after verifying the subscription token.
- The Android VPN service explicitly excludes its own package (`builder.addDisallowedApplication(packageName)`), guaranteeing billing/renewal API requests work **even when the user's VPN subscription is expired, limited, or disconnected**.
- **Transport:** HTTPS (TLS 1.2+) only. Never log subscription tokens, receipt images, or card digits in server access logs.

---

## 2. Standard Headers, Idempotency & Rate Limits

| Header | Required | Description |
|---|---|---|
| `X-Subscription-Token` | Yes (except `/v1/app/config`) | Identifies the customer subscription cleanly without admin access. |
| `Idempotency-Key` | Yes on `POST /v1/billing/orders` | UUID v4 generated per checkout attempt. If the server sees the same key within 24h for the same subscriber, return the existing order (`200 OK`) instead of creating a duplicate. |
| `Accept-Language` | Optional | `fa` or `en` for localized server messages. |

### Recommended Rate Limits
- `POST /v1/billing/discounts/validate`: **10 requests / minute / subscriber** (prevents promo brute-forcing).
- `POST /v1/billing/orders`: **5 requests / 10 minutes / subscriber**.
- `POST /v1/billing/orders/{id}/receipt`: **5 uploads / 10 minutes / order** (max payload **2 MB**; client pre-compresses JPEG to `<= 350 KB`).
- `GET /v1/billing/orders/{id}`: **30 requests / minute / subscriber** (client uses exponential backoff: 3s → 4s → 6s → 10s → 15s → 20s).
- `POST /v1/account/regenerate-link`: **2 requests / hour / subscriber**.

---

## 3. Standard Error Format & Codes

All non-2xx responses return JSON:
```json
{
  "error_code": "DISCOUNT_EXPIRED",
  "message_fa": "مهلت استفاده از این کد تخفیف به پایان رسیده است.",
  "message_en": "This discount code has expired."
}
```

| HTTP | `error_code` | Meaning |
|---|---|---|
| `400` | `INVALID_RECEIPT_FORMAT` | Unsupported MIME type or missing card last4 / tracking number |
| `401` | `INVALID_SUBSCRIPTION_TOKEN` | Subscription token unknown or revoked |
| `404` | `ORDER_NOT_FOUND` / `PLAN_NOT_FOUND` | Entity does not exist or belongs to another subscriber |
| `409` | `ORDER_WINDOW_EXPIRED` | Payment window elapsed before receipt submission |
| `409` | `ORDER_ALREADY_FINALIZED` | Order is already `APPROVED` or `EXPIRED` |
| `422` | `DISCOUNT_INVALID` / `DISCOUNT_EXPIRED` | Promo code invalid or ineligible for selected plan |
| `429` | `RATE_LIMITED` | Too many requests (`Retry-After` header included) |

---

## 4. Endpoints

### 4.1 List Plans & Add-ons
`GET /v1/billing/plans`

Returns the active `PlansCatalogResponseDto` (matching local `assets/plans_config.json`):
```json
{
  "currency": "TOMAN",
  "payment_window_minutes": 30,
  "destination_card": {
    "card_number": "6037997512348901",
    "holder_name_fa": "شرکت فناوری پاسارگارد نتورک",
    "holder_name_en": "PasarGuard Network Tech",
    "bank_name_fa": "بانک ملی ایران",
    "bank_name_en": "Bank Melli Iran"
  },
  "plans": [
    {
      "id": "renew_3m_150g",
      "category": "RENEW",
      "title_fa": "سه‌ماهه • ۱۵۰ گیگابایت",
      "title_en": "3 Months • 150 GB",
      "duration_days": 90,
      "data_limit_gb": 150,
      "price_toman": 420000,
      "old_price_toman": 525000,
      "tag_fa": "محبوب‌ترین",
      "tag_en": "Most Popular",
      "is_popular": true,
      "is_best_value": false,
      "max_devices": 3,
      "features_fa": ["..."],
      "features_en": ["..."]
    }
  ]
}
```
Supported `category` values: `RENEW`, `UPGRADE`, `EXTRA_TRAFFIC`, `EXTRA_DAYS`. (`data_limit_gb: null` = unlimited.)

### 4.2 Validate Discount Code
`POST /v1/billing/discounts/validate`
```json
// Request
{ "plan_id": "renew_3m_150g", "code": "WELCOME15" }

// Response 200
{
  "valid": true,
  "code": "WELCOME15",
  "discount_amount_toman": 63000,
  "discount_percent": 15,
  "message_fa": "کد تخفیف ۱۵٪ اعمال شد.",
  "message_en": "15% discount applied."
}
```

### 4.3 Create Order (Idempotent)
`POST /v1/billing/orders` (`Idempotency-Key: <uuid>`)
```json
// Request
{
  "plan_id": "renew_3m_150g",
  "payment_method": "CARD_TO_CARD",
  "discount_code": "WELCOME15"
}

// Response 201 (or 200 on idempotent replay)
{
  "id": "PG-9A4F2C10",
  "status": "AWAITING_RECEIPT",
  "plan_id": "renew_3m_150g",
  "plan_title_fa": "سه‌ماهه • ۱۵۰ گیگابایت",
  "plan_title_en": "3 Months • 150 GB",
  "plan_category": "RENEW",
  "payment_method": "CARD_TO_CARD",
  "base_price_toman": 420000,
  "discount_code": "WELCOME15",
  "discount_amount_toman": 63000,
  "final_price_toman": 357000,
  "destination_card": { "...": "..." },
  "created_at_ms": 1763748000000,
  "expires_at_ms": 1763749800000
}
```
*Tip:* You may optionally add a tiny unique rial/toman offset (e.g. +100..+900 Toman) server-side in `final_price_toman` for easier bank SMS matching; the client displays and copies `final_price_toman` verbatim and instructs the user not to round it.

### 4.4 Upload Card-to-Card Receipt (Multipart)
`POST /v1/billing/orders/{orderId}/receipt`  
`Content-Type: multipart/form-data`
- `payer_card_last4` (`text/plain`): 4 digits (optional if tracking number provided)
- `tracking_number` (`text/plain`): bank tracking/reference number
- `receipt_image` (`image/jpeg`, optional): compressed screenshot (`<= 350 KB`)

Transitions order from `AWAITING_RECEIPT` (or `REJECTED` on retry) → `PENDING_REVIEW` and returns updated `OrderDto`.

### 4.5 Get Order Status
`GET /v1/billing/orders/{orderId}` → returns `OrderDto`.  
Statuses: `AWAITING_RECEIPT`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `EXPIRED`.

### 4.6 List Customer Orders
`GET /v1/billing/orders?status=APPROVED&limit=50` → returns `{ "orders": [OrderDto, ...] }`.

### 4.7 Connected Devices (HWID)
- `GET /v1/account/devices` → `{ "devices": [{ "id", "name", "platform", "model", "last_seen_ms", "is_current" }] }`
- `DELETE /v1/account/devices/{deviceId}` → `AccountActionResponseDto`

### 4.8 Account Self-Service Actions
- `POST /v1/account/regenerate-link` → `AccountActionResponseDto` (optionally returns `new_subscription_url`)
- `POST /v1/support/tickets` → `{ "subject", "message", "order_id" }` → `AccountActionResponseDto`

### 4.9 Remote App Config / Announcements / Updates
`GET /v1/app/config?platform=android&version_code=4`
```json
{
  "latest_version": "1.3.0",
  "latest_version_code": 4,
  "download_url": "https://example.com/app.apk",
  "changelog": "...",
  "force_update": false,
  "announcement": null
}
```

---

## 5. Admin Approval, Auto-Activation & Push Flow

1. User submits receipt → backend stores image privately (S3/MinIO with signed expiring admin URLs) and sends an interactive notification to your **Admin Telegram Bot / Dashboard** with Approve / Reject buttons.
2. When Admin clicks **Approve**:
   - Backend calls your **PasarGuard Panel Admin API** server-side (admin token stays strictly on the backend!) to extend expiry / add traffic / upgrade data limit according to `plan.category` (`RENEW`, `UPGRADE`, `EXTRA_TRAFFIC`, `EXTRA_DAYS`).
   - Backend marks the order `APPROVED` inside a DB transaction (guarding against double-crediting with an `activated_at` column).
   - Optionally sends a silent FCM push `{ "type": "order_status", "order_id": "...", "status": "APPROVED" }` to trigger `OrderPushEventReceiver` immediately, in addition to the client's automatic polling.
3. The Android client receives `APPROVED`, displays the celebratory success screen, automatically calls `syncSubscription()` (refreshing `/sub/{token}/info` and servers), and clears pending expiry/low-traffic notifications.

- `POST /v1/billing/orders/{orderId}/transfer-ticket` with the original `X-Purchase-Token` returns a one-time transfer ticket for a provisioned purchase. The account portal consumes it after login and confirmation.
