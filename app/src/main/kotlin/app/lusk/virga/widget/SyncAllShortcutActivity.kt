package app.lusk.virga.widget

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.lusk.virga.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * No-UI trampoline activity for the "Sync all" launcher shortcut (D7b).
 *
 * Enqueues a sync of all enabled tasks via WorkManager, shows a brief Toast,
 * and finishes immediately. WorkManager owns the durable work once enqueued,
 * so this activity can finish before the coroutine completes.
 *
 * SEC: exported=true is required because a launcher shortcut must be able to
 * start this activity from outside the app. It exposes no privileged Virga
 * internals; it only enqueues work that the user explicitly triggered via the
 * shortcut. excludeFromRecents keeps it out of the task switcher.
 */
class SyncAllShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enqueueSyncAll()
        finish()
    }

    private fun enqueueSyncAll() {
        Toast.makeText(this, getString(R.string.shortcut_sync_all_toast), Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        // Detached scope: this activity finishes before the coroutine completes.
        // WorkManager owns durable delivery once syncAllEnabled() enqueues the work.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                VirgaWidgetEntryPoint::class.java,
            )
            runCatching { entryPoint.syncScheduler().syncAllEnabled() }
        }
    }
}
