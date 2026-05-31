package app.lusk.virga.feature.sync

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

private data class StorageRoot(val label: String, val dir: File, val removable: Boolean)

/**
 * Real-filesystem folder picker for builds that hold all-files access
 * (MANAGE_EXTERNAL_STORAGE). Unlike SAF's `OpenDocumentTree`, this can select the
 * root of internal storage or an SD card — paths Android's document picker blocks
 * with a "privacy" restriction. Used only when [Environment.isExternalStorageManager]
 * is true; scoped-storage builds still use SAF.
 */
@Composable
internal fun LocalFolderPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (path: String) -> Unit,
) {
    val context = LocalContext.current
    val roots = remember { discoverStorageRoots(context) }
    // null => showing the volume list; otherwise browsing inside a volume.
    var current by remember { mutableStateOf(if (roots.size == 1) roots.first().dir else null) }

    val atVolumeRoot = current != null && roots.any { it.dir.absolutePath == current!!.absolutePath }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                current?.absolutePath ?: stringResource(R.string.sync_localpick_choose_storage),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            val dir = current
            // Listed in the @Composable scope (not the LazyListScope below).
            val subdirs = remember(dir?.absolutePath) {
                dir?.listFiles()
                    ?.filter { it.isDirectory && it.canRead() && !it.isHidden }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (dir == null) {
                    items(roots) { root ->
                        ListItem(
                            headlineContent = { Text(root.label) },
                            leadingContent = {
                                Icon(
                                    if (root.removable) Icons.Filled.SdStorage else Icons.Filled.Smartphone,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { current = root.dir },
                        )
                    }
                } else {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.sync_localpick_up)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            modifier = Modifier.clickable {
                                current = if (atVolumeRoot || dir.parentFile == null) null else dir.parentFile
                            },
                        )
                    }
                    items(subdirs) { sub ->
                        ListItem(
                            headlineContent = { Text(sub.name) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            modifier = Modifier.clickable { current = sub },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = current != null,
                onClick = { current?.let { onSelect(it.absolutePath) }; onDismiss() },
            ) { Text(stringResource(R.string.sync_localpick_select)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_edit_cancel)) }
        },
    )
}

/**
 * Accessible storage volume roots. Internal storage is always present; removable
 * volumes (SD card / USB) are found via [StorageManager.getStorageVolumes] +
 * [android.os.storage.StorageVolume.getDirectory] (API 30+), which lists the SD
 * card even when [Context.getExternalFilesDirs] doesn't (newer Android often
 * doesn't expose the card as an app-specific dir). [Context.getExternalFilesDirs]
 * is used as a fallback for older releases. Keyed by path to de-duplicate.
 */
private fun discoverStorageRoots(context: Context): List<StorageRoot> {
    val internal = Environment.getExternalStorageDirectory()
    val roots = LinkedHashMap<String, StorageRoot>()
    roots[internal.absolutePath] = StorageRoot("Internal storage", internal, removable = false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val sm = context.getSystemService(StorageManager::class.java)
        sm?.storageVolumes?.forEach { vol ->
            if (vol.state != Environment.MEDIA_MOUNTED) return@forEach
            val dir = vol.directory ?: return@forEach
            if (!dir.canRead()) return@forEach
            val label = when {
                vol.isPrimary -> "Internal storage"
                else -> vol.getDescription(context) ?: "SD card"
            }
            roots.putIfAbsent(dir.absolutePath, StorageRoot(label, dir, removable = vol.isRemovable))
        }
    }

    // Fallback (pre-R, or any volume the above missed): app-specific dirs look
    // like /storage/<vol>/Android/data/<pkg>/files — the volume root precedes "/Android/".
    context.getExternalFilesDirs(null).forEach { f ->
        f ?: return@forEach
        val idx = f.absolutePath.indexOf("/Android/")
        if (idx <= 0) return@forEach
        val volRoot = File(f.absolutePath.substring(0, idx))
        if (volRoot.canRead()) {
            val removable = volRoot.absolutePath != internal.absolutePath
            roots.putIfAbsent(
                volRoot.absolutePath,
                StorageRoot(if (removable) "SD card" else "Internal storage", volRoot, removable),
            )
        }
    }
    return roots.values.toList()
}
