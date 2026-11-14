import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The ONE permanent release signing key (see the "Release signing" note in
// android/README.md): scripts/build-apk-docker.sh mounts the host signing
// directory read-only and exports these at the in-container paths; a local
// (non-Docker) release build exports them at the host paths. Absent (the
// JVM unit-test runs, a keyless local build) no stableRelease config is
// created and the release type falls back to the debug signing config
// below - the published path always signs with the permanent key, never a
// regenerated per-build one.
val releaseKeystoreFile: String? = System.getenv("DASHHOME_RELEASE_KEYSTORE")
val releaseKeystorePropsFile: String? = System.getenv("DASHHOME_RELEASE_KEYSTORE_PROPS")

android {
    namespace = "com.jsnto.dashhome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jsnto.dashhome"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    signingConfigs {
        if (!releaseKeystoreFile.isNullOrBlank() && !releaseKeystorePropsFile.isNullOrBlank()) {
            val props = Properties()
            File(releaseKeystorePropsFile).inputStream().use { props.load(it) }
            create("stableRelease") {
                storeFile = File(releaseKeystoreFile)
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the permanent release key when it is provided
            // (the published path); otherwise fall back to the debug
            // signing config so a keyless build still packages.
            signingConfig =
                signingConfigs.findByName("stableRelease") ?: signingConfigs.getByName("debug")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // The settings "Update" section's running version (BuildConfig.VERSION_NAME).
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test") {
            // The shared board-layout golden vectors
            // (frontend/test-fixtures/board-layout) ride the unit-test
            // classpath: BoardVectorTest reads the very files the Go and
            // web suites assert against.
            resources.srcDir("../../frontend/test-fixtures")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // Markdown rendering for the Hermes push feed.
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)

    testImplementation(libs.junit)
    // Deterministic test dispatcher for the ChatState send tests.
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json ships with the Android runtime; the artifact mirrors it in
    // JVM unit tests.
    testImplementation(libs.org.json)

    // Robolectric render tests: run the real Compose + Markwon stack on the
    // JVM (no emulator) to prove the push-feed body is drawn visibly.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.tooling)
}
