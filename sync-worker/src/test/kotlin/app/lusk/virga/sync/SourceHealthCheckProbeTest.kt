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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robolectric coverage for the unit-testable parts of [SourceHealthCheck]: the timeout/close
 * mechanic ([SourceHealthCheck.timedReadForTest]), the open guard, and [probe]'s short-circuit
 * branches (non-content pass-through, unreadable tree). The DocumentFile depth-first walk and
 * the real SAF sample reads need a registered DocumentsProvider and are exercised by
 * instrumentation paths — see the codecov.yml ignore rationale for SourceHealthCheck.kt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceHealthCheckProbeTest {

    private lateinit var context: Context
    private lateinit var health: SourceHealthCheck

    private val realDispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.IO
        override val io = Dispatchers.IO
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        health = SourceHealthCheck(context, realDispatchers)
    }

    /** An InputStream whose read() blocks until close() is called from another thread. */
    private class BlockingStream(val closed: AtomicBoolean) : InputStream() {
        override fun read(): Int {
            while (!closed.get()) Thread.sleep(10)
            return -1
        }
        override fun close() { closed.set(true) }
    }

    @Test
    fun `a non-content source short-circuits to OK without touching SAF`() = runBlocking {
        assertThat(health.probe("/storage/emulated/0/DCIM")).isEqualTo(SourceHealthCheck.HealthResult.OK)
    }

    @Test
    fun `a content tree with no readable permission is UNREADABLE`() = runBlocking {
        // No DocumentsProvider is registered, so canRead() is false → UNREADABLE.
        val result = health.probe("content://com.example/tree/root")
        assertThat(result).isEqualTo(SourceHealthCheck.HealthResult.UNREADABLE)
    }

    @Test
    fun `timedRead returns TIMED_OUT and closes a wedged stream`() = runBlocking {
        val closed = AtomicBoolean(false)
        val result = health.timedReadForTest(BlockingStream(closed), timeoutMs = 50L)
        assertThat(result).isEqualTo(SourceHealthCheck.HealthResult.TIMED_OUT)
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun `timedRead returns OK for a stream that reads promptly`() = runBlocking {
        val result = health.timedReadForTest("data".byteInputStream(), timeoutMs = 5_000L)
        assertThat(result).isEqualTo(SourceHealthCheck.HealthResult.OK)
    }
}
