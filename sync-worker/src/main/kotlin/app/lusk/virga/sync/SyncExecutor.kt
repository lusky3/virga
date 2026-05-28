package app.lusk.virga.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.core.rclone.BisyncOptions
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.SyncOptions
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Turns a [SyncTaskEntity] into the right rclone invocation and surfaces its
 * progress. Pure orchestration — no Android dependencies — so it is unit
 * testable against a fake [RcloneEngine].
 */
class SyncExecutor @Inject constructor(
    private val engine: RcloneEngine,
) {
    /**
     * Runs [task]. [metered] selects the bandwidth limit (metered vs wifi).
     * The returned flow emits progress and completes when the sync finishes;
     * failures propagate as exceptions for the caller to record.
     */
    fun run(task: SyncTaskEntity, metered: Boolean): Flow<SyncProgress> {
        val local = task.sourcePath
        val remote = remoteSpec(task)
        val bwLimit = if (metered) task.bwLimitMetered else task.bwLimitWifi
        val filters = task.filters.lines().filter { it.isNotBlank() }

        return when (task.direction) {
            SyncDirection.BISYNC -> engine.bisync(
                path1 = local,
                path2 = remote,
                options = BisyncOptions(
                    bwLimit = bwLimit,
                    transfers = task.transfers,
                    checkers = task.checkers,
                    filters = filters,
                ),
            )
            else -> engine.sync(
                source = local,
                dest = remote,
                options = SyncOptions(
                    direction = task.direction,
                    bwLimit = bwLimit,
                    transfers = task.transfers,
                    checkers = task.checkers,
                    bufferSize = task.bufferSize,
                    filters = filters,
                ),
            )
        }
    }

    /** Builds the rclone "remote:path" destination spec. */
    private fun remoteSpec(task: SyncTaskEntity): String {
        val path = task.remotePath.removePrefix("/")
        return "${task.remoteName}:$path"
    }
}
