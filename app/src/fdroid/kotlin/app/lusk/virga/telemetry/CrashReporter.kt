package app.lusk.virga.telemetry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid no-op crash reporter. The F-Droid flavor compiles NO Sentry SDK at all
 * (see app/build.gradle.kts — sentry-android-core is github/play-only), so this build
 * carries zero telemetry code and trips no F-Droid "Tracking" anti-feature.
 *
 * Same public surface as the real Sentry-backed CrashReporter (src/sentry) so shared
 * code (VirgaApplication) injects [CrashReporter] without knowing the flavor.
 * [isAvailable] is always false and [setEnabled] does nothing.
 */
@Singleton
class CrashReporter @Inject constructor(
    @Suppress("UnusedPrivateProperty") @ApplicationContext private val context: Context,
) {
    /** Always false on F-Droid: there is no crash-reporting backend to enable. */
    val isAvailable: Boolean get() = false

    /** No-op: nothing to initialize or tear down on F-Droid. */
    fun setEnabled(enabled: Boolean) = Unit
}
