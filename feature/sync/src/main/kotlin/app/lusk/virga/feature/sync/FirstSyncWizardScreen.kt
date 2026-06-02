package app.lusk.virga.feature.sync

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstSyncWizardScreen(
    onBack: () -> Unit,
    onNavigateToRemotes: () -> Unit,
    onFinished: (taskId: Long) -> Unit,
    viewModel: FirstSyncWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val remotes by viewModel.availableRemotes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val saveError = state.saveError
    LaunchedEffect(saveError) {
        if (saveError != null) snackbar.showSnackbar(saveError)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val path = if (Environment.isExternalStorageManager()) resolveTreeUriToPath(uri) else null
            viewModel.applySourcePath(path ?: uri.toString())
        }
    }

    var showLocalPicker by remember { mutableStateOf(false) }
    if (showLocalPicker) {
        LocalFolderPickerDialog(
            onDismiss = { showLocalPicker = false },
            onSelect = { path ->
                showLocalPicker = false
                viewModel.applySourcePath(path)
            },
        )
    }

    val stepCount = WizardStep.entries.size - 1 // exclude DONE
    val stepIndex = state.step.ordinal.coerceAtMost(stepCount - 1)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.step == WizardStep.INTRO) onBack()
                        else viewModel.goBack()
                    }) {
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
                .fillMaxSize(),
        ) {
            LinearProgressIndicator(
                progress = { (stepIndex + 1f) / stepCount },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(VirgaSpacing.md)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
            ) {
                when (state.step) {
                    WizardStep.INTRO -> IntroStep()
                    WizardStep.ACCOUNT -> AccountStep(
                        remotes = remotes,
                        selected = state.remoteName,
                        onSelect = viewModel::selectRemote,
                        onAddRemote = onNavigateToRemotes,
                    )
                    WizardStep.SOURCE -> SourceStep(
                        sourcePath = state.sourcePath,
                        onPickFolder = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                                Environment.isExternalStorageManager()
                            ) {
                                showLocalPicker = true
                            } else {
                                folderLauncher.launch(null)
                            }
                        },
                    )
                    WizardStep.DESTINATION -> DestinationStep(
                        remotePath = state.remotePath,
                        onRemotePathChange = viewModel::setRemotePath,
                    )
                    WizardStep.DIRECTION_NAME -> DirectionNameStep(
                        direction = state.direction,
                        taskName = state.taskName,
                        onDirectionChange = viewModel::setDirection,
                        onNameChange = viewModel::setTaskName,
                    )
                    WizardStep.DONE -> { /* navigation handled by save callback */ }
                }
            }

            if (state.step != WizardStep.DONE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step != WizardStep.INTRO) {
                        TextButton(onClick = { viewModel.goBack() }) {
                            Text(stringResource(R.string.wizard_back))
                        }
                    } else {
                        Spacer(Modifier)
                    }

                    val isLastStep = state.step == WizardStep.DIRECTION_NAME
                    val canAdvance = when (state.step) {
                        WizardStep.INTRO -> true
                        WizardStep.ACCOUNT -> state.canAdvanceAccount
                        WizardStep.SOURCE -> state.canAdvanceSource
                        WizardStep.DESTINATION -> state.canAdvanceDestination
                        WizardStep.DIRECTION_NAME -> state.canFinish
                        else -> false
                    }

                    Button(
                        onClick = {
                            if (isLastStep) viewModel.save { id -> onFinished(id) }
                            else viewModel.goNext()
                        },
                        enabled = canAdvance && !state.saving,
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = VirgaSpacing.sm),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Text(
                            if (isLastStep) stringResource(R.string.wizard_create)
                            else stringResource(R.string.wizard_next),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroStep() {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
        Text(
            text = stringResource(R.string.wizard_intro_heading),
            style = MaterialTheme.typography.headlineMedium,
        )
        VirgaCard {
            Row(
                modifier = Modifier.padding(VirgaSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.wizard_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.wizard_intro_steps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountStep(
    remotes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onAddRemote: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
        Text(
            text = stringResource(R.string.wizard_account_heading),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.wizard_account_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected.ifBlank { stringResource(R.string.sync_edit_field_remote_placeholder) },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sync_edit_field_remote)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                remotes.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = if (name == selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        onClick = { onSelect(name); expanded = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.wizard_account_add)) },
                    onClick = { expanded = false; onAddRemote() },
                )
            }
        }
        if (remotes.isEmpty()) {
            TextButton(onClick = onAddRemote) {
                Text(stringResource(R.string.wizard_account_add_cta))
            }
        }
    }
}

@Composable
private fun SourceStep(
    sourcePath: String,
    onPickFolder: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
        Text(
            text = stringResource(R.string.wizard_source_heading),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.wizard_source_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sourcePath.isNotBlank()) {
            VirgaCard {
                Text(
                    text = sourcePath,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(VirgaSpacing.md),
                )
            }
        }
        Button(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.padding(end = VirgaSpacing.sm),
            )
            Text(
                if (sourcePath.isBlank()) stringResource(R.string.wizard_source_choose)
                else stringResource(R.string.wizard_source_change),
            )
        }
    }
}

@Composable
private fun DestinationStep(
    remotePath: String,
    onRemotePathChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
        Text(
            text = stringResource(R.string.wizard_destination_heading),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.wizard_destination_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = remotePath,
            onValueChange = onRemotePathChange,
            label = { Text(stringResource(R.string.sync_edit_field_remote_path)) },
            placeholder = { Text(stringResource(R.string.wizard_destination_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DirectionNameStep(
    direction: SyncDirection,
    taskName: String,
    onDirectionChange: (SyncDirection) -> Unit,
    onNameChange: (String) -> Unit,
) {
    val entries = SyncDirection.entries
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
        Text(
            text = stringResource(R.string.wizard_direction_heading),
            style = MaterialTheme.typography.headlineMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs)) {
            Text(
                stringResource(R.string.sync_edit_field_direction),
                style = MaterialTheme.typography.labelLarge,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                entries.forEachIndexed { index, dir ->
                    SegmentedButton(
                        selected = direction == dir,
                        onClick = { onDirectionChange(dir) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                        label = { Text(stringResource(directionLabelRes(dir))) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = taskName,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.sync_edit_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
