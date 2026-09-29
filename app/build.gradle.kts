plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "edu.pickupauth"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.pickupauth"
        minSdk = 29          // Android 10
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-captura"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
