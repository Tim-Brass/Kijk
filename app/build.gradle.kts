plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "nl.kijk.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.kijk.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = if (buildNumber >= 111) "0.2.${buildNumber - 111}" else "0.1.$buildNumber"   // vanaf build 111: 0.2.0
        // alleen ARM (tv's en boxen); scheelt veel MB's aan x86-bibliotheken
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Vaste sleutel, zodat nieuwe versies over de oude heen geïnstalleerd kunnen worden.
    signingConfigs {
        create("kijk") {
            storeFile = file("kijk.keystore")
            storePassword = "kijkkijk"
            keyAlias = "kijk"
            keyPassword = "kijkkijk"
        }
    }

    buildTypes {
        release {
            // R8: ongebruikte code weg en optimaliseren (sneller), maar namen blijven (zie proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("kijk")
        }
        debug {
            signingConfig = signingConfigs.getByName("kijk")
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
        // Media3 markeert veel API's als "unstable"; dat is hier een bewuste keuze.
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Dolby Digital (AC3/E-AC3), DTS e.d. zelf decoderen: veel tv's/boxen geven anders geen geluid
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    // live beeld van de deurbel via Homey (WebRTC); doet alleen iets als je het live beeld opent
    implementation("io.getstream:stream-webrtc-android:1.3.9")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
