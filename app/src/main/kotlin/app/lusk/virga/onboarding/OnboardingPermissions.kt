package app.lusk.virga.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.lusk.virga.R

/**
 * Resolved page indices for the three permission pages in the onboarding pager.
 *
 * [notif] is -1 on API < 33 (page absent); all callers treat -1 as a non-match
 * because pager indices are always >= 0.
 */
internal data class PageIndices(
    val storage: Int,
    val battery: Int,
    val notif: Int,
) {
    fun isPermissionPage(page: Int) = page == storage || page == battery || page == notif
}

/** True when POST_NOTIFICATIONS is granted (always true below API 33). */
internal fun isNotifGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Returns true when storage access is considered granted for this device/API. */
internal fun isStorageGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/** Returns true if Virga is exempt from battery optimization. */
internal fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun pageIsSatisfied(
    page: Int,
    indices: PageIndices,
    storageGranted: Boolean,
    batteryExempt: Boolean,
    notifGranted: Boolean,
): Boolean = when (page) {
    indices.storage -> storageGranted
    indices.battery -> batteryExempt
    indices.notif -> notifGranted
    else -> true
}

@Composable
internal fun permissionActionLabel(
    willLaunchIntent: Boolean,
    page: Int,
    indices: PageIndices,
): String? = when {
    !willLaunchIntent -> null
    page == indices.storage -> stringResource(R.string.onboarding_btn_open_storage_settings)
    page == indices.battery -> stringResource(R.string.onboarding_btn_open_battery_settings)
    page == indices.notif -> stringResource(R.string.onboarding_btn_open_notif_settings)
    else -> null
}

internal fun launchPermissionRequest(
    page: Int,
    indices: PageIndices,
    context: Context,
    requestStorage: (String) -> Unit,
    requestNotif: (String) -> Unit,
): Boolean = when (page) {
    indices.storage -> requestStorageAccess(context, requestStorage)
    indices.battery -> openBatterySettings(context)
    else -> {
        // Notifications page (API 33+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif(Manifest.permission.POST_NOTIFICATIONS)
        }
        true
    }
}

/** Launches appropriate storage settings; returns true if the intent was dispatched. */
internal fun requestStorageAccess(
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
internal fun openBatterySettings(context: Context): Boolean =
    runCatching {
        // The targeted "Allow <app> to ignore battery optimizations?" dialog
        // (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + its permission) is restricted
        // by Play policy to app categories a general sync app doesn't qualify for, so
        // only the sideload builds (github/fdroid, which keep the permission) use it.
        // The Play build strips the permission and opens the general optimization-
        // settings list instead (always allowed); that list is also the fallback.
        if (app.lusk.virga.BuildConfig.DISTRIBUTION != "play") {
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
