plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.visioncrossnew"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.visioncrossnew"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    // 加入這兩段，其他原本的東西都不要動
    androidResources {
        noCompress("tflite")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    // 匯入 OpenCV 影像處理函式庫 (QuickBirdStudios 輕量版)
    implementation("com.quickbirdstudios:opencv:4.5.3.0")

    // CameraX
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    // --- 斬草除根：把有 Bug 的 support 徹底移除，只留最新核心 ---
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

}