package app.lusk.virga.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    // Permission pages whose system intent we've already launched. Lets the user
    // return and see the status update instead of the page auto-advancing past
    // it; a second tap still proceeds so they're never trapped if they decline.
    var intentLaunchedPages by remember { mutableStateOf(emptySet<Int>()) }

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

                // a11y-05: when the primary action will launch a system settings
                // intent (first tap on an unsatisfied permission page), override the
                // semantic onClick label to describe the real action.
                val page = pagerState.currentPage
                val satisfied = when (page) {
                    1 -> storageGranted
                    2 -> batteryExempt
                    else -> true
                }
                val willLaunchIntent = !satisfied && page !in intentLaunchedPages && (page == 1 || page == 2)
                val actionLabel = when {
                    willLaunchIntent && page == 1 -> stringResource(R.string.onboarding_btn_open_storage_settings)
                    willLaunchIntent && page == 2 -> stringResource(R.string.onboarding_btn_open_battery_settings)
                    else -> null
                }

                Button(
                    onClick = {
                        val currentPage = pagerState.currentPage
                        val currentSatisfied = when (currentPage) {
                            1 -> storageGranted
                            2 -> batteryExempt
                            else -> true
                        }
                        if (!currentSatisfied && currentPage !in intentLaunchedPages && (currentPage == 1 || currentPage == 2)) {
                            val ok = when (currentPage) {
                                1 -> requestStorageAccess(context, readPermissionLauncher::launch)
                                else -> openBatterySettings(context)
                            }
                            if (!ok) {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        if (currentPage == 1) storageSettingsError else batterySettingsError,
                                    )
                                }
                            }
                            intentLaunchedPages = intentLaunchedPages + currentPage
                            return@Button
                        }
                        if (currentPage == pages.lastIndex) {
                            viewModel.completeOnboarding()
                            onFinished()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                        }
                    },
                    modifier = if (actionLabel != null) {
                        Modifier.semantics { onClick(label = actionLabel, action = null) }
                    } else {
                        Modifier
                    },
                ) {
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
                    data = "package:${context.packageName}".toUri()
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
        // Targeted "Allow <app> to ignore battery optimizations?" dialog rather
        // than the full per-app optimization list. Requires the
        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (declared in the manifest).
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            },
        )
    }.recoverCatching {
        // Fall back to the optimization list if the targeted dialog is unavailable.
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

/**
 * Animated page indicator.
 *
 * a11y-04: the containing Row carries a "Page X of N" stateDescription and the
 * individual dots are cleared of semantics so TalkBack announces the row as a
 * single status element rather than N unlabelled tappable blobs.
 *
 * ui-12: the active dot animates its width and color.
 */
@Composable
private fun PageIndicator(pageCount: Int, current: Int) {
    val pageLabel = stringResource(R.string.onboarding_page_indicator, current + 1, pageCount)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { stateDescription = pageLabel },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { i ->
            val isActive = i == current

            val dotWidth by animateDpAsState(
                targetValue = if (isActive) 20.dp else 8.dp,
                animationSpec = tween(durationMillis = 200),
                label = "dotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = tween(durationMillis = 200),
                label = "dotColor",
            )

            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .padding(horizontal = 4.dp)
                    .width(dotWidth)
                    .height(8.dp)
                    .background(color = dotColor, shape = CircleShape),
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
