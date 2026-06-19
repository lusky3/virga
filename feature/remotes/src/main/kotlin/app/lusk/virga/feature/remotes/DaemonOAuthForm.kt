package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Form shown when the user picks a non-bundled OAuth provider (bundled=false).
 * Lets them optionally provide their own client credentials, then starts the
 * daemon-mediated OAuth flow via [onConnect].
 *
 * When rclone asks for a token pasted from `rclone authorize` run on another
 * machine, [tokenPrompt] carries its instructions: the form switches to the
 * paste stage — instructions, a token field, and Submit/Cancel — and resumes
 * the flow via [onSubmitToken].
 *
 * When rclone asks for a required field with no usable default, [fieldPrompt]
 * is non-null: the form shows a labelled input field and resumes via [onSubmitFieldAnswer].
 *
 * [onUseDesktopAuth] starts a fresh paste-token flow (forcePasteToken=true), offered as a
 * secondary action in both the connect stage and the in-progress/AwaitingAuth stage so
 * the user can fall back when the on-device browser doesn't complete the redirect.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DaemonOAuthForm(
    providerName: String,
    /** UI-M2: gates the Connect button — a blank remote name would dead-end the flow. */
    nameUsable: Boolean,
    oauthInProgress: Boolean,
    tokenPrompt: String?,
    fieldPrompt: DaemonOAuthFieldPrompt? = null,
    onConnect: (clientId: String, clientSecret: String) -> Unit,
    onSubmitToken: (token: String) -> Unit,
    onSubmitFieldAnswer: (answer: String) -> Unit = {},
    onCancel: () -> Unit,
    /** Secondary action: restart with paste-token (forcePasteToken=true) as a fallback. */
    onUseDesktopAuth: (clientId: String, clientSecret: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // L1: clientId is benign, but the client SECRET and the pasted token are
    // sensitive — keep them in plain `remember` so they never land in the
    // saved-state Bundle (accept loss on process death). clientId stays saveable.
    var clientId by rememberSaveable { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        Text(
            text = stringResource(R.string.remotes_daemon_oauth_title, providerName),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.remotes_daemon_oauth_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text(stringResource(R.string.remotes_daemon_oauth_client_id)) },
            singleLine = true,
            enabled = !oauthInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text(stringResource(R.string.remotes_daemon_oauth_client_secret)) },
            singleLine = true,
            enabled = !oauthInProgress,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        when {
            // Field-input stage: rclone requires a value for a specific option
            // with no usable default. Show label, help, examples, and an input
            // field; Submit resumes the flow, Cancel aborts it.
            fieldPrompt != null -> {
                DaemonOAuthFieldInput(
                    prompt = fieldPrompt,
                    onSubmit = onSubmitFieldAnswer,
                    onCancel = onCancel,
                )
            }
            // Paste stage: rclone is waiting for the output of `rclone authorize`
            // run on another machine. Show its instructions verbatim plus a
            // token field; Submit resumes the flow, Cancel aborts it.
            tokenPrompt != null -> {
                Text(
                    text = tokenPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.remotes_daemon_oauth_paste_label)) },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm, Alignment.End),
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.remotes_daemon_oauth_cancel))
                    }
                    Button(
                        onClick = {
                            onSubmitToken(token.trim())
                            // L1: clear the pasted token from memory once submitted.
                            token = ""
                        },
                        enabled = token.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.remotes_daemon_oauth_paste_submit))
                    }
                }
            }
            oauthInProgress -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.remotes_daemon_oauth_waiting), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.remotes_daemon_oauth_cancel)) }
                }
                // Fallback: let the user switch to paste-token if the browser redirect
                // doesn't complete (e.g. deep-link not configured for the provider).
                TextButton(
                    onClick = { onUseDesktopAuth(clientId, clientSecret) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remotes_daemon_oauth_use_desktop))
                }
            }
            else -> {
                Button(
                    onClick = { onConnect(clientId, clientSecret) },
                    enabled = nameUsable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remotes_daemon_oauth_connect))
                }
                // Secondary action: paste-token fallback for users who prefer the
                // desktop-authorize path or whose provider doesn't redirect back on-device.
                TextButton(
                    onClick = { onUseDesktopAuth(clientId, clientSecret) },
                    enabled = nameUsable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remotes_daemon_oauth_use_desktop))
                }
            }
        }
    }
}

/**
 * Input section shown when rclone requires a field value with no usable default.
 * Extracted to keep [DaemonOAuthForm]'s composable body under the Lizard nloc limit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaemonOAuthFieldInput(
    prompt: DaemonOAuthFieldPrompt,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var fieldValue by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        Text(
            text = prompt.help.ifBlank { prompt.label },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            label = { Text(prompt.label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            visualTransformation =
                if (prompt.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (prompt.examples.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                prompt.examples.forEach { example ->
                    AssistChip(
                        onClick = { fieldValue = example },
                        label = { Text(example) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm, Alignment.End),
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.remotes_daemon_oauth_cancel))
            }
            Button(
                // Submit the raw value: isNotBlank() already blocks empty input, and
                // trimming would corrupt credentials where leading/trailing whitespace
                // is meaningful (tokens, secrets).
                onClick = { onSubmit(fieldValue); fieldValue = "" },
                enabled = fieldValue.isNotBlank(),
            ) {
                Text(stringResource(R.string.remotes_daemon_oauth_field_submit))
            }
        }
    }
}
