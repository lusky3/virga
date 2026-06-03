package app.lusk.virga

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.notification.NotificationDeepLinks
import app.lusk.virga.core.datastore.ThemeMode
import app.lusk.virga.navigation.VirgaNavHost
import app.lusk.virga.onboarding.OnboardingScreen
import app.lusk.virga.onboarding.OnboardingViewModel
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
        pendingRoute = intent?.getStringExtra(NotificationDeepLinks.EXTRA_OPEN_ROUTE)
        pendingTaskId = intent?.getLongExtra(NotificationDeepLinks.EXTRA_TASK_ID, -1L) ?: -1L
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
            // Android 13+ gates the dataSync foreground-service notification behind
            // runtime POST_NOTIFICATIONS. Request it once on launch so a sync's
            // foreground service can start even when onboarding was skipped. The
            // result is intentionally ignored — sync still runs if denied, the
            // progress notification just won't show.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* result ignored */ }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

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
                ready = complete != null

                // Local override so the user immediately enters the app after
                // tapping "Get started", before the DataStore write propagates.
                var dismissed by remember { mutableStateOf(false) }
                // When onboarding finishes for the very first time, funnel the
                // user directly into the guided setup wizard.
                var startWizard by remember { mutableStateOf(false) }

                when {
                    complete == null -> Unit // splash still showing
                    complete == true || dismissed -> VirgaNavHost(
                        startAtWizard = startWizard,
                        openRoute = pendingRoute,
                        openTaskId = pendingTaskId.takeIf { it > 0 },
                        onOpenRouteConsumed = { pendingRoute = null; pendingTaskId = -1L },
                    )
                    else -> OnboardingScreen(onFinished = { startWizard = true; dismissed = true })
                }
            }
        }
    }
}
