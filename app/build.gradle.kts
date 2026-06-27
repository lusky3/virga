import com.android.build.api.dsl.ApplicationProductFlavor
import com.android.build.api.dsl.VariantDimension
import java.util.Properties

plugins {
    id("virga.android.application")
    id("virga.android.compose")
    id("virga.android.hilt")
    id("virga.jvm.test")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.sentry.android)
    alias(libs.plugins.roborazzi)
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

// rclone version single source of truth: parse the same shell-sourceable env file
// rclone-build and the CI workflows read, so the version surfaced in the About
// screen never drifts from the binary that actually ships.
val rcloneVersion: String = rootProject.file("scripts/rclone-versions.env").readLines()
    .first { it.trim().startsWith("RCLONE_VERSION=") }
    .substringAfter('=').trim()

// In-app update check (foss flavor only — polls the GitHub Releases API). On by
// default for GitHub / sideload installs that have no store to update them.
// F-Droid ships updates through its own client, so an app self-checking GitHub
// is both redundant and against the grain of F-Droid's "no unsolicited network"
// expectation. The F-Droid build recipe flips this off without patching source,
// via `-Pvirga.enableUpdateCheck=false` or a line in gradle.properties.
val enableUpdateCheck: Boolean =
    (project.findProperty("virga.enableUpdateCheck") as String?)?.toBoolean() ?: true

// Typed BuildConfig field helpers shared by defaultConfig and the per-flavor
// surface below. Centralising the `"String"`/`"boolean"` type literals keeps each
// at a single occurrence (no StringLiteralDuplication) and the quoting consistent.
private fun VariantDimension.stringField(field: String, value: String) =
    buildConfigField("String", field, "\"$value\"")

private fun VariantDimension.boolField(field: String, value: Boolean) =
    buildConfigField("boolean", field, value.toString())

// Single definition of the per-flavor BuildConfig surface. The three distribution
// flavors are structurally identical, so routing every field through here keeps each
// field-name string literal at one occurrence and stops the flavors from drifting
// apart. The flavor's own name supplies DISTRIBUTION, so it isn't repeated per flavor.
private fun ApplicationProductFlavor.distribution(
    sdcardAccess: Boolean,
    updateCheck: Boolean,
    crashAvailable: Boolean,
    crashDefaultOn: Boolean,
) {
    dimension = "distribution"
    boolField("ALLOW_BYO_OAUTH", true)
    boolField("SDCARD_ACCESS_AVAILABLE", sdcardAccess)
    stringField("DISTRIBUTION", name)
    boolField("ENABLE_UPDATE_CHECK", updateCheck)
    boolField("CRASH_REPORTING_AVAILABLE", crashAvailable)
    boolField("CRASH_REPORTING_DEFAULT_ON", crashDefaultOn)
}

// F-Droid's contract is fully FOSS with no baked service secrets: it ships the no-op
// CrashReporter (no Sentry SDK) and is BYO-OAuth only. defaultConfig may carry CI/local
// OAuth client IDs + a Sentry DSN, so the fdroid flavor explicitly overrides them back
// to empty/unset — enforcing the no-baked-secrets contract in code rather than relying
// solely on F-Droid's secret-free build environment.
private fun ApplicationProductFlavor.clearBakedServiceConfig() {
    stringField("SENTRY_DSN", "")
    stringField("OAUTH_CLIENT_ID_GDRIVE", "")
    stringField("OAUTH_CLIENT_ID_ONEDRIVE", "")
    stringField("OAUTH_CLIENT_ID_DROPBOX", "")
    stringField("OAUTH_CLIENT_ID_PCLOUD", "")
    manifestPlaceholders["googleOAuthScheme"] = "com.googleusercontent.apps.unset"
}

