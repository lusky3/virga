package app.lusk.virga.feature.settings

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * Language / locale override section in Settings.
 *
 * Offers "System default" (null tag) plus each supported language. The list is
 * structured so adding a translation later only requires appending a [LanguageOption]
 * to [SUPPORTED_LANGUAGES] and adding the corresponding resources.
 *
 * [selectedTag] is the persisted BCP-47 tag or null for system default.
 * [onLanguageSelected] receives the chosen tag (null = system default) and should
 * both persist the pref AND apply the locale immediately to the running process.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSection(
    selectedTag: String?,
    onLanguageSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = SUPPORTED_LANGUAGES.firstOrNull { it.tag == selectedTag }?.labelRes
        ?: R.string.settings_language_system_default

    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_language))
    Text(
        stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(currentLabel),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_language_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // "System default" is always first.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_language_system_default)) },
                onClick = { onLanguageSelected(null); expanded = false },
            )
            SUPPORTED_LANGUAGES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = { onLanguageSelected(option.tag); expanded = false },
                )
            }
        }
    }
}

/** A BCP-47 language tag paired with its display-name string resource. */
private data class LanguageOption(val tag: String, val labelRes: Int)

/**
 * Languages the app ships translations for. Currently only English.
 * Append here (and add res/values-XX/) when a new translation is ready.
 */
private val SUPPORTED_LANGUAGES: List<LanguageOption> = listOf(
    LanguageOption(tag = "en", labelRes = R.string.settings_language_english),
)
