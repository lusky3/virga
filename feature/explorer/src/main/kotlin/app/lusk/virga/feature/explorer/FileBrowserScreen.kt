package app.lusk.virga.feature.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.ui.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onBack: () -> Unit,
    onNavigateToRemotes: () -> Unit = {},
    viewModel: FileBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val remotes by viewModel.remotes.collectAsStateWithLifecycle()

    // In-folder back navigates up; at root it leaves the screen.
    BackHandler(enabled = state.remoteName != null && !state.atRoot) { viewModel.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // Task #25c: Ellipsis on remote name / path headlines
                        Text(
                            state.remoteName ?: stringResource(R.string.explorer_title_browse),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.remoteName != null) {
                            Text(
                                "/" + state.breadcrumb.joinToString("/"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (!state.atRoot) viewModel.up() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.explorer_cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.remoteName == null -> RemotePicker(
                    remotes = remotes,
                    onSelect = viewModel::selectRemote,
                    onNavigateToRemotes = onNavigateToRemotes,
                )
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> ErrorState(state.error!!, viewModel::retry)
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.truncated) {
                        Text(
                            stringResource(R.string.explorer_truncated_notice),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (state.entries.isEmpty()) {
                        EmptyFolder()
                    } else {
                        FileList(state.entries, viewModel::open)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemotePicker(
    remotes: List<String>,
    onSelect: (String) -> Unit,
    onNavigateToRemotes: () -> Unit,
) {
    if (remotes.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.explorer_no_remotes),
            action = {
                TextButton(onClick = onNavigateToRemotes) {
                    Text(stringResource(R.string.explorer_add_remote))
                }
            },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(remotes, key = { it }) { name ->
            ListItem(
                // Task #25c: ellipsis on long remote names in the picker list
                headlineContent = {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                modifier = Modifier.clickable { onSelect(name) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FileList(entries: List<FileItem>, onOpen: (FileItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { item ->
            ListItem(
                // Task #25c: Ellipsis on file/directory names
                headlineContent = {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    if (!item.isDir) Text(formatFileSize(item.size))
                },
                leadingContent = {
                    Icon(
                        if (item.isDir) Icons.Filled.Folder
                        else Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable(enabled = item.isDir) { onOpen(item) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyFolder() {
    EmptyState(title = stringResource(R.string.explorer_empty_folder))
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.explorer_btn_retry)) }
        }
    }
}
