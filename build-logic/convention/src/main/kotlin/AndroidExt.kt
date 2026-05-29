import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Configures the KGP DSL — replaces the AGP `kotlinOptions { }` block that was
 * removed in AGP 9. Sets a single jvmTarget for both Kotlin and Java compilation.
 */
internal fun Project.configureKotlinJvmTarget() {
    extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions {
        jvmTarget.set(Versions.jvmTarget)
    }
}

/** Adds the core-library-desugaring dependency once per module. */
internal fun Project.addCoreLibraryDesugaring() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        add("coreLibraryDesugaring", libs.library("desugar-jdk-libs"))
    }
}

/** Looks up a library by alias from a version catalog. */
internal fun VersionCatalog.library(alias: String) =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library aliased '$alias' in catalog") }

/** Apply JUnit Platform + a one-line summary listener to every Test task. */
internal fun Project.configureTestTasks() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("skipped", "failed")
        }
        addTestListener(object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: TestDescriptor) = Unit
            override fun beforeTest(testDescriptor: TestDescriptor) = Unit
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = Unit
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) {
                    logger.lifecycle(
                        "${project.path} tests: ${result.testCount} total, " +
                            "${result.successfulTestCount} passed, " +
                            "${result.failedTestCount} failed, " +
                            "${result.skippedTestCount} skipped",
                    )
                }
            }
        })
    }
}
