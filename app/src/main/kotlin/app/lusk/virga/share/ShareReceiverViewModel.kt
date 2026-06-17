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

    private val _extra = MutableStateFlow<List<Uri>>(emptyList())
    private val _selectedRemote = MutableStateFlow<Remote?>(null)
    private val _destPath = MutableStateFlow("")
    private val _uploadStatus = MutableStateFlow<UploadStatus>(UploadStatus.Idle)

    val uiState: StateFlow<ShareReceiverUiState> = combine(
        _extra,
        remoteRepository.remotes,
        _selectedRemote,
        _destPath,
        _uploadStatus,
    ) { uris, remotes, selected, dest, status ->
        val names = uris.map { uri ->
            sanitizeSafName(safDisplayName(context, uri))
        }
        ShareReceiverUiState(
            fileNames = names,
            remotes = remotes,
            selectedRemote = selected ?: remotes.firstOrNull(),
            destPath = dest,
            uploadStatus = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareReceiverUiState())

    fun setUris(uris: List<Uri>) {
        _extra.value = uris
    }

    fun selectRemote(remote: Remote) {
        _selectedRemote.update { remote }
    }

    fun setDestPath(path: String) {
        _destPath.update { path }
    }

    fun upload() {
        val uris = _extra.value
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
        for (uri in uris) {
            try {
                val staged = withContext(dispatchers.io) { stageUri(context, uri, stagingDir) }
                if (staged == null) { failed++; continue }
                val remotePath = buildRemotePath(destPath, staged.name)
                withContext(dispatchers.io) {
                    fileBrowserRepository.uploadFromLocal(staged, remoteName, remotePath)
                }
                staged.delete()
                succeeded++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }
        return UploadStatus.Done(succeeded, failed)
    }

    private fun buildRemotePath(destPath: String, fileName: String): String {
        val base = destPath.trim().trimEnd('/')
        return if (base.isEmpty()) fileName else "$base/$fileName"
    }
}
