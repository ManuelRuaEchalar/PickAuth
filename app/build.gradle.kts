import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// URL del Apps Script y clave de subida: viven en local.properties (no se versiona) para no publicarlas.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String) = localProps.getProperty(key, "").replace("\"", "")

android {
    namespace = "edu.pickupauth"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.pickupauth"
        minSdk = 29          // Android 10
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-recoleccion"
        buildConfigField("String", "UPLOAD_URL", "\"${localProp("pickupauth.uploadUrl")}\"")
        buildConfigField("String", "UPLOAD_TOKEN", "\"${localProp("pickupauth.uploadToken")}\"")
    }

    buildFeatures { buildConfig = true }

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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