android {
    namespace = "app.lusk.virga"

    defaultConfig {
        applicationId = "app.lusk.virga"
        // Injectable from CI (release.yml derives these from the git tag) so the
        // published build carries the real version, not a hardcoded 1 / 0.1.0.
        versionCode = System.getenv("VIRGA_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VIRGA_VERSION_NAME") ?: "0.3.0"
        testInstrumentationRunner = "app.lusk.virga.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        // OAuth client IDs are public-by-design for PKCE mobile clients but
        // identify the developer's specific OAuth registration; populate via
        // local.properties (oauthClientId.gdrive=…) or VIRGA_OAUTH_CLIENT_ID_*
        // env vars. Empty defaults keep CI builds compiling.
        val gdriveClientId = oauthClientId("gdrive")
        stringField("OAUTH_CLIENT_ID_GDRIVE", gdriveClientId)
        stringField("OAUTH_CLIENT_ID_ONEDRIVE", oauthClientId("onedrive"))
        stringField("OAUTH_CLIENT_ID_DROPBOX", oauthClientId("dropbox"))
        stringField("OAUTH_CLIENT_ID_PCLOUD", oauthClientId("pcloud"))

        // Opt-in crash reporting endpoint (empty = disabled). Read at runtime by
        // CrashReporter, which only initializes Sentry when this is non-blank AND the
        // user has enabled the Settings toggle.
        stringField("SENTRY_DSN", sentryDsn())

        // Bundled rclone version (from scripts/rclone-versions.env) so the About
        // screen can show exactly which rclone build ships in this APK.
        stringField("RCLONE_VERSION", rcloneVersion)

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

    // Compose unit tests (Roborazzi screenshot tests) run under Robolectric and
    // need the merged AndroidManifest + resources on the unit-test classpath.
    // They are JUnit 4 (the form `compose-ui-test-junit4` expects) and run via the
    // Vintage engine alongside the module's JUnit 5 tests.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // GitHub sideload (per-ABI APKs). Full filesystem/SD-card access, BYO OAuth,
        // the GitHub-Releases self-update check, and opt-OUT crash reporting (Sentry
        // compiled in; on by default, disclosed + toggleable at first launch).
        create("github") {
            // Crash reporting present; default enabled (opt-out) for this direct channel.
            distribution(
                sdcardAccess = true,
                updateCheck = enableUpdateCheck,
                crashAvailable = true,
                crashDefaultOn = true,
            )
        }
        // F-Droid (per-ABI APKs). Fully FOSS: NO Sentry SDK compiled in (src/fdroid
        // ships a no-op CrashReporter), NO self-update check, NO baked OAuth client
        // IDs (BYO-keys only). Full filesystem/SD-card access is allowed on F-Droid.
        create("fdroid") {
            distribution(
                sdcardAccess = true,
                updateCheck = false,
                crashAvailable = false,
                crashDefaultOn = false,
            )
            // Strip any defaultConfig-inherited OAuth client IDs / Sentry DSN so the
            // FOSS build never bakes developer secrets (BYO-OAuth, no crash reporting).
            clearBakedServiceConfig()
        }
        // Google Play (AAB). Play policy is hostile to MANAGE_EXTERNAL_STORAGE for
        // general-purpose sync apps, so the play manifest strips it (SAF instead);
        // SDCARD_ACCESS_AVAILABLE=false drives the UI explanation. In-app-update, BYO
        // OAuth, and opt-IN crash reporting (Sentry compiled in; off until consent).
        create("play") {
            // In-app-update, BYO OAuth, and opt-IN crash reporting (Sentry compiled
            // in; off until consent).
            distribution(
                sdcardAccess = false,
                updateCheck = false,
                crashAvailable = true,
                crashDefaultOn = false,
            )
        }
    }

    // Source-set wiring for the flavor split:
    //  - src/foss (GitHub-Releases update checker) is shared by github + fdroid.
    //  - src/sentry (the real Sentry CrashReporter) is shared by github + play;
    //    fdroid gets the no-op CrashReporter in src/fdroid (no Sentry SDK at all).
    sourceSets {
        getByName("github") {
            kotlin.srcDir("src/foss/kotlin")
            kotlin.srcDir("src/sentry/kotlin")
        }
        getByName("fdroid") { kotlin.srcDir("src/foss/kotlin") }
        getByName("play") { kotlin.srcDir("src/sentry/kotlin") }
    }

    splits {
        abi {
            // ABI splits (per-ABI APKs) are incompatible with AAB bundling: AGP errors
            // ("Multiple shrunk-resources files…") if both are active, and an app bundle
            // does its own ABI splitting on Play's servers. So enable splits only when
            // NOT building a bundle — foss ships per-ABI APKs (release.yml assembles),
            // play ships an AAB (release.yml bundles). Each release.yml build is a
            // separate Gradle invocation, so task-name detection is reliable.
            val buildingBundle = gradle.startParameter.taskNames.any {
                it.contains("bundle", ignoreCase = true)
            }
            isEnable = !buildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // No universal APK: librclone.so is ~100 MB per ABI, so an all-ABI
            // universal APK would be ~300 MB. Ship per-ABI APKs only (each carries
            // a distinct versionCode — see the androidComponents block below).
            isUniversalApk = false
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
                storePassword = keystoreProp("debugStoreCredential", "VIRGA_DEBUG_KEYSTORE_CREDENTIAL")
                keyAlias = keystoreProp("debugKeyAlias", "VIRGA_DEBUG_KEY_ALIAS")
                keyPassword = keystoreProp("debugKeyCredential", "VIRGA_DEBUG_KEY_CREDENTIAL")
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
    // appcompat: AppCompatActivity base (locale override) + AppCompat-compatible host
    // for BiometricPrompt. biometric: app-lock (D1). Wired up by later 0.3.0 items;
    // added here (F2) so the base-class/dependency churn lands once.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // lifecycle-process: ProcessLifecycleOwner for AppLockViewModel grace-period re-lock.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // navigationevent-compose: bridges the activity's view-tree NavigationEventDispatcher
    // into the LocalNavigationEventDispatcherOwner composition local that nav3 1.1.x's
    // NavDisplay reads (activity-compose 1.13 sets the view-tree owner but not the local).
    implementation(libs.androidx.navigationevent.compose)
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
    // Roborazzi screenshot tests (Robolectric + Compose UI test) for app-owned
    // screens. Mirrors feature:sync; run via the JUnit Vintage engine.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.material3)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testRuntimeOnly(libs.junit5.vintage)
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
    // GitHub-Releases self-update checker (src/foss) uses OkHttp; shared by the
    // github + fdroid flavors (fdroid compiles it but ENABLE_UPDATE_CHECK=false).
    "githubImplementation"(libs.okhttp)
    "fdroidImplementation"(libs.okhttp)
    // Play update flow (in-app update API).
    "playImplementation"(libs.play.appupdate.ktx)

    // Crash reporting (MIT-licensed Sentry SDK) — compiled into github + play ONLY.
    // The fdroid flavor ships a no-op CrashReporter (src/fdroid) with no Sentry SDK at
    // all, so the F-Droid build carries zero telemetry code (no Tracking anti-feature).
    // auto-init is disabled in the manifest; SentryAndroid.init runs only after consent.
    "githubImplementation"(libs.sentry.android.core)
    "playImplementation"(libs.sentry.android.core)

    // Glance home-screen widget + Quick Settings tile (Phase 3 WS3.6)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.profileinstaller)
    "baselineProfile"(project(":benchmark"))
}
