package app.lusk.virga.core.common.notification

/** Channel ids shared between the app (which registers them) and the worker. */
object NotificationChannelIds {
    const val SYNC_PROGRESS = "sync_progress"
    const val SYNC_COMPLETE = "sync_complete"
    const val SYNC_ERROR = "sync_error"
}
