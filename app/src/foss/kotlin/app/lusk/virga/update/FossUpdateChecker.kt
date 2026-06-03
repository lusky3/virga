package app.lusk.virga.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import app.lusk.virga.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val RELEASES_API = "https://api.github.com/repos/lusky3/virga/releases/latest"
private const val RELEASES_PAGE = "https://github.com/lusky3/virga/releases/latest"

@Singleton
class FossUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateChecker {

    // Bounded timeouts so a slow/hung connection can't keep the IO coroutine
    // parked far longer than this best-effort background check deserves.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun check(): AvailableUpdate? = runCatching {
        // F-Droid builds set ENABLE_UPDATE_CHECK=false: the F-Droid client handles
        // updates, so we make no unsolicited GitHub call there. GitHub/sideload
        // builds keep it on so users without a store still learn about updates.
        if (!BuildConfig.ENABLE_UPDATE_CHECK) return@runCatching null
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }
            val tagName = JSONObject(body).optString("tag_name").ifEmpty { return@withContext null }
            val remoteVersion = tagName.removePrefix("v")
            if (isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                AvailableUpdate(remoteVersion)
            } else {
                null
            }
        }
    }.getOrNull()

    override fun startUpdate(activity: Activity) {
        // User-initiated: don't silently dead-end if no browser can handle the page
        // (ActivityNotFoundException / SecurityException). Log so the no-op is
        // diagnosable; the user can still reach the page from Settings → What's new.
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_PAGE.toUri()))
        }.onFailure { Log.w(TAG, "Couldn't open the releases page", it) }
    }

    private companion object {
        const val TAG = "FossUpdateChecker"
    }
}

/**
 * Returns true when [remote] is strictly newer than [local].
 * Compares dot-separated integer segments; non-numeric segments fall back to
 * string comparison so pre-release suffixes don't crash the check.
 */
internal fun isNewerVersion(remote: String, local: String): Boolean {
    val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
    val len = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until len) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r > l) return true
        if (r < l) return false
    }
    return false
}
