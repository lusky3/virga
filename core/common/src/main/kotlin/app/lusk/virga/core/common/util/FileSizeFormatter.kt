package app.lusk.virga.core.common.util

import java.util.Locale

/** Formats a byte count into a human-readable size string (e.g. "1.5 MB"). */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024; i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
