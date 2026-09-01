plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arktube.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arktube.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-stage0"
    }

    // Signing config (keystore path/passwords) is the project owner's
    // own concern, passed through via env vars. Locally, with these
    // unset, `./gradlew assembleRelease` still works, it just produces
    // an unsigned APK you'd sign yourself.
    // `isNullOrBlank()` (not just a null check) matters here because
    // CI sets this from a GitHub Actions secret via an `env:` block --
    // when that secret isn't configured, the expression evaluating it
    // resolves to an *empty string*, not an unset var, so a plain
    // `!= null` check would still (wrongly) try `file("")` and fail
    // the build instead of falling back to unsigned, same as the
    // local no-env-vars-at-all case does.
    val releaseStorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    // Same optional, env-var-driven pattern as release below, but for
    // the debug keystore. Left unset, Gradle falls back to its own
    // implicit auto-generated ~/.android/debug.keystore like before --
    // this only kicks in when DEBUG_KEYSTORE_PATH is actually set (CI,
    // via the decoded repo secret).
    val debugStorePath = System.getenv("DEBUG_KEYSTORE_PATH")
    signingConfigs {
        if (!releaseStorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        if (!debugStorePath.isNullOrBlank()) {
            getByName("debug") {
                storeFile = file(debugStorePath)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEBUG_KEYSTORE_ALIAS")
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!releaseStorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Splash screen (AndroidX backport of the Android 12 SplashScreen
    // API) -- gives a consistent splash on API 24+ instead of only
    // API 31+, via Theme.ArkTubeApp.Starting below.
    implementation("androidx.core:core-splashscreen:1.0.1")
    // MediaSessionCompat/MediaMetadataCompat/PlaybackStateCompat, the
    // MediaStyle notification helper, and MediaButtonReceiver -- see
    // MediaPlaybackService.
    implementation("androidx.media:media:1.7.0")
    // Periodic background job for NotificationSyncWorker -- polls the
    // user's own YouTube notification inbox (via a headless WebView
    // reusing their existing m.youtube.com login) and mirrors new
    // items as native Android notifications. See that class's own
    // doc comment for why this is a WorkManager job rather than the
    // YouTube Data API/OAuth.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
