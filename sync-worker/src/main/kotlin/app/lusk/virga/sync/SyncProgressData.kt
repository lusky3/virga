package app.lusk.virga.sync

import androidx.work.Data
import androidx.work.workDataOf
import app.lusk.virga.core.common.model.SyncProgress

/**
 * Phase of a running sync, used to pick the right progress treatment in the UI
 * (BRAND §10/§12): an indeterminate "listing" indicator before totals are known,
 * a determinate wavy bar once bytes/files are flowing.
 */
enum class SyncPhase { LISTING, TRANSFERRING }

/**
 * Encodes/decodes [SyncProgress] to a WorkManager [Data] bundle so [SyncWorker]
 * can publish live progress via `setProgress(...)` and the UI can read it back
 * through `WorkManager.getWorkInfosForUniqueWorkFlow(...)` (WS1.1). Survives
 * process death — WorkManager persists the latest progress for a RUNNING work.
 */
object SyncProgressData {
    private const val BYTES = "p_bytes"
    private const val TOTAL = "p_total"
    private const val SPEED = "p_speed"
    private const val FILES = "p_files"
    private const val TOTAL_FILES = "p_total_files"
    private const val ETA = "p_eta"
    private const val ERRORS = "p_errors"
    private const val DELETES = "p_deletes"

    /**
     * A run is still "listing" until rclone reports any totals to transfer. Recomputed
     * from totals by consumers ([SyncProgressMonitor]); not persisted in the bundle.
     */
    fun phaseOf(p: SyncProgress): SyncPhase =
        if (p.totalBytes > 0 || p.totalFiles > 0) SyncPhase.TRANSFERRING else SyncPhase.LISTING

    fun encode(p: SyncProgress): Data = workDataOf(
        BYTES to p.bytesTransferred,
        TOTAL to p.totalBytes,
        SPEED to p.speedBytesPerSec,
        FILES to p.transferredFiles,
        TOTAL_FILES to p.totalFiles,
        ETA to (p.etaSeconds ?: -1L),
        ERRORS to p.errors,
        DELETES to p.deletes,
    )

    /** Returns null if [data] carries no progress (e.g. work not yet running). */
    fun decode(data: Data): SyncProgress? {
        if (!data.keyValueMap.containsKey(TOTAL)) return null
        val eta = data.getLong(ETA, -1L)
        return SyncProgress(
            bytesTransferred = data.getLong(BYTES, 0L),
            totalBytes = data.getLong(TOTAL, 0L),
            speedBytesPerSec = data.getDouble(SPEED, 0.0),
            transferredFiles = data.getInt(FILES, 0),
            totalFiles = data.getInt(TOTAL_FILES, 0),
            etaSeconds = if (eta < 0) null else eta,
            errors = data.getInt(ERRORS, 0),
            deletes = data.getInt(DELETES, 0),
        )
    }
}
