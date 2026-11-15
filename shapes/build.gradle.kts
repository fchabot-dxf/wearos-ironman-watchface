plugins {
    alias(libs.plugins.android.application)
}

// Resource-only Watch Face Format package (no Kotlin/Java code).
// One WFF watch face per APK, so this face ships separately from :app.
android {
    namespace = "com.dansemur.shapes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dansemur.shapes"
        minSdk = 33 // Wear OS 4+ (Watch Face Format)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
