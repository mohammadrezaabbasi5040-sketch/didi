plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ir.dkhunter"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.dkhunter"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "4.0"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
