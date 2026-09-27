import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.murmur.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.murmur.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // sherpa-onnx ships native libs for several ABIs; the phone only needs arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Store native libraries as real files: the NPU runtime (libQnnHtpV81Skel.so) is loaded
        // by the phone's DSP from the app's library folder, which can't read inside the APK.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    // The NPU build (scripts/fetch-deps.sh --npu) adds Qualcomm's QNN runtime to sherpa-onnx.
    val qnnAar = file("libs/sherpa-onnx-1.13.8-qnn.aar")
    implementation(files(if (qnnAar.exists()) qnnAar else file("libs/sherpa-onnx-1.13.8.aar")))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
}
