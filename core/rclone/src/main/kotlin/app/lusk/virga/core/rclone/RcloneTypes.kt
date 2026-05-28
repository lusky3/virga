package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.SyncDirection

/** A running rclone RC daemon: child process + the localhost endpoint + creds. */
data class RcloneDaemon(
    val process: Process,
    val port: Int,
    val user: String,
    val pass: String,
) {
    val baseUrl: String get() = "http://127.0.0.1:$port"
}

/** Options for a one-way sync (`sync/copy`, `sync/sync`). */
data class SyncOptions(
    val direction: SyncDirection,
    val bwLimit: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val filters: List<String> = emptyList(),
    val dryRun: Boolean = false,
    /** When true, delete extraneous files on the destination (true sync vs copy). */
    val deleteExtraneous: Boolean = true,
)

/** Options for a two-way `sync/bisync`. */
data class BisyncOptions(
    val bwLimit: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val filters: List<String> = emptyList(),
    val dryRun: Boolean = false,
    /** First-run resync to establish the bisync baseline. */
    val resync: Boolean = false,
)

/** Parsed view of the rclone config (remote name -> type). */
data class RcloneConfig(
    val remotes: Map<String, String>,
)
