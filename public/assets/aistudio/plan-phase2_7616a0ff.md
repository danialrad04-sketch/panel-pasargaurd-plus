# PasarGuard Android VPN Client — Phase 2: Polish, Pro Features, Subscription Management & Renewal

Phase 1 is confirmed working (Xray primary core connects with my real configs). Do NOT rewrite the project. Extend and refactor the existing codebase. Deliver in **two stages** (2A, then 2B). For each stage: show a short plan first, then generate only changed/new files, complete and compilable, then build and give me the APK link (see BUILD & DELIVERY). Wait for my confirmation after 2A before starting 2B.

---

# STAGE 2A — Ping/latency system, UI polish & smoothness, pro features

## A1. Advanced ping / latency testing

1. **Multiple test URLs.** Ship a built-in list the user can choose from, plus the ability to add/edit/delete custom URLs:
   - `https://www.gstatic.com/generate_204` (default)
   - `https://cp.cloudflare.com/generate_204`
   - `https://connectivitycheck.gstatic.com/generate_204`
   - `http://detectportal.firefox.com/success.txt`
   - `https://www.google.com/generate_204`
   - `https://captive.apple.com`
   - Custom URL
2. **Test modes** (selectable in Settings and via a quick sheet on the Servers screen):
   - Through-core HTTP test (real, through Xray) — default
   - TCP connect test (fast, to server address:port)
   - "Multi-URL" mode: test against 2-3 URLs and report the best/average
3. **Quality metrics per server:** average latency, min/max, jitter, packet loss % (e.g. 3 samples per server, configurable 1-5), and a computed **stability score** (Excellent / Good / Poor / Dead) with color chips.
4. **Concurrency and control:** configurable timeout (2-10s) and max parallel tests (1-10). Show a **live progress UI** ("12/40 tested"), allow **cancel**, and update each server's row in real time as results arrive. No UI jank while testing.
5. **Sorting & filtering:** sort by ping / name / country / stability / favorites; hide dead servers toggle.
6. **Server detail bottom sheet:** protocol, transport (tcp/ws/grpc/xhttp...), security (reality/tls), port, address (copy button), ping history sparkline (last 10 results, stored in Room), per-server "Test again", "Favorite", "Copy link", "Show QR", "Connect".
7. **Auto-select modes:** Lowest ping / Most stable / Round-robin among top N. Auto test on app open (optional toggle) and before auto-select.
8. **Connection diagnostics screen:** after connecting, show the exit IP, country/city/ISP (use a configurable IP-info endpoint), DNS leak check, and a **download speed test through the tunnel** (configurable test URL, shows Mbps with a live animated gauge).

## A2. UI / UX polish and smoothness

1. **Performance:** use stable keys and `remember`/`derivedStateOf` correctly, avoid unnecessary recompositions, use immutable UI state classes, lazy lists with keys + `contentType`, hoist animations. Add a **Baseline Profile** and enable R8 full mode for release. Target consistent 60/120 fps on mid-range phones.
2. **Motion system:** one centralized motion spec (durations/easings/springs). Shared element transitions between lists and detail sheets, predictive back animations, smooth tab transitions, animated number counters, animated connect button states (idle / connecting / connected / error), subtle haptic feedback on key actions (connect, copy, success, error). Respect the system "reduce motion" setting.
3. **Polished states everywhere:** shimmer skeletons, friendly empty states with illustrations, inline error banners with Retry, pull-to-refresh on Servers and Subscription, snackbars with undo where relevant.
4. **Edge-to-edge** with correct insets, Material 3 components, optional **Material You dynamic color** toggle in addition to the brand theme, themed (monochrome) adaptive icon.
5. **Tablet/foldable layouts** (adaptive two-pane on wide screens), landscape support.
6. **Accessibility:** content descriptions, 48dp touch targets, TalkBack-friendly order, font-scale safe layouts, sufficient contrast.
7. **Persian polish:** Persian digits toggle, correct RTL mirroring for icons/progress/charts, **Jalali (Shamsi) calendar** for all dates with a toggle (Jalali / Gregorian), thousands separators.
8. **Onboarding & first-run tips:** short coach marks for the connect button, server list, and subscription card.

## A3. Pro features (add all that are technically feasible)

