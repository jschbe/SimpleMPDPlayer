package com.github.jschbe.simplempdplayer

import android.app.Application
import android.content.Context
import android.content.Intent
import com.github.jschbe.simplempdplayer.audio.LocalStreamController
import com.github.jschbe.simplempdplayer.audio.LocalStreamService
import com.github.jschbe.simplempdplayer.audio.StreamBackend
import com.github.jschbe.simplempdplayer.mpd.MpdRepository
import com.github.jschbe.simplempdplayer.data.*
import com.github.jschbe.simplempdplayer.timer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Media3 source exceptions may contain remote details; expose only our error categories.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        sleepTimer.observe(playbackScope)
    }

    // Shared while the process lives: background metadata must not depend on an Activity's collectors.
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sleepTimer: SleepTimerController by lazy {
        SleepTimerController(SleepTimerStore(sleepTimerDataStore), ServerSettingsStore(serverDataStore).settings,
            AndroidTimerClock(this), AndroidTimerScheduler(this), profileMutationLock,
            pause = {
                if (android.os.Build.VERSION.SDK_INT >= 37 &&
                    checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    TimerError.PERMISSION
                } else MpdRepository.pauseForTimer(it, ::resolveTimerHost)
            }, playback = {
                if (!repositoryDelegate.isInitialized()) null else repository.activeServer?.let { TimerPlayback(it, repository.state.value) }
            })
    }
    private val repositoryDelegate: Lazy<MpdRepository> = lazy {
        MpdRepository(playbackScope).also { repository ->
            com.github.jschbe.simplempdplayer.mpd.MpdNetworkObserver(this, repository::networkChanged).start()
            playbackScope.launch { repository.state.collect { sleepTimer.playbackChanged() } }
        }
    }
    val repository: MpdRepository get() = repositoryDelegate.value
    val streamBackend by lazy { AndroidStreamBackend(this) }
    val localStream by lazy {
        LocalStreamController(streamBackend).also { stream ->
            playbackScope.launch {
                repository.state.collect { stream.connectionChanged(repository.activeServer, it) }
            }
        }
    }
}

class AndroidStreamBackend(private val context: Context) : StreamBackend {
    private var service: LocalStreamService? = null
    fun attach(value: LocalStreamService) { service = value }
    fun detach(value: LocalStreamService) { if (service === value) service = null }
    fun refreshLanguage(configuration: android.content.res.Configuration) { service?.refreshLanguage(configuration) }
    override fun start(generation: Long) {
        context.startService(Intent(context, LocalStreamService::class.java).putExtra(LocalStreamService.GENERATION, generation))
    }
    override fun stop() {
        service?.releasePlayback()
        context.stopService(Intent(context, LocalStreamService::class.java))
    }
}
