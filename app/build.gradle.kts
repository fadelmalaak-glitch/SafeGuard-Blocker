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
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Debug-signed so the APK installs out of the box via sideload (no keystore needed).
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    // Pure Android SDK implementation — zero external dependencies for maximum build stability.
}
