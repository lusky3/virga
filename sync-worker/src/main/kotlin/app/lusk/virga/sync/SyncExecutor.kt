package app.lusk.virga.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.rclone.BisyncOptions
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.SyncOptions
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Turns a [SyncTask] into the right rclone invocation and surfaces its
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
    /**
     * @param allowDeletes when true, the destination is mirrored to match the
     * source (rclone `sync`, which DELETES extraneous destination files); when
     * false (the default), the sync only adds/updates (rclone `copy`). One-way
     * syncs must default to additive — mirroring a few local files onto a
     * populated remote folder would delete everything else there.
     *
     * @param resync requests rclone's bisync `--resync` to establish the baseline
     * listing. Required on a bisync task's first run — without it rclone aborts
     * with "cannot find prior listing". The worker passes true until the task has
     * a prior successful run. Ignored for non-bisync directions.
     */
    fun run(
        task: SyncTask,
        metered: Boolean,
        allowDeletes: Boolean = false,
        resync: Boolean = false,
        dryRun: Boolean = false,
    ): Flow<SyncProgress> {
        val local = task.sourcePath
        val remote = remoteSpec(task)
        val bwLimit = if (metered) task.bwLimitMetered else task.bwLimitWifi
        val filters = task.filters.lines().filter { it.isNotBlank() }

        val extra = ExtraConfigParser.parseToMap(task.extraConfig)
        return when (task.direction) {
            SyncDirection.BISYNC -> engine.bisync(
                path1 = local,
                path2 = remote,
                options = BisyncOptions(
                    bwLimit = bwLimit,
                    transfers = task.transfers,
                    checkers = task.checkers,
                    bufferSize = task.bufferSize,
                    filters = filters,
                    resync = resync,
                    dryRun = dryRun,
                    checksum = task.checksum,
                    backupDir = task.backupDir,
                    maxDelete = task.maxDelete,
                    extraConfig = extra,
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
                    deleteExtraneous = allowDeletes,
                    dryRun = dryRun,
                    checksum = task.checksum,
                    backupDir = task.backupDir,
                    maxDelete = task.maxDelete,
                    extraConfig = extra,
                ),
            )
        }
    }

    /** Builds the rclone "remote:path" destination spec. */
    private fun remoteSpec(task: SyncTask): String {
        val path = task.remotePath.removePrefix("/")
        return "${task.remoteName}:$path"
    }
}
