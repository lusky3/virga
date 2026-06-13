package app.lusk.virga.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Unit tests for [OAuthKeyStore] using a real in-process DataStore backed by a
 * temp file (mirrors [PreferencesRepositoryTest]). Covers set/clear/observe, the
 * trim + blank-removal semantics, the PREFIX strip on the read path, and the
 * IOException-degrades-to-empty behaviour.
 */
class OAuthKeyStoreTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @TempDir
    lateinit var tempDir: File

    private fun createStore(): OAuthKeyStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tempDir, "oauth.preferences_pb") },
        )
        return OAuthKeyStore(dataStore)
    }

    // --- defaults ---

    @Test fun `clientIds is empty and clientId is null before any writes`() = testScope.runTest {
        val store = createStore()

        assertThat(store.clientId("gdrive")).isNull()
        store.clientIds.test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- setClientId ---

    @Test fun `setClientId persists and is observable per provider`() = testScope.runTest {
        val store = createStore()

        store.setClientId("gdrive", "client-123")

        assertThat(store.clientId("gdrive")).isEqualTo("client-123")
        store.clientIds.test {
            assertThat(awaitItem()).containsExactly("gdrive", "client-123")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setClientId trims surrounding whitespace`() = testScope.runTest {
        val store = createStore()

        store.setClientId("dropbox", "  abc.def  ")

        assertThat(store.clientId("dropbox")).isEqualTo("abc.def")
    }

    @Test fun `setClientId with blank value removes the key`() = testScope.runTest {
        val store = createStore()
        store.setClientId("onedrive", "to-be-cleared")

        store.setClientId("onedrive", "   ") // blank after trim -> remove

        assertThat(store.clientId("onedrive")).isNull()
        store.clientIds.test {
            assertThat(awaitItem()).doesNotContainKey("onedrive")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setClientId overwrites a prior value for the same provider`() = testScope.runTest {
        val store = createStore()
        store.setClientId("gdrive", "old")

        store.setClientId("gdrive", "new")

        assertThat(store.clientId("gdrive")).isEqualTo("new")
    }

    // --- clearClientId ---

    @Test fun `clearClientId removes only the targeted provider`() = testScope.runTest {
        val store = createStore()
        store.setClientId("gdrive", "g-id")
        store.setClientId("dropbox", "d-id")

        store.clearClientId("gdrive")

        assertThat(store.clientId("gdrive")).isNull()
        assertThat(store.clientId("dropbox")).isEqualTo("d-id")
    }

    @Test fun `clearClientId on an absent provider is a no-op`() = testScope.runTest {
        val store = createStore()

        store.clearClientId("never-set") // must not throw

        store.clientIds.test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- clientIds map (PREFIX strip + filtering) ---

    @Test fun `clientIds returns every set provider with the prefix stripped`() = testScope.runTest {
        val store = createStore()
        store.setClientId("gdrive", "g")
        store.setClientId("dropbox", "d")
        store.setClientId("onedrive", "o")

        store.clientIds.test {
            assertThat(awaitItem()).containsExactly(
                "gdrive", "g",
                "dropbox", "d",
                "onedrive", "o",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `clientIds ignores prefs entries that do not carry the oauth prefix`() = testScope.runTest {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(tempDir, "oauth.preferences_pb") },
        )
        // Write a non-prefixed key directly alongside a prefixed one.
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("unrelated_setting")] = "ignore-me"
            prefs[stringPreferencesKey("oauth_client_id_gdrive")] = "kept"
        }
        val store = OAuthKeyStore(dataStore)

        store.clientIds.test {
            // Only the prefixed entry survives, with the prefix stripped.
            assertThat(awaitItem()).containsExactly("gdrive", "kept")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- IOException degradation ---

    @Test fun `clientIds degrades to empty when the DataStore throws IOException`() = testScope.runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        every { dataStore.data } returns flow { throw IOException("corrupt prefs") }
        val store = OAuthKeyStore(dataStore)

        store.clientIds.test {
            assertThat(awaitItem()).isEmpty()
            awaitComplete()
        }
    }

    @Test fun `non-IOException from the DataStore is rethrown`() = testScope.runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        every { dataStore.data } returns flow { throw IllegalStateException("boom") }
        val store = OAuthKeyStore(dataStore)

        store.clientIds.test {
            assertThat(awaitError()).isInstanceOf(IllegalStateException::class.java)
        }
    }
}
