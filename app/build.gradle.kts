plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bolm.mudif"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bolm.mudif"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Pinned debug keystore (app/debug.keystore): every CI build signs
            // with the SAME key so updates install in-place instead of
            // failing with "signature does not match". Fingerprint recorded in
            // app/debug.keystore.fingerprint.txt.
            signingConfig = signingConfigs.getByName("debug")
        }
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
        viewBinding = true
    }
    packaging {
        jniLibs {
            // Legacy packaging keeps .so files extractable:
            // required for the bootstrap symlink trick (see doc/ARCHITECTURE.md)
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.snakeyaml)
    implementation(libs.commons.compress)
    // Termux:X11 as local module (pure Java, prebuilt native .so)
    implementation(project(":termux-x11"))
}
