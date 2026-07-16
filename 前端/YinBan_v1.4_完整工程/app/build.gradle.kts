// ============================================================
// 路径: app/build.gradle.kts
// 用途: AI 影伴系统 (YinBan) — 应用模块构建配置
// ============================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yinban.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yinban.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // BuildConfig 自定义字段
        buildConfigField("boolean", "SHOW_SERVER_CONFIG", "true")
        buildConfigField("String", "WS_BASE_URL", "\"ws://10.240.11.161\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // OkHttp (WebSocket + HTTP)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines（DeepSeekClient HTTP 异步调用）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
