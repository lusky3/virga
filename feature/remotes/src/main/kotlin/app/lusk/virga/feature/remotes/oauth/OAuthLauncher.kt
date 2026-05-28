package app.lusk.virga.feature.remotes.oauth

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Launches a Custom Tabs session for the given OAuth authorize URL. */
internal fun launchCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
        .launchUrl(context, url.toUri())
}
