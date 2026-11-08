plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.safeguard.blocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.safeguard.blocker"
        minSdk = 24
        // Target 33 on purpose: avoids the stricter Android 14 (API 34) foreground-service-type
        // enforcement so the app installs and runs reliably on sideloaded devices, including
        // Android 15 phones such as the Oppo Reno 15. Not published to Play, so this is fine.
        targetSdk = 33
        versionCode = 6
        versionName = "3.2"
    }

    signingConfigs {
        create("release") {
            // Stable, committed keystore so every build shares one signature. This fixes
            // "App not installed" caused by signature mismatches across rebuilds, and a
            // proper (non-debug) signature is accepted more readily by OEM installers
            // such as Oppo/ColorOS. For a personal self-control app this is acceptable.
            storeFile = file("safeguard-release.keystore")
            storePassword = "safeguard2024"
            keyAlias = "safeguard"
            keyPassword = "safeguard2024"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Pure Android SDK implementation — zero external dependencies for maximum build stability.
}
