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
        versionCode = 2
        versionName = "1.0.0"

        // Real phones are arm64-v8a (or armeabi-v7a on older/low-end devices) — x86/
        // x86_64 only matter for emulators, and onnxruntime + Vosk ship a native .so per
        // ABI, so dropping them meaningfully shrinks the APK without losing anything a
        // real device needs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing reads from env vars so the keystore and its passwords never
    // touch the repo. Generate the keystore once (see README), then export
    // ITANTRA_KEYSTORE / ITANTRA_KEYSTORE_PASSWORD / ITANTRA_KEY_PASSWORD before
    // running assembleRelease. Falls back to unsigned if unset, so debug builds
    // and CI runs without those secrets still work.
    val releaseKeystorePath = System.getenv("ITANTRA_KEYSTORE")
    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ITANTRA_KEYSTORE_PASSWORD")
                keyAlias = "itantra"
                keyPassword = System.getenv("ITANTRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

    androidResources {
        // The IndicConformer ONNX weights are already-quantized dense binaries — deflating
        // them buys little and the compression pass on ~800MB of them is what blew the
        // packaging step's heap. Storing them uncompressed is also better for ONNX
        // Runtime, which mmaps the model file by path.
        noCompress += "onnx"
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
    // out of the box — see LanguageRoutingSpeechRecognizer. Telugu's model is
    // currently commented out of VoskSpeechRecognizer for a smaller demo-video build.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // Offline STT for the six languages Vosk has no model for (Marathi, Kannada,
    // Malayalam, Tamil, Odia, Bengali): AI4Bharat IndicConformer CTC checkpoints run
    // through ONNX Runtime — see IndicConformerSpeechRecognizer. All six are currently
    // commented out of its language map for the same demo-size reason; this dependency
    // stays so that class still compiles while they're disabled.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    // Local, file-backed store-and-forward outbox (spec §7/§10).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
