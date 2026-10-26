plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.muxover.oriel.home"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // Kept at 28 on purpose: Oriel Settings joins Wi-Fi networks and switches Wi-Fi and Bluetooth,
        // which apps targeting 29+ can no longer do on Android TV builds without a system signature.
        targetSdk = 28
        versionCode = 4
        versionName = "1.0.0"
    }

    flavorDimensions += "board"
    productFlavors {
        // Any Android TV.
        create("standard") {
            dimension = "board"
            applicationId = "com.muxover.oriel"
        }
        // Hikeen boards (many HiSilicon TVs) force-close the previous app on every launch unless its package
        // is on a built-in list; this package name is on that list, so the home screen is never killed.
        create("hikeen") {
            dimension = "board"
            applicationId = "com.zeasn.launcher"
        }
    }

    buildTypes {
        release {
            // No shrinking: the root helper (Injector) is started by class name through app_process.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 28 is deliberate (see defaultConfig); Oriel is installed by its own installer, not from Play.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
}
