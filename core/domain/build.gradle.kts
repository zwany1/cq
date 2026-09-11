plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    // core:domain 核心只依赖 kotlinx-coroutines（语言级基础设施），无其他业务依赖
    implementation(libs.kotlinx.coroutines.core)
}

android {
    namespace = "com.zengqi.ai.domain"
    compileSdk = 35
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
