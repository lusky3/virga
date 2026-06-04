package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Wrapper sub-flow form for meta/wrapper backends (crypt, union, alias, combine,
 * chunker, compress, cache, hasher).
 *
 * - **crypt**: delegates to [CryptRemoteForm] (single remote + password fields).
 * - **union**: multi-select remote picker (chip-based).
 * - **others**: single remote picker + schema-driven option fields.
 */
@Composable
internal fun WrapperForm(
    wrapperType: String,
    existingRemotes: List<Remote>,
    schemaOptions: List<RemoteOption>?,
    /** Single remote selection (non-union, non-crypt wrappers). */
    selectedRemote: String,
    onRemoteSelected: (String) -> Unit,
    /** Multi-select for union upstreams. */
    selectedRemotes: Set<String>,
    onRemotesChanged: (Set<String>) -> Unit,
    /** Schema-driven option values for non-crypt wrappers. */
    typedValues: MutableMap<String, String>,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    // Crypt-specific fields (only used when wrapperType == "crypt")
    cryptBaseRemote: String,
    onCryptBaseRemoteSelected: (String) -> Unit,
    cryptBasePath: String,
    onCryptBasePathChange: (String) -> Unit,
    cryptPassword: String,
    onCryptPasswordChange: (String) -> Unit,
    cryptSalt: String,
    onCryptSaltChange: (String) -> Unit,
) {
    if (existingRemotes.isEmpty()) {
        Text(
            stringResource(R.string.remotes_wrapper_no_base_remotes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val isCrypt = wrapperType.equals("crypt", ignoreCase = true)
    val isUnion = wrapperType.equals("union", ignoreCase = true)

    when {
        isCrypt -> {
            CryptRemoteForm(
                existingRemotes = existingRemotes,
                selectedBaseRemote = cryptBaseRemote,
                onBaseRemoteSelected = onCryptBaseRemoteSelected,
                basePath = cryptBasePath,
                onBasePathChange = onCryptBasePathChange,
                password = cryptPassword,
                onPasswordChange = onCryptPasswordChange,
                salt = cryptSalt,
                onSaltChange = onCryptSaltChange,
            )
        }
        isUnion -> {
            UnionRemotePicker(
                existingRemotes = existingRemotes,
                selectedRemotes = selectedRemotes,
                onRemotesChanged = onRemotesChanged,
            )
            // Show additional schema options below the picker
            if (schemaOptions != null) {
                val filtered = schemaOptions.filter { it.name != "upstreams" }
                if (filtered.isNotEmpty()) {
                    TypedOptionFields(
                        options = filtered,
                        values = typedValues,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = onToggleAdvanced,
                    )
                }
            }
        }
        else -> {
            SingleRemotePicker(
                existingRemotes = existingRemotes,
                selectedRemote = selectedRemote,
                onRemoteSelected = onRemoteSelected,
            )
            // Show additional schema options (excluding 'remote' which we set ourselves)
            if (schemaOptions != null) {
                val filtered = schemaOptions.filter { it.name != "remote" }
                if (filtered.isNotEmpty()) {
                    TypedOptionFields(
                        options = filtered,
                        values = typedValues,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = onToggleAdvanced,
                    )
                }
            }
        }
    }
}

/**
 * Single remote picker dropdown for wrappers that wrap one remote
 * (alias, combine, chunker, compress, cache, hasher).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleRemotePicker(
    existingRemotes: List<Remote>,
    selectedRemote: String,
    onRemoteSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.remotes_wrapper_pick_base),
        style = MaterialTheme.typography.titleSmall,
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedRemote,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.remotes_wrapper_base_remote_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            existingRemotes.forEach { remote ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(remote.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                remote.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onRemoteSelected(remote.name)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * Multi-select chip picker for union upstreams.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnionRemotePicker(
    existingRemotes: List<Remote>,
    selectedRemotes: Set<String>,
    onRemotesChanged: (Set<String>) -> Unit,
) {
    Text(
        stringResource(R.string.remotes_wrapper_pick_upstreams),
        style = MaterialTheme.typography.titleSmall,
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        existingRemotes.forEach { remote ->
            FilterChip(
                selected = remote.name in selectedRemotes,
                onClick = {
                    val new = if (remote.name in selectedRemotes) {
                        selectedRemotes - remote.name
                    } else {
                        selectedRemotes + remote.name
                    }
                    onRemotesChanged(new)
                },
                label = { Text(remote.name) },
            )
        }
    }
}
