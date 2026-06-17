package app.lusk.virga.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.lusk.virga.R
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/** Callbacks grouped to keep composable parameter count under detekt limit (max 6). */
data class ShareReceiverCallbacks(
    val onRemoteSelected: (Remote) -> Unit,
    val onDestPathChanged: (String) -> Unit,
    val onUpload: () -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReceiverScreen(
    state: ShareReceiverUiState,
    callbacks: ShareReceiverCallbacks,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.share_receiver_title)) })
        },
        modifier = modifier,
    ) { padding ->
        when (state.uploadStatus) {
            UploadStatus.Uploading -> UploadingContent(Modifier.padding(padding))
            is UploadStatus.Done -> DoneContent(state.uploadStatus, callbacks.onDismiss, Modifier.padding(padding))
            is UploadStatus.Error -> ErrorContent(state.uploadStatus.message, callbacks.onDismiss, Modifier.padding(padding))
            UploadStatus.Idle -> IdleContent(state, callbacks, Modifier.padding(padding))
        }
    }
}

@Composable
private fun UploadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DoneContent(
    status: UploadStatus.Done,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_500)
        onDismiss()
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = pluralStringResource(R.plurals.share_upload_success, status.succeeded, status.succeeded),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (status.failed > 0) {
                Text(
                    text = pluralStringResource(R.plurals.share_upload_failure, status.failed, status.failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = VirgaSpacing.xs),
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = VirgaSpacing.sm)) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdleContent(
    state: ShareReceiverUiState,
    callbacks: ShareReceiverCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VirgaSpacing.md)
            .padding(bottom = VirgaSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
    ) {
        val fileCount = state.fileNames.size
        Text(
            text = pluralStringResource(R.plurals.share_file_count, fileCount, fileCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.remotes.isEmpty()) {
            NoRemotesContent(onDismiss = callbacks.onDismiss)
            return@Column
        }

        RemotePicker(
            remotes = state.remotes,
            selected = state.selectedRemote,
            onSelected = callbacks.onRemoteSelected,
        )

        OutlinedTextField(
            value = state.destPath,
            onValueChange = callbacks.onDestPathChanged,
            label = { Text(stringResource(R.string.share_dest_path_hint)) },
            placeholder = { Text(stringResource(R.string.share_dest_path_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = callbacks.onUpload,
            enabled = state.selectedRemote != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.share_upload_button))
        }
    }
}

@Composable
private fun NoRemotesContent(onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.share_no_remotes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = VirgaSpacing.sm)) {
            Text(stringResource(R.string.share_no_remotes_dismiss))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotePicker(
    remotes: List<Remote>,
    selected: Remote?,
    onSelected: (Remote) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.share_pick_remote_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            remotes.forEach { remote ->
                DropdownMenuItem(
                    text = { Text(remote.name) },
                    onClick = { onSelected(remote); expanded = false },
                )
            }
        }
    }
}
