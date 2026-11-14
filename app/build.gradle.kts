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
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.snakeyaml)
    implementation(libs.commons.compress)
    // P6: XZ support for commons-compress (Debian .tar.xz extraction).
    // XZCompressorInputStream needs the XZ for Java runtime library.
    implementation("org.tukaani:xz:1.9")
    // P17.5: Termux:X11 from OUR fork (taqniamostakbal/termux-x11),
    // commit 7b3151d3525127216b78ad2b363b426e56f51312, which carries the
    // Mudif viewer edits (touch mode applied at startup, Mudif purple
    // keys). P17.3 proved the tiny-computer JitPack artifact silently
    // ignores edits to the vendored termux-x11/ copy; P17.4 proved the
    // vendored copy cannot be built as a local module (it lacks the
    // generated Prefs class and the hidden-API stubs). The fork is the
    // complete upstream repo with the 4 Mudif edits, so JitPack builds
    // it the same way it built the original. Keep the kotlin-stdlib
    // excludes: the module's transitive deps can pull kotlin-stdlib
    // 2.1.20, which conflicts with the project's Kotlin 2.0.20.
    // P18.1: fork commit fc815a5 adds the floating keyboard button
    // (FAB) that shows/hides the extra keys on demand.
    // P18.2: fork commit 38a73e4 fixes the drag: a plain single-finger
    // drag now moves the cursor in SimulatedTouch (the tactile-mouse
    // mode), and the FAB bottom margin is 91dp so it clears the 75dp
    // keys bar when shown.
    // P18.3: fork commit 22d647b restyles the keys bar as a light card
    // (white rounded keys, dark text, purple pressed, held key stays
    // purple), shrinks it to 56dp (28dp/row), and sets the FAB margin
    // to 72dp. The app-side settings screen + landscape lock are in
    // this PR (ViewerPrefs / SettingsActivity / X11Session).
    // P18.3.1: fork commit 63aca3c tints the 5 icon keys (arrows +
    // keyboard) dark on the light card, white when pressed/held — the
    // P18.3 restyle left the white icon drawables untinted, so the
    // icons were invisible on the white keys (rig FAIL item 3).
    // P18.5: fork commit b1c96fb adds the raw drag path in
    // SIMULATED_TOUCH (cursor follows the finger, bypassing
    // GestureDetector onScroll slop/batching).
    // p30: fork commit 89638a7 makes BACK exit the viewer to the
    // Mudif app (finish) instead of forwarding BACK to the X
    // desktop (the p29 rig FAIL: BACK never left the viewer, because
    // the foreground activity is the fork's MainActivity, not
    // VncActivity). The fix is in the fork's MainActivity
    // (OnBackInvokedCallback + dispatchKeyEvent + onBackPressed).
    implementation("com.github.taqniamostakbal:termux-x11:e8c222daf4ef5a78324bc5911af58df8e2bebeb0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    // NetHunter PTY terminal (Tiny Container's Global.terminalSession).
    // P5.8: the persistent PTY session that owns the container and takes
    // the GUI commands. Same JitPack repo, same tiny-computer org.
    // P5.8-fix: terminal-lib's pom pulls kotlin-stdlib 2.1.20, which
    // conflicts with the project's Kotlin 2.0.20 (same conflict that
    // forced the excludes on termux-x11 above). Exclude it: the lib's
    // classes are pure Java and never reference Kotlin at runtime
    // (verified against the real AAR classes.jar).
    implementation(libs.terminal.lib) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    // Phase 2 — Google Cast sender (live HLS, Default Media Receiver)
    // + route chooser dialog. No Remote Display API (deprecated 2021).
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
}
