import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies the Android library + Kotlin Android plugins and the shared Android
 * configuration. Every `:core:*` / `:feature:*` / `:sync-worker` module
 * applies this in place of repeating the same ~25 lines of build config.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // AGP 9 has built-in Kotlin support; applying `kotlin-android` here is
        // an error per the plugin's preflight check.
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = Versions.COMPILE_SDK
            defaultConfig {
                minSdk = Versions.MIN_SDK
            }
            compileOptions {
                sourceCompatibility = Versions.javaVersion
                targetCompatibility = Versions.javaVersion
                isCoreLibraryDesugaringEnabled = true
            }
        }
        configureKotlinJvmTarget()
        addCoreLibraryDesugaring()
        configureTestTasks()
        configureKover()
    }
}
