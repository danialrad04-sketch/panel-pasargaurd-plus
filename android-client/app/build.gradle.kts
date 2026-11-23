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

android {
    namespace = "com.pasarguard.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pasarguard.vpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        val baseUrl = resolveConfig("PASARGUARD_BASE_URL", "https://panel.example.com")
        val appName = resolveConfig("PASARGUARD_APP_NAME", "PasarGuard VPN")
        val telegramUrl = resolveConfig("PASARGUARD_SUPPORT_TELEGRAM", "https://t.me/your_support_channel")
        val whatsappUrl = resolveConfig("PASARGUARD_SUPPORT_WHATSAPP", "https://wa.me/15550000000")
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
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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

    // QR Code Scanner
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.accompanist.permissions)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
