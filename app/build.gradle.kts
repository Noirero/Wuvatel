plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.mangatranslator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mangatranslator"
        minSdk = 23
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.1-m3.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Compose 1.11 generation: stable and compatible with compileSdk 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Bundled Japanese OCR model: available immediately, no first-run model wait.
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // M3.1: on-device JP -> ID translation after one-time model download.
    implementation("com.google.mlkit:translate:17.0.3")
}
