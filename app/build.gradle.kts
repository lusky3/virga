plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.lusk.virga"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lusk.virga"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            // F-Droid / GitHub. No proprietary dependencies.
            buildConfigField("boolean", "ALLOW_BYO_OAUTH", "true")
        }
        create("play") {
            dimension = "distribution"
            // Google Play distribution.
            buildConfigField("boolean", "ALLOW_BYO_OAUTH", "true")
        }
    }

    // Default OAuth client IDs (public by design for mobile apps). Real values
    // are injected at release time; placeholders keep debug builds compiling.
    defaultConfig {
        buildConfigField("String", "OAUTH_CLIENT_ID_GDRIVE", "\"\"")
        buildConfigField("String", "OAUTH_CLIENT_ID_ONEDRIVE", "\"\"")
        buildConfigField("String", "OAUTH_CLIENT_ID_DROPBOX", "\"\"")
        buildConfigField("String", "OAUTH_CLIENT_ID_PCLOUD", "\"\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true // GitHub/F-Droid sideload convenience.
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // The rclone binary ships as lib/<abi>/librclone.so and must be extracted to
    // disk (not loaded from the APK) so we can exec it as a child process.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The rclone binary is a complete Go ELF, already stripped. Tell AGP
            // not to run NDK strip on it (it is not a normal JNI library).
            keepDebugSymbols += "**/librclone.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:storage"))
    implementation(project(":sync-worker"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:remotes"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:explorer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.android.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.hilt.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
