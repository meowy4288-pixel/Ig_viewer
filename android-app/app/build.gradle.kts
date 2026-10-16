plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.igviewer.archive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.igviewer.archive"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.6"
    }

    buildTypes {
        release {
            // Optimized build for real use: R8 shrinks + optimizes, and Compose gets
            // far smoother (debug builds have no R8 and are known to jank when scrolling).
            // Signed with the debug key so it installs straight over the debug APK.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
    }
    // The Rust core ships as a JNI library per ABI.
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
    lint {
        // Lint's bundled engine cannot run under this JDK (fails parsing "25.0.4.1"),
        // which would abort release builds. Unit tests + the debug build cover us.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
