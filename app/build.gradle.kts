plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.universalcast.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.universalcast.receiver"
        minSdk = 23 // Android Keystore EC keys (KeyGenParameterSpec) require API 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Development mode only changes labelling/diagnostics. It NEVER enables plaintext or unauthenticated
            // endpoints: ConfigValidator rejects those in every mode.
            buildConfigField("String", "OPERATING_MODE", "\"development\"")
            buildConfigField("int", "LISTEN_PORT", "8765")
            // Extra addresses put in the pairing QR (comma-separated). 127.0.0.1 lets a USB phone reach the TV
            // emulator through `adb reverse` + `adb forward` (see BUILD.md). The TLS key pin still applies.
            buildConfigField("String", "EXTRA_PAIRING_ADDRESSES", "\"127.0.0.1\"")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            buildConfigField("String", "OPERATING_MODE", "\"production\"")
            buildConfigField("int", "LISTEN_PORT", "8765")
            buildConfigField("String", "EXTRA_PAIRING_ADDRESSES", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned unless you add your own signingConfig (see BUILD.md).
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("com.google.zxing:core:3.5.3")
    implementation("org.slf4j:slf4j-android:1.7.36")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(kotlin("test"))
}
