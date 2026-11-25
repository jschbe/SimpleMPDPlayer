package com.github.jschbe.simplempdplayer

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
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
import com.github.jschbe.simplempdplayer.ui.ServersApp
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme

class MainActivity : AppCompatActivity() {
    // The same lifecycle-owned model serves Compose and window callbacks, in either callback order.
    private val model: ServersViewModel by lazy {
        val app = application as PlayerApplication
        ViewModelProvider(this, viewModelFactory {
            initializer {
                ServersViewModel(app.serverSettings, networkPermission = {
                    Build.VERSION.SDK_INT < 37 || app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
                }, sharedRepository = app.repository, localStream = app.localStream, sleepTimer = app.sleepTimer)
            }
        })[ServersViewModel::class.java]
    }

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (!getSystemService(PowerManager::class.java).isInteractive) {
                    model.noteDeparture()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                    if (hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canInteract()) {
                        model.onVisibleReturn()
                    }
            }
        }
    }

    private fun canInteract(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus before resume is covered by ON_RESUME. Screen events also cover retained focus.
        if (hasFocus && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            model.onWindowFocusGained(canReturn = canInteract())
        }
    }

    override fun onStart() {
        super.onStart()
        (application as PlayerApplication).onInteractiveStart()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.noteDeparture()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenEvents)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
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
            val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                model.refreshNetworkPermission()
                if (!granted && Build.VERSION.SDK_INT >= 37 &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)
                ) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                model.onForeground(canReturn = hasWindowFocus() && canInteract())
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
                    onQueueAction = model::performQueue,
                    onRetryQueue = model::retryQueue,
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
                        if (Build.VERSION.SDK_INT >= 37 &&
                            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                        ) {
                            networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else model.reconnect()
                    },
                )
            }
        }
    }

    private fun currentLanguage() = AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
}
