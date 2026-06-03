package app.lusk.virga.telemetry

import android.content.Context
import app.lusk.virga.BuildConfig
import app.lusk.virga.core.common.util.Redaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in crash reporting. Virga ships "no tracking" by default, so Sentry's manifest
 * auto-init is disabled (see AndroidManifest `io.sentry.auto-init` = false) and the SDK
 * is initialized here ONLY when both:
 *   1. a DSN is configured at build time ([isAvailable]) — empty in contributor/CI/F-Droid
 *      builds that don't supply one, and
 *   2. the user has turned on the Settings toggle (observed in VirgaApplication).
 *
 * Privacy posture: `sendDefaultPii = false` (no IP/device identifiers); the PII-bearing
 * auto-breadcrumb integrations (system-event, network) are disabled; and a
 * `beforeSend` + `beforeBreadcrumb` pair runs [redact] over the channels that can carry
 * the data the app keeps off-device (tokens, `content://`/filesystem paths, remote
 * names): event message (template + formatted + params), exception values, exception
 * stack-frame paths (with captured locals dropped), and breadcrumb messages + string
 * data. Anything not enumerated here is not guaranteed scrubbed — keep that in mind
 * before adding `setExtra`/`setTag`/manual breadcrumbs carrying sensitive strings.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dsn: String = BuildConfig.SENTRY_DSN

    /** True only when a DSN was baked in; without one, opting in is a no-op. */
    val isAvailable: Boolean get() = dsn.isNotBlank()

    @Volatile private var initialized = false

    /** Idempotent. Enables (initializes) or disables (closes) the Sentry SDK. */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == initialized) return
        if (enabled) {
            if (dsn.isBlank()) return // nothing to initialize
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                options.release =
                    "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                // Privacy: never attach device PII (IP, device name).
                options.isSendDefaultPii = false
                // Silence the auto-breadcrumb channels that can carry app data (broadcast
                // intent extras, connectivity details). Lifecycle breadcrumbs stay on for
                // triage; all surviving breadcrumbs still pass through beforeBreadcrumb.
                options.isEnableSystemEventBreadcrumbs = false
                options.isEnableNetworkEventBreadcrumbs = false
                // Scrub every outgoing event AND every breadcrumb of secrets/paths/remotes.
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
                options.beforeBreadcrumb =
                    SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> scrubBreadcrumb(crumb) }
            }
            initialized = true
        } else {
            Sentry.close()
            initialized = false
        }
    }

    private fun scrub(event: SentryEvent): SentryEvent {
        event.message?.let { m ->
            // All three are serialized independently: formatted is the interpolated
            // string, message the raw template, params the interpolation args.
            m.formatted = m.formatted?.let(::redact)
            m.message = m.message?.let(::redact)
            m.params = m.params?.map(::redact)
        }
        event.exceptions?.forEach { ex ->
            ex.value = ex.value?.let(::redact)
            // Stack-frame filename/abs_path can hold local + SAF paths; captured locals
            // (vars) can hold anything — redact the paths and drop the locals entirely.
            ex.stacktrace?.frames?.forEach { f ->
                f.filename = f.filename?.let(::redact)
                f.absPath = f.absPath?.let(::redact)
                f.vars = null
            }
        }
        return event
    }

    private fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
        crumb.message = crumb.message?.let(::redact)
        // data values are Any; redact string ones (URLs/paths/names) in place.
        crumb.data.keys.toList().forEach { k ->
            (crumb.data[k] as? String)?.let { crumb.setData(k, redact(it)) }
        }
        return crumb
    }

    // Tokens/passwords + filesystem/SAF paths, via the shared util (also used by the
    // run-log writer) so the redaction patterns can't drift between the two.
    private fun redact(text: String): String = Redaction.secretsAndPaths(text)
}
