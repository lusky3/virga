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
 * Most operations signal failure the same way: `suspend` methods **throw**
 * [app.lusk.virga.core.common.error.VirgaError] on failure (rather than some
 * returning `Result` and others throwing), and the streaming [sync]/[bisync]
 * flows surface failures by terminating the flow with a `VirgaError`. Callers
 * that need a `Result` (e.g. repositories at the UI boundary) wrap the call in
 * `runCatching`. The sole exception is [dedupe], a fire-and-forget maintenance
 * action that returns [Result] directly because its caller always treats failure
 * as a non-fatal, surfaced-to-snackbar outcome rather than a thrown error.
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
     * Creates the directory [path] within [remote] (e.g. remote `"gdrive:"`, path
     * `"backups/photos"`) via `operations/mkdir`. Idempotent — rclone no-ops if the
     * directory already exists. Throws [VirgaError] on failure.
     */
    suspend fun mkdir(remote: String, path: String)

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

    /**
     * Compares [source] and [dest] without transferring any data (rclone check).
     *
     * RC endpoint: `operations/check` — chosen over the generic command runner
     * because it is the only RC endpoint that runs a check job asynchronously
     * with job-status polling (same pattern as sync/copy). It accepts `srcFs`,
     * `dstFs`, `_filter`, and `_config` blocks, so task filters and config
     * options apply identically to sync.
     *
     * Terminal [SyncProgress.errors] carries rclone's check `errors` stat, which
     * counts each differing or missing file (plus any genuine read/hash error) —
     * treat it as the "files that differ or are missing" count, not an exact clean
     * diff. The flow completes with a single terminal emission (like [sync]).
     */
    fun check(source: String, dest: String, options: SyncOptions): Flow<SyncProgress>

    /**
     * Returns the transferred files for the run identified by [group] (the rclone
     * stats group "job/<id>", carried on the terminal [SyncProgress.statsGroup]).
     * Each entry reflects one transfer attempt; entries with a non-empty
     * [TransferredFile.error] are the failures. Scoping by [group] is REQUIRED: the
     * daemon is shared across concurrent "sync all" runs and accumulates every job's
     * transfers, so an unscoped query would mis-attribute other runs' failures.
     * Throws [app.lusk.virga.core.common.error.VirgaError] on failure, consistent with
     * the throw-on-failure convention of this interface.
     *
     * RC endpoint: `core/transferred` with a `group` param (rclone ≥ 1.50). Returns
     * `{"transferred": [{"name": "...", "error": "...", "bytes": N, ...}, ...]}`.
     * Entries are returned for ALL transfers (success + failure); callers filter to
     * non-empty error. Entries with a missing or blank name are skipped defensively.
     */
    suspend fun transferredFiles(group: String): List<TransferredFile>

    /**
     * Finds and removes duplicate files on [remoteName] by hash.
     *
     * rclone exposes `dedupe` only as a CLI command — there is no `operations/dedupe`
     * RC method — so this routes through the generic `core/command` runner
     * (`command="dedupe"`, `arg=["<remoteName>:"]`, `opt={"dedupe-mode": …}`,
     * `returnType=COMBINED_OUTPUT`). A truthy `error` flag in the response is mapped
     * to [Result.failure]. [dedupeMode] is one of "skip", "first", "newest",
     * "oldest", "rename", or "largest"; defaults to "skip" (non-destructive of
     * differing same-name files — removes only exact byte duplicates).
     *
     * Routes through [acquireDaemon]/[releaseDaemon] with NonCancellable
     * release, consistent with the pattern used by [DryRunUseCase].
     */
    suspend fun dedupe(remoteName: String, dedupeMode: String = "skip"): Result<Unit>
}
