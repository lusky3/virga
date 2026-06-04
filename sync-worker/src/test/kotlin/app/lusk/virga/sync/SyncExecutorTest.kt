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
}
