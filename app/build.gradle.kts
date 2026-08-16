plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

import java.io.FileInputStream
import java.util.Properties

val keystoreProps = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) {
    keystoreProps.load(FileInputStream(keystoreFile))
}

android {
    namespace = "com.translive.app"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.translive.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.4.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    // The app's JNI bridge is self-contained.  Static STL
                    // avoids a launch-time dependency on libc++_shared.so,
                    // which ColorOS will not supply from the system image.
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"].toString())
                storePassword = keystoreProps["storePassword"].toString()
                keyAlias = keystoreProps["keyAlias"].toString()
                keyPassword = keystoreProps["keyPassword"].toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // libOpenCL.so is a link-time stub only. The manifest declares the public
    // Android system library, so packaging this stub would mask the Adreno
    // driver on the device.
    packaging {
        jniLibs.excludes += "**/libOpenCL.so"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Room (history & dialogue logs)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Lottie for animations
    implementation("com.airbnb.android:lottie-compose:6.7.1")

    // OkHttp for model downloads
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Sherpa-ONNX for offline STT (Whisper Tiny + Silero VAD)
    // Official release AAR. Qwen3-ASR Kotlin support begins after 1.13.1.
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))

    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    // ML Kit Text Recognition (bundled — fully offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ML Kit Translation (on-device NMT — fast, for camera realtime)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // LiteRT-LM runtime. Keep this aligned with the official Gemma LiteRT
    // model releases; 0.16.0 is required for the current GPU model catalog.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")

    // Tesseract4Android (Cyrillic/Arabic/Thai/etc. OCR — ML Kit doesn't support these scripts)
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0")

    // ML Kit Devanagari text recognition (Hindi, Marathi)
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
}
