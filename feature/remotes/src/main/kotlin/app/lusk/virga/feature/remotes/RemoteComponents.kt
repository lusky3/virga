package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.lusk.virga.core.designsystem.component.VirgaCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.rclone.oauth.OAuthProvider

/** Curated list of common rclone backend types with friendly display names. */
internal val RcloneBackendTypes: List<Pair<String, String>> = listOf(
    "drive"    to "Google Drive",
    "onedrive" to "OneDrive",
    "dropbox"  to "Dropbox",
    "s3"       to "Amazon S3 / compatible",
    "b2"       to "Backblaze B2",
    "sftp"     to "SFTP",
    "ftp"      to "FTP",
    "webdav"   to "WebDAV",
    "mega"     to "MEGA",
    "pcloud"   to "pCloud",
    "box"      to "Box",
    "gphotos"  to "Google Photos",
    "crypt"    to "Encrypted",
    "local"    to "Local disk",
)

@Composable
internal fun RemoteCard(
    remote: Remote,
    onOpenBrowser: () -> Unit,
    onCreateTask: (String) -> Unit,
    onDelete: () -> Unit,
    quota: RemoteQuota? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    VirgaCard(onClick = onOpenBrowser) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    remote.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(remote.type, style = MaterialTheme.typography.bodySmall)
                RemoteQuotaRow(quota)
            }

            // Overflow menu
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.remotes_card_cd_overflow),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remotes_card_menu_browse)) },
                    onClick = { menuExpanded = false; onOpenBrowser() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remotes_card_menu_new_task)) },
                    onClick = { menuExpanded = false; onCreateTask(remote.name) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remotes_card_menu_delete)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

