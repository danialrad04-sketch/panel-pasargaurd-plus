---
name: android-engineering
description: "Develop or review PasarGuard Plus Android features, Kotlin/Compose UI, VPN client lifecycle, Room, and Gradle builds."
---

# Android Engineering

Trace the flow through `android-client/app/src` from navigation and ViewModel to repository, then check lifecycle and persistence. Keep changes within the existing architecture. For billing or receipt changes, also load `billing-integrity`.

From `android-client/`, use the Gradle wrapper when available and run `:app:testDebugUnitTest` and, when the Android SDK and AAR are available, `:app:assembleDebug`. Read `.github/workflows/build-android-apk.yml` for JDK 17, Gradle setup, and AAR prerequisites. Verify localized resources and RTL behavior for visible changes. Report emulator or device checks separately from compilation.
