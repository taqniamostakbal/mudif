plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bolm.mudif.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bolm.mudif.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // No custom signing config: the default debug key is used,
            // exactly as the app module does for its debug builds.
        }
        release {
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
}

dependencies {
    // Versions pinned directly here (not in the version catalog) so the
    // tv module never edits the app module's catalog.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
