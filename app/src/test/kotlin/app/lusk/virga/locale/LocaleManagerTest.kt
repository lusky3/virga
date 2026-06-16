package app.lusk.virga.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LocaleManager].
 *
 * [AppCompatDelegate.setApplicationLocales] and [AppCompatDelegate.getApplicationLocales]
 * are static methods on a Java class. Robolectric does not shadow them, so the round-trip
 * is tested by mocking the static methods via MockK. Each test verifies:
 *   - the correct [LocaleListCompat] argument was passed to setApplicationLocales, AND/OR
 *   - currentTag() returns the value that getApplicationLocales would produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleManagerTest {

    @Before
    fun setUp() {
        mockkStatic(AppCompatDelegate::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(AppCompatDelegate::class)
    }

    // --- apply with a valid tag calls setApplicationLocales ---

    @Test
    fun `apply with a valid BCP-47 tag calls setApplicationLocales with that tag`() {
        val captured = slot<LocaleListCompat>()
        every { AppCompatDelegate.setApplicationLocales(capture(captured)) } returns Unit

        LocaleManager.apply("fr")

        verify(exactly = 1) { AppCompatDelegate.setApplicationLocales(any()) }
        assertThat(captured.captured.toLanguageTags()).isEqualTo("fr")
    }

    @Test
    fun `apply with a different tag captures the new tag`() {
        val captured = slot<LocaleListCompat>()
        every { AppCompatDelegate.setApplicationLocales(capture(captured)) } returns Unit

        LocaleManager.apply("de")
        LocaleManager.apply("es")

        // Only the last call's argument is in the slot; verify it's the second tag.
        assertThat(captured.captured.toLanguageTags()).isEqualTo("es")
    }

    // --- apply with null or blank passes an empty LocaleListCompat ---

    @Test
    fun `apply with null passes empty LocaleListCompat to setApplicationLocales`() {
        val captured = slot<LocaleListCompat>()
        every { AppCompatDelegate.setApplicationLocales(capture(captured)) } returns Unit

        LocaleManager.apply(null)

        assertThat(captured.captured.isEmpty).isTrue()
    }

    @Test
    fun `apply with empty string passes empty LocaleListCompat to setApplicationLocales`() {
        val captured = slot<LocaleListCompat>()
        every { AppCompatDelegate.setApplicationLocales(capture(captured)) } returns Unit

        LocaleManager.apply("")

        assertThat(captured.captured.isEmpty).isTrue()
    }

    // --- currentTag reads back from AppCompatDelegate ---

    @Test
    fun `currentTag returns null when getApplicationLocales returns empty list`() {
        every { AppCompatDelegate.setApplicationLocales(any()) } returns Unit
        every { AppCompatDelegate.getApplicationLocales() } returns LocaleListCompat.getEmptyLocaleList()

        assertThat(LocaleManager.currentTag()).isNull()
    }

    @Test
    fun `currentTag returns the BCP-47 tag when a locale is active`() {
        val frLocaleList = LocaleListCompat.forLanguageTags("fr")
        every { AppCompatDelegate.setApplicationLocales(any()) } returns Unit
        every { AppCompatDelegate.getApplicationLocales() } returns frLocaleList

        assertThat(LocaleManager.currentTag()).isEqualTo("fr")
    }

    // --- apply then currentTag (integrated, using stubbed getApplicationLocales) ---

    @Test
    fun `apply with valid tag then currentTag returns that tag`() {
        val deLocaleList = LocaleListCompat.forLanguageTags("de")
        every { AppCompatDelegate.setApplicationLocales(any()) } returns Unit
        every { AppCompatDelegate.getApplicationLocales() } returns deLocaleList

        LocaleManager.apply("de")
        assertThat(LocaleManager.currentTag()).isEqualTo("de")
    }

    @Test
    fun `apply with null then currentTag returns null`() {
        every { AppCompatDelegate.setApplicationLocales(any()) } returns Unit
        every { AppCompatDelegate.getApplicationLocales() } returns LocaleListCompat.getEmptyLocaleList()

        LocaleManager.apply(null)
        assertThat(LocaleManager.currentTag()).isNull()
    }

    // --- setApplicationLocales is always called, even for null input ---

    @Test
    fun `apply always invokes setApplicationLocales exactly once`() {
        every { AppCompatDelegate.setApplicationLocales(any()) } returns Unit

        LocaleManager.apply("fr")

        verify(exactly = 1) { AppCompatDelegate.setApplicationLocales(any()) }
    }
}
