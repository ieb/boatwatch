plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.co.tfd.boatwatch.autopilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "uk.co.tfd.boatwatch.autopilot"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "FAKE_HTTP", "true")
            buildConfigField("String", "DEFAULT_URL", "\"http://10.0.2.2:8080\"")
        }
        release {
            buildConfigField("boolean", "FAKE_HTTP", "false")
            buildConfigField("String", "DEFAULT_URL", "\"http://boatsystems.local\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Wear Compose
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.wear.compose:compose-navigation:1.3.0")

    // Compose
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WearOS text input (RemoteInput)
    implementation("androidx.wear:wear-input:1.2.0-alpha02")

    // HTTP client for streaming SeaSmart
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
