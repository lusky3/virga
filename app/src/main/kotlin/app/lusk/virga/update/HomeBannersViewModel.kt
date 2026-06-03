package app.lusk.virga.update

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.BuildConfig
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.feature.sync.ChangelogInfo
import app.lusk.virga.feature.sync.UpdateBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of what the Home screen should display for update/changelog banners. */
data class HomeBannersState(
    val changelog: ChangelogInfo?,
    val update: UpdateBanner?,
)

@HiltViewModel
class HomeBannersViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    /** Raw update result from the checker; populated once in init. */
    private val _update = MutableStateFlow<UpdateBanner?>(null)

    /** Session-only flag — dismissing hides the banner until the next launch. */
    private val _updateDismissed = MutableStateFlow(false)

    val state: StateFlow<HomeBannersState> = combine(
        prefs.preferences,
        _update,
        _updateDismissed,
    ) { appPrefs, availableUpdate, updateDismissed ->
        val changelogInfo = if (BuildConfig.VERSION_CODE > appPrefs.lastSeenChangelogVersionCode) {
            releaseNotesFor(BuildConfig.VERSION_NAME)
                ?.let { ChangelogInfo(it.versionName, it.notes) }
        } else {
            null
        }
        val updateBanner = if (!updateDismissed) availableUpdate else null
        HomeBannersState(changelog = changelogInfo, update = updateBanner)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeBannersState(changelog = null, update = null),
    )

    init {
        viewModelScope.launch {
            _update.value = updateChecker.check()?.let { UpdateBanner(it.versionLabel) }
        }
    }

    fun dismissChangelog() {
        viewModelScope.launch {
            prefs.setLastSeenChangelogVersionCode(BuildConfig.VERSION_CODE)
        }
    }

    fun dismissUpdate() {
        _updateDismissed.value = true
    }

    fun startUpdate(activity: Activity) {
        updateChecker.startUpdate(activity)
    }
}
