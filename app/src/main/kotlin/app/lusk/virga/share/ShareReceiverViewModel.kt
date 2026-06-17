package app.lusk.virga.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Upload phase for the share receiver UI. */
sealed interface UploadStatus {
    data object Idle : UploadStatus
    data object Uploading : UploadStatus
    data class Done(val succeeded: Int, val failed: Int) : UploadStatus
    data class Error(val message: String) : UploadStatus
}

data class ShareReceiverUiState(
    val fileNames: List<String> = emptyList(),
    val remotes: List<Remote> = emptyList(),
    val selectedRemote: Remote? = null,
    val destPath: String = "",
    val uploadStatus: UploadStatus = UploadStatus.Idle,
)

@HiltViewModel
class ShareReceiverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteRepository: RemoteRepository,
    private val fileBrowserRepository: FileBrowserRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    // Resolved once off-main in setUris(); never re-derived from URIs in combine.
    private val _fileNames = MutableStateFlow<List<String>>(emptyList())

    // Raw URIs kept separately for the upload path.
    private val _uris = MutableStateFlow<List<Uri>>(emptyList())

    private val _selectedRemote = MutableStateFlow<Remote?>(null)
    private val _destPath = MutableStateFlow("")
    private val _uploadStatus = MutableStateFlow<UploadStatus>(UploadStatus.Idle)

    val uiState: StateFlow<ShareReceiverUiState> = combine(
        _fileNames,
        remoteRepository.remotes,
        _selectedRemote,
        _destPath,
        _uploadStatus,
    ) { names, remotes, selected, dest, status ->
        ShareReceiverUiState(
            fileNames = names,
            remotes = remotes,
            selectedRemote = selected ?: remotes.firstOrNull(),
            destPath = dest,
            uploadStatus = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareReceiverUiState())

    /**
     * Seeds the URIs to upload. Resolves display names off-main (once) and pushes
     * them into [_fileNames] so the combine never runs ContentResolver queries.
     * Safe to call multiple times — subsequent calls replace the previous batch.
     */
    fun setUris(uris: List<Uri>) {
        _uris.value = uris
        viewModelScope.launch(dispatchers.io) {
            val names = uris.map { uri ->
                sanitizeSafName(safDisplayName(context, uri))
            }
            _fileNames.value = names
        }
    }

    fun selectRemote(remote: Remote) {
        _selectedRemote.update { remote }
    }

    fun setDestPath(path: String) {
        _destPath.update { path }
    }

    fun upload() {
        val uris = _uris.value
        val remoteName = uiState.value.selectedRemote?.name ?: return
        val destPath = _destPath.value.trim()
        _uploadStatus.value = UploadStatus.Uploading
        viewModelScope.launch {
            val result = runUpload(uris, remoteName, destPath)
            _uploadStatus.value = result
        }
    }

    private suspend fun runUpload(
        uris: List<Uri>,
        remoteName: String,
        destPath: String,
    ): UploadStatus {
        val stagingDir = File(context.cacheDir, "shared").also { it.mkdirs() }
        var succeeded = 0
        var failed = 0
        val usedNames = mutableSetOf<String>()
        for (uri in uris) {
            var staged: File? = null
            try {
                val s = withContext(dispatchers.io) { stageUri(context, uri, stagingDir) }
                if (s == null) { failed++; continue }
                staged = s
                val uniqueName = disambiguate(s.name, usedNames)
                usedNames += uniqueName
                val remotePath = buildRemotePath(destPath, uniqueName)
                withContext(dispatchers.io) {
                    fileBrowserRepository.uploadFromLocal(s, remoteName, remotePath)
                }
                succeeded++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            } finally {
                // Always remove the cache copy — also on upload failure / cancellation.
                staged?.delete()
            }
        }
        return UploadStatus.Done(succeeded, failed)
    }

    private fun buildRemotePath(destPath: String, fileName: String): String {
        val base = destPath.trim().trimEnd('/')
        return if (base.isEmpty()) fileName else "$base/$fileName"
    }
}

/**
 * Returns [name] if it is not in [used], otherwise appends " (N)" before the
 * extension (e.g. `photo (2).jpg`, `photo (3).jpg`, …) until a free slot is found.
 */
internal fun disambiguate(name: String, used: Set<String>): String {
    if (name !in used) return name
    // dot > 0 so a leading-dot file (e.g. ".gitignore") is treated as extension-less
    // and disambiguates to ".gitignore (2)" rather than " (2).gitignore".
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 2
    while (true) {
        val candidate = "$base ($n)$ext"
        if (candidate !in used) return candidate
        n++
    }
}
