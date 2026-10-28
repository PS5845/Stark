plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.starkphone"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.starkphone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
