package app.lusk.virga

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.notification.NotificationDeepLinks
import app.lusk.virga.core.datastore.ThemeMode
import app.lusk.virga.lock.AppLockViewModel
import app.lusk.virga.lock.BiometricGate
import app.lusk.virga.lock.LockScreen
import app.lusk.virga.navigation.VirgaNavHost
import app.lusk.virga.onboarding.OnboardingScreen
import app.lusk.virga.onboarding.OnboardingViewModel
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /**
     * In-app destination requested by a notification deep link (e.g. the watchdog
     * "Settings" action). Snapshot state so updates from [onNewIntent] recompose.
     *
     * Consumed by [VirgaNavHost], which is only composed once onboarding is
     * complete. That's fine: this state persists across the onboarding→app
     * transition, so the route is honoured as soon as the host appears. And the
     * only current deep-link source — the watchdog notification — can only exist
     * after onboarding (the watchdog is enabled from Settings), so a pre-onboarding
     * deep link isn't a reachable case.
     *
     * IMPORTANT: pendingRoute / pendingTaskId are NOT consumed while the app is
     * locked. They remain set across the lock screen so the deep-link destination
     * is honoured immediately after authentication, just as if the lock weren't there.
     */
    private var pendingRoute by mutableStateOf<String?>(null)

    /** Task id for a [NotificationDeepLinks.ROUTE_TASK] deep link (e.g. a sync notification). */
    private var pendingTaskId by mutableLongStateOf(-1L)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLink(intent)
    }

    private fun readDeepLink(intent: Intent?) {
        // D7b: the "Add remote" static shortcut fires a dedicated action rather than
        // carrying EXTRA_OPEN_ROUTE as a string extra. Static shortcut extras are not
        // reliably delivered across all OEM launchers, so we use the action instead.
        if (intent?.action == ACTION_SHORTCUT_ADD_REMOTE) {
            pendingRoute = NotificationDeepLinks.ROUTE_ADD_REMOTE
            pendingTaskId = -1L
            return
        }
        pendingRoute = intent?.getStringExtra(NotificationDeepLinks.EXTRA_OPEN_ROUTE)
        pendingTaskId = intent?.getLongExtra(NotificationDeepLinks.EXTRA_TASK_ID, -1L) ?: -1L
    }

    private companion object {
        const val ACTION_SHORTCUT_ADD_REMOTE = "app.lusk.virga.action.SHORTCUT_ADD_REMOTE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        readDeepLink(intent)
        // Keep the system splash up until we know whether to show onboarding,
        // so we never flash the wrong UI on cold start.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeVm: AppThemeViewModel = hiltViewModel()
            val themePrefs by themeVm.themePrefs.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            // Respect the user's persisted theme + dynamic-color choices. Falls
            // back to system dark mode when ThemeMode is SYSTEM.
            VirgaTheme(
                darkTheme = when (themePrefs.themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> systemDark
                },
                dynamicColor = themePrefs.dynamicColor,
            ) {
                val viewModel: OnboardingViewModel = hiltViewModel()
                val complete by viewModel.onboardingComplete.collectAsStateWithLifecycle()

                // App-lock ViewModel. Only active inside VirgaTheme so it has the
                // correct Compose context. The lock gate only wraps the
                // authenticated-content (post-onboarding) branch; splash and
                // onboarding are unaffected.
                val lockVm: AppLockViewModel = hiltViewModel()
                val locked by lockVm.locked.collectAsStateWithLifecycle()
                val lockResolved by lockVm.resolved.collectAsStateWithLifecycle()

                // Hold the splash until BOTH onboarding status and the lock pref are
                // known, so a lock-enabled user never flashes unlocked content before
                // the lock state resolves on cold start.
                ready = complete != null && lockResolved

                // Local override so the user immediately enters the app after
                // tapping "Get started", before the DataStore write propagates.
                var dismissed by remember { mutableStateOf(false) }
                // When onboarding finishes for the very first time, funnel the
                // user directly into the guided setup wizard.
                var startWizard by remember { mutableStateOf(false) }

                when {
                    complete == null -> Unit // splash still showing
                    complete == true || dismissed -> {
                        if (locked) {
                            LockScreen(onUnlock = { launchBiometric(lockVm) })
                        } else {
                            VirgaNavHost(
                                startAtWizard = startWizard,
                                openRoute = pendingRoute,
                                openTaskId = pendingTaskId.takeIf { it > 0 },
                                onOpenRouteConsumed = { pendingRoute = null; pendingTaskId = -1L },
                            )
                        }
                    }
                    else -> OnboardingScreen(
                        onFinished = { startWizard = true; dismissed = true },
                        onAddFirstRemote = {
                            pendingRoute = NotificationDeepLinks.ROUTE_ADD_REMOTE
                            dismissed = true
                        },
                    )
                }
            }
        }
    }

    /**
     * Triggers the biometric/device-credential prompt via [BiometricGate].
     *
     * Fail-open: if the device can no longer authenticate (no enrolled biometric or
     * PIN/pattern/password), [AppLockViewModel.markUnlocked] is called immediately
     * rather than leaving the user permanently locked out. This is intentional —
     * app-lock is a UI convenience only; data is already encrypted at rest by rclone.
     */
    private fun launchBiometric(vm: AppLockViewModel) {
        if (BiometricGate.canAuthenticate(this)) {
            BiometricGate.authenticate(
                activity = this,
                onSuccess = { vm.markUnlocked() },
                onError = { /* stay locked; user can tap Unlock again */ },
            )
        } else {
            // No credential available — fail open so the user isn't bricked out.
            vm.markUnlocked()
        }
    }
}
