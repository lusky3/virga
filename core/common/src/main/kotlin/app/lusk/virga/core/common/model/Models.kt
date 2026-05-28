package app.lusk.virga.core.common.model

/** Direction of a sync task. BISYNC is rclone's two-way sync (experimental). */
enum class SyncDirection { UPLOAD, DOWNLOAD, BISYNC }

/** Lifecycle state of a sync task / run, surfaced in list UIs. */
enum class SyncStatus { IDLE, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

enum class StorageType { INTERNAL, SD_CARD, USB }

/**
 * A mounted storage volume the user can sync from. [filesystemPath] is only
 * populated when MANAGE_EXTERNAL_STORAGE is granted; without it rclone cannot
 * read the volume directly.
 */
data class StorageRoot(
    val id: String,
    val displayName: String,
    val type: StorageType,
    val filesystemPath: String?,
    val totalBytes: Long,
    val availableBytes: Long,
)

/** A configured rclone remote (e.g. a Google Drive account). */
data class Remote(
    val name: String,
    val type: String,
)

/** A single entry returned by `operations/list` on a remote path. */
data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modTimeEpochMs: Long?,
    val mimeType: String? = null,
)

/** Aggregate progress for a running sync, derived from rclone `core/stats`. */
data class SyncProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Double,
    val transferredFiles: Int,
    val totalFiles: Int,
    val etaSeconds: Long?,
    val errors: Int,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Progress for a single file transfer. */
data class TransferProgress(
    val name: String,
    val bytes: Long,
    val size: Long,
    val speedBytesPerSec: Double,
)
