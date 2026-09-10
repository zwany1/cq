plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.devtools.ksp)
}

android {
    namespace = "com.zengqi.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zengqi.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.9.1"
        
        // Force multi-DEX output
        multiDexEnabled = true

        buildConfigField("String", "HARDENING_LEVEL", "\"OPEN_SOURCE\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    ndkVersion = "30.0.14904198"

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("ZENGQI_STORE_PASSWORD") ?: project.findProperty("ZENGQI_STORE_PASSWORD") as String? ?: "debug_password_placeholder"
            keyAlias = System.getenv("ZENGQI_KEY_ALIAS") ?: project.findProperty("ZENGQI_KEY_ALIAS") as String? ?: "your_alias"
            keyPassword = System.getenv("ZENGQI_KEY_PASSWORD") ?: project.findProperty("ZENGQI_KEY_PASSWORD") as String? ?: "debug_password_placeholder"
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "曾栖")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // jniLibs are picked up automatically from src/main/jniLibs/
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val pythonExecutable = if (isWindows) "python" else "python3"








dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:ui-common"))

    // Feature modules
    implementation(project(":feature:character"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:importchat"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:recall"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))

    // sherpa-onnx: 离线流式语音识别，运行时由 app 模块提供
    implementation(files("../core/network/libs/sherpa-onnx-1.13.3.aar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.animation.graphics)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.app.update.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.tracing)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}