/** Shows a compact "used of total" line with a progress bar. Renders nothing when quota is unavailable. */
@Composable
private fun RemoteQuotaRow(quota: RemoteQuota?) {
    val used = quota?.used ?: return
    val total = quota.total ?: return
    if (total <= 0L) return

    val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
    val label = stringResource(
        R.string.remotes_quota_used_of_total,
        formatFileSize(used),
        formatFileSize(total),
    )
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/**
 * [AddRemoteDialog] manual section: renders typed fields for each [RemoteOption] in
 * [options], or falls back to the freeform textarea when [options] is null.
 *
 * Collecting the typed values: each option name maps to a mutable state string;
 * booleans are stored as "true"/"false". On confirm, the map is serialised into a
 * "key=value\n..." string so it flows through the unchanged [onManualConfirm] path.
 *
 * NOTE: The crypt-wrapping wizard (pick base remote + passwords → create a crypt:
 * remote) is intentionally deferred.  The "crypt" backend type is left in
 * [RcloneBackendTypes] and falls through to normal typed / freeform handling.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AddRemoteDialog(
    oauthProviders: List<OAuthProvider>,
    error: String?,
    customClientIds: Map<String, String>,
    /**
     * Called once when the dialog opens so the VM can start loading provider schema.
     * Safe to call repeatedly — the VM guards against double-loading.
     */
    onEnsureProviders: () -> Unit,
    /**
     * Returns the non-advanced option list for a backend type, or null when the
     * schema is not yet loaded or the type is unknown → fall back to freeform.
     */
    optionsForBackend: (String) -> List<RemoteOption>?,
    /**
     * The loaded provider schema (null until loaded). Passed in only so the typed
     * fields recompute when the schema arrives AFTER the user already picked a
     * type — [optionsForBackend] reads a non-observable StateFlow.value, so we
     * key the lookup on this Compose-observable value.
     */
    providersLoaded: List<RemoteProvider>?,
    onDismiss: () -> Unit,
    onManualConfirm: (name: String, type: String, params: String) -> Unit,
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
) {
    LaunchedEffect(Unit) { onEnsureProviders() }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var params by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    val filteredBackends = remember(type) {
        val q = type.trim().lowercase()
        if (q.isEmpty()) RcloneBackendTypes
        else RcloneBackendTypes.filter { (id, label) ->
            id.contains(q) || label.lowercase().contains(q)
        }
    }

    // Typed field values keyed by option name. Reset when the backend type changes.
    val typedValues = remember { mutableStateMapOf<String, String>() }
    var showAdvanced by remember { mutableStateOf(false) }

    // Recompute which options to render whenever type changes.
    val schemaOptions: List<RemoteOption>? = remember(type, providersLoaded) { optionsForBackend(type.trim()) }

    // Reset typed values when the user picks a different backend type.
    LaunchedEffect(type) {
        typedValues.clear()
        showAdvanced = false
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.remotes_add_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.remotes_add_field_name)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.remotes_add_sign_in_with),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                oauthProviders.forEach { provider ->
                    val custom = provider.id in customClientIds
                    AssistChip(
                        onClick = { onOAuth(provider, name) },
                        enabled = name.isNotBlank(),
                        label = {
                            Text(
                                if (custom) stringResource(R.string.remotes_byo_chip_custom, provider.displayName)
                                else provider.displayName,
                            )
                        },
                    )
                }
            }

            ByoKeysSection(
                providers = oauthProviders,
                customClientIds = customClientIds,
                onSaveClientId = onSaveClientId,
                onClearClientId = onClearClientId,
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.remotes_add_or_manual),
                style = MaterialTheme.typography.labelLarge,
            )

            // Searchable / editable dropdown for backend type
            ExposedDropdownMenuBox(
                expanded = typeMenuExpanded,
                onExpandedChange = { typeMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it; typeMenuExpanded = true },
                    label = { Text(stringResource(R.string.remotes_add_field_type)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                )
                if (filteredBackends.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        filteredBackends.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(id, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    type = id
                                    typeMenuExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }

            // Schema-driven typed fields when available; freeform fallback otherwise.
            if (schemaOptions != null) {
                TypedOptionFields(
                    options = schemaOptions,
                    values = typedValues,
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                )
            } else {
                OutlinedTextField(
                    value = params,
                    onValueChange = { params = it },
                    label = { Text(stringResource(R.string.remotes_add_field_params)) },
                    placeholder = { Text(stringResource(R.string.remotes_add_params_placeholder)) },
                    supportingText = { Text(stringResource(R.string.remotes_add_params_supporting)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.remotes_add_cancel)) }
                TextButton(
                    onClick = {
                        val paramsText = if (schemaOptions != null) {
                            // Serialise typed values to the same "key=value\n..." format the
                            // VM's addRemote() parser already understands — no persistence change.
                            typedValues.entries
                                .filter { (_, v) -> v.isNotBlank() }
                                .joinToString("\n") { (k, v) -> "$k=$v" }
                        } else {
                            params
                        }
                        onManualConfirm(name, type, paramsText)
                    },
                    enabled = name.isNotBlank() && type.isNotBlank(),
                ) { Text(stringResource(R.string.remotes_add_create)) }
            }
        }
    }
}

