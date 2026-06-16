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
import androidx.compose.animation.core.snap
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
import app.lusk.virga.core.designsystem.theme.VirgaMotion
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.designsystem.theme.rememberReduceMotion
import kotlinx.coroutines.launch

/**
 * Onboarding pager: welcome → storage → battery → (API 33+) notifications → first-remote.
 * Completing the final step persists the flag and navigates into the main app.
 *
 * @param onFinished Called when the user finishes onboarding via "Get started".
 * @param onAddFirstRemote Called when the user taps "Add your first remote" on the final
 *   page.  Caller should navigate to [AddRemoteRoute] via the pending-route mechanism.
 *   Defaults to no-op so callers that don't need it (e.g. tests) compile unchanged.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onAddFirstRemote: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val pages = buildOnboardingPages()
    // Static page indices — computed once from the actual list so the rest of
    // the composable stays correct whether or not the notifications page is present.
    val storagePageIndex = 1
    val batteryPageIndex = 2
    val notifPageIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 3 else -1

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val storageSettingsError = stringResource(R.string.onboarding_storage_settings_error)
    val batterySettingsError = stringResource(R.string.onboarding_battery_settings_error)

    // Track whether permissions are currently granted so we can reflect
    // granted vs still-needed after the user returns from system settings.
    var storageGranted by remember { mutableStateOf(isStorageGranted(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var notifGranted by remember { mutableStateOf(isNotifGranted(context)) }
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
                notifGranted = isNotifGranted(context)
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

    // POST_NOTIFICATIONS runtime permission (API 33+).
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // safeDrawingPadding keeps the pager content, page indicator, and the
        // Back/Next/Get-started row clear of the status bar and the gesture/3-button
        // navigation bar (the screen is drawn edge-to-edge via enableEdgeToEdge).
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(VirgaSpacing.lg)) {
            HorizontalPager(
                state = pagerState,
                // Drive paging only via Back/Next — free swiping let users silently skip
                // the permission pages (storage/battery) without ever seeing the prompt.
                userScrollEnabled = false,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { pageIndex ->
                val page = pages[pageIndex]
                // Show a status hint on permission pages when already granted or still needed.
                val statusHint = when (pageIndex) {
                    storagePageIndex -> if (storageGranted) {
                        stringResource(R.string.onboarding_storage_granted)
                    } else {
                        stringResource(R.string.onboarding_storage_needed)
                    }
                    batteryPageIndex -> if (batteryExempt) {
                        stringResource(R.string.onboarding_battery_exempt)
                    } else {
                        stringResource(R.string.onboarding_battery_needed)
                    }
                    notifPageIndex -> if (notifGranted) {
                        stringResource(R.string.onboarding_notif_granted)
                    } else {
                        stringResource(R.string.onboarding_notif_needed)
                    }
                    else -> null
                }
                PageContent(title = page.title, body = page.body, statusHint = statusHint)
            }

            PageIndicator(pageCount = pages.size, current = pagerState.currentPage)

            val page = pagerState.currentPage
            val isLastPage = page == pages.lastIndex
            val pageSatisfied = pageIsSatisfied(page, storagePageIndex, batteryPageIndex,
                notifPageIndex, storageGranted, batteryExempt, notifGranted)
            // a11y-05: on permission pages, the first tap on an unsatisfied page
            // launches a system permission request instead of advancing — override the
            // semantic onClick label to say so.
            val isPermissionPage = page == storagePageIndex || page == batteryPageIndex ||
                page == notifPageIndex
            val willLaunchIntent = !pageSatisfied && page !in intentLaunchedPages && isPermissionPage
            val actionLabel = permissionActionLabel(willLaunchIntent, page,
                storagePageIndex, batteryPageIndex, notifPageIndex)

            Row(
                Modifier.fillMaxWidth().padding(top = VirgaSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back button hidden on first page.
                if (page > 0) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(page - 1) }
                    }) { Text(stringResource(R.string.onboarding_btn_back)) }
                } else {
                    TextButton(onClick = {
                        viewModel.completeOnboarding()
                        onFinished()
                    }) { Text(stringResource(R.string.onboarding_btn_skip)) }
                }

                // On the final page: secondary "Get started" TextButton + primary CTA.
                if (isLastPage) {
                    TextButton(onClick = { viewModel.completeOnboarding(); onFinished() }) {
                        Text(stringResource(R.string.onboarding_btn_get_started))
                    }
                }

                Button(
                    onClick = {
                        if (willLaunchIntent) {
                            val ok = launchPermissionRequest(page, storagePageIndex,
                                batteryPageIndex, context, readPermissionLauncher::launch,
                                notifPermissionLauncher::launch)
                            if (!ok) {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        if (page == storagePageIndex) storageSettingsError
                                        else batterySettingsError,
                                    )
                                }
                            }
                            intentLaunchedPages = intentLaunchedPages + page
                            return@Button
                        }
                        if (isLastPage) {
                            viewModel.completeOnboarding()
                            onAddFirstRemote()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    },
                    modifier = if (actionLabel != null) {
                        Modifier.semantics { onClick(label = actionLabel, action = null) }
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        if (isLastPage) {
                            stringResource(R.string.onboarding_btn_add_first_remote)
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

/** True when POST_NOTIFICATIONS is granted (always true below API 33). */
private fun isNotifGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun pageIsSatisfied(
    page: Int,
    storageIdx: Int,
    batteryIdx: Int,
    notifIdx: Int,
    storageGranted: Boolean,
    batteryExempt: Boolean,
    notifGranted: Boolean,
): Boolean = when (page) {
    storageIdx -> storageGranted
    batteryIdx -> batteryExempt
    notifIdx -> notifGranted
    else -> true
}

