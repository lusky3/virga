package app.lusk.virga.sync

import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit tests for [WatchdogController]'s enable/disable/heartbeat decisions, using
 * a recording fake for the Android side-effects ([WatchdogPlatform]) so no
 * emulator/Robolectric is needed.
 */
class WatchdogControllerTest {

    private class FakePlatform : WatchdogPlatform {
        val calls = mutableListOf<String>()
        override fun startService() { calls += "start" }
        override fun stopService() { calls += "stop" }
        override fun scheduleHeartbeat() { calls += "schedule" }
        override fun cancelHeartbeat() { calls += "cancel" }
        override suspend fun rescheduleSyncs() { calls += "reschedule" }
    }

    private val platform = FakePlatform()
    private val prefsFlow = MutableStateFlow(AppPreferences())
    private val preferences: PreferencesRepository = mockk {
        every { preferences } returns prefsFlow
    }

    private fun controller() = WatchdogController(platform, preferences)

    @Test
    fun setEnabled_true_startsServiceThenSchedulesHeartbeat() {
        controller().setEnabled(true)
        assertThat(platform.calls).containsExactly("start", "schedule").inOrder()
    }

    @Test
    fun setEnabled_false_cancelsHeartbeatThenStopsService() {
        controller().setEnabled(false)
        assertThat(platform.calls).containsExactly("cancel", "stop").inOrder()
    }

    @Test
    fun onHeartbeat_whenEnabled_reschedulesStartsAndRearms() = runBlocking {
        prefsFlow.value = AppPreferences(watchdogEnabled = true)

        controller().onHeartbeat()

        assertThat(platform.calls).containsExactly("reschedule", "start", "schedule").inOrder()
    }

    @Test
    fun onHeartbeat_whenDisabled_tearsDownAndDoesNotResurrect() = runBlocking {
        // A heartbeat that slips through after the watchdog was turned off must NOT
        // restart the service or re-arm the alarm (regression guard for the
        // heartbeat-resurrection bug).
        prefsFlow.value = AppPreferences(watchdogEnabled = false)

        controller().onHeartbeat()

        assertThat(platform.calls).containsExactly("cancel", "stop").inOrder()
        assertThat(platform.calls).containsNoneOf("start", "schedule", "reschedule")
    }
}
