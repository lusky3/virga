import java.util.Properties

plugins {
    id("virga.android.application")
    id("virga.android.compose")
    id("virga.android.hilt")
    id("virga.jvm.test")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.sentry.android)
}

// OAuth client IDs are public-by-design for mobile apps but identify the
// developer's specific OAuth client, so we keep them out of git. Reads from
// local.properties (gitignored) with optional env-var override for CI.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun oauthClientId(provider: String): String =
    System.getenv("VIRGA_OAUTH_CLIENT_ID_${provider.uppercase()}")
        ?: localProps.getProperty("oauthClientId.$provider")
        ?: ""

// Sentry DSN for opt-in crash reporting. Like the OAuth client IDs it is developer-
// specific and kept out of git (local.properties / env). An empty default means crash
// reporting is unavailable — CrashReporter no-ops — so contributor and CI builds
// without the secret behave exactly as before (no telemetry, no network).
fun sentryDsn(): String =
    System.getenv("VIRGA_SENTRY_DSN")
        ?: localProps.getProperty("sentryDsn")
        ?: ""

// Release signing. The keystore and its passwords never enter git: read from
// keystore.properties (gitignored) for local release builds, with env-var
// overrides (VIRGA_KEYSTORE_*) so CI can inject secrets. storeFile is resolved
// relative to the project root. When no keystore is configured (e.g. an open-
// source contributor's CI without the secrets) release stays unsigned rather
// than failing the build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun keystoreProp(key: String, env: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(key)
val releaseStoreFile: java.io.File? =
    keystoreProp("storeFile", "VIRGA_KEYSTORE_FILE")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

// Optional debug-signing override. Locally, AGP signs debug with the default
// ~/.android/debug.keystore — no config needed. On a fresh CI runner that
// keystore doesn't exist, so AGP would mint a throwaway key whose SHA wouldn't
// match the registered debug OAuth client or the debug App Links assetlinks.
// When VIRGA_DEBUG_KEYSTORE_FILE (or keystore.properties debugStoreFile) is
// provided, pin the debug signingConfig to it so CI debug builds get the stable
// debug signature. Absent it, the default debug keystore is used unchanged.
val debugStoreFile: java.io.File? =
    keystoreProp("debugStoreFile", "VIRGA_DEBUG_KEYSTORE_FILE")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

// In-app update check (foss flavor only — polls the GitHub Releases API). On by
// default for GitHub / sideload installs that have no store to update them.
// F-Droid ships updates through its own client, so an app self-checking GitHub
// is both redundant and against the grain of F-Droid's "no unsolicited network"
// expectation. The F-Droid build recipe flips this off without patching source,
// via `-Pvirga.enableUpdateCheck=false` or a line in gradle.properties.
val enableUpdateCheck: Boolean =
    (project.findProperty("virga.enableUpdateCheck") as String?)?.toBoolean() ?: true

