package app.lusk.virga.sync

import android.content.Context
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStagingTimeoutTest {

    private lateinit var context: Context
    private lateinit var staging: LocalStaging

    private val realDispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.IO
        override val io = Dispatchers.IO
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        staging = LocalStaging(context, realDispatchers)
    }

    /** An InputStream whose read() blocks until close() is called from another thread. */
    private class BlockingStream(val closed: AtomicBoolean) : InputStream() {
        override fun read(): Int {
            while (!closed.get()) Thread.sleep(10)
            return -1
        }
        override fun close() { closed.set(true) }
    }

    /** An InputStream whose reads always fail (a bad-sector source). */
    private class ErroringStream : InputStream() {
        override fun read(): Int = throw java.io.IOException("bad sector")
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("bad sector")
    }

    @Test
    fun `a read that exceeds the timeout is closed, counted TIMEOUT, and leaves no partial file`() = runBlocking {
        val closed = AtomicBoolean(false)
        // Drive the helper directly via the test-only entry point.
        val dest = File(context.cacheDir, "out.bin")
        val outcome = staging.copyDocumentToFileTimedForTest(
            stream = BlockingStream(closed),
            dest = dest,
            timeoutMs = 50L,
        )
        assertThat(outcome).isEqualTo(LocalStaging.CopyOutcome.TIMEOUT)
        assertThat(closed.get()).isTrue() // outer coroutine closed the wedged stream
        assertThat(dest.exists()).isFalse() // partial output removed so it can't be uploaded
    }

    @Test
    fun `a stream that errors mid-copy is counted ERROR and leaves no partial file`() = runBlocking {
        val dest = File(context.cacheDir, "errored.bin")
        // The IO error is captured by the child as ERROR — it must NOT escape and abort
        // the whole staging copy.
        val outcome = staging.copyDocumentToFileTimedForTest(
            stream = ErroringStream(),
            dest = dest,
            timeoutMs = 5_000L,
        )
        assertThat(outcome).isEqualTo(LocalStaging.CopyOutcome.ERROR)
        assertThat(dest.exists()).isFalse()
    }

    @Test
    fun `a normal stream copies fully and is counted as COPIED`() = runBlocking {
        val dest = File(context.cacheDir, "copied.bin")
        val outcome = staging.copyDocumentToFileTimedForTest(
            stream = "hello".byteInputStream(),
            dest = dest,
            timeoutMs = 5_000L,
        )
        assertThat(outcome).isEqualTo(LocalStaging.CopyOutcome.COPIED)
        assertThat(dest.readText()).isEqualTo("hello")
    }

    @Test
    fun `CopyTally record folds outcomes into copied, error, and timeout totals`() {
        val tally = LocalStaging.CopyTally()
        tally.record(LocalStaging.CopyOutcome.COPIED)
        tally.record(LocalStaging.CopyOutcome.COPIED)
        tally.record(LocalStaging.CopyOutcome.ERROR)
        tally.record(LocalStaging.CopyOutcome.TIMEOUT)
        assertThat(tally.copied).isEqualTo(2)
        // A timeout counts as both a timeout AND an error (file is absent from the stage).
        assertThat(tally.errors).isEqualTo(2)
        assertThat(tally.timeouts).isEqualTo(1)
    }
}
