plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dansemur.ironman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dansemur.ironman"
        minSdk = 33 // Wear OS 4+ (supports Watch Face Format natively)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
        viewBinding = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.wearable)

    // Wear OS Tiles
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)

    // Complication Data Source Service
    implementation(libs.androidx.wear.watchface.complications.data.source)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)

    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.guava)
}
