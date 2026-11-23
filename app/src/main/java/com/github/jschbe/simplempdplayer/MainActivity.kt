package com.github.jschbe.simplempdplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import com.github.jschbe.simplempdplayer.ui.AppLanguage
import com.github.jschbe.simplempdplayer.data.ServerSettingsStore
import com.github.jschbe.simplempdplayer.data.serverDataStore
import com.github.jschbe.simplempdplayer.ui.ServersApp
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme

class MainActivity : AppCompatActivity() {
    // The same lifecycle-owned model serves Compose and window callbacks, in either callback order.
    private val model: ServersViewModel by lazy {
        val app = application as PlayerApplication
        ViewModelProvider(this, viewModelFactory {
            initializer {
                ServersViewModel(ServerSettingsStore(app.serverDataStore), networkPermission = {
                    Build.VERSION.SDK_INT < 37 || app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
                }, sharedRepository = app.repository, localStream = app.localStream, sleepTimer = app.sleepTimer)
            }
        })[ServersViewModel::class.java]
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus can precede resume. ON_RESUME checks independently, so neither order loses a check.
        if (hasFocus && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.onWindowFocusGained()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as PlayerApplication).streamBackend.refreshLanguage(resources.configuration)
        enableEdgeToEdge()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            // Reading configuration makes external Android 13+ changes observable in Compose.
            val configuration = LocalConfiguration.current
            var language by remember(configuration) {
                mutableStateOf(currentLanguage())
            }
            LaunchedEffect(model) {
                var toast: Toast? = null
                try {
                    model.notices.collect { notice ->
                        if (model.isCurrentNotice(notice)) {
                            toast?.cancel()
                            val text = when (notice.message) {
                                NoticeMessage.MPD_AVAILABLE -> R.string.mpd_available
                                NoticeMessage.MPD_UNAVAILABLE -> R.string.mpd_unavailable
                                NoticeMessage.CONNECTED -> R.string.connected
                                NoticeMessage.CONNECTION_FAILED -> R.string.connection_failed
                            }
                            toast = Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).also { it.show() }
                        }
                    }
                } finally { toast?.cancel() }
            }
            val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                model.refreshNetworkPermission()
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                model.onForeground()
                // System -> explicit with the same effective locale need not recreate us.
                language = currentLanguage()
            }
            SimpleMPDPlayerTheme(mode = state.settings.theme) {
                ServersApp(
                    state = state,
                    onAdd = { model.openEditor() },
                    onEdit = model::openEditor,
                    onDismissEditor = model::dismissEditor,
                    onSave = model::save,
                    onDelete = model::delete,
                    onSelect = model::select,
                    onReconnect = model::reconnect,
                    onAction = model::perform,
                    onLocalListening = model::setLocalListening,
                    onSleepEnabled = model::setSleepEnabled,
                    onSleepMinutes = model::setSleepMinutes,
                    onSleepRetry = model::refreshSleepTimer,
                    onLoadBrowser = model::loadBrowser,
                    onBrowse = model::browse,
                    onEnqueue = model::enqueue,
                    onTest = model::test,
                    onReorder = model::reorder,
                    onTheme = model::setTheme,
                    language = language,
                    onLanguage = {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it.tag))
                        language = currentLanguage()
                    },
                    onTrackInfoExpanded = model::setTrackInfoExpanded,
                    onReload = model::reloadSettings,
                    onGrantNetworkAccess = {
                        if (Build.VERSION.SDK_INT >= 37) {
                            networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else model.reconnect()
                    },
                )
            }
        }
    }

    private fun currentLanguage() = AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
}
