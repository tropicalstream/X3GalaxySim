import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fish Audio config (app/tools/fish_audio.config) baked into BuildConfig so the
// app can synthesize the crew voices at runtime.
val fishCfg = Properties().apply {
    val f = file("tools/fish_audio.config")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun fishCfgValue(key: String): String = (fishCfg.getProperty(key) ?: "").trim()

android {
    namespace = "com.rayneo.x3constellation"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rayneo.x3constellation"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-alpha"

        buildConfigField("String", "FISH_API_KEY", "\"${fishCfgValue("API_KEY")}\"")
        buildConfigField("String", "FISH_MODEL", "\"${fishCfgValue("MODEL")}\"")
        buildConfigField("String", "FISH_VOICE_NAVIGATION", "\"${fishCfgValue("NAVIGATION_VOICE_ID")}\"")
        buildConfigField("String", "FISH_VOICE_SCIENCE", "\"${fishCfgValue("SCIENCE_VOICE_ID")}\"")
        buildConfigField("String", "FISH_VOICE_ENGINEERING", "\"${fishCfgValue("ENGINEERING_VOICE_ID")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    // Keep the voice clips uncompressed so they play directly.
    androidResources {
        noCompress += "wav"
    }
}

// Friendly APK name -> SpaceX3Tour-debug.apk
base {
    archivesName.set("SpaceX3Tour")
}

dependencies {
    implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    implementation(files("libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