**Connection**
- **Quick Settings tile** to connect/disconnect.
- **Home-screen widget** (Jetpack Glance): status, connect button, remaining days/traffic.
- **Auto-reconnect** on network change/drop with exponential backoff, and **automatic failover** to the next best server if the current one dies (toggle).
- **Auto-connect** on app launch, on device boot, and on untrusted Wi-Fi/mobile data (rules per network type).
- **Kill switch / Always-on VPN guidance** with a deep link to system settings, plus an in-app "block traffic if VPN drops" option.
- **Routing modes:** Global / Bypass LAN / **Bypass Iran** (geoip:ir + geosite:category-ir) / Custom rules (domain/IP/geosite/geoip, direct/proxy/block). Simple rule editor UI.
- **DNS settings:** system / custom / DoH / DoT presets (Cloudflare, Google, Quad9, custom), plus "remote DNS through proxy" toggle.
- **Advanced connection options:** MTU, IPv6 on/off, fragment/noise toggle (when the config supports it), mux toggle, sniffing toggle, allow-insecure global override (off by default with a warning).
- **Per-app split tunneling:** searchable app list with icons, bypass/only-selected modes, saved presets (e.g. "Banking apps bypass").

**Profiles & configs**
- **Multiple subscriptions/profiles:** add several subscription links, switch between them, per-profile name/color.
- **Manual import:** paste/clipboard-detect `vless://`, `vmess://`, `trojan://`, `ss://`, or a subscription URL; scan QR; import from a file. Group imported configs under "Manual".
- **Share / QR / copy** for any server.
- **Subscription header support:** read and use `subscription-userinfo`, `profile-title`, `support-url`, `profile-web-page-url`, `announce`, `profile-update-interval` response headers when the panel provides them. Show panel **announcements** in an in-app banner/dialog (dismissible, remembered).
- **Update interval** setting for auto-refresh (1h / 6h / 12h / 24h / manual) using WorkManager.

**Security & privacy**
- **App lock** (biometric/PIN) toggle, hide app content in recents, optional "disguise" notification text.
- **Sanitized connection log screen** (no UUIDs/keys/full domains), export a diagnostics report to share with support.

**Maintenance**
- **Backup / restore settings** (export/import a JSON file; never includes the subscription token unless the user opts in).
- **In-app update checker** against a configurable remote JSON (`latest_version`, `download_url`, `changelog`, `force_update`).
- **Statistics screen:** per-day and per-session usage history, total connected time, most used servers (Room-backed, charts).

---

# STAGE 2B — Subscription management, plans, renewal & manual card-to-card payment UI (frontend only; backend comes later)

Hide all payment/order logic behind the existing `PaymentRepository` interface with a `FakePaymentRepository` that simulates realistic responses (including delays, pending/approved/rejected/expired, and errors). **Never put admin tokens or panel admin credentials in the app.**

## B1. Subscription dashboard (professional)

