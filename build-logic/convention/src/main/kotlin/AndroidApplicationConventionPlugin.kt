import com.android.build.api.dsl.ApplicationExtension
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
        configureKotlinJvmTarget()
        addCoreLibraryDesugaring()
        configureTestTasks()
    }
}
