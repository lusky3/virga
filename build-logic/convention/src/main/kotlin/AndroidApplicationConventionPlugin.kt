import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.VariantOutput
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies the Android application + Kotlin Android plugins and the shared
 * Android configuration. The :app module supplies the application-specific
 * bits (flavors, ABI splits, packaging, BuildConfig fields).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // AGP 9 has built-in Kotlin support; applying `kotlin-android` here is
        // an error per the plugin's preflight check.
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = Versions.COMPILE_SDK
            defaultConfig {
                minSdk = Versions.MIN_SDK
                targetSdk = Versions.TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = Versions.javaVersion
                targetCompatibility = Versions.javaVersion
                isCoreLibraryDesugaringEnabled = true
            }
        }
        // Per-ABI versionCode offset. The :app ABI splits emit several APKs that would
        // otherwise share the base versionCode — Play rejects that, and F-Droid/sideload
        // users get indistinguishable artifacts. Give each ABI a distinct, monotonic code
        // (base * 10 + offset); the play AAB has no ABI filter so it keeps base * 10.
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                variant.outputs.forEach { output ->
                    val base = output.versionCode.orNull ?: 1
                    output.versionCode.set(base * 10 + abiVersionCodeOffset(output))
                }
            }
        }
        configureKotlinJvmTarget()
        addCoreLibraryDesugaring()
        configureTestTasks()
        configureKover()
    }
}

/** Distinct offset per ABI split output (0 for the no-filter AAB/base output). */
private fun abiVersionCodeOffset(output: VariantOutput): Int = when (
    output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
) {
    "armeabi-v7a" -> 1
    "arm64-v8a" -> 2
    "x86_64" -> 3
    else -> 0
}