1. **Subscription Passport Card:** username, status chip (active / on_hold / limited / expired / disabled), plan name, expiry date (Jalali/Gregorian) with a live countdown, data used / total with an animated ring, reset strategy and next reset date, device limit (HWID).
2. **Usage insights:** daily usage bar chart (7/30 days, from `/sub/{token}/usage` when available, graceful fallback), **usage forecast** ("at your current rate your data lasts ~N days" and "likely to run out before expiry" warning), average daily usage, peak day.
3. **Smart alerts and notifications (WorkManager):** expiry warnings (7d / 3d / 1d / expired), low-traffic warnings (80% / 90% / 100%), each toggleable, with deep links that open the renewal screen. Notification channels for each type.
4. **Subscription link tools:** copy / share / QR code of the subscription link (with a warning that it is private), "Refresh now" with last-sync time.
5. **Device management UI (backend-ready):** list of devices/HWIDs using this subscription, remove device action (behind an interface; works with fake data now).
6. **Account actions (backend-ready, via interfaces):** request traffic reset, request new subscription link, contact support (Telegram/WhatsApp/ticket form), view renewal history.
7. **Expired/limited state UX:** a clear, friendly full-screen state with a prominent "Renew now" button. IMPORTANT: calls to the payment/order backend must work even when the VPN is OFF or blocked, and the app must route its own backend/panel API calls outside the tunnel (exclude the app's own traffic from the VPN) so renewal never gets stuck because the VPN is down.

## B2. Plans & purchase

1. **Plans screen:** configurable list from a single `PlansConfig` (local JSON now, remote endpoint later). Each plan: title, data GB / unlimited, duration days, price in Toman, old price + discount badge, "Most popular" / "Best value" tags, max devices, short feature bullets. Persian numbers and thousands separators in RTL, price-per-GB and price-per-day hints.
2. **Plan types:** **Renew** (extend current), **New plan / Upgrade**, and **Extra traffic add-ons** (e.g. +10 GB, +50 GB) and **Extra days** packs, all via the same flow.
3. **Compare plans** view (side-by-side bottom sheet) and a smart suggestion ("Recommended for your usage" based on the user's average consumption).

## B3. Checkout flow

1. **Summary screen:** selected plan, **discount code field** (validate through the repository, show applied discount), optional wallet/credit line (disabled slot), final price.
2. **Payment method selection:** card-to-card (active); online gateway and crypto (visible but disabled, "Coming soon").
3. **Card-to-card screen:** destination card number (one-tap copy, formatted in groups of 4), card holder name, bank name, **exact amount to pay** (one-tap copy), a **countdown timer** (30 min payment window, with warning color near the end), numbered step-by-step instructions, and a note about not rounding the amount. Then the payer form: last 4 digits of payer card, tracking/reference number, optional **receipt screenshot** (image picker, preview, **compress before upload**, remove/replace).
4. **Submit -> "Waiting for confirmation" screen** with animated status (pending / approved / rejected with reason / expired), polling with backoff and a push-ready design (an interface for FCM or similar later), "Contact support" shortcut, ability to leave the screen and return from Orders.
5. **Success screen** (celebratory animation) that automatically refreshes the subscription (re-fetches `/sub/{token}/info` and servers), updates cached state, and cancels pending expiry/low-traffic notifications.
6. **Failure/edge cases:** expired payment window, rejected receipt (retry with new receipt), network errors, duplicate submissions prevented, app killed mid-flow (restore the pending order from Room).

## B4. Orders & history

- **Orders screen:** list with status chips (Pending / Approved / Rejected / Expired), date (Jalali/Gregorian), plan, amount, tracking number; order detail screen with timeline (created -> receipt submitted -> reviewed), copyable order ID, and "Download/share receipt" (simple generated invoice image/PDF).
- Filters by status; empty and error states.

## B5. Future backend contract

Create documented Kotlin interfaces + DTOs and an `API_CONTRACT.md` covering: list plans/add-ons, validate discount code, create order, upload receipt (multipart), get order status, list orders, list/remove devices, request traffic reset, request new sub link, get app config/announcements/update info. Include auth model (the customer is identified by a signed subscription token or a server-issued session — never admin credentials), error codes, idempotency keys for order creation, rate limiting notes, and webhook/admin-approval flow notes so I can build the server later.

---

# BUILD & DELIVERY (applies after EVERY stage)

1. **Build the app yourself** (`./gradlew assembleDebug`, plus run unit tests). Fix all compile errors before reporting. Never hand me code you haven't built.
2. Produce an installable **universal debug APK** (all ABIs) named `AppName-v<versionName>-build<versionCode>-debug.apk`. Bump `versionCode` every build (continue from the current version; this stage is v1.2.0 for 2A and v1.3.0 for 2B).
3. Give me a **direct DOWNLOAD LINK** to the APK at the very end of your response.
4. Next to the link, write a **changelog** + **what to test** + **known limitations**.
5. If you cannot build or host a file here, say so clearly and give me the exact local build commands and a ready GitHub Actions workflow (`.github/workflows/build.yml`) that builds the APK and uploads it as an artifact. Do not pretend a link exists if it doesn't.
6. Never log configs, tokens, subscription links, or receipt data in release builds.

---

# RULES

- Keep Clean Architecture + MVVM + Hilt; do not break existing screens or the working Xray/sing-box cores.
- Add unit tests for new logic (ping scoring, forecast calculation, Jalali formatting, header parsing, order state machine).
- If a feature is not technically feasible, say so and offer the closest alternative instead of silently skipping it.
- No "..." or TODO in core logic. Comment only non-obvious parts.
- Keep the same design language: dark glassmorphism, Vazirmatn/Inter, full RTL, smooth animations.
- Deliver **2A first**, wait for my confirmation, then **2B**.
