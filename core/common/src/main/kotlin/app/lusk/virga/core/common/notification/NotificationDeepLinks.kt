package app.lusk.virga.core.common.notification

/**
 * Intent extras used to deep-link into the app from notifications, shared between
 * the module that builds the PendingIntent (e.g. `sync-worker`) and the launch
 * Activity that reads it (`app`). Kept here so both sides reference one constant
 * rather than a fragile hard-coded string.
 */
object NotificationDeepLinks {
    /** String extra on the launch intent naming the in-app destination to open. */
    const val EXTRA_OPEN_ROUTE = "app.lusk.virga.OPEN_ROUTE"

    /** [EXTRA_OPEN_ROUTE] value: switch to the Settings tab. */
    const val ROUTE_SETTINGS = "settings"

    /** [EXTRA_OPEN_ROUTE] value: open a task's summary; pair with [EXTRA_TASK_ID]. */
    const val ROUTE_TASK = "task"

    /**
     * [EXTRA_OPEN_ROUTE] value: navigate to Remotes tab then push [AddRemoteRoute],
     * so Back from Add-Remote lands on the Remotes list.  Used by the onboarding
     * "Add your first remote" CTA.
     */
    const val ROUTE_ADD_REMOTE = "add_remote"

    /** Long extra carrying the task id for [ROUTE_TASK]. */
    const val EXTRA_TASK_ID = "app.lusk.virga.OPEN_TASK_ID"
}
