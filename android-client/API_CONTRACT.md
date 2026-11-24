# PasarGuard Plus billing API

The implementation is the FastAPI service in [billing-service](../billing-service/main.py). Android uses [BillingBackendContract.kt](app/src/main/java/com/pasarguard/vpn/data/api/BillingBackendContract.kt) for orders and [CustomerApi.kt](app/src/main/java/com/pasarguard/vpn/data/api/CustomerApi.kt) for its account. Send requests to the configured billing HTTPS origin. Monetary amounts are integer toman. Order timestamps ending in `_ms` are UTC epoch milliseconds.

## Customer access

- An installed customer's subscription uses `X-Subscription-Token`. New guests use a random 64-character hexadecimal `X-Purchase-Token`.
- A native account uses the opaque bearer session returned by the customer service. For renewal from that account include the selected subscription token when listing plans or placing the order.
- For native account requests use `X-Pasarguard-Client: native`. API failures normally contain FastAPI's `detail` property. Validation errors can contain a list instead of a string.

## Orders

| Method and path | Behavior |
|---|---|
| `GET /v1/billing/plans` | Destination card, payment window in minutes and the visible plans in sales order. |
| `POST /v1/billing/orders` | Create an order with JSON `plan_id` and `payment_method`. Send a UUID `Idempotency-Key` and reuse it for retries of the same checkout. Supports `CARD_TO_CARD` and `WALLET`; wallet requires an account. Discount codes are currently unavailable. |
| `GET /v1/billing/orders` | Orders visible to the caller. |
| `GET /v1/billing/orders/{id}` | Current payment and activation states for an owned order. |
| `POST /v1/billing/orders/{id}/receipt` | Multipart fields `tracking_number`, `payer_card_last4` and optional `receipt_image`. Provide a tracking number or image. Image limit is 512 KiB. Bank confirmation may arrive independently of this upload. |
| `GET /v1/billing/orders/{id}/subscription` | Subscription URL for an owned, fulfilled new purchase. |
| `POST /v1/billing/orders/{id}/transfer-ticket` | Obtain a one-time ticket for moving an eligible guest order into the signed-in account. |
| `POST /v1/customer/claim` | Claim the order using that ticket. |

The card payment window is 30 minutes. Payment states are `AWAITING_RECEIPT`, `PENDING_REVIEW`, `APPROVED`, `REJECTED` and `EXPIRED`. `APPROVED` can precede fulfillment. For orders with fulfillment, `activation_status` is `PENDING` or `READY`. The optional timeline fields are `submitted_at_ms`, `reviewed_at_ms` and `activation_ready_at_ms`. Historical orders can lack timeline dates. A reconciled order reports the actual recorded amount as `final_price_toman`.

## Accounts and refunds

Native account paths are `POST /v1/customer/code`, `POST /v1/customer/verify`, `POST /v1/customer/login`, `GET /v1/customer/me` and `POST /v1/customer/logout`.

- `GET /v1/customer/wallet` returns `balance_toman` and refund entries. A refund amount becomes visible when the refund finishes.
- `GET /v1/customer/orders/{id}/cancel-preview` returns `quote`, `amount` and `balance`. The signed quote is valid for two minutes.
- `POST /v1/customer/orders/{id}/cancel` accepts JSON `{"quote":"…"}` and returns 202 when cancellation is recorded. A changed or expired quote returns 409 and the client fetches a new quote. Legacy account clients can submit without a quote. The wallet credit is recorded after successful access revocation.

## Management and distribution

The admin API under `/admin/api` uses the manager session and requires the configured origin for mutations. The order detail endpoint returns decisions and provisioning information. The `expedite` endpoint accepts a form field `reason` and advances existing queued work; the `deposits/{id}/match` endpoint takes JSON `order_id` and `reason` and atomically assigns the bank deposit and approves the order. Receipt images are available in the authenticated admin session.

`GET /v1/app/config` accepts `platform`, `package_name`, `version_code` and optionally `abi`. A newer compatible update returns its version, download URL and Android checksum. A missing or unavailable update returns 204. `GET /download/android.apk` is the public link to the currently offered Android APK and optionally accepts `abi`; it defaults to ARM64.
