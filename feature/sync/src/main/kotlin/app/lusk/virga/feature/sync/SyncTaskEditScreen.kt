package app.lusk.virga.feature.sync

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncDirection
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTaskEditScreen(
    taskId: Long,
    prefillRemote: String? = null,
    prefillRemotePath: String? = null,
    onBack: () -> Unit,
    onNavigateToRemotes: () -> Unit = {},
    viewModel: SyncTaskEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.load(taskId, prefillRemote, prefillRemotePath) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val remotes by viewModel.availableRemotes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val unresolvedMsg = stringResource(R.string.sync_edit_source_path_unresolvable)

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            if (android.os.Environment.isExternalStorageManager()) {
                val path = resolveTreeUriToPath(uri)
                if (path != null) {
                    viewModel.applySourcePath(path)
                } else {
                    coroutineScope.launch { snackbarHostState.showSnackbar(unresolvedMsg) }
                }
            } else {
                // Scoped storage: store the content:// tree URI directly.
                // SAF permission is already persisted above; staging happens in SyncWorker.
                viewModel.applySourcePath(uri.toString())
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (taskId > 0) stringResource(R.string.sync_edit_title_edit)
                        else stringResource(R.string.sync_edit_title_new),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sync_edit_cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Task name. Track focus-then-blur so the "required" error only appears
            // after real user interaction (onFocusChanged fires with isFocused=false
            // on initial composition, which must NOT mark the field touched).
            val nameWasFocused = remember { mutableStateOf(false) }
            OutlinedTextField(
                value = form.name,
                onValueChange = { viewModel.update { f -> f.copy(name = it) } },
                label = { Text(stringResource(R.string.sync_edit_field_name)) },
                singleLine = true,
                isError = form.nameError != null,
                supportingText = form.nameError?.let { msg ->
                    { Text(msg, modifier = Modifier.semantics { error(msg) }) }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                trailingIcon = if (form.name.isNotEmpty()) {
                    { IconButton(onClick = { viewModel.update { f -> f.copy(name = "") } }) { Icon(Icons.Filled.Clear, null) } }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (it.isFocused) nameWasFocused.value = true
                        else if (nameWasFocused.value) viewModel.touchName()
                    }
                    .semantics { form.nameError?.let { error(it) } },
            )

            // Source path
            SourcePathField(
                value = form.sourcePath,
                error = form.sourcePathError,
                onValueChange = { viewModel.update { f -> f.copy(sourcePath = it) } },
                onBlur = viewModel::touchSourcePath,
                onClear = viewModel::clearSourcePath,
                onChooseFolder = { folderLauncher.launch(null) },
            )

            // Remote
            RemoteDropdown(
                remotes = remotes,
                selected = form.remoteName,
                error = form.remoteNameError,
                onSelect = { v -> viewModel.update { f -> f.copy(remoteName = v) }; viewModel.touchRemoteName() },
                onNavigateToRemotes = onNavigateToRemotes,
            )

            OutlinedTextField(
                value = form.remotePath,
                onValueChange = { viewModel.update { f -> f.copy(remotePath = it) } },
                label = { Text(stringResource(R.string.sync_edit_field_remote_path)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = if (form.remotePath.isNotEmpty()) {
                    { IconButton(onClick = { viewModel.update { f -> f.copy(remotePath = "") } }) { Icon(Icons.Filled.Clear, null) } }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            // Direction
            DirectionSegmentedRow(
                selected = form.direction,
                directionError = form.directionError,
                onSelect = { viewModel.update { f -> f.copy(direction = it) } },
            )

            IntervalDropdown(
                selected = form.intervalMinutes,
                customMinutes = form.customIntervalMinutes,
                customIntervalError = form.customIntervalError,
                onSelect = { viewModel.update { f -> f.copy(intervalMinutes = it) } },
                onCustomMinutes = { viewModel.update { f -> f.copy(customIntervalMinutes = it) } },
            )

            // Wi-Fi only toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = form.wifiOnly,
                        role = Role.Switch,
                        onValueChange = { v -> viewModel.update { f -> f.copy(wifiOnly = v) } },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sync_edit_field_wifi_only),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = form.wifiOnly, onCheckedChange = null)
            }

            // Advanced section
            AdvancedSection(form = form, viewModel = viewModel)

            Button(
                onClick = { viewModel.save(onBack) },
                enabled = form.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sync_edit_save)) }
        }
    }
}

