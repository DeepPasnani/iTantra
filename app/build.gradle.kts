plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.itantra.voicemesh.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra.voicemesh.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-dev"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-messaging"))
    implementation(project(":transport-api"))
    implementation(project(":core-routing"))
    implementation(project(":core-reliability"))
    implementation(project(":transport-wifi"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Offline STT (spec §11/§12): Vosk covers Hindi, Gujarati, Telugu, and English
    // out of the box (see the model-download instructions this session's chat
    // response gives) — Marathi/Kannada/Malayalam/Tamil/Odia/Bengali have no
    // ready-made lightweight engine today, see LanguageRoutingSpeechRecognizer.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // Local, file-backed store-and-forward outbox (spec §7/§10).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
