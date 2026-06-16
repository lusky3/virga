package app.lusk.virga.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [PreferencesRepository.setNotifyOnFailureOnly].
 *
 * Mirrors the existing boolean-pref test pattern used for watchdogEnabled /
 * appLockEnabled / quietHoursEnabled — real in-process DataStore backed by a
 * temp file; no Android context required.
 */
class NotifyOnFailureOnlyPreferenceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @TempDir
    lateinit var tempDir: File

    private fun createRepo(): PreferencesRepository {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tempDir, "prefs.preferences_pb") },
        )
        return PreferencesRepository(dataStore)
    }

    // --- defaults ---

    @Test fun `notifyOnFailureOnly defaults to false`() = testScope.runTest {
        val repo = createRepo()
        repo.preferences.test {
            assertThat(awaitItem().notifyOnFailureOnly).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- setNotifyOnFailureOnly true ---

    @Test fun `setNotifyOnFailureOnly true is persisted`() = testScope.runTest {
        val repo = createRepo()

        repo.setNotifyOnFailureOnly(true)

        repo.preferences.test {
            assertThat(awaitItem().notifyOnFailureOnly).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- toggle back to false ---

    @Test fun `setNotifyOnFailureOnly false after true reverts to false`() = testScope.runTest {
        val repo = createRepo()
        repo.setNotifyOnFailureOnly(true)

        repo.setNotifyOnFailureOnly(false)

        repo.preferences.test {
            assertThat(awaitItem().notifyOnFailureOnly).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
