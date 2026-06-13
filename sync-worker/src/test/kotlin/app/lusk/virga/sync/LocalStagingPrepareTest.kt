package app.lusk.virga.sync

import android.content.Context
import app.lusk.virga.core.common.model.SyncDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric coverage for [LocalStaging.prepare] / [writeBack] / [cleanup] — the
 * SAF-bridge logic that needs a real [Context] (cacheDir, contentResolver, Uri
 * parsing, DocumentFile.fromTreeUri).
 *
 * The readable-tree copy paths (a populated DocumentsProvider, the
 * copyTreeToLocal / copyLocalToTree tallies) need a registered provider and are
 * exercised by instrumented tests; see the notes on the affected cases. What is
 * reachable here: the non-content passthrough, the run-id-keyed stage-dir
 * derivation, the lost-permission (unreadable tree) early-out, the DOWNLOAD
 * empty-stage branch, and the writeBack / cleanup null-guards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStagingPrepareTest {

    private lateinit var context: Context
    private lateinit var staging: LocalStaging

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        staging = LocalStaging(context)
    }

    // --- non-content:// passthrough -----------------------------------------

    @Test
    fun `plain filesystem path passes through unstaged`() = runBlocking {
        val result = staging.prepare("/sdcard/DCIM", SyncDirection.UPLOAD, runId = 7L)

        assertThat(result.isStaged).isFalse()
        assertThat(result.localPath).isEqualTo("/sdcard/DCIM")
        assertThat(result.cacheDir).isNull()
        assertThat(result.treeUriString).isNull()
        // Defaults for a non-staged source: always readable, always fully staged.
        assertThat(result.sourceReadable).isTrue()
        assertThat(result.fullyStaged).isTrue()
    }

    @Test
    fun `plain path passthrough is identical for DOWNLOAD and BISYNC`() = runBlocking {
        for (dir in SyncDirection.values()) {
            val result = staging.prepare("/storage/emulated/0/Music", dir, runId = 1L)
            assertThat(result.isStaged).isFalse()
            assertThat(result.localPath).isEqualTo("/storage/emulated/0/Music")
        }
    }

    // --- run-id-keyed stage dir derivation ----------------------------------

    @Test
    fun `DOWNLOAD content source stages an empty run-id-keyed dir`() = runBlocking {
        val source = "content://com.example/tree/root"
        val result = staging.prepare(source, SyncDirection.DOWNLOAD, runId = 42L)

        assertThat(result.isStaged).isTrue()
        assertThat(result.treeUriString).isEqualTo(source)
        assertThat(result.cacheDir).isNotNull()
        // DOWNLOAD leaves the stage empty for rclone to fill.
        assertThat(result.cacheDir!!.exists()).isTrue()
        assertThat(result.cacheDir!!.listFiles()).isEmpty()

        // Dir is saf-stage/$hash-$runId under the app cache dir, hash = unsigned hex
        // of the source string's hashCode.
        val expectedHash = source.hashCode().toUInt().toString(16)
        val expected = File(context.cacheDir, "saf-stage/$expectedHash-42")
        assertThat(result.cacheDir!!.canonicalPath).isEqualTo(expected.canonicalPath)
        assertThat(result.localPath).isEqualTo(expected.absolutePath)
    }

    @Test
    fun `different run ids derive distinct stage dirs for the same source`() = runBlocking {
        val source = "content://com.example/tree/shared"
        val a = staging.prepare(source, SyncDirection.DOWNLOAD, runId = 100L)
        val b = staging.prepare(source, SyncDirection.DOWNLOAD, runId = 200L)

        // The runId suffix is what stops two concurrent runs from wiping each
        // other's in-flight stage via the leading deleteRecursively().
        assertThat(a.cacheDir!!.canonicalPath).isNotEqualTo(b.cacheDir!!.canonicalPath)
        assertThat(a.cacheDir!!.name).endsWith("-100")
        assertThat(b.cacheDir!!.name).endsWith("-200")
    }

    @Test
    fun `re-preparing the same run id clears stale stage contents`() = runBlocking {
        val source = "content://com.example/tree/reused"
        val first = staging.prepare(source, SyncDirection.DOWNLOAD, runId = 5L)
        // Simulate leftover output from a prior run in the same stage dir.
        File(first.cacheDir, "leftover.txt").writeText("stale")
        assertThat(first.cacheDir!!.listFiles()).isNotEmpty()

        val second = staging.prepare(source, SyncDirection.DOWNLOAD, runId = 5L)
        // The leading deleteRecursively() wipes the dir before re-creating it.
        assertThat(second.cacheDir!!.canonicalPath).isEqualTo(first.cacheDir!!.canonicalPath)
        assertThat(second.cacheDir!!.listFiles()).isEmpty()
    }

    // --- lost-permission (unreadable tree) early-out ------------------------

    @Test
    fun `UPLOAD with unreadable tree reports sourceReadable false and no files`() = runBlocking {
        // Under Robolectric there is no registered DocumentsProvider, so the tree
        // resolves but canRead() is false — the same signal as a lost persisted
        // URI permission. The worker MUST see sourceReadable=false so it refuses
        // to mirror an empty stage and delete the cloud destination.
        val source = "content://com.lost.permission/tree/gone"
        val result = staging.prepare(source, SyncDirection.UPLOAD, runId = 3L)

        assertThat(result.isStaged).isTrue()
        assertThat(result.sourceReadable).isFalse()
        assertThat(result.stagedFileCount).isEqualTo(0)
        assertThat(result.treeUriString).isEqualTo(source)
        assertThat(result.cacheDir).isNotNull()
    }

    @Test
    fun `BISYNC with unreadable tree also reports sourceReadable false`() = runBlocking {
        val source = "content://com.lost.permission/tree/bisync"
        val result = staging.prepare(source, SyncDirection.BISYNC, runId = 4L)

        assertThat(result.isStaged).isTrue()
        assertThat(result.sourceReadable).isFalse()
        assertThat(result.stagedFileCount).isEqualTo(0)
    }

    // --- writeBack / cleanup guards -----------------------------------------

    @Test
    fun `writeBack is a no-op when cacheDir is null`() = runBlocking {
        // No cacheDir => nothing to copy out; must return without touching SAF.
        val staged = LocalStaging.StagedSource(localPath = "/x", isStaged = false, cacheDir = null)
        staging.writeBack(staged) // does not throw
    }

    @Test
    fun `writeBack is a no-op when treeUriString is null`() = runBlocking {
        val dir = File(context.cacheDir, "wb-no-tree").apply { mkdirs() }
        val staged = LocalStaging.StagedSource(
            localPath = dir.absolutePath,
            isStaged = true,
            treeUriString = null,
            cacheDir = dir,
        )
        staging.writeBack(staged) // returns at the treeUri null-guard, no throw
    }

    @Test
    fun `cleanup deletes the staging cache dir`() = runBlocking {
        val dir = File(context.cacheDir, "to-clean").apply { mkdirs() }
        File(dir, "f.txt").writeText("data")
        val staged = LocalStaging.StagedSource(
            localPath = dir.absolutePath,
            isStaged = true,
            cacheDir = dir,
        )

        staging.cleanup(staged)

        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `cleanup with null cacheDir is a silent no-op`() = runBlocking {
        val staged = LocalStaging.StagedSource(localPath = "/x", isStaged = false, cacheDir = null)
        staging.cleanup(staged) // does not throw
    }
}