android {
    namespace = "app.lusk.virga"

    defaultConfig {
        applicationId = "app.lusk.virga"
        // Injectable from CI (release.yml derives these from the git tag) so the
        // published build carries the real version, not a hardcoded 1 / 0.1.0.
        versionCode = System.getenv("VIRGA_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VIRGA_VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "app.lusk.virga.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        // OAuth client IDs are public-by-design for PKCE mobile clients but
        // identify the developer's specific OAuth registration; populate via
        // local.properties (oauthClientId.gdrive=…) or VIRGA_OAUTH_CLIENT_ID_*
        // env vars. Empty defaults keep CI builds compiling.
        val gdriveClientId = oauthClientId("gdrive")
        buildConfigField("String", "OAUTH_CLIENT_ID_GDRIVE", "\"$gdriveClientId\"")
        buildConfigField("String", "OAUTH_CLIENT_ID_ONEDRIVE", "\"${oauthClientId("onedrive")}\"")
        buildConfigField("String", "OAUTH_CLIENT_ID_DROPBOX", "\"${oauthClientId("dropbox")}\"")
        buildConfigField("String", "OAUTH_CLIENT_ID_PCLOUD", "\"${oauthClientId("pcloud")}\"")

        // Opt-in crash reporting endpoint (empty = disabled). Read at runtime by
        // CrashReporter, which only initializes Sentry when this is non-blank AND the
        // user has enabled the Settings toggle.
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn()}\"")

        // Google Android OAuth clients require a redirect URI scheme of the
        // form com.googleusercontent.apps.<reversed-client-id>. The reversed
        // part is just the client ID with the .apps.googleusercontent.com
        // suffix stripped — there's no actual byte-reversal. Pass it to the
        // manifest as a placeholder so the OAuthRedirectActivity intent-filter
        // can advertise the right scheme.
        manifestPlaceholders["googleOAuthScheme"] = gdriveClientId
            .removeSuffix(".apps.googleusercontent.com")
            .takeIf { it.isNotBlank() }
            ?.let { "com.googleusercontent.apps.$it" }
            ?: "com.googleusercontent.apps.unset"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // FOSS distribution (F-Droid / GitHub sideload). MANAGE_EXTERNAL_STORAGE
        // is permitted here — the user has full filesystem and SD-card access.
        // BYO OAuth is exposed so users can substitute their own client IDs.
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_BYO_OAUTH", "true")
            buildConfigField("boolean", "SDCARD_ACCESS_AVAILABLE", "true")
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
            // GitHub Releases self-check — true for GitHub/sideload, flipped off
            // by the F-Droid build recipe (see enableUpdateCheck above).
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", enableUpdateCheck.toString())
        }
        // Google Play distribution. Play policy is hostile to
        // MANAGE_EXTERNAL_STORAGE for general-purpose sync apps; we still
        // declare the permission but flag the build so the UI explains that
        // SD-card access is best-effort and may be revoked by Play review.
        // BYO OAuth stays available so power users on Play can still use their
        // own client IDs — it is a build-config gate, not network behavior, and
        // Play does not forbid it.
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_BYO_OAUTH", "true")
            buildConfigField("boolean", "SDCARD_ACCESS_AVAILABLE", "false")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProp("storePassword", "VIRGA_KEYSTORE_PASSWORD")
                keyAlias = keystoreProp("keyAlias", "VIRGA_KEY_ALIAS")
                keyPassword = keystoreProp("keyPassword", "VIRGA_KEY_PASSWORD")
            }
        }
        // Override AGP's built-in debug signingConfig only when a debug keystore
        // is explicitly supplied (CI). Locally this is skipped, so AGP keeps using
        // ~/.android/debug.keystore. The `debug` build type already points at this
        // config by default, so reconfiguring it is enough.
        debugStoreFile?.let { f ->
            getByName("debug") {
                storeFile = f
                storePassword = keystoreProp("debugStorePassword", "VIRGA_DEBUG_KEYSTORE_PASSWORD")
                keyAlias = keystoreProp("debugKeyAlias", "VIRGA_DEBUG_KEY_ALIAS")
                keyPassword = keystoreProp("debugKeyPassword", "VIRGA_DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isDebuggable = false // explicit guard (AGP default) so release can never ship debuggable
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the upload keystore when configured (keystore.properties
            // or VIRGA_KEYSTORE_* env vars). The App Links assetlinks.json trusts
            // this key's SHA-256, so an unsigned/wrong-key release would fail
            // domain verification. Left unsigned only when no keystore is present.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // The rclone binary ships as lib/<abi>/librclone.so and must be extracted
    // to disk (not loaded from the APK) so we can exec it as a child process.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Only our Go binary is a complete already-stripped ELF that the
            // NDK strip tool can't process — scope this to the precise path so
            // unrelated transitive .so files still get stripped normally (L4).
            keepDebugSymbols += "**/lib/*/librclone.so"
        }
    }
}

// Sentry Gradle plugin — used ONLY to upload R8 mapping files (and source context) so
// release-build crash reports symbolicate in Sentry. It deliberately does NOT manage the
// SDK or instrument bytecode: we own the `sentry-android-core` dependency and a manual,
// opt-in init in CrashReporter, with auto-init disabled in the manifest.
sentry {
    org.set(System.getenv("SENTRY_ORG") ?: "lusktech")
    // Sentry project slug; override at build time with SENTRY_PROJECT if it ever changes.
    projectName.set(System.getenv("SENTRY_PROJECT") ?: "virga")
    authToken.set(System.getenv("SENTRY_AUTH_TOKEN"))

    // Don't let the plugin add the SDK (avoids drift with our pinned 8.43.0) and don't
    // auto-instrument bytecode — that would wrap our OkHttp / file IO and capture request
    // data, which is at odds with the opt-in privacy posture. We want symbolication only.
    autoInstallation { enabled.set(false) }
    tracingInstrumentation { enabled.set(false) }

    // Upload only when a build-time auth token is present, so tokenless contributor /
    // F-Droid / CI builds never fail and never phone home at build time. The R8 mapping
    // is still bundled + UUID-tagged so a manual upload can symbolicate later.
    val hasToken = !System.getenv("SENTRY_AUTH_TOKEN").isNullOrBlank()
    autoUploadProguardMapping.set(hasToken)
    includeSourceContext.set(hasToken)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    // Direct rclone use (OAuthConfig DI module) — explicit now that core:data no
    // longer re-exports rclone via api().
    implementation(project(":core:rclone"))
    implementation(project(":sync-worker"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:remotes"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:explorer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.android.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.serialization.json)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    ksp(libs.hilt.work.compiler)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.truth)
    // Hilt testing — required to bring up @AndroidEntryPoint activities on
    // device. Activate by replacing the @AndroidEntryPoint host with a
    // HiltTestApplication via a custom runner; see HiltComposeSmokeTest below
    // for the minimal pattern. Tests that don't need Hilt can use
    // createAndroidComposeRule<ComponentActivity>() directly.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.coroutines.test)

    // The baseline profile is installed at build time by ProfileInstaller; the
    // dependency below is what wires the generated file into the APK.
    // FOSS update checker uses OkHttp to poll the GitHub Releases API.
    "fossImplementation"(libs.okhttp)
    // Play update flow (in-app update API).
    "playImplementation"(libs.play.appupdate.ktx)

    // Opt-in crash reporting (both flavors). MIT-licensed SDK; auto-init is disabled
    // in the manifest and Sentry is initialized manually by CrashReporter only after
    // the user opts in, so the FOSS build makes no telemetry calls by default.
    implementation(libs.sentry.android.core)

    // Glance home-screen widget + Quick Settings tile (Phase 3 WS3.6)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.profileinstaller)
    "baselineProfile"(project(":benchmark"))
}