/**
 * Renders a column of typed input fields for [options]. Non-advanced options are
 * always shown; advanced options appear after the "Show advanced options" expander.
 *
 * Field type mapping (rclone type → Compose widget):
 *  - "bool"                       → Switch in a labelled Row
 *  - "int" | "SizeSuffix" | "Duration" → OutlinedTextField with number keyboard
 *  - isPassword == true           → OutlinedTextField with PasswordVisualTransformation
 *  - options with Examples list   → ExposedDropdownMenu (free-text + suggestions)
 *  - everything else              → plain OutlinedTextField
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypedOptionFields(
    options: List<RemoteOption>,
    values: MutableMap<String, String>,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    val normalOptions = options.filter { !it.advanced }
    val advancedOptions = options.filter { it.advanced }

    normalOptions.forEach { opt ->
        OptionField(opt = opt, values = values)
    }

    if (advancedOptions.isNotEmpty()) {
        TextButton(onClick = onToggleAdvanced) {
            Text(
                if (showAdvanced) stringResource(R.string.remotes_add_hide_advanced)
                else stringResource(R.string.remotes_add_show_advanced),
            )
        }
        if (showAdvanced) {
            advancedOptions.forEach { opt ->
                OptionField(opt = opt, values = values)
            }
        }
    }
}

/**
 * Single typed input field for a [RemoteOption]. Chooses the widget based on the
 * option's type and metadata:
 *  - bool → Switch row
 *  - numeric types → number-keyboard OutlinedTextField
 *  - password → password OutlinedTextField
 *  - with Examples → dropdown suggestion OutlinedTextField
 *  - default → plain OutlinedTextField
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionField(
    opt: RemoteOption,
    values: MutableMap<String, String>,
) {
    val current = values[opt.name] ?: opt.default.orEmpty()
    val requiredSuffix = if (opt.required) stringResource(R.string.remotes_add_required_hint) else ""
    val labelText = opt.name + requiredSuffix

    when {
        opt.type == "bool" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(labelText, style = MaterialTheme.typography.bodyMedium)
                    if (opt.help.isNotBlank()) {
                        Text(
                            opt.help,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = current.equals("true", ignoreCase = true),
                    onCheckedChange = { values[opt.name] = it.toString() },
                )
            }
        }

        opt.examples.isNotEmpty() -> {
            ExamplesDropdownField(
                opt = opt,
                current = current,
                labelText = labelText,
                onValueChange = { values[opt.name] = it },
            )
        }

        opt.isPassword -> {
            OutlinedTextField(
                value = current,
                onValueChange = { values[opt.name] = it },
                label = { Text(labelText) },
                supportingText = if (opt.help.isNotBlank()) {
                    { Text(opt.help, style = MaterialTheme.typography.bodySmall) }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        opt.type in NUMERIC_TYPES -> {
            OutlinedTextField(
                value = current,
                onValueChange = { values[opt.name] = it },
                label = { Text(labelText) },
                supportingText = if (opt.help.isNotBlank()) {
                    { Text(opt.help, style = MaterialTheme.typography.bodySmall) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        else -> {
            OutlinedTextField(
                value = current,
                onValueChange = { values[opt.name] = it },
                label = { Text(labelText) },
                supportingText = if (opt.help.isNotBlank()) {
                    { Text(opt.help, style = MaterialTheme.typography.bodySmall) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * An editable field backed by a dropdown list of example values. The user can
 * either pick from the examples or type freely. Labels in the dropdown use the
 * example's help text when available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamplesDropdownField(
    opt: RemoteOption,
    current: String,
    labelText: String,
    onValueChange: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { menuExpanded = it },
    ) {
        OutlinedTextField(
            value = current,
            onValueChange = { onValueChange(it); menuExpanded = true },
            label = { Text(labelText) },
            supportingText = if (opt.help.isNotBlank()) {
                { Text(opt.help, style = MaterialTheme.typography.bodySmall) }
            } else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            opt.examples.forEach { (value, help) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                            if (help.isNotBlank()) {
                                Text(
                                    help,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onValueChange(value)
                        menuExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

private val NUMERIC_TYPES = setOf("int", "SizeSuffix", "Duration", "int64")

/**
 * Expandable "bring your own OAuth keys" section. The built-in client IDs are
 * shared across all installs and share one app's rate limits; power users paste
 * their own client ID here (Virga uses PKCE, so no client secret is needed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByoKeysSection(
    providers: List<OAuthProvider>,
    customClientIds: Map<String, String>,
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    // Field text tracks the selected provider's stored value; editing overrides it.
    var clientIdText by remember(selectedId) { mutableStateOf(customClientIds[selectedId].orEmpty()) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.remotes_byo_toggle))
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.remotes_byo_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { provider ->
                    FilterChip(
                        selected = provider.id == selectedId,
                        onClick = { selectedId = provider.id },
                        label = { Text(provider.displayName) },
                    )
                }
            }
            OutlinedTextField(
                value = clientIdText,
                onValueChange = { clientIdText = it },
                label = { Text(stringResource(R.string.remotes_byo_client_id)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onSaveClientId(selectedId, clientIdText) },
                    enabled = selectedId.isNotBlank(),
                ) { Text(stringResource(R.string.remotes_byo_save)) }
                if (selectedId in customClientIds) {
                    TextButton(onClick = { onClearClientId(selectedId); clientIdText = "" }) {
                        Text(stringResource(R.string.remotes_byo_clear))
                    }
                }
            }
        }
    }
}
