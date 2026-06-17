package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.lusk.virga.core.common.model.RemoteOption
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
internal fun TypedOptionFields(
    options: List<RemoteOption>,
    values: MutableMap<String, String>,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    backendType: String? = null,
) {
    if (backendType != null) {
        BackendSubForm(type = backendType, values = values)
    }

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
                isError = opt.required && current.isBlank(),
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
                isError = opt.required && current.isBlank(),
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
                isError = opt.required && current.isBlank(),
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
            isError = opt.required && current.isBlank(),
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
