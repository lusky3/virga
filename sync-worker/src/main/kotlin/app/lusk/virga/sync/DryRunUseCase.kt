package app.lusk.virga.sync

import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.rclone.RcloneEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Outcome of a dry-run preview (WS2.3): what a real run *would* do. */
data class DryRunResult(
    val filesToTransfer: Int,
    val bytesToTransfer: Long,
    val errors: Int,
    /** Non-null when the dry run itself failed (e.g. remote unreachable). */
    val error: String? = null,
)

/**
 * Runs a task with rclone `--dry-run` (no WorkManager, no real transfer) and
 * reports the planned change-set (WS2.3). rclone reports would-transfer stats in
 * dry-run mode, so the final [SyncProgress] is the preview.
 *
 * SAF (`content://`) sources are not supported: rclone can't read them without
 * the staging copy that a real run performs, which defeats the point of a
 * preview — callers must gate those out (see [isAvailableFor]).
 */
class DryRunUseCase @Inject constructor(
    private val executor: SyncExecutor,
    private val engine: RcloneEngine,
) {
    fun isAvailableFor(task: SyncTask): Boolean = !task.sourcePath.startsWith("content://")

    suspend fun preview(task: SyncTask): DryRunResult {
        var last: SyncProgress? = null
        var error: String? = null
        var leased = false
        try {
            engine.acquireDaemon()
            leased = true
            executor.run(
                task = task,
                metered = false,
                allowDeletes = task.deleteExtraneous,
                dryRun = true,
            )
                .catch { error = it.message ?: "Preview failed" }
                .collect { last = it }
        } finally {
            // Release even if the caller's scope was cancelled (e.g. the user
            // navigated away mid-preview); releaseDaemon() suspends, so run it
            // NonCancellable or it would no-op on an already-cancelled coroutine.
            // Reference-counted, so it won't tear down a concurrent sync's daemon.
            if (leased) withContext(NonCancellable) { runCatching { engine.releaseDaemon() } }
        }
        // In --dry-run, rclone transfers nothing, so "transferred" stats stay 0;
        // the PLANNED change-set is in the total* fields.
        return DryRunResult(
            filesToTransfer = last?.totalFiles ?: 0,
            bytesToTransfer = last?.totalBytes ?: 0L,
            errors = last?.errors ?: 0,
            error = error,
        )
    }
}
