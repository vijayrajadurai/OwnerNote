plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.shopai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ownernote.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.8"

        // Production API (Render). Debug defaults to the local Owner Note backend.
        // Override with -PAPI_BASE_URL=http://<lan-ip>:4000 when testing on a physical device.
        buildConfigField("String", "API_BASE_URL", "\"https://shop-ai-api.onrender.com\"")
        // Sarvam AI TTS proxy (same values as EXPO_PUBLIC_TTS_PROXY_* in apps/mobile/eas.json).
        buildConfigField("String", "TTS_PROXY_URL", "\"https://store-accountant-tts-proxy.vercel.app\"")
        buildConfigField("String", "TTS_PROXY_KEY", "\"newonx2026secret\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("OwnerNote Keystore/ownernote.jks")
            storePassword = "Ownernote"
            keyAlias = "ownernote"
            keyPassword = "Ownernote"
        }
    }

    buildTypes {
        debug {
            val localUrl = (project.findProperty("API_BASE_URL") as String?) ?: "https://shop-ai-api.onrender.com"
            buildConfigField("String", "API_BASE_URL", "\"$localUrl\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    lint {
        // Lifecycle 2.9 (via the Rive runtime) ships a LiveData lint check that crashes on this AGP; no LiveData is used.
        disable += "NullSafeMutableLiveData"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.tesseract4android)
    // Handwritten notes only (free, on-device). Printed bills stay on Tesseract.
    implementation(libs.mlkit.text.recognition)
    // Product barcodes: Google's code scanner UI (no camera permission, model via Play services).
    implementation(libs.play.services.code.scanner)
    // KAI's animation rig (docs/KAI_RIVE_SPEC.md).
    implementation(libs.rive.android)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics) {
        exclude(group = "com.google.firebase", module = "firebase-analytics")
        exclude(group = "com.google.firebase", module = "firebase-analytics-ktx")
        exclude(group = "com.google.android.gms", module = "play-services-measurement")
        exclude(group = "com.google.android.gms", module = "play-services-measurement-api")
        exclude(group = "com.google.android.gms", module = "play-services-measurement-impl")
        exclude(group = "com.google.android.gms", module = "play-services-measurement-sdk")
        exclude(group = "com.google.android.gms", module = "play-services-measurement-sdk-api")
        exclude(group = "com.google.android.gms", module = "play-services-ads-identifier")
        exclude(group = "com.android.installreferrer", module = "installreferrer")
    }
    implementation("com.google.firebase:firebase-measurement-connector")
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.auth.api.phone)
    implementation(libs.play.integrity)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Books engine tests run real Room/SQLite on the JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Morning Work's SQL measured on a real SQLite with a large business (JVM, no device).
    testImplementation(libs.sqlite.jdbc)

    // On-device checks of the real OCR engines against sample photos.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Owner Note does not use ads or Google Analytics. Strip every transitive ads/analytics SDK
// so Play does not merge AD_ID / AdServices permissions.
configurations.configureEach {
    exclude(group = "com.google.firebase", module = "firebase-analytics")
    exclude(group = "com.google.firebase", module = "firebase-analytics-ktx")
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-identifier")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
    exclude(group = "com.google.android.gms", module = "play-services-measurement")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-api")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-impl")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-sdk")
    exclude(group = "com.google.android.gms", module = "play-services-measurement-sdk-api")
    exclude(group = "com.android.installreferrer", module = "installreferrer")
}

