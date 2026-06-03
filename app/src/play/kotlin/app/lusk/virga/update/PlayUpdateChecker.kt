package app.lusk.virga.update

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val UPDATE_REQUEST_CODE = 5001
private const val TAG = "PlayUpdateChecker"

@Singleton
class PlayUpdateChecker @Inject constructor(
    @ApplicationContext context: Context,
) : UpdateChecker {

    private val appUpdateManager = AppUpdateManagerFactory.create(context)

    // Retained so startUpdate can use the most recently fetched info.
    @Volatile private var cachedInfo: AppUpdateInfo? = null

    override suspend fun check(): AvailableUpdate? = runCatching {
        val info = appUpdateManager.requestAppUpdateInfo()
        cachedInfo = info
        if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            AvailableUpdate(versionLabel = "")
        } else {
            null
        }
    }.getOrNull()

    override fun startUpdate(activity: Activity) {
        val info = cachedInfo
        if (info == null || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            // The cached info expired or is no longer actionable (the user may have
            // already updated, or it predates a Play refresh). Don't silently no-op
            // forever — the next check() refreshes cachedInfo and re-shows the banner.
            Log.w(TAG, "startUpdate skipped: no actionable cached AppUpdateInfo")
            return
        }
        runCatching {
            appUpdateManager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE),
                UPDATE_REQUEST_CODE,
            )
        }.onFailure { Log.w(TAG, "Play in-app update flow failed to start", it) }
    }
}
