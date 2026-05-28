package app.lusk.virga.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    val pagerState = rememberPagerState(pageCount = { Pages.size })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // On API <30 we ask for the legacy storage permission at runtime; on API
    // 30+ MES is not a runtime grant, so we hand the user to the system page.
    val readPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* No-op; we re-check permission state when the user returns. */ }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { pageIndex ->
                val page = Pages[pageIndex]
                PageContent(title = page.title, body = page.body)
            }

            PageIndicator(pageCount = Pages.size, current = pagerState.currentPage)

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    viewModel.completeOnboarding()
                    onFinished()
                }) { Text("Skip") }

                Button(onClick = {
                    when (pagerState.currentPage) {
                        1 -> requestStorageAccess(context, readPermissionLauncher::launch)
                        2 -> openBatterySettings(context)
                    }
                    if (pagerState.currentPage == Pages.lastIndex) {
                        viewModel.completeOnboarding()
                        onFinished()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }) {
                    Text(if (pagerState.currentPage == Pages.lastIndex) "Get started" else "Next")
                }
            }
        }
    }
}

private fun requestStorageAccess(
    context: Context,
    requestRuntimePermission: (String) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    } else {
        requestRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
private fun PageContent(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
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

private val Pages = listOf(
    Page(
        title = "Welcome to Virga",
        body = "Sync your phone's storage — including the SD card — to cloud providers " +
            "using a bundled rclone engine. No tracking, no accounts, just sync.",
    ),
    Page(
        title = "Storage access",
        body = "Virga needs filesystem access to sync your local folders. On Android 11+ " +
            "this is the \"All files access\" permission; below that it's the standard " +
            "storage permission. Tap Next to grant access.",
    ),
    Page(
        title = "Keep syncing in the background",
        body = "Some manufacturers aggressively kill background apps, which can stop " +
            "scheduled syncs. Exempt Virga from battery optimization (tap Next) and " +
            "see dontkillmyapp.com for device-specific steps.",
    ),
    Page(
        title = "Add your first remote",
        body = "On the Remotes tab, add a cloud provider or import an existing rclone.conf " +
            "from desktop, then create a sync task. Tap Get started.",
    ),
)
