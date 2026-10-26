import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localSigning = Properties()
val localSigningFile = rootProject.file("signing/credentials.properties")
if (localSigningFile.exists()) localSigningFile.inputStream().use { localSigning.load(it) }
fun signingValue(name: String) = System.getenv(name) ?: localSigning.getProperty(name)
val signingReady = listOf("ANDROID_KEYSTORE", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    .all { !signingValue(it).isNullOrEmpty() }

android {
    namespace = "com.coco.dense"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.coco.dense"
        minSdk = 31
        targetSdk = 35
        val appVersion = providers.gradleProperty("appVersion").orElse("0.1.12").get()
        val parts = appVersion.split('.').map { it.toIntOrNull() ?: 0 }
        versionCode = (parts.getOrElse(0) { 0 } * 1_000_000) + (parts.getOrElse(1) { 0 } * 1_000) + parts.getOrElse(2) { 0 }
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signingReady) create("release") {
            storeFile = rootProject.file(requireNotNull(signingValue("ANDROID_KEYSTORE")))
            storePassword = signingValue("ANDROID_STORE_PASSWORD")
            keyAlias = signingValue("ANDROID_KEY_ALIAS")
            keyPassword = signingValue("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".qa" }
        release {
            isMinifyEnabled = false
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(bom)
    androidTestImplementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
