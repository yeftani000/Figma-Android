plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key comes from GitHub Actions secrets (see .github/workflows/build.yml).
// Without them the build falls back to the throwaway debug key.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.example.figmawrapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.figmawrapper"
        minSdk = 26
        targetSdk = 34
        // CI passes -PversionName / -PversionCode (taken from the release tag / run number)
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("shared") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("shared")
                            else signingConfigs.getByName("debug")
        }
    }

    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