@Composable
private fun SourcePathField(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onBlur: () -> Unit,
    onClear: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.sync_edit_field_source_path)) },
            placeholder = { Text(stringResource(R.string.sync_edit_field_source_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { msg ->
                { Text(msg, modifier = Modifier.semantics { error(msg) }) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            trailingIcon = if (value.isNotEmpty()) {
                { IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, null) } }
            } else null,
            modifier = Modifier.fillMaxWidth().semantics { error?.let { error(it) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChooseFolder) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(stringResource(R.string.sync_edit_source_choose_folder))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteDropdown(
    remotes: List<String>,
    selected: String,
    error: String?,
    onSelect: (String) -> Unit,
    onNavigateToRemotes: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { stringResource(R.string.sync_edit_field_remote_placeholder) },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sync_edit_field_remote)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = error?.let { msg ->
                { Text(msg, modifier = Modifier.semantics { error(msg) }) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .semantics { error?.let { error(it) } },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (remotes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_edit_no_remotes_item)) },
                    onClick = { expanded = false; onNavigateToRemotes() },
                )
            }
            remotes.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(name); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionSegmentedRow(
    selected: SyncDirection,
    directionError: String?,
    onSelect: (SyncDirection) -> Unit,
) {
    val entries = SyncDirection.entries
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sync_edit_field_direction), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, dir ->
                SegmentedButton(
                    selected = selected == dir,
                    onClick = { onSelect(dir) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                    label = { Text(stringResource(directionLabelRes(dir))) },
                )
            }
        }
        Text(
            text = stringResource(directionHintRes(selected)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (directionError != null) {
            Text(
                text = directionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { error(directionError) },
            )
        }
    }
}

private fun directionLabelRes(dir: SyncDirection): Int = when (dir) {
    SyncDirection.UPLOAD -> R.string.sync_direction_upload
    SyncDirection.DOWNLOAD -> R.string.sync_direction_download
    SyncDirection.BISYNC -> R.string.sync_direction_bisync
}

private fun directionHintRes(dir: SyncDirection): Int = when (dir) {
    SyncDirection.UPLOAD -> R.string.sync_direction_hint_upload
    SyncDirection.DOWNLOAD -> R.string.sync_direction_hint_download
    SyncDirection.BISYNC -> R.string.sync_direction_hint_bisync
}

/**
 * Converts a SAF tree URI (content://...) to a real filesystem path that rclone can use.
 * Returns null if the path cannot be resolved or does not exist on disk.
 */
private fun resolveTreeUriToPath(uri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
    val colonIdx = docId.indexOf(':')
    if (colonIdx < 0) return null
    val volume = docId.substring(0, colonIdx)
    val relativePath = docId.substring(colonIdx + 1)
    val base = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    val resolved = if (relativePath.isEmpty()) File(base) else File(base, relativePath)
    // Reject path traversal: a malicious DocumentsProvider could return a docId with
    // ".." segments. Canonicalize (resolves "../" and symlinks) and require the result
    // to stay within the storage root.
    val canonical = runCatching { resolved.canonicalPath }.getOrNull() ?: return null
    val canonicalBase = runCatching { File(base).canonicalPath }.getOrNull() ?: return null
    if (canonical != canonicalBase && !canonical.startsWith("$canonicalBase/")) return null
    return if (resolved.exists()) canonical else null
}

