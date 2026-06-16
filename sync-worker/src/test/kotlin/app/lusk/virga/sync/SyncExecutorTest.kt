package app.lusk.virga.sync

import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.rclone.BisyncOptions
import app.lusk.virga.core.rclone.RcloneConfig
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.SyncOptions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SyncExecutorTest {

    /** Records the arguments the executor passes to the engine. */
    private class RecordingEngine : RcloneEngine {
        var syncArgs: Triple<String, String, SyncOptions>? = null
        var bisyncArgs: Triple<String, String, BisyncOptions>? = null
        var checkArgs: Triple<String, String, SyncOptions>? = null

        override fun sync(source: String, dest: String, options: SyncOptions): Flow<SyncProgress> {
            syncArgs = Triple(source, dest, options)
            return flowOf(SyncProgress(100, 100, 0.0, 2, 2, null, 0))
        }

        override fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress> {
            bisyncArgs = Triple(path1, path2, options)
            return flowOf(SyncProgress(0, 0, 0.0, 0, 0, null, 0))
        }

        // Unused in these tests.
        override suspend fun startDaemon() = error("unused")
        override suspend fun stopDaemon() = Unit
        override suspend fun acquireDaemon() = error("unused")
        override suspend fun releaseDaemon() = Unit
        override suspend fun stopDaemonIfIdle() = Unit
        override suspend fun cleanupStaleConfigIfIdle() = Unit
        override suspend fun isDaemonHealthy() = true
        override suspend fun listRemotes(): List<Remote> = emptyList()
        override suspend fun createRemote(name: String, type: String, params: Map<String, String>, sensitiveKeys: Set<String>) = Unit
        override suspend fun deleteRemote(name: String) = Unit
        override suspend fun getConfig() = RcloneConfig(emptyMap())
        override suspend fun importConfig(confContent: String) = Unit
        override suspend fun listDir(remote: String, path: String, recurse: Boolean, filters: List<String>): List<FileItem> = emptyList()
        override suspend fun deleteFile(remote: String, path: String) = Unit
        override suspend fun moveFile(source: String, dest: String) = Unit
        override suspend fun copyFile(source: String, dest: String) = Unit
        override suspend fun downloadFile(
            remoteName: String,
            remotePath: String,
            destDir: String,
            destName: String,
        ) = Unit
        override suspend fun uploadFile(
            srcDir: String,
            srcName: String,
            remoteName: String,
            remotePath: String,
        ) = Unit
        override suspend fun purge(remote: String, path: String) = Unit
        override suspend fun mkdir(remote: String, path: String) = Unit
        override suspend fun testConnectivity(remoteName: String) = Result.success(Unit)
        override suspend fun about(remoteName: String) =
            app.lusk.virga.core.common.model.RemoteQuota(null, null, null)
        override suspend fun providers() =
            emptyList<app.lusk.virga.core.common.model.RemoteProvider>()
        override suspend fun createCryptRemote(
            name: String,
            baseRemoteSpec: String,
            password: String,
            salt: String?,
        ) = Unit
        override suspend fun <T> withDaemonForOAuth(block: suspend (app.lusk.virga.core.rclone.RcloneDaemon) -> T): T =
            error("unused")
        override fun check(source: String, dest: String, options: SyncOptions): Flow<SyncProgress> {
            checkArgs = Triple(source, dest, options)
            return flowOf(SyncProgress(0, 0, 0.0, 0, 0, null, 0))
        }
        override suspend fun dedupe(remoteName: String, dedupeMode: String): Result<Unit> =
            error("unused")
        override suspend fun transferredFiles(group: String): List<app.lusk.virga.core.rclone.TransferredFile> =
            emptyList()
        override suspend fun updateRemote(name: String, params: Map<String, String>, sensitiveKeys: Set<String>) = Unit
        override suspend fun getRemoteParams(name: String): Map<String, String> = emptyMap()
        override suspend fun renameRemote(oldName: String, newName: String) = Unit
    }

    private fun task(
        direction: SyncDirection,
        wifiOnly: Boolean = true,
    ) = SyncTask(
        id = 1,
        name = "Photos",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup/DCIM",
        direction = direction,
        intervalMinutes = null,
        bwLimitWifi = null,
        bwLimitMetered = "1M",
        filters = "- *.tmp\n+ *.jpg",
    )

    @Test
    fun `upload builds remote spec and forwards options`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false).collect {}

        val (source, dest, options) = engine.syncArgs!!
        assertThat(source).isEqualTo("/storage/emulated/0/DCIM")
        assertThat(dest).isEqualTo("gdrive:Backup/DCIM")
        assertThat(options.direction).isEqualTo(SyncDirection.UPLOAD)
        assertThat(options.filters).containsExactly("- *.tmp", "+ *.jpg")
    }

    @Test
    fun `metered run selects metered bandwidth limit`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = true).collect {}
        assertThat(engine.syncArgs!!.third.bwLimit).isEqualTo("1M")
    }

    @Test
    fun `wifi run uses wifi bandwidth limit`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false).collect {}
        assertThat(engine.syncArgs!!.third.bwLimit).isNull()
    }

    @Test
    fun `bisync direction routes to bisync`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC), metered = false).collect {}
        assertThat(engine.bisyncArgs).isNotNull()
        assertThat(engine.bisyncArgs!!.first).isEqualTo("/storage/emulated/0/DCIM")
        assertThat(engine.bisyncArgs!!.second).isEqualTo("gdrive:Backup/DCIM")
    }

    @Test
    fun `bisync forwards resync flag for first-run baseline`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC), metered = false, resync = true).collect {}
        assertThat(engine.bisyncArgs!!.third.resync).isTrue()
    }

    @Test
    fun `bisync defaults to no resync once a baseline exists`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC), metered = false).collect {}
        assertThat(engine.bisyncArgs!!.third.resync).isFalse()
    }

    @Test
    fun `bisync forwards the task buffer size`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC).copy(bufferSize = "32M"), metered = false).collect {}
        assertThat(engine.bisyncArgs!!.third.bufferSize).isEqualTo("32M")
    }

    @Test
    fun `allowMove true sets deleteSource on SyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false, allowMove = true).collect {}
        assertThat(engine.syncArgs!!.third.deleteSource).isTrue()
    }

    @Test
    fun `allowMove defaults to false so deleteSource is false`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false).collect {}
        assertThat(engine.syncArgs!!.third.deleteSource).isFalse()
    }

    // --- B5: size / age filter threading ------------------------------------

    @Test
    fun `size and age fields are threaded into SyncOptions for one-way sync`() = runTest {
        val engine = RecordingEngine()
        val t = task(SyncDirection.UPLOAD).copy(
            minSize = "10M",
            maxSize = "2G",
            minAge = "30d",
            maxAge = "1y",
        )
        SyncExecutor(engine).run(t, metered = false).collect {}

        val opts = engine.syncArgs!!.third
        assertThat(opts.minSize).isEqualTo("10M")
        assertThat(opts.maxSize).isEqualTo("2G")
        assertThat(opts.minAge).isEqualTo("30d")
        assertThat(opts.maxAge).isEqualTo("1y")
    }

    @Test
    fun `size and age fields are threaded into BisyncOptions`() = runTest {
        val engine = RecordingEngine()
        val t = task(SyncDirection.BISYNC).copy(minSize = "512", maxAge = "7d")
        SyncExecutor(engine).run(t, metered = false).collect {}

        val opts = engine.bisyncArgs!!.third
        assertThat(opts.minSize).isEqualTo("512")
        assertThat(opts.maxAge).isEqualTo("7d")
        assertThat(opts.maxSize).isNull()
        assertThat(opts.minAge).isNull()
    }

    @Test
    fun `blank size-age fields become null in SyncOptions`() = runTest {
        val engine = RecordingEngine()
        // Default task has empty string fields.
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false).collect {}

        val opts = engine.syncArgs!!.third
        assertThat(opts.minSize).isNull()
        assertThat(opts.maxSize).isNull()
        assertThat(opts.minAge).isNull()
        assertThat(opts.maxAge).isNull()
    }

    // --- B6: MaxTransfer threading -----------------------------------------

    @Test
    fun `maxTransfer is threaded into SyncOptions for one-way sync`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(
            task(SyncDirection.UPLOAD).copy(maxTransfer = "10G"),
            metered = false,
        ).collect {}
        assertThat(engine.syncArgs!!.third.maxTransfer).isEqualTo("10G")
    }

    @Test
    fun `blank maxTransfer becomes null in SyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false).collect {}
        assertThat(engine.syncArgs!!.third.maxTransfer).isNull()
    }

    @Test
    fun `maxTransfer is threaded into BisyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(
            task(SyncDirection.BISYNC).copy(maxTransfer = "5G"),
            metered = false,
        ).collect {}
        assertThat(engine.bisyncArgs!!.third.maxTransfer).isEqualTo("5G")
    }

    @Test
    fun `blank maxTransfer becomes null in BisyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC), metered = false).collect {}
        assertThat(engine.bisyncArgs!!.third.maxTransfer).isNull()
    }

    @Test
    fun `runCheck forwards task performance settings and wifi bwlimit`() = runTest {
        val engine = RecordingEngine()
        val perfTask = task(SyncDirection.UPLOAD).copy(
            bwLimitWifi = "5M",
            bwLimitMetered = "1M",
            transfers = 7,
            checkers = 16,
            bufferSize = "64M",
        )
        SyncExecutor(engine).runCheck(perfTask).collect {}

        val opts = engine.checkArgs!!.third
        // Verify mirrors the run's knobs; uses the Wi-Fi limit (not the metered cap).
        assertThat(opts.bwLimit).isEqualTo("5M")
        assertThat(opts.transfers).isEqualTo(7)
        assertThat(opts.checkers).isEqualTo(16)
        assertThat(opts.bufferSize).isEqualTo("64M")
    }

    @Test
    fun `runCheck blank size-age fields become null in SyncOptions`() = runTest {
        val engine = RecordingEngine()
        // Default task has blank strings for minSize/maxSize/minAge/maxAge.
        SyncExecutor(engine).runCheck(task(SyncDirection.UPLOAD)).collect {}

        val opts = engine.checkArgs!!.third
        assertThat(opts.minSize).isNull()
        assertThat(opts.maxSize).isNull()
        assertThat(opts.minAge).isNull()
        assertThat(opts.maxAge).isNull()
    }

    @Test
    fun `runCheck threads non-blank size-age fields into SyncOptions`() = runTest {
        val engine = RecordingEngine()
        val t = task(SyncDirection.UPLOAD).copy(
            minSize = "1M",
            maxSize = "1G",
            minAge = "7d",
            maxAge = "1y",
        )
        SyncExecutor(engine).runCheck(t).collect {}

        val opts = engine.checkArgs!!.third
        assertThat(opts.minSize).isEqualTo("1M")
        assertThat(opts.maxSize).isEqualTo("1G")
        assertThat(opts.minAge).isEqualTo("7d")
        assertThat(opts.maxAge).isEqualTo("1y")
    }

    @Test
    fun `runCheck builds correct remote spec`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).runCheck(task(SyncDirection.UPLOAD)).collect {}

        val (source, dest, _) = engine.checkArgs!!
        assertThat(source).isEqualTo("/storage/emulated/0/DCIM")
        assertThat(dest).isEqualTo("gdrive:Backup/DCIM")
    }

    @Test
    fun `runCheck forwards checksum flag`() = runTest {
        val engine = RecordingEngine()
        val t = task(SyncDirection.UPLOAD).copy(checksum = true)
        SyncExecutor(engine).runCheck(t).collect {}

        assertThat(engine.checkArgs!!.third.checksum).isTrue()
    }

    @Test
    fun `allowDeletes true sets deleteExtraneous on SyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false, allowDeletes = true).collect {}
        assertThat(engine.syncArgs!!.third.deleteExtraneous).isTrue()
    }

    @Test
    fun `allowDeletes defaults to false so deleteExtraneous is false`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false).collect {}
        assertThat(engine.syncArgs!!.third.deleteExtraneous).isFalse()
    }

    @Test
    fun `dryRun true is forwarded into SyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.UPLOAD), metered = false, dryRun = true).collect {}
        assertThat(engine.syncArgs!!.third.dryRun).isTrue()
    }

    @Test
    fun `dryRun true is forwarded into BisyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC), metered = false, dryRun = true).collect {}
        assertThat(engine.bisyncArgs!!.third.dryRun).isTrue()
    }

    // --- B7: conflictResolve threading into BisyncOptions ------------------

    @Test
    fun `B7 non-blank conflictResolve is threaded into BisyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(
            task(SyncDirection.BISYNC).copy(conflictResolve = "newer"),
            metered = false,
        ).collect {}

        assertThat(engine.bisyncArgs!!.third.conflictResolve).isEqualTo("newer")
    }

    @Test
    fun `B7 blank conflictResolve becomes null in BisyncOptions`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(task(SyncDirection.BISYNC), metered = false).collect {}

        assertThat(engine.bisyncArgs!!.third.conflictResolve).isNull()
    }

    @Test
    fun `B7 conflictResolve is not set on SyncOptions for one-way syncs`() = runTest {
        val engine = RecordingEngine()
        SyncExecutor(engine).run(
            task(SyncDirection.UPLOAD).copy(conflictResolve = "newer"),
            metered = false,
        ).collect {}

        // One-way uses syncArgs, not bisyncArgs — verify the engine received sync, not bisync.
        assertThat(engine.syncArgs).isNotNull()
        assertThat(engine.bisyncArgs).isNull()
    }
}
