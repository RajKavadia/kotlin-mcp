plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.counterproof"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.counterproof"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // Debuggable + non-minified: the exact shape kotlin-runtime-mcp needs.
            // `adb jdwp` lists this build; release never appears there.
            isMinifyEnabled = false
            isDebuggable = true
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
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.navigation:navigation-fragment:2.10.2")
}
