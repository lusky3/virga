import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Centralised JDK / SDK constants used by every convention plugin. */
internal object Versions {
    val javaVersion = JavaVersion.VERSION_17
    val jvmTarget = JvmTarget.JVM_17
    const val MIN_SDK = 26
    const val TARGET_SDK = 36
    const val COMPILE_SDK = 36
}
