package app.lusk.virga.share

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * Tests for the [ShareReceiverActivity.extractUris] intent-parsing logic.
 *
 * # Why reflection?
 * [extractUris] is `private` and lives inside a Hilt `@AndroidEntryPoint`
 * activity, making it non-trivial to launch with real injection in a pure unit
 * test. The function itself has no Android-runtime side effects beyond reading
 * the [Intent]; using reflection to call it on a plain instantiated activity
 * (no `onCreate`, no Hilt) is the lowest-risk seam that avoids touching
 * production code or wiring a full instrumented Hilt test graph.
 *
 * If a future refactoring extracts [extractUris] to a package-internal helper
 * (like [sanitizeSafName]) these tests can be simplified to a direct call.
 *
 * # Why Robolectric?
 * [android.net.Uri] and [android.content.Intent] are Android classes that need a
 * runtime to behave correctly (Uri.parse in particular returns a stub under
 * plain JVM). Robolectric supplies the shadow implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtractUrisTest {

    /**
     * Reflective accessor for [ShareReceiverActivity.extractUris].
     * Cached once per test class; the method is stable.
     */
    private val extractUrisMethod: Method by lazy {
        ShareReceiverActivity::class.java
            .getDeclaredMethod("extractUris", Intent::class.java)
            .also { it.isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractUris(activity: ShareReceiverActivity, intent: Intent?): List<Uri> =
        extractUrisMethod.invoke(activity, intent) as List<Uri>

    /**
     * Creates an unstarted [ShareReceiverActivity] instance for reflection.
     * We only need the instance as the receiver; we never call [onCreate].
     * Hilt injection is skipped because [extractUrisMethod] accesses no
     * injected fields.
     */
    private fun activity(): ShareReceiverActivity =
        ShareReceiverActivity::class.java.getDeclaredConstructor().newInstance()

    // ── ACTION_SEND (single file) ─────────────────────────────────────────────

    @Test
    fun `should return single-element list for ACTION_SEND with EXTRA_STREAM`() {
        val uri = Uri.parse("content://com.example/file/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val result = extractUris(activity(), intent)

        assertThat(result).containsExactly(uri)
    }

    @Test
    fun `should return empty list for ACTION_SEND when EXTRA_STREAM is absent`() {
        val intent = Intent(Intent.ACTION_SEND)

        val result = extractUris(activity(), intent)

        assertThat(result).isEmpty()
    }

    // ── ACTION_SEND_MULTIPLE ──────────────────────────────────────────────────

    @Test
    fun `should return all URIs for ACTION_SEND_MULTIPLE with two streams`() {
        val uri1 = Uri.parse("content://com.example/file/1")
        val uri2 = Uri.parse("content://com.example/file/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri1, uri2))
        }

        val result = extractUris(activity(), intent)

        assertThat(result).containsExactly(uri1, uri2).inOrder()
    }

    @Test
    fun `should return empty list for ACTION_SEND_MULTIPLE when EXTRA_STREAM is absent`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)

        val result = extractUris(activity(), intent)

        assertThat(result).isEmpty()
    }

    // ── other / unknown actions ───────────────────────────────────────────────

    @Test
    fun `should return empty list for an unknown action`() {
        val intent = Intent("com.example.CUSTOM_ACTION")

        val result = extractUris(activity(), intent)

        assertThat(result).isEmpty()
    }

    @Test
    fun `should return empty list for a null intent`() {
        val result = extractUris(activity(), null)

        assertThat(result).isEmpty()
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `should return single-element list for ACTION_SEND_MULTIPLE with exactly one URI`() {
        val uri = Uri.parse("content://com.example/single/1")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
        }

        val result = extractUris(activity(), intent)

        assertThat(result).containsExactly(uri)
    }
}
