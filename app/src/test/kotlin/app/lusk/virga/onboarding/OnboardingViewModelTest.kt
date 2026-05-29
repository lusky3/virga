package app.lusk.virga.onboarding

import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val prefsFlow = MutableStateFlow(AppPreferences())
    private val repository: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns prefsFlow
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun onboardingComplete_startsAsNull_thenReflectsRepository() = runTest(dispatcher) {
        val vm = OnboardingViewModel(repository)
        // Initial value is null while the SharingStarted.Eagerly is still
        // priming — assert that AFTER advanceUntilIdle we see the repo flag.
        assertThat(vm.onboardingComplete.value).isNull()

        prefsFlow.value = AppPreferences(onboardingComplete = false)
        advanceUntilIdle()
        assertThat(vm.onboardingComplete.value).isFalse()

        prefsFlow.value = AppPreferences(onboardingComplete = true)
        advanceUntilIdle()
        assertThat(vm.onboardingComplete.value).isTrue()
    }

    @Test
    fun completeOnboarding_writesTrueToRepository() = runTest(dispatcher) {
        val vm = OnboardingViewModel(repository)
        vm.completeOnboarding()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setOnboardingComplete(true) }
    }
}