@Composable
private fun permissionActionLabel(
    willLaunchIntent: Boolean,
    page: Int,
    storageIdx: Int,
    batteryIdx: Int,
    notifIdx: Int,
): String? = when {
    !willLaunchIntent -> null
    page == storageIdx -> stringResource(R.string.onboarding_btn_open_storage_settings)
    page == batteryIdx -> stringResource(R.string.onboarding_btn_open_battery_settings)
    page == notifIdx -> stringResource(R.string.onboarding_btn_open_notif_settings)
    else -> null
}

private fun launchPermissionRequest(
    page: Int,
    storageIdx: Int,
    batteryIdx: Int,
    context: Context,
    requestStorage: (String) -> Unit,
    requestNotif: (String) -> Unit,
): Boolean = when (page) {
    storageIdx -> requestStorageAccess(context, requestStorage)
    batteryIdx -> openBatterySettings(context)
    else -> {
        // Notifications page (API 33+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif(Manifest.permission.POST_NOTIFICATIONS)
        }
        true
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

/** Launches appropriate storage settings; returns true if the intent was dispatched. */
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

// BatteryLife: the direct ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is gated to the
// FOSS/sideload flavor (allowed there); the Play flavor strips the permission
// (play/AndroidManifest.xml) and only opens the general settings list, so the Play APK
// is policy-compliant. Lint can't model the runtime flavor branch, hence the suppression.
@Suppress("BatteryLife")
private fun openBatterySettings(context: Context): Boolean =
    runCatching {
        // The targeted "Allow <app> to ignore battery optimizations?" dialog
        // (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + its permission) is restricted
        // by Play policy to app categories a general sync app doesn't qualify for, so
        // only the FOSS/sideload build uses it. The Play build (and the fallback for
        // both) opens the general optimization-settings list, which is always allowed.
        if (app.lusk.virga.BuildConfig.DISTRIBUTION == "foss") {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                },
            )
        } else {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
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
            .padding(VirgaSpacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = VirgaSpacing.md),
        )
        statusHint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = VirgaSpacing.sm),
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
        val reduceMotion = rememberReduceMotion()
        repeat(pageCount) { i ->
            val isActive = i == current

            val dotWidth by animateDpAsState(
                targetValue = if (isActive) 20.dp else 8.dp,
                // Use the shared list-enter token (220 ms, decelerate) so dot transitions
                // stay consistent with other short UI animations. Snap when reduce-motion is on.
                animationSpec = if (reduceMotion) snap() else VirgaMotion.listEnterTween(),
                label = "dotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = if (reduceMotion) snap() else VirgaMotion.listEnterTween(),
                label = "dotColor",
            )

            Box(
                Modifier
                    .clearAndSetSemantics {}
                    .padding(horizontal = VirgaSpacing.xs)
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            Page(
                title = stringResource(R.string.onboarding_notif_title),
                body = stringResource(R.string.onboarding_notif_body),
            ),
        )
    }
    add(
        Page(
            title = stringResource(R.string.onboarding_first_remote_title),
            body = stringResource(R.string.onboarding_first_remote_body),
        ),
    )
}
