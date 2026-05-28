package app.lusk.virga.core.storage

import app.lusk.virga.core.common.model.StorageRoot

/**
 * Enumerates mountable storage volumes and reports whether the app has
 * filesystem-level access (MANAGE_EXTERNAL_STORAGE). rclone needs real
 * filesystem paths, so [StorageRoot.filesystemPath] is null unless [hasFullAccess].
 */
interface StorageAccessor {
    suspend fun getStorageRoots(): List<StorageRoot>
    fun hasFullAccess(): Boolean
    fun getFilesystemPath(root: StorageRoot): String?
}
