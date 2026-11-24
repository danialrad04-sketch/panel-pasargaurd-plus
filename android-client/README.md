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

## 2. Multi-Core VPN Engine (Xray-core Primary + sing-box Secondary)

The app uses a clean `VpnCore` abstraction (`vpn/core/VpnCore.kt`) with automatic routing via `CoreSelector`:
- **Primary Core — `XrayCore` (`app/libs/libv2ray.aar`)**:
  - **Bundled binding**: [`2dust/AndroidLibXrayLite`](https://github.com/2dust/AndroidLibXrayLite) **`v26.11.9`** (`github.com/xtls/xray-core v1.260327.1-0.20261109052709-676eaf66f8f0`)
  - **Native TUN**: Uses Xray-core v26's built-in Android TUN inbound (`protocol: "tun"` fed directly by `VpnService` fd via `CoreController.startLoop(configJson, tunFd)` — no external `tun2socks` binary required).
  - **Supported features**: VLESS (Reality, XTLS-Vision, XHTTP/SplitHTTP with `mode` & URL-encoded `extra` JSON, VLESS post-quantum `encryption` & `pqv`, gRPC, WS, HTTPUpgrade, mKCP, raw/TCP), VMess, Trojan, Shadowsocks, plus verbatim PasarGuard panel Xray-JSON subscriptions (`/sub/{token}/xray` & `/sub/{token}/v2ray-json`).
  - **How to update Xray-core**:
    1. Download the newest `libv2ray.aar` from [`2dust/AndroidLibXrayLite/releases`](https://github.com/2dust/AndroidLibXrayLite/releases).
    2. Replace `android-client/app/libs/libv2ray.aar`.
    3. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` to verify JNI compatibility and build fresh per-ABI APKs.
    4. Update the download URL and SHA-256 in `.github/workflows/build-android-apk.yml` for GitHub builds.

- **Secondary Core — `SingBoxCore` (optional `app/libs/libbox.aar`)**:
  - Used automatically for protocols Xray-core does not support (`Hysteria2`, `TUIC`, `WireGuard`) or when manually selected in **Settings -> VPN Core Engine**.
  - To bundle `sing-box`, drop `libbox.aar` from [`SagerNet/sing-box`](https://github.com/SagerNet/sing-box) into `android-client/app/libs/libbox.aar`.

---

## 3. Plug In Your Billing & Payment Backend (Stage 2B)

In-app plans, manual card-to-card checkout, receipt upload, order tracking, and account self-service actions are isolated behind clean repository interfaces:
- Interfaces: `domain/repository/Repositories.kt` -> `PaymentRepository` & `AccountActionsRepository`
- Frontend simulation (default): `data/repository/FakePaymentRepository.kt` (backed by Room `OrderDao` + `assets/plans_config.json`)
- Backend Retrofit contract & DTOs: `data/api/BillingBackendContract.kt`
- Full API specification (auth, idempotency, rate limits, multipart receipt upload, admin approval & push webhook flow): [`API_CONTRACT.md`](./API_CONTRACT.md)

To switch from `FakePaymentRepository` to your production billing server:
1. Implement `RemotePaymentRepository : PaymentRepository, AccountActionsRepository` using `BillingBackendApi`.
2. Bind it in `di/AppModule.kt` -> `RepositoryModule.bindPaymentRepository(...)` & `bindAccountActionsRepository(...)`.
3. Never embed PasarGuard panel admin tokens in the app; authenticate customer calls via `X-Subscription-Token` or a customer JWT.

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

GitHub Actions publishes one APK per download. Most current Android phones use
`arm64-v8a`, older 32-bit ARM phones use `armeabi-v7a`, and x86-64 Android
devices use `x86_64`. Use the APK matching the device.

The GitHub Debug downloads use R8 and resource shrinking. Local Debug builds
stay debuggable, and can use the same optimization with
`-PpasarguardOptimizeDebug=true`. For a larger APK that includes every
supported architecture, opt in with `-PpasarguardUniversalApk=true`.

## انتشار برای به‌روزرسانی داخل اپ

Workflow ساخت اندروید برای انتشار گزینهٔ `publish_release` دارد. در GitHub Actions Secrets فایل کلید امضای همیشگی را به صورت Base64 در `ANDROID_RELEASE_KEYSTORE_BASE64` و مقادیر مربوط به آن را در `ANDROID_RELEASE_STORE_PASSWORD`، `ANDROID_RELEASE_KEY_ALIAS` و `ANDROID_RELEASE_KEY_PASSWORD` تنظیم کنید. شمارهٔ ساخت افزایشی و نام نسخه باید در انتشار وارد شوند. برای به‌روزرسانی یک نسخهٔ نصب‌شده، شناسهٔ بسته و کلید امضا باید همان باشند. بیلدهای آزمایشی CI فقط برای نصب و تست هستند.

اپ فایل را در حافظهٔ موقت دانلود می‌کند و پس از تأیید SHA-256، شناسه، شمارهٔ ساخت و امضا، نصب‌کنندهٔ اندروید را باز می‌کند.
