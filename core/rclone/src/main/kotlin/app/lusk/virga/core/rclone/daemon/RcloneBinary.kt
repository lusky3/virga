package app.lusk.virga.core.rclone.daemon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locates the extracted rclone executable. Because it ships as
 * lib/<abi>/librclone.so with legacy packaging, Android extracts it to the
 * app's nativeLibraryDir at install time, where it is executable.
 */
@Singleton
class RcloneBinary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val file: File
        get() = File(context.applicationInfo.nativeLibraryDir, "librclone.so")

    fun exists(): Boolean = file.exists() && file.canExecute()
}
