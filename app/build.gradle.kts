plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paleblue"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paleblue"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Audio must not be re-compressed by AAPT or it can't be opened as a file descriptor.
    androidResources { noCompress += listOf("ogg", "wav") }
    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Drop the RayNeo SDK .aar files into app/libs/ (see libs/README.md)
    implementation(files("libs").asFileTree.matching { include("*.aar") })
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
