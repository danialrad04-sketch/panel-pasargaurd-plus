# PasarGuard Branded Android VPN Client

Production-ready Android VPN customer application built with **Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, EncryptedSharedPreferences, WorkManager, and `sing-box` (`libbox`) native TUN** against the **PasarGuard** customer subscription API.

---

## 1. Configure Base URL & Branding

All white-label values are centralized with zero hardcoded secrets:

1. **Panel URL, App Name, Support Links & Deep-Link Scheme**:
   Edit `gradle.properties` (or override per-developer / in CI inside `local.properties` or environment variables):
   ```properties
   PASARGUARD_BASE_URL=https://panel.yourdomain.com
   PASARGUARD_APP_NAME=YourBrand VPN
   PASARGUARD_SUPPORT_TELEGRAM=https://t.me/your_support
   PASARGUARD_SUPPORT_WHATSAPP=https://wa.me/15550000000
   PASARGUARD_PAYMENT_SCHEME=yourbrandvpn
   ```
2. **Colors, Startup Language & Alert Thresholds**:
   Edit a single file: `app/src/main/java/com/pasarguard/vpn/BrandConfig.kt`.
3. **Typography (Vazirmatn & Inter)**:
   The theme automatically loads bundled fonts when present (and falls back cleanly to system sans-serif until added). Drop the `.ttf` files into `app/src/main/res/font/`:
   - `vazirmatn_regular.ttf`, `vazirmatn_medium.ttf`, `vazirmatn_semibold.ttf`, `vazirmatn_bold.ttf`
   - `inter_regular.ttf`, `inter_medium.ttf`, `inter_semibold.ttf`, `inter_bold.ttf`
4. **App Icon**:
   Replace `app/src/main/res/drawable/ic_launcher_foreground.xml` (and `ic_launcher_background.xml`) with your brand vector asset.

---

## 2. Add the `sing-box` Native Core (`libbox.aar`)

The app already includes `SingBoxConfigBuilder` (VLESS Reality/Vision/WS/gRPC, VMess, Trojan, Shadowsocks -> `sing-box` TUN JSON) and `SingBoxEngineWrapper` (native `PlatformInterface` + Clash API live speed telemetry).

To enable native packet forwarding in production:
1. Build or download `libbox.aar` from the official [`SagerNet/sing-box`](https://github.com/SagerNet/sing-box) release (`gomobile` Android target).
2. Place `libbox.aar` into `android-client/app/libs/libbox.aar`.
3. `app/build.gradle.kts` automatically picks up `app/libs/*.aar` via `fileTree`.

---

## 3. Plug In Your Payment Gateway

In-app renewal is isolated behind the clean `PaymentRepository` interface:
- Interface: `domain/repository/Repositories.kt` -> `PaymentRepository`
- Placeholder implementation: `data/repository/PlaceholderPaymentRepository.kt`

To connect Zarinpal, Cryptomus, Stripe, Telegram Bot storefront, or a custom billing backend:
1. Create your implementation class (e.g. `ZarinpalPaymentRepository : PaymentRepository`) injecting `OkHttpClient` / Retrofit.
2. Return your real plan list in `getAvailablePlans()`.
3. In `createCheckoutSession(planId, subscriptionToken, callbackUrl)`, request a gateway payment link and configure your backend's post-payment redirect URL to `callbackUrl` (default: `pasarguardvpn://payment/callback`).
4. When your backend webhook receives successful payment confirmation, extend the user in PasarGuard (`PUT /api/user/{username}` or `POST /api/user/from_template/{username}`) and redirect the browser to `pasarguardvpn://payment/callback?status=success`.
5. Bind your class in `di/AppModule.kt` -> `RepositoryModule.bindPaymentRepository(...)`.
6. Upon return from Chrome Custom Tabs, `RenewalViewModel` automatically polls `GET /sub/{token}/info` until the updated `expire` / `data_limit` appears and refreshes Room + servers.

---

## 4. PasarGuard Endpoints Used

| Purpose | Endpoint |
|---|---|
| Subscription metadata & quota | `GET /sub/{token}/info` |
| Proxy links (JSON primary) | `GET /sub/{token}/raw` |
| Proxy links (Base64 fallback + headers) | `GET /sub/{token}` |
| Usage history chart | `GET /sub/{token}/usage` |
| Lightweight header check | `HEAD /sub/{token}` |

Device identity headers (`X-HWID`, `X-Device-OS`, `X-Ver-OS`, `X-Device-Model`) are attached automatically via `HwidHeaderInterceptor`.

---

## 5. Build Debug / Release APK & AAB

Open `android-client/` in **Android Studio Ladybug+ (JDK 17)** or run via Gradle:

```bash
cd android-client

# Debug APK
./gradlew :app:assembleDebug

# Minified Release APK (R8 + ProGuard enabled)
./gradlew :app:assembleRelease

# Google Play App Bundle (AAB)
./gradlew :app:bundleRelease
```

Outputs are generated under `app/build/outputs/apk/` and `app/build/outputs/bundle/`.
