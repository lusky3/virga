package app.lusk.virga.update

import android.app.Activity

/** Describes an update that is available for the user to install. */
data class AvailableUpdate(val versionLabel: String)

/**
 * Per-flavor strategy for checking and starting an app update.
 *
 * Both implementations are best-effort: [check] returns null on any error
 * (offline, rate-limit, parse failure) so the app never crashes or blocks
 * launch because of an update check.
 */
interface UpdateChecker {
    /** Returns an [AvailableUpdate] when a newer version is available, else null. */
    suspend fun check(): AvailableUpdate?

    /** Starts the update flow (Play in-app update or browser) using [activity]. */
    fun startUpdate(activity: Activity)
}
