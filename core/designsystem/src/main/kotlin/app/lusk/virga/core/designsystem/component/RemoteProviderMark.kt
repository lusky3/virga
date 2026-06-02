package app.lusk.virga.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.R

/**
 * Leading provider glyph for a cloud remote card (BRAND §6: "tinted vector
 * assets keyed by rclone backend type; fall back to a generic cloud glyph").
 *
 * Brand marks are recognizable provider shapes (Drive, Dropbox, MEGA, Box,
 * Google Photos, Backblaze B2) sourced from Simple Icons (CC0). They are stored
 * monochrome and tinted here with `onSurfaceVariant` so they stay legible and
 * WCAG-consistent across light/dark/dynamic schemes — the brand colour is not
 * baked in. Backends without a freely-licensed mark (OneDrive, S3, pCloud) and
 * the non-branded backends (SFTP/FTP/WebDAV/crypt/local) use a meaningful
 * Material Symbol; anything unknown falls back to a generic cloud.
 *
 * @param type The rclone backend id (e.g. "drive", "onedrive").
 * @param contentDescription Accessible label — pass the friendly provider name.
 * @param modifier Optional modifier; default size is 24.dp.
 */
@Composable
fun RemoteProviderMark(
    type: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val brand = brandMark(type)
    if (brand != null) {
        Icon(
            painter = painterResource(brand),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(24.dp),
        )
    } else {
        Icon(
            imageVector = fallbackMark(type),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(24.dp),
        )
    }
}

/** Backends with a bundled, freely-licensed brand shape. */
@DrawableRes
private fun brandMark(type: String): Int? = when (type) {
    "drive" -> R.drawable.ic_provider_drive
    "dropbox" -> R.drawable.ic_provider_dropbox
    "mega" -> R.drawable.ic_provider_mega
    "box" -> R.drawable.ic_provider_box
    "gphotos" -> R.drawable.ic_provider_gphotos
    "b2" -> R.drawable.ic_provider_b2
    else -> null
}

/**
 * Meaningful Material Symbol for backends without a bundled brand mark.
 *
 * OneDrive / S3 / pCloud have no freely-licensed glyph: their official logos are
 * trademarked and the brand guidelines forbid recolouring them to monochrome
 * (and they were withdrawn from the CC0 Simple Icons set). Rather than ship a
 * misappropriated/modified logo, each gets a *distinct, meaningful* monochrome
 * Material Symbol (Apache-2.0) so they read as different providers — never three
 * identical clouds. If official, licensed marks are obtained later, move the
 * provider into [brandMark].
 */
private fun fallbackMark(type: String) = when (type) {
    "s3" -> Icons.Filled.Storage // object/bucket storage
    "onedrive" -> Icons.Filled.Cloud // a cloud service
    "pcloud" -> Icons.Filled.CloudSync // cloud backup/sync
    "webdav" -> Icons.Outlined.CloudQueue // generic networked storage
    "sftp", "ftp" -> Icons.Filled.Dns // a server host
    "crypt" -> Icons.Filled.Lock
    "local" -> Icons.Filled.Folder
    else -> Icons.Outlined.CloudQueue // unknown backend
}
