package app.lusk.virga.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lusk.virga.R

/**
 * Full-screen lock gate shown when [AppLockViewModel.locked] is true.
 *
 * On first composition a [LaunchedEffect] fires [onUnlock] automatically so the
 * biometric prompt appears immediately without requiring a button tap. The button
 * lets the user re-trigger the prompt if they dismiss or cancel it.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    // Auto-prompt on first show.
    LaunchedEffect(Unit) { onUnlock() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val lockIconDesc = stringResource(R.string.lock_screen_icon_desc)
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = lockIconDesc,
                modifier = Modifier
                    .size(64.dp)
                    .semantics { contentDescription = lockIconDesc },
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.lock_screen_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.lock_screen_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onUnlock) {
                Text(stringResource(R.string.lock_unlock_button))
            }
        }
    }
}
