package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.model.SyncProgress
import kotlinx.coroutines.flow.Flow

/**
 * High-level contract for driving rclone. Implementations manage an RC daemon
 * child process and translate these calls into authenticated RC API requests.
 *
 * Paths use rclone syntax: a remote path is "remoteName:some/dir"; a local path
 * is an absolute filesystem path (requires MANAGE_EXTERNAL_STORAGE for SD cards).
 *
 * ## Failure convention
 * Every operation signals failure the same way: `suspend` methods **throw**
 * [app.lusk.virga.core.common.error.VirgaError] on failure (rather than some
 * returning `Result` and others throwing), and the streaming [sync]/[bisync]
 * flows surface failures by terminating the flow with a `VirgaError`. Callers
 * that need a `Result` (e.g. repositories at the UI boundary) wrap the call in
 * `runCatching`.
 */
interface RcloneEngine {
    suspend fun startDaemon(): RcloneDaemon
    suspend fun stopDaemon()
    suspend fun isDaemonHealthy(): Boolean

    /**
     * Acquire a reference-counted lease on the shared daemon (starting it if needed)
     * and return it. Long-lived consumers (a running sync, dry-run preview, file
     * browser) MUST use [acquireDaemon]/[releaseDaemon] rather than [startDaemon]/
     * [stopDaemon], so concurrent consumers don't tear down each other's daemon
     * mid-operation. Every [acquireDaemon] must be balanced by one [releaseDaemon].
     */
    suspend fun acquireDaemon(): RcloneDaemon

    /** Release a lease from [acquireDaemon]; stops the daemon when the last lease drops. */
    suspend fun releaseDaemon()

    /**
     * Best-effort stop for non-leasing consumers (e.g. the file browser closing):
     * stops the daemon ONLY if no lease is currently held, so it never tears down a
     * daemon an active sync is using. A no-op while any lease is outstanding.
     */
    suspend fun stopDaemonIfIdle()

    /**
     * Purges any decrypted plaintext config left on disk by a worker killed before it
     * could run its cleanup (process death). Lease-aware: a no-op when a daemon is live
     * or any lease is held, so it can never delete a config a concurrent sync is using.
     * Safe to call once at app startup.
     */
    suspend fun cleanupStaleConfigIfIdle()

    /**
     * Fetches the full provider/option schema from rclone's `config/providers`
     * endpoint. Returns an empty list on failure so callers are failure-tolerant.
     */
    suspend fun providers(): List<RemoteProvider>

    suspend fun listRemotes(): List<Remote>
    suspend fun createRemote(
        name: String,
        type: String,
        params: Map<String, String>,
        sensitiveKeys: Set<String> = emptySet(),
    )

    /**
     * Creates a `crypt:` remote that wraps [baseRemoteSpec] (e.g. "gdrive:encrypted").
     *
     * Passwords are sent to rclone's `config/create` with `opt.obscure = true` so
     * rclone performs the obscuring itself — **the plaintext values are never stored,
     * logged, or persisted by this app**. They exist only in memory for the duration
     * of this call.
     *
     * On-device note: rclone's crypt backend expects `remote`, `password`, and the
     * optional `password2` (salt) as documented at https://rclone.org/crypt/.
     * The parameter names are `remote` / `password` / `password2`.
     */
    suspend fun createCryptRemote(
        name: String,
        baseRemoteSpec: String,
        password: String,
        salt: String?,
    )

    suspend fun deleteRemote(name: String)
    suspend fun getConfig(): RcloneConfig
    suspend fun importConfig(confContent: String)

    suspend fun listDir(
        remote: String,
        path: String,
        recurse: Boolean = false,
        filters: List<String> = emptyList(),
    ): List<FileItem>

    /** Deletes a single file at [remote]:[path]. Throws [VirgaError] on failure. */
    suspend fun deleteFile(remote: String, path: String)

    /** Moves/renames a file. Paths use rclone "remote:path" syntax. Throws [VirgaError] on failure. */
    suspend fun moveFile(source: String, dest: String)

    /**
     * Fetches storage quota for [remoteName] via `operations/about`.
     * Throws [VirgaError] on failure; any field in the result may be null
     * when the backend does not report it.
     */
    suspend fun about(remoteName: String): RemoteQuota

    /**
     * Provides a daemon for a long-running config-mutating operation (daemon OAuth).
     * The daemon stays alive for the entire [block]. On success, persists the updated
     * config and tears down. On failure/cancellation, cleans up without persisting.
     * Rejects if syncs hold leases (same as [createRemote]).
     */
    suspend fun <T> withDaemonForOAuth(block: suspend (RcloneDaemon) -> T): T

    /**
     * Tests connectivity to [remoteName] by attempting `operations/about`, falling
     * back to `operations/list` if the backend doesn't support about.
     * Returns [Result.success] if either succeeds, [Result.failure] if both fail.
     */
    suspend fun testConnectivity(remoteName: String): Result<Unit>

    /** Emits progress until the sync completes; the terminal emission has full counts. */
    fun sync(source: String, dest: String, options: SyncOptions): Flow<SyncProgress>
    fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress>
}
