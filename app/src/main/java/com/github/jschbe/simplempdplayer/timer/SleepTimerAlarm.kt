package com.github.jschbe.simplempdplayer.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.github.jschbe.simplempdplayer.BuildConfig
import com.github.jschbe.simplempdplayer.PlayerApplication
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidTimerClock(private val context: Context) : TimerClock {
    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun wallTime() = System.currentTimeMillis()
    override fun boot(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }.getOrNull()
}

class AndroidTimerScheduler(private val context: Context) : TimerScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun intent() = Intent(context, SleepTimerReceiver::class.java).setAction(ACTION)
    private fun existing() = PendingIntent.getBroadcast(context, REQUEST, intent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    override fun hasPendingIntent() = existing() != null
    override fun schedule(record: TimerRecord) {
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent().putExtra(TOKEN, record.token),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, requireNotNull(record.deadline), pending)
    }
    override fun cancel() {
        existing()?.let { alarms.cancel(it); it.cancel() }
    }
    companion object {
        const val ACTION = "com.github.jschbe.simplempdplayer.SLEEP_TIMER"
        const val TOKEN = "timer_token"
        private const val REQUEST = 5
    }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidTimerScheduler.ACTION) return
        val token = intent.getStringExtra(AndroidTimerScheduler.TOKEN) ?: return
        val result = goAsync()
        // No Activity, ExoPlayer or existing MPD socket is initialized by this path.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val timer = (context.applicationContext as PlayerApplication).sleepTimer
                if (!timer.deliverAlarm(token, 8_000) && BuildConfig.DEBUG) {
                    Log.d("SleepTimerReceiver", "Delivery budget expired")
                }
            } finally { result.finish() }
        }
    }
}

/** Cancellable platform DNS avoids an unbounded blocking JVM resolver in the short receiver. */
internal suspend fun resolveTimerHost(host: String): String {
    val normalized = host.removeSurrounding("[", "]")
    if (InetAddresses.isNumericAddress(normalized)) return normalized
    return suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        DnsResolver.getInstance().query(null, normalized, DnsResolver.FLAG_EMPTY, Executor { it.run() }, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (continuation.isActive) {
                        val address = answer.firstOrNull()?.hostAddress
                        if (address == null || rcode != 0) continuation.resumeWithException(java.net.UnknownHostException())
                        else continuation.resume(address)
                    }
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) continuation.resumeWithException(java.net.UnknownHostException())
                }
            })
    }
}
