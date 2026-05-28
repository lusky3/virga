package app.lusk.virga

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.navigation.VirgaNavHost
import app.lusk.virga.onboarding.OnboardingScreen
import app.lusk.virga.onboarding.OnboardingViewModel
import app.lusk.virga.ui.theme.VirgaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Keep the system splash up until we know whether to show onboarding,
        // so we never flash the wrong UI on cold start.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VirgaTheme {
                val viewModel: OnboardingViewModel = hiltViewModel()
                val complete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
                ready = complete != null

                // Local override so the user immediately enters the app after
                // tapping "Get started", before the DataStore write propagates.
                var dismissed by remember { mutableStateOf(false) }

                when {
                    complete == null -> Unit // splash still showing
                    complete == true || dismissed -> VirgaNavHost()
                    else -> OnboardingScreen(onFinished = { dismissed = true })
                }
            }
        }
    }
}
