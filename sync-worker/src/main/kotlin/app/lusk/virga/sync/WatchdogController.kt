package app.lusk.virga.sync

import app.lusk.virga.core.datastore.PreferencesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Whether the watchdog feature is built into this flavor. Bound in `:app`
 * (`WatchdogAvailabilityModule`, from `BuildConfig.WATCHDOG_AVAILABLE`) so this
 * flavor-agnostic module doesn't depend on `:app`. False on `play`: a persistent
 * `specialUse` foreground service is a known Play-policy rejection risk for a
 * general sync app — see [WatchdogController] and the manifest removal in
 * `app/src/play/AndroidManifest.xml`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WatchdogAvailable

/**
 * Orchestrates the optional persistent watchdog (see [WatchdogService]). Single
 * source of truth for turning it on/off and for the periodic "heartbeat" that
 * repairs the schedule. Delegates all Android side-effects to [WatchdogPlatform]
 * so this decision logic is unit-testable.
 *
 * Wiring:
 *  - `VirgaApplication` observes the `watchdogEnabled` preference and calls
 *    [setEnabled] — covers runtime toggles (app in foreground, FGS start allowed).
 *  - `BootReceiver` calls [setEnabled] after boot when the pref is on — covers the
 *    background-FGS-start exemption granted on BOOT_COMPLETED.
 *  - [WatchdogReceiver] calls [onHeartbeat] when the AlarmManager alarm fires.
 *
 * [available] is a defense-in-depth gate on top of the manifest-level removal for
 * flavors that don't ship the feature: it stops the watchdog from ever starting
 * even if a stale `watchdogEnabled=true` pref survives (e.g. a DataStore value
 * carried over from a build where the feature was present).
 */
@Singleton
class WatchdogController @Inject constructor(
    private val platform: WatchdogPlatform,
    private val preferences: PreferencesRepository,
    @WatchdogAvailable private val available: Boolean,
) {

    /** Applies the desired state: (re)start or tear down the service + heartbeat. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && available) {
            platform.startService()
            platform.scheduleHeartbeat()
        } else {
            platform.cancelHeartbeat()
            platform.stopService()
        }
    }

    /**
     * Heartbeat tick: re-enqueue any periodic/calendar work the OEM may have
     * silently dropped, make sure the keep-alive service is up, then re-arm.
     *
     * Gated on [available] and the live preference: a tick can still arrive
     * *after* the watchdog was disabled — an in-flight broadcast racing
     * [setEnabled]`(false)`'s `cancelHeartbeat`, or a START_STICKY restart.
     * Without this check the tick would resurrect the service and re-arm the
     * alarm forever, leaving a zombie the user can't turn off. When disabled
     * (or unavailable on this flavor) we tear down instead.
     */
    suspend fun onHeartbeat() {
        if (!available || !preferences.preferences.first().watchdogEnabled) {
            platform.cancelHeartbeat()
            platform.stopService()
            return
        }
        runCatching { platform.rescheduleSyncs() }
        platform.startService()
        platform.scheduleHeartbeat()
    }
}
