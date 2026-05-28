package app.lusk.virga.core.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.StorageRoot
import app.lusk.virga.core.common.model.StorageType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageAccessorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : StorageAccessor {

    private val storageManager: StorageManager? get() = context.getSystemService()

    override fun hasFullAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    override suspend fun getStorageRoots(): List<StorageRoot> = withContext(dispatchers.io) {
        val manager = storageManager ?: return@withContext emptyList()
        val fullAccess = hasFullAccess()
        manager.storageVolumes.mapNotNull { volume ->
            val path = volumePath(volume)
            val dir = path?.let(::File)
            StorageRoot(
                id = volume.uuid ?: if (volume.isPrimary) "primary" else volume.hashCode().toString(),
                displayName = volume.getDescription(context) ?: "Storage",
                type = volume.toStorageType(),
                filesystemPath = if (fullAccess) path else null,
                totalBytes = dir?.totalSpace ?: 0L,
                availableBytes = dir?.usableSpace ?: 0L,
            )
        }
    }

    override fun getFilesystemPath(root: StorageRoot): String? =
        if (hasFullAccess()) root.filesystemPath else null

    private fun StorageVolume.toStorageType(): StorageType = when {
        isPrimary -> StorageType.INTERNAL
        isRemovable -> StorageType.SD_CARD
        else -> StorageType.USB
    }

    private fun volumePath(volume: StorageVolume): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            volume.directory?.absolutePath
        volume.isPrimary ->
            Environment.getExternalStorageDirectory()?.absolutePath
        // Pre-R: derive the conventional mount point from the volume UUID.
        volume.uuid != null -> "/storage/${volume.uuid}"
        else -> null
    }
}
