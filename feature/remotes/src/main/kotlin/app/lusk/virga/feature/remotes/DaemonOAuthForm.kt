package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
 */
@Composable
internal fun DaemonOAuthForm(
    providerName: String,
    /** UI-M2: gates the Connect button — a blank remote name would dead-end the flow. */
    nameUsable: Boolean,
    oauthInProgress: Boolean,
    tokenPrompt: String?,
    onConnect: (clientId: String, clientSecret: String) -> Unit,
    onSubmitToken: (token: String) -> Unit,
    onCancel: () -> Unit,
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
            }
            else -> {
                Button(
                    onClick = { onConnect(clientId, clientSecret) },
                    enabled = nameUsable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remotes_daemon_oauth_connect))
                }
            }
        }
    }
}
