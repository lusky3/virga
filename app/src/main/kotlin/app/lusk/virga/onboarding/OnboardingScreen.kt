package app.lusk.virga.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lusk.virga.R
import kotlinx.coroutines.launch

/**
 * 4-step onboarding pager: welcome → storage permission → battery hint → done.
 * Completing the final step persists the flag and navigates into the main app.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val pages = buildOnboardingPages()
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val storageSettingsError = stringResource(R.string.onboarding_storage_settings_error)
    val batterySettingsError = stringResource(R.string.onboarding_battery_settings_error)

    // Track whether storage permission is currently granted so we can reflect
    // granted vs still-needed after the user returns from system settings.
    var storageGranted by remember { mutableStateOf(isStorageGranted(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    // Re-query permission state whenever the app resumes from the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = isStorageGranted(context)
                batteryExempt = isBatteryExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // On API <30 we ask for the legacy storage permission at runtime; on API
    // 30+ MES is not a runtime grant, so we hand the user to the system page.
    val readPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        storageGranted = granted
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // safeDrawingPadding keeps the pager content, page indicator, and the
        // Back/Next/Get-started row clear of the status bar and the gesture/3-button
        // navigation bar (the screen is drawn edge-to-edge via enableEdgeToEdge).
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { pageIndex ->
                val page = pages[pageIndex]
                // Show a status hint on permission pages when already granted or still needed.
                val statusHint = when {
                    pageIndex == 1 && storageGranted -> stringResource(R.string.onboarding_storage_granted)
                    pageIndex == 1 && !storageGranted -> stringResource(R.string.onboarding_storage_needed)
                    pageIndex == 2 && batteryExempt -> stringResource(R.string.onboarding_battery_exempt)
                    else -> null
                }
                PageContent(title = page.title, body = page.body, statusHint = statusHint)
            }

            PageIndicator(pageCount = pages.size, current = pagerState.currentPage)

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back button hidden on first page.
                if (pagerState.currentPage > 0) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Text(stringResource(R.string.onboarding_btn_back)) }
                } else {
                    TextButton(onClick = {
                        viewModel.completeOnboarding()
                        onFinished()
                    }) { Text(stringResource(R.string.onboarding_btn_skip)) }
                }

                Button(onClick = {
                    when (pagerState.currentPage) {
                        1 -> {
                            val ok = requestStorageAccess(context, readPermissionLauncher::launch)
                            if (!ok) {
                                scope.launch { snackbar.showSnackbar(storageSettingsError) }
                            }
                        }
                        2 -> {
                            val ok = openBatterySettings(context)
                            if (!ok) {
                                scope.launch { snackbar.showSnackbar(batterySettingsError) }
                            }
                        }
                    }
                    if (pagerState.currentPage == pages.lastIndex) {
                        viewModel.completeOnboarding()
                        onFinished()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }) {
                    Text(
                        if (pagerState.currentPage == pages.lastIndex) {
                            stringResource(R.string.onboarding_btn_get_started)
                        } else {
                            stringResource(R.string.onboarding_btn_next)
                        },
                    )
                }
            }
        }

        // Snackbar overlay for intent failure messages.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SnackbarHost(snackbar)
        }
    }
}

/** Returns true when storage access is considered granted for this device/API. */
private fun isStorageGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/** Returns true if Virga is exempt from battery optimization. */
private fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Launches the appropriate storage settings page.
 * Returns true if the intent was dispatched successfully.
 */
private fun requestStorageAccess(
    context: Context,
    requestRuntimePermission: (String) -> Unit,
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val result = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }
        if (result.isSuccess) return true
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.isSuccess
    } else {
        requestRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        return true
    }
}

/**
 * Opens battery optimization settings.
 * Returns true if the intent was dispatched successfully.
 */
private fun openBatterySettings(context: Context): Boolean =
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.isSuccess

@Composable
private fun PageContent(title: String, body: String, statusHint: String? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        statusHint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PageIndicator(pageCount: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == current) 10.dp else 8.dp)
                    .background(color = color, shape = CircleShape),
            )
        }
    }
}

private data class Page(val title: String, val body: String)

// Onboarding copy varies by distribution: F-Droid / GitHub builds get the
// full "SD card access" pitch; Play Store builds soften it since Play review
// is hostile to MANAGE_EXTERNAL_STORAGE for general sync apps.
@Composable
private fun buildOnboardingPages(): List<Page> = buildList {
    val welcomeBody = stringResource(
        if (app.lusk.virga.BuildConfig.SDCARD_ACCESS_AVAILABLE) {
            R.string.onboarding_welcome_body_foss
        } else {
            R.string.onboarding_welcome_body_play
        },
    )
    val storageBody = stringResource(
        if (app.lusk.virga.BuildConfig.SDCARD_ACCESS_AVAILABLE) {
            R.string.onboarding_storage_body_foss
        } else {
            R.string.onboarding_storage_body_play
        },
    )
    add(Page(title = stringResource(R.string.onboarding_welcome_title), body = welcomeBody))
    add(Page(title = stringResource(R.string.onboarding_storage_title), body = storageBody))
    add(
        Page(
            title = stringResource(R.string.onboarding_battery_title),
            body = stringResource(R.string.onboarding_battery_body),
        ),
    )
    add(
        Page(
            title = stringResource(R.string.onboarding_first_remote_title),
            body = stringResource(R.string.onboarding_first_remote_body),
        ),
    )
}
