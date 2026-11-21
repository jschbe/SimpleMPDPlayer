package com.github.jschbe.simplempdplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.github.jschbe.simplempdplayer.data.ServerSettingsStore
import com.github.jschbe.simplempdplayer.data.serverDataStore
import com.github.jschbe.simplempdplayer.ui.ServersApp
import com.github.jschbe.simplempdplayer.ui.theme.SimpleMPDPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = applicationContext
            val factory = remember {
                viewModelFactory {
                    initializer {
                        ServersViewModel(ServerSettingsStore(context.serverDataStore), networkPermission = {
                            Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
                        })
                    }
                }
            }
            val model: ServersViewModel = viewModel(factory = factory)
            val state = model.state.collectAsStateWithLifecycle().value
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
                            toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
                        }
                    }
                } finally { toast?.cancel() }
            }
            val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                model.refreshNetworkPermission()
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshNetworkPermission() }
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
                    onLoadBrowser = model::loadBrowser,
                    onBrowse = model::browse,
                    onEnqueue = model::enqueue,
                    onTest = model::test,
                    onReorder = model::reorder,
                    onTheme = model::setTheme,
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
}
