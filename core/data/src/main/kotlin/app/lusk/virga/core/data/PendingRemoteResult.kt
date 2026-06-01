package app.lusk.virga.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot hand-off for "a remote was just created" — the returnable add-remote
 * result (WS1.2). When the user is mid-task-creation, taps "add a remote", and
 * finishes adding one (manually or via OAuth), [RemotesViewModel] writes the new
 * remote's name here; the task editor observes [created] and auto-selects it on
 * return, so adding a remote never dead-ends or loses the in-progress form.
 *
 * Mirrors [RemoteFolderPickStore] / the OAuth result hand-off so we don't need
 * Navigation 3's result API.
 */
@Singleton
class PendingRemoteResult @Inject constructor() {
    private val _created = MutableStateFlow<String?>(null)
    val created: StateFlow<String?> = _created

    /** Record that a remote named [remoteName] was just created. */
    fun created(remoteName: String) {
        _created.value = remoteName
    }

    /** Returns the pending remote name (if any) and clears it so it is delivered once. */
    fun consume(): String? = _created.value.also { _created.value = null }
}
