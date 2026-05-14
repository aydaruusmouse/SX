import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

// How the phone reaches Laravel (port 8001 must match `php artisan serve --port=8001`):
//
// 1) Emulator (default if nothing set): http://10.0.2.2:8001 — Android maps this to your dev machine.
//
// 2) Physical phone + USB debugging — simplest fixed URL: in local.properties set
//      license.api.mode=adb_reverse
//    then once per cable session run: adb reverse tcp:8001 tcp:8001
//    The app uses http://127.0.0.1:8001; the phone's localhost is forwarded to your Mac's 8001.
//
// 3) Physical phone + Wi‑Fi same LAN — set full URL (phone cannot use "localhost" for your Mac):
//      license.api.base.url=http://YOUR_MAC_LAN_IP:8001
//    Mac IP: ipconfig getifaddr en0 (or en1). Not 192.168.x.1 (usually the router).
//
// Optional override: license.api.base.url always wins if set.
val licenseApiPort = (localProperties.getProperty("license.api.port") ?: "8001").trim()
val licenseApiMode = localProperties.getProperty("license.api.mode")?.trim()?.lowercase()
val licenseApiBaseUrl =
    localProperties.getProperty("license.api.base.url")?.trim()?.trimEnd('/')
        ?: when (licenseApiMode) {
            "adb_reverse", "usb", "device_localhost" ->
                "http://127.0.0.1:$licenseApiPort"
            else ->
                "http://10.0.2.2:$licenseApiPort"
        }

android {
    namespace = "com.sarif.auto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sarif.auto"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "LICENSE_API_BASE_URL", "\"$licenseApiBaseUrl\"")
        // Must match Laravel JWT issuer: typically APP_URL in license-server/.env (include port), e.g. http://127.0.0.1:8001
        buildConfigField("String", "LICENSE_JWT_ISS", "\"http://127.0.0.1:8001\"")
        buildConfigField("String", "LICENSE_JWT_AUD", "\"sarif-auto\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.auth0:java-jwt:4.4.0")
}
