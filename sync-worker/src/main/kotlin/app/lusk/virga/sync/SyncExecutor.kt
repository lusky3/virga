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
     * @param allowMove when true, use rclone `sync/move`: files are transferred then
     * deleted from the source. One-way only; mutually exclusive with [allowDeletes].
     * Never applies to bisync. Forbidden for SAF (`content://`) sources.
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
        allowMove: Boolean = false,
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
                    minSize = task.minSize.ifBlank { null },
                    maxSize = task.maxSize.ifBlank { null },
                    minAge = task.minAge.ifBlank { null },
                    maxAge = task.maxAge.ifBlank { null },
                    resync = resync,
                    dryRun = dryRun,
                    checksum = task.checksum,
                    backupDir = task.backupDir,
                    maxDelete = task.maxDelete,
                    extraConfig = extra,
                    maxTransfer = task.maxTransfer.ifBlank { null },
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
                    minSize = task.minSize.ifBlank { null },
                    maxSize = task.maxSize.ifBlank { null },
                    minAge = task.minAge.ifBlank { null },
                    maxAge = task.maxAge.ifBlank { null },
                    deleteExtraneous = allowDeletes,
                    deleteSource = allowMove,
                    dryRun = dryRun,
                    checksum = task.checksum,
                    backupDir = task.backupDir,
                    maxDelete = task.maxDelete,
                    extraConfig = extra,
                    maxTransfer = task.maxTransfer.ifBlank { null },
                ),
            )
        }
    }

    /**
     * Runs a check (compare without transferring) for [task]. Only valid for non-SAF
     * sources; callers must gate with [CheckUseCase.isAvailableFor]. The returned flow
     * emits progress and completes when the check finishes.
     */
    fun runCheck(task: SyncTask): Flow<SyncProgress> {
        val local = task.sourcePath
        val remote = remoteSpec(task)
        val filters = task.filters.lines().filter { it.isNotBlank() }
        return engine.check(
            source = local,
            dest = remote,
            options = SyncOptions(
                direction = task.direction,
                // Forward the task's performance + comparison config so a verify behaves
                // like the real run it previews (checkers especially matter — check is
                // checker-bound; checksum decides hash-vs-size compare; extraConfig is
                // the power-user escape hatch). A verify is user-initiated with no
                // metered signal, so it uses the Wi-Fi bandwidth limit. Transfer/delete
                // knobs (deleteExtraneous, maxDelete, maxTransfer, backupDir) are
                // intentionally omitted — a check transfers and deletes nothing.
                bwLimit = task.bwLimitWifi,
                transfers = task.transfers,
                checkers = task.checkers,
                bufferSize = task.bufferSize,
                filters = filters,
                minSize = task.minSize.ifBlank { null },
                maxSize = task.maxSize.ifBlank { null },
                minAge = task.minAge.ifBlank { null },
                maxAge = task.maxAge.ifBlank { null },
                checksum = task.checksum,
                extraConfig = ExtraConfigParser.parseToMap(task.extraConfig),
            ),
        )
    }

    /** Builds the rclone "remote:path" destination spec. */
    private fun remoteSpec(task: SyncTask): String {
        val path = task.remotePath.removePrefix("/")
        return "${task.remoteName}:$path"
    }
}
