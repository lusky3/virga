import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType

/**
 * Enables Compose in an Android module and pulls in the standard Compose
 * dependency set. Apply *after* `virga.android.library` or
 * `virga.android.application`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.findByType<LibraryExtension>()?.apply { buildFeatures.compose = true }
        extensions.findByType<ApplicationExtension>()?.apply { buildFeatures.compose = true }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            add("implementation", platform(libs.library("compose-bom")))
            libs.findBundle("compose").orElseThrow().get().forEach { dep ->
                add("implementation", dep)
            }
            add("debugImplementation", libs.library("compose-ui-tooling"))
        }
    }
}
