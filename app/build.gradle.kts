plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.watermarkremover"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watermarkremover"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // CI 注入环境变量：SIGNING_KEY_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD
            storeFile = System.getenv("SIGNING_KEY_FILE")?.let { file(it) }
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            // TODO: 打包上架前重新启用 minify
            // isMinifyEnabled = true
            // isShrinkResources = true
            isMinifyEnabled = false
            isShrinkResources = false
            // 仅当 storeFile 存在时启用 release 签名
            // AGP 8.x: signingConfigs.getByName("debug") 且 storeFile=null 会导致 APK 不生成
            // -> 无签名时改为 signingConfig = null（AGP 8.x 行为变化，不再自动 fallback）
            val signingFile = System.getenv("SIGNING_KEY_FILE")
            signingConfig = if (!signingFile.isNullOrEmpty()) {
                signingConfigs.getByName("release")
            } else {
                null  // AGP 8.x: 无 storeFile 时设为 null，不要引用 debug（它也不会 fallback）
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        jniLibs {
            // OpenCV(4.9.0) 与 FFmpegKit(6.0-2) 的 AAR 都自带 libc++_shared.so，
            // mergeDebugNativeLibs 会因重复而失败，pickFirst 取其一即可。
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ABI 分割 - 每个 APK 只包含一种架构，大幅减少体积
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true  // 同时生成通用 APK（包含所有架构）
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // OpenCV Android SDK - 使用本地源码和 jniLibs（已复制到项目）
    // implementation("org.opencv:opencv:4.9.0")  // Maven 版本不包含 native 库

    // FFmpegKit (Full GPL - includes FFmpeg)
    implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
    useBuildCache = true
}
