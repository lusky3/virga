package app.lusk.virga.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Handles ACTION_SEND and ACTION_SEND_MULTIPLE share intents. Displays a small
 * Compose UI for the user to pick a destination remote and upload shared file(s).
 * Independent from [app.lusk.virga.MainActivity] — does not touch the nav graph.
 */
@AndroidEntryPoint
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        setContent {
            VirgaTheme {
                val viewModel: ShareReceiverViewModel = hiltViewModel()
                viewModel.setUris(uris)
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ShareReceiverScreen(
                    state = state,
                    callbacks = ShareReceiverCallbacks(
                        onRemoteSelected = viewModel::selectRemote,
                        onDestPathChanged = viewModel::setDestPath,
                        onUpload = viewModel::upload,
                        onDismiss = ::finish,
                    ),
                )
            }
        }
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull()
                    ?: emptyList()
            }
            else -> emptyList()
        }
    }
}
