package app.lusk.virga.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A folder chosen in the remote file browser, handed back to whoever opened it. */
data class PickedFolder(val remoteName: String, val path: String)

/**
 * One-shot channel for "pick a remote folder" navigation results. The file
 * browser (opened in pick mode) writes the chosen folder here; the screen that
 * opened it (e.g. the task editor) observes [picked] and [consume]s it. Mirrors
 * the OAuth-result hand-off pattern so we don't need Navigation 3's result API.
 */
@Singleton
class RemoteFolderPickStore @Inject constructor() {
    private val _picked = MutableStateFlow<PickedFolder?>(null)
    val picked: StateFlow<PickedFolder?> = _picked

    fun pick(remoteName: String, path: String) {
        _picked.value = PickedFolder(remoteName, path)
    }

    /** Returns the pending pick (if any) and clears it so it is delivered once. */
    fun consume(): PickedFolder? = _picked.value.also { _picked.value = null }
}
