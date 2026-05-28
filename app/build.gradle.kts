plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // v0.9.56 — Firebase Crashlytics. Order matters: google-services must come
    // BEFORE crashlytics so the Firebase config is registered before the
    // crashlytics plugin attaches itself. See:
    // https://firebase.google.com/docs/crashlytics/get-started?platform=android
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.uruj"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.uruj"
        minSdk = 26
        targetSdk = 36
        versionCode = 146
        versionName = "0.9.57"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose + AndroidX
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Settings persistence
    implementation(libs.androidx.datastore.preferences)

    // Sensors
    implementation(libs.play.services.location)
    implementation(libs.androidx.health.connect.client)

    // Concurrency + serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Route map (v0.3.0) — OpenStreetMap tiles, free, no API key
    implementation(libs.osmdroid.android)

    // v0.9.56 — Firebase Crashlytics for automatic crash capture. BoM pins all
    // Firebase library versions in lockstep so the SDK + plugin stay aligned.
    // Crashlytics auto-initializes via FirebaseInitProvider — no manual
    // `Firebase.initialize(...)` call needed. UrujApplication.kt installs a
    // local crash-logger fallback on top so even if Firebase upload fails
    // (offline, throttled, etc.) we still have an on-device record at
    // `/files/crash-logs/YYYY-MM-DD_HHmmss.txt`.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Debug tooling
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
