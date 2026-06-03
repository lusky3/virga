package app.lusk.virga.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Lets users grant the all-files storage access they may have skipped during onboarding
 * (the onboarding step is now skippable). Self-contained and only rendered (by the caller,
 * gated on a foss/all-files build) when access is NOT already granted — it tracks the grant
 * state, re-checks on resume (returning from system settings), and disappears once granted.
 * Mirrors the onboarding storage flow.
 */
@Composable
internal fun StorageAccessSection() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(isStorageGranted(context)) }
    // API <30 grants READ_EXTERNAL_STORAGE at runtime; 30+ is handed to system settings.
    val readPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }
    // Re-query when returning from the system all-files-access page.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isStorageGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return // nothing to offer once access is held

    val failMsg = stringResource(R.string.settings_snack_no_storage_settings)
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_storage))
    Text(
        stringResource(R.string.settings_storage_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            if (!requestStorageAccess(context, readPermissionLauncher::launch)) {
                Toast.makeText(context, failMsg, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.padding(top = VirgaSpacing.sm),
    ) {
        Text(stringResource(R.string.settings_btn_grant_storage))
    }
}

/** True when storage access is considered granted for this device/API. */
internal fun isStorageGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Launches the appropriate storage-access flow; returns false if no intent could be
 * dispatched (caller surfaces a message). On API 30+ opens the app's All-files-access
 * settings (falling back to the global list); below that requests the runtime permission.
 */
internal fun requestStorageAccess(context: Context, requestRuntimePermission: (String) -> Unit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                },
            )
        }.isSuccess
        if (opened) return true
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.isSuccess
    }
    requestRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    return true
}
