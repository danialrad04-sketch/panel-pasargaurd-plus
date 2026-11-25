import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun resolveConfig(key: String, defaultValue: String): String {
    return localProps.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: System.getenv(key)
        ?: defaultValue
}

val optimizeDebugApks = providers.gradleProperty("pasarguardOptimizeDebug").orNull == "true"
val buildUniversalApk = providers.gradleProperty("pasarguardUniversalApk").orNull == "true"
val releaseKeyStore = System.getenv("PASARGUARD_RELEASE_KEYSTORE")
val releaseStorePassword = System.getenv("PASARGUARD_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("PASARGUARD_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("PASARGUARD_RELEASE_KEY_PASSWORD")

android {
    namespace = "com.pasarguard.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pasarguard.vpn"
        minSdk = 24
        targetSdk = 35
        resourceConfigurations += listOf("en", "fa")
        versionCode = resolveConfig("PASARGUARD_VERSION_CODE", "17").toInt().also { require(it in 1..2_100_000_000) }
        versionName = resolveConfig("PASARGUARD_VERSION_NAME", "2.0.2").also {
            require(Regex("[0-9]+(?:[.][0-9]+){1,3}").matches(it))
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        val baseUrl = resolveConfig("PASARGUARD_BASE_URL", "https://panel.example.com")
        val billingUrl = resolveConfig("PASARGUARD_BILLING_URL", "")
        require(billingUrl.isBlank() || Regex("https://[a-zA-Z0-9.-]+(?::[0-9]+)?/?").matches(billingUrl)) {
            "PASARGUARD_BILLING_URL must be an HTTPS origin"
        }
        buildConfigField("String", "BILLING_BASE_URL", "\"$billingUrl\"")
        for (field in listOf("FCM_APP_ID", "FCM_API_KEY", "FCM_PROJECT_ID", "FCM_SENDER_ID")) {
            val value = resolveConfig("DONINO_$field", "")
            require(Regex("[A-Za-z0-9:_-]*").matches(value))
            buildConfigField("String", field, "\"$value\"")
            if (value.isNotBlank()) {
                val resource = when (field) {
                    "FCM_APP_ID" -> "google_app_id"
                    "FCM_API_KEY" -> "google_api_key"
                    "FCM_PROJECT_ID" -> "project_id"
                    else -> "gcm_defaultSenderId"
                }
                resValue("string", resource, value)
            }
        }
        val appName = resolveConfig("PASARGUARD_APP_NAME", "Donino Vpn")
        val telegramUrl = resolveConfig("PASARGUARD_SUPPORT_TELEGRAM", "")
        val whatsappUrl = resolveConfig("PASARGUARD_SUPPORT_WHATSAPP", "")
        val paymentScheme = resolveConfig("PASARGUARD_PAYMENT_SCHEME", "pasarguardvpn")

        buildConfigField("String", "DEFAULT_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "BRAND_NAME", "\"$appName\"")
        buildConfigField("String", "SUPPORT_TELEGRAM_URL", "\"$telegramUrl\"")
        buildConfigField("String", "SUPPORT_WHATSAPP_URL", "\"$whatsappUrl\"")
        buildConfigField("String", "PAYMENT_CALLBACK_SCHEME", "\"$paymentScheme\"")

        manifestPlaceholders["appName"] = appName
        manifestPlaceholders["paymentScheme"] = paymentScheme
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = buildUniversalApk
        }
    }

    signingConfigs {
        if (!releaseKeyStore.isNullOrBlank()) {
            require(!releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank())
            create("distribution") {
                storeFile = file(releaseKeyStore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = optimizeDebugApks
            isShrinkResources = optimizeDebugApks
            if (optimizeDebugApks) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("distribution") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Optional drop-in sing-box native AAR (place libbox.aar in app/libs/)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.browser)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Networking & Serialization
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Room, DataStore, Keystore Encrypted Prefs
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation("com.google.firebase:firebase-messaging:24.1.2")

    // QR Code Scanner
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.accompanist.permissions)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
