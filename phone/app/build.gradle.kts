plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "uk.krodity.usbspeaker"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "uk.krodity.usbspeaker"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        base.archivesName = "usb-speaker-$versionName"
    }

    signingConfigs {
        // Pinned rather than left to AGP's default: AGP 9 resolves the debug
        // keystore at the XDG path, and letting it drift produces APKs signed
        // with a different key than the ones already on the phones, failing an
        // update with INSTALL_FAILED_UPDATE_INCOMPATIBLE. See pc-remote.
        getByName("debug") {
            storeFile = File(System.getProperty("user.home"), ".config/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
