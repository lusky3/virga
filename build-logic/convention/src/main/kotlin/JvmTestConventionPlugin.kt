import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * Wires the standard JUnit 5 + coroutines-test + Turbine + Truth + MockK
 * test set every module shares. Skip it on modules that only use Robolectric
 * with JUnit 4 (none today).
 */
class JvmTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            libs.findBundle("junit5").orElseThrow().get().forEach { dep ->
                add("testImplementation", dep)
            }
            add("testRuntimeOnly", libs.library("junit5-engine"))
            // Gradle 9 requires junit-platform-launcher to be on the test
            // runtime classpath when `useJUnitPlatform()` is in effect.
            add("testRuntimeOnly", libs.library("junit5-launcher"))
            add("testImplementation", libs.library("coroutines-test"))
            add("testImplementation", libs.library("turbine"))
            add("testImplementation", libs.library("truth"))
            add("testImplementation", libs.library("mockk"))
        }
    }
}